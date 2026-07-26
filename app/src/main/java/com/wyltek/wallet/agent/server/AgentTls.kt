package com.wyltek.wallet.agent.server

import android.content.Context
import io.ktor.network.tls.certificates.buildKeyStore
import java.io.File
import java.security.KeyStore

object AgentTls {
    const val ALIAS = "agent"
    private const val PASS = "agentpass"           // local file password; trust is the biscuit, not this
    private const val FILE = "agent_tls.ks"

    /** Load the persisted self-signed keystore, generating + persisting it on first use.
     *  CN/SAN are irrelevant (the POS uses setInsecure), so a fixed cert survives IP changes. */
    fun keyStore(context: Context): KeyStore = loadOrCreate(File(context.filesDir, FILE))

    /** Context-free core so this is testable on a host JVM (see AgentTlsTest). Loads with
     *  [KeyStore.getDefaultType] — the SAME type [buildKeyStore] persists in (BKS on Android,
     *  PKCS12 on host JVM) — never a hardcoded "JKS", which has no provider on Android and would
     *  throw on every reuse of an existing file. */
    internal fun loadOrCreate(f: File): KeyStore {
        if (f.exists()) {
            return KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                f.inputStream().use { load(it, PASS.toCharArray()) }
            }
        }
        val ks = buildKeyStore { certificate(ALIAS) { password = PASS; domains = listOf("blackbox-agent") } }
        f.outputStream().use { ks.store(it, PASS.toCharArray()) }
        return ks
    }

    fun password(): CharArray = PASS.toCharArray()
}
