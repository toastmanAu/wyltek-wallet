package com.wyltek.wallet.agent

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wyltek.wallet.agent.store.AgentSecureStore
import com.wyltek.wallet.core.security.StrongBoxManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AgentKeyStoreTest {
    private fun ks(): AgentKeyStore {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        return AgentKeyStore(AgentSecureStore(ctx), StrongBoxManager(ctx))
    }

    @Test fun provisions_once_and_is_stable() {
        val k = ks()
        val pub1 = k.rootPublicHex()
        val sec1 = k.rootSecretHex()
        assertTrue(pub1.isNotEmpty())
        assertTrue(sec1.isNotEmpty())
        // second instance over the same store returns the same public key
        val pub2 = ks().rootPublicHex()
        assertEquals(pub1, pub2)
    }
}
