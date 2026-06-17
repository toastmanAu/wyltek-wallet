lets plan a native android l1 nervos wallet. App should incorporate a light client as referenced in sources, to give direct, un-gated chain access as well as allowing the setting of public/private rpc connections to a full node. the app should assemble transactions locally only broadcasting securely. The app should utilise post quantum cryptography, utilising the testnet deployed lockscripts provided in sources. The app should utilise passkey technology also for secure key storage as an option. The wallet must support both traditional nervos wallets and pqr resistant ones, allowing either to be imported into the app seamlessly. facility should be provided for easy "internal transfer" for users that creates a transaction between their pqr and non-pqr wallets. Wallets should have full bip39 compliance. The wallet should validate outgoing send addresses for consensus matching, and should allow all deprecated formats as well as current and yet to be developed yet compliant formats. the wallet should integrate the encrypted messaging protcol included in sources to allow users to send messages over chain to contacts via the wallet. The wallet should also have a tab/page for viewing and interacting withg assets belonging to the wallet stored in spore/cota/ckbfs. The attached cellswap repo contains display examples of on chain assets that can be learnt from. the assets should also utilise the lsdl protcol to be able to list items from wallet for sale per source examples. the wallet should be user skinnable, employiong paneled architecture that can be modified with user images or pre-selected options.

## Current Status (2026-06-10)

App branding finalised as **Blackbox Vault** (under the Wyltek umbrella). All six original MVPs shipped, with a strong second wave of hardening and feature work on top.

