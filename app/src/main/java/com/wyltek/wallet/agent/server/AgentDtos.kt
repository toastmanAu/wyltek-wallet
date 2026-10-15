package com.wyltek.wallet.agent.server

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class IntentRequest(
    val token: String,
    val op: String,
    val asset: String,
    val to: String = "",
    val amount: Long = 0,
    val nonce: String,
    val action: String? = null,
    val daoRef: String? = null
)

@Serializable
data class IntentResponse(
    val status: String,           // "sent" | "needs_approval" | "denied" | "failed"
    val txHash: String? = null,
    val pendingId: Long? = null,
    val reason: String? = null
)

@Serializable
data class IntentStatusResponse(
    val status: String,
    val txHash: String? = null,
    val error: String? = null
)

@Serializable
data class AccountInfo(
    val tokenId: String,
    val account: String,
    val revoked: Boolean
)

@Serializable
data class RelayAcceptResponse(
    val status: String,
    @SerialName("intent_id") val intentId: String
)

/** Result of a token-gated, method-whitelisted CKB read-RPC proxy call. */
sealed class ChainProxyResult {
    data class Ok(val body: String) : ChainProxyResult()
    object BadToken : ChainProxyResult()
    object BadMethod : ChainProxyResult()
    data class Upstream(val message: String) : ChainProxyResult()
}

@Serializable data class PrepaidJournalRequest(@SerialName("entries_hex") val entriesHex: String)
@Serializable data class JournalVerdictDto(val index: Int, val verdict: String, @SerialName("tx_hash") val txHash: String? = null)
@Serializable data class PrepaidJournalResponse(val results: List<JournalVerdictDto>)
@Serializable data class PrepaidCardDto(@SerialName("reserve_id") val reserveId: String, @SerialName("balance_units") val balanceUnits: Int, val revoked: Boolean)
@Serializable data class PrepaidCardsResponse(val cards: List<PrepaidCardDto>)

sealed class PrepaidJournalResult {
    data object BadToken : PrepaidJournalResult()
    data class BadRequest(val reason: String) : PrepaidJournalResult()
    data class Ok(val results: List<JournalVerdictDto>) : PrepaidJournalResult()
}
@Serializable data class StaffKeyDto(@SerialName("key_id") val keyId: String, val uid: String, val sig: String)
@Serializable data class StaffKeysResponse(val staff: List<StaffKeyDto>)
sealed class PrepaidStaffResult {
    data object BadToken : PrepaidStaffResult()
    data class Ok(val staff: List<StaffKeyDto>) : PrepaidStaffResult()
}
sealed class PrepaidCardsResult {
    data object BadToken : PrepaidCardsResult()
    data class Ok(val cards: List<PrepaidCardDto>) : PrepaidCardsResult()
}
