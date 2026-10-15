package com.wyltek.wallet.prepaid

import com.wyltek.wallet.core.chain.TxStatus
import com.wyltek.wallet.core.native.SlotPickFfi
import com.wyltek.wallet.prepaid.db.*
import com.wyltek.wallet.prepaid.fakes.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class VoucherServiceRetireTest {
    private val uid = "04958caa5c5e80"
    private val form = VoucherForm("COF01", "Flat white", 450, "AUD", 1)
    private suspend fun issued(): Pair<VoucherHarness, FakeTag> { val h = VoucherHarness(); val tag = FakeTag(uid); assertEquals(ActionOutcome.Done, h.svc.issue(tag, form)); return h to tag }

    @Test fun void_spends_the_cell_to_the_merchant_and_wipes_the_tag() = runTest {
        val (h, tag) = issued(); val v = h.vstore.voucherByUid(uid)!!
        assertEquals(ActionOutcome.Done, h.svc.void(v, tag))
        assertNull(h.chain.cellAt(v.outpoint))
        val after = h.vstore.voucher(v.voucherId)!!
        assertEquals(VOUCHER_VOID, after.state); assertEquals(0, after.countLeft); assertNull(after.pendingTxHash)
        assertTrue(tag.slot(SlotPickFfi.A).all { it == 0.toByte() })
        assertTrue(h.svc.classify(tag) is TapClass.Blank)
    }

    @Test fun a_missing_cell_never_closes_the_voucher() = runTest {
        val (h, _) = issued(); val v = h.vstore.voucherByUid(uid)!!
        h.chain.cells.remove(v.outpoint)                                  // indistinguishable from an RPC outage
        val out = h.svc.void(v, null)
        assertTrue(out is ActionOutcome.Failed)
        assertEquals(v, h.vstore.voucher(v.voucherId))                    // row untouched: still active, count intact
        assertTrue(h.store.events.none { it.kind == KIND_VOUCHER_VOID })
    }

    @Test fun close_used_spends_only_used_vouchers_and_marks_them_closed() = runTest {
        val (h, _) = issued(); val v = h.vstore.voucherByUid(uid)!!
        assertEquals(0, h.svc.closeUsed())
        h.vstore.updateVoucher(v.copy(state = VOUCHER_USED, countLeft = 0))
        assertEquals(1, h.svc.closeUsed())
        assertEquals(VOUCHER_CLOSED, h.vstore.voucher(v.voucherId)!!.state); assertNull(h.chain.cellAt(v.outpoint))
        assertTrue(h.store.events.any { it.kind == KIND_VOUCHER_CLOSE && it.phase == PHASE_DONE })
    }

    @Test fun a_timed_out_void_is_parked_and_recovered_on_commit() = runTest {
        val (h, _) = issued(); val v = h.vstore.voucherByUid(uid)!!
        h.chain.confirmAfterPolls = null
        assertTrue(h.svc.void(v, null) is ActionOutcome.StillConfirming)
        assertEquals(KIND_VOUCHER_VOID, h.vstore.voucher(v.voucherId)!!.pendingKind)
        h.chain.confirmAfterPolls = 0
        assertEquals(1, h.svc.recoverPending())
        assertEquals(VOUCHER_VOID, h.vstore.voucher(v.voucherId)!!.state)
    }

    @Test fun wipe_only_clears_this_vouchers_record_once_it_is_void() = runTest {
        val (h, tag) = issued(); val v = h.vstore.voucherByUid(uid)!!
        assertTrue(h.svc.wipe(tag, v) is ActionOutcome.Failed)             // still active
        assertEquals(ActionOutcome.Done, h.svc.void(v, null))              // tag absent: record stays
        assertTrue(h.svc.classify(tag) is TapClass.Voucher)
        assertEquals(ActionOutcome.Done, h.svc.wipe(tag, h.vstore.voucher(v.voucherId)!!))
        assertTrue(h.svc.classify(tag) is TapClass.Blank)
    }

    // ---- guards beyond the brief (dispatch: Review Focus 4 for close, pending-spend refusal, foreign-record wipe)

    @Test fun a_missing_cell_never_closes_a_used_voucher() = runTest {
        val (h, _) = issued(); val v = h.vstore.voucherByUid(uid)!!
        val used = v.copy(state = VOUCHER_USED, countLeft = 0); h.vstore.updateVoucher(used)
        h.chain.cells.remove(v.outpoint)
        assertEquals(0, h.svc.closeUsed())
        assertEquals(used, h.vstore.voucher(v.voucherId))
        assertTrue(h.store.events.none { it.kind == KIND_VOUCHER_CLOSE })
    }

    @Test fun void_refuses_a_voucher_with_a_pending_spend() = runTest {
        val (h, _) = issued(); val v = h.vstore.voucherByUid(uid)!!
        h.chain.confirmAfterPolls = null
        assertTrue(h.svc.void(v, null) is ActionOutcome.StillConfirming)
        val parked = h.vstore.voucher(v.voucherId)!!
        val again = h.svc.void(parked, null)
        assertTrue(again is ActionOutcome.Failed)
        assertEquals(parked, h.vstore.voucher(v.voucherId))
        assertEquals(1, h.store.events.count { it.kind == KIND_VOUCHER_VOID && it.phase == PHASE_REQUESTED })
    }

    @Test fun void_refuses_a_used_voucher_and_close_used_never_spends_an_active_one() = runTest {
        val (h, _) = issued(); val v = h.vstore.voucherByUid(uid)!!
        assertEquals(0, h.svc.closeUsed()); assertNotNull(h.chain.cellAt(v.outpoint))
        assertEquals(VOUCHER_ACTIVE, h.vstore.voucher(v.voucherId)!!.state)
        h.vstore.updateVoucher(v.copy(state = VOUCHER_USED, countLeft = 0))
        assertTrue(h.svc.void(h.vstore.voucher(v.voucherId)!!, null) is ActionOutcome.Failed)
        assertNotNull(h.chain.cellAt(v.outpoint))
    }

    @Test fun a_refused_void_leaves_the_row_as_it_was() = runTest {
        val (h, _) = issued(); val v = h.vstore.voucherByUid(uid)!!
        h.chain.refuseBroadcast = true
        assertTrue(h.svc.void(v, null) is ActionOutcome.Failed)
        assertEquals(v.copy(updatedAt = h.vstore.voucher(v.voucherId)!!.updatedAt), h.vstore.voucher(v.voucherId))
        assertNotNull(h.chain.cellAt(v.outpoint))
    }

    private fun foreignRecord(h: VoucherHarness, voucherId: String, merchant: String) = h.protocol.voucherSeal(h.core.kHex, uid,
        com.wyltek.wallet.core.native.VoucherRecordInfo(0u, voucherId, "0x" + "11".repeat(8), 450u, "AUD", 1u, 0u, merchant))

    @Test fun wipe_refuses_a_tag_carrying_a_different_voucher() = runTest {
        val (h, tag) = issued(); val v = h.vstore.voucherByUid(uid)!!
        assertEquals(ActionOutcome.Done, h.svc.void(v, null))
        val other = foreignRecord(h, "0x" + "77".repeat(16), h.core.merchantIdHex); tag.writeSlot(SlotPickFfi.A, other)
        assertTrue(h.svc.wipe(tag, h.vstore.voucher(v.voucherId)!!) is ActionOutcome.Failed)
        assertArrayEquals(other, tag.slot(SlotPickFfi.A))
    }

    @Test fun wipe_refuses_a_tag_carrying_another_merchants_record_for_this_id() = runTest {
        val (h, tag) = issued(); val v = h.vstore.voucherByUid(uid)!!
        assertEquals(ActionOutcome.Done, h.svc.void(v, null))
        val other = foreignRecord(h, v.voucherId, "0x" + "ee".repeat(8)); tag.writeSlot(SlotPickFfi.A, other)
        assertTrue(h.svc.wipe(tag, h.vstore.voucher(v.voucherId)!!) is ActionOutcome.Failed)
        assertArrayEquals(other, tag.slot(SlotPickFfi.A))
    }

    @Test fun a_void_does_not_wipe_a_tag_carrying_a_different_voucher() = runTest {
        val (h, tag) = issued(); val v = h.vstore.voucherByUid(uid)!!
        val other = foreignRecord(h, "0x" + "77".repeat(16), h.core.merchantIdHex); tag.writeSlot(SlotPickFfi.A, other)
        assertEquals(ActionOutcome.Done, h.svc.void(v, tag))
        assertEquals(VOUCHER_VOID, h.vstore.voucher(v.voucherId)!!.state)
        assertArrayEquals(other, tag.slot(SlotPickFfi.A))
        assertTrue(h.store.events.any { it.kind == KIND_VOUCHER_VOID && it.phase == PHASE_DONE && it.detail.contains("not wiped") })
    }
}
