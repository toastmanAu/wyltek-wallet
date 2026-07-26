# Companion-direct Agent Server (Tier-1 phone endpoint) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the `wyltek-wallet` phone reachable by the shipped POS Tier-1 client over a plain LAN — serving the relay's `/relay/*` async contract over self-signed HTTPS, discoverable by mDNS, with no Tailscale and no relay.

**Architecture:** Four additive pieces over the *existing* Ktor agent server (backend untouched): (1) `/relay/*` routes that make the phone "a local relay" over the existing `dispatch()` + `PendingStore`; (2) `PendingStore`/DAO lookup-by-`intent_id` + terminal-row storage; (3) an `AgentGateway` facade impl (compute `intent_id`, idempotency, async dispatch mapping, link-on-approval); (4) device transport/discovery — LAN bind, self-signed TLS, `NsdManager` advertise, Wi-Fi-change lifecycle — plus a 3-field provisioning QR.

**Tech Stack:** Kotlin / Ktor CIO 3.x / Room / Android foreground service / NsdManager; host-JVM tests via `ktor-server-test-host-jvm` + JUnit4 (`./gradlew :app:testDebugUnitTest`).

**Design spec:** `docs/superpowers/specs/2026-07-25-companion-direct-agent-server-design.md` (approved; this is sub-project #2).

## Global Constraints

- **Reuse the backend verbatim** — biscuit verify, caps/ledger, signing/broadcast (`AgentActionDispatcher`/`WalletRepository`), StrongBox keys, `PendingStore`. No new signing/authz. (Spec §2, §11)
- **No confirmation axis** — do NOT touch sale-confirmation or balance (that is #1b). (Spec §2)
- **`/v1/*` routes, `RelayClient` WS path, and the relay repo stay untouched.** The POS #1 firmware is byte-identical. (Spec §11)
- **Wire contract the POS already speaks:** `POST /relay/intent` → `{"status":"queued","intent_id":"<hex>"}`; `GET /relay/intent/{intent_id}` → `{"status","txHash","error"}` (field `txHash` is camelCase — the POS parses `"txHash"`). Non-2xx GET = transient (POS keeps polling). (Spec §5)
- **`intent_id = hex(sha256(token ‖ 0x00 ‖ nonce))`**, idempotent per `(token,nonce)`, opaque to the POS. (Spec §5)
- **Status mapping:** `Sent`→row `status="sent"`+`txHash`; `Denied`→`"denied"`+reason; `Failed`→`"failed"`+message; `Approval`→ link row, `status` stays `"needs_approval"`. (Spec §5)
- **TLS trust is the biscuit, not the cert** — self-signed, POS uses `setInsecure()`. (Spec §7)
- **mDNS advertises only while the foreground service runs**; bind the active **Wi-Fi** address (not cellular); keep a Tailnet co-connector if present. (Spec §8)
- **`service_name = "blackbox-" + first 8 hex chars of device_id`**, minted into the QR and advertised as the mDNS instance; TXT record carries `device_id`. (Spec §6)
- No attribution / `Co-Authored-By` trailer in commits.

**Build/test env:** host-JVM unit tests: `./gradlew :app:testDebugUnitTest --tests "<class>"`. Compile gate: `./gradlew :app:compileDebugKotlin`. Both need the Android SDK (`sdk.dir=/home/phill/Android/Sdk` in `local.properties`). If gradle cannot run in the execution sandbox, report BLOCKED with the exact error and commit the code (the reviewer verifies statically); the user runs the build.

---

## File Structure

- `agent/server/AgentDtos.kt` — add `RelayAcceptResponse`.
- `agent/server/AgentDispatchPort.kt` — add `submitRelayIntent` / `relayIntentStatus`.
- `agent/server/AgentServer.kt` — add `/relay/*` routes.
- `app/src/test/.../agent/AgentServerTest.kt` — extend fake + `/relay/*` tests.
- `agent/db/AgentDao.kt` + `PendingStore.kt` — `pendingByRelayIntentId` + terminal upsert.
- `agent/AgentGateway.kt` — implement the facade methods; `intent_id` helper.
- `app/src/test/.../agent/RelayIntentIdTest.kt` (new) — pure `intent_id` test.
- `agent/provisioning/AgentProvisioning.kt` + a `RelayServiceName` helper — 3-field QR + `service_name`.
- `app/src/test/.../agent/AgentProvisioningTest.kt` (new/extended) — 3-field + derivation.
- `agent/server/LanAddress.kt` (new), `agent/server/AgentTls.kt` (new) — LAN resolver, self-signed keystore.
- `agent/server/AgentMdns.kt` (new) — `NsdManager` advertiser.
- `agent/service/AgentGatewayService.kt` — LAN bind + `sslConnector` + mDNS + Wi-Fi callback.

---

## Task 1: `/relay/*` routes + DTO + port methods (host-JVM TDD)

**Files:**
- Modify: `app/src/main/java/com/wyltek/wallet/agent/server/AgentDtos.kt`
- Modify: `app/src/main/java/com/wyltek/wallet/agent/server/AgentDispatchPort.kt`
- Modify: `app/src/main/java/com/wyltek/wallet/agent/server/AgentServer.kt`
- Test: `app/src/test/java/com/wyltek/wallet/agent/AgentServerTest.kt`

**Interfaces:**
- Consumes: existing `IntentRequest`, `IntentStatusResponse`, `Intent`, `AgentDispatchPort`.
- Produces: `RelayAcceptResponse(status, intentId@SerialName("intent_id"))`; `AgentDispatchPort.submitRelayIntent(token,intent,sourceIp,nowUnix): String`; `AgentDispatchPort.relayIntentStatus(intentId): IntentStatusResponse?`; routes `POST /relay/intent`, `GET /relay/intent/{intent_id}`.

- [ ] **Step 1: Add the response DTO + port methods**

In `AgentDtos.kt`, add (with `import kotlinx.serialization.SerialName`):
```kotlin
@Serializable
data class RelayAcceptResponse(
    val status: String,
    @SerialName("intent_id") val intentId: String
)
```

In `AgentDispatchPort.kt`, add to the interface:
```kotlin
    /** Relay-compatible async facade: accept an intent, return an opaque intent_id the
     *  POS polls. Idempotent per (token, nonce). */
    suspend fun submitRelayIntent(token: String, intent: Intent, sourceIp: String, nowUnix: Long): String

    /** Poll a relay-facade intent by intent_id. Null = unknown/not-yet-tracked → 404. */
    suspend fun relayIntentStatus(intentId: String): IntentStatusResponse?
```

- [ ] **Step 2: Extend the test fake + write failing `/relay/*` tests**

In `AgentServerTest.kt`, the single `fakePort(...)` factory must implement the two new methods (the interface won't compile otherwise). Replace the `object : AgentDispatchPort` with one that records submissions, and add a `relayStatus` param:

```kotlin
    private fun fakePort(
        dispatchResult: DispatchResult,
        relayStatus: IntentStatusResponse? = IntentStatusResponse("sent", "0xrelay", null),
    ): AgentDispatchPort = object : AgentDispatchPort {
        val submitted = mutableMapOf<String, IntentStatusResponse?>()

        override suspend fun dispatch(token: String, intent: Intent, sourceIp: String, nowUnix: Long) = dispatchResult
        override suspend fun pendingStatus(id: Long): IntentStatusResponse? = if (id == 42L) storedStatus else null
        override suspend fun accounts(): List<AccountInfo> = accounts

        override suspend fun submitRelayIntent(token: String, intent: Intent, sourceIp: String, nowUnix: Long): String {
            val id = "id-${intent.nonce}"                 // deterministic + idempotent per nonce
            submitted.putIfAbsent(id, relayStatus)
            return id
        }
        override suspend fun relayIntentStatus(intentId: String): IntentStatusResponse? =
            if (submitted.containsKey(intentId)) submitted[intentId] else null
    }
```

Add these tests:
```kotlin
    @Test
    fun `POST relay intent returns 200 queued with intent_id`() = testApplication {
        application { agentModule(fakePort(DispatchResult.Denied("n/a"))) }
        val client = jsonClient(this)
        val resp = client.post("/relay/intent") {
            contentType(ContentType.Application.Json); setBody(validReq)
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        val body = resp.body<RelayAcceptResponse>()
        assertEquals("queued", body.status)
        assertEquals("id-n1", body.intentId)
    }

    @Test
    fun `POST relay intent is idempotent per nonce`() = testApplication {
        application { agentModule(fakePort(DispatchResult.Denied("n/a"))) }
        val client = jsonClient(this)
        val a = client.post("/relay/intent") { contentType(ContentType.Application.Json); setBody(validReq) }
            .body<RelayAcceptResponse>()
        val b = client.post("/relay/intent") { contentType(ContentType.Application.Json); setBody(validReq) }
            .body<RelayAcceptResponse>()
        assertEquals(a.intentId, b.intentId)
    }

    @Test
    fun `GET relay intent id returns mapped status`() = testApplication {
        application { agentModule(fakePort(DispatchResult.Denied("n/a"),
            relayStatus = IntentStatusResponse("sent", "0xfeed", null))) }
        val client = jsonClient(this)
        client.post("/relay/intent") { contentType(ContentType.Application.Json); setBody(validReq) }
        val resp = client.get("/relay/intent/id-n1")
        assertEquals(HttpStatusCode.OK, resp.status)
        val body = resp.body<IntentStatusResponse>()
        assertEquals("sent", body.status); assertEquals("0xfeed", body.txHash)
    }

    @Test
    fun `GET relay intent unknown id returns 404`() = testApplication {
        application { agentModule(fakePort(DispatchResult.Denied("n/a"))) }
        val client = jsonClient(this)
        assertEquals(HttpStatusCode.NotFound, client.get("/relay/intent/nope").status)
    }
```
Add the import `import com.wyltek.wallet.agent.server.RelayAcceptResponse`.

Run:
```bash
cd /home/phill/wyltek-wallet && ./gradlew :app:testDebugUnitTest --tests "com.wyltek.wallet.agent.AgentServerTest"
```
Expected: FAIL to compile — `agentModule` has no `/relay/*` routes yet and the routes reference `port.submitRelayIntent` which the routes don't call yet.

- [ ] **Step 3: Add the routes**

In `AgentServer.kt`, inside `routing { … }` (after the `/v1/account` block), add — and `import com.wyltek.wallet.agent.server.RelayAcceptResponse` is same-package so no import needed:
```kotlin
        post("/relay/intent") {
            val req = call.receive<IntentRequest>()
            val sourceIp = call.request.origin.remoteHost
            val intent = Intent(
                op = req.op, asset = req.asset, to = req.to, amount = req.amount,
                nonce = req.nonce, action = req.action, daoRef = req.daoRef
            )
            val now = System.currentTimeMillis() / 1000
            val intentId = port.submitRelayIntent(req.token, intent, sourceIp, now)
            call.respond(HttpStatusCode.OK, RelayAcceptResponse("queued", intentId))
        }

        get("/relay/intent/{intent_id}") {
            val id = call.parameters["intent_id"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, IntentStatusResponse("bad_id"))
            val status = port.relayIntentStatus(id)
                ?: return@get call.respond(HttpStatusCode.NotFound, IntentStatusResponse("not_found"))
            call.respond(status)
        }
```

- [ ] **Step 4: Run the tests — expect PASS**

Run:
```bash
cd /home/phill/wyltek-wallet && ./gradlew :app:testDebugUnitTest --tests "com.wyltek.wallet.agent.AgentServerTest"
```
Expected: all `AgentServerTest` tests pass (existing `/v1` + new `/relay`).

- [ ] **Step 5: Commit**

```bash
cd /home/phill/wyltek-wallet
git add app/src/main/java/com/wyltek/wallet/agent/server/AgentDtos.kt \
        app/src/main/java/com/wyltek/wallet/agent/server/AgentDispatchPort.kt \
        app/src/main/java/com/wyltek/wallet/agent/server/AgentServer.kt \
        app/src/test/java/com/wyltek/wallet/agent/AgentServerTest.kt
git commit -m "feat(agent): /relay/* async routes + port methods (relay-compatible facade)"
```

---

## Task 2: `intent_id` helper (pure, host-JVM TDD)

**Files:**
- Create: `app/src/main/java/com/wyltek/wallet/agent/RelayIntentId.kt`
- Test: `app/src/test/java/com/wyltek/wallet/agent/RelayIntentIdTest.kt`

**Interfaces:**
- Produces: `fun relayIntentId(token: String, nonce: String): String` — `hex(sha256(token ‖ 0x00 ‖ nonce))`, lowercase hex, deterministic, idempotent per `(token,nonce)`.

- [ ] **Step 1: Write the failing test**

Create `RelayIntentIdTest.kt`:
```kotlin
package com.wyltek.wallet.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class RelayIntentIdTest {
    @Test fun `deterministic per token and nonce`() {
        assertEquals(relayIntentId("tok", "n1"), relayIntentId("tok", "n1"))
    }
    @Test fun `differs on nonce`() {
        assertNotEquals(relayIntentId("tok", "n1"), relayIntentId("tok", "n2"))
    }
    @Test fun `differs on token`() {
        assertNotEquals(relayIntentId("tokA", "n1"), relayIntentId("tokB", "n1"))
    }
    @Test fun `is 64-char lowercase hex`() {
        val id = relayIntentId("tok", "n1")
        assertEquals(64, id.length)
        assertEquals(id.lowercase(), id)
        assert(id.all { it in "0123456789abcdef" })
    }
    @Test fun `separator prevents token-nonce boundary collision`() {
        // ("ab","c") vs ("a","bc") must not collide thanks to the 0x00 separator
        assertNotEquals(relayIntentId("ab", "c"), relayIntentId("a", "bc"))
    }
}
```

Run:
```bash
cd /home/phill/wyltek-wallet && ./gradlew :app:testDebugUnitTest --tests "com.wyltek.wallet.agent.RelayIntentIdTest"
```
Expected: FAIL to compile — `relayIntentId` undefined.

- [ ] **Step 2: Implement the helper**

Create `RelayIntentId.kt`:
```kotlin
package com.wyltek.wallet.agent

import java.security.MessageDigest

/** Opaque, idempotent intent id for the /relay facade: sha256(token ‖ 0x00 ‖ nonce), hex.
 *  The 0x00 separator prevents (token,nonce) boundary-shift collisions. */
fun relayIntentId(token: String, nonce: String): String {
    val md = MessageDigest.getInstance("SHA-256")
    md.update(token.toByteArray(Charsets.UTF_8))
    md.update(0)
    md.update(nonce.toByteArray(Charsets.UTF_8))
    return md.digest().joinToString("") { "%02x".format(it) }
}
```

- [ ] **Step 3: Run — expect PASS**

```bash
cd /home/phill/wyltek-wallet && ./gradlew :app:testDebugUnitTest --tests "com.wyltek.wallet.agent.RelayIntentIdTest"
```
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
cd /home/phill/wyltek-wallet
git add app/src/main/java/com/wyltek/wallet/agent/RelayIntentId.kt \
        app/src/test/java/com/wyltek/wallet/agent/RelayIntentIdTest.kt
git commit -m "feat(agent): relayIntentId(token,nonce) = sha256 helper for the /relay facade"
```

---

## Task 3: `PendingStore`/DAO — lookup by intent_id + terminal upsert

**Files:**
- Modify: `app/src/main/java/com/wyltek/wallet/agent/db/AgentDao.kt`
- Modify: `app/src/main/java/com/wyltek/wallet/agent/PendingStore.kt`

**Interfaces:**
- Consumes: `PendingIntentEntity` (has `relay_intent_id` column), `Intent`.
- Produces: `PendingStore.getByRelayIntentId(id: String): PendingIntentEntity?`; `PendingStore.putTerminalByRelayIntent(intentId, intent, sourceIp, now, status, txHash, error)` — idempotent insert of a terminal tracking row keyed by `relayIntentId`.

- [ ] **Step 1: Add DAO queries**

In `AgentDao.kt`, add to the `// ---- pending intents ----` section:
```kotlin
    @Query("SELECT * FROM pending_intents WHERE relay_intent_id=:rid LIMIT 1")
    suspend fun pendingByRelayIntentId(rid: String): PendingIntentEntity?

    @Query("SELECT COUNT(*) FROM pending_intents WHERE relay_intent_id=:rid")
    suspend fun countByRelayIntentId(rid: String): Int

    /** Atomic idempotent insert of a terminal /relay tracking row. Returns the row id,
     *  or the existing row's id if this relay_intent_id was already recorded. */
    @Transaction
    suspend fun insertTerminalRelay(p: PendingIntentEntity): Long {
        val existing = pendingByRelayIntentId(p.relayIntentId!!)
        if (existing != null) return existing.id
        return insertPending(p)
    }
```
Add `import androidx.room.Transaction` if not present.

- [ ] **Step 2: Add PendingStore wrappers**

In `PendingStore.kt`, add:
```kotlin
    suspend fun getByRelayIntentId(id: String): PendingIntentEntity? = dao.pendingByRelayIntentId(id)

    /** Insert (idempotently) a terminal tracking row for an auto-Sent/Denied/Failed /relay intent. */
    suspend fun putTerminalByRelayIntent(
        intentId: String, intent: com.wyltek.wallet.core.native.Intent,
        sourceIp: String, now: Long, status: String, txHash: String?, error: String?,
    ): Long = dao.insertTerminalRelay(
        com.wyltek.wallet.agent.db.PendingIntentEntity(
            token = "", op = intent.op, asset = intent.asset, to = intent.to, amount = intent.amount,
            nonce = intent.nonce, action = intent.action, daoRef = intent.daoRef, sourceIp = sourceIp,
            status = status, createdAt = now, resultTxHash = txHash, resultError = error,
            relayIntentId = intentId,
        )
    )
```
(`token` is stored empty on the tracking row — the biscuit was already consumed at dispatch; the row exists only for POS polling. Do not persist the token on terminal rows.)

- [ ] **Step 3: Compile gate**

Run:
```bash
cd /home/phill/wyltek-wallet && ./gradlew :app:compileDebugKotlin
```
Expected: BUILD SUCCESSFUL. (Room validates the new `@Query`/`@Transaction` at compile time; a bad column name fails here.)

- [ ] **Step 4: Commit**

```bash
cd /home/phill/wyltek-wallet
git add app/src/main/java/com/wyltek/wallet/agent/db/AgentDao.kt \
        app/src/main/java/com/wyltek/wallet/agent/PendingStore.kt
git commit -m "feat(agent): PendingStore lookup-by-relayIntentId + idempotent terminal upsert"
```

---

## Task 4: `AgentGateway` facade implementation

**Files:**
- Modify: `app/src/main/java/com/wyltek/wallet/agent/AgentGateway.kt`

**Interfaces:**
- Consumes: `relayIntentId` (Task 2), `PendingStore.getByRelayIntentId`/`putTerminalByRelayIntent`/`linkRelayIntent` (Task 3 + existing), `dispatcher.dispatch`, `DispatchResult`, `PENDING_SENT`/`PENDING_DENIED`/`PENDING_FAILED`.
- Produces: `AgentGateway.submitRelayIntent(...)` + `AgentGateway.relayIntentStatus(...)` (the `AgentDispatchPort` methods from Task 1), backed by a background scope.

- [ ] **Step 1: Add a background scope + implement the two methods**

In `AgentGateway.kt`, add a member scope near the other fields:
```kotlin
    private val gatewayScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
    )
```

Add the two overrides (alongside the existing `dispatch`/`pendingStatus`/`accounts` `AgentDispatchPort` impls):
```kotlin
    override suspend fun submitRelayIntent(
        token: String, intent: Intent, sourceIp: String, nowUnix: Long
    ): String {
        val intentId = relayIntentId(token, intent.nonce)
        // Idempotent: if we already track this intent_id, don't re-dispatch.
        if (pendingStore.getByRelayIntentId(intentId) != null) return intentId

        gatewayScope.launch {
            val result = try {
                dispatcher.dispatch(token, intent, sourceIp, nowUnix)
            } catch (e: Throwable) {
                pendingStore.putTerminalByRelayIntent(
                    intentId, intent, sourceIp, nowUnix,
                    com.wyltek.wallet.agent.db.PENDING_FAILED, null, e.message ?: "dispatch error"
                )
                return@launch
            }
            when (result) {
                is DispatchResult.Sent -> pendingStore.putTerminalByRelayIntent(
                    intentId, intent, sourceIp, nowUnix,
                    com.wyltek.wallet.agent.db.PENDING_SENT, result.txHash, null
                )
                is DispatchResult.Denied -> pendingStore.putTerminalByRelayIntent(
                    intentId, intent, sourceIp, nowUnix,
                    com.wyltek.wallet.agent.db.PENDING_DENIED, null, result.reason
                )
                is DispatchResult.Failed -> pendingStore.putTerminalByRelayIntent(
                    intentId, intent, sourceIp, nowUnix,
                    com.wyltek.wallet.agent.db.PENDING_FAILED, null, result.message
                )
                is DispatchResult.Approval ->
                    // The approval row already exists (dispatch created it); make it findable by
                    // intent_id so the merchant's later Approve updates the row the POS polls.
                    pendingStore.linkRelayIntent(result.pendingId, intentId)
            }
        }
        return intentId
    }

    override suspend fun relayIntentStatus(intentId: String): IntentStatusResponse? {
        val row = pendingStore.getByRelayIntentId(intentId) ?: return null
        return IntentStatusResponse(
            status = row.status, txHash = row.resultTxHash, error = row.resultError
        )
    }
```
Add imports: `import com.wyltek.wallet.agent.server.IntentStatusResponse` (if not present), `import kotlinx.coroutines.launch`.

> Note: the merchant-approval completion path (`AgentViewModel.approve` → execute → `pendingStore.setResult(...)`) already updates the row by id; because Task-4 linked the row's `relay_intent_id`, `relayIntentStatus` finds the updated row — no change to approval execution. Verify `setResult` writes `status`/`resultTxHash`/`resultError` (it does: `setPendingResult`), so an approved row surfaces `status="sent"`+txHash to the POS poll.

- [ ] **Step 2: Compile gate**

Run:
```bash
cd /home/phill/wyltek-wallet && ./gradlew :app:compileDebugKotlin
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
cd /home/phill/wyltek-wallet
git add app/src/main/java/com/wyltek/wallet/agent/AgentGateway.kt
git commit -m "feat(agent): AgentGateway /relay facade — async dispatch mapping + link-on-approval"
```

---

## Task 5: Provisioning QR — 3-field bundle + `service_name`

**Files:**
- Modify: `app/src/main/java/com/wyltek/wallet/agent/provisioning/AgentProvisioning.kt`
- Test: `app/src/test/java/com/wyltek/wallet/agent/AgentProvisioningTest.kt` (create if absent)

**Interfaces:**
- Produces: `fun serviceNameFor(deviceId: String): String` = `"blackbox-" + deviceId.filter{it.isLetterOrDigit()}.take(8).lowercase()`; `buildProvisioningBundle(deviceId, token)` now emits `{device_id, token, service_name}`.

- [ ] **Step 1: Write the failing test**

Create/extend `AgentProvisioningTest.kt`:
```kotlin
package com.wyltek.wallet.agent

import com.wyltek.wallet.agent.provisioning.buildProvisioningBundle
import com.wyltek.wallet.agent.provisioning.serviceNameFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentProvisioningTest {
    @Test fun `serviceName is blackbox plus 8 hex of deviceId`() {
        assertEquals("blackbox-1a2b3c4d", serviceNameFor("1a2b3c4d-ffff-0000-1111-222233334444"))
    }
    @Test fun `bundle carries device_id token and service_name`() {
        val b = buildProvisioningBundle("1a2b3c4d-ffff", "biscuit-b64")!!
        assertTrue(b.contains(""""device_id":"1a2b3c4d-ffff""""))
        assertTrue(b.contains(""""token":"biscuit-b64""""))
        assertTrue(b.contains(""""service_name":"blackbox-1a2b3c4d""""))
    }
    @Test fun `blank deviceId yields null bundle`() {
        assertNull(buildProvisioningBundle("", "t"))
        assertNull(buildProvisioningBundle("   ", "t"))
    }
}
```

Run:
```bash
cd /home/phill/wyltek-wallet && ./gradlew :app:testDebugUnitTest --tests "com.wyltek.wallet.agent.AgentProvisioningTest"
```
Expected: FAIL to compile — `serviceNameFor` undefined and the 2-field bundle lacks `service_name`.

- [ ] **Step 2: Implement**

In `AgentProvisioning.kt`, add the helper and extend the bundle:
```kotlin
/** Stable mDNS instance name for this device: "blackbox-" + first 8 alphanumerics of the id. */
fun serviceNameFor(deviceId: String): String =
    "blackbox-" + deviceId.filter { it.isLetterOrDigit() }.take(8).lowercase()

fun buildProvisioningBundle(deviceId: String?, token: String): String? {
    val id = deviceId?.ifBlank { null } ?: return null
    val svc = serviceNameFor(id)
    return """{"device_id":"$id","token":"$token","service_name":"$svc"}"""
}
```

- [ ] **Step 3: Run — expect PASS**

```bash
cd /home/phill/wyltek-wallet && ./gradlew :app:testDebugUnitTest --tests "com.wyltek.wallet.agent.AgentProvisioningTest"
```
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
cd /home/phill/wyltek-wallet
git add app/src/main/java/com/wyltek/wallet/agent/provisioning/AgentProvisioning.kt \
        app/src/test/java/com/wyltek/wallet/agent/AgentProvisioningTest.kt
git commit -m "feat(agent): provisioning QR gains service_name (blackbox-<8hex>) for Tier-1 mDNS"
```

---

## Task 6: Device transport — LAN bind + self-signed TLS

**Files:**
- Create: `app/src/main/java/com/wyltek/wallet/agent/server/LanAddress.kt`
- Create: `app/src/main/java/com/wyltek/wallet/agent/server/AgentTls.kt`
- Modify: `app/src/main/java/com/wyltek/wallet/agent/service/AgentGatewayService.kt`
- Modify: `app/build.gradle.kts` (add `io.ktor:ktor-network-tls-certificates`)

**Interfaces:**
- Produces: `LanAddress.bindAddress(): String?` (active Wi-Fi site-local IPv4); `AgentTls.keyStore(context): java.security.KeyStore` (persisted self-signed); the service binds an `sslConnector` there.

- [ ] **Step 1: Add the TLS-cert dependency**

In `app/build.gradle.kts` dependencies, add (match the Ktor version already used, `3.1.3`):
```kotlin
    implementation("io.ktor:ktor-network-tls-certificates:3.1.3")
```

- [ ] **Step 2: LAN address resolver**

Create `LanAddress.kt` (sibling to `Tailnet.kt`):
```kotlin
package com.wyltek.wallet.agent.server

import java.net.Inet4Address
import java.net.NetworkInterface

object LanAddress {
    /** Active site-local IPv4 (192.168/, 10., 172.16–31) — the LAN/Wi-Fi address, skipping
     *  loopback, the Tailnet CGNAT range (100.64–127), and non-site-local addresses. Null if none. */
    fun bindAddress(): String? {
        for (nif in NetworkInterface.getNetworkInterfaces()) {
            if (!nif.isUp || nif.isLoopback) continue
            for (addr in nif.inetAddresses) {
                if (addr !is Inet4Address || addr.isLoopbackAddress) continue
                if (addr.isSiteLocalAddress) return addr.hostAddress   // 10/8, 172.16/12, 192.168/16
            }
        }
        return null
    }
}
```

- [ ] **Step 3: Self-signed keystore (generate once, persist)**

Create `AgentTls.kt`:
```kotlin
package com.wyltek.wallet.agent.server

import android.content.Context
import io.ktor.network.tls.certificates.buildKeyStore
import java.io.File
import java.security.KeyStore

object AgentTls {
    const val ALIAS = "agent"
    private const val PASS = "agentpass"           // local file password; trust is the biscuit, not this
    private const val FILE = "agent_tls.jks"

    /** Load the persisted self-signed keystore, generating + persisting it on first use.
     *  CN/SAN are irrelevant (the POS uses setInsecure), so a fixed cert survives IP changes. */
    fun keyStore(context: Context): KeyStore {
        val f = File(context.filesDir, FILE)
        if (f.exists()) {
            return KeyStore.getInstance("JKS").apply {
                f.inputStream().use { load(it, PASS.toCharArray()) }
            }
        }
        val ks = buildKeyStore { certificate(ALIAS) { password = PASS; domains = listOf("blackbox-agent") } }
        f.outputStream().use { ks.store(it, PASS.toCharArray()) }
        return ks
    }

    fun password(): CharArray = PASS.toCharArray()
}
```

- [ ] **Step 4: Bind the server over TLS on the LAN address**

In `AgentGatewayService.kt` `handleStart()`, replace the `Tailnet.bindAddress()` + plain `embeddedServer(CIO, host = bindAddr, port = SERVER_PORT)` block with a LAN-first, TLS bind (keep the Tailnet co-connector if present):
```kotlin
        val lan = com.wyltek.wallet.agent.server.LanAddress.bindAddress()
        val tailnet = Tailnet.bindAddress()
        if (lan == null && tailnet == null) {
            Log.w(TAG, "no LAN or Tailnet address — cannot start agent gateway")
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.notify(NOTIF_ID_TAILNET_ERROR, AgentNotifications.serverNotification(this, "no local network"))
            stopSelf(); return
        }

        val androidApp = application as WyltekWalletApp
        val port: AgentDispatchPort = androidApp.agentGateway
        val ks = com.wyltek.wallet.agent.server.AgentTls.keyStore(this)

        server = embeddedServer(CIO, configure = {
            if (lan != null) sslConnector(ks, com.wyltek.wallet.agent.server.AgentTls.ALIAS,
                { com.wyltek.wallet.agent.server.AgentTls.password() },
                { com.wyltek.wallet.agent.server.AgentTls.password() }) { host = lan; port = SERVER_PORT }
            if (tailnet != null) sslConnector(ks, com.wyltek.wallet.agent.server.AgentTls.ALIAS,
                { com.wyltek.wallet.agent.server.AgentTls.password() },
                { com.wyltek.wallet.agent.server.AgentTls.password() }) { host = tailnet; port = SERVER_PORT }
        }, module = { agentModule(port) }).start(wait = false)

        val boundAddr = lan ?: tailnet
```
Replace subsequent references to `bindAddr` in that method with `boundAddr`. (The `RelayClient` start block below is unchanged.)

- [ ] **Step 5: Compile gate**

Run:
```bash
cd /home/phill/wyltek-wallet && ./gradlew :app:compileDebugKotlin
```
Expected: BUILD SUCCESSFUL (resolves `ktor-network-tls-certificates`, `sslConnector`, `buildKeyStore`).

- [ ] **Step 6: Commit**

```bash
cd /home/phill/wyltek-wallet
git add app/build.gradle.kts \
        app/src/main/java/com/wyltek/wallet/agent/server/LanAddress.kt \
        app/src/main/java/com/wyltek/wallet/agent/server/AgentTls.kt \
        app/src/main/java/com/wyltek/wallet/agent/service/AgentGatewayService.kt
git commit -m "feat(agent): serve the gateway over self-signed TLS on the LAN (Tailnet co-connector kept)"
```

---

## Task 7: Device discovery — mDNS advertise + Wi-Fi lifecycle

**Files:**
- Create: `app/src/main/java/com/wyltek/wallet/agent/server/AgentMdns.kt`
- Modify: `app/src/main/java/com/wyltek/wallet/agent/service/AgentGatewayService.kt`

**Interfaces:**
- Consumes: `NsdManager`, the bound LAN address/port, `agent_svc` service_name via the provisioned device (`AgentGateway`/secure store), `SERVER_PORT`.
- Produces: `AgentMdns` (register/unregister `_blackbox-agent._tcp`); a `ConnectivityManager` callback in the service that restarts the gateway on Wi-Fi change.

- [ ] **Step 1: mDNS advertiser**

Create `AgentMdns.kt`:
```kotlin
package com.wyltek.wallet.agent.server

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log

/** Advertises _blackbox-agent._tcp on the LAN while the gateway runs. Instance name = service_name;
 *  a device_id TXT record lets the POS match the exact instance if Android renames on collision. */
class AgentMdns(context: Context) {
    private val TAG = "AgentMdns"
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var listener: NsdManager.RegistrationListener? = null

    fun register(serviceName: String, deviceId: String, port: Int) {
        unregister()
        val info = NsdServiceInfo().apply {
            this.serviceName = serviceName
            serviceType = "_blackbox-agent._tcp."
            this.port = port
            setAttribute("device_id", deviceId)
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(s: NsdServiceInfo) = Log.i(TAG, "mDNS registered: ${s.serviceName}")
            override fun onRegistrationFailed(s: NsdServiceInfo, code: Int) = Log.w(TAG, "mDNS register failed: $code")
            override fun onServiceUnregistered(s: NsdServiceInfo) = Log.i(TAG, "mDNS unregistered")
            override fun onUnregistrationFailed(s: NsdServiceInfo, code: Int) = Log.w(TAG, "mDNS unregister failed: $code")
        }
        listener = l
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
    }

    fun unregister() {
        listener?.let { try { nsd.unregisterService(it) } catch (_: Exception) {} }
        listener = null
    }
}
```

- [ ] **Step 2: Register mDNS after start; unregister on stop; restart on Wi-Fi change**

In `AgentGatewayService.kt`:

Add fields:
```kotlin
    private var mdns: com.wyltek.wallet.agent.server.AgentMdns? = null
    private var netCallback: android.net.ConnectivityManager.NetworkCallback? = null
```

At the end of `handleStart()` (after `startForeground(...)`), advertise using the provisioned device_id + derived service_name:
```kotlin
        if (lan != null) {
            val deviceId = androidApp.agentGateway.deviceId()          // provisioned id (add accessor if needed)
            val svc = com.wyltek.wallet.agent.provisioning.serviceNameFor(deviceId)
            mdns = com.wyltek.wallet.agent.server.AgentMdns(this).also { it.register(svc, deviceId, SERVER_PORT) }
        }
        registerWifiCallback()
```
> If `AgentGateway` has no `deviceId()` accessor, add one that reads the provisioned device_id from `secure`/DB (the same id minted into the QR). Do NOT invent a new id — it must equal the QR's `device_id` so the POS's `service_name` matches.

Add the Wi-Fi callback (restart the gateway when connectivity changes so a stale bind re-binds):
```kotlin
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

    private fun restart() {
        // Re-bind + re-advertise on the current Wi-Fi address. Debounce trivial repeats by
        // only restarting if still running.
        if (!running) return
        serviceScope.launch { // hop off the callback thread
            handleStop()
            handleStart()
        }
    }
```

In `handleStop()` (and `onDestroy()`), tear down mDNS + the callback:
```kotlin
        mdns?.unregister(); mdns = null
        netCallback?.let {
            (getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager)
                .unregisterNetworkCallback(it)
        }
        netCallback = null
```
Add `import kotlinx.coroutines.launch` if needed.

- [ ] **Step 3: Compile gate**

Run:
```bash
cd /home/phill/wyltek-wallet && ./gradlew :app:compileDebugKotlin
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Full assemble (catches manifest/resource issues the compile step misses)**

Run:
```bash
cd /home/phill/wyltek-wallet && ./gradlew :app:assembleDebug
```
Expected: BUILD SUCCESSFUL. (Confirm no new manifest permission is required — `NsdManager` and `ConnectivityManager` need `INTERNET`/`ACCESS_NETWORK_STATE`, already present for the existing server/relay; add `<uses-permission>` only if the build/lint flags it.)

- [ ] **Step 5: Commit**

```bash
cd /home/phill/wyltek-wallet
git add app/src/main/java/com/wyltek/wallet/agent/server/AgentMdns.kt \
        app/src/main/java/com/wyltek/wallet/agent/service/AgentGatewayService.kt
git commit -m "feat(agent): advertise _blackbox-agent._tcp via NsdManager + re-bind on Wi-Fi change"
```

---

## Manual verification (hardware-gated, run OUTSIDE the sandbox)

End-to-end Tier-1 needs the physical POS + phone on one LAN — **this closes POS #1's deferred Tier-1 HW checkpoint.** Capture in HARDWARE-NOTES:

1. Provision the POS from the phone's 3-field QR; confirm the POS enters Tier 1 (mDNS resolves `_blackbox-agent._tcp`).
2. Within-cap refund/sweep → POS reaches `SENT` against `https://<phone>:8443/relay/intent` with **no relay, no Tailscale**.
3. Over-cap intent → POS shows waiting; merchant taps Approve → POS reaches `SENT` (approval landed on the local row).
4. Denied/failed intent → POS shows the decline.
5. Toggle the phone's Wi-Fi → gateway re-binds + re-advertises; a fresh submit still works.
6. A pre-#2-provisioned POS (2-field QR) still works as node tier (regression).

---

## Self-Review

**Spec coverage:** §5 facade → Tasks 1–4; §6 discovery/service_name → Tasks 5 & 7; §7 TLS → Task 6; §8 bind/lifecycle → Tasks 6–7; §9 error handling → Task 6 Step 4 (no-network), Task 7 Step 1 (mDNS fail), Task 6 Step 3 (TLS); §10 testing → host-JVM Tasks 1,2,5 + compile gates + manual; §11 scope boundary → no `/v1`/`RelayClient`/relay/confirm changes in any task. ✔

**Placeholder scan:** every step has real code + a concrete gradle command. The one conditional ("add `deviceId()` accessor if absent") names exactly what to read and the invariant (must equal the QR device_id) — not a blank.

**Type consistency:** `submitRelayIntent`/`relayIntentStatus` signatures identical across the interface (T1), fake (T1), routes (T1), and `AgentGateway` impl (T4). `RelayAcceptResponse(status, intentId@"intent_id")` used in DTO + routes + test. `relayIntentId(token,nonce)` (T2) called in T4. `getByRelayIntentId`/`putTerminalByRelayIntent` (T3) called in T4. `serviceNameFor` (T5) called in T7. `PENDING_SENT/DENIED/FAILED` constants reused, not re-spelled.
