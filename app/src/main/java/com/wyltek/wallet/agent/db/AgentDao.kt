package com.wyltek.wallet.agent.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface AgentDao {
    // ---- ledger usage ----
    @Query("SELECT COALESCE(SUM(amount),0) FROM spend_records WHERE tokenId=:tokenId AND asset=:asset")
    suspend fun cumulative(tokenId: String, asset: String): Long

    @Query("SELECT COALESCE(SUM(amount),0) FROM spend_records WHERE tokenId=:tokenId AND asset=:asset AND unix>=:winStart AND unix<=:now")
    suspend fun windowSum(tokenId: String, asset: String, winStart: Long, now: Long): Long

    @Query("SELECT COUNT(*) FROM spend_records WHERE tokenId=:tokenId AND nonce=:nonce")
    suspend fun nonceCount(tokenId: String, nonce: String): Int

    // ---- spend lifecycle ----
    @Insert
    suspend fun insertSpend(rec: SpendRecordEntity): Long

    /** Atomic reserve: returns the row id, or -1 if the (tokenId,nonce) pair already exists. */
    @Transaction
    suspend fun reserve(rec: SpendRecordEntity): Long {
        if (nonceCount(rec.tokenId, rec.nonce) > 0) return -1
        return insertSpend(rec)
    }

    @Query("UPDATE spend_records SET status=:status, txHash=:txHash WHERE id=:id")
    suspend fun setStatus(id: Long, status: String, txHash: String?)

    @Query("DELETE FROM spend_records WHERE id=:id")
    suspend fun deleteSpend(id: Long)

    // ---- token registry ----
    @Insert
    suspend fun insertToken(t: TokenRegistryEntity)

    @Query("SELECT * FROM token_registry WHERE token=:token LIMIT 1")
    suspend fun tokenByString(token: String): TokenRegistryEntity?

    @Query("SELECT * FROM token_registry ORDER BY createdAt DESC")
    suspend fun allTokens(): List<TokenRegistryEntity>

    @Query("UPDATE token_registry SET revoked=1 WHERE tokenId=:tokenId")
    suspend fun revoke(tokenId: String)

    @Query("SELECT revoked FROM token_registry WHERE tokenId=:tokenId LIMIT 1")
    suspend fun isRevoked(tokenId: String): Boolean?

    // ---- pending intents ----
    @Insert
    suspend fun insertPending(p: PendingIntentEntity): Long

    @Query("SELECT * FROM pending_intents WHERE id=:id LIMIT 1")
    suspend fun pending(id: Long): PendingIntentEntity?

    @Query("SELECT * FROM pending_intents WHERE status=:status ORDER BY createdAt DESC")
    suspend fun pendingByStatus(status: String): List<PendingIntentEntity>

    @Query("UPDATE pending_intents SET status=:status, resultTxHash=:txHash, resultError=:error WHERE id=:id")
    suspend fun setPendingResult(id: Long, status: String, txHash: String?, error: String?)

    /** Live view of the approval queue. Room re-emits on any write to the table, so a screen
     *  collecting this updates itself when the relay pushes a new intent — no manual refresh. */
    @Query("SELECT * FROM pending_intents WHERE status=:status ORDER BY createdAt DESC")
    fun pendingByStatusFlow(status: String): Flow<List<PendingIntentEntity>>

    /** Record which relay intent a pending row came from, so approving it can report back. */
    @Query("UPDATE pending_intents SET relay_intent_id=:relayIntentId WHERE id=:id")
    suspend fun setRelayIntentId(id: Long, relayIntentId: String)
}
