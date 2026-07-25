# Design — Companion-direct agent server (Tier-1 phone endpoint)

**Date:** 2026-07-25
**Status:** Approved, ready for implementation plan
**Repo:** `wyltek-wallet` (Android/Kotlin)
**Cross-repo context:** this is **sub-project #2** of the BlackBox two-tier transport design (`blackbox-pos/docs/superpowers/specs/2026-07-25-blackbox-two-tier-transport-design.md`). It makes the phone reachable by the already-shipped **POS Tier-1 signing-transport client** (blackbox-pos `c3d6e36`) over a plain LAN — no Tailscale, no relay.

---

## 1. Problem

The POS Tier-1 client (sub-project #1) resolves the companion phone via mDNS (`_blackbox-agent._tcp`) and speaks the relay's async contract (`POST /relay/intent` → `{queued, intent_id}`, poll `GET /relay/intent/{intent_id}` → `{status, txHash, error}`) over `https://` with `setInsecure()`. Nothing serves that on the phone yet, so end-to-end Tier 1 (and #1's HW checkpoint) is blocked here.

The phone **already has** an Agent Gateway server — but it is not reachable the way Tier 1 needs:
- It binds only the **Tailscale** overlay (`Tailnet.bindAddress()`, `100.64/10`), not the LAN.
- It serves a **synchronous `/v1/intent`** contract (`200 sent`+txHash / `202 needs_approval`+pendingId / `403` / `502`; poll by numeric row-id), **not** the relay's async `/relay/*` shape the POS speaks.
- It serves **plain HTTP** on `8443` (no TLS), while the POS dials `https://`.
- It does **not** advertise mDNS.
- The provisioning QR mints only `{device_id, token}` — no `service_name`.

## 2. Non-goals (YAGNI)

- **No new signing/authz.** Reuse `AgentActionDispatcher` / `WalletRepository` / `AgentLedger` / StrongBox keys verbatim.
- **No confirmation axis.** Sale-confirmation + balance stay on the indexer (sub-project #1b). #2 is signing-path discovery only.
- **No POS change.** The POS #1 firmware is byte-identical; the phone conforms to *its* contract.
- **No relay change.** The relay repo and the phone's `RelayClient` WS path are untouched.
- **No removal of `/v1/*`.** Existing routes stay.
- **No Tailscale removal.** If a Tailnet address is present it stays reachable (a co-connector); Tier 1 just no longer *requires* it.

## 3. What already exists (reused, not rebuilt)

`AgentGateway` (facade, implements `AgentDispatchPort`) wires: biscuit verify (`AgentTokenService`), authz/caps/ledger (`AgentActionDispatcher` + `AgentLedger`), sign+broadcast (`WalletRepository`), approval queue (`PendingStore`, rows carry a `relayIntentId`), StrongBox keys, sqlcipher DB. `AgentServer.kt` (`agentModule`) is a Ktor CIO app; `AgentGatewayService` is a `START_NOT_STICKY` foreground service; `RelayClient` establishes the async dispatch→result pattern; `AgentServerTest` exercises the routes via an `AgentDispatchPort` fake.

## 4. Architecture

Four additive pieces over the existing server; the backend is untouched.

```
POS #1 (unchanged)                         wyltek-wallet — NEW in #2
 mDNS resolve _blackbox-agent._tcp ─────────► ① NsdManager advertise (service_name, port, device_id TXT)
 POST https://<ip>:8443/relay/intent ───────► ② /relay/* routes (async facade)  ┐ over EXISTING
 GET  .../relay/intent/{intent_id} ─────────►    dispatch() + PendingStore        │ dispatch/keys/
                                             ③ TLS self-signed (Ktor sslConnector)│ ledger/approval
                                             ④ bind active Wi-Fi addr + lifecycle  ┘
```

## 5. The `/relay/*` facade

The phone plays the relay's role locally, reusing the `RelayClient` dispatch→result mapping.

**`POST /relay/intent`** (body = existing `IntentRequest` DTO — `{token, op, asset, to, amount, nonce, action?, daoRef?}`):
1. `intent_id = hex(sha256(token ‖ 0x00 ‖ nonce))` — idempotent per `(token, nonce)`, opaque to the POS, no `token_id` extraction.
2. **Idempotency:** if a tracking row already exists for `intent_id`, respond `{queued, intent_id}` without re-dispatching (mirrors the relay's idempotent enqueue).
3. Otherwise launch `dispatch(token, intent, sourceIp, now)` on the service scope (async — do not block the response), mapping the result exactly as `RelayClient` does:
   - `Sent` → upsert a terminal tracking row (`relayIntentId = intent_id`, `status="sent"`, `txHash`).
   - `Denied` → terminal row `status="denied"`, `error=reason`.
   - `Failed` → terminal row `status="error"`, `error=message`.
   - `Approval` → `pendingStore.linkRelayIntent(pendingId, intent_id)`; the row stays `status="needs_approval"` until the user approves.
4. Respond **immediately**: `{ "status":"queued", "intent_id":"<hex>" }`.

**`GET /relay/intent/{intent_id}`**: `pendingStore.getByRelayIntentId(intent_id)` → `{ status, txHash, error }` (existing `IntentStatusResponse` shape). No row yet → `404` (the POS treats a non-2xx poll as transient and keeps polling to its 180 s backstop).

**Approval-completion local-row wiring (the one new backend behavior):** when the user approves (`AgentViewModel.approve` → execute → update the pending row), the outcome must be visible via `getByRelayIntentId`. Today `reportRelayResult` POSTs to the relay for relay-origin intents and *no-ops* for HTTP-origin ones (the `/v1` client polls the row by numeric id). Because a `/relay/*` intent's row is linked by `relayIntentId = intent_id`, the same approval-updated row is already found by `getByRelayIntentId` — so no change to approval execution or signing, only the lookup key.

**Status → POS mapping** (identical to what POS #1 already handles on the node tier): `sent`+`txHash 0x…`→SENT, `denied`→DENIED, `error`→ERROR, `needs_approval`/`queued`→keep polling.

**`PendingStore` additions:** `getByRelayIntentId(id: String)` and a terminal-row upsert keyed by `relayIntentId` (auto-`Sent` intents create no approval row today — the relay stored their result; now the phone must).

## 6. Discovery: mDNS + service_name + provisioning

- **`service_name`** minted at provisioning as `blackbox-<first 8 hex of device_id>` — a valid, per-device-unique mDNS instance label. Used as BOTH the QR field and the advertised instance name.
- **Provisioning QR** (`buildProvisioningBundle`): `{device_id, token}` → `{device_id, token, service_name}`. This third field is what POS #1's optional `service_name` parse consumes — minting it flips a provisioned POS into Tier 1. Pre-#2 provisioned devices stay 2-field → node tier, unchanged.
- **mDNS advertise** (`NsdManager`, new): register on foreground-service start (after bind) — `serviceType="_blackbox-agent._tcp"`, `serviceName=service_name`, `port=8443`, **TXT record `device_id`** (lets the POS match the exact instance if Android auto-renames on collision; POS first-responder fallback still works on a single-phone LAN). Unregister on stop.
- **Port `8443`** is carried in the mDNS SRV record; the POS builds `https://<ip>:<port>` from what it resolves — no hard-coded port POS-side.

## 7. TLS (self-signed)

Trust is the biscuit, not the cert (POS uses `setInsecure()`), but the phone must serve TLS because the POS dials `https://`.
- Generate a self-signed cert **once** via `ktor-network-tls-certificates` `buildKeyStore { certificate("agent") { … } }`; persist the keystore in app-private storage (or `AgentSecureStore`); load on start. CN/SAN irrelevant (no validation), so a fixed cert survives IP changes.
- Replace the plain CIO connector with `sslConnector(keyStore, "agent", …) { host = <lan addr>; port = 8443 }` in `AgentGatewayService`.

## 8. Bind + lifecycle

- **LAN address resolver** (new, sibling to `Tailnet.bindAddress()`): active Wi-Fi site-local IPv4 (`192.168/`, `10.`, `172.16–31`), skipping loopback/Tailnet/cellular. Bind the `sslConnector` there.
- **Coexistence:** if a Tailnet address is present, add a second connector on it (Ktor supports multiple connectors). `RelayClient` and `/v1/*` untouched.
- **mDNS lifecycle** tied to the foreground service: register after start, unregister in `handleStop`/`onDestroy` — advertising exists only while the merchant has the gateway ON (chosen exposure posture).
- **Wi-Fi change:** a `ConnectivityManager` network callback restarts the gateway (re-bind + re-advertise) when the Wi-Fi address changes. `START_NOT_STICKY` stays (merchant explicitly starts).

## 9. Error handling

- **No LAN address** → notify "no local network", don't start (mirrors the Tailnet-unavailable branch).
- **`onRegistrationFailed` (mDNS)** → log + notify; server still runs (POS surfaces "Phone not found on network"); retry on next Wi-Fi-change/restart.
- **TLS keystore gen/load failure** → fatal for the connector; log + notify + stopSelf (first-run-only risk once persisted).
- **Dispatch throw / Denied / Failed** → mapped into the tracking row (`error`/`denied`); POS reads it via the poll.
- **Wi-Fi drops mid-flight** → network callback restarts; the in-flight POS poll sees transient GET failures and keeps polling to its backstop.

## 10. Testing

- **Host-JVM (Ktor `testApplication` + `AgentDispatchPort` fake, extending `AgentServerTest`):** `POST /relay/intent` → `{queued, intent_id}`; idempotent `intent_id` per `(token, nonce)`; `GET /relay/intent/{intent_id}` reflects the fake's mapped result (`sent`+txHash / `denied` / `error` / `needs_approval`→keep-polling); unknown id → `404`.
- **`PendingStore.getByRelayIntentId` + terminal upsert:** unit test (in-memory/fake DB).
- **`buildProvisioningBundle` 3-field + `blackbox-<8hex>` derivation:** pure host test.
- **Android-framework-dependent (instrumented or manual):** `NsdManager` advertise, `sslConnector` handshake, LAN resolver, Wi-Fi-change restart.
- **End-to-end Tier-1 (physical POS ↔ phone on a LAN):** the HW checkpoint that **closes POS #1's deferred Tier-1 checkpoint** — capture in HARDWARE-NOTES.

## 11. Scope boundary

- **Reused verbatim:** biscuit verify, caps/ledger, signing/broadcast, StrongBox keys, Ktor engine, `AgentDispatchPort`, `PendingStore`, foreground service.
- **Untouched:** `/v1/*` routes, `RelayClient` WS path, POS #1 firmware, relay repo.
- **Not in #2:** confirmation axis (#1b); new signing semantics.

## 12. Success criteria

1. A POS provisioned via a 3-field QR resolves the phone by mDNS and completes a refund/sweep against `https://<phone>:8443/relay/intent` → poll → `SENT`, with **no relay and no Tailscale**.
2. An auto-approved intent reaches `SENT` on the POS poll; an approval-required intent shows `needs_approval` (POS keeps polling) then `SENT` after the merchant taps Approve — via the local tracking row, no relay.
3. A denied/failed intent surfaces `denied`/`error` on the POS poll.
4. Idempotent re-POST of the same `(token, nonce)` returns the same `intent_id` without double-dispatching.
5. Host-JVM tests green (routes, idempotency, `PendingStore`, provisioning mint); existing `/v1` tests still pass.
6. mDNS advertise/unadvertise follows the foreground-service lifecycle; a Wi-Fi change re-binds + re-advertises.
7. Pre-#2 provisioned devices (2-field QR) still work as node tier.

## 13. Files touched (approximate — the plan refines)

- `agent/server/AgentServer.kt` — add `/relay/intent` + `/relay/intent/{intent_id}` routes.
- `agent/server/AgentDtos.kt` — relay-shaped request/response as needed (reuse `IntentRequest`; a `{status, intent_id}` response).
- `agent/AgentGateway.kt` / `AgentDispatchPort.kt` — expose `getByRelayIntentId`; async-dispatch + tracking-row helper for the facade.
- `agent/PendingStore.kt` (+ DAO/entities) — `getByRelayIntentId`, terminal-row upsert.
- `agent/provisioning/AgentProvisioning.kt` — 3-field bundle + `service_name` derivation.
- `agent/service/AgentGatewayService.kt` — LAN bind, `sslConnector`, mDNS register/unregister, Wi-Fi-change callback.
- new: LAN-address resolver, `NsdManager` advertiser, TLS keystore helper.
- `agent/ui/AgentViewModel.kt` / approval path — ensure HTTP-origin approvals land on the local row (via existing `relayIntentId` link).
- `agent/server/AgentServerTest.kt` (+ new tests) — `/relay/*` routes, idempotency, `PendingStore`, provisioning mint.

## 14. Open questions (resolved to defaults; revisit in the plan)

- **Reuse `IntentResponse` vs a dedicated relay-response DTO** for `POST /relay/intent`'s `{status, intent_id}` — pick during planning; the POS only needs `status` + `intent_id`.
- **Keystore persistence location** (app-private file vs `AgentSecureStore`) — default to `AgentSecureStore` for consistency with existing secrets.
- **Exact POS-side mDNS instance/TXT matching** — finalized jointly with the POS Tier-1 HW checkpoint (POS #1 currently first-responder fallback); the `device_id` TXT is provided for exact matching.
