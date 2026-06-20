# Agent Gateway — Plan C: Keyless Relay + Wake — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reach the phone when the Direct (tailnet) path can't — phone behind carrier NAT, off-tailnet, or in Doze. A **keyless** Python/FastAPI relay on `wyltek-10700` accepts agent intents, pre-validates the biscuit token (public-key only), captures source IP, queues the intent, and wakes the phone via a **persistent tailnet connection (primary)** or **FCM (fallback)**. The phone's existing foreground service pulls the intent, runs the **same** `AgentActionDispatcher` (re-validating everything authoritatively), and returns the result through the relay. The relay never holds keys and never signs.

**Architecture:** Two codebases. (1) `~/agent-gateway-relay/` — FastAPI app (deployed as a systemd unit beside `rag-service`), holding only the device's biscuit **root public key** (uploaded at pairing). It does *advisory* pre-checks (biscuit signature + ttl/scope/allow_ip caveats via `biscuit-python`, a revocation list, advisory cap pre-accounting) to drop obviously-bad/spam requests before spending a wake, then queues + wakes. (2) `wyltek-wallet` Android additions — a `RelayClient` in `AgentGatewayService` (Plan B2) holding an OkHttp WebSocket to the relay over the tailnet (auth'd with a device token), draining queued intents through `dispatcher.dispatch`, posting results back; plus a Firebase `FirebaseMessagingService` that, on a high-priority data message, starts/nudges the service to connect-and-drain. **The device is always the authority** — the relay's checks are an optimization and an outer fence.

**Tech Stack:** Python 3.11 + FastAPI + uvicorn/gunicorn + `biscuit-python` + `firebase-admin` + SQLite (relay); Kotlin + OkHttp WebSocket (already a dep) + `firebase-messaging` + Google Services plugin (device).

## Global Constraints

- **Depends on Plans B1 + B2.** The relay client reuses `AgentGateway.dispatcher` (B1) and lives inside `AgentGatewayService` (B2). Execute B1 + B2 first.
- **Keyless relay.** The relay stores only the biscuit **root public key** (hex) per paired device + a per-device auth token. It MUST NOT receive or store any private key, seed, or signing material. Pre-validation uses `biscuit-python` public verification only. The phone re-runs the authoritative `decide()` on every intent.
- **Deploy convention (wyltek-10700):** mirror `rag-service` — a systemd unit running gunicorn with uvicorn workers, in its own repo `~/agent-gateway-relay/`, bound to the tailnet/LAN interface, with `MemoryMax` bounding blast radius. Do not co-mingle with `rag-service`.
- **Transport:** agent→relay and phone→relay both run over the tailnet (or LAN); the device-token auth + biscuit are the access controls. FCM carries only an opaque wake nudge (no intent contents, no secrets) — the phone pulls the actual intent from the relay over the authenticated channel.
- **Device is authority:** any relay pre-check that passes still goes through the full device `dispatch()`; any relay pre-check that fails returns `denied` to the agent WITHOUT waking the phone. The relay's advisory cap ledger is never the source of truth.
- **Idempotency:** intents are keyed by `(token_id, nonce)` end-to-end; the relay dedups and the device ledger's unique `(tokenId,nonce)` is the hard guard.
- **Firebase is an operator prerequisite:** creating the Firebase project, downloading `google-services.json`, and provisioning the Admin service-account JSON to the relay are operator steps (documented in the FCM task). The persistent-connection path works without Firebase; FCM is the fallback.
- Commit after each task; conventional messages; no attribution footer. Relay commits go in `~/agent-gateway-relay/`; Android commits in `~/wyltek-wallet`.

## Surfaces this plan builds on

```kotlin
// Plan B1/B2 (wyltek-wallet)
class AgentGateway(context) { val dispatcher; val tokenService; val pendingStore; /* + */ val keyStore: AgentKeyStore }
// AgentKeyStore.rootPublicHex(): String              // the biscuit root PUBLIC key to upload at pairing
suspend fun AgentActionDispatcher.dispatch(token, intent, sourceIp, nowUnix): DispatchResult
// AgentGatewayService (B2 foreground service) — Plan C adds a RelayClient inside it
// Rust binding: Intent(op,asset,to,amount,nonce,action,daoRef)
```

