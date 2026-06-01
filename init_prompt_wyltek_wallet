lets plan a native android l1 nervos wallet. App should incorporate a light client as referenced in sources, to give direct, un-gated chain access as well as allowing the setting of public/private rpc connections to a full node. the app should assemble transactions locally only broadcasting securely. The app should utilise post quantum cryptography, utilising the testnet deployed lockscripts provided in sources. The app should utilise passkey technology also for secure key storage as an option. The wallet must support both traditional nervos wallets and pqr resistant ones, allowing either to be imported into the app seamlessly. facility should be provided for easy "internal transfer" for users that creates a transaction between their pqr and non-pqr wallets. Wallets should have full bip39 compliance. The wallet should validate outgoing send addresses for consensus matching, and should allow all deprecated formats as well as current and yet to be developed yet compliant formats. the wallet should integrate the encrypted messaging protcol included in sources to allow users to send messages over chain to contacts via the wallet. The wallet should also have a tab/page for viewing and interacting withg assets belonging to the wallet stored in spore/cota/ckbfs. The attached cellswap repo contains display examples of on chain assets that can be learnt from. the assets should also utilise the lsdl protcol to be able to list items from wallet for sale per source examples. the wallet should be user skinnable, employiong paneled architecture that can be modified with user images or pre-selected options.

Below is the concrete native Android plan.

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

### MVP 1 — native wallet core

* Kotlin Compose shell.
* Create/import classic CKB wallet.
* Local transaction assembly.
* RPC selection.
* Send/receive CKB.
* Address validation.
* Android Keystore encrypted seed storage.

### MVP 2 — light client

* Bundle/test Android-compatible light-client.
* Local service wrapper.
* Node status page.
* Switch between light-client, public RPC, private RPC.
* Cell sync and balance indexing.

### MVP 3 — PQ accounts

* Integrate ML-DSA-65 testnet lock.
* Create/import PQ wallet.
* Sign PQ transactions locally.
* Internal transfer classic ↔ PQ.
* PQ transaction preview with script warnings.

### MVP 4 — assets

* Spore/CoTA/CKBFS scanners.
* Asset gallery.
* Cell inspector.
* CKBFS rendering.
* Cellswap-inspired display UI.

### MVP 5 — marketplace

* LSDL list/cancel/buy.
* Royalty/expiry UI.
* Listed asset dashboard.
* Transaction simulation checks.

### MVP 6 — messaging

* CEMP-PQ profile cells.
* Contact book.
* Encrypted send/receive.
* Notification scanner.
* Local encrypted message database.

## Main warning

Keep PQ support **testnet-labelled** until the lock scripts are audited and mainnet-ready. The uploaded PQ repo itself flags the scripts as testnet/not audited, so the Android app should make that impossible to miss.

