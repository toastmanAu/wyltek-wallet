package com.wyltek.wallet.agent.server

import android.content.Context
import io.ktor.network.tls.certificates.buildKeyStore
import java.io.File
import java.security.KeyStore

object AgentTls {
    const val ALIAS = "agent"
    private const val PASS = "agentpass"           // local file password; trust is the biscuit, not this
    private const val FILE = "agent_tls.jks"

    /** Load the persisted self-signed keystore, generating + persisting it on first use.
     *  CN/SAN are irrelevant (the POS uses setInsecure), so a fixed cert survives IP changes. */
    fun keyStore(context: Context): KeyStore {
        val f = File(context.filesDir, FILE)
        if (f.exists()) {
            return KeyStore.getInstance("JKS").apply {
                f.inputStream().use { load(it, PASS.toCharArray()) }
            }
        }
        val ks = buildKeyStore { certificate(ALIAS) { password = PASS; domains = listOf("blackbox-agent") } }
        f.outputStream().use { ks.store(it, PASS.toCharArray()) }
        return ks
    }

    fun password(): CharArray = PASS.toCharArray()
}
