package com.wyltek.wallet.prepaid

import com.wyltek.wallet.core.native.*
import com.wyltek.wallet.prepaid.db.*
import com.wyltek.wallet.prepaid.fakes.*
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Final review HIGH-1: resuming an issue must never reseal a tag a terminal has already redeemed. */
class VoucherResumeSafetyTest {
    private val uid = "04958caa5c5e80"
    private val form = VoucherForm("COF01", "Flat white", 450, "AUD", 1)
    private fun entry(kind: Int) = "02" + "%02x".format(kind) + "00".repeat(94)
    private fun TestScope.vh() = VoucherHarness(scope = this)

    /** Issue commits, slot A lands, the B zeroing tears: AwaitingTag with a committed issue marker. */
    private suspend fun tornIssue(h: VoucherHarness, tag: FakeTag, f: VoucherForm = form): VoucherEntity {
        tag.tearOnWriteCall = 2
        assertTrue(h.svc.issue(tag, f) is ActionOutcome.AwaitingTag)
        tag.present = true
        val v = h.vstore.voucherByUid(uid)!!
        assertEquals(KIND_VOUCHER_ISSUE, v.pendingKind); assertTrue(v.pendingCommitted); assertEquals(VOUCHER_ACTIVE, v.state)
        return v
    }

    /** What a terminal leaves on the tag after one redeem: counter [counter], [count] uses left, in slot B. */
    private fun redeemOnTag(h: VoucherHarness, tag: FakeTag, v: VoucherEntity, counter: UInt, count: UByte) {
        val raw = h.protocol.voucherSeal(h.core.kHex, uid, VoucherRecordInfo(1u, v.voucherId, v.productHash, v.issuePriceMinor.toUInt(),
            v.currency, count, counter, h.core.merchantIdHex))
        for (p in 0 until 16) tag.pages[20 + p] = raw.copyOfRange(p * 4, p * 4 + 4)
    }

    private fun settleOne(h: VoucherHarness, v: VoucherEntity) {
        h.protocol.verifyResult = BatchResultFfi(listOf(JournalVerdict.ACCEPTED), emptyList(),
            listOf(RedeemFfi(0u, 3u, v.voucherId, 1u, "ee".repeat(32))), emptyList())
    }

    @Test fun a_redeemed_tag_behind_a_committed_issue_marker_is_never_rewritten_and_the_cell_closes() = runTest {
        val h = vh(); val tag = FakeTag(uid)
        val v = tornIssue(h, tag)
        redeemOnTag(h, tag, v, 1u, 0u)                                     // the customer redeems at a POS
        settleOne(h, v)
        assertEquals(listOf("accepted"), h.core.settle(entry(3), "t")!!.verdicts)
        advanceUntilIdle()                                                // the post-settle close runs
        assertEquals(VOUCHER_CLOSED, h.vstore.voucher(v.voucherId)!!.state)
        assertNull(h.chain.cellAt(v.outpoint))
        // A later tap of the same tag: nothing may reseal it with counter 0 / full count.
        val writes = tag.pageWrites
        val out = h.svc.resume(tag, h.vstore.voucher(v.voucherId)!!)
        assertEquals(ActionOutcome.Done, out)
        assertEquals(writes, tag.pageWrites)
        assertEquals(1u, h.protocol.voucherSelectSlot("", uid, tag.readSlots()).record!!.counter)
        assertEquals(VOUCHER_CLOSED, h.vstore.voucher(v.voucherId)!!.state)
    }

    @Test fun the_same_scenario_with_the_close_failing_still_never_rewrites_the_tag() = runTest {
        val h = vh(); val tag = FakeTag(uid)
        val v = tornIssue(h, tag)
        redeemOnTag(h, tag, v, 1u, 0u)
        h.chain.cellAtThrowsFor += v.outpoint                             // the close cannot run yet
        settleOne(h, v)
        assertEquals(listOf("accepted"), h.core.settle(entry(3), "t")!!.verdicts)
        advanceUntilIdle()
        val used = h.vstore.voucher(v.voucherId)!!
        assertEquals(VOUCHER_USED, used.state)
        assertTrue(h.svc.classify(tag) is TapClass.VoucherResume)
        val writes = tag.pageWrites
        assertEquals(ActionOutcome.Done, h.svc.resume(tag, used))
        assertEquals(writes, tag.pageWrites)
        assertEquals(1u, h.protocol.voucherSelectSlot("", uid, tag.readSlots()).record!!.counter)
        assertEquals(VOUCHER_USED, h.vstore.voucher(v.voucherId)!!.state)
        h.chain.cellAtThrowsFor -= v.outpoint
        assertEquals(1, h.svc.closeUsed())
        assertEquals(VOUCHER_CLOSED, h.vstore.voucher(v.voucherId)!!.state)
    }