## File Structure

| File | Responsibility |
|------|----------------|
| `~/agent-gateway-relay/pyproject.toml` + `requirements.txt` (create) | deps (fastapi, uvicorn, gunicorn, biscuit-python, firebase-admin) |
| `~/agent-gateway-relay/relay/config.py` (create) | settings (bind addr, db path, fcm creds path) |
| `~/agent-gateway-relay/relay/store.py` (create) | SQLite: devices, intents, revocations, advisory ledger |
| `~/agent-gateway-relay/relay/biscuit_check.py` (create) | public biscuit pre-validation via biscuit-python |
| `~/agent-gateway-relay/relay/wake.py` (create) | connection registry + FCM sender |
| `~/agent-gateway-relay/relay/app.py` (create) | FastAPI app: pairing, agent, device endpoints |
| `~/agent-gateway-relay/deploy/agent-relay.service` (create) | systemd unit |
| `~/agent-gateway-relay/tests/*` (create) | pytest |
| `app/.../agent/relay/RelayClient.kt` (create) | OkHttp WebSocket client, drain loop |
| `app/.../agent/relay/RelayPairing.kt` (create) | pair: upload root pubkey + device token |
| `app/.../agent/service/AgentGatewayService.kt` (modify) | own the RelayClient lifecycle |
| `app/.../agent/fcm/AgentFirebaseService.kt` (create) | FCM wake → nudge service |
| `app/build.gradle.kts` + root `build.gradle.kts` + `AndroidManifest.xml` + `google-services.json` (modify/add) | Firebase wiring |
| `app/.../ui/screens/AgentScreen.kt` (modify) | relay pairing UI |

---

### Task 1: Relay scaffold + biscuit public pre-validation

**Files:** `~/agent-gateway-relay/{pyproject.toml,requirements.txt,relay/config.py,relay/biscuit_check.py,tests/test_biscuit_check.py}`

**Interfaces:**
- Produces: `biscuit_check.precheck(token: str, root_pub_hex: str, op: str, recipient: str, source_ip: str, now_unix: int) -> PrecheckResult` where `PrecheckResult` = `ok: bool, token_id: str|None, reason: str|None`.

- [ ] **Step 1: Scaffold**

```bash
mkdir -p ~/agent-gateway-relay/relay ~/agent-gateway-relay/tests ~/agent-gateway-relay/deploy
cd ~/agent-gateway-relay && git init
```
`requirements.txt`:
```
fastapi==0.115.*
uvicorn[standard]==0.34.*
gunicorn==23.*
biscuit-python==0.3.*
firebase-admin==6.*
pydantic==2.*
pytest==8.*
httpx==0.27.*
```
`relay/config.py`:
```python
import os
class Settings:
    bind_host = os.environ.get("RELAY_HOST", "127.0.0.1")   # set to the tailnet addr in the unit
    bind_port = int(os.environ.get("RELAY_PORT", "9991"))
    db_path = os.environ.get("RELAY_DB", os.path.expanduser("~/agent-gateway-relay/relay.sqlite"))
    fcm_creds = os.environ.get("RELAY_FCM_CREDS", "")        # path to firebase admin json; empty = FCM disabled
settings = Settings()
```

- [ ] **Step 2: Biscuit pre-validation + test**

