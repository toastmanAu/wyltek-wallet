# Agent Gateway — Plan B2: Transport + UI — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Expose the on-device agent core (Plan B1) to an agent over the tailnet and to the user through the UI: an embedded Ktor HTTP server in a foreground service (the **Direct** transport), durable pending-approval handling with a biometric-gated approval flow, and a token-management Compose screen (mint / list / revoke / usage), all reachable through a single `AgentGateway` instance held on the `Application`.

**Architecture:** `WyltekWalletApp` holds one lazy `AgentGateway` (Plan B1). A `AgentGatewayService` foreground service runs a Ktor CIO server bound to the Tailscale interface, exposing `POST /v1/intent`, `GET /v1/intent/{id}`, `GET /v1/account`. The server delegates to `AgentActionDispatcher` (B1): `AllowAuto` executes synchronously and returns the tx hash; `NeedApproval` persists a durable pending intent (new Room table) + posts an approval notification and returns `202 {id}`; the agent polls the id. The user approves via `BiometricPrompt`, which re-runs `decide()` (re-checking caps/ttl/revocation/replay at approval time) and executes. Token management is an `AgentViewModel` + `AgentScreen` reached from Settings.

**Tech Stack:** Ktor 3.1.3 (server-core + CIO + content-negotiation + kotlinx-json), Jetpack Compose (BOM 2025.04.00), the existing single-`WalletViewModel`/manual-DI patterns, Room/SQLCipher (Plan B1), `BiometricAuth` (FragmentActivity), zxing QR (`QrCodeImage`).

## Global Constraints

- **Depends on Plan B1** (`AgentGateway`, `AgentActionDispatcher`, `AgentTokenService`, `AgentLedger`, `AgentDatabase`, the Rust `decide`/`Intent`/etc. bindings). Execute B1 first.
- **New Kotlin** lives in `app/.../agent/` (logic/service) and `app/.../ui/screens/` + `app/.../ui/navigation/` (UI), matching where the analogues already live.
- **Single gateway instance:** `WyltekWalletApp.agentGateway` (lazy). The service and ViewModels reach it via `(application as WyltekWalletApp).agentGateway`. Do not construct a second `AgentGateway`/`AgentDatabase` (two SQLCipher opens of the same file = corruption risk).
- **Tailnet binding:** the Ktor server binds to the device's Tailscale address (an IPv4 in `100.64.0.0/10`) when present; if absent, it does NOT silently bind to all interfaces — it reports "tailnet unavailable" and stays stopped (the user can override to bind `0.0.0.0` explicitly later). Transport confidentiality is Tailscale/WireGuard; the biscuit token is still required on every request (defense in depth). The embedded server is a listener, so Android's cleartext-traffic policy does NOT apply — no `network_security_config.xml` needed.
- **Foreground service type** = `dataSync` (the `FOREGROUND_SERVICE_DATA_SYNC` permission is already declared). `POST_NOTIFICATIONS` is already declared; request the runtime permission on Android 13+ before starting.
- **Approval re-validates:** approving a pending intent re-runs `decide()` and only executes if the result is `AllowAuto` or `NeedApproval` (both mean policy-valid within caps); a `Deny` at approval time (cap now exceeded, token revoked/expired, nonce already used) aborts. Never execute a pending intent without re-checking.
- **Money** shannons as `Long`; `Intent` carries the message payload for `messaging` in `action` (per B1/B-CEMP). Reuse, don't duplicate, the dispatcher's reserve→broadcast→confirm/rollback core.
- DTOs serialized with kotlinx-serialization (already applied). Commit after each task; conventional messages; no attribution footer.

## Plan B1 surface this plan builds on (verify against B1 as merged)

```kotlin
// app/.../agent/AgentGateway.kt
class AgentGateway(context: Context) { val tokenService: AgentTokenService; val dispatcher: AgentActionDispatcher }
// app/.../agent/AgentActionDispatcher.kt
sealed class DispatchResult { Sent(txHash,tokenId); Approval(tokenId,asset,amount); Denied(reason); Failed(message) }
suspend fun dispatch(token: String, intent: Intent, sourceIp: String, nowUnix: Long): DispatchResult
// app/.../agent/AgentTokenService.kt
suspend fun mint(spec: TokenSpec): MintedToken            // MintedToken(token, tokenId)
suspend fun list(): List<TokenRegistryEntity>             // (tokenId, token, account, revoked, createdAt)
suspend fun revoke(tokenId: String); suspend fun caps(token: String): List<CapInfo>
// Rust bindings (com.wyltek.wallet.core.native): Intent(op,asset,to,amount,nonce,action,daoRef),
//   TokenSpec(account,scopes,caps,ttlUnix,allowTo,allowIp), Scope, CapInfo(asset,cumulative,windowSeconds,windowLimit,autoLimit)
```

