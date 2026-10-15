package com.wyltek.wallet.prepaid

import com.wyltek.wallet.core.native.*
import com.wyltek.wallet.prepaid.db.*
import org.junit.Assert.*
import org.junit.Test

class VoucherClassifierTest {
    private val me = "1122334455667788"; private val uid = "04958caa5c5e80"
    private fun v(state: String = VOUCHER_ACTIVE, pending: String? = null) = VoucherEntity("c0".repeat(16), uid, "COF01", "Flat white", "5a".repeat(8),
        450, "AUD", 1, 1, "0x" + "aa".repeat(32) + ":0", state, pendingTxHash = pending, issuedAt = 0, updatedAt = 0)
    private fun rec(merchant: String = me) = VoucherRecordInfo(0u, "c0".repeat(16), "5a".repeat(8), 450u, "AUD", 1u, 0u, merchant)
    private fun slot(pick: SlotPickFfi, r: VoucherRecordInfo? = null, reason: SlotReason = SlotReason.NO_VALID_SLOT, hint: String? = null) = VoucherSlotResult(pick, r, reason, hint)

    @Test fun our_voucher_classifies_as_voucher() =
        assertEquals(TapClass.Voucher(v(), rec()), VoucherClassifier.classify(uid, slot(SlotPickFfi.A, rec()), v(), v(), me))

    @Test fun a_voucher_tag_is_never_blank() {
        val cases = listOf(
            slot(SlotPickFfi.A, rec("99".repeat(8))) to null,                                    // another merchant's voucher
            slot(SlotPickFfi.NONE, reason = SlotReason.MAC_FAIL, hint = me) to null,              // ours, failing MAC, unknown UID
            slot(SlotPickFfi.NONE, reason = SlotReason.MAC_FAIL, hint = "99".repeat(8)) to null,
            slot(SlotPickFfi.NONE, reason = SlotReason.BAD_VERSION) to null,
            slot(SlotPickFfi.AMBIGUOUS) to null,
            slot(SlotPickFfi.A, rec()) to null,                                                   // valid but not in this Vault
            slot(SlotPickFfi.NONE) to v(),                                                        // wiped/torn tag of a live voucher
            slot(SlotPickFfi.NONE) to v(VOUCHER_ISSUING),                                         // torn mid-issue
            slot(SlotPickFfi.NONE) to v(VOUCHER_USED),                                            // wiped, already used
            slot(SlotPickFfi.AMBIGUOUS) to v(),                                                   // ambiguous with live row
        )
        for ((s, byUid) in cases) {
            val c = VoucherClassifier.classify(uid, s, byUid, null, me)
            assertNotNull("$s must not fall through to the card classifier", c)
            assertFalse(c is TapClass.Blank)
        }
    }

    @Test fun a_tag_with_no_voucher_and_no_live_voucher_row_falls_through() {
        assertNull(VoucherClassifier.classify(uid, slot(SlotPickFfi.NONE), null, null, me))
        assertNull(VoucherClassifier.classify(uid, slot(SlotPickFfi.NONE), v(VOUCHER_CLOSED), null, me))
        assertNull(VoucherClassifier.classify(uid, slot(SlotPickFfi.NONE), v(VOUCHER_VOID), null, me))
    }

    @Test fun pending_voucher_resumes_and_damaged_live_voucher_is_damaged() {
        assertEquals(TapClass.VoucherResume(v(pending = "0xab")), VoucherClassifier.classify(uid, slot(SlotPickFfi.NONE), v(pending = "0xab"), null, me))
        assertTrue(VoucherClassifier.classify(uid, slot(SlotPickFfi.NONE), v(), null, me) is TapClass.VoucherDamaged)
        assertTrue(VoucherClassifier.classify(uid, slot(SlotPickFfi.NONE, reason = SlotReason.MAC_FAIL, hint = me), v(), null, me) is TapClass.VoucherDamaged)
    }

    @Test fun record_moved_to_another_tag_is_refused() {
        val other = v().copy(uid = "04ebbb02cd2a81")
        assertEquals(TapClass.Refused(VoucherClassifier.REASON_MOVED), VoucherClassifier.classify(uid, slot(SlotPickFfi.A, rec()), null, other, me))
    }
}
