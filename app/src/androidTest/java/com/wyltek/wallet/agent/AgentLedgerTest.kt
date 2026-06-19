package com.wyltek.wallet.agent

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wyltek.wallet.agent.db.AgentDatabaseFactory
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AgentLedgerTest {
    private var db: com.wyltek.wallet.agent.db.AgentDatabase? = null

    private fun newLedger(): AgentLedger {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = AgentDatabaseFactory.open(ctx, ByteArray(32) { 7 })
        return AgentLedger(db!!)
    }

    @After
    fun tearDown() {
        db?.close()
    }

    @Test fun reserve_then_view_sums_cumulative() = runBlocking {
        val l = newLedger()
        assertNotNull(l.reserve("t1", "CKB", 10, 100, "a"))
        assertNotNull(l.reserve("t1", "CKB", 5, 200, "b"))
        val v = l.view("t1", "CKB", 0, 300)
        assertEquals(15L, v.cumulativeSpent)
        assertEquals(0L, v.windowSpent)
    }

    @Test fun window_counts_only_recent() = runBlocking {
        val l = newLedger()
        l.reserve("t1", "CKB", 10, 100, "a")
        l.reserve("t1", "CKB", 7, 250, "b")
        val v = l.view("t1", "CKB", 100, 300) // winStart=200
        assertEquals(7L, v.windowSpent)
    }

    @Test fun duplicate_nonce_rejected() = runBlocking {
        val l = newLedger()
        assertNotNull(l.reserve("t1", "CKB", 10, 100, "dup"))
        assertNull(l.reserve("t1", "CKB", 1, 110, "dup"))
    }

    @Test fun rollback_removes_from_cumulative() = runBlocking {
        val l = newLedger()
        val id = l.reserve("t1", "CKB", 10, 100, "a")!!
        l.rollback(id)
        assertEquals(0L, l.view("t1", "CKB", 0, 300).cumulativeSpent)
    }

    @Test fun concurrent_same_nonce_one_wins() = runBlocking {
        val l = newLedger()
        val results = (1..2).map { async(Dispatchers.IO) { l.reserve("t1", "CKB", 5, 100, "dup") } }.awaitAll()
        assertEquals(1, results.count { it != null })
    }
}
