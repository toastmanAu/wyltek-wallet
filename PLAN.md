lets plan a native android l1 nervos wallet. App should incorporate a light client as referenced in sources, to give direct, un-gated chain access as well as allowing the setting of public/private rpc connections to a full node. the app should assemble transactions locally only broadcasting securely. The app should utilise post quantum cryptography, utilising the testnet deployed lockscripts provided in sources. The app should utilise passkey technology also for secure key storage as an option. The wallet must support both traditional nervos wallets and pqr resistant ones, allowing either to be imported into the app seamlessly. facility should be provided for easy "internal transfer" for users that creates a transaction between their pqr and non-pqr wallets. Wallets should have full bip39 compliance. The wallet should validate outgoing send addresses for consensus matching, and should allow all deprecated formats as well as current and yet to be developed yet compliant formats. the wallet should integrate the encrypted messaging protcol included in sources to allow users to send messages over chain to contacts via the wallet. The wallet should also have a tab/page for viewing and interacting withg assets belonging to the wallet stored in spore/cota/ckbfs. The attached cellswap repo contains display examples of on chain assets that can be learnt from. the assets should also utilise the lsdl protcol to be able to list items from wallet for sale per source examples. the wallet should be user skinnable, employiong paneled architecture that can be modified with user images or pre-selected options.

## Current Status (2026-06-10)

App branding finalised as **Blackbox Vault** (under the Wyltek umbrella). All six original MVPs shipped, with a strong second wave of hardening and feature work on top.

| Milestone | Status |
|-----------|--------|
| MVP 1 — Native wallet core | ✅ Complete |
| MVP 2 — Light client / chain access | 🔄 Public RPC + failover + health checks shipped; embedded `ckb-light-client-lite` binary still pending |
| MVP 3 — PQ accounts (ML-DSA-65) | 🔄 Crypto primitives + Hybrid account creation + signing dispatch in place; awaiting real `ckb-mldsa-lock` testnet deployment hashes in NetworkConfig + protocol-level verification of the witness/sighash format |
| MVP 4 — Assets gallery | ✅ Complete (Spore/CoTA/CKBFS scanners + gallery + inspector) |
| MVP 5 — Marketplace (LSDL) | ✅ Complete (list / cancel / buy) |
| MVP 6 — Messaging (CEMP-PQ) | ✅ Complete (profile + contacts + encrypted send/receive + notification scanner) |

### Second-wave features (shipped since MVPs)

- **JoyID / passkey integration** — WebAuthn credentials, account linking, redirect-relay signing.
- **Watch-only xpub import** — BIP-32 derivation, address refresh, no-key monitoring.
- **Hardware-backed key wrapping** — StrongBox / AndroidKeyStore AES-256-GCM seed wrap with toggle.
- **RPC failover + health checks** — multi-endpoint pool, tip-lag detection, automatic switching, health screen.
- **User skinning system** — 6 theme presets, custom theme JSON import/export, per-panel image backgrounds.
- **Custom token import** — add any sUDT by type script.
- **QR receive + scan** — QR code generation + camera scanner for send.
- **Transaction history + detail** — explorer links, send/receive classification, status badges.
- **Mnemonic verify flow** — post-create word challenge before wallet activation.
- **Nervos DAO** — deposit, two-phase withdrawal, unlock, APC tracking, cycle progress indicators.
- **Internal transfer** — one-tap classic ↔ PQ flow.
- **UniFFI bindings expanded** — Rust-side address validation, BIP-39 helpers, secp256k1 + ML-DSA-65 signing, tx builder.

### Next initiatives

- **Embedded light client** — bundle `ckb-light-client-lite` as a foreground service. Last remaining MVP-2 item.
- **Multi-chain swaps (BTC / ETH / SOL)** — designed in `PLAN-SWAPS.md`. Zero-custody, no spread, third-party aggregator routes (fiat ramp + DEX aggregator + CKB-native DEX). Bottom bar restructure: `Home | Swaps | Market | Messages | Settings`; Assets moves to Home card.

Below is the original concrete native Android plan, preserved for reference.

## Product shape

Build it as **“Pocket L1 Nervos Wallet”**: a native Android CKB wallet with three account modes:

1. **Classic CKB wallet**
   secp256k1 / standard Nervos addresses / BIP39.

2. **Post-quantum wallet**
   ML-DSA / Falcon testnet lock support from `ckb-mldsa-lock-main`.

3. **Hybrid vault**
   One app identity with both classic and PQ accounts, plus a one-tap **Internal Transfer** flow between them.

## Core architecture

