# Light-Client Integration — Phase 1 (Engine) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the app run the embedded `ckb-light-client` binary as a foreground-service subprocess and query it through a `LightClientProvider` that satisfies the existing `ChainProvider` interface.

**Architecture:** Ship the cross-compiled `libckblightclient.so` in `jniLibs` with legacy packaging so it extracts to `nativeLibraryDir` (the only exec-permitted path on Android). A `LightClientService` (modeled on `AgentGatewayService`) writes a generated `config.toml`, execs the binary, and supervises the process. A `LightClientProvider` delegates every chain query to an internal `RpcProfile("http://127.0.0.1:9000")` (the light client serves the identical indexer RPC) and adds a `registerScripts` step that calls `set_scripts` — the one method a light client needs that the full-node profile does not.

**Tech Stack:** Kotlin, Android foreground service, OkHttp/kotlinx-serialization (existing `CkbRpcClient`), cargo-ndk-built `ckb-light-client` (SQLite, proven in the build spike).

## Global Constraints

- Module split: chain/RPC types live in `wallet-core` (`com.wyltek.wallet.core.chain`); Android service + UI live in `app` (`com.wyltek.wallet`). Copy exact-value verbatim.
- Target ABI: **arm64-v8a only** (the only ABI built). minSdk 26, targetSdk/compileSdk 35.
- The light client is **testnet-only** for now (the spike binary + locks are testnet). Keep it labelled testnet.
- The binary must run from `applicationInfo.nativeLibraryDir` (read-only, exec-permitted). Do NOT copy it to `filesDir` and exec — Android blocks exec from writable app storage on API 29+.
- RPC bind stays `127.0.0.1:9000` (localhost only — never expose).
- Do NOT commit the 19 MB binary to git. It is staged by `scripts/build-light-client-android.sh` and gitignored under `jniLibs`.
- Phase 1 does **not** touch Settings UI, `ChainManager` failover registration, or `RpcHealthChecker` — those are Phase 2.

---

### Task 1: Package the native binary so it extracts to `nativeLibraryDir`

**Files:**
- Create: `app/src/main/jniLibs/arm64-v8a/libckblightclient.so` (staged by the build script; gitignored)
- Modify: `app/build.gradle.kts` (the `packaging { }` block at lines ~48-52)
- Modify: `.gitignore`
- Modify: `scripts/build-light-client-android.sh` (add a copy-into-jniLibs step)

**Interfaces:**
- Produces: a runnable binary at `applicationInfo.nativeLibraryDir + "/libckblightclient.so"` on-device, consumed by Task 5.

- [ ] **Step 1: Gitignore the staged binary**

Add to `.gitignore`:
```
app/src/main/jniLibs/**/libckblightclient.so
```

- [ ] **Step 2: Stage the binary into jniLibs from the build script**

Append to the end of `scripts/build-light-client-android.sh` (after the existing strip/stage block):
```bash
# Place the stripped lib where the app packages native code from.
JNI_DST="$REPO_ROOT/app/src/main/jniLibs/arm64-v8a"
if [[ -f "$REPO_ROOT/build/libckblightclient.so" ]]; then
  mkdir -p "$JNI_DST"
  cp "$REPO_ROOT/build/libckblightclient.so" "$JNI_DST/libckblightclient.so"
  echo "==> placed in jniLibs: $JNI_DST/libckblightclient.so"
fi
```
Run it: `scripts/build-light-client-android.sh arm64-v8a` (uses the already-cloned source — fast).

- [ ] **Step 3: Enable legacy jniLibs packaging so the .so extracts to nativeLibraryDir**

In `app/build.gradle.kts`, change the `packaging { }` block to:
```kotlin
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            // Extract native libs to nativeLibraryDir so the light-client binary
            // can be exec'd (Android blocks exec from compressed-in-APK libs).
            useLegacyPackaging = true
        }
    }
```

- [ ] **Step 4: Build the app and verify the binary lands in nativeLibraryDir on-device**

Run:
```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell run-as com.wyltek.wallet sh -c 'ls -l $(dirname $(pwd))/lib/* 2>/dev/null || true'
adb shell "ls -l /data/app/*/com.wyltek.wallet*/lib/arm64/libckblightclient.so 2>/dev/null || pm path com.wyltek.wallet"
```
Expected: `libckblightclient.so` present under the app's `lib/arm64` dir (its `nativeLibraryDir`).

