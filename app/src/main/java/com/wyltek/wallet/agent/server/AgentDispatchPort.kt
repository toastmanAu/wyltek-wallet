package com.wyltek.wallet.agent.server

import com.wyltek.wallet.agent.DispatchResult
import com.wyltek.wallet.core.native.Intent

/**
 * Minimal seam between the Ktor HTTP layer and the agent backend.
 * [AgentGatewayPort] is implemented by an adapter over [com.wyltek.wallet.agent.AgentGateway]
 * on device, and by a pure fake in host JVM tests.
 */
interface AgentDispatchPort {
    suspend fun dispatch(
        token: String,
        intent: Intent,
        sourceIp: String,
        nowUnix: Long
    ): DispatchResult

    suspend fun pendingStatus(id: Long): IntentStatusResponse?

    suspend fun accounts(): List<AccountInfo>

    /** Relay-compatible async facade: accept an intent, return an opaque intent_id the
     *  POS polls. Idempotent per (token, nonce). */
    suspend fun submitRelayIntent(token: String, intent: Intent, sourceIp: String, nowUnix: Long): String

    /** Poll a relay-facade intent by intent_id. Null = unknown/not-yet-tracked → 404. */
    suspend fun relayIntentStatus(intentId: String): IntentStatusResponse?

    /** Token-gated, method-whitelisted proxy of a CKB read RPC to the phone's node.
     *  Reads only (get_cells_capacity / get_transactions); returns the node body verbatim. */
    suspend fun proxyChainRead(token: String, method: String, rawBody: String): ChainProxyResult
}
