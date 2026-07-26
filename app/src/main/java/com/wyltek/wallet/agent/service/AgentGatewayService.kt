package com.wyltek.wallet.agent.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.wyltek.wallet.WyltekWalletApp
import com.wyltek.wallet.agent.provisioning.serviceNameFor
import com.wyltek.wallet.agent.relay.RelayClient
import com.wyltek.wallet.agent.relay.RelayPairing
import com.wyltek.wallet.agent.server.AgentDispatchPort
import com.wyltek.wallet.agent.server.AgentHttpServer
import com.wyltek.wallet.agent.server.AgentMdns
import com.wyltek.wallet.agent.server.AgentTls
import com.wyltek.wallet.agent.server.LanAddress
import com.wyltek.wallet.agent.server.Tailnet
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Foreground service that hosts the NanoHTTPD HTTPS server on the device's LAN (Wi-Fi) address.
 * TLS uses a persisted self-signed cert (see [AgentTls]) — trust comes from the biscuit token,
 * not the cert.
 *
 * NanoHTTPD binds a single host — this deliberately drops the old Ktor CIO Tailnet co-connector
 * simplification; Tier-1 is LAN, so LAN wins when both a LAN and a Tailnet address are present.
 *
 * Lifecycle:
 *  - ACTION_START: resolve LAN/Tailnet addresses, start the NanoHTTPD server, call startForeground
 *  - ACTION_STOP: stop server, stopForeground, stopSelf
 */
class AgentGatewayService : Service() {

    @Volatile private var server: NanoHTTPD? = null
    private var relayClient: RelayClient? = null
    @Volatile private var mdns: AgentMdns? = null
    private var netCallback: android.net.ConnectivityManager.NetworkCallback? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val rebindMutex = Mutex()

    // Set as the very first step of a real stop (handleStop, main thread) and cleared near the
    // top of a real start (handleStart, main thread); read from the serviceScope (IO) coroutine
    // in restart() to detect a stop that began mid-rebind. @Volatile for cross-thread visibility.
    @Volatile private var stopping = false

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
        val lan = LanAddress.bindAddress()
        val tailnet = Tailnet.bindAddress()
        if (lan == null && tailnet == null) {
            Log.w(TAG, "no LAN or Tailnet address — cannot start agent gateway")
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.notify(
                NOTIF_ID_TAILNET_ERROR,
                AgentNotifications.serverNotification(this, "no local network")
            )
            stopSelf()
            return
        }

        val boundAddr = lan ?: tailnet
        stopping = false // defensive reset — a stray stop shouldn't poison the next start

        startForeground(
            NOTIF_ID,
            AgentNotifications.serverNotification(this, "$boundAddr:$SERVER_PORT"),
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )

        startServerAndMdns()
        if (server == null) {
            // §9: bind failed on the initial start (not a Wi-Fi rebind) — log+notify already
            // happened inside startServerAndMdns(); nothing left to run this service for.
            Log.e(TAG, "initial server bind failed — stopping agent gateway service")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        val androidApp = application as WyltekWalletApp

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

        registerWifiCallback()
        running = true
        Log.i(TAG, "Agent gateway started on $boundAddr:$SERVER_PORT")
    }

