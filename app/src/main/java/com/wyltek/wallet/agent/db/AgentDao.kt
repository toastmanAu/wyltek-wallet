package com.wyltek.wallet.agent.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction

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
}
