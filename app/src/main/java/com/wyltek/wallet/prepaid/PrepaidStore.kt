package com.wyltek.wallet.prepaid

import com.wyltek.wallet.agent.db.AgentDatabase
import com.wyltek.wallet.prepaid.db.PrepaidCardEntity
import com.wyltek.wallet.prepaid.db.PrepaidEventEntity
import kotlinx.coroutines.flow.Flow

interface PrepaidStore {
    suspend fun card(reserveId: String): PrepaidCardEntity?
    suspend fun cardByUid(uid: String): PrepaidCardEntity?
    suspend fun insertCard(c: PrepaidCardEntity)
    suspend fun updateCard(c: PrepaidCardEntity)
    /** All rows or none (one Room transaction). */
    suspend fun updateCards(cards: List<PrepaidCardEntity>)
    suspend fun deleteIssuing(reserveId: String): Int
    suspend fun allCards(): List<PrepaidCardEntity>
    suspend fun cardsWithPending(): List<PrepaidCardEntity>
    suspend fun activeCount(): Int
    suspend fun event(e: PrepaidEventEntity)
    suspend fun allEvents(): List<PrepaidEventEntity>
    suspend fun eventsForCard(reserveId: String): List<PrepaidEventEntity>
    fun cardsFlow(): Flow<List<PrepaidCardEntity>>
    fun eventsFlow(): Flow<List<PrepaidEventEntity>>
}

class RoomPrepaidStore(db: AgentDatabase) : PrepaidStore {
    private val dao = db.prepaidDao()
    override suspend fun card(reserveId: String) = dao.card(reserveId)
    override suspend fun cardByUid(uid: String) = dao.cardByUid(uid)
    override suspend fun insertCard(c: PrepaidCardEntity) = dao.insertCard(c)
    override suspend fun updateCard(c: PrepaidCardEntity) = dao.updateCard(c)
    override suspend fun updateCards(cards: List<PrepaidCardEntity>) = dao.updateCards(cards)
    override suspend fun deleteIssuing(reserveId: String) = dao.deleteIssuing(reserveId)
    override suspend fun allCards() = dao.allCards()
    override suspend fun cardsWithPending() = dao.cardsWithPending()
    override suspend fun activeCount() = dao.activeCount()
    override suspend fun event(e: PrepaidEventEntity) { dao.insertEvent(e) }
    override suspend fun allEvents() = dao.allEvents()
    override suspend fun eventsForCard(reserveId: String) = dao.eventsForCard(reserveId)
    override fun cardsFlow() = dao.cardsFlow()
    override fun eventsFlow() = dao.eventsFlow()
}
