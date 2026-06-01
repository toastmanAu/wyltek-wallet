package com.wyltek.wallet.core.passkey

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class JoyIDConfig(
    val testnetUrl: String = "https://app.joyid.dev",
    val mainnetUrl: String = "https://joyid.dev",
    val isTestnet: Boolean = true
)

class JoyIDIntegration(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("joyid_integration", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private var config = JoyIDConfig()

    init {
        loadConfig()
    }

    fun getConfig(): JoyIDConfig = config

    fun setTestnet(testnet: Boolean) {
        config = config.copy(isTestnet = testnet)
        saveConfig()
    }

    fun buildJoyIDSignMessageURL(
        message: String,
        callbackUrl: String
    ): String {
        val baseUrl = if (config.isTestnet) config.testnetUrl else config.mainnetUrl
        val encodedMessage = android.util.Base64.encodeToString(
            message.toByteArray(),
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP
        )
        val encodedCallback = Uri.encode(callbackUrl)

        return "$baseUrl/sign-message?message=$encodedMessage&callback=$encodedCallback"
    }

    fun buildJoyIDSignCKBURL(
        rawTx: String,
        callbackUrl: String
    ): String {
        val baseUrl = if (config.isTestnet) config.testnetUrl else config.mainnetUrl
        val encodedTx = android.util.Base64.encodeToString(
            rawTx.toByteArray(),
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP
        )
        val encodedCallback = Uri.encode(callbackUrl)

        return "$baseUrl/sign-ckb-raw-tx?tx=$encodedTx&callback=$encodedCallback"
    }

    fun parseCallbackResult(uri: Uri): JoyIDSignResult? {
        val signature = uri.getQueryParameter("signature") ?: return null
        val message = uri.getQueryParameter("message") ?: return null
        val pubkeyHash = uri.getQueryParameter("pubkey_hash") ?: return null

        return JoyIDSignResult(
            signature = base64UrlToHex(signature),
            message = message,
            pubkeyHash = pubkeyHash
        )
    }

    fun base64UrlToHex(base64Url: String): String {
        val decoded = android.util.Base64.decode(
            base64Url,
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP
        )
        return decoded.joinToString("") { "%02x".format(it) }
    }

    fun hexToBase64Url(hex: String): String {
        val bytes = ByteArray(hex.length / 2)
        for (i in bytes.indices) {
            bytes[i] = hex.substring(i * 2, i * 2 + 2).toByte(16)
        }
        return android.util.Base64.encodeToString(
            bytes,
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP
        )
    }

    fun derToP1363(derSignature: String): String {
        val derBytes = hexToBytes(derSignature)
        if (derBytes.size < 8) return derSignature

        var offset = 0
        if (derBytes[offset] != 0x30.toByte()) return derSignature
        offset++

        val totalLength = derBytes[offset].toInt() and 0xFF
        offset++

        if (derBytes[offset] != 0x02.toByte()) return derSignature
        offset++

        val rLength = derBytes[offset].toInt() and 0xFF
        offset++

        val r = derBytes.copyOfRange(offset, offset + rLength)
        offset += rLength

        if (derBytes[offset] != 0x02.toByte()) return derSignature
        offset++

        val sLength = derBytes[offset].toInt() and 0xFF
        offset++

        val s = derBytes.copyOfRange(offset, offset + sLength)

        val rPadded = r.takeLast(32).toByteArray().let {
            if (it.size < 32) ByteArray(32 - it.size) + it else it
        }
        val sPadded = s.takeLast(32).toByteArray().let {
            if (it.size < 32) ByteArray(32 - it.size) + it else it
        }

        return (rPadded + sPadded).joinToString("") { "%02x".format(it) }
    }

    fun calculateChallenge(message: String): String {
        val bytes = message.toByteArray()
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(bytes)
        return hash.joinToString("") { "%02x".format(it) }
    }

    private fun hexToBytes(hex: String): ByteArray {
        val cleanHex = hex.removePrefix("0x")
        val bytes = ByteArray(cleanHex.length / 2)
        for (i in bytes.indices) {
            bytes[i] = cleanHex.substring(i * 2, i * 2 + 2).toByte(16)
        }
        return bytes
    }

    private fun saveConfig() {
        try {
            val jsonString = json.encodeToString(config)
            prefs.edit().putString("config", jsonString).apply()
        } catch (e: Exception) {
            // Handle serialization error
        }
    }

    private fun loadConfig() {
        val jsonString = prefs.getString("config", null)
        if (jsonString != null) {
            try {
                config = json.decodeFromString(jsonString)
            } catch (e: Exception) {
                config = JoyIDConfig()
            }
        }
    }
}

@Serializable
data class JoyIDSignResult(
    val signature: String,
    val message: String,
    val pubkeyHash: String
)
