package com.wyltek.wallet.prepaid

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wyltek.wallet.agent.db.AgentDatabase
import com.wyltek.wallet.agent.db.AgentDatabaseFactory
import com.wyltek.wallet.prepaid.db.CARD_ACTIVE
import com.wyltek.wallet.prepaid.db.CARD_ISSUING
import com.wyltek.wallet.prepaid.db.KIND_ISSUE
import com.wyltek.wallet.prepaid.db.PHASE_BROADCAST
import com.wyltek.wallet.prepaid.db.PHASE_REQUESTED
import com.wyltek.wallet.prepaid.db.PrepaidCardEntity
import com.wyltek.wallet.prepaid.db.PrepaidEventEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PrepaidDaoTest {
    private lateinit var db: AgentDatabase
    @Before fun open() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.deleteDatabase("agent_gateway.db")
        db = AgentDatabaseFactory.open(ctx, ByteArray(32) { 7 })
    }
    @After fun close() = db.close()

    private fun card(rid: String, uid: String, state: String = CARD_ACTIVE) = PrepaidCardEntity(
        reserveId = rid, uid = uid, firstOutpoint = "0xaa:0", currentOutpoint = "0xaa:0", balanceUnits = 1000,
        counter = 0, settledCounter = 0, state = state, issuedAt = 1, updatedAt = 1)

    @Test fun card_round_trip_and_uid_unique() = runBlocking {
        val dao = db.prepaidDao()
        dao.insertCard(card("r1", "u1"))
        assertEquals("u1", dao.card("r1")!!.uid)
        assertEquals("r1", dao.cardByUid("u1")!!.reserveId)
        try { dao.insertCard(card("r2", "u1")); fail("uid must be unique") } catch (e: android.database.sqlite.SQLiteConstraintException) {}
        assertEquals(1, dao.activeCount())
    }

    @Test fun delete_issuing_only_removes_issuing_rows() = runBlocking {
        val dao = db.prepaidDao()
        dao.insertCard(card("r1", "u1", CARD_ISSUING)); dao.insertCard(card("r2", "u2", CARD_ACTIVE))
        assertEquals(1, dao.deleteIssuing("r1")); assertEquals(0, dao.deleteIssuing("r2"))
        assertNotNull(dao.card("r2"))
    }

    @Test fun events_append_and_read_newest_first() = runBlocking {
        val dao = db.prepaidDao()
        val e = PrepaidEventEntity(atMs = 1, actionId = "a", kind = KIND_ISSUE, phase = PHASE_REQUESTED, reserveId = "r1", uid = "u1",
            amountUnits = 1000, balanceBefore = 0, balanceAfter = 0, counterBefore = 0, counterAfter = 0, txHash = null, detail = "")
        dao.insertEvent(e); dao.insertEvent(e.copy(atMs = 2, phase = PHASE_BROADCAST, txHash = "0xbb"))
        val forCard = dao.eventsForCard("r1")
        assertEquals(listOf(PHASE_BROADCAST, PHASE_REQUESTED), forCard.map { it.phase })
        assertEquals(2, dao.eventsForAction("a").size)
    }
}
