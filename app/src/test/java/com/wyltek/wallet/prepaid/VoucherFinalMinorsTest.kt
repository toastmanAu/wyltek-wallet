package com.wyltek.wallet.prepaid

import com.wyltek.wallet.core.model.LockScript
import com.wyltek.wallet.core.native.*
import com.wyltek.wallet.prepaid.db.*
import com.wyltek.wallet.prepaid.fakes.*
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Final review folded minors: LOW-3, LOW-5, LOW-6, LOW-7, code-minor-1. */
class VoucherFinalMinorsTest {
    private val uid = "04958caa5c5e80"
    private val form = VoucherForm("COF01", "Flat white", 450, "AUD", 1)
    private fun entry(kind: Int) = "02" + "%02x".format(kind) + "00".repeat(94)
    private fun TestScope.vh() = VoucherHarness(scope = this)

    // ---------------------------------------------------------------- LOW-3: spendCell checks the cell it spends

    private suspend fun voidWithCell(mutate: (com.wyltek.wallet.core.model.Utxo) -> com.wyltek.wallet.core.model.Utxo) {
        val h = VoucherHarness(); assertEquals(ActionOutcome.Done, h.svc.issue(FakeTag(uid), form))
        val v = h.vstore.voucherByUid(uid)!!
        h.chain.cells[v.outpoint] = mutate(h.chain.cells[v.outpoint]!!)
        val broadcasts = h.chain.broadcasts.size; val events = h.store.events.size
        val out = h.svc.void(v, null)
        assertTrue(out.toString(), out is ActionOutcome.Failed)
        assertEquals(v, h.vstore.voucher(v.voucherId))
        assertEquals(broadcasts, h.chain.broadcasts.size); assertEquals(events, h.store.events.size)
    }

    @Test fun a_cell_under_another_lock_is_never_spent() = runTest {
        voidWithCell { it.copy(lock = LockScript(it.lock.codeHash, it.lock.hashType, "0x" + "cd".repeat(20))) }
    }
    @Test fun a_cell_with_other_data_is_never_spent() = runTest { voidWithCell { it.copy(data = "0x4242563100000001") } }
    @Test fun a_cell_with_no_data_is_never_spent() = runTest { voidWithCell { it.copy(data = null) } }
    @Test fun a_cell_with_another_capacity_is_never_spent() = runTest { voidWithCell { it.copy(capacity = 7_000_000_000uL) } }

    @Test fun the_lock_check_ignores_hex_case() = runTest {
        val h = VoucherHarness(); assertEquals(ActionOutcome.Done, h.svc.issue(FakeTag(uid), form))
        val v = h.vstore.voucherByUid(uid)!!
        val c = h.chain.cells[v.outpoint]!!
        h.chain.cells[v.outpoint] = c.copy(lock = LockScript(c.lock.codeHash.uppercase().replace("0X", "0x"), c.lock.hashType, c.lock.args.uppercase().replace("0X", "0x")),
            data = c.data!!.uppercase().replace("0X", "0x"))
        assertEquals(ActionOutcome.Done, h.svc.void(v, null))
    }

    // ---------------------------------------------------------------- LOW-6: a failed write retries every copy

    private class FailingRedeems(private val inner: InMemoryVoucherStore) : VoucherStore by inner {
        override suspend fun applyRedeems(vouchers: List<VoucherEntity>, seen: List<JournalSeenEntity>) = throw IllegalStateException("disk full")
    }

    @Test fun when_the_redeem_write_fails_an_identical_second_entry_also_answers_retry() = runTest {
        val h = vh(); h.svc.issue(FakeTag(uid), form.copy(count = 3))
        val v = h.vstore.voucherByUid(uid)!!
        VoucherService(h.core, FailingRedeems(h.vstore))
        val r = RedeemFfi(0u, 3u, v.voucherId, 1u, "77".repeat(8), "ee".repeat(32))
        h.protocol.verifyResult = BatchResultFfi(listOf(JournalVerdict.ACCEPTED, JournalVerdict.ACCEPTED), emptyList(), listOf(r, r.copy(index = 1u)), emptyList())
        assertEquals(listOf("retry", "retry"), h.core.settle(entry(3) + entry(3), "t")!!.verdicts)
    }

    // ---------------------------------------------------------------- LOW-7: issue refuses a tag that holds a record

    private suspend fun issueRefusedOver(prepare: (VoucherHarness, FakeTag) -> Unit) {
        val h = VoucherHarness(); val tag = FakeTag(uid)
        prepare(h, tag)
        val before = tag.slot(com.wyltek.wallet.core.native.SlotPickFfi.A).copyOf()
        val out = h.svc.issue(tag, form)
        assertTrue(out.toString(), out is ActionOutcome.Failed)
        assertTrue(h.chain.broadcasts.isEmpty()); assertNull(h.vstore.voucherByUid(uid))
        assertArrayEquals(before, tag.slot(com.wyltek.wallet.core.native.SlotPickFfi.A))
    }
    private fun put(tag: FakeTag, raw: ByteArray) { for (p in 0 until 16) tag.pages[4 + p] = raw.copyOfRange(p * 4, p * 4 + 4) }

    @Test fun issue_refuses_a_tag_with_a_valid_card_record_even_without_an_index_row() = runTest {
        issueRefusedOver { h, tag -> put(tag, h.protocol.seal(h.core.kHex, uid, CardRecordInfo(0u, "ab".repeat(32), 100, 1u, h.core.merchantIdHex))) }
    }
    @Test fun issue_refuses_a_tag_with_a_voucher_record_the_index_does_not_know() = runTest {
        issueRefusedOver { h, tag -> put(tag, h.protocol.voucherSeal(h.core.kHex, uid, VoucherRecordInfo(0u, "c0".repeat(16), "5a".repeat(8), 450u, "AUD", 1u, 0u, h.core.merchantIdHex))) }
    }
    @Test fun issue_refuses_a_tag_with_a_voucher_record_that_fails_authentication() = runTest {
        issueRefusedOver { h, tag ->
            val raw = h.protocol.voucherSeal(h.core.kHex, uid, VoucherRecordInfo(0u, "c0".repeat(16), "5a".repeat(8), 450u, "AUD", 1u, 0u, "99".repeat(8)))
            raw[60] = (raw[60].toInt() xor 1).toByte(); put(tag, raw)
        }
    }

    // ---------------------------------------------------------------- code-minor-1: no post-settle launch when nothing can close

    @Test fun no_post_settle_close_is_launched_when_no_voucher_is_used_up() = runTest {
        val h = vh(); h.svc.issue(FakeTag(uid), form.copy(count = 3))
        assertNull(h.core.launchAfterSettle())
        val v = h.vstore.voucherByUid(uid)!!
        h.vstore.updateVoucher(v.copy(state = VOUCHER_USED, countLeft = 0))
        assertNotNull(h.core.launchAfterSettle())
    }

    // ---------------------------------------------------------------- LOW-5: the enrol arm expires

    @Test fun an_enrol_arm_lasts_sixty_seconds() {
        val a = EnrolArm("ring", armedAtMs = 1_000)
        assertTrue(a.liveAt(1_000)); assertTrue(a.liveAt(60_999))
        assertFalse(a.liveAt(61_000)); assertFalse(a.liveAt(500_000))
        assertEquals(60_000L, EnrolArm.TTL_MS)
    }
}
