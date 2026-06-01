package com.wyltek.wallet.core.security

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class StrongBoxManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("strongbox_manager", Context.MODE_PRIVATE)
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private val KEY_ALIAS = "wyltek_wallet_strongbox_key"
    private val GCM_TAG_LENGTH = 128
    private val IV_LENGTH = 12

    fun isStrongBoxAvailable(): Boolean {
        return try {
            val keyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                "AndroidKeyStore"
            )
            val spec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setIsStrongBoxBacked(true)
                .build()

            keyGenerator.init(spec)
            keyGenerator.generateKey()
            true
        } catch (e: StrongBoxUnavailableException) {
            false
        } catch (e: Exception) {
            false
        }
    }

    fun isKeyCreated(): Boolean {
        return keyStore.containsAlias(KEY_ALIAS)
    }

    fun createStrongBoxKey(): Boolean {
        return try {
            if (isKeyCreated()) return true

            val keyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                "AndroidKeyStore"
            )
            val spec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setIsStrongBoxBacked(true)
                .build()

            keyGenerator.init(spec)
            keyGenerator.generateKey()

            prefs.edit().putBoolean("strongbox_enabled", true).apply()
            true
        } catch (e: Exception) {
            false
        }
    }

    fun encryptData(plaintext: ByteArray): ByteArray? {
        return try {
            val key = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry ?: return null
            val secretKey = key.secretKey

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)

            val iv = cipher.iv
            val ciphertext = cipher.doFinal(plaintext)

            iv + ciphertext
        } catch (e: Exception) {
            null
        }
    }

    fun decryptData(ciphertext: ByteArray): ByteArray? {
        return try {
            if (ciphertext.size < IV_LENGTH + GCM_TAG_LENGTH / 8) return null

            val iv = ciphertext.copyOfRange(0, IV_LENGTH)
            val encryptedData = ciphertext.copyOfRange(IV_LENGTH, ciphertext.size)

            val key = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry ?: return null
            val secretKey = key.secretKey

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)

            cipher.doFinal(encryptedData)
        } catch (e: Exception) {
            null
        }
    }

    fun wrapPrivateKey(privateKey: ByteArray): ByteArray? {
        return encryptData(privateKey)
    }

    fun unwrapPrivateKey(wrappedKey: ByteArray): ByteArray? {
        return decryptData(wrappedKey)
    }

    fun deleteKey(): Boolean {
        return try {
            keyStore.deleteEntry(KEY_ALIAS)
            prefs.edit().putBoolean("strongbox_enabled", false).apply()
            true
        } catch (e: Exception) {
            false
        }
    }

    fun isStrongBoxEnabled(): Boolean {
        return prefs.getBoolean("strongbox_enabled", false) && isKeyCreated()
    }

    fun getSecurityInfo(): SecurityInfo {
        return SecurityInfo(
            strongBoxAvailable = isStrongBoxAvailable(),
            strongBoxEnabled = isStrongBoxEnabled(),
            keyCreated = isKeyCreated(),
            algorithm = "AES-256-GCM",
            keyStore = "AndroidKeyStore"
        )
    }
}

data class SecurityInfo(
    val strongBoxAvailable: Boolean,
    val strongBoxEnabled: Boolean,
    val keyCreated: Boolean,
    val algorithm: String,
    val keyStore: String
)
