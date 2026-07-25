package com.wyltek.wallet.agent.server

import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Collections

/** Pure host-JVM test — no Android runtime required.
 *
 *  Regression coverage for the JKS/BKS keystore-type mismatch: [AgentTls.keyStore] persists
 *  via ktor's `buildKeyStore`, which writes in the platform default keystore type (BKS on
 *  Android, PKCS12 on host JVM here) — never "JKS". A hardcoded `KeyStore.getInstance("JKS")`
 *  reload would throw on this host JVM too (no "JKS" entry was ever written), which is exactly
 *  what this test would catch if the bug reappeared. */
class AgentTlsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `first call creates the keystore file`() {
        val f = File(tmp.root, "agent_tls.ks")
        assertTrue("file must not exist before first load", !f.exists())

        AgentTls.loadOrCreate(f)

        assertTrue("first call must create the keystore file", f.exists())
    }

    @Test
    fun `second call reloads the persisted keystore without throwing`() {
        val f = File(tmp.root, "agent_tls.ks")

        AgentTls.loadOrCreate(f) // create + persist
        val reloaded = AgentTls.loadOrCreate(f) // reload — this is the exact regression path

        val aliases = Collections.list(reloaded.aliases())
        assertTrue("alias '${AgentTls.ALIAS}' must be present in the reloaded store", aliases.contains(AgentTls.ALIAS))
    }
}
