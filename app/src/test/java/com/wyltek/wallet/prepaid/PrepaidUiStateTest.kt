package com.wyltek.wallet.prepaid

import com.wyltek.wallet.core.native.*
import com.wyltek.wallet.prepaid.db.*
import com.wyltek.wallet.prepaid.ui.Armed
import com.wyltek.wallet.prepaid.ui.PrepaidUiReducer
import com.wyltek.wallet.prepaid.ui.PrepaidUiState
import com.wyltek.wallet.prepaid.ui.TapAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrepaidUiStateTest {
    private val slot = SlotResult(SlotPickFfi.A, null, SlotReason.NO_VALID_SLOT, null)
    private fun card(state: String) = PrepaidCardEntity("r", "u", null, "x:0", "x:0", 100, 0, 0, state, issuedAt = 0, updatedAt = 0)
    private val rec = CardRecordInfo(0u, "r", 100, 0u, "m")

    @Test fun actions_follow_the_classification_table() {
        assertEquals(listOf(TapAction.ISSUE, TapAction.ISSUE_VOUCHER), PrepaidUiReducer.tapActions(TapClass.Blank("u"), true))
        assertEquals(listOf(TapAction.RESUME), PrepaidUiReducer.tapActions(TapClass.Resume(card(CARD_ACTIVE), slot), true))
        assertEquals(listOf(TapAction.REPAIR, TapAction.VOID, TapAction.CASH_OUT), PrepaidUiReducer.tapActions(TapClass.Damaged(card(CARD_ACTIVE), "x"), true))
        assertEquals(listOf(TapAction.LOAD, TapAction.REVOKE, TapAction.CASH_OUT, TapAction.VOID), PrepaidUiReducer.tapActions(TapClass.Known(card(CARD_ACTIVE), rec, slot), true))
        assertEquals(listOf(TapAction.REINSTATE, TapAction.CASH_OUT, TapAction.VOID), PrepaidUiReducer.tapActions(TapClass.Known(card(CARD_REVOKED), rec, slot), true))
        assertEquals(emptyList<TapAction>(), PrepaidUiReducer.tapActions(TapClass.Known(card(CARD_CLOSED), rec, slot), true))
        assertEquals(listOf(TapAction.ADOPT), PrepaidUiReducer.tapActions(TapClass.Bench(rec, slot), true))
        assertEquals(emptyList<TapAction>(), PrepaidUiReducer.tapActions(TapClass.Refused("x"), true))
        assertEquals(emptyList<TapAction>(), PrepaidUiReducer.tapActions(null, false))
    }

    private fun voucher(state: String, pending: String? = null) = VoucherEntity("c0".repeat(16), "u", "COF01", "Flat white", "5a".repeat(8), 450, "AUD", 1, 1, "x:0", state, pendingTxHash = pending, issuedAt = 0, updatedAt = 0)
    private val vrec = VoucherRecordInfo(0u, "c0".repeat(16), "5a".repeat(8), 450u, "AUD", 1u, 0u, "m")

    @Test fun voucher_actions_follow_their_state() {
        assertEquals(listOf(TapAction.ISSUE, TapAction.ISSUE_VOUCHER), PrepaidUiReducer.tapActions(TapClass.Blank("u"), true))
        assertEquals(listOf(TapAction.VOID_VOUCHER), PrepaidUiReducer.tapActions(TapClass.Voucher(voucher(VOUCHER_ACTIVE), vrec), true))
        for (s in listOf(VOUCHER_VOID, VOUCHER_USED, VOUCHER_CLOSED)) assertEquals(listOf(TapAction.WIPE_VOUCHER), PrepaidUiReducer.tapActions(TapClass.Voucher(voucher(s), vrec), true))
        assertEquals(listOf(TapAction.RESUME_VOUCHER), PrepaidUiReducer.tapActions(TapClass.VoucherResume(voucher(VOUCHER_ACTIVE, "0xab")), false))
        assertEquals(listOf(TapAction.VOID_VOUCHER), PrepaidUiReducer.tapActions(TapClass.VoucherDamaged(voucher(VOUCHER_ACTIVE), "x"), true))
    }

    @Test fun without_a_tag_only_resume_remains() {
        assertEquals(listOf(TapAction.RESUME), PrepaidUiReducer.tapActions(TapClass.Resume(card(CARD_ACTIVE), slot), false))
        assertEquals(emptyList<TapAction>(), PrepaidUiReducer.tapActions(TapClass.Known(card(CARD_ACTIVE), rec, slot), false))
        assertEquals(emptyList<TapAction>(), PrepaidUiReducer.tapActions(TapClass.Blank("u"), false))
    }

    @Test fun scanned_address_is_stored_and_null_is_a_no_op() {
        val base = PrepaidUiState()
        assertEquals("ckt1abc", PrepaidUiReducer.consumeScannedAddress(base, "ckt1abc").scannedAddress)
        assertEquals(base, PrepaidUiReducer.consumeScannedAddress(base, null))
    }

    @Test fun accept_as_is_is_offered_only_after_the_terminal_refusal() {
        val resume = TapClass.Resume(card(CARD_ACTIVE), slot)
        val refusal = com.wyltek.wallet.prepaid.ActionOutcome.Failed("card was used at a terminal before the pending credit was confirmed; inspect the card")
        assertTrue(PrepaidUiReducer.offersAcceptAsIs(refusal, resume))
        assertFalse(PrepaidUiReducer.offersAcceptAsIs(com.wyltek.wallet.prepaid.ActionOutcome.Failed("busy"), resume))
        assertFalse(PrepaidUiReducer.offersAcceptAsIs(refusal, TapClass.Known(card(CARD_ACTIVE), rec, slot)))
        assertFalse(PrepaidUiReducer.offersAcceptAsIs(null, resume))
    }

    @Test fun an_armed_cash_out_waits_for_the_card_then_becomes_a_wipe_or_ends() {
        val co = Armed.CashOut("r", "u", "ckt1x")
        assertEquals(co, PrepaidUiReducer.armAfter(co, ActionOutcome.NeedsTag("r")))
        assertEquals(Armed.Wipe("r", "u"), PrepaidUiReducer.armAfter(co, ActionOutcome.WipePending("r")))
        assertNull(PrepaidUiReducer.armAfter(co, ActionOutcome.Done))
        assertNull(PrepaidUiReducer.armAfter(co, ActionOutcome.Failed("2 unsettled taps: sync the terminal first")))
        assertNull(PrepaidUiReducer.armAfter(co, ActionOutcome.StillConfirming("0xh")))   // the pending marker takes over
        val w = Armed.Wipe("r", "u")
        assertEquals(w, PrepaidUiReducer.armAfter(w, ActionOutcome.NeedsTag("r")))
        assertNull(PrepaidUiReducer.armAfter(w, ActionOutcome.Done))
        assertNull(PrepaidUiReducer.armAfter(w, ActionOutcome.Failed("not this card's record")))
    }

    @Test fun a_finished_attempt_cannot_rearm_after_the_user_dismissed_or_changed_it() {
        val co = Armed.CashOut("r", "u", "ckt1x")
        assertNull(PrepaidUiReducer.settleArm(current = null, ran = co, out = ActionOutcome.NeedsTag("r")))
        assertNull(PrepaidUiReducer.settleArm(current = null, ran = co, out = ActionOutcome.WipePending("r")))
        val other = Armed.CashOut("r", "u", "ckt1other")
        assertEquals(other, PrepaidUiReducer.settleArm(current = other, ran = co, out = ActionOutcome.Done))
        assertEquals(co, PrepaidUiReducer.settleArm(current = co, ran = co, out = ActionOutcome.NeedsTag("r")))
        assertEquals(Armed.Wipe("r", "u"), PrepaidUiReducer.settleArm(current = co, ran = co, out = ActionOutcome.WipePending("r")))
    }

    @Test fun busy_keeps_the_step_armed() {
        val co = Armed.CashOut("r", "u", "ckt1x")
        assertEquals(co, PrepaidUiReducer.armAfter(co, ActionOutcome.Failed("busy")))
    }

    @Test fun a_closed_card_with_its_record_still_on_the_tag_offers_a_wipe() {
        assertEquals(listOf(TapAction.WIPE), PrepaidUiReducer.tapActions(TapClass.Refused(TapClassifier.REASON_CLOSED), true))
        assertEquals(emptyList<TapAction>(), PrepaidUiReducer.tapActions(TapClass.Refused(TapClassifier.REASON_CLOSED), false))
        assertEquals(emptyList<TapAction>(), PrepaidUiReducer.tapActions(TapClass.Refused(TapClassifier.REASON_OTHER_MERCHANT), true))
    }

    @Test fun an_armed_step_runs_only_for_its_own_card() {
        val co = Armed.CashOut("r", "04ABCD", "ckt1x")
        assertTrue(PrepaidUiReducer.armedFor(co, "04abcd"))
        assertFalse(PrepaidUiReducer.armedFor(co, "04ffff"))
        assertFalse(PrepaidUiReducer.armedFor(null, "04abcd"))
    }

    @Test fun waiting_outcomes_mark_the_tag_gone() {
        assertTrue(PrepaidUiReducer.tagGone(ActionOutcome.NeedsTag("r")))
        assertTrue(PrepaidUiReducer.tagGone(ActionOutcome.WipePending("r")))
        assertTrue(PrepaidUiReducer.tagGone(ActionOutcome.AwaitingTag("r")))
        assertFalse(PrepaidUiReducer.tagGone(ActionOutcome.Done))
    }
}