| Milestone | Status |
|-----------|--------|
| MVP 1 — Native wallet core | ✅ Complete |
| MVP 2 — Light client / chain access | 🔄 Public RPC + failover + health checks shipped; embedded `ckb-light-client-lite` binary still pending |
| MVP 3 — PQ accounts (ML-DSA-65) | 🔄 Hybrid creation + signing dispatch + real testnet deployment all wired (mldsa65-lock-v2-rust); internal-transfer screen wired. Pending: sUDT-from-PQ; device test of internal transfer |
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
* ✅ **Real `ckb-mldsa-lock` testnet deployment wired** — `mldsa65-lock-v2-rust` at code_hash `0xd70653f7…78a4` (Script hash, hash_type `type`), cell_dep = session-10 deploy tx `0x1074b1ac…70cb1` @ index 3. **NOTE:** the app previously pointed at the legacy C lock `0x8984f4…d310d` (deprecated — sighash gap, lost owner) which the bundled JS SDK also targets; corrected 2026-06-14.
* ✅ **Protocol-correct signing (v2-rust)** — `sign_ckb_mldsa65` produces `WitnessArgs(lock = [flag(0x7b) | pubkey | sig])` (flat, not a molecule table). Signing digest = `blake2b("ckb-mldsa-msg", generate_ckb_tx_message_all stream)`; ML-DSA context = `CKB-MLDSA-LOCK`. The signer reconstructs the CighashAll stream over all input cells (via `MldsaInputCell`). Matches `contracts/mldsa-lock-v2-rust/src/{entry,helpers,streamer}.rs`.
* ✅ **Lock-args format** — `mldsa65_lock_args_v2` produces the 37-byte layout `[0x80, 0x01, 0x01, 0x01, flag(0x7a), blake2b256_personal("ckb-mldsa-sct", pubkey)]`.
* ✅ **Molecule serializer fixed** — the prior `molecule.rs` wrote `item_count` where `full_size` belongs and encoded dynvecs as fixvecs, producing a non-canonical `tx_hash`. Rewritten per spec and validated against a real on-chain `tx_hash`. (This bug meant NO send — secp or PQ — could ever have verified on-chain.)
* ✅ **Fee estimate** — PQ sends reserve 10k shannons (vs 1k for secp) to cover the ~5.3 KB witness.
* ✅ **Live testnet verification** — confirmed on-chain 2026-06-14 via `rust-core/examples/pq_testnet.rs` (a harness reusing the app's exact Rust). Spend tx `0x51ccf4cfed7b003a3d1538a5913cf15bbd11053c04960f3b7beba5c86cb3601e` committed; PQ change returned to the lock. App layer (NetworkConfig + WalletRepository + regenerated UniFFI bindings + rebuilt arm64 `.so`) compiles.
* ✅ **Classic secp send fixed + verified on-chain (2026-06-15, tx `0x74b8352c…`)** — was broken four ways: non-canonical molecule (shared fix), un-personalized sighash blake2b, bare-65-byte witness instead of WitnessArgs-wrapped, and a **wrong testnet secp dep-group tx hash** (`…f640995` doesn't exist on Pudge; correct = `0xf8de3bb47d055cdf…aee5d37`). New `sign_ckb_secp256k1_witness` does the canonical ckb-default-hash sighash + WitnessArgs wrapping; wired into `sendCkb`.
* ✅ **DAO + sUDT secp paths migrated + verified (2026-06-15)** — `depositDao`, `withdrawDaoPhase1`, and `sendToken` now use the verified `sign_ckb_secp256k1_witness`. Verified on-chain: DAO deposit `0x4274e062…` and DAO withdraw phase-1 `0xd7d8a09e…`. Fixes uncovered along the way: a **wrong testnet DAO cell-dep** (`0x8e4966b8…`→ genesis `0x8f8c79eb…@2`); **`build_transaction` couldn't carry `header_deps`** (added `TransactionRequest.header_deps` so the signed `tx_hash` includes DAO headers — phase-1 was unsignable without it); and the **withdrawing-cell data must be the deposit block number as u64 little-endian, 8 bytes** (was big-endian/unpadded).
* 🔄 **DAO unlock (phase 2)** — signing primitive on-chain-verified (2026-06-15) **and app layer now fully wired (2026-06-16)**. Core proven via `sign_ckb_secp256k1_dao_witness` + harness `claim-dao`: the bespoke witness (`witnesses[0]` = secp lock sig + `input_type` = deposit-header index), both header_deps, header resolution, epoch math, and `since` encoding (deposit_epoch + 180) are all sound — a `since=0` probe ran the DAO type script to error **−17 (ERROR_INCORRECT_SINCE)** on `Inputs[0].Type` (not `.Lock`), and the correct-`since` tx is rejected only as `Immature`. App wiring (`WalletRepository.claimDao` + `WalletViewModel.unlockDao`): faithful port of the harness — the withdrawing cell's outpoint *is* the phase-1 withdraw tx, so a single `get_transaction` recovers the deposit outpoint/capacity/block; `since` re-derived in Kotlin and **verified bit-for-bit against the harness** (Rust vs JVM mirror). New RPC plumbing: `tx_status.block_hash`, `calculate_dao_maximum_withdraw`, `getTransactionDetail`. **DAO scanner field-labeling fixed** — withdrawing cells no longer swap deposit (D, from cell data) and withdraw (W, the cell's own block), so `unlockEpoch = depositEpoch + 180` and the `UNLOCKABLE` gating/display are correct. **Still cannot be fully broadcast-verified for ~180 epochs (~weeks)** — the post-`since` capacity check and wall-clock lock are unprovable until a real deposit matures.
* ✅ **sUDT `sendToken` fixed + verified on-chain (2026-06-16)** — **zero-fee bug**: CKB change was computed as `(totalInput − sudtOutputs)` with no fee withheld, so outputs summed to exactly the inputs → guaranteed pool rejection (`PoolRejectedTransactionByMinFeeRate`). `build_transaction` only *reports* `estimated_fee`; the caller must deduct it (as `sendCkb`/DAO do). Now reserves `sudtFeeEstimate` from the CKB change. Harness gains `mint-sudt` / `send-sudt` / `sudt-balance` / `secp-balance` (mirror the app's exact tx construction; mint authorizes output>input via owner-lock-as-input, type args = `ckbhash(owner lock)`). **Live verified:** mint 100k tokens `0xd30ff5ff…`, then transfer 30k via `sendToken` `0x0bc30c02…` (accepted — pool runs the sUDT type script, so conservation 100k = 30k + 70k and the secp lock both passed; 70k change confirmed at the owner lock).
* ✅ **Latent double-`0x` cell-dep `tx_hash` fixed (2026-06-16)** — `depositDao`, `withdrawDaoPhase1`, and `sendToken` emitted `0x0xf8de…` (the dep hash already carries `0x`). These app-side JSON builders were only ever verified through the Rust harness's own builder, so the malformed cell-dep was never exercised on-chain and would have been node-rejected. Corrected to the proven `dep.txHash` form (matches the working PQ/`sendCkb` path).
* ⏳ sUDT sends from a PQ sub-account — currently refused with a clear UI hint pointing the user to the Classic sub-account.
* ✅ **Internal transfer screen wired (2026-06-16)** — `InternalTransferScreen.kt` resolves the wallet's classic + PQ sub-accounts (`NetworkConfig.isPqLock`), offers a swap-direction toggle (Classic→PQ default), and reuses the send path via `viewModel.sendCkb(to.bech32m, amount, fromCkbAddress = from)` so the source sub-account drives the secp-vs-ML-DSA signing dispatch. PQ-source transfers fire the biometric gate. Compiles clean; awaiting device test + live testnet broadcast.

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

