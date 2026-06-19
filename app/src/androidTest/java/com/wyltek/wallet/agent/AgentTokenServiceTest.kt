package com.wyltek.wallet.agent

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wyltek.wallet.agent.db.AgentDatabaseFactory
import com.wyltek.wallet.agent.store.AgentSecureStore
import com.wyltek.wallet.core.native.CapInfo
import com.wyltek.wallet.core.native.Scope
import com.wyltek.wallet.core.native.TokenSpec
import com.wyltek.wallet.core.security.StrongBoxManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AgentTokenServiceTest {
    private fun svc(): AgentTokenService {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val ks = AgentKeyStore(AgentSecureStore(ctx), StrongBoxManager(ctx))
        val db = AgentDatabaseFactory.open(ctx, ByteArray(32) { 9 })
        return AgentTokenService(ks, db)
    }

    private fun spec() = TokenSpec(
        account = "ckt1qfunded",
        scopes = listOf(Scope.SEND_CKB),
        caps = listOf(CapInfo(asset = "CKB", cumulative = 100_000_000_000L, windowSeconds = 0L, windowLimit = 0L, autoLimit = 20_000_000_000L)),
        ttlUnix = null,
        allowTo = emptyList(),
        allowIp = emptyList()
    )

    @Test fun mint_persists_and_lists() = runBlocking {
        val s = svc()
        val m = s.mint(spec())
        assertTrue(m.token.isNotEmpty())
        assertTrue(m.tokenId.isNotEmpty())
        assertTrue(s.list().any { it.tokenId == m.tokenId && it.account == "ckt1qfunded" })
        assertFalse(s.isRevoked(m.tokenId))
        assertEquals(1, s.caps(m.token).size)
    }

    @Test fun revoke_marks_revoked() = runBlocking {
        val s = svc()
        val m = s.mint(spec())
        s.revoke(m.tokenId)
        assertTrue(s.isRevoked(m.tokenId))
    }
}
