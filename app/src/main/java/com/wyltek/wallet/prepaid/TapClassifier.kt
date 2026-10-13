package com.wyltek.wallet.prepaid

import com.wyltek.wallet.core.native.CardRecordInfo
import com.wyltek.wallet.core.native.SlotPickFfi
import com.wyltek.wallet.core.native.SlotReason
import com.wyltek.wallet.core.native.SlotResult
import com.wyltek.wallet.prepaid.db.PrepaidCardEntity

sealed class TapClass {
    data class Resume(val card: PrepaidCardEntity, val slot: SlotResult) : TapClass()
    data class Blank(val uidHex: String) : TapClass()
    data class Damaged(val card: PrepaidCardEntity, val reason: String) : TapClass()
    data class Known(val card: PrepaidCardEntity, val record: CardRecordInfo, val slot: SlotResult) : TapClass()
    data class Bench(val record: CardRecordInfo, val slot: SlotResult) : TapClass()
    data class Refused(val reason: String) : TapClass()
}

/** D3 spec §5.1. Pure: the service supplies the two index lookups. Refusals never reach the audit log. */
object TapClassifier {
    const val REASON_OTHER_MERCHANT = "another merchant's card"
    const val REASON_BAD_VERSION = "unsupported card version"
    const val REASON_AMBIGUOUS = "card state ambiguous"
    const val REASON_UNFORMATTED = "unformatted card"
    const val REASON_AUTH = "card failed authentication"
    const val REASON_MOVED = "record belongs to a different tag in the index"
    const val REASON_NO_SLOT = "no valid slot"

    fun classify(
        uidHex: String,
        slot: SlotResult,
        cardByUid: PrepaidCardEntity?,
        cardByReserve: PrepaidCardEntity?,
        ourMerchantIdHex: String,
    ): TapClass {
        if (cardByUid?.pendingTxHash != null) return TapClass.Resume(cardByUid, slot)
        return when (slot.pick) {
            SlotPickFfi.AMBIGUOUS ->
                cardByUid?.let { TapClass.Damaged(it, REASON_AMBIGUOUS) } ?: TapClass.Refused(REASON_AMBIGUOUS)
            SlotPickFfi.NONE -> classifyNoSlot(uidHex, slot, cardByUid, ourMerchantIdHex)
            SlotPickFfi.A, SlotPickFfi.B -> classifyRecord(uidHex, slot, cardByReserve, ourMerchantIdHex)
        }
    }

    private fun classifyNoSlot(
        uidHex: String,
        slot: SlotResult,
        cardByUid: PrepaidCardEntity?,
        ourMerchantIdHex: String,
    ): TapClass = when (slot.reason) {
        SlotReason.BAD_VERSION -> TapClass.Refused(REASON_BAD_VERSION)
        SlotReason.MAC_FAIL -> {
            val hint = slot.merchantHintHex
            when {
                hint != null && !hint.equals(ourMerchantIdHex, ignoreCase = true) ->
                    TapClass.Refused(REASON_OTHER_MERCHANT)
                cardByUid != null -> TapClass.Damaged(cardByUid, REASON_AUTH)
                else -> TapClass.Refused(REASON_AUTH)
            }
        }
        SlotReason.NO_VALID_SLOT ->
            cardByUid?.let { TapClass.Damaged(it, REASON_NO_SLOT) } ?: TapClass.Blank(uidHex)
    }

    private fun classifyRecord(
        uidHex: String,
        slot: SlotResult,
        cardByReserve: PrepaidCardEntity?,
        ourMerchantIdHex: String,
    ): TapClass {
        val rec = slot.record ?: return TapClass.Refused(REASON_NO_SLOT)
        return when {
            rec.reserveIdHex.all { it == '0' } || rec.merchantIdHex.all { it == '0' } ->
                TapClass.Refused(REASON_UNFORMATTED)
            !rec.merchantIdHex.equals(ourMerchantIdHex, ignoreCase = true) ->
                TapClass.Refused(REASON_OTHER_MERCHANT)
            cardByReserve == null -> TapClass.Bench(rec, slot)
            !cardByReserve.uid.equals(uidHex, ignoreCase = true) -> TapClass.Refused(REASON_MOVED)
            else -> TapClass.Known(cardByReserve, rec, slot)
        }
    }
}