## File Structure

| File | Responsibility |
|------|----------------|
| `app/build.gradle.kts` (modify) | Ktor deps |
| `app/.../WyltekWalletApp.kt` (modify) | `agentGateway` lazy singleton |
| `app/.../agent/db/AgentEntities.kt` + `AgentDao.kt` + `AgentDatabase.kt` (modify) | `PendingIntentEntity` + DAO + DB version 2 migration |
| `app/.../agent/PendingStore.kt` (create) | persist/list/update pending intents |
| `app/.../agent/AgentActionDispatcher.kt` (modify) | persist NeedApproval; `executeApproved`; `listPending`; shared exec core |
| `app/.../agent/server/AgentDtos.kt` (create) | `@Serializable` request/response DTOs |
| `app/.../agent/server/AgentServer.kt` (create) | Ktor application module (routes) |
| `app/.../agent/server/Tailnet.kt` (create) | resolve the Tailscale bind address |
| `app/.../agent/service/AgentGatewayService.kt` (create) | foreground service + Ktor lifecycle + notifications |
| `app/.../agent/service/AgentNotifications.kt` (create) | channels + server + approval notifications |
| `app/src/main/AndroidManifest.xml` (modify) | `<service>` declaration |
| `app/.../ui/navigation/AppNavigation.kt` (modify) | `Screen.Agent`, `Screen.AgentApproval` routes |
| `app/.../ui/screens/SettingsScreen.kt` (modify) | "Agent Gateway" settings entry |
| `app/.../agent/ui/AgentViewModel.kt` (create) | state for tokens, server status, pending |
| `app/.../ui/screens/AgentScreen.kt` (create) | server toggle, token list/mint/revoke, usage, QR |
| `app/.../ui/screens/AgentApprovalScreen.kt` (create) | pending list + biometric approve/reject |
| `app/src/androidTest/.../agent/*` + `app/src/test/.../agent/AgentServerTest.kt` (create) | tests |

---

### Task 1: Pending-approval persistence + dispatcher execution path

**Files:**
- Modify: `app/.../agent/db/AgentEntities.kt`, `AgentDao.kt`, `AgentDatabase.kt`
- Create: `app/.../agent/PendingStore.kt`
- Modify: `app/.../agent/AgentActionDispatcher.kt`
- Modify: `app/.../WyltekWalletApp.kt`, `app/build.gradle.kts`
- Create: `app/src/androidTest/.../agent/PendingApprovalTest.kt`

**Interfaces:**
- Produces:
  - `@Entity PendingIntentEntity(id, token, op, asset, to, amount, nonce, action?, daoRef?, sourceIp, status, createdAt, resultTxHash?, resultError?)` with statuses `PENDING_APPROVAL`/`SENT`/`DENIED`/`FAILED`.
  - `PendingStore(db)` : `suspend fun put(...) : Long`, `suspend fun get(id): PendingIntentEntity?`, `suspend fun listPending(): List<PendingIntentEntity>`, `suspend fun setResult(id, status, txHash?, error?)`.
  - Dispatcher additions: `dispatch(...)` now persists on `NeedApproval` and returns a `DispatchResult.Approval(pendingId=..., ...)`; new `suspend fun executeApproved(pendingId: Long): DispatchResult`; `suspend fun listPending(): List<PendingIntentEntity>`.

- [ ] **Step 1: Ktor deps + Application singleton**

In `app/build.gradle.kts` `dependencies {}`:
```kotlin
    // Agent Gateway Direct transport
    implementation("io.ktor:ktor-server-core-jvm:3.1.3")
    implementation("io.ktor:ktor-server-cio-jvm:3.1.3")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:3.1.3")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:3.1.3")
    testImplementation("io.ktor:ktor-server-test-host-jvm:3.1.3")
```
In `WyltekWalletApp.kt`:
```kotlin
class WyltekWalletApp : Application() {
    val agentGateway: AgentGateway by lazy { AgentGateway(this) }
    override fun onCreate() { super.onCreate() }
}
```

- [ ] **Step 2: Add the pending entity + DAO + DB migration to v2**

