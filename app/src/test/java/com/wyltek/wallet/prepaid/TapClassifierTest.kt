package com.wyltek.wallet.prepaid

import com.wyltek.wallet.core.native.CardRecordInfo
import com.wyltek.wallet.core.native.SlotPickFfi
import com.wyltek.wallet.core.native.SlotReason
import com.wyltek.wallet.core.native.SlotResult
import com.wyltek.wallet.prepaid.db.CARD_ACTIVE
import com.wyltek.wallet.prepaid.db.PrepaidCardEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TapClassifierTest {
    private val ours = "1122334455667788"
    private val uid = "04958caa5c5e80"
    private val rid = "a0".repeat(32)
    private fun card(uid: String = this.uid, pending: String? = null) = PrepaidCardEntity(reserveId = rid, uid = uid, firstOutpoint = "0x1:0",
        currentOutpoint = "0x1:0", balanceUnits = 100, counter = 1, settledCounter = 0, state = CARD_ACTIVE, pendingTxHash = pending,
        pendingUnits = if (pending != null) 50 else null, issuedAt = 0, updatedAt = 0)
    private fun rec(merchant: String = ours, reserve: String = rid) = CardRecordInfo(writerId = 0u, reserveIdHex = reserve, balanceUnits = 100, counter = 1u, merchantIdHex = merchant)
    private fun slot(pick: SlotPickFfi, record: CardRecordInfo? = null, reason: SlotReason = SlotReason.NO_VALID_SLOT, hint: String? = null) =
        SlotResult(pick = pick, record = record, reason = reason, merchantHintHex = hint)
    private fun go(s: SlotResult, byUid: PrepaidCardEntity? = null, byReserve: PrepaidCardEntity? = null) = TapClassifier.classify(uid, s, byUid, byReserve, ours)

    @Test fun `pending credit wins over everything`() {
        val c = card(pending = "0xabc")
        assertTrue(go(slot(SlotPickFfi.NONE), byUid = c) is TapClass.Resume)
        assertTrue(go(slot(SlotPickFfi.A, rec()), byUid = c, byReserve = c) is TapClass.Resume)
    }
    @Test fun `blank tag unknown uid is Blank`() { assertEquals(TapClass.Blank(uid), go(slot(SlotPickFfi.NONE))) }
    @Test fun `blank tag known uid is Damaged`() { assertTrue(go(slot(SlotPickFfi.NONE), byUid = card()) is TapClass.Damaged) }
    @Test fun `bad version is refused`() { assertEquals(TapClass.Refused(TapClassifier.REASON_BAD_VERSION), go(slot(SlotPickFfi.NONE, reason = SlotReason.BAD_VERSION))) }
    @Test fun `mac fail with another merchant hint is refused`() {
        assertEquals(TapClass.Refused(TapClassifier.REASON_OTHER_MERCHANT), go(slot(SlotPickFfi.NONE, reason = SlotReason.MAC_FAIL, hint = "ffffffffffffffff")))
    }
    @Test fun `mac fail with our hint is auth failure, damaged if known`() {
        assertEquals(TapClass.Refused(TapClassifier.REASON_AUTH), go(slot(SlotPickFfi.NONE, reason = SlotReason.MAC_FAIL, hint = ours)))
        assertTrue(go(slot(SlotPickFfi.NONE, reason = SlotReason.MAC_FAIL, hint = ours), byUid = card()) is TapClass.Damaged)
    }
    @Test fun `ambiguous is refused, damaged if known`() {
        assertEquals(TapClass.Refused(TapClassifier.REASON_AMBIGUOUS), go(slot(SlotPickFfi.AMBIGUOUS)))
        assertEquals(TapClassifier.REASON_AMBIGUOUS, (go(slot(SlotPickFfi.AMBIGUOUS), byUid = card()) as TapClass.Damaged).reason)
    }
    @Test fun `valid record known reserve is Known`() {
        val r = go(slot(SlotPickFfi.B, rec()), byUid = card(), byReserve = card())
        assertTrue(r is TapClass.Known); assertEquals(SlotPickFfi.B, (r as TapClass.Known).slot.pick)
    }
    @Test fun `valid record unknown reserve is Bench`() { assertTrue(go(slot(SlotPickFfi.A, rec())) is TapClass.Bench) }
    @Test fun `valid record other merchant is refused`() { assertEquals(TapClass.Refused(TapClassifier.REASON_OTHER_MERCHANT), go(slot(SlotPickFfi.A, rec(merchant = "ffffffffffffffff")))) }
    @Test fun `unformatted record is refused`() { assertEquals(TapClass.Refused(TapClassifier.REASON_UNFORMATTED), go(slot(SlotPickFfi.A, rec(reserve = "00".repeat(32))))) }
    @Test fun `reserve known under a different uid is refused`() {
        assertEquals(TapClass.Refused(TapClassifier.REASON_MOVED), go(slot(SlotPickFfi.A, rec()), byReserve = card(uid = "04ebbb02cd2a81")))
    }
    @Test fun `merchant id comparison is case-insensitive`() {
        assertTrue(go(slot(SlotPickFfi.A, rec(merchant = ours.uppercase())), byReserve = card()) is TapClass.Known)
    }
}
