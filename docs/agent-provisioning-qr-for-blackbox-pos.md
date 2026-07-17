# Task: emit an agent-provisioning bundle QR (for BlackBox POS)

**For a fresh session agent working in `~/wyltek-wallet`.** Small, self-contained change. No POS repo access needed — the contract below is the whole interface.

## Why

The BlackBox POS (an ESP32 point-of-sale in `~/blackbox-pos`, a *separate* project) is now an Agent-Gateway **agent**: it holds a scoped biscuit token minted by this wallet and submits `send_ckb` intents (merchant refunds) to the keyless relay, which routes them here for cap-validation + signing. The POS gets provisioned by **scanning a QR from this wallet's Agent screen**.

The POS needs BOTH the biscuit **token** AND the paired device's **`device_id`** (so the relay knows which phone to route the intent to — the relay looks up the device's biscuit root pubkey by `device_id`). This wallet's Agent screen currently renders a QR of the **bare token only** (`AgentScreen.kt`, the "Minted Token" card: `QrCodeImage(content = token)`). The POS can't use that — it has no `device_id`, so its `/relay/intent` calls would fail with "unknown device."

**This task:** change that QR to encode a small JSON bundle carrying both.

## The exact contract the POS parses (do not deviate)

The QR payload must be this JSON, **compact — no spaces**:

```json
{"device_id":"<relay device id>","token":"<biscuit base64>"}
```

Hard constraints (the POS parser is a tiny hand-rolled `strstr` matcher on a memory-constrained MCU, NOT a real JSON parser):
- **Compact only.** The POS matches the literal substring `"device_id":"` and `"token":"` — a space after the colon (`"device_id": "…"`) breaks it. Kotlin `org.json.JSONObject(...).toString()` emits compact (no spaces) by default — use it, or build the string manually. Do **not** pretty-print.
- **String values, no escaped quotes.** Both values are quote-free in practice (a biscuit token is URL-safe base64 `[A-Za-z0-9_-=]`; `device_id` is a UUID). Don't wrap anything that could contain a `"`.
- **Order-independent** — the POS finds each key anywhere, so `{"token":"…","device_id":"…"}` is equally fine.
- The POS rejects a **truncated** payload (it requires each value to end on a closing `"`). So the whole bundle must fit in one scan — see the QR-density note below.

## Where the pieces live (verified in this repo)

- **The QR emission site:** `app/src/main/java/com/wyltek/wallet/ui/screens/AgentScreen.kt` — the `uiState.lastMintedToken?.let { token -> … }` "Minted Token" card, the line `QrCodeImage(content = token, …)`. Change `content = token` to `content = <the bundle string>`.
- **The token:** already in hand as `uiState.lastMintedToken` (a `String`) / `AgentTokenService.mint(...)` returns `MintedToken`.
- **The `device_id`:** `app/src/main/java/com/wyltek/wallet/agent/relay/RelayPairing.kt` — `loadOrGenerateDeviceId()` returns the stable UUID, stored under `KEY_RELAY_DEVICE_ID` in secure storage (`secure.loadBlob(KEY_RELAY_DEVICE_ID)`). Expose it to the Agent screen (e.g. add a `deviceId` field to the Agent UI state read from `RelayPairing`/secure storage, or a `RelayPairing.deviceId(secure)` accessor). The screen/ViewModel already has the secure-storage handle used for pairing.

## What to do

1. Read `device_id` from `RelayPairing`/secure storage and make it available where the "Minted Token" card renders.
2. Build the compact bundle: `JSONObject().put("device_id", deviceId).put("token", token).toString()` (or a manual compact string).
3. Set the `QrCodeImage(content = …)` to the bundle. Keep the human-readable `SelectionContainer { Text(token) }` below showing the raw token if you like (harmless), or show the bundle — the POS only reads the QR.
4. **Guard the not-paired case:** if `device_id` is missing/blank (the device hasn't paired to the relay yet — `RelayPairing` never ran), the bundle is useless. Show a warning ("Pair this device with the relay before provisioning a POS") and/or disable/hide the QR, rather than emitting a bundle with an empty `device_id`. Pairing is done from this same Agent screen's relay-pair flow (`RelayPairing` + `POST /pair`); it must have run first.

## QR density (worth a glance)

The bundle is ~750–1050 chars (biscuit token ~700 base64 chars, up to ~1 KB with `allow_to`/`allow_ip` caveats, plus ~40 chars of JSON + a UUID). That's a dense byte-mode QR. At the current `Modifier.size(220.dp)` it should still scan, but:
- Use the QR library's **lowest error-correction level (L)** for max data density if `QrCodeImage` exposes it.
- If a full-caveat token pushes past what scans reliably at 220dp, either enlarge the QR or (better) mint POS tokens with tighter caveats. Test with a real GM861S/phone scan.

## How to verify (end-to-end, needs the POS)

The POS side is already merged and build-clean on `~/blackbox-pos` master. To prove the bundle works:
1. In this wallet: pair the device to the relay (Agent screen), then mint a `send_ckb` token with a cap (and optionally an `allow_to` for the test customer address). The "Minted Token" card shows the new **bundle** QR.
2. On the POS (flashed): **Settings → Merchant Wallet → Provision (scan token)** → scan the QR. It should flip to **"Provisioned"** (and survive a power-cycle — it's NVS-persisted). If it says "Not a valid agent token — try again", the bundle is malformed (most likely: pretty-printed with spaces, or truncated because the QR was too dense to scan fully).
3. Then POS **Refund** → scan a customer `ckt1…` address → enter an amount under the token's auto-limit → **Send refund** → the intent routes here; the phone auto-signs (under auto-limit) or prompts for approval (above it) → POS shows **Sent** with the txHash.

The POS's own runbook (`~/blackbox-pos/docs/superpowers/WAVE9a-HW-CHECKPOINT.md`) has the full checkpoint script; you only own the wallet-side bundle-QR change here.

## Scope / non-goals

- This is the ONLY thing blocking the POS's Wave 9a hardware checkpoint.
- Do NOT change the relay, the biscuit mint/caps, the intent contract, or the pairing flow — all of those are working and proven on testnet. Only the QR *content* on the Agent screen changes (bare token → `{device_id, token}` bundle), plus surfacing `device_id` to that screen and the not-paired guard.
- Commit conventionally (`feat: …`), no attribution footer (per repo convention).
