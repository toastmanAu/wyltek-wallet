# Pocket L1 Nervos Wallet

Native Android CKB wallet with post-quantum cryptography support.

## Account Modes

1. **Classic CKB** — secp256k1 / standard Nervos addresses / BIP39
2. **Post-Quantum** — ML-DSA / Falcon testnet lock support
3. **Hybrid Vault** — both classic and PQ accounts with one-tap internal transfer

## Architecture

```
Kotlin + Jetpack Compose UI
 ├─ Wallet Core (Rust via UniFFI/JNI)
 │   ├─ BIP39 / seed vault
 │   ├─ classic CKB account engine
 │   ├─ PQ account engine
 │   ├─ transaction builder (local only)
 │   └─ address validator
 │
 ├─ Chain Access
 │   ├─ embedded ckb-light-client-lite
 │   ├─ public RPC profile
 │   ├─ private full-node RPC profile
 │   └─ failover + health checks
 │
 ├─ Signing + Key Storage
 │   ├─ Android Keystore
 │   ├─ BiometricPrompt
 │   ├─ passkey / WebAuthn
 │   ├─ encrypted export/import
 │   └─ hardware-backed key wrapping
 │
 ├─ Assets
 │   ├─ Spore viewer
 │   ├─ CoTA viewer
 │   ├─ CKBFS resolver
 │   └─ LSDL listing flows
 │
 ├─ Messaging
 │   ├─ CEMP-PQ protocol
 │   ├─ ML-KEM + AES-GCM encryption
 │   └─ notification cell scanner
 │
 └─ Skinning
     ├─ panel templates
     ├─ user image picker
     └─ theme presets
```

## Tech Stack

- Kotlin + Jetpack Compose
- Rust wallet core via UniFFI/JNI
- Room / SQLCipher for local metadata
- Android Keystore + BiometricPrompt
- Credential Manager for passkeys
- WorkManager for background sync
- Foreground service for light-client runtime

## Build Milestones

| MVP | Scope |
|-----|-------|
| 1 | Native wallet core — create/import, local tx assembly, RPC, send/receive, address validation |
| 2 | Light client — embedded light-client, node status, cell sync |
| 3 | PQ accounts — ML-DSA-65, internal transfer classic ↔ PQ |
| 4 | Assets — Spore/CoTA/CKBFS scanners, gallery, cell inspector |
| 5 | Marketplace — LSDL list/cancel/buy, royalty/expiry UI |
| 6 | Messaging — CEMP-PQ profile cells, contact book, encrypted send/receive |

## Security

- All transactions assembled and signed locally
- RPC connections only for: cell queries, header queries, fee estimation, script resolution, tx submission
- Never send private keys, seed material, or passkey secrets to a server
- PQ support is **testnet-labelled** until lock scripts are audited

## Source Repos

- `ckb-light-client-lite` — embedded light-client
- `ckb-mldsa-lock` — PQ lock variants (ML-DSA-65 default)
- `ccc` — transaction assembly, address handling
- `cemp-pq` — encrypted on-chain messaging
- `ckb-cell-marketplace` — asset display patterns
- `ckb-lsdl` — listing cells for sale
- `key-vault-wasm` — PQ key derivation concepts
- `joyid-sdk-js` / `jidsdr` — passkey reference patterns

## License

Private — Wyltek Studio
