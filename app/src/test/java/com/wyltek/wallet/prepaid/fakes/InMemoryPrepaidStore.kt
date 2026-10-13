package com.wyltek.wallet.prepaid.fakes

import com.wyltek.wallet.prepaid.PrepaidStore
import com.wyltek.wallet.prepaid.db.CARD_ACTIVE
import com.wyltek.wallet.prepaid.db.CARD_ISSUING
import com.wyltek.wallet.prepaid.db.CARD_REVOKED
import com.wyltek.wallet.prepaid.db.PrepaidCardEntity
import com.wyltek.wallet.prepaid.db.PrepaidEventEntity
import kotlinx.coroutines.flow.MutableStateFlow

class InMemoryPrepaidStore : PrepaidStore {
    val cards = linkedMapOf<String, PrepaidCardEntity>()
    val events = mutableListOf<PrepaidEventEntity>()
    private val cardsState = MutableStateFlow<List<PrepaidCardEntity>>(emptyList())
    private val eventsState = MutableStateFlow<List<PrepaidEventEntity>>(emptyList())
    private fun emit() { cardsState.value = cards.values.sortedByDescending { it.updatedAt }; eventsState.value = events.reversed() }
    override suspend fun card(reserveId: String) = cards[reserveId]
    override suspend fun cardByUid(uid: String) = cards.values.firstOrNull { it.uid.equals(uid, true) }
    override suspend fun insertCard(c: PrepaidCardEntity) {
        check(c.reserveId !in cards) { "duplicate reserveId" }
        check(cards.values.none { it.uid.equals(c.uid, true) }) { "duplicate uid" }
        cards[c.reserveId] = c; emit()
    }
    override suspend fun updateCard(c: PrepaidCardEntity) { check(c.reserveId in cards); cards[c.reserveId] = c; emit() }
    override suspend fun deleteIssuing(reserveId: String): Int = if (cards[reserveId]?.state == CARD_ISSUING) { cards.remove(reserveId); emit(); 1 } else 0
    override suspend fun allCards() = cards.values.toList()
    override suspend fun cardsWithPending() = cards.values.filter { it.pendingTxHash != null }
    override suspend fun activeCount() = cards.values.count { it.state in setOf(CARD_ISSUING, CARD_ACTIVE, CARD_REVOKED) }
    override suspend fun event(e: PrepaidEventEntity) { events += e.copy(id = events.size + 1L); emit() }
    override suspend fun allEvents() = events.toList()
    override suspend fun eventsForCard(reserveId: String) = events.filter { it.reserveId == reserveId }.reversed()
    override fun cardsFlow() = cardsState
    override fun eventsFlow() = eventsState
    fun phases(actionId: String) = events.filter { it.actionId == actionId }.map { it.phase }
}
