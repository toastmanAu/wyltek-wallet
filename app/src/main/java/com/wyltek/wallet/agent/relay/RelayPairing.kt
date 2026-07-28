package com.wyltek.wallet.agent.relay

import android.util.Log
import com.wyltek.wallet.agent.AgentKeyStore
import com.wyltek.wallet.agent.store.AgentSecureStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.SecureRandom
import java.util.UUID

/**
 * Handles one-time device pairing with the relay server.
 *
 * Pairing generates a random 32-byte [deviceToken] (hex) and a stable [deviceId] (UUID),
 * persists both in [AgentSecureStore], reads the Ed25519 root public key from [keyStore],
 * and POSTs to `$relayBaseUrl/pair`.
 *
 * Call [pair] once from the pairing UI flow; subsequent service starts read the persisted values.
 */
class RelayPairing(
    private val keyStore: AgentKeyStore,
    private val secure: AgentSecureStore,
) {
    private val TAG = "RelayPairing"
    private val json = "application/json".toMediaType()
    private val http = OkHttpClient()

    suspend fun pair(relayBaseUrl: String): Boolean = withContext(Dispatchers.IO) {
        // 1. Generate or load persistent device_id (stable UUID)
        val deviceId = loadOrGenerateDeviceId()

        // 2. Generate a fresh 32-byte deviceToken (replace any prior one on re-pair)
        val tokenBytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val deviceToken = tokenBytes.toHex()

        // 3. Read the Ed25519 root public key
        val rootPubHex = keyStore.rootPublicHex()

        // 4. POST /pair
        val body = JSONObject()
            .put("device_id",    deviceId)
            .put("root_pub_hex", rootPubHex)
            .put("device_token", deviceToken)
            .put("fcm_token",    "")        // Task 6 will wire FCM
            .toString()
            .toRequestBody(json)

        val pairUrl = relayBaseUrl.trimEnd('/') + "/pair"
        val ok = try {
            http.newCall(
                Request.Builder().url(pairUrl).post(body).build()
            ).execute().use { resp ->
                Log.i(TAG, "pair POST $pairUrl → ${resp.code}")
                resp.isSuccessful
            }
        } catch (e: Exception) {
            Log.e(TAG, "pair POST failed: ${e.message}")
            false
        }

        if (ok) {
            // 5. Persist relay config so the service can reconnect on next start
            secure.storeBlob(KEY_RELAY_BASE_URL,   relayBaseUrl.toByteArray(Charsets.UTF_8))
            secure.storeBlob(KEY_RELAY_DEVICE_TOKEN, deviceToken.toByteArray(Charsets.UTF_8))
            secure.storeBlob(KEY_RELAY_DEVICE_ID,   deviceId.toByteArray(Charsets.UTF_8))
        }
        ok
    }

    private fun loadOrGenerateDeviceId(): String {
        val existing = secure.loadBlob(KEY_RELAY_DEVICE_ID)
        if (existing != null) return String(existing, Charsets.UTF_8)
        val id = UUID.randomUUID().toString()
        secure.storeBlob(KEY_RELAY_DEVICE_ID, id.toByteArray(Charsets.UTF_8))
        return id
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it) }

    companion object {
        const val KEY_RELAY_BASE_URL     = "relay_base_url"
        const val KEY_RELAY_DEVICE_TOKEN = "relay_device_token"
        const val KEY_RELAY_DEVICE_ID    = "relay_device_id"

        /** Read relay config from the store; returns null if not paired. */
        fun loadConfig(secure: AgentSecureStore): RelayConfig? {
            val baseUrl     = secure.loadBlob(KEY_RELAY_BASE_URL)?.let { String(it, Charsets.UTF_8) } ?: return null
            val deviceToken = secure.loadBlob(KEY_RELAY_DEVICE_TOKEN)?.let { String(it, Charsets.UTF_8) } ?: return null
            return RelayConfig(
                wsUrl       = baseUrl.trimEnd('/').replace("http", "ws") + "/relay/device",
                resultUrl   = baseUrl.trimEnd('/') + "/relay/result",
                deviceToken = deviceToken,
            )
        }

        /** Stored relay device id, or null if this device never paired. */
        fun deviceId(secure: AgentSecureStore): String? =
            secure.loadBlob(KEY_RELAY_DEVICE_ID)
                ?.let { String(it, Charsets.UTF_8) }
                ?.ifBlank { null }

        /** Load the stable per-device id, generating + persisting one if absent. Independent
         *  of relay pairing — Tier-1 companion-direct provisioning needs a device_id without a
         *  relay, and both the QR bundle and the mDNS advertise must derive from the SAME id. */
        fun ensureDeviceId(secure: AgentSecureStore): String {
            deviceId(secure)?.let { return it }
            val id = UUID.randomUUID().toString()
            secure.storeBlob(KEY_RELAY_DEVICE_ID, id.toByteArray(Charsets.UTF_8))
            return id
        }
    }
}

data class RelayConfig(
    val wsUrl: String,
    val resultUrl: String,
    val deviceToken: String,
)
