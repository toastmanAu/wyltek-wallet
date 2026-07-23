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
}