In `AgentEntities.kt` add (after the B1 entities):
```kotlin
const val PENDING_APPROVAL = "pending_approval"
const val PENDING_SENT = "sent"
const val PENDING_DENIED = "denied"
const val PENDING_FAILED = "failed"

@Entity(tableName = "pending_intents")
data class PendingIntentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val token: String,
    val op: String,
    val asset: String,
    val to: String,
    val amount: Long,
    val nonce: String,
    val action: String? = null,
    val daoRef: String? = null,
    val sourceIp: String,
    val status: String = PENDING_APPROVAL,
    val createdAt: Long,
    val resultTxHash: String? = null,
    val resultError: String? = null
)
```
In `AgentDao.kt` add:
```kotlin
    @Insert suspend fun insertPending(p: PendingIntentEntity): Long
    @Query("SELECT * FROM pending_intents WHERE id=:id LIMIT 1") suspend fun pending(id: Long): PendingIntentEntity?
    @Query("SELECT * FROM pending_intents WHERE status=:status ORDER BY createdAt DESC") suspend fun pendingByStatus(status: String): List<PendingIntentEntity>
    @Query("UPDATE pending_intents SET status=:status, resultTxHash=:txHash, resultError=:error WHERE id=:id") suspend fun setPendingResult(id: Long, status: String, txHash: String?, error: String?)
```
In `AgentDatabase.kt` bump `version = 2`, add `PendingIntentEntity::class` to `entities`, and provide the migration (new table is additive):
```kotlin
val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS pending_intents (
            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, token TEXT NOT NULL, op TEXT NOT NULL,
            asset TEXT NOT NULL, `to` TEXT NOT NULL, amount INTEGER NOT NULL, nonce TEXT NOT NULL,
            action TEXT, daoRef TEXT, sourceIp TEXT NOT NULL, status TEXT NOT NULL, createdAt INTEGER NOT NULL,
            resultTxHash TEXT, resultError TEXT)""")
    }
}
```
and in `AgentDatabaseFactory.open(...)` add `.addMigrations(MIGRATION_1_2)`.

- [ ] **Step 3: Create `PendingStore`**

`app/.../agent/PendingStore.kt`:
```kotlin
package com.wyltek.wallet.agent

import com.wyltek.wallet.agent.db.AgentDatabase
import com.wyltek.wallet.agent.db.PendingIntentEntity
import com.wyltek.wallet.agent.db.PENDING_APPROVAL

class PendingStore(db: AgentDatabase) {
    private val dao = db.agentDao()
    suspend fun put(p: PendingIntentEntity): Long = dao.insertPending(p)
    suspend fun get(id: Long): PendingIntentEntity? = dao.pending(id)
    suspend fun listPending(): List<PendingIntentEntity> = dao.pendingByStatus(PENDING_APPROVAL)
    suspend fun setResult(id: Long, status: String, txHash: String?, error: String?) =
        dao.setPendingResult(id, status, txHash, error)
}
```

- [ ] **Step 4: Extend the dispatcher (persist approval + executeApproved)**

