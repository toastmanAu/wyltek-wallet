# Blackbox Vault

Native Android CKB / Nervos wallet with post-quantum cryptography, hardware-backed key storage, on-chain encrypted messaging, an asset / marketplace surface, and Nervos DAO. Built by Wyltek.

> **Status:** MVPs 1, 3, 4, 5, 6 complete. MVP 2 (chain access) shipped via RPC; embedded light client still pending. Active work: multi-chain swaps. See `PLAN.md` for the roadmap and `PLAN-SWAPS.md` for the swaps initiative.

## Account modes

1. **Classic CKB** — secp256k1, standard Nervos addresses, BIP-39.
2. **Post-Quantum** — ML-DSA-65 testnet lock (`fips204`). Extended BIP-39 backup (36 / 54 / 72 words).
3. **Hybrid vault** — both classic and PQ accounts under one identity with a one-tap **Internal Transfer** flow.
4. **Watch-only** — xpub import for monitoring without private keys.
5. **JoyID / passkey** — WebAuthn-backed accounts via redirect-relay signing.

## What's shipped

### Wallet core
- BIP-39 create / import (12 / 18 / 24-word classic, extended for PQ).
- Mnemonic verify challenge before activation.
- secp256k1 and ML-DSA-65 signing via Rust (`fips204`).
- Local-only transaction assembly. The network only ever sees signed transactions.
- Address validation engine: bech32 / bech32m / deprecated formats, network mismatch detection.
- Custom sUDT import by type script.

### Chain access
- Public RPC (mainnet / testnet) with per-network endpoint pools.
- Multi-endpoint failover and tip-lag health checks.
- Dedicated **RPC Health** screen.
- Cell sync, balance indexing, fee estimation via RPC.

### Key storage
- Android Keystore with **StrongBox** AES-256-GCM seed wrap (toggleable).
- BiometricPrompt unlock.
- Credential Manager for passkeys.

### Assets
- Spore, CoTA and CKBFS scanners.
- Asset gallery with capacity, owner lock, and cell inspector.
- CKBFS rendering for supported MIME types.
- Cellswap-inspired display patterns.

### Marketplace
- LSDL list / cancel / buy flows.
- Royalty + expiry UI.
- Listed-asset dashboard.
- Transaction simulation pre-broadcast.

### Messaging
- CEMP-PQ protocol: profile cells, contact discovery, encrypted send / receive.
- ML-KEM + AES-256-GCM payloads.
- Notification cell scanner.
- Local encrypted message database.

### Nervos DAO
- Deposit, two-phase withdrawal, unlock.
- APC tracking, compensation cycle progress, status badges.
- Active / Completed tabs with optimistic pending-tx state.

### UI
- Jetpack Compose throughout.
- 6 built-in theme presets + custom theme JSON import / export.
- Per-panel image backgrounds (skin engine).
- QR receive + camera scanner for send.
- Transaction history + detail with explorer links.

## Architecture

```
Kotlin + Jetpack Compose UI
 ├─ Wallet Core (Rust via UniFFI/JNI)
 │   ├─ BIP-39 / seed vault
 │   ├─ classic CKB engine (secp256k1)
 │   ├─ PQ engine (ML-DSA-65 / fips204)
 │   ├─ transaction builder (local only)
 │   └─ address validator
 │
 ├─ Chain Access
 │   ├─ public RPC pool (mainnet / testnet)
 │   ├─ failover + health checks
 │   └─ embedded ckb-light-client-lite (planned)
 │
 ├─ Signing + Key Storage
 │   ├─ Android Keystore (StrongBox)
 │   ├─ BiometricPrompt
 │   ├─ Credential Manager passkeys
 │   └─ encrypted export / import
 │
 ├─ Assets
 │   ├─ Spore / CoTA / CKBFS scanners
 │   ├─ cell inspector
 │   └─ LSDL listing flows
 │
 ├─ Messaging
 │   ├─ CEMP-PQ protocol
 │   ├─ ML-KEM + AES-GCM
 │   └─ notification cell scanner
 │
 ├─ Nervos DAO
 │   ├─ deposit / withdraw / unlock
 │   └─ APC + cycle tracking
 │
 └─ Skinning
     ├─ panel templates
     ├─ user image picker
     └─ theme presets + JSON import / export
```

## Tech stack

- Kotlin + Jetpack Compose
- Rust wallet core via UniFFI / JNI (`rust-core/`)
- Kotlin wallet integration layer (`wallet-core/`)
- Room / SQLCipher for local metadata
- Android Keystore + BiometricPrompt + StrongBox
- Credential Manager for passkeys
- WorkManager for background sync
- Foreground service for the (planned) light-client runtime

## Security

- All transactions are assembled and signed locally.
- RPC connections are only used for: cell queries, header queries, fee estimation, script resolution, and final signed-tx submission.
- Private keys, seed material, and passkey secrets never leave the device.
- **PQ support is testnet-labelled** until the upstream lock scripts are audited. The app surfaces this prominently anywhere PQ assets are sent or signed.

## Source repos

These upstream projects informed each subsystem:

- `ckb-light-client-lite` — embedded light-client (planned).
- `ckb-mldsa-lock` — PQ lock variants (ML-DSA-65 default).
- `ccc` — transaction assembly, address handling.
- `cemp-pq` — encrypted on-chain messaging.
- `ckb-cell-marketplace` — asset display patterns.
- `ckb-lsdl` — listing cells for sale.
- `key-vault-wasm` — PQ key-derivation concepts.
- `joyid-sdk-js` / `jidsdr` — passkey reference patterns.

## Build

```bash
# Rust core (build before Kotlin if it has changed)
cd rust-core && cargo build --release

# Android (debug)
./gradlew :app:assembleDebug

# Android (release)
./gradlew :app:assembleRelease
```

## Roadmap

- **Embedded light client** — bundle `ckb-light-client-lite` as a foreground service to fully close MVP 2.
- **Multi-chain swaps** — BTC / ETH / SOL via BIP-39 derivation, zero-custody routing through Onramper (fiat), Rango (DEX aggregator) and UTXOSwap (CKB-native DEX). Full design in `PLAN-SWAPS.md`.

## License

Private — Wyltek Studio
