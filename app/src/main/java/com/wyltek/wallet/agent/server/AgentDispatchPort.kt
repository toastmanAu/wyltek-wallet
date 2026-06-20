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
}
