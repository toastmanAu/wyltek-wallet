package com.wyltek.wallet.agent

import com.wyltek.wallet.agent.db.AgentDatabase
import com.wyltek.wallet.agent.db.PENDING_APPROVAL
import com.wyltek.wallet.agent.db.PendingIntentEntity
import kotlinx.coroutines.flow.Flow

class PendingStore(db: AgentDatabase) {
    private val dao = db.agentDao()

    suspend fun put(p: PendingIntentEntity): Long = dao.insertPending(p)

    suspend fun get(id: Long): PendingIntentEntity? = dao.pending(id)

    suspend fun listPending(): List<PendingIntentEntity> = dao.pendingByStatus(PENDING_APPROVAL)

    suspend fun setResult(id: Long, status: String, txHash: String?, error: String?) =
        dao.setPendingResult(id, status, txHash, error)

    /** Live approval queue — emits again on every write to the table. */
    fun pendingFlow(): Flow<List<PendingIntentEntity>> = dao.pendingByStatusFlow(PENDING_APPROVAL)

    suspend fun linkRelayIntent(id: Long, relayIntentId: String) =
        dao.setRelayIntentId(id, relayIntentId)

    suspend fun getByRelayIntentId(id: String): PendingIntentEntity? = dao.pendingByRelayIntentId(id)

    /** Insert (idempotently) a terminal tracking row for an auto-Sent/Denied/Failed /relay intent. */
    suspend fun putTerminalByRelayIntent(
        intentId: String, intent: com.wyltek.wallet.core.native.Intent,
        sourceIp: String, now: Long, status: String, txHash: String?, error: String?,
    ): Long = dao.insertTerminalRelay(
        com.wyltek.wallet.agent.db.PendingIntentEntity(
            token = "", op = intent.op, asset = intent.asset, to = intent.to, amount = intent.amount,
            nonce = intent.nonce, action = intent.action, daoRef = intent.daoRef, sourceIp = sourceIp,
            status = status, createdAt = now, resultTxHash = txHash, resultError = error,
            relayIntentId = intentId,
        )
    )
}