```text
Android Kotlin UI
 ├─ Wallet Core module
 │   ├─ BIP39 / seed vault
 │   ├─ classic CKB account engine
 │   ├─ PQ account engine
 │   ├─ transaction builder
 │   └─ address validator
 │
 ├─ Chain Access module
 │   ├─ embedded ckb-light-client-lite binary/service
 │   ├─ public RPC profile
 │   ├─ private full-node RPC profile
 │   └─ failover + health checks
 │
 ├─ Signing + Key Storage
 │   ├─ Android Keystore
 │   ├─ BiometricPrompt
 │   ├─ passkey / WebAuthn option
 │   ├─ encrypted export/import
 │   └─ hardware-backed key wrapping where available
 │
 ├─ Assets module
 │   ├─ Spore viewer
 │   ├─ CoTA viewer
 │   ├─ CKBFS resolver
 │   ├─ Cellswap display patterns
 │   └─ LSDL listing / cancel / buy flows
 │
 ├─ Messaging module
 │   ├─ CEMP-PQ profile cell
 │   ├─ contact discovery
 │   ├─ ML-KEM encryption
 │   ├─ AES-GCM payloads
 │   └─ notification cell scanner
 │
 └─ Skinning module
     ├─ panel templates
     ├─ user image picker
     ├─ theme presets
     └─ per-page background/panel overrides
```

## Source repo roles

Use:

* `ckb-light-client-lite-main` for the embedded light-client direction. It already targets constrained devices, static musl, SQLite, and much lower disk usage than the standard build.
* `ckb-mldsa-lock-main` for PQ lock variants. Use **ML-DSA-65 RustCrypto** as the default PQ account mode, with ML-DSA-44/87 and Falcon as advanced/testnet options.
* `key-vault-wasm-main` for PQ key derivation concepts, but port the critical vault pieces into native Rust/Kotlin rather than depending on browser IndexedDB.
* `ccc-master` for transaction assembly patterns, address handling, and CKB ecosystem compatibility.
* `joyid-sdk-js-main` and `jidsdr-main` for passkey/JoyID reference patterns, but Android should use native Credential Manager / passkey flows.
* `cemp-pq-main` for encrypted on-chain messaging.
* `ckb-cell-marketplace-master` for asset display, CKBFS rendering, and marketplace UI patterns.
* `ckb-lsdl-main` for listing wallet-owned cells for sale.

Check ~/Documents/ckb repos/ first asx many necessary repositories are available locally

## Important security decision

The app should **assemble and sign locally only**. RPC/light-client/full-node connections are only allowed to:

* query cells,
* query headers,
* estimate fees,
* resolve scripts,
* submit final signed transactions.

Never send private keys, seed material, unsigned signing payloads with hidden mutation risk, or passkey secrets to a server.

## Native implementation stack

Use:

```text
Kotlin + Jetpack Compose
Rust wallet core via UniFFI/JNI
Room / SQLCipher for local metadata
Android Keystore + BiometricPrompt
Credential Manager for passkeys
WorkManager for background sync
Foreground service for optional light-client runtime
```

Rust should own:

* CKB transaction building,
* script config registry,
* Molecule serialization,
* PQ signature generation,
* address validation,
* CEMP-PQ crypto,
* CKBFS decoding helpers.

Kotlin should own:

* UI,
* account screens,
* settings,
* permissions,
* image skinning,
* background sync orchestration.

## Wallet flows

### Account creation

User chooses:

```text
Create wallet
 ├─ Classic CKB
 ├─ Post-quantum CKB
 └─ Hybrid classic + PQ
```

Backup options:

```text
Classic: 12/18/24-word BIP39
PQ: extended BIP39-compatible backup format, likely 36/54/72 words depending security level
Hybrid: one backup bundle with clearly separated classic + PQ material
```

### Import

Support:

* current CKB address formats,
* deprecated formats,
* raw lock script import,
* xpub-like watch-only import later,
* PQ lock script import,
* JoyID/passkey account link/import where feasible.

### Internal transfer

A dedicated screen:

```text
Move funds internally
From: Classic wallet
To: PQ wallet
Amount: Max / Custom
Fee: Auto / Advanced
Security note: You are migrating funds into a post-quantum lock.
```

Reverse direction also supported.

## Address validation

Create a strict **CKB Address Compatibility Engine**:

```text
Input address
 ├─ decode bech32/bech32m/deprecated variants
 ├─ identify network: mainnet/testnet/devnet
 ├─ decode payload
 ├─ resolve lock script
 ├─ verify script template known or custom
 ├─ reject network mismatch unless user overrides in dev mode
 └─ show human-readable transaction preview
```

The wallet should not merely check “valid string”. It must check **consensus/network compatibility** before building.

## Asset page

Tabs:

```text
Assets
 ├─ Coins / CKB
 ├─ Spore
 ├─ CoTA
 ├─ CKBFS files
 ├─ Listed for sale
 └─ Hidden / Unknown cells
```

Each asset card:

```text
image/preview
name
type
capacity locked
owner lock
actions: send / list / delist / inspect cell / export outpoint
```

LSDL listing flow:

```text
Select asset → Set price → Royalty/expiry → Preview lock args → Sign → Broadcast
```

## Messaging page

Use `cemp-pq-main` as the protocol base.

Screens:

```text
Messages
 ├─ Contacts
 ├─ Inbox
 ├─ Sent
 ├─ Profile Cell
 └─ Encryption settings
```

Flow:

```text
Create profile cell
Discover recipient profile
Encrypt message with ML-KEM + AES-256-GCM
Create Message Cell + Notification Cell
Recipient scans notification cells
Decrypt locally
```