In `AgentActionDispatcher.kt`: take a `PendingStore` (constructor param; `AgentGateway` passes it). Change the `NeedApproval` branch of `dispatch(...)` to persist a `PendingIntentEntity` (status `PENDING_APPROVAL`) and return `DispatchResult.Approval(pendingId = <rowId>, asset, amount)` — add `pendingId: Long` to `Approval`. Add:
```kotlin
    suspend fun listPending() = pendingStore.listPending()

    /** Re-validates and executes a human-approved pending intent. */
    suspend fun executeApproved(pendingId: Long): DispatchResult {
        val p = pendingStore.get(pendingId) ?: return DispatchResult.Denied("pending intent not found")
        if (p.status != PENDING_APPROVAL) return DispatchResult.Denied("already ${p.status}")
        val intent = Intent(op = p.op, asset = p.asset, to = p.to, amount = p.amount, nonce = p.nonce, action = p.action, daoRef = p.daoRef)
        val reg = tokenService.list().firstOrNull { it.token == p.token } ?: return finishPending(pendingId, DispatchResult.Denied("unknown token"))
        if (reg.revoked) return finishPending(pendingId, DispatchResult.Denied("token revoked"))
        val now = nowSeconds()
        val pub = keyStore.rootPublicHex()
        val caps = try { tokenService.caps(p.token) } catch (e: Throwable) { return finishPending(pendingId, DispatchResult.Denied("bad token: ${e.message}")) }
        val cap = caps.firstOrNull { it.asset == p.asset } ?: return finishPending(pendingId, DispatchResult.Denied("no cap for asset ${p.asset}"))
        val base = ledger.view(reg.tokenId, p.asset, cap.windowSeconds, now)
        val view = LedgerView(base.cumulativeSpent, base.windowSpent, ledger.nonceSeen(reg.tokenId, p.nonce))
        val ctx = RequestCtx(account = reg.account, sourceIp = p.sourceIp, nowUnix = now)
        // Approved intents may execute on AllowAuto OR NeedApproval (both = policy-valid within caps). Deny aborts.
        return when (val d = decide(p.token, pub, intent, view, ctx)) {
            is Decision.Deny -> finishPending(pendingId, DispatchResult.Denied(d.reason))
            else -> {
                val r = executeAuto(reg.tokenId, intent, now)  // shared reserve->broadcast->confirm/rollback core (B1)
                finishPending(pendingId, r)
            }
        }
    }

    private suspend fun finishPending(id: Long, r: DispatchResult): DispatchResult {
        when (r) {
            is DispatchResult.Sent -> pendingStore.setResult(id, PENDING_SENT, r.txHash, null)
            is DispatchResult.Denied -> pendingStore.setResult(id, PENDING_DENIED, null, r.reason)
            is DispatchResult.Failed -> pendingStore.setResult(id, PENDING_FAILED, null, r.message)
            else -> {}
        }
        return r
    }
```
Add a `nowSeconds()` helper (`System.currentTimeMillis()/1000`) and inject the same clock used by `dispatch`. (`executeAuto` already exists in B1 as the AllowAuto core — make it internal/reusable.)

Wire `PendingStore` into `AgentGateway` (construct from the same `db`) and pass to the dispatcher.

- [ ] **Step 5: Test (instrumented — needs device/emulator)**

`PendingApprovalTest`: mint a token (auto_limit 20, cumulative 100); `dispatch` a 50-CKB intent → assert `Approval` with a `pendingId`; `listPending()` has 1; `executeApproved(pendingId)` with a fake send returning success → `Sent`, pending now `SENT`; a second `executeApproved(samePendingId)` → `Denied("already sent")`. Also: dispatch 50 over-cap (cumulative 40) then approve → `Denied` (re-validation catches it).

Run: `./gradlew :app:compileDebugKotlin`; if device: the connected test. Operator runs connected tests if no emulator.

- [ ] **Step 6: Commit**

```bash
cd ~/wyltek-wallet
git add app/build.gradle.kts app/src/main/java/com/wyltek/wallet/WyltekWalletApp.kt app/src/main/java/com/wyltek/wallet/agent app/src/androidTest
git commit -m "feat(agent): durable pending-approval store + dispatcher executeApproved"
```

---

### Task 2: Ktor server module + DTOs + tailnet bind

**Files:**
- Create: `app/.../agent/server/AgentDtos.kt`, `AgentServer.kt`, `Tailnet.kt`
- Create: `app/src/test/.../agent/AgentServerTest.kt` (host JVM test via Ktor `testApplication`)

**Interfaces:**
- Produces: `fun Application.agentModule(gateway: AgentGateway, peerIp: () -> String)`; `data class` DTOs; `object Tailnet { fun bindAddress(): String? }`.

- [ ] **Step 1: DTOs**

`app/.../agent/server/AgentDtos.kt`:
```kotlin
package com.wyltek.wallet.agent.server
import kotlinx.serialization.Serializable

@Serializable data class IntentRequest(
    val token: String, val op: String, val asset: String, val to: String = "",
    val amount: Long = 0, val nonce: String, val action: String? = null, val daoRef: String? = null
)
@Serializable data class IntentResponse(
    val status: String,                 // "sent" | "needs_approval" | "denied" | "failed"
    val txHash: String? = null, val pendingId: Long? = null, val reason: String? = null
)
@Serializable data class IntentStatusResponse(
    val status: String, val txHash: String? = null, val error: String? = null
)
@Serializable data class AccountInfo(val tokenId: String, val account: String, val revoked: Boolean)
```

- [ ] **Step 2: Tailnet bind resolver**

