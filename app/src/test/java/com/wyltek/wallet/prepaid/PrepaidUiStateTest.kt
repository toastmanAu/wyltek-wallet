package com.wyltek.wallet.prepaid

import com.wyltek.wallet.core.native.*
import com.wyltek.wallet.prepaid.db.*
import com.wyltek.wallet.prepaid.ui.PrepaidUiReducer
import com.wyltek.wallet.prepaid.ui.TapAction
import org.junit.Assert.assertEquals
import org.junit.Test

class PrepaidUiStateTest {
    private val slot = SlotResult(SlotPickFfi.A, null, SlotReason.NO_VALID_SLOT, null)
    private fun card(state: String) = PrepaidCardEntity("r", "u", null, "x:0", "x:0", 100, 0, 0, state, issuedAt = 0, updatedAt = 0)
    private val rec = CardRecordInfo(0u, "r", 100, 0u, "m")

    @Test fun actions_follow_the_classification_table() {
        assertEquals(listOf(TapAction.ISSUE), PrepaidUiReducer.tapActions(TapClass.Blank("u"), true))
        assertEquals(listOf(TapAction.RESUME), PrepaidUiReducer.tapActions(TapClass.Resume(card(CARD_ACTIVE), slot), true))
        assertEquals(listOf(TapAction.REPAIR, TapAction.VOID, TapAction.CASH_OUT), PrepaidUiReducer.tapActions(TapClass.Damaged(card(CARD_ACTIVE), "x"), true))
        assertEquals(listOf(TapAction.LOAD, TapAction.REVOKE, TapAction.CASH_OUT, TapAction.VOID), PrepaidUiReducer.tapActions(TapClass.Known(card(CARD_ACTIVE), rec, slot), true))
        assertEquals(listOf(TapAction.REINSTATE, TapAction.CASH_OUT, TapAction.VOID), PrepaidUiReducer.tapActions(TapClass.Known(card(CARD_REVOKED), rec, slot), true))
        assertEquals(emptyList<TapAction>(), PrepaidUiReducer.tapActions(TapClass.Known(card(CARD_CLOSED), rec, slot), true))
        assertEquals(listOf(TapAction.ADOPT), PrepaidUiReducer.tapActions(TapClass.Bench(rec, slot), true))
        assertEquals(emptyList<TapAction>(), PrepaidUiReducer.tapActions(TapClass.Refused("x"), true))
        assertEquals(emptyList<TapAction>(), PrepaidUiReducer.tapActions(null, false))
    }
}