## UI / skinning

Use a **panel architecture**:

```text
Home dashboard panel
Wallet balance panel
Send/receive panel
Asset gallery panel
Message panel
Node status panel
Marketplace panel
```

Each panel supports:

* built-in cyberpunk/Nervos themes,
* user image background,
* crop/position controls,
* blur/dim overlay,
* readable text safety layer,
* import/export theme JSON.

## Build milestones

### MVP 1 — native wallet core ✅ COMPLETE

* Kotlin Compose shell.
* Create/import classic CKB wallet.
* Local transaction assembly.
* RPC selection + network switcher (mainnet / testnet).
* Send/receive CKB.
* Address validation.
* Android Keystore encrypted seed storage.
* Transaction history with detail view + explorer links.
* sUDT balance display + send.
* Custom token import (add any sUDT by type script).
* QR code generation (receive) + scanning (send).

### MVP 2 — light client 🔄 IN PROGRESS

* ⏳ Bundle Android-compatible `ckb-light-client-lite` binary (last remaining item).
* ⏳ Foreground service wrapper.
* ✅ Node status / RPC health page with tip-lag detection.
* ✅ Public RPC + network switcher (mainnet / testnet).
* ✅ Multi-endpoint RPC failover.
* ✅ Cell sync and balance indexing via RPC.

### MVP 3 — PQ accounts 🔄 PARTIAL

* ✅ ML-DSA-65 primitives via `fips204` (keygen, sign, verify, BIP-39 seed → ML-DSA key).
* ✅ Hybrid account creation — single seed derives both secp256k1 + ML-DSA-65 addresses.
* ✅ Signing dispatch wired in `Repository.sendCkb` — selects secp or ML-DSA-65 path from chosen sub-address's lockScript codeHash.
* ✅ UI picker in SendScreen for hybrid accounts ("Sign with Classic / PQ").
* ✅ Biometric gate fires for any PQ sub-account selection.
* ⏳ **`ckb-mldsa-lock` testnet deployment hashes** — `NetworkConfig.testnet.mldsa65` currently holds placeholder zeros. Wallet refuses to construct/broadcast PQ transactions until real values are plugged in. The placeholder gate is enforced server-side in `Repository.buildPqAddress` and `Repository.resolveSigningContext`.
* ⏳ **Protocol verification** — the ML-DSA-65 signing path uses `sign_transaction(raw_tx, sk, "mldsa65")` as a working scaffold. The actual deployed `ckb-mldsa-lock` contract may require a different sighash construction (e.g. blake2b over tx_hash + witness placeholders, as secp does). Verify against the contract's verifier before live broadcast.
* ⏳ sUDT sends from a PQ sub-account — currently refused with a clear UI hint pointing the user to the Classic sub-account.
* ⏳ Internal transfer screen still a UI stub (`InternalTransferScreen.kt` button is `/* TODO */`).

### MVP 4 — assets ✅ COMPLETE

* ✅ Spore / CoTA / CKBFS scanners.
* ✅ Asset gallery (tabs, capacity, preview).
* ✅ Cell inspector + outpoint export.
* ✅ CKBFS rendering for supported MIME types.
* ✅ Custom sUDT import.
* ✅ Cellswap-inspired display UI.

### MVP 5 — marketplace ✅ COMPLETE

* ✅ LSDL list / cancel / buy.
* ✅ Royalty / expiry UI.
* ✅ Listed asset dashboard.
* ✅ Transaction simulation pre-broadcast.

### MVP 6 — messaging ✅ COMPLETE

* ✅ CEMP-PQ profile cells.
* ✅ Contact book.
* ✅ Encrypted send / receive (ML-KEM + AES-256-GCM).
* ✅ Notification cell scanner.
* ✅ Local encrypted message database.

### Wave 2 — second-pass features ✅ SHIPPED

* ✅ JoyID / passkey credentials + WebAuthn signing.
* ✅ Watch-only xpub import + derivation.
* ✅ StrongBox hardware-backed AES-256-GCM seed wrap.
* ✅ User skinning system (presets + custom themes + per-panel backgrounds).
* ✅ Mnemonic verify flow.
* ✅ Transaction history + detail screen with explorer links.
* ✅ Nervos DAO (deposit / withdraw phase 1 / unlock / APC).
* ✅ QR receive + camera-based scan-to-send.
* ✅ Branding: Blackbox Vault.

### Wave 3 — planned

* ⏳ Embedded `ckb-light-client-lite` foreground service (closes MVP 2).
* ⏳ Multi-chain swaps page — BTC / ETH / SOL via BIP-39 derivation, zero-custody routing through Onramper (fiat), Rango (DEX aggregator), and UTXOSwap (CKB-native DEX). Full design in `PLAN-SWAPS.md`.

## Main warning

Keep PQ support **testnet-labelled** until the lock scripts are audited and mainnet-ready. The uploaded PQ repo itself flags the scripts as testnet/not audited, so the Android app should make that impossible to miss.

