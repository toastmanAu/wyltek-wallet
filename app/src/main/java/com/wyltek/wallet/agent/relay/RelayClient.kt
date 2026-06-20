package com.wyltek.wallet.agent.relay

import android.util.Log
import com.wyltek.wallet.agent.DispatchResult
import com.wyltek.wallet.agent.server.AgentDispatchPort
import com.wyltek.wallet.core.native.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Persistent WebSocket drain client for the relay server.
 *
 * Connects to [wsUrl] (wss://relay/relay/device) with header x-device-token,
 * receives intent frames, dispatches via [dispatchPort], and POSTs results to [resultUrl].
 *
 * Reconnects with exponential back-off on failure. Heartbeat ping every 25s (server sees
 * the OkHttp pingInterval of 30s as its keepalive).
 */
class RelayClient(
    private val wsUrl: String,
    private val resultUrl: String,
    private val deviceToken: String,
    private val dispatchPort: AgentDispatchPort,
    private val scope: CoroutineScope,
) {
    private val TAG = "RelayClient"
    private val json = "application/json".toMediaType()

    private val http = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    @Volatile private var ws: WebSocket? = null
    @Volatile private var reconnectDelayMs: Long = 5_000L
    private var heartbeatJob: Job? = null

    fun connect() {
        val req = Request.Builder()
            .url(wsUrl)
            .addHeader("x-device-token", deviceToken)
            .build()

        ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "Relay WS connected: $wsUrl")
                reconnectDelayMs = 5_000L
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val msg = try { JSONObject(text) } catch (e: Exception) {
                    Log.w(TAG, "Relay: bad JSON frame (${text.length} bytes)"); return
                }
                if (msg.optString("type") != "intent") return
                scope.launch(Dispatchers.IO) { handleIntent(msg) }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "Relay WS closed $code: $reason")
                ws = null
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "Relay WS failure: ${t.message}; reconnecting in ${reconnectDelayMs}ms")
                ws = null
                scope.launch {
                    delay(reconnectDelayMs)
                    reconnectDelayMs = minOf(reconnectDelayMs * 2, 120_000L)
                    connect()
                }
            }
        })

        // Heartbeat — keeps the server-side session alive between OkHttp pings
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(25_000)
                ws?.send("ping")
            }
        }
    }

    private suspend fun handleIntent(msg: JSONObject) {
        val intentId = msg.optString("intent_id").ifEmpty {
            Log.w(TAG, "Relay intent missing intent_id"); return
        }
        val intent = Intent(
            op     = msg.optString("op"),
            asset  = msg.optString("asset"),
            to     = msg.optString("to"),
            amount = msg.optLong("amount", 0L),
            nonce  = msg.optString("nonce"),
            action = msg.optString("action").ifEmpty { null },
            daoRef = msg.optString("dao_ref").ifEmpty { null },
        )
        val sourceIp = msg.optString("source_ip", "0.0.0.0")
        val token    = msg.optString("token")
        val now      = System.currentTimeMillis() / 1000

        val result = try {
            dispatchPort.dispatch(token, intent, sourceIp, now)
        } catch (e: Throwable) {
            Log.e(TAG, "dispatch threw: ${e.message}", e)
            postResult(intentId, "failed", null, e.message)
            return
        }

        val (status, txHash, error) = when (result) {
            is DispatchResult.Sent     -> Triple("sent",             result.txHash, null)
            is DispatchResult.Approval -> Triple("needs_approval",   null,          null)
            is DispatchResult.Denied   -> Triple("denied",           null,          result.reason)
            is DispatchResult.Failed   -> Triple("failed",           null,          result.message)
        }
        postResult(intentId, status, txHash, error)

        // TODO Task-4-relay: When approval completes (B2 executeApproved), call
        //   postRelayResult(resultUrl, deviceToken, intentId, status, txHash, error)
        // Track relay origin by storing intentId on PendingIntentEntity.relayIntentId.
    }

    private fun postResult(intentId: String, status: String, txHash: String?, error: String?) {
        val body = JSONObject()
            .put("device_token", deviceToken)
            .put("intent_id",    intentId)
            .put("status",       status)
            .put("tx_hash",      txHash)
            .put("error",        error)
            .toString()
            .toRequestBody(json)
        try {
            http.newCall(
                Request.Builder().url(resultUrl).post(body).build()
            ).execute().use { resp ->
                if (!resp.isSuccessful) Log.w(TAG, "relay result POST ${resp.code}: $intentId")
            }
        } catch (e: Exception) {
            Log.e(TAG, "relay result POST failed: ${e.message}")
        }
    }

    /**
     * Helper for approval-completion cross-plan wiring (Plan B2).
     * Call this from AgentActionDispatcher.executeApproved when the PendingIntentEntity
     * has a non-null relayIntentId.
     */
    suspend fun postRelayResult(
        intentId: String,
        status: String,
        txHash: String?,
        error: String?
    ) {
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            postResult(intentId, status, txHash, error)
        }
    }

    fun disconnect() {
        heartbeatJob?.cancel()
        ws?.close(1000, "bye")
        ws = null
    }
}
