# sUDT sends from the PQ lock — Design

**Date:** 2026-06-18
**Status:** Approved (brainstorming) — pending spec review
**Branch:** `feat/sudt-from-pq`

## Goal

Allow an sUDT (Simple UDT) token to be spent **from** a wallet's post-quantum (ML-DSA-65) lock. Today `WalletRepository.sendToken` refuses any PQ-source token send and the internal-transfer screen disables token chips when the source is the PQ lock. CKB-from-PQ (`sendCkb`) and sUDT-from-Classic (`sendToken` secp path) are both already verified on-chain; this closes the remaining gap.

## Root cause (why it's blocked today)

The deployed `mldsa65-lock-v2-rust` lock signs a **CighashAll** digest — `blake2b("ckb-mldsa-msg", generate_ckb_tx_message_all)` — that commits to **every input's full `CellOutput`, including its type script**, plus that input's data.

The off-chain signer reconstructs this digest from a `MldsaInputCell` list. The current `MldsaInputCell` (`rust-core/src/signing.rs:113`) carries only `capacity`, `lock_*`, and `data` — **no type script** — and `input_cell_output_bytes` hardcodes `type_: None`. For pure-CKB inputs that is correct. But an sUDT input cell **has** a type script (the sUDT type) and data (the u128 balance). Signing such an input with `type_: None` produces a digest the on-chain lock disagrees with → `SignatureVerifyFailed (error -46)`.

This is the same class of bug as the historical non-canonical molecule serializer: the signer must reconstruct, byte-for-byte, what the on-chain VM sees. The secp lock ignores input cell contents beyond the sighash, so this never mattered for the secp `sendToken` path — only the ML-DSA lock exposes it.

## Architecture

Three layers, smallest-blast-radius first.

### Part 1 — Rust signing layer (the core fix)

Extend `MldsaInputCell` with an optional type script:

```rust
pub struct MldsaInputCell {
    pub capacity: u64,
    pub lock_code_hash: String,
    pub lock_hash_type: String,
    pub lock_args: String,
    pub type_code_hash: String,   // NEW — "" = no type script
    pub type_hash_type: String,   // NEW
    pub type_args: String,        // NEW
    pub data: String,
}
```