- [ ] **Step 5: Commit**

```bash
git add app/build.gradle.kts .gitignore scripts/build-light-client-android.sh
git commit -m "build(light-client): legacy jniLibs packaging + stage binary into app"
```

---

### Task 2: `LightClientConfig` — pure config.toml generator (TDD)

**Files:**
- Create: `wallet-core/src/main/java/com/wyltek/wallet/core/chain/LightClientConfig.kt`
- Test: `wallet-core/src/test/java/com/wyltek/wallet/core/chain/LightClientConfigTest.kt`

**Interfaces:**
- Produces: `object LightClientConfig { fun generate(storePath: String, networkPath: String, rpcListen: String = "127.0.0.1:9000", chain: String = "testnet", bootnodes: List<String>): String }` — consumed by Task 5.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.wyltek.wallet.core.chain

import org.junit.Assert.assertTrue
import org.junit.Test

class LightClientConfigTest {
    private val boots = listOf("/ip4/18.217.146.65/tcp/8111/p2p/QmT6DFfm18wtbJz3y4aPNn3ac86N4d4p4xtfQRRPf73frC")

    @Test fun emits_required_sections_and_values() {
        val toml = LightClientConfig.generate(
            storePath = "/data/lc/store",
            networkPath = "/data/lc/network",
            bootnodes = boots
        )
        assertTrue(toml.contains("chain = \"testnet\""))
        assertTrue(toml.contains("[store]"))
        assertTrue(toml.contains("path = \"/data/lc/store\""))
        assertTrue(toml.contains("[network]"))
        assertTrue(toml.contains("path = \"/data/lc/network\""))
        assertTrue(toml.contains("[rpc]"))
        assertTrue(toml.contains("listen_address = \"127.0.0.1:9000\""))
        // UPnP must stay off on mobile (dormant openssl path; CGNAT anyway).
        assertTrue(toml.contains("upnp = false"))
        assertTrue(toml.contains(boots[0]))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :wallet-core:testDebugUnitTest --tests "*LightClientConfigTest*"`
Expected: FAIL — `LightClientConfig` unresolved.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.wyltek.wallet.core.chain

/** Generates the `config.toml` the embedded ckb-light-client reads at startup. */
object LightClientConfig {
    fun generate(
        storePath: String,
        networkPath: String,
        rpcListen: String = "127.0.0.1:9000",
        chain: String = "testnet",
        bootnodes: List<String>
    ): String {
        val boots = bootnodes.joinToString(",\n") { "  \"$it\"" }
        return """
            chain = "$chain"

            [store]
            path = "$storePath"

            [network]
            path = "$networkPath"
            listen_addresses = ["/ip4/0.0.0.0/tcp/8118"]
            bootnodes = [
            $boots
            ]
            max_peers = 125
            max_outbound_peers = 8
            ping_interval_secs = 120
            ping_timeout_secs = 1200
            connect_outbound_interval_secs = 15
            upnp = false
            discovery_local_address = false
            bootnode_mode = false

            [rpc]
            listen_address = "$rpcListen"
        """.trimIndent()
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :wallet-core:testDebugUnitTest --tests "*LightClientConfigTest*"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add wallet-core/src/main/java/com/wyltek/wallet/core/chain/LightClientConfig.kt \
        wallet-core/src/test/java/com/wyltek/wallet/core/chain/LightClientConfigTest.kt
git commit -m "feat(light-client): config.toml generator"
```

---

### Task 3: `ScriptStatus` + `set_scripts`/`get_scripts` on `CkbRpcClient` (TDD on serialization)

**Files:**
- Modify: `wallet-core/src/main/java/com/wyltek/wallet/core/chain/CkbRpcClient.kt` (add type + two methods)
- Test: `wallet-core/src/test/java/com/wyltek/wallet/core/chain/ScriptStatusTest.kt`

**Interfaces:**
- Consumes: existing `Script` serializable DTO (`code_hash`, `hash_type`, `args`) and the private `call(method, params)` in `CkbRpcClient`.
- Produces:
  - `@Serializable data class ScriptStatus(val script: Script, @SerialName("script_type") val scriptType: String = "lock", @SerialName("block_number") val blockNumber: String)`
  - `suspend fun CkbRpcClient.setScripts(scripts: List<ScriptStatus>)`
  - `suspend fun CkbRpcClient.getScripts(): List<ScriptStatus>`
  Consumed by Task 4.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.wyltek.wallet.core.chain

import kotlinx.serialization.json.Json
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptStatusTest {
    @Test fun serializes_to_light_client_set_scripts_shape() {
        val s = ScriptStatus(
            script = Script(code_hash = "0xdead", hash_type = "type", args = "0xbeef"),
            blockNumber = "0x148335b"
        )
        val out = Json.encodeToString(ScriptStatus.serializer(), s)
        assertTrue(out.contains("\"script_type\":\"lock\""))
        assertTrue(out.contains("\"block_number\":\"0x148335b\""))
        assertTrue(out.contains("\"code_hash\":\"0xdead\""))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :wallet-core:testDebugUnitTest --tests "*ScriptStatusTest*"`
Expected: FAIL — `ScriptStatus` unresolved.

- [ ] **Step 3: Add the type and methods**

In `CkbRpcClient.kt`, add the import `import kotlinx.serialization.SerialName` and `import kotlinx.serialization.builtins.ListSerializer` at the top, then add at file scope (outside the class):
```kotlin
@kotlinx.serialization.Serializable
data class ScriptStatus(
    val script: Script,
    @SerialName("script_type") val scriptType: String = "lock",
    @SerialName("block_number") val blockNumber: String
)
```
And inside `class CkbRpcClient`, add:
```kotlin
    /** Register lock scripts for the light client to sync from `blockNumber`. */
    suspend fun setScripts(scripts: List<ScriptStatus>) {
        val params = buildJsonArray {
            add(json.encodeToJsonElement(ListSerializer(ScriptStatus.serializer()), scripts))
        }
        call("set_scripts", params)
    }

    /** The scripts the light client is currently watching. */
    suspend fun getScripts(): List<ScriptStatus> {
        val result = call("get_scripts")
        return json.decodeFromJsonElement(ListSerializer(ScriptStatus.serializer()), result)
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :wallet-core:testDebugUnitTest --tests "*ScriptStatusTest*"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add wallet-core/src/main/java/com/wyltek/wallet/core/chain/CkbRpcClient.kt \
        wallet-core/src/test/java/com/wyltek/wallet/core/chain/ScriptStatusTest.kt
git commit -m "feat(light-client): set_scripts/get_scripts + ScriptStatus type"
```

---

### Task 4: `LightClientProvider` — delegate queries to `RpcProfile`, add `registerScripts`

**Files:**
- Create: `wallet-core/src/main/java/com/wyltek/wallet/core/chain/LightClientProvider.kt`
- Test: `wallet-core/src/test/java/com/wyltek/wallet/core/chain/LightClientProviderTest.kt`

**Interfaces:**
- Consumes: `ChainProvider`, `RpcProfile`, `CkbRpcClient`, `ScriptStatus`, `LockScript`, `Script`.
- Produces:
  - `class LightClientProvider(url: String, override var isActive: Boolean = false) : ChainProvider` with `override val name = "Embedded Light Client"`.
  - pure helper `fun buildScriptStatuses(locks: List<LockScript>, startBlockHex: String): List<ScriptStatus>` (testable).
  - `suspend fun registerScripts(locks: List<LockScript>, startBlockHex: String)` and `suspend fun getRegisteredScripts(): List<ScriptStatus>`.
  Consumed by Task 6.

- [ ] **Step 1: Write the failing test (pure helper)**

```kotlin
package com.wyltek.wallet.core.chain

import com.wyltek.wallet.core.model.LockScript
import org.junit.Assert.assertEquals
import org.junit.Test

class LightClientProviderTest {
    @Test fun builds_one_script_status_per_lock_at_start_block() {
        val locks = listOf(
            LockScript(codeHash = "0xaa", hashType = "type", args = "0x01"),
            LockScript(codeHash = "0xbb", hashType = "type", args = "0x02"),
        )
        val statuses = LightClientProvider.buildScriptStatuses(locks, "0x148335b")
        assertEquals(2, statuses.size)
        assertEquals("lock", statuses[0].scriptType)
        assertEquals("0x148335b", statuses[0].blockNumber)
        assertEquals("0xaa", statuses[0].script.code_hash)
        assertEquals("0xbb", statuses[1].script.code_hash)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :wallet-core:testDebugUnitTest --tests "*LightClientProviderTest*"`
Expected: FAIL — unresolved.

- [ ] **Step 3: Write the implementation**

```kotlin
package com.wyltek.wallet.core.chain

import com.wyltek.wallet.core.model.*
import kotlinx.serialization.json.JsonElement

/**
 * A [ChainProvider] backed by the embedded light client's localhost JSON-RPC.
 * The light client serves the identical indexer surface as a full node's
 * indexer, so every query delegates to an internal [RpcProfile]. The one extra
 * concern is [registerScripts] — the light client only returns cells for lock
 * scripts registered via `set_scripts`.
 */
class LightClientProvider(
    val url: String,
    override var isActive: Boolean = false
) : ChainProvider {

    override val name: String = NAME

    private val inner = RpcProfile(NAME, url, isPrivate = true, isActive = isActive)
    private val client = CkbRpcClient(url)

    private fun String.withHexPrefix() = if (startsWith("0x", true)) this else "0x$this"

    /** Tell the light client which locks to watch, syncing from [startBlockHex]. */
    suspend fun registerScripts(locks: List<LockScript>, startBlockHex: String) {
        client.setScripts(buildScriptStatuses(locks, startBlockHex))
    }

    suspend fun getRegisteredScripts(): List<ScriptStatus> = client.getScripts()

    // --- ChainProvider delegation ---
    override suspend fun getCellsByLock(lockScript: LockScript) = inner.getCellsByLock(lockScript)
    override suspend fun getCellsByLockAndType(lockScript: LockScript, typeScript: LockScript) =
        inner.getCellsByLockAndType(lockScript, typeScript)
    override suspend fun getCellsCapacity(lockScript: LockScript) = inner.getCellsCapacity(lockScript)
    override suspend fun getTipHeader() = inner.getTipHeader()
    override suspend fun getHeaderByNumber(blockNumber: String) = inner.getHeaderByNumber(blockNumber)
    override suspend fun estimateFee(rate: ULong) = inner.estimateFee(rate)
    override suspend fun sendTransaction(transaction: Transaction) = inner.sendTransaction(transaction)
    override suspend fun sendTransactionJson(txJson: JsonElement) = inner.sendTransactionJson(txJson)
    override suspend fun getTransactionStatus(txHash: String) = inner.getTransactionStatus(txHash)
    override suspend fun getTransactionsByLock(lockScript: LockScript) = inner.getTransactionsByLock(lockScript)
    override suspend fun getTransactionDetail(txHash: String) = inner.getTransactionDetail(txHash)
    override suspend fun calculateDaoMaximumWithdraw(
        depositTxHash: String, depositIndex: String, withdrawBlockHash: String
    ) = inner.calculateDaoMaximumWithdraw(depositTxHash, depositIndex, withdrawBlockHash)

    companion object {
        const val NAME = "Embedded Light Client"

        /** Pure builder: one watched lock ScriptStatus per [locks] at [startBlockHex]. */
        fun buildScriptStatuses(locks: List<LockScript>, startBlockHex: String): List<ScriptStatus> =
            locks.map {
                ScriptStatus(
                    script = Script(
                        code_hash = if (it.codeHash.startsWith("0x", true)) it.codeHash else "0x${it.codeHash}",
                        hash_type = it.hashType,
                        args = if (it.args.startsWith("0x", true)) it.args else "0x${it.args}"
                    ),
                    blockNumber = startBlockHex
                )
            }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :wallet-core:testDebugUnitTest --tests "*LightClientProviderTest*"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add wallet-core/src/main/java/com/wyltek/wallet/core/chain/LightClientProvider.kt \
        wallet-core/src/test/java/com/wyltek/wallet/core/chain/LightClientProviderTest.kt
git commit -m "feat(light-client): LightClientProvider (delegates queries, adds set_scripts)"
```

---

### Task 5: `LightClientService` — foreground service that execs + supervises the binary

**Files:**
- Create: `app/src/main/java/com/wyltek/wallet/lightclient/LightClientService.kt`
- Modify: `app/src/main/AndroidManifest.xml` (declare the service)
- (Reference pattern: `app/src/main/java/com/wyltek/wallet/agent/service/AgentGatewayService.kt`)

**Interfaces:**
- Consumes: `LightClientConfig.generate(...)` (Task 2), the staged binary at `applicationInfo.nativeLibraryDir/libckblightclient.so` (Task 1).
- Produces: `LightClientService` with companion `start(context)`, `stop(context)`, `@Volatile var running` — consumed by Task 6.

- [ ] **Step 1: Implement the service**

```kotlin
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
            Log.i(TAG, "light client started (pid via $bin)")
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
```

- [ ] **Step 2: Declare the service in the manifest**

In `app/src/main/AndroidManifest.xml`, alongside the existing `AgentGatewayService` declaration (around line 34), add:
```xml
        <service
            android:name=".lightclient.LightClientService"
            android:exported="false"
            android:foregroundServiceType="dataSync" />
```

- [ ] **Step 3: Build**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: On-device verification**

```bash
./gradlew :app:assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start-foreground-service -a com.wyltek.wallet.lightclient.ACTION_START -n com.wyltek.wallet/.lightclient.LightClientService
sleep 20
adb forward tcp:19000 tcp:9000
curl -s -X POST http://127.0.0.1:19000 -H 'content-type: application/json' \
  -d '{"id":1,"jsonrpc":"2.0","method":"get_tip_header","params":[]}'
```
Expected: a JSON `result` header (the in-app service's light client is serving RPC). Then stop:
```bash
adb shell am start-service -a com.wyltek.wallet.lightclient.ACTION_STOP -n com.wyltek.wallet/.lightclient.LightClientService
adb forward --remove tcp:19000
```

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/wyltek/wallet/lightclient/LightClientService.kt app/src/main/AndroidManifest.xml
git commit -m "feat(light-client): foreground service execs the embedded binary"
```

---

### Task 6: `LightClientController` — orchestrate start → ready → register scripts; expose state

**Files:**
- Create: `app/src/main/java/com/wyltek/wallet/lightclient/LightClientController.kt`
- Test: `app/src/test/java/com/wyltek/wallet/lightclient/LightClientStateTest.kt`

**Interfaces:**
- Consumes: `LightClientService` (Task 5), `LightClientProvider` (Task 4), `HeaderInfo`.
- Produces: `enum class LightClientState { STOPPED, STARTING, SYNCING, READY, ERROR }`, a pure `fun classifyState(running: Boolean, clientTip: ULong?, networkTip: ULong?, lagThreshold: ULong = 50u): LightClientState`, and `class LightClientController(...)` exposing `val state: StateFlow<LightClientState>` plus `suspend fun start(locks, startBlockHex)` / `fun stop()`.

- [ ] **Step 1: Write the failing test (pure state classifier)**

```kotlin
package com.wyltek.wallet.lightclient

import org.junit.Assert.assertEquals
import org.junit.Test

class LightClientStateTest {
    @Test fun stopped_when_not_running() {
        assertEquals(LightClientState.STOPPED, classifyState(false, null, null))
    }
    @Test fun starting_when_running_but_no_tip_yet() {
        assertEquals(LightClientState.STARTING, classifyState(true, null, 100u))
    }
    @Test fun syncing_when_client_tip_lags_network() {
        assertEquals(LightClientState.SYNCING, classifyState(true, 100u, 1000u))
    }
    @Test fun ready_when_within_lag_threshold() {
        assertEquals(LightClientState.READY, classifyState(true, 970u, 1000u, lagThreshold = 50u))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*LightClientStateTest*"`
Expected: FAIL — unresolved.

- [ ] **Step 3: Implement state classifier + controller**

```kotlin
package com.wyltek.wallet.lightclient

import android.content.Context
import com.wyltek.wallet.core.chain.LightClientProvider
import com.wyltek.wallet.core.model.LockScript
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class LightClientState { STOPPED, STARTING, SYNCING, READY, ERROR }

/** Pure state classifier — decides READY vs SYNCING from tip lag. */
fun classifyState(
    running: Boolean,
    clientTip: ULong?,
    networkTip: ULong?,
    lagThreshold: ULong = 50u
): LightClientState = when {
    !running -> LightClientState.STOPPED
    clientTip == null -> LightClientState.STARTING
    networkTip == null -> LightClientState.SYNCING
    networkTip > clientTip && (networkTip - clientTip) > lagThreshold -> LightClientState.SYNCING
    else -> LightClientState.READY
}

/**
 * Orchestrates the light-client lifecycle: start the service, build a provider,
 * register the wallet's locks, and expose a [state] flow for the UI.
 */
class LightClientController(private val appContext: Context) {
    val provider = LightClientProvider(LightClientService.RPC_URL)
    private val _state = MutableStateFlow(LightClientState.STOPPED)
    val state: StateFlow<LightClientState> = _state

    fun stop() {
        LightClientService.stop(appContext)
        _state.value = LightClientState.STOPPED
    }

    /**
     * Start the service and register [locks] from [startBlockHex]. Caller polls
     * [refresh] to advance the state as the client syncs. Returns immediately
     * after kicking off the service; registration is retried in [refresh].
     */
    fun start() {
        LightClientService.start(appContext)
        _state.value = LightClientState.STARTING
    }

    /** Re-evaluate state and (once the RPC is up) register scripts if needed. */
    suspend fun refresh(locks: List<LockScript>, startBlockHex: String, networkTip: ULong?) {
        if (!LightClientService.isRunning()) { _state.value = LightClientState.STOPPED; return }
        val clientTip = runCatching { provider.getTipHeader()?.number }.getOrNull()
        if (clientTip != null && provider.getRegisteredScriptsSafe().isEmpty()) {
            runCatching { provider.registerScripts(locks, startBlockHex) }
        }
        _state.value = classifyState(true, clientTip, networkTip)
    }
}

private suspend fun LightClientProvider.getRegisteredScriptsSafe() =
    runCatching { getRegisteredScripts() }.getOrDefault(emptyList())
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "*LightClientStateTest*"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/wyltek/wallet/lightclient/LightClientController.kt \
        app/src/test/java/com/wyltek/wallet/lightclient/LightClientStateTest.kt
git commit -m "feat(light-client): controller + tip-lag state classifier"
```

---

## Phase 2 (separate follow-up plan — not in this plan)

1. Wire the Settings "Light Client" row (`SettingsScreen.kt:64`) → start/stop + a sync-progress screen driven by `LightClientController.state`.
2. Register `LightClientProvider` with `ChainManager` (so the wallet can switch its active source to the embedded client) — including a "don't auto-failover to a still-syncing client" guard.
3. Real tip-lag thresholding in `RpcHealthChecker` so a syncing light client isn't reported "healthy".
4. Compute the per-wallet `startBlockHex` (the wallet's funding/creation block) instead of a fixed test value.
5. Optional: rebuild the binary with tentacle UPnP disabled to drop the dormant vendored openssl and shrink the 19 MB lib.

## Self-Review

- **Spec coverage:** Phase-1 covers spec follow-up items 1 (provider + set_scripts: Tasks 3,4) and 2 (foreground service + jniLibs: Tasks 1,5) and the binary-exec packaging detail. Spec items 3 (Settings UI, tip-lag in RpcHealthChecker) and 4 (openssl shrink) are explicitly Phase 2. The pure tip-lag *logic* is seeded in Task 6 (`classifyState`).
- **Placeholders:** none — every code step shows full code; device steps show exact adb/curl commands with expected output.
- **Type consistency:** `LightClientService.RPC_URL`/`RPC_PORT`, `LightClientProvider.NAME`/`buildScriptStatuses`, `ScriptStatus(script, scriptType, blockNumber)`, `classifyState(running, clientTip, networkTip, lagThreshold)`, `LightClientState` — names are consistent across Tasks 3-6. `LightClientConfig.generate(storePath, networkPath, rpcListen, chain, bootnodes)` matches its call in Task 5.
- **Assumption to verify at execution:** that `Script` (the serializable DTO with `code_hash`/`hash_type`/`args`) is importable in `wallet-core` test scope and that `AgentNotifications.serverNotification`/`ensureChannels` are reusable from the new service package (both in `com.wyltek.wallet.agent.service`). If `AgentNotifications` is tightly coupled to the agent, Task 5 adds a minimal `LightClientNotifications` instead.