`app/.../agent/server/Tailnet.kt`:
```kotlin
package com.wyltek.wallet.agent.server
import java.net.Inet4Address
import java.net.NetworkInterface

object Tailnet {
    /** Returns the device's Tailscale IPv4 (100.64.0.0/10) if present, else null. */
    fun bindAddress(): String? {
        for (nif in NetworkInterface.getNetworkInterfaces()) {
            if (!nif.isUp || nif.isLoopback) continue
            for (addr in nif.inetAddresses) {
                if (addr is Inet4Address) {
                    val b = addr.address
                    val first = b[0].toInt() and 0xFF
                    val second = b[1].toInt() and 0xFF
                    // 100.64.0.0/10 == 100.64.x.x .. 100.127.x.x
                    if (first == 100 && second in 64..127) return addr.hostAddress
                }
            }
        }
        return null
    }
}
```

- [ ] **Step 3: Server module + tests**

`app/.../agent/server/AgentServer.kt`:
```kotlin
package com.wyltek.wallet.agent.server

import com.wyltek.wallet.agent.AgentGateway
import com.wyltek.wallet.agent.DispatchResult
import com.wyltek.wallet.agent.db.*
import com.wyltek.wallet.core.native.Intent
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.*

/** peerIp supplies the source IP for the IP-lock caveat (the engine's request origin). */
fun Application.agentModule(gateway: AgentGateway, peerIp: () -> String) {
    install(ContentNegotiation) { json() }
    routing {
        post("/v1/intent") {
            val req = call.receive<IntentRequest>()
            val intent = Intent(op = req.op, asset = req.asset, to = req.to, amount = req.amount,
                nonce = req.nonce, action = req.action, daoRef = req.daoRef)
            val now = System.currentTimeMillis() / 1000
            when (val d = gateway.dispatcher.dispatch(req.token, intent, peerIp(), now)) {
                is DispatchResult.Sent -> call.respond(HttpStatusCode.OK, IntentResponse("sent", txHash = d.txHash))
                is DispatchResult.Approval -> call.respond(HttpStatusCode.Accepted, IntentResponse("needs_approval", pendingId = d.pendingId))
                is DispatchResult.Denied -> call.respond(HttpStatusCode.Forbidden, IntentResponse("denied", reason = d.reason))
                is DispatchResult.Failed -> call.respond(HttpStatusCode.BadGateway, IntentResponse("failed", reason = d.message))
            }
        }
        get("/v1/intent/{id}") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, IntentStatusResponse("bad_id"))
            val p = gateway.pendingStore.get(id)
                ?: return@get call.respond(HttpStatusCode.NotFound, IntentStatusResponse("not_found"))
            call.respond(IntentStatusResponse(status = p.status, txHash = p.resultTxHash, error = p.resultError))
        }
        get("/v1/account") {
            val list = gateway.tokenService.list().map { AccountInfo(it.tokenId, it.account, it.revoked) }
            call.respond(list)
        }
    }
}
```
> Expose `gateway.pendingStore` (make it a public val on `AgentGateway`). The `peerIp` lambda is supplied by the service from the Ktor engine's connection point; in tests it's a fixed string.

`app/src/test/.../agent/AgentServerTest.kt` (host JVM, no device — uses `testApplication`): build an `AgentGateway` test double or a thin fake exposing a `dispatcher`/`tokenService`/`pendingStore` with canned results, install `agentModule`, and assert: a within-cap intent → 200 `sent`+txHash; over-limit → 202 `needs_approval`+pendingId; bad token → 403 `denied`; `GET /v1/intent/{id}` returns the stored status; `GET /v1/account` returns the registry list. (Because `AgentGateway` wraps Room/SQLCipher, define a small `AgentDispatchPort` interface the server depends on and have `AgentGateway` implement it, so the host test injects a pure fake — note this refactor in the task.)

- [ ] **Step 4: Verify + commit**

Run: `./gradlew :app:testDebugUnitTest --tests "*AgentServerTest*"` (host JVM — runnable without a device).
Expected: server route tests pass.
```bash
git add app/src/main/java/com/wyltek/wallet/agent/server app/src/test
git commit -m "feat(agent): Ktor server module + DTOs + tailnet bind resolver"
```

---

### Task 3: Foreground service + notifications

**Files:**
- Create: `app/.../agent/service/AgentNotifications.kt`, `AgentGatewayService.kt`
- Modify: `app/src/main/AndroidManifest.xml`

**Interfaces:**
- Produces: `AgentGatewayService` with actions `ACTION_START`/`ACTION_STOP` and a companion `fun start(context)/stop(context)/isRunning(): Boolean`; `AgentNotifications` (channels + builders).

- [ ] **Step 1: Notifications**

