package com.wyltek.wallet.agent.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.wyltek.wallet.WyltekWalletApp
import com.wyltek.wallet.agent.relay.RelayClient
import com.wyltek.wallet.agent.relay.RelayPairing
import com.wyltek.wallet.agent.server.AgentDispatchPort
import com.wyltek.wallet.agent.server.Tailnet
import com.wyltek.wallet.agent.server.agentModule
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Foreground service that hosts the Ktor CIO HTTP server on the device's Tailscale address.
 *
 * Lifecycle:
 *  - ACTION_START: resolve Tailnet address, start CIO server, call startForeground
 *  - ACTION_STOP: stop server, stopForeground, stopSelf
 *
 * The Android [application] property is captured into a local BEFORE entering the Ktor lambda
 * because Ktor's ApplicationCall receiver shadows the Android property name.
 */
class AgentGatewayService : Service() {

    private var server: EmbeddedServer<*, *>? = null
    private var relayClient: RelayClient? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        AgentNotifications.ensureChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart()
            ACTION_STOP -> handleStop()
        }
        return START_NOT_STICKY
    }

    private fun handleStart() {
        val bindAddr = Tailnet.bindAddress()
        if (bindAddr == null) {
            Log.w(TAG, "Tailnet unavailable — cannot start agent gateway")
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.notify(
                NOTIF_ID_TAILNET_ERROR,
                AgentNotifications.serverNotification(this, "tailnet unavailable")
            )
            stopSelf()
            return
        }

        // Capture the Android Application before entering the Ktor lambda — Ktor's
        // Application receiver would shadow the Android `application` property otherwise.
        val androidApp = application as WyltekWalletApp
        val port: AgentDispatchPort = androidApp.agentGateway

        server = embeddedServer(CIO, host = bindAddr, port = SERVER_PORT) {
            agentModule(port)
        }.start(wait = false)

        // Start relay client if already paired
        val relayConfig = RelayPairing.loadConfig(androidApp.agentGateway.secure)
        if (relayConfig != null) {
            val client = RelayClient(
                wsUrl       = relayConfig.wsUrl,
                resultUrl   = relayConfig.resultUrl,
                deviceToken = relayConfig.deviceToken,
                dispatchPort = androidApp.agentGateway,
                scope       = serviceScope,
                pendingStore = androidApp.agentGateway.pendingStore,
            )
            client.connect()
            relayClient = client
            Log.i(TAG, "Relay client connected to ${relayConfig.wsUrl}")
        } else {
            Log.i(TAG, "No relay pairing found — relay client not started")
        }

        running = true
        Log.i(TAG, "Agent gateway started on $bindAddr:$SERVER_PORT")

        startForeground(
            NOTIF_ID,
            AgentNotifications.serverNotification(this, "$bindAddr:$SERVER_PORT"),
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    }

    private fun handleStop() {
        relayClient?.disconnect()
        relayClient = null
        server?.stop(gracePeriodMillis = 500, timeoutMillis = 2000)
        server = null
        running = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.i(TAG, "Agent gateway stopped")
    }

    override fun onDestroy() {
        super.onDestroy()
        relayClient?.disconnect()
        relayClient = null
        if (running) {
            server?.stop(gracePeriodMillis = 100, timeoutMillis = 500)
            server = null
            running = false
        }
        serviceScope.cancel()
    }

    companion object {
        const val ACTION_START = "com.wyltek.wallet.agent.ACTION_START"
        const val ACTION_STOP = "com.wyltek.wallet.agent.ACTION_STOP"

        private const val TAG = "AgentGatewayService"
        private const val NOTIF_ID = 1001
        private const val NOTIF_ID_TAILNET_ERROR = 1002
        private const val SERVER_PORT = 8443

        @Volatile
        var running: Boolean = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, AgentGatewayService::class.java).apply {
                action = ACTION_START
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, AgentGatewayService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        fun isRunning(): Boolean = running
    }
}
