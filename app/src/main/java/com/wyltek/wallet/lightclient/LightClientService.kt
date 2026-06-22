package com.wyltek.wallet.lightclient

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import com.wyltek.wallet.agent.service.AgentNotifications
import com.wyltek.wallet.core.chain.LightClientConfig
import java.io.File

/**
 * Foreground service that runs the embedded ckb-light-client subprocess.
 * The binary ships as libckblightclient.so in jniLibs and is exec'd from the
 * read-only nativeLibraryDir (the only exec-permitted location on API 29+).
 */
class LightClientService : Service() {

    @Volatile private var process: Process? = null

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() { super.onCreate(); AgentNotifications.ensureChannels(this) }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart()
            ACTION_STOP -> handleStop()
        }
        return START_NOT_STICKY
    }

    private fun handleStart() {
        if (running) return
        val bin = File(applicationInfo.nativeLibraryDir, "libckblightclient.so")
        if (!bin.exists()) { Log.e(TAG, "binary missing at ${bin.path}"); stopSelf(); return }

        val root = File(filesDir, "lightclient").apply { mkdirs() }
        val store = File(root, "store").apply { mkdirs() }
        val network = File(root, "network").apply { mkdirs() }
        val configFile = File(root, "config.toml")
        configFile.writeText(
            LightClientConfig.generate(
                storePath = store.absolutePath,
                networkPath = network.absolutePath,
                bootnodes = TESTNET_BOOTNODES
            )
        )

        try {
            process = ProcessBuilder(bin.absolutePath, "run", "--config-file", configFile.absolutePath)
                .redirectErrorStream(true)
                .redirectOutput(File(root, "lc.log"))
                .start()
            running = true
            Log.i(TAG, "light client started ($bin)")
        } catch (e: Exception) {
            Log.e(TAG, "failed to start light client: ${e.message}")
            stopSelf(); return
        }

        startForeground(
            NOTIF_ID,
            AgentNotifications.serverNotification(this, "light client: 127.0.0.1:$RPC_PORT"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    }

    private fun handleStop() {
        process?.destroy()
        process = null
        running = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.i(TAG, "light client stopped")
    }

    override fun onDestroy() {
        super.onDestroy()
        process?.destroy(); process = null; running = false
    }

    companion object {
        const val ACTION_START = "com.wyltek.wallet.lightclient.ACTION_START"
        const val ACTION_STOP = "com.wyltek.wallet.lightclient.ACTION_STOP"
        const val RPC_PORT = 9000
        const val RPC_URL = "http://127.0.0.1:9000"
        private const val TAG = "LightClientService"
        private const val NOTIF_ID = 1101

        val TESTNET_BOOTNODES = listOf(
            "/ip4/18.217.146.65/tcp/8111/p2p/QmT6DFfm18wtbJz3y4aPNn3ac86N4d4p4xtfQRRPf73frC",
            "/ip4/18.136.60.221/tcp/8111/p2p/QmTt6HeNakL8Fpmevrhdna7J4NzEMf9pLchf1CXtmtSrwb",
            "/ip4/35.176.207.239/tcp/8111/p2p/QmSJTsMsMGBjzv1oBNwQU36VhQRxc2WQpFoRu1ZifYKrjZ",
            "/ip4/13.228.149.113/tcp/8111/p2p/QmQoTR39rBkpZVgLApDGDoFnJ2YDBS9hYeiib1Z6aoAdEf",
            "/ip4/34.216.103.183/tcp/8111/p2p/Qmd41MaByDprkC5gP1XBKgamZ9DTLNk37zbPgwtiWCzRV6"
        )

        @Volatile var running: Boolean = false; private set

        fun start(context: Context) =
            context.startForegroundService(Intent(context, LightClientService::class.java).apply { action = ACTION_START })
        fun stop(context: Context) =
            context.startService(Intent(context, LightClientService::class.java).apply { action = ACTION_STOP })
        fun isRunning() = running
    }
}
