package com.wyltek.wallet.core.keystore

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.util.Base64

class SeedVault(private val context: Context) {

    companion object {
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val KEY_ALIAS = "wyltek_seed_vault_key"
        private const val ENCRYPTED_PREFS = "seed_vault_prefs"
        private const val IV_KEY = "seed_iv"
        private const val PREFIX = "seed_"
        private const val GCM_TAG_LENGTH = 128
        private const val GCM_IV_LENGTH = 12
    }

    private val masterKey by lazy {
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    private val encryptedPrefs by lazy {
        EncryptedSharedPreferences.create(
            context,
            ENCRYPTED_PREFS,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }

        keyStore.getEntry(KEY_ALIAS, null)?.let {
            return (it as KeyStore.SecretKeyEntry).secretKey
        }

        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            KEYSTORE_PROVIDER
        )
        keyGenerator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        )
        return keyGenerator.generateKey()
    }

    fun storeSeed(walletId: String, seedHex: String) {
        val key = getOrCreateKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)

        val iv = cipher.iv
        val encrypted = cipher.doFinal(seedHex.toByteArray(Charsets.UTF_8))

        val encodedIv = Base64.encodeToString(iv, Base64.NO_WRAP)
        val encodedSeed = Base64.encodeToString(encrypted, Base64.NO_WRAP)

        encryptedPrefs.edit()
            .putString("${PREFIX}${walletId}_iv", encodedIv)
            .putString("${PREFIX}${walletId}_seed", encodedSeed)
            .apply()
    }

    fun loadSeed(walletId: String): String? {
        val encodedIv = encryptedPrefs.getString("${PREFIX}${walletId}_iv", null) ?: return null
        val encodedSeed = encryptedPrefs.getString("${PREFIX}${walletId}_seed", null) ?: return null

        val iv = Base64.decode(encodedIv, Base64.NO_WRAP)
        val encrypted = Base64.decode(encodedSeed, Base64.NO_WRAP)

        val key = getOrCreateKey()
        val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, spec)

        val decrypted = cipher.doFinal(encrypted)
        return String(decrypted, Charsets.UTF_8)
    }

    fun deleteSeed(walletId: String): Boolean {
        return encryptedPrefs.edit()
            .remove("${PREFIX}${walletId}_iv")
            .remove("${PREFIX}${walletId}_seed")
            .commit()
    }

    fun hasSeed(walletId: String): Boolean {
        return encryptedPrefs.contains("${PREFIX}${walletId}_seed")
    }

    fun listWalletIds(): List<String> {
        return encryptedPrefs.all.keys
            .filter { it.startsWith(PREFIX) && it.endsWith("_seed") }
            .map { it.removePrefix(PREFIX).removeSuffix("_seed") }
    }
}
