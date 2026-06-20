package com.wyltek.wallet.agent.server

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