`relay/biscuit_check.py`:
```python
from dataclasses import dataclass
from datetime import datetime, timezone
from biscuit_auth import Biscuit, PublicKey, Authorizer, Rule

@dataclass
class PrecheckResult:
    ok: bool
    token_id: str | None = None
    reason: str | None = None

def precheck(token: str, root_pub_hex: str, op: str, recipient: str, source_ip: str, now_unix: int) -> PrecheckResult:
    """Public, advisory pre-validation. The device re-checks authoritatively."""
    try:
        pk = PublicKey.from_hex(root_pub_hex)
        b = Biscuit.from_base64(token, pk)            # verifies root signature
    except Exception as e:
        return PrecheckResult(False, reason=f"parse: {e}")
    try:
        az = Authorizer()
        az.add_token(b)
        # ambient facts the token's checks reference (mirror the device authorizer)
        az.add_fact(f'operation("{op}")')
        az.add_fact(f'recipient("{recipient}")')
        az.add_fact(f'source_ip("{source_ip}")')
        # account check can't run here (relay doesn't know which account) — the device enforces it.
        # Provide requested_account matching the token's own account so the account check passes advisorily:
        az.add_code('requested_account($a) <- account($a);')
        ts = datetime.fromtimestamp(now_unix, tz=timezone.utc).isoformat()
        az.add_fact(f'time({ts})')
        az.add_policy("allow if true")
        az.authorize()                                 # raises if any caveat fails (ttl/scope/allow_to/allow_ip)
    except Exception as e:
        return PrecheckResult(False, reason=f"denied: {e}")
    # token id for dedup/revocation
    try:
        facts = az.query(Rule('id($id) <- token_id($id)'))
        token_id = str(facts[0].terms[0]) if facts else None
    except Exception:
        token_id = None
    return PrecheckResult(True, token_id=token_id)
```
`tests/test_biscuit_check.py`: generate a token with biscuit-python (or load a fixture minted by the Rust wallet) and assert: valid in-scope op → `ok`; wrong op (scope not granted) → not ok; expired ttl → not ok; tampered token / wrong pubkey → not ok.

> Confirm `biscuit-python` 0.3 API names (`PublicKey.from_hex`, `Biscuit.from_base64`, `Authorizer.add_token/add_fact/add_code/add_policy/authorize/query`, the `time` term format — biscuit dates are RFC3339). Adjust to the installed API; the device path proved the same caveats in Rust, so semantics are known. If `account`-as-`requested_account` rewrite is awkward, drop the account check here (relay is advisory; device enforces it) and note it.

- [ ] **Step 3: Run + commit**

```bash
cd ~/agent-gateway-relay && python -m venv .venv && . .venv/bin/activate && pip install -r requirements.txt
pytest -q
git add . && git commit -m "feat(relay): scaffold + biscuit public pre-validation"
```

---

### Task 2: Relay store (devices, intents, revocations, advisory ledger)

**Files:** `~/agent-gateway-relay/relay/store.py`, `tests/test_store.py`

**Interfaces:**
- Produces: `Store(db_path)` with: `pair_device(device_id, root_pub_hex, device_token_hash) `; `device_by_token(device_token) -> Row|None`; `device_root_pub(device_id) -> str|None`; `enqueue(intent_id, device_id, token, intent_json, source_ip) -> bool` (False if dup `intent_id`); `next_for_device(device_id) -> Row|None`; `set_result(intent_id, status, tx_hash, error)`; `get_intent(intent_id) -> Row|None`; `revoke(token_id)`; `is_revoked(token_id) -> bool`; advisory `record_spend(token_id, asset, amount)` / `advisory_cumulative(token_id, asset) -> int`.

- [ ] **Step 1: Implement (SQLite, WAL) + tests**

`relay/store.py`: a thin SQLite wrapper (stdlib `sqlite3`, `PRAGMA journal_mode=WAL`) with tables:
- `devices(device_id PK, root_pub_hex, device_token_hash, paired_at)`
- `intents(intent_id PK, device_id, token, intent_json, source_ip, status, tx_hash, error, created_at)` — `intent_id = sha256(token_id ‖ nonce)` so dedup is natural.
- `revocations(token_id PK, at)`
- `advisory_ledger(token_id, asset, amount, at)`
Store device tokens **hashed** (sha256) — never plaintext. `device_by_token` hashes the presented token and looks up.

`tests/test_store.py`: pair → lookup by token; enqueue dedups same intent_id; next_for_device returns oldest queued then marks in-flight; set_result updates; revoke/is_revoked; advisory cumulative sums.

- [ ] **Step 2: Run + commit**

```bash
cd ~/agent-gateway-relay && pytest -q tests/test_store.py
git add . && git commit -m "feat(relay): SQLite store for devices/intents/revocations/advisory ledger"
```

