package com.wyltek.wallet.agent

import com.wyltek.wallet.agent.db.AgentDatabase
import com.wyltek.wallet.agent.db.SpendRecordEntity
import com.wyltek.wallet.agent.db.STATUS_CONFIRMED
import com.wyltek.wallet.core.native.LedgerView

/** Owns spend accounting; fulfills the same view/apply contract as the Rust InMemoryLedger. */
class AgentLedger(private val db: AgentDatabase) {
    private val dao = db.agentDao()

    suspend fun view(tokenId: String, asset: String, windowSeconds: Long, nowUnix: Long): LedgerView {
        val cumulative = dao.cumulative(tokenId, asset)
        val window = if (windowSeconds > 0) {
            val winStart = (nowUnix - windowSeconds).coerceAtLeast(0)
            dao.windowSum(tokenId, asset, winStart, nowUnix)
        } else 0L
        val nonceSeen = false // replay is enforced atomically in reserve(); decide() also guards on this
        return LedgerView(cumulativeSpent = cumulative, windowSpent = window, nonceSeen = nonceSeen)
    }

    /** Pre-checks replay for the decide() input (cheap read; the authoritative guard is reserve()). */
    suspend fun nonceSeen(tokenId: String, nonce: String): Boolean = dao.nonceCount(tokenId, nonce) > 0

    /** Atomically reserve a pending spend; returns row id, or null if the nonce was already used. */
    suspend fun reserve(tokenId: String, asset: String, amount: Long, unix: Long, nonce: String): Long? {
        val id = dao.reserve(SpendRecordEntity(tokenId = tokenId, asset = asset, amount = amount, unix = unix, nonce = nonce))
        return if (id < 0) null else id
    }

    suspend fun confirm(id: Long, txHash: String) = dao.setStatus(id, STATUS_CONFIRMED, txHash)
    suspend fun rollback(id: Long) = dao.deleteSpend(id)
}
