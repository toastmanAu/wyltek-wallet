package com.wyltek.wallet.agent

import com.wyltek.wallet.agent.server.PrepaidCardDto
import com.wyltek.wallet.prepaid.db.CARD_ACTIVE
import com.wyltek.wallet.prepaid.db.CARD_REVOKED
import com.wyltek.wallet.prepaid.db.KIND_CASH_OUT
import com.wyltek.wallet.prepaid.db.KIND_KEY_GENERATED
import com.wyltek.wallet.prepaid.db.PHASE_DONE
import com.wyltek.wallet.prepaid.db.PrepaidEventEntity
import com.wyltek.wallet.prepaid.db.KIND_VOID
import com.wyltek.wallet.prepaid.db.PrepaidCardEntity

/** Pure pieces of the gateway's prepaid wiring, host-testable without Android. */
object PrepaidGatewayMapping {
    /** spec §3.3: the account id that funds every reserve, persisted on first selection. */
    const val KEY_FUNDING_ACCOUNT = "prepaid.funding_account"
    const val SECP256K1_BLAKE160_CODE_HASH = "0x9bd7e06f3ecf4be0f2fcd2188b23f1b9fcc88e5d4b65a8637b17723bbda3cce8"
    const val REASON_NO_ACCOUNT = "Prepaid demo runs on testnet only; add or select a testnet classic account"
    const val REASON_MISSING = "funding account missing"

    /**
     * What the POS sees for one index row (I2). Only active/revoked cards are listed; a card with a void or
     * cash-out in flight is reported revoked so the terminal stops honouring it before the chain confirms.
     */
    fun cardDtoOf(card: PrepaidCardEntity): PrepaidCardDto? {
        if (card.state != CARD_ACTIVE && card.state != CARD_REVOKED) return null
        val retiring = card.pendingTxHash != null && (card.pendingKind == KIND_VOID || card.pendingKind == KIND_CASH_OUT)
        return PrepaidCardDto(card.reserveId, card.balanceUnits, card.state == CARD_REVOKED || retiring)
    }

    const val WARNING_KEY_GENERATED = "A new card key (K_debit) was generated: re-provision every terminal before it settles prepaid cards."

    /** I6: the audit row for a K_debit (re)generation. */
    fun keyGeneratedEvent(nowMs: Long, actionId: String) = PrepaidEventEntity(atMs = nowMs, actionId = actionId, kind = KIND_KEY_GENERATED,
        phase = PHASE_DONE, reserveId = null, uid = "", amountUnits = 0, balanceBefore = 0, balanceAfter = 0, counterBefore = 0,
        counterAfter = 0, txHash = null, detail = "K_debit generated; re-provision every terminal")

    data class Candidate(val id: String, val testnet: Boolean, val firstAddressCodeHash: String?)

    /** Null when the key has no PIN set; hex fields are lower-cased on the wire. */
    fun staffDtoOf(k: com.wyltek.wallet.prepaid.db.StaffKeyEntity): com.wyltek.wallet.agent.server.StaffKeyDto? =
        k.pinCheckHex?.let { com.wyltek.wallet.agent.server.StaffKeyDto(k.keyId.lowercase(), k.pinVer, it.lowercase()) }

    /** Active (non-revoked) keys that have a PIN, capped at [com.wyltek.wallet.prepaid.StaffKeyService.MAX_ACTIVE]; filter BEFORE the
     *  cap so a PIN-less key never uses a slot. Never uid or sig. */
    fun staffDtosOf(keys: List<com.wyltek.wallet.prepaid.db.StaffKeyEntity>) =
        keys.filter { it.revokedAt == null && it.pinCheckHex != null }.take(com.wyltek.wallet.prepaid.StaffKeyService.MAX_ACTIVE).mapNotNull(::staffDtoOf)

    sealed class FundingChoice {
        /** [persist] = this is a first selection that must be written to [KEY_FUNDING_ACCOUNT]. */
        data class Use(val accountId: String, val persist: Boolean) : FundingChoice()
        data class Unavailable(val reason: String) : FundingChoice()
    }

    /**
     * I1. A stored account is used as long as it exists (never silently switched). Without one, the first
     * TESTNET account whose FIRST address is secp256k1_blake160 and that has a seed is chosen and persisted.
     */
    fun chooseFundingAccount(storedId: String?, accounts: List<Candidate>, hasSeed: (String) -> Boolean): FundingChoice {
        if (storedId != null)
            return if (accounts.any { it.id == storedId }) FundingChoice.Use(storedId, persist = false) else FundingChoice.Unavailable(REASON_MISSING)
        val pick = accounts.firstOrNull { a ->
            a.testnet && a.firstAddressCodeHash.equals(SECP256K1_BLAKE160_CODE_HASH, ignoreCase = true) && hasSeed(a.id)
        } ?: return FundingChoice.Unavailable(REASON_NO_ACCOUNT)
        return FundingChoice.Use(pick.id, persist = true)
    }
}
