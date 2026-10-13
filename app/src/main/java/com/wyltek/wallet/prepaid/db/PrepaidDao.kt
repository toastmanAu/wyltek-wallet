package com.wyltek.wallet.prepaid.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface PrepaidDao {
    // ---- index ----
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCard(c: PrepaidCardEntity)

    @Update
    suspend fun updateCard(c: PrepaidCardEntity)

    /** Only a card whose issue never left the wallet may be removed (broadcast refused / rejected). */
    @Query("DELETE FROM prepaid_cards WHERE reserveId = :reserveId AND state = 'issuing'")
    suspend fun deleteIssuing(reserveId: String): Int

    @Query("SELECT * FROM prepaid_cards WHERE reserveId = :reserveId LIMIT 1")
    suspend fun card(reserveId: String): PrepaidCardEntity?

    @Query("SELECT * FROM prepaid_cards WHERE uid = :uid LIMIT 1")
    suspend fun cardByUid(uid: String): PrepaidCardEntity?

    @Query("SELECT * FROM prepaid_cards ORDER BY updatedAt DESC")
    suspend fun allCards(): List<PrepaidCardEntity>

    @Query("SELECT * FROM prepaid_cards ORDER BY updatedAt DESC")
    fun cardsFlow(): Flow<List<PrepaidCardEntity>>

    @Query("SELECT * FROM prepaid_cards WHERE pendingTxHash IS NOT NULL")
    suspend fun cardsWithPending(): List<PrepaidCardEntity>

    @Query("SELECT COUNT(*) FROM prepaid_cards WHERE state IN ('issuing','active','revoked')")
    suspend fun activeCount(): Int

    // ---- audit log: insert + select only ----
    @Insert
    suspend fun insertEvent(e: PrepaidEventEntity): Long

    @Query("SELECT * FROM prepaid_events ORDER BY id DESC")
    fun eventsFlow(): Flow<List<PrepaidEventEntity>>

    @Query("SELECT * FROM prepaid_events ORDER BY id ASC")
    suspend fun allEvents(): List<PrepaidEventEntity>

    @Query("SELECT * FROM prepaid_events WHERE reserveId = :reserveId ORDER BY id DESC")
    suspend fun eventsForCard(reserveId: String): List<PrepaidEventEntity>

    @Query("SELECT * FROM prepaid_events WHERE actionId = :actionId ORDER BY id ASC")
    suspend fun eventsForAction(actionId: String): List<PrepaidEventEntity>
}
