package com.wyltek.wallet.agent.server

import android.content.Context
import io.ktor.network.tls.certificates.buildKeyStore
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey

object AgentTls {
    const val ALIAS = "agent"
    private const val PASS = "agentpass"           // local file password; trust is the biscuit, not this
    internal const val PASS_FOR_TESTS = PASS       // not secret (see above); lets tests build stores
    private const val FILE = "agent_tls.ks"
    private const val MIN_KEY_BITS = 2048
    private const val DAY_MS = 86_400_000L
    private const val MIN_REMAINING_MS = 365L * DAY_MS

    const val NOTICE_REGENERATED =
        "Terminal pairing key was regenerated: re-provision every terminal (scan a new pairing QR)."

    /** A loaded keystore, and whether an existing weak/expiring one was replaced (which changes
     *  the public-key pin, so every provisioned terminal must be re-provisioned). */
    data class Loaded(val keyStore: KeyStore, val regenerated: Boolean)

    /** Load the persisted self-signed keystore, generating + persisting it on first use.
     *  The POS pins this certificate's public key (see [pinHex]); the cert's CN/SAN and
     *  validity still do not matter to the POS, so a fixed cert survives IP changes. */
    fun load(context: Context): Loaded = loadOrCreate(File(context.filesDir, FILE))

    fun keyStore(context: Context): KeyStore = load(context).keyStore

    /** Context-free core so this is testable on a host JVM (see AgentTlsTest). Loads with
     *  [KeyStore.getDefaultType] — the SAME type [buildKeyStore] persists in (BKS on Android,
     *  PKCS12 on host JVM) — never a hardcoded "JKS", which has no provider on Android and would
     *  throw on every reuse of an existing file. */
    internal fun loadOrCreate(f: File, nowMs: Long = System.currentTimeMillis()): Loaded {
        if (f.exists()) {
            val existing = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                f.inputStream().use { load(it, PASS.toCharArray()) }
            }
            if (!needsRegeneration(existing, nowMs)) return Loaded(existing, regenerated = false)
            f.delete()
            return Loaded(create(f), regenerated = true)
        }
        return Loaded(create(f), regenerated = false)
    }

    private fun create(f: File): KeyStore {
        val ks = buildKeyStore {
            certificate(ALIAS) {
                password = PASS
                domains = listOf("blackbox-agent")
                keySizeInBits = MIN_KEY_BITS
                daysValid = 3650
            }
        }
        f.outputStream().use { ks.store(it, PASS.toCharArray()) }
        return ks
    }

    /** True when the stored cert is missing, weaker than RSA-2048, or has under a year left. */
    fun needsRegeneration(ks: KeyStore, nowMs: Long): Boolean {
        val cert = ks.getCertificate(ALIAS) as? X509Certificate ?: return true
        val rsa = cert.publicKey as? RSAPublicKey ?: return true
        return rsa.modulus.bitLength() < MIN_KEY_BITS || cert.notAfter.time < nowMs + MIN_REMAINING_MS
    }

    /** sha256 of the DER SubjectPublicKeyInfo, 64 lowercase hex chars. */
    fun pinHexOf(cert: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(cert.publicKey.encoded)
            .joinToString("") { "%02x".format(it) }

    fun pinHex(ks: KeyStore): String = pinHexOf(ks.getCertificate(ALIAS) as X509Certificate)

    fun password(): CharArray = PASS.toCharArray()
}
