package com.wyltek.wallet.agent.store

import android.content.Context
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.SecureRandom

/**
 * Encrypted key/value blob store for Agent Gateway secrets, layered on
 * EncryptedSharedPreferences (AES-256-GCM, AndroidKeyStore master key) — the same
 * primitive SeedVault uses. Holds the SQLCipher passphrase and (via AgentKeyStore)
 * the sealed biscuit root key. Values never appear in plaintext prefs.
 */
class AgentSecureStore(context: Context) {
    private val prefs = EncryptedSharedPreferences.create(
        context,
        "agent_secure_store",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun storeBlob(key: String, value: ByteArray) {
        prefs.edit().putString(key, Base64.encodeToString(value, Base64.NO_WRAP)).apply()
    }

    fun loadBlob(key: String): ByteArray? =
        prefs.getString(key, null)?.let { Base64.decode(it, Base64.NO_WRAP) }

    fun deleteBlob(key: String) { prefs.edit().remove(key).apply() }

    /** Returns the SQLCipher passphrase, generating + persisting a 32-byte random one on first use. */
    fun sqlcipherPassphrase(): ByteArray {
        loadBlob(KEY_DB_PASSPHRASE)?.let { return it }
        val pass = ByteArray(32).also { SecureRandom().nextBytes(it) }
        storeBlob(KEY_DB_PASSPHRASE, pass)
        return pass
    }

    companion object {
        private const val KEY_DB_PASSPHRASE = "sqlcipher_passphrase"
    }
}