`app/.../agent/service/AgentNotifications.kt`: create two channels (`agent_server` low-importance for the persistent server notification; `agent_approval` high-importance for approval prompts). Provide `serverNotification(context, bindAddr): Notification` and `approvalNotification(context, pendingId, summary): Notification` whose content intent deep-links to `MainActivity` with an extra `agent_approval_pending_id` (MainActivity routes to the approval screen when present). Create channels in `AgentNotifications.ensureChannels(context)`.

- [ ] **Step 2: The service**

`app/.../agent/service/AgentGatewayService.kt`: a `Service` that on `ACTION_START` resolves `Tailnet.bindAddress()` (if null → post a "tailnet unavailable" notification and `stopSelf()`), builds a Ktor CIO server:
```kotlin
server = embeddedServer(CIO, host = bindAddr, port = 8443) {
    val gateway = (application as WyltekWalletApp).agentGateway   // NOTE: ktor 'application' shadows Android; capture the Android app first
    agentModule(gateway) { /* peer ip from call.request.local/origin */ }
}.start(wait = false)
```
calls `startForeground(NOTIF_ID, AgentNotifications.serverNotification(this, "$bindAddr:8443"), FOREGROUND_SERVICE_TYPE_DATA_SYNC)`. On `ACTION_STOP`: `server.stop()`, `stopForeground(STOP_FOREGROUND_REMOVE)`, `stopSelf()`. Maintain a `@Volatile var running` companion flag for `isRunning()`. Capture the Android `Application` into a local before the Ktor lambda (Ktor's `application` receiver shadows it). The `peerIp` lambda reads the Ktor `ApplicationCall` origin remote host; thread it via a `call`-scoped value (use `call.request.origin.remoteHost` inside the route instead of the lambda if cleaner — adjust `agentModule` to read it per-call).

> Per-call peer IP: simplest correct form is to drop the `peerIp` lambda and read `call.request.origin.remoteHost` inside the `post("/v1/intent")` handler, passing it to `dispatch`. Adjust Task 2's `agentModule` accordingly when implementing.

- [ ] **Step 3: Manifest**

In `AndroidManifest.xml` inside `<application>`:
```xml
<service
    android:name=".agent.service.AgentGatewayService"
    android:foregroundServiceType="dataSync"
    android:exported="false" />
```

- [ ] **Step 4: Verify + commit**

Run: `./gradlew :app:compileDebugKotlin` → green. (Runtime start/stop is exercised manually / in Task 5's UI.)
```bash
git add app/src/main/java/com/wyltek/wallet/agent/service app/src/main/AndroidManifest.xml
git commit -m "feat(agent): foreground service hosting the Ktor server + notifications"
```

---

### Task 4: AgentViewModel + token-management screen + Settings entry

**Files:**
- Create: `app/.../agent/ui/AgentViewModel.kt`, `app/.../ui/screens/AgentScreen.kt`
- Modify: `app/.../ui/navigation/AppNavigation.kt`, `app/.../ui/screens/SettingsScreen.kt`

**Interfaces:**
- Produces: `AgentViewModel(application)` exposing `StateFlow<AgentUiState>` (tokens, serverRunning, bindAddress, pending) + actions `refresh()`, `startServer()`, `stopServer()`, `mint(spec)`, `revoke(tokenId)`. `AgentScreen(onBack, onApprovals)`.

- [ ] **Step 1: AgentViewModel**

`app/.../agent/ui/AgentViewModel.kt`: an `AndroidViewModel` reaching `(getApplication() as WyltekWalletApp).agentGateway`. `AgentUiState(tokens: List<TokenRow>, serverRunning: Boolean, bindAddress: String?, pendingCount: Int, lastMintedToken: String?, error: String?)`. Actions mirror `WalletViewModel`'s coroutine + `_uiState.copy()` style:
```kotlin
fun refresh() = viewModelScope.launch {
    val g = gateway
    _uiState.value = _uiState.value.copy(
        tokens = g.tokenService.list().map { TokenRow(it.tokenId, it.account, it.revoked) },
        serverRunning = AgentGatewayService.isRunning(),
        bindAddress = Tailnet.bindAddress(),
        pendingCount = g.dispatcher.listPending().size
    )
}
fun startServer() { AgentGatewayService.start(getApplication()); refresh() }
fun stopServer() { AgentGatewayService.stop(getApplication()); refresh() }
fun mint(spec: TokenSpec) = viewModelScope.launch {
    try { val m = gateway.tokenService.mint(spec); _uiState.value = _uiState.value.copy(lastMintedToken = m.token); refresh() }
    catch (e: Throwable) { _uiState.value = _uiState.value.copy(error = e.message) }
}
fun revoke(tokenId: String) = viewModelScope.launch { gateway.tokenService.revoke(tokenId); refresh() }
```

- [ ] **Step 2: AgentScreen**

`app/.../ui/screens/AgentScreen.kt`: a `Column`/`TopAppBar` (mirror `SettingsScreen` styling and components) with:
- A server card: bind address + a Start/Stop `Button` (disabled with "Tailnet unavailable" when `bindAddress == null`); request `POST_NOTIFICATIONS` (Android 13+) before `startServer()`.
- A "Pending approvals (N)" row → `onApprovals()`.
- A token list (`tokens`): each row shows account + tokenId + revoked badge + a "Revoke" `TextButton`.
- A "Mint token" form (reuse the `OutlinedTextField` + `Button` pattern from `SendScreen`): scope checkboxes (`send_ckb/send_udt/dao/messaging`), account dropdown (from `WalletViewModel`/`AccountManager` accounts), per-asset cap fields (cumulative CKB, auto-limit CKB, optional window seconds/limit), optional ttl + allow_to + allow_ip. On submit, assemble a `TokenSpec` and call `mint`.
- When `lastMintedToken != null`, show it in a selectable `Text` + `QrCodeImage(content = lastMintedToken)` (reuse `app/.../ui/components/QrCode.kt`) with a "copy" affordance, and a warning that it is shown once.

- [ ] **Step 3: Nav + Settings entry**

In `AppNavigation.kt`: add `data object Agent : Screen("agent", "Agent")` and `data object AgentApproval : Screen("agent-approval", "Approvals")`; add `composable(Screen.Agent.route) { AgentScreen(onBack = { navController.popBackStack() }, onApprovals = { navController.navigate(Screen.AgentApproval.route) }) }`. In `SettingsScreen.kt` add a `SettingsSection(title = "Agent")` with a `SettingsItem(icon = Icons.Default.SmartToy, title = "Agent Gateway", subtitle = "Tokens & server", onClick = onAgent)` and add `onAgent: () -> Unit = {}` to the signature; wire it in `AppNavigation`'s `SettingsScreen(...)` call.

- [ ] **Step 4: Verify + commit**

Run: `./gradlew :app:compileDebugKotlin` → green.
```bash
git add app/src/main/java/com/wyltek/wallet/agent/ui app/src/main/java/com/wyltek/wallet/ui/screens/AgentScreen.kt app/src/main/java/com/wyltek/wallet/ui/navigation/AppNavigation.kt app/src/main/java/com/wyltek/wallet/ui/screens/SettingsScreen.kt
git commit -m "feat(agent): token-management screen + AgentViewModel + Settings entry"
```

---

### Task 5: Approval screen + biometric approve flow + notification deep-link

**Files:**
- Create: `app/.../ui/screens/AgentApprovalScreen.kt`
- Modify: `app/.../agent/ui/AgentViewModel.kt` (pending list + approve/reject), `AppNavigation.kt` (route + deep-link arg), `MainActivity.kt` (handle the approval deep-link extra)

**Interfaces:**
- Produces: `AgentApprovalScreen(onBack)`; `AgentViewModel` gains `pending: List<PendingRow>`, `approve(pendingId)` (called AFTER biometric success), `reject(pendingId)`.

- [ ] **Step 1: ViewModel approval actions**

Add to `AgentViewModel`:
```kotlin
fun loadPending() = viewModelScope.launch {
    _uiState.value = _uiState.value.copy(pending = gateway.dispatcher.listPending().map {
        PendingRow(it.id, it.op, it.asset, it.amount, it.to, it.action)
    })
}
fun approve(pendingId: Long) = viewModelScope.launch {
    val r = gateway.dispatcher.executeApproved(pendingId)
    _uiState.value = _uiState.value.copy(error = (r as? DispatchResult.Denied)?.reason ?: (r as? DispatchResult.Failed)?.message)
    loadPending()
}
fun reject(pendingId: Long) = viewModelScope.launch {
    gateway.pendingStore.setResult(pendingId, PENDING_DENIED, null, "rejected by user"); loadPending()
}
```

- [ ] **Step 2: Approval screen with biometric gate**

`app/.../ui/screens/AgentApprovalScreen.kt`: list `uiState.pending`; each row shows op/asset/amount/recipient (and decoded message preview for `messaging`), with **Approve** and **Reject** buttons. Approve uses the established biometric pattern:
```kotlin
val biometric = rememberBiometricAuth()
// in the Approve button onClick:
biometric.authenticate(
    title = "Approve agent transaction",
    subtitle = "$op $amount $asset to $to",
    description = "An agent requested a spend above its auto-limit."
) { result ->
    when (result) {
        is BiometricResult.Success -> viewModel.approve(row.id)
        is BiometricResult.Cancelled -> {}
        is BiometricResult.Error -> { /* show row.error */ }
        is BiometricResult.Unavailable -> { /* prompt to set up device credential */ }
    }
}
```
Call `viewModel.loadPending()` in a `LaunchedEffect(Unit)`.

- [ ] **Step 3: Notification deep-link**

In `AppNavigation.kt` add `composable(Screen.AgentApproval.route) { AgentApprovalScreen(onBack = { navController.popBackStack() }) }`. In `MainActivity.onCreate`/`onNewIntent`, read `intent.getLongExtra("agent_approval_pending_id", -1)`; if set, navigate to `Screen.AgentApproval.route` after the NavHost is ready (pass a start-destination hint or a `LaunchedEffect` keyed on the extra). The approval notification's content `PendingIntent` (Task 3) carries this extra.

- [ ] **Step 4: Verify + commit**

Run: `./gradlew :app:compileDebugKotlin` → green; if device, a smoke instrumented test that `executeApproved` is invoked after a simulated approval (or operator manual run). 
```bash
git add app/src/main/java/com/wyltek/wallet/ui/screens/AgentApprovalScreen.kt app/src/main/java/com/wyltek/wallet/agent/ui/AgentViewModel.kt app/src/main/java/com/wyltek/wallet/ui/navigation/AppNavigation.kt app/src/main/java/com/wyltek/wallet/MainActivity.kt
git commit -m "feat(agent): approval screen + biometric approve flow + notification deep-link"
```

---

## Self-Review

**1. Spec coverage** (against `2026-06-19-agent-gateway-design.md` Part 4 + Part 5 UI):

- Part 4 Direct adapter: Ktor server (Task 2) in a foreground service bound to the tailnet (Task 3), endpoints `POST /v1/intent` / `GET /v1/intent/{id}` / `GET /v1/account`, IP captured per-call for the `allow_ip` caveat.
- Part 5 tiered approval: `NeedApproval` persists durably (Task 1) + FCM/local approval notification (Task 3) → biometric tap (Task 5) → re-validated `executeApproved` (Task 1). (Push here is a *local* notification; the FCM/relay wake is Plan C.)
- Part 5 token management UI: mint/list/revoke/usage + QR (Task 4).
- B1's `MessagingSender`/CEMP path is reachable through `dispatch` once B-CEMP lands (the `messaging` op flows through unchanged).

**2. Placeholder scan:** No "TBD"/"add error handling". Named, compile-gated refactors: exposing `pendingStore`/`executeAuto` from B1; the `AgentDispatchPort` interface for the host server test; reading `call.request.origin.remoteHost` for peer IP; capturing the Android `Application` before Ktor's shadowing `application` receiver. Each names the exact thing to do.

**3. Type consistency:** `DispatchResult.Approval` gains `pendingId: Long` (Task 1) and is read by the server (Task 2) and ViewModel (Task 5). `PendingIntentEntity`/`PendingStore` defined in Task 1, consumed in Tasks 2/5. DTOs defined once (Task 2). `AgentGateway` exposes `dispatcher`/`tokenService`/`pendingStore` consistently. Rust `Intent`/`Decision`/`LedgerView`/`RequestCtx`/`TokenSpec`/`CapInfo` are the B1 bindings.

**Open items (flagged):**
- **`DispatchResult.Approval` signature change** ripples to B1's tests — update B1's dispatcher test expectations when B2 lands (Task 1 touches B1 code).
- **Tailnet detection** keys on `100.64.0.0/10`; if the user runs Tailscale with a custom range this needs adjustment (documented; the server refuses to bind rather than exposing on all interfaces).
- **No TLS on the Ktor server** — transport confidentiality relies on Tailscale/WireGuard; the biscuit token is the auth. Optional TLS is future hardening.
- **Host-testability:** server routes are host-JVM testable via `testApplication` (Task 2) — the one part not needing a device. Room-backed dispatcher/UI tests need an emulator (operator).
- **Carry-forward (whole program):** biscuit attenuation UI, decide-time revocation enforcement (already wired in the dispatcher via the registry `revoked` flag — confirm), multi-asset cap regression test, CEMP inbox/receive path, and Plan C (relay + FCM wake).
