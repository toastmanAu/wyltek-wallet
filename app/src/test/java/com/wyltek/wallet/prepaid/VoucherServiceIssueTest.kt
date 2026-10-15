package com.wyltek.wallet.prepaid

import com.wyltek.wallet.core.native.SlotPickFfi
import com.wyltek.wallet.prepaid.db.*
import com.wyltek.wallet.prepaid.fakes.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class VoucherServiceIssueTest {
    private val lock = VOUCHER_TEST_LOCK
    private val uid = "04958caa5c5e80"
    private val form = VoucherForm(" cof01 ", "Flat white", 450, "AUD", 1)
    private fun H() = VoucherHarness()

    @Test fun issue_creates_a_69_ckb_bbv1_cell_writes_the_tag_and_logs_phases() = runTest {
        val h = H(); val tag = FakeTag(uid)
        assertTrue(h.svc.classify(tag) is TapClass.Blank)
        assertEquals(ActionOutcome.Done, h.svc.issue(tag, form))
        val v = h.vstore.voucherByUid(uid)!!
        assertEquals(VOUCHER_ACTIVE, v.state); assertEquals("COF01", v.productCode); assertEquals(1, v.countLeft); assertNull(v.pendingTxHash)
        val cell = h.chain.cellAt(v.outpoint)!!
        assertEquals(VoucherService.VOUCHER_CELL_SHANNONS, cell.capacity); assertEquals(VoucherService.VOUCHER_CELL_DATA, cell.data)
        val slot = h.protocol.voucherSelectSlot("", uid, tag.readSlots())
        assertEquals(SlotPickFfi.A, slot.pick); assertEquals(v.voucherId, slot.record!!.voucherIdHex); assertEquals(h.protocol.productHash("COF01"), slot.record!!.productHashHex)
        assertEquals(listOf(PHASE_REQUESTED, PHASE_BROADCAST, PHASE_COMMITTED, PHASE_WRITTEN, PHASE_DONE), h.store.phases("a1"))
        assertTrue(h.store.events.all { it.kind == KIND_VOUCHER_ISSUE && it.reserveId == v.voucherId })
        assertTrue(h.svc.classify(tag) is TapClass.Voucher)
    }

    @Test fun torn_write_awaits_the_tag_and_resume_finishes_it() = runTest {
        val h = H(); val tag = FakeTag(uid); tag.failAfterPages = 3
        val out = h.svc.issue(tag, form)
        assertTrue(out is ActionOutcome.AwaitingTag)
        val v = h.vstore.voucherByUid(uid)!!
        assertTrue(v.pendingCommitted); assertEquals(PHASE_FAILED, h.store.phases("a1").last())
        tag.present = true
        assertTrue(h.svc.classify(tag) is TapClass.VoucherResume)
        assertEquals(ActionOutcome.Done, h.svc.resume(tag, v))
        assertEquals(PHASE_DONE, h.store.phases("a1").last())
        assertNull(h.vstore.voucher(v.voucherId)!!.pendingTxHash)
    }

    @Test fun an_absent_tag_needs_the_tag_and_nothing_is_done() = runTest {
        val h = H(); val tag = FakeTag(uid); tag.present = false
        assertEquals(ActionOutcome.NeedsTag(uid), h.svc.issue(tag, form))
        assertTrue(h.chain.broadcasts.isEmpty()); assertNull(h.vstore.voucherByUid(uid)); assertTrue(h.store.events.isEmpty())
        tag.present = true
        assertEquals(ActionOutcome.Done, h.svc.issue(tag, form))                     // the re-tap runs it
    }

    @Test fun bad_form_inputs_are_refused_before_any_chain_work() = runTest {
        val h = H()
        for (f in listOf(form.copy(productCode = "  "), form.copy(count = 0), form.copy(count = 256), form.copy(currency = "aud"), form.copy(issuePriceMinor = -1), form.copy(issuePriceMinor = 4_294_967_296))) {
            assertTrue(f.toString(), h.svc.issue(FakeTag(uid), f) is ActionOutcome.Failed)
        }
        assertTrue(h.chain.broadcasts.isEmpty())
    }

    @Test fun a_tag_that_is_a_live_card_or_live_voucher_is_refused_and_a_card_cannot_be_issued_over_a_voucher() = runTest {
        val h = H(); val tag = FakeTag(uid)
        assertEquals(ActionOutcome.Done, h.svc.issue(tag, form))
        assertTrue(h.svc.issue(FakeTag(uid), form) is ActionOutcome.Failed)
        assertEquals(ActionOutcome.Failed("this tag holds a voucher"), h.core.issue(FakeTag(uid), 1000, null))
    }

    @Test fun the_voucher_cell_is_never_a_fee_input() = runTest {
        val h = H()
        assertEquals(ActionOutcome.Done, h.svc.issue(FakeTag(uid), form))
        val vcell = h.chain.cellAt(h.vstore.voucherByUid(uid)!!.outpoint)!!
        val planned = PrepaidTxPlanner.plan(emptyList(), h.chain.cellsByLock(lock), emptySet(), listOf(PrepaidTxPlanner.output(100uL * 100_000_000uL, lock)), lock)
        assertFalse(planned.inputs.any { PrepaidTxPlanner.outpointKey(it) == PrepaidTxPlanner.outpointKey(vcell) })
    }

    @Test fun recover_applies_a_commit_that_landed_while_the_page_was_closed() = runTest {
        val h = H(); h.chain.confirmAfterPolls = null
        val out = h.svc.issue(FakeTag(uid), form)
        assertTrue(out is ActionOutcome.StillConfirming)
        h.chain.confirmAfterPolls = 0
        assertEquals(1, h.svc.recoverPending())
        val v = h.vstore.voucherByUid(uid)!!
        assertEquals(VOUCHER_ACTIVE, v.state); assertTrue(v.pendingCommitted)        // written on the next tap
    }
}
