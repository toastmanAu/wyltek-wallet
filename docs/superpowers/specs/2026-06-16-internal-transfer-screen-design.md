# Internal Transfer Screen — Design

**Date:** 2026-06-16
**Status:** Approved (design); pending implementation plan
**Branch:** `fix/pq-v2rust-and-molecule` (or a fresh feature branch)

## Purpose

Let a user move funds between the two locks of one hybrid wallet — the Classic
(secp256k1) lock and the Post-Quantum (ML-DSA-65) lock — without leaving the app
or copy-pasting their own address. Replaces the `InternalTransferScreen.kt` stub
(`onClick = { /* TODO */ }`).

## Key Insight

An internal transfer is a **self-send**: a transfer where the destination is the
*other* sub-account's own address. A hybrid `WalletAccount` already carries both
locks as `addresses: List<CkbAddress>`. The existing ViewModel methods both take
an explicit source sub-account and are **on-chain verified**:

- `sendCkb(toAddress, amount, fromCkbAddress)` — classic spend `0x74b8352c…`, PQ spend `0x51ccf4…`
- `sendToken(toAddress, tokenTypeScript, amount, fromCkbAddress)` — sUDT transfer `0x0bc30c02…`

Therefore this feature adds **no transaction, signing, or Rust code** — it is a UI
shell that orchestrates verified primitives.

## Scope

| In scope (v1) | Out of scope (v1) |
|---|---|
| CKB transfer, both directions | sUDT **from** PQ (gracefully disabled) |
| sUDT transfer, Classic → PQ only | Fee customization |
| Bidirectional swap toggle | Address book / external recipients |
| Graceful disable when a path is unsupported or wallet isn't hybrid | Batch / multi-asset in one tx |

## Architecture

Pure Compose UI over existing state. No changes to `WalletRepository`,
`WalletViewModel` method signatures, `wallet-core`, or `rust-core`.

State source: `WalletViewModel.uiState` —
`currentAccount.addresses`, `tokenBalances: List<TokenInfo>`, `isLoading`,
`lastTxHash`, `error`.

Sub-account resolution: classify each `CkbAddress` in `currentAccount.addresses`
via `NetworkConfig.isPqLock(address.lockScript.codeHash, account.network)` →
`classicAddr` and `pqAddr`. If **both** are not present, render a disabled state:
*"Internal transfer needs both a Classic and a PQ lock on this wallet."*

## Components (top → bottom)

1. **Direction card** — From / To rows (truncated bech32m) with the existing
   `SwapHoriz` icon as a button that toggles `direction`
   (`CLASSIC_TO_PQ` ⇄ `PQ_TO_CLASSIC`). `source` / `dest` derive from `direction`.
2. **Asset selector** — selectable chips: `CKB` plus one per `tokenBalances`
   entry (label = `symbol`). When `source` is the PQ lock, token chips are
   **disabled** with a hint (*"PQ token sends coming soon — CKB only from PQ"*);
   the `CKB` chip stays enabled. If the selected asset becomes disabled after a
   swap, selection falls back to `CKB`.
3. **Amount field** — `CKB` → decimal parsed to shannons (×100_000_000);
   token → raw integer units as `BigInteger` (matching `sendToken`'s contract,
   since `TokenInfo` carries a raw `amount` and no decimals). A **Max** action
   fills the selected asset's balance at the source lock.
4. **Security note** — shown only when `source` is the PQ lock (signing from PQ
   triggers the device gate).
5. **Send button** — disabled until the amount is valid (> 0, parses) and
   ≤ source balance for the selected asset.

## Data Flow

```
tap Send
  → if source is PQ: biometric gate (rememberBiometricAuth, mirrors
    SendScreen.requiresAuth) ; else proceed
  → on gate success (or not required):
      CKB   → viewModel.sendCkb(dest.bech32m, shannons, fromCkbAddress = source)
      token → viewModel.sendToken(dest.bech32m, token.typeScript, units,
                                  fromCkbAddress = source)
  → observe uiState:
      isLoading  → spinner / disabled button
      lastTxHash → success surface (tx hash + explorer link), clearable
      error      → inline error text
```

## Error Handling

- Insufficient balance and broadcast failures already return
  `WalletResult.Error` from the repository → surfaced inline. No new logic.
- The unsupported PQ→Classic sUDT path is prevented at the UI layer (disabled
  chips), so no invalid transaction is ever constructed or broadcast.
- Biometric `Unavailable` / `Error` → inline auth error (mirrors `SendScreen`).

## Testing

Manual on testnet (all reuse already-verified primitives):

- Classic → PQ, CKB — succeeds.
- PQ → Classic, CKB — succeeds (PQ signing path).
- Classic → PQ, sUDT — succeeds.
- Swap to PQ → Classic — token chips disable, CKB stays enabled.
- Non-hybrid wallet (single lock) — disabled state renders, no send possible.
- Insufficient amount — send button disabled; over-balance attempt surfaces the
  repository error.

## Files Touched

- `app/src/main/java/com/wyltek/wallet/ui/screens/InternalTransferScreen.kt`
  — replace the stub body with the stateful screen (the only substantive change).
- Possibly a small `viewModel` parameter added to the composable signature +
  the `AppNavigation.kt` call site (currently `InternalTransferScreen(onBack = …)`),
  to pass `uiState` and the send actions.

No other files change.