    /**
     * Bind the NanoHTTPD server (LAN address, falling back to Tailnet — NanoHTTPD binds a
     * single host) and register mDNS. No foreground notification, relay client, or
     * network-callback side effects — safe to call again for a Wi-Fi rebind without touching
     * the rest of the service's lifecycle.
     */
    private fun startServerAndMdns() {
        val androidApp = application as WyltekWalletApp
        val dispatchPort: AgentDispatchPort = androidApp.agentGateway
        val lan = LanAddress.bindAddress()
        val tailnet = Tailnet.bindAddress()
        if (lan == null && tailnet == null) {
            Log.w(TAG, "rebind: no LAN/Tailnet address")
            return
        }
        // §9: keystore load and server bind can both fail (corrupt/foreign keystore file,
        // port already bound, etc). Neither should crash this foreground service — log, notify,
        // leave `server = null`, and let the caller (handleStart) decide whether to stopSelf().
        try {
            val ks = AgentTls.keyStore(this)
            val srv = AgentHttpServer(lan ?: tailnet!!, SERVER_PORT, dispatchPort)
            srv.secure(ks, AgentTls.password())
            srv.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            server = srv
        } catch (e: Exception) {
            Log.e(TAG, "TLS keystore load/bind failed — agent gateway not started", e)
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.notify(
                NOTIF_ID_TAILNET_ERROR,
                AgentNotifications.serverNotification(this, "tls/bind error")
            )
            server = null
            return
        }

        if (lan != null) {
            // ensure (not just read) so the advertised service_name matches the QR's, even on a
            // Tier-1 device that was never relay-paired (same stable id from the secure store).
            val deviceId = RelayPairing.ensureDeviceId(androidApp.agentGateway.secure)
            val svc = serviceNameFor(deviceId)
            mdns = AgentMdns(this).also { it.register(svc, deviceId, SERVER_PORT) }
        }
        Log.i(TAG, "gateway server on ${lan ?: tailnet}:$SERVER_PORT")
    }

    /** Stop the server + mDNS only. Leaves foreground state, relay, network callback, and
     *  serviceScope intact — used both by the real stop path and by the Wi-Fi rebind path. */
    private fun stopServerAndMdns() {
        mdns?.unregister(); mdns = null
        server?.stop()
        server = null
    }

    private fun registerWifiCallback() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val cb = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) = restart()
            override fun onLost(network: android.net.Network) = restart()
        }
        val req = android.net.NetworkRequest.Builder()
            .addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI).build()
        cm.registerNetworkCallback(req, cb)
        netCallback = cb
    }

    /**
     * Wi-Fi changed: rebind the server + re-advertise mDNS only. Deliberately does NOT call
     * handleStop()/handleStart() — those touch startForeground/stopSelf/serviceScope/netCallback,
     * and stopSelf() queues an async onDestroy() that would race a rebind and tear down the
     * freshly-restarted state (or permanently cancel serviceScope). Mutex-guarded because
     * onAvailable/onLost can fire back-to-back on a single Wi-Fi flip.
     *
     * Re-checks `stopping`/`running` three times: before stopServerAndMdns(), after it (before
     * rebinding), and once more after startServerAndMdns() binds the new server/mdns — undoing
     * the rebind if a stop raced in during the bind itself. handleStop() (main thread, unguarded
     * by rebindMutex) sets `stopping = true` as its very first line, so any of these checks will
     * observe a concurrent stop and either bail or self-undo instead of leaving a server/mDNS
     * advertisement bound with no handle left to stop it (onDestroy()'s cleanup is `if (running)`,
     * which is already false once handleStop() has run).
     */
    private fun restart() {
        if (!running) return
        serviceScope.launch { // hop off the callback thread
            rebindMutex.withLock {
                if (!running || stopping) return@withLock // a real stop won the race
                stopServerAndMdns()
                if (stopping || !running) return@withLock // a real stop began mid-rebind — don't resurrect
                startServerAndMdns()
                if (stopping || !running) stopServerAndMdns() // a stop raced our rebind — undo it
            }
        }
    }

    private fun handleStop() {
        stopping = true
        stopServerAndMdns()
        relayClient?.disconnect()
        relayClient = null
        netCallback?.let {
            (getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager)
                .unregisterNetworkCallback(it)
        }
        netCallback = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        running = false
        Log.i(TAG, "Agent gateway stopped")
    }

    override fun onDestroy() {
        super.onDestroy()
        mdns?.unregister(); mdns = null
        netCallback?.let {
            (getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager)
                .unregisterNetworkCallback(it)
        }
        netCallback = null
        relayClient?.disconnect()
        relayClient = null
        if (running) {
            server?.stop()
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
