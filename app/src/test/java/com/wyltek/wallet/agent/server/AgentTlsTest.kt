package com.wyltek.wallet.agent.server

import io.ktor.network.tls.certificates.buildKeyStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
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

    private companion object {
        const val NOW = 1_800_000_000_000L
        const val DAY = 86_400_000L
        // sha256 of the DER SubjectPublicKeyInfo of tls/pinned_cert.pem, from openssl.
        const val EXPECTED_PIN = "e0d16ea472c14acfa5c7a68950078fc3e93709d618e4e8c39cf6ce7845e1a591"
    }

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
        val reloaded = AgentTls.loadOrCreate(f).keyStore // reload — this is the exact regression path

        val aliases = Collections.list(reloaded.aliases())
        assertTrue("alias '${AgentTls.ALIAS}' must be present in the reloaded store", aliases.contains(AgentTls.ALIAS))
    }

    private fun writeStore(f: File, bits: Int, days: Long) {
        val ks = buildKeyStore {
            certificate(AgentTls.ALIAS) {
                password = AgentTls.PASS_FOR_TESTS
                domains = listOf("x")
                keySizeInBits = bits
                daysValid = days
            }
        }
        f.outputStream().use { ks.store(it, AgentTls.PASS_FOR_TESTS.toCharArray()) }
    }

    private fun readStore(f: File): KeyStore =
        KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            f.inputStream().use { load(it, AgentTls.PASS_FOR_TESTS.toCharArray()) }
        }

    @Test fun `pinHexOf matches openssl sha256 of the DER SubjectPublicKeyInfo`() {
        val cert = CertificateFactory.getInstance("X.509")
            .generateCertificate(javaClass.getResourceAsStream("/tls/pinned_cert.pem")) as X509Certificate
        assertEquals(EXPECTED_PIN, AgentTls.pinHexOf(cert))
    }

    @Test fun `new keystore is RSA-2048 valid for at least nine years`() {
        val ks = AgentTls.loadOrCreate(File(tmp.root, "a.ks"), nowMs = NOW).keyStore
        val cert = ks.getCertificate(AgentTls.ALIAS) as X509Certificate
        assertEquals(2048, (cert.publicKey as RSAPublicKey).modulus.bitLength())
        assertTrue(cert.notAfter.time >= NOW + 9L * 365 * DAY)
    }

    @Test fun `pinHex is 64 lowercase hex and stable across reload`() {
        val f = File(tmp.root, "a.ks")
        val a = AgentTls.pinHex(AgentTls.loadOrCreate(f, NOW).keyStore)
        val b = AgentTls.pinHex(AgentTls.loadOrCreate(f, NOW).keyStore)
        assertTrue(Regex("[0-9a-f]{64}").matches(a))
        assertEquals(a, b)
    }

    @Test fun `first creation is not reported as regenerated`() {
        assertFalse(AgentTls.loadOrCreate(File(tmp.root, "a.ks"), NOW).regenerated)
    }

    @Test fun `healthy store is kept with the same public key`() {
        val f = File(tmp.root, "a.ks")
        val first = AgentTls.loadOrCreate(f, NOW)
        val again = AgentTls.loadOrCreate(f, NOW)
        assertFalse(again.regenerated)
        assertEquals(AgentTls.pinHex(first.keyStore), AgentTls.pinHex(again.keyStore))
    }

    @Test fun `1024-bit store is regenerated`() {
        val f = File(tmp.root, "weak.ks")
        writeStore(f, 1024, 3650)
        val before = AgentTls.pinHex(readStore(f))
        val loaded = AgentTls.loadOrCreate(f, System.currentTimeMillis())
        assertTrue(loaded.regenerated)
        assertNotEquals(before, AgentTls.pinHex(loaded.keyStore))
    }

    // ktor stamps notBefore from the real clock, so validity-window tests evaluate at the real clock.
    @Test fun `store with under a year left is regenerated`() {
        val f = File(tmp.root, "short.ks")
        writeStore(f, 2048, 100)
        assertTrue(AgentTls.loadOrCreate(f, System.currentTimeMillis()).regenerated)
    }

    @Test fun `store with exactly one year plus a day left is kept`() {
        val f = File(tmp.root, "ok.ks")
        writeStore(f, 2048, 366)
        assertFalse(AgentTls.loadOrCreate(f, System.currentTimeMillis()).regenerated)
    }

    @Test fun `regeneration creates the marker file`() {
        val f = File(tmp.root, "weak.ks"); val m = File(tmp.root, "agent_tls.regen")
        writeStore(f, 1024, 3650)
        assertTrue(AgentTls.loadOrCreate(f, m, System.currentTimeMillis()).regenerated)
        assertTrue(m.exists())
    }

    @Test fun `healthy and first-time stores leave no marker`() {
        val m = File(tmp.root, "agent_tls.regen")
        val f = File(tmp.root, "ok.ks")
        AgentTls.loadOrCreate(f, m, System.currentTimeMillis())   // first creation
        AgentTls.loadOrCreate(f, m, System.currentTimeMillis())   // healthy reload
        assertFalse(m.exists())
    }

    @Test fun `an unwritable marker does not fail the load`() {
        val f = File(tmp.root, "weak2.ks")
        writeStore(f, 1024, 3650)
        val m = File(File(tmp.root, "missing-dir"), "agent_tls.regen")   // parent absent: createNewFile throws
        assertTrue(AgentTls.loadOrCreate(f, m, System.currentTimeMillis()).regenerated)
        assertFalse(m.exists())
    }
}
