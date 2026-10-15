package com.wyltek.wallet.prepaid

import com.wyltek.wallet.core.native.SlotPickFfi
import com.wyltek.wallet.core.native.SlotReason
import com.wyltek.wallet.core.native.VoucherSlotResult
import com.wyltek.wallet.prepaid.db.VOUCHER_LIVE
import com.wyltek.wallet.prepaid.db.VoucherEntity

/** Vouchers spec §4: a tag that carries anything voucher-shaped, or whose UID has a live voucher, is classified here and
 *  never reaches the card classifier (which would call it Blank and offer to overwrite it). Pure. */
object VoucherClassifier {
    const val REASON_OTHER_MERCHANT = "another merchant's voucher"
    const val REASON_BAD_VERSION = "unsupported voucher version"
    const val REASON_AMBIGUOUS = "voucher state ambiguous"
    const val REASON_AUTH = "voucher failed authentication"
    const val REASON_UNKNOWN = "voucher not issued by this Vault"
    const val REASON_MOVED = "voucher record belongs to a different tag"
    const val REASON_NO_SLOT = "no valid voucher slot"

    fun classify(uidHex: String, vslot: VoucherSlotResult, byUid: VoucherEntity?, byId: VoucherEntity?, ourMerchantIdHex: String): TapClass? {
        if (byUid?.pendingTxHash != null) return TapClass.VoucherResume(byUid)
        val live = byUid?.takeIf { it.state in VOUCHER_LIVE }
        return when (vslot.pick) {
            SlotPickFfi.A, SlotPickFfi.B -> {
                val rec = vslot.record ?: return TapClass.Refused(REASON_NO_SLOT)
                when {
                    !rec.merchantIdHex.equals(ourMerchantIdHex, true) -> TapClass.Refused(REASON_OTHER_MERCHANT)
                    byId == null -> TapClass.Refused(REASON_UNKNOWN)
                    !byId.uid.equals(uidHex, true) -> TapClass.Refused(REASON_MOVED)
                    else -> TapClass.Voucher(byId, rec)
                }
            }
            SlotPickFfi.AMBIGUOUS -> live?.let { TapClass.VoucherDamaged(it, REASON_AMBIGUOUS) } ?: TapClass.Refused(REASON_AMBIGUOUS)
            SlotPickFfi.NONE -> when (vslot.reason) {
                SlotReason.BAD_VERSION -> TapClass.Refused(REASON_BAD_VERSION)
                SlotReason.MAC_FAIL -> when {
                    vslot.merchantHintHex != null && !vslot.merchantHintHex.equals(ourMerchantIdHex, true) -> TapClass.Refused(REASON_OTHER_MERCHANT)
                    live != null -> TapClass.VoucherDamaged(live, REASON_AUTH)
                    else -> TapClass.Refused(REASON_AUTH)
                }
                SlotReason.NO_VALID_SLOT -> live?.let { TapClass.VoucherDamaged(it, REASON_NO_SLOT) }
            }
        }
    }
}
