# Design: agent-provisioning QR bundle (for BlackBox POS)

**Date:** 2026-07-17
**Status:** Approved, pending implementation
**Source:** `docs/agent-provisioning-qr-for-blackbox-pos.md` (handoff from prior session)

## Goal

The BlackBox POS (a separate ESP32 project) provisions itself as an Agent-Gateway
agent by scanning a QR from this wallet's Agent screen. The relay routes the POS's
`send_ckb` intents to the correct paired phone by looking up that phone's biscuit
root pubkey via its **`device_id`**. The current QR encodes the **bare biscuit
token only**, so the POS has no `device_id` and its `/relay/intent` calls fail with
"unknown device."

Change the "Minted Token" QR to encode a compact JSON bundle carrying **both** the
`device_id` and the `token`. When the device has not paired to the relay, warn and
suppress the QR rather than emit an unroutable bundle.

## Wire-format contract (fixed by the POS — do not deviate)

The QR payload is this JSON, **compact, no spaces**:

```json
{"device_id":"<relay device id>","token":"<biscuit base64>"}
```

The POS parser is a hand-rolled `strstr` matcher on a memory-constrained MCU, **not**
a JSON parser. Constraints that follow:

- **Compact only.** It literal-substring-matches `"device_id":"` and `"token":"`. A
  space after a colon breaks it. `org.json.JSONObject(...).toString()` emits compact
  by default — use it. Never pretty-print.
- **String values, no escaped quotes.** A biscuit token is URL-safe base64
  (`[A-Za-z0-9_-=]`); `device_id` is a UUID. Neither can contain `"`.
- **Order-independent** — the POS finds each key anywhere in the string.
- **No truncation** — each value must end on a closing `"`; the whole bundle must
  fit one scan.

## Changes (three isolated units)

### 1. `RelayPairing.kt` — expose `device_id` (read-only accessor)

Add a companion function alongside `loadConfig`:

```kotlin
/** Stored relay device id, or null if this device never paired. */
fun deviceId(secure: AgentSecureStore): String? =
    secure.loadBlob(KEY_RELAY_DEVICE_ID)?.let { String(it, Charsets.UTF_8) }?.ifBlank { null }
```

It **reads** the stored blob only. It must **not** call the private
`loadOrGenerateDeviceId()`, because generating an id here would create a `device_id`
with no matching relay-side pairing — defeating the not-paired guard. `KEY_RELAY_DEVICE_ID`
stays private (the accessor is the public surface).

### 2. Bundle builder — pure, testable

Add a small pure function in a new file
`app/src/main/java/com/wyltek/wallet/agent/provisioning/AgentProvisioning.kt`
that owns the POS contract:

```kotlin
/** Compact provisioning bundle for the POS, or null if unpaired (deviceId blank). */
fun buildProvisioningBundle(deviceId: String?, token: String): String? {
    val id = deviceId?.ifBlank { null } ?: return null
    return JSONObject().put("device_id", id).put("token", token).toString()
}
```

This keeps the byte-exact POS contract out of the `@Composable` (which needs an
instrumented harness to test) and into fast JVM unit tests.

### 3. `AgentViewModel` + `AgentScreen.kt` — surface deviceId, render bundle, guard

- **`AgentUiState`** gains `val deviceId: String? = null`.
- **`refresh()`** (already loads relay state from `gateway.secure`) also sets
  `deviceId = RelayPairing.deviceId(gateway.secure)`.
- **`AgentScreen.kt`**, the `uiState.lastMintedToken?.let { token -> … }` "Minted
  Token" card (~line 225):
  - Compute `val bundle = buildProvisioningBundle(uiState.deviceId, token)`.
  - If `bundle == null` (not paired): render a warning
    (`"Pair this device with the relay before provisioning a POS"`) and **omit**
    `QrCodeImage` entirely. Keep the selectable token text + Dismiss button.
  - If `bundle != null`: `QrCodeImage(content = bundle, modifier = Modifier.size(220.dp))`.

## Not changed

- `QrCodeImage` / `QrCode.kt` — ZXing's `QRCodeWriter` already defaults to
  error-correction level **L** (max density) with no hints. A ~1KB byte-mode payload
  sits around QR version ~28–30, well under the version-40 / 2953-byte EC-L ceiling,
  so `encode` will not throw.
- The relay, biscuit mint/caps, intent contract, and pairing flow — all proven on
  testnet, explicitly out of scope.

## Testing

Unit tests on `buildProvisioningBundle` (pure JVM, no Android harness):

- Compact output: contains literal substrings `"device_id":"` and `"token":"`,
  contains no `": "` (no space after colon).
- Values intact: a realistic ~700-char base64 token appears whole and un-truncated,
  closing `"` present.
- Guard: `null` deviceId → returns `null`; blank/whitespace deviceId → returns `null`.
- Well-formed: output re-parses via `JSONObject` back to the same two values.

End-to-end (real POS scan → Provisioned → refund round-trip) stays a manual checkpoint
per the handoff doc; not automatable here.

## Non-goals

- No change to the mint action (a token can still be minted before pairing; only the
  card's QR is gated).
- No relay / biscuit / intent / pairing changes.
- Commit conventionally (`feat: …`), no attribution footer (repo convention).