`input_cell_output_bytes` builds `type_: Some(ScriptSer{ code_hash, hash_type, args })` when `type_code_hash` is non-empty, else `None` (today's behaviour). Empty-string-means-absent matches the existing `TxOutput` convention used elsewhere in the codebase.

**Invariant preserved:** pure-CKB inputs serialize byte-identically to today, so the secp path and the already-verified PQ-CKB (`sendCkb`) path are unchanged. The secp `signWitness0` only reads `inputs.size`, so the new fields are inert there.

Rebuild the arm64 `.so` and regenerate UniFFI bindings (the Kotlin `MldsaInputCell` record gains three fields).

### Part 2 — Kotlin wiring (`WalletRepository.sendToken`)

Route `sendToken` through the same `resolveSigningContext` abstraction `sendCkb` uses, instead of hand-rolled secp signing:

1. **Delete** the PQ refusal block and the unconditional secp `keyPair`.
2. `val signCtx = resolveSigningContext(seed, from, network)` — null → clear "PQ lock not deployed" error.
3. Fee = `signCtx.minFeeEstimate` (1 000 secp / 10 000 PQ), replacing the flat `2000`. The existing change math already subtracts the fee estimate, so no zero-fee regression.
4. Cell-deps = `signCtx.cellDeps + sUDT type-script dep` (and the matching `cellDepsForJson + sUDT JsonCellDep` for the broadcast JSON).
5. Build `mldsaInputs` in the **same order** as `allInputs = sudtInputs + selectedCkb`:
   - sUDT inputs → type script (`tokenTypeScript.*`) + u128 `data`,
   - CKB inputs → empty type + empty data.
6. `val witness0 = signCtx.signWitness0(built, mldsaInputs)` — secp WitnessArgs **or** ML-DSA flat witness, transparently.
7. witnesses array: `witness0` then `"0x"` for the rest.

**Correctness points:**
- *Input order* — the CighashAll stream hashes inputs positionally, so `mldsaInputs` is constructed from `sudtInputs` then `selectedCkb`, mirroring `allInputs`.
- *Single script group* — all inputs are fetched by the same PQ lock (`getCellsByLock(fromLock)`), so the digest's one-group witness assumption (first group witness has `input_type`/`output_type` absent, rest empty) holds.
- Also fix the stale `SigningContext` doc-comment, which still describes the pre-v2 digest `blake2b("ckb-default-hash", "CKB-MLDSA-LOCK" || tx_hash)`.

### Part 3 — Verification (harness + on-chain)

- **Rust unit test** (`signing.rs`): assert `cighash_all_digest` over an input *with* a type script differs from the same input *without* one — proves the type script is committed. This is the regression guard that would have caught the bug.
- **Harness cmd** `send-sudt-from-pq <seed> <to> <amount>` in `rust-core/examples/pq_testnet.rs` — mirrors the app's exact PQ `sendToken` path; builds + broadcasts.

**On-chain sequence (Pudge testnet, funded hybrid wallet):**
1. `secp-balance` / `sudt-balance` — confirm the classic lock holds CKB + sUDT; confirm the PQ lock has ~200+ CKB free to hold an sUDT cell + fee.
2. **Fund the PQ lock with sUDT** via the existing working Classic→PQ `send-sudt` (PQ as destination works today). Confirm the sUDT cell lands on the PQ lock.
3. **The test:** `send-sudt-from-pq` (PQ→Classic). Expect pool-accepted; verify sUDT conservation (in = out + change) and that the change returns to the PQ lock.
4. Diagnosis shortcuts: `-46 SignatureVerifyFailed` → digest still wrong (type script omission); `PoolRejectedTransactionByMinFeeRate` → fee not reserved.

## Components & boundaries

| Unit | Responsibility | Depends on |
|------|----------------|------------|
| `MldsaInputCell` + `input_cell_output_bytes` (Rust) | Reconstruct an input's `CellOutput` bytes incl. optional type script | `molecule.rs` serializers |
| `cighash_all_digest` (Rust) | The CighashAll digest over inputs | `MldsaInputCell` |
| `resolveSigningContext` (Kotlin) | Pick secp vs PQ dep + witness builder | `NetworkConfig`, signing FFI |
| `sendToken` (Kotlin) | Build sUDT tx, reserve fee, sign via context, broadcast | `resolveSigningContext`, `chainManager` |
| `send-sudt-from-pq` (harness) | On-chain replica of `sendToken` PQ path | signing FFI |

## Error handling

- Insufficient sUDT / CKB capacity → explicit `WalletResult.Error` (existing guards retained).
- PQ lock not deployed on network → null `signCtx` → clear error (existing pattern).
- Empty/garbage lock code hash → `resolveSigningContext` returns null → refuse to sign.

## Testing

- Rust: digest-includes-type-script unit test (new); existing `cighash_all_digest` sample test still green.
- Kotlin: `:app:compileDebugKotlin` green; existing `InternalTransferLogicTest` unaffected.
- On-chain: pool-accepted PQ→Classic sUDT tx hash on Pudge with confirmed conservation = definition of done.

## Out of scope (YAGNI)

- Flipping the internal-transfer screen's `tokensEnabled` gate to allow PQ-source token chips — separate follow-up once this is proven on-chain.
- sUDT *minting* from a PQ lock.
- Multi-token / batched sends.

## Definition of done

1. Rust digest unit test green.
2. `:app:compileDebugKotlin` green.
3. Pool-accepted PQ→Classic sUDT tx on Pudge testnet, conservation verified, change at PQ lock.
4. PLAN.md line 343 updated (sUDT-from-PQ ⏳ → ✅) with the tx hash.

## Fallback

If the PQ lock cannot be funded with sUDT on testnet this session, fall back to Parts 1–2 + the Rust digest test, and hand over the harness commands for the user to run the live broadcast.