---

### Task 3: Agent-facing + device-facing HTTP + WebSocket endpoints

**Files:** `~/agent-gateway-relay/relay/{app.py,wake.py}`, `tests/test_app.py`, `~/agent-gateway-relay/deploy/agent-relay.service`

**Interfaces:**
- Produces a FastAPI app with: `POST /pair` (operator/phone), `POST /relay/intent` (agent), `GET /relay/intent/{id}` (agent), `WS /relay/device` (phone), `POST /relay/result` (phone), `POST /relay/revoke` (phone), `GET /healthz`.

- [ ] **Step 1: Connection registry + FCM sender**

`relay/wake.py`:
```python
import json
from typing import Optional
from relay.config import settings

class ConnectionRegistry:
    """device_id -> live WebSocket. Persistent path uses this; FCM is the fallback."""
    def __init__(self): self._conns = {}
    def add(self, device_id, ws): self._conns[device_id] = ws
    def remove(self, device_id): self._conns.pop(device_id, None)
    def is_connected(self, device_id) -> bool: return device_id in self._conns
    async def push(self, device_id, payload: dict) -> bool:
        ws = self._conns.get(device_id)
        if ws is None: return False
        try:
            await ws.send_text(json.dumps(payload)); return True
        except Exception:
            self.remove(device_id); return False

_fcm_ready = False
def _ensure_fcm():
    global _fcm_ready
    if _fcm_ready or not settings.fcm_creds: return _fcm_ready
    import firebase_admin
    from firebase_admin import credentials
    firebase_admin.initialize_app(credentials.Certificate(settings.fcm_creds))
    _fcm_ready = True
    return True

def fcm_wake(device_fcm_token: str) -> bool:
    """High-priority data-only nudge: 'connect and drain'. Carries NO intent contents."""
    if not _ensure_fcm() or not device_fcm_token: return False
    from firebase_admin import messaging
    msg = messaging.Message(
        data={"type": "agent_wake"},
        token=device_fcm_token,
        android=messaging.AndroidConfig(priority="high"),
    )
    try:
        messaging.send(msg); return True
    except Exception:
        return False
```
(Add an `fcm_token` column to `devices` in Task 2's schema, set at pairing — note this when implementing Task 2, or add a small migration here.)

- [ ] **Step 2: The app**

`relay/app.py` (key routes — full bodies):
```python
import hashlib, json, time
from fastapi import FastAPI, WebSocket, WebSocketDisconnect, Header, HTTPException
from pydantic import BaseModel
from relay.config import settings
from relay.store import Store
from relay.biscuit_check import precheck
from relay.wake import ConnectionRegistry, fcm_wake

app = FastAPI()
store = Store(settings.db_path)
registry = ConnectionRegistry()

def _intent_id(token_id: str, nonce: str) -> str:
    return hashlib.sha256(f"{token_id}\x00{nonce}".encode()).hexdigest()

class PairReq(BaseModel):
    device_id: str; root_pub_hex: str; device_token: str; fcm_token: str = ""
@app.post("/pair")
def pair(r: PairReq):
    store.pair_device(r.device_id, r.root_pub_hex, hashlib.sha256(r.device_token.encode()).hexdigest(), r.fcm_token)
    return {"ok": True}

class IntentReq(BaseModel):
    device_id: str; token: str
    op: str; asset: str; to: str = ""; amount: int = 0; nonce: str
    action: str | None = None; dao_ref: str | None = None
@app.post("/relay/intent")
async def relay_intent(r: IntentReq, x_forwarded_for: str | None = Header(default=None)):
    root_pub = store.device_root_pub(r.device_id)
    if not root_pub: raise HTTPException(404, "unknown device")
    source_ip = (x_forwarded_for or "").split(",")[0].strip() or "0.0.0.0"
    pc = precheck(r.token, root_pub, r.op, r.to, source_ip, int(time.time()))
    if not pc.ok: return {"status": "denied", "reason": pc.reason}
    if pc.token_id and store.is_revoked(pc.token_id): return {"status": "denied", "reason": "revoked"}
    iid = _intent_id(pc.token_id or r.token, r.nonce)
    payload = {"intent_id": iid, "token": r.token, "op": r.op, "asset": r.asset,
               "to": r.to, "amount": r.amount, "nonce": r.nonce, "action": r.action,
               "dao_ref": r.dao_ref, "source_ip": source_ip}
    fresh = store.enqueue(iid, r.device_id, r.token, json.dumps(payload), source_ip)
    if fresh:
        woke = await registry.push(r.device_id, {"type": "intent", **payload})
        if not woke:
            fcm_wake(store.device_fcm_token(r.device_id))   # fallback nudge; phone connects + drains
    return {"status": "queued", "intent_id": iid}

@app.get("/relay/intent/{iid}")
def intent_status(iid: str):
    row = store.get_intent(iid)
    if not row: raise HTTPException(404, "not found")
    return {"status": row["status"], "txHash": row["tx_hash"], "error": row["error"]}

def _auth_device(device_token: str) -> str:
    dev = store.device_by_token(device_token)
    if not dev: raise HTTPException(401, "bad device token")
    return dev["device_id"]

class ResultReq(BaseModel):
    device_token: str; intent_id: str; status: str; tx_hash: str | None = None; error: str | None = None
@app.post("/relay/result")
def relay_result(r: ResultReq):
    _auth_device(r.device_token)
    store.set_result(r.intent_id, r.status, r.tx_hash, r.error); return {"ok": True}

class RevokeReq(BaseModel):
    device_token: str; token_id: str
@app.post("/relay/revoke")
def relay_revoke(r: RevokeReq):
    _auth_device(r.device_token); store.revoke(r.token_id); return {"ok": True}

@app.websocket("/relay/device")
async def device_ws(ws: WebSocket):
    await ws.accept()
    token = ws.headers.get("x-device-token", "")
    dev = store.device_by_token(token)
    if not dev:
        await ws.close(code=4401); return
    device_id = dev["device_id"]
    registry.add(device_id, ws)
    try:
        # drain anything already queued, then keep the socket open for live pushes
        while True:
            nxt = store.next_for_device(device_id)
            if nxt: await ws.send_text(json.dumps({"type": "intent", **json.loads(nxt["intent_json"])}))
            await ws.receive_text()   # client heartbeat / ack; keeps the connection alive
    except WebSocketDisconnect:
        registry.remove(device_id)

@app.get("/healthz")
def healthz(): return {"ok": True}
```
`tests/test_app.py` (FastAPI `TestClient` + a websocket test): pair; POST a valid intent (with a fixture token) → `queued`; status endpoint reflects it; POST result with the device token → status flips to `sent`; revoke; a bad device token on the WS → closed; an intent failing precheck → `denied` without enqueue.

- [ ] **Step 3: systemd unit**

`deploy/agent-relay.service` (mirror `rag-service`):
```ini
[Unit]
Description=Agent Gateway Relay (keyless)
After=network-online.target
[Service]
User=phill
WorkingDirectory=/home/phill/agent-gateway-relay
Environment=RELAY_HOST=100.x.x.x   ; the tailnet addr of wyltek-10700
Environment=RELAY_PORT=9991
Environment=RELAY_FCM_CREDS=/home/phill/agent-gateway-relay/secrets/fcm-admin.json
ExecStart=/home/phill/agent-gateway-relay/.venv/bin/gunicorn relay.app:app -k uvicorn.workers.UvicornWorker -b ${RELAY_HOST}:${RELAY_PORT} --workers 2
MemoryHigh=512M
MemoryMax=768M
Restart=on-failure
[Install]
WantedBy=multi-user.target
```

- [ ] **Step 4: Run + commit**

```bash
cd ~/agent-gateway-relay && pytest -q
git add . && git commit -m "feat(relay): agent + device endpoints, WS drain, FCM fallback, systemd unit"
```
Operator deploy: `cp deploy/agent-relay.service ~/.config/systemd/user/ && systemctl --user enable --now agent-relay` (or system unit), after setting the tailnet `RELAY_HOST`.

---

### Task 4: Android RelayClient (persistent WebSocket drain)

**Files:** `app/.../agent/relay/RelayClient.kt`, `app/.../agent/relay/RelayPairing.kt`; modify `app/.../agent/service/AgentGatewayService.kt`; instrumented `app/src/androidTest/.../agent/RelayClientTest.kt`

**Interfaces:**
- Produces: `RelayClient(relayUrl, deviceToken, gateway, scope)` with `fun connect()`, `fun disconnect()`, and an internal drain loop: on each received `intent` message → `gateway.dispatcher.dispatch(token, intent, sourceIp, now)` → `POST /relay/result`. `RelayPairing.pair(relayBaseUrl): Boolean` (uploads `device_id`, `keyStore.rootPublicHex()`, a generated `deviceToken`, and the FCM token).

- [ ] **Step 1: RelayClient (OkHttp WebSocket — OkHttp is already a dep)**

`app/.../agent/relay/RelayClient.kt`:
```kotlin
package com.wyltek.wallet.agent.relay

import com.wyltek.wallet.agent.AgentGateway
import com.wyltek.wallet.agent.DispatchResult
import com.wyltek.wallet.core.native.Intent
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONObject

class RelayClient(
    private val wsUrl: String,          // wss/ws://relay/relay/device
    private val resultUrl: String,      // http(s)://relay/relay/result
    private val deviceToken: String,
    private val gateway: AgentGateway,
    private val scope: CoroutineScope,
) {
    private val http = OkHttpClient.Builder().pingInterval(30, java.util.concurrent.TimeUnit.SECONDS).build()
    private var ws: WebSocket? = null

    fun connect() {
        val req = Request.Builder().url(wsUrl).addHeader("x-device-token", deviceToken).build()
        ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val msg = JSONObject(text)
                if (msg.optString("type") != "intent") return
                scope.launch(Dispatchers.IO) { handleIntent(msg) }
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { ws = null }
            override fun onFailure(webSocket: WebSocket, t: Throwable, r: Response?) {
                ws = null; scope.launch { delay(5000); connect() }   // reconnect with backoff
            }
        })
        // heartbeat to keep the server loop alive
        scope.launch { while (isActive) { delay(25000); ws?.send("ping") } }
    }

    private suspend fun handleIntent(msg: JSONObject) {
        val intent = Intent(
            op = msg.getString("op"), asset = msg.getString("asset"), to = msg.optString("to"),
            amount = msg.optLong("amount"), nonce = msg.getString("nonce"),
            action = msg.optString("action").ifEmpty { null }, daoRef = msg.optString("dao_ref").ifEmpty { null })
        val sourceIp = msg.optString("source_ip", "0.0.0.0")
        val r = gateway.dispatcher.dispatch(msg.getString("token"), intent, sourceIp, System.currentTimeMillis() / 1000)
        val (status, tx, err) = when (r) {
            is DispatchResult.Sent -> Triple("sent", r.txHash, null)
            is DispatchResult.Approval -> Triple("needs_approval", null, null)  // user must approve on-device; agent polls
            is DispatchResult.Denied -> Triple("denied", null, r.reason)
            is DispatchResult.Failed -> Triple("failed", null, r.message)
        }
        postResult(msg.getString("intent_id"), status, tx, err)
    }

    private fun postResult(intentId: String, status: String, tx: String?, err: String?) {
        val body = JSONObject().put("device_token", deviceToken).put("intent_id", intentId)
            .put("status", status).put("tx_hash", tx).put("error", err).toString()
        http.newCall(Request.Builder().url(resultUrl)
            .post(RequestBody.create("application/json".toMediaType(), body)).build()).execute().close()
    }

    fun disconnect() { ws?.close(1000, "bye"); ws = null }
}
```
> Note: a `NeedApproval` over the relay path posts `needs_approval`; the agent then polls `GET /relay/intent/{id}` which stays `needs_approval` until the user approves on-device (Plan B2 approval flow), at which point B2's `executeApproved` runs — extend B2's approval completion to also `POST /relay/result` for relay-originated intents (track origin on the `PendingIntentEntity`, e.g. a `relayIntentId` column). Flag this cross-plan wire-up.

- [ ] **Step 2: Pairing**

`RelayPairing.pair(relayBaseUrl)`: generate a random `deviceToken` (32 bytes, persisted in `AgentSecureStore`), a stable `device_id` (persisted), read `gateway.keyStore.rootPublicHex()` and the FCM token (if available, Task 6), `POST /pair`. Persist `relayBaseUrl` + `deviceToken` + `device_id` in `AgentSecureStore`.

- [ ] **Step 3: Service ownership**

In `AgentGatewayService` (B2): when started and a relay is paired, construct + `connect()` the `RelayClient` using the service's `CoroutineScope`; `disconnect()` on stop. The persistent connection lives for the service lifetime (the same foreground service hosting the Direct Ktor server).

- [ ] **Step 4: Test + commit**

`RelayClientTest` (instrumented): stand up a local `MockWebServer` (OkHttp) speaking the WS protocol; push an `intent` frame; assert `dispatch` is invoked (inject a fake gateway dispatcher) and a result is POSTed. `./gradlew :app:compileDebugKotlin`; connected test if device.
```bash
cd ~/wyltek-wallet
git add app/src/main/java/com/wyltek/wallet/agent/relay app/src/main/java/com/wyltek/wallet/agent/service app/src/androidTest
git commit -m "feat(agent): relay WebSocket client + pairing in the foreground service"
```

---

### Task 5: Relay pairing UI

**Files:** modify `app/.../ui/screens/AgentScreen.kt`, `app/.../agent/ui/AgentViewModel.kt`

- [ ] **Step 1: ViewModel**

Add `relayPaired: Boolean`, `relayUrl: String?` to `AgentUiState`; actions `pairRelay(baseUrl: String)` (calls `RelayPairing.pair`, updates state) and `unpairRelay()`. `refresh()` reads paired state from `AgentSecureStore`.

- [ ] **Step 2: UI**

In `AgentScreen`, add a "Relay" card: a relay base-URL `OutlinedTextField` (default `http://<wyltek-10700-tailnet>:9991`), a "Pair" `Button` (calls `pairRelay`), and once paired show the relay URL + connection status + an "Unpair" button. Mirror the existing card/field/button styling.

- [ ] **Step 3: Verify + commit**

`./gradlew :app:compileDebugKotlin`.
```bash
git add app/src/main/java/com/wyltek/wallet/ui/screens/AgentScreen.kt app/src/main/java/com/wyltek/wallet/agent/ui/AgentViewModel.kt
git commit -m "feat(agent): relay pairing UI"
```

---

### Task 6: FCM fallback wake (operator Firebase prerequisite)

**Files:** root `build.gradle.kts`, `app/build.gradle.kts`, `app/google-services.json` (operator), `app/src/main/AndroidManifest.xml`, `app/.../agent/fcm/AgentFirebaseService.kt`, `app/.../agent/relay/RelayPairing.kt` (FCM token)

**Interfaces:**
- Produces: `AgentFirebaseService : FirebaseMessagingService` that on a `{"type":"agent_wake"}` data message starts/nudges `AgentGatewayService` to connect-and-drain, and on `onNewToken` re-pairs (uploads the new FCM token).

- [ ] **Step 1: Operator prerequisite (document, do not fake)**

Create a Firebase project, add the Android app (package `com.wyltek.wallet`), download `google-services.json` into `app/`, and download an Admin SDK service-account JSON onto wyltek-10700 at the path in `RELAY_FCM_CREDS`. If these aren't available, the persistent-connection path (Tasks 1–5) is fully functional; STOP here and report FCM as pending operator setup.

- [ ] **Step 2: Gradle + manifest**

Root `build.gradle.kts` plugins: `id("com.google.gms.google-services") version "4.4.2" apply false`. `app/build.gradle.kts`: apply `id("com.google.gms.google-services")` and add `implementation(platform("com.google.firebase:firebase-bom:33.7.0"))` + `implementation("com.google.firebase:firebase-messaging")`. Manifest `<application>`:
```xml
<service android:name=".agent.fcm.AgentFirebaseService" android:exported="false">
    <intent-filter><action android:name="com.google.firebase.MESSAGING_EVENT" /></intent-filter>
</service>
```

- [ ] **Step 3: The service**

`app/.../agent/fcm/AgentFirebaseService.kt`:
```kotlin
package com.wyltek.wallet.agent.fcm

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.wyltek.wallet.agent.service.AgentGatewayService

class AgentFirebaseService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        if (message.data["type"] == "agent_wake") {
            AgentGatewayService.start(this)   // start (if needed) → RelayClient connects → drains queued intents
        }
    }
    override fun onNewToken(token: String) {
        // re-pair to upload the refreshed FCM token (RelayPairing reads the stored relay URL + device token)
        AgentGatewayService.start(this)       // service re-pairs with the new token on start if a relay is configured
    }
}
```
Update `RelayPairing` to fetch the current FCM token (`FirebaseMessaging.getInstance().token`) and include it in `POST /pair`.

- [ ] **Step 4: Verify + commit**

`./gradlew :app:assembleDebug` (needs `google-services.json`; if absent, operator step). End-to-end wake test is operator-run.
```bash
git add app/build.gradle.kts build.gradle.kts app/src/main/AndroidManifest.xml app/src/main/java/com/wyltek/wallet/agent/fcm app/src/main/java/com/wyltek/wallet/agent/relay/RelayPairing.kt
git commit -m "feat(agent): FCM fallback wake"
```

---

## Self-Review

**1. Spec coverage** (against `2026-06-19-agent-gateway-design.md` Part 6):
- Keyless relay (public-key only), pre-validation, revocation list, advisory cap pre-accounting, source-IP capture, queue → Tasks 1–3.
- Wake: persistent tailnet connection (primary, Task 4) + FCM fallback (Task 6) — both, per the user's choice.
- Device re-validates authoritatively via the same `dispatch()` → Task 4. Relay holds no keys.
- Idempotency via `intent_id = sha256(token_id‖nonce)` (relay) + the device ledger unique `(tokenId,nonce)` (B1).

**2. Placeholder scan:** No "TBD". Named verify-against-source items: `biscuit-python` 0.3 API + RFC3339 time term; the `fcm_token` column added to `devices` (Task 2 schema or a Task 3 migration); OkHttp `MediaType`/`RequestBody` deprecation in 4.12 (`"...".toMediaType()` / `RequestBody.create`) — adjust to the installed OkHttp idiom; the cross-plan wire-up for relay-originated `NeedApproval` results (B2 `executeApproved` must `POST /relay/result` — add a `relayIntentId` column to `PendingIntentEntity`). Each names exactly what to do.

**3. Type consistency:** relay `intent_id` derivation identical on enqueue + dedup; `device_token` always stored hashed, compared hashed; the WS `intent` payload shape matches what `RelayClient.handleIntent` parses; `RelayClient` consumes `gateway.dispatcher.dispatch` + `DispatchResult` exactly as B1/B2 define them; `Intent` is the B1 binding (with `action`/`daoRef`).

**Open items (flagged):**
- **Relay-originated NeedApproval** needs the device, after on-device biometric approval (B2), to POST the result back to the relay — requires a `relayIntentId` on `PendingIntentEntity` and a small addition to B2's `executeApproved`. Cross-plan; tracked here.
- **biscuit-python availability/version** — if `biscuit-python` 0.3 can't be installed on the box's Python, fall back to a thin Rust pre-check sidecar exposing the same `precheck` over a local socket (the relay would call it). Note as a contingency.
- **FCM is operator-gated** (Firebase project + two JSON files); persistent path stands alone without it.
- **Relay advisory cap ledger** is intentionally non-authoritative; never let it gate spend — only the device ledger does. (Stated in Global Constraints.)
- **Carry-forward (whole program, now complete as a plan set):** biscuit attenuation UI, decide-time revocation already enforced device-side (registry `revoked`) + now relay-side, multi-asset cap regression test, CEMP inbox/receive path. With Plans A/B1/B-CEMP/B2/C the design is fully planned end-to-end.