    @Test fun resume_finalises_without_writing_when_the_tag_carries_this_voucher_at_a_later_counter() = runTest {
        val h = vh(); val tag = FakeTag(uid)
        val v = tornIssue(h, tag, form.copy(count = 3))
        redeemOnTag(h, tag, v, 1u, 2u)                                     // redeemed at a POS, not yet settled
        val writes = tag.pageWrites
        assertEquals(ActionOutcome.Done, h.svc.resume(tag, v))
        assertEquals(writes, tag.pageWrites)
        assertEquals(1u, h.protocol.voucherSelectSlot("", uid, tag.readSlots()).record!!.counter)
        val after = h.vstore.voucher(v.voucherId)!!
        assertNull(after.pendingTxHash); assertEquals(VOUCHER_ACTIVE, after.state)
    }

    @Test fun resume_of_a_used_voucher_never_writes_and_never_reactivates_it() = runTest {
        val h = vh(); val tag = FakeTag(uid)
        val v = tornIssue(h, tag)
        // The row is USED with the committed issue marker still on it; the tag lost its record entirely.
        h.vstore.updateVoucher(h.vstore.voucher(v.voucherId)!!.copy(state = VOUCHER_USED, countLeft = 0))
        for (p in 4 until 36) tag.pages[p] = ByteArray(4)
        val writes = tag.pageWrites
        assertEquals(ActionOutcome.Done, h.svc.resume(tag, v))
        assertEquals(writes, tag.pageWrites)
        val after = h.vstore.voucher(v.voucherId)!!
        assertEquals(VOUCHER_USED, after.state); assertNull(after.pendingTxHash)
    }

    @Test fun finalise_never_moves_a_used_voucher_back_to_active() = runTest {
        val h = vh(); val tag = FakeTag(uid)
        val v = tornIssue(h, tag)                                          // slot A holds the issue record (counter 0)
        h.vstore.updateVoucher(h.vstore.voucher(v.voucherId)!!.copy(state = VOUCHER_USED, countLeft = 0))
        assertEquals(ActionOutcome.Done, h.svc.resume(tag, v))
        assertEquals(VOUCHER_USED, h.vstore.voucher(v.voucherId)!!.state)
    }

    @Test fun resume_of_an_active_voucher_with_uses_spent_never_writes() = runTest {
        val h = vh(); val tag = FakeTag(uid)
        val v = tornIssue(h, tag, form.copy(count = 3))
        h.vstore.updateVoucher(h.vstore.voucher(v.voucherId)!!.copy(countLeft = 2))
        for (p in 4 until 36) tag.pages[p] = ByteArray(4)
        val writes = tag.pageWrites
        assertEquals(ActionOutcome.Done, h.svc.resume(tag, v))
        assertEquals(writes, tag.pageWrites)
        assertEquals(2, h.vstore.voucher(v.voucherId)!!.countLeft)
    }

    @Test fun a_committed_issue_marker_does_not_stop_a_used_voucher_from_closing() = runTest {
        val h = vh(); val tag = FakeTag(uid)
        val v = tornIssue(h, tag)
        h.vstore.updateVoucher(h.vstore.voucher(v.voucherId)!!.copy(state = VOUCHER_USED, countLeft = 0))
        assertEquals(1, h.svc.closeUsed())
        assertEquals(VOUCHER_CLOSED, h.vstore.voucher(v.voucherId)!!.state)
        assertNull(h.chain.cellAt(v.outpoint))
    }

    @Test fun an_uncommitted_issue_still_blocks_close() = runTest {
        val h = vh(); val tag = FakeTag(uid)
        val v = tornIssue(h, tag)
        h.vstore.updateVoucher(h.vstore.voucher(v.voucherId)!!.copy(state = VOUCHER_USED, countLeft = 0, pendingCommitted = false))
        assertEquals(0, h.svc.closeUsed())
    }
}
