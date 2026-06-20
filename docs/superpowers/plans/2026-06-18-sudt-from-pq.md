# sUDT sends from the PQ lock — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let an sUDT token be spent from a wallet's post-quantum (ML-DSA-65) lock, closing the last gap in the internal-transfer feature.

**Architecture:** The deployed `mldsa65-lock-v2-rust` lock signs a CighashAll digest that commits to every input's full `CellOutput` — including its type script. The off-chain signer's `MldsaInputCell` currently omits the type script, so signing an sUDT input (which has one) yields a digest the lock rejects. Fix: add an optional type script to `MldsaInputCell`; route `sendToken` through the same `resolveSigningContext` abstraction `sendCkb` already uses (secp-vs-PQ witness + lock cell-dep), appending the sUDT type-script cell-dep; prove it on testnet via a new harness command.

**Tech Stack:** Rust (rust-core, uniffi), Kotlin (Android app), CKB Pudge testnet.

## Global Constraints

- `MldsaInputCell` type-script fields use the empty-string-means-absent convention (matches existing `TxOutput`).
- Pure-CKB inputs and the existing secp / PQ-CKB paths MUST remain byte-identical (no regression to verified `sendCkb` / secp `sendToken`).
- On-chain proof is the definition of done — compile + unit tests are necessary but not sufficient (per `~/.claude/rules/ckb-transactions.md` feedback log).
- sUDT `type_args` is the **classic owner lock hash** fixed at mint, even when the token is spent from the PQ lock.
- Branch: `feat/sudt-from-pq`.

---

## Task 1: Extend `MldsaInputCell` with an optional type script (Rust)

**Files:**
- Modify: `rust-core/src/signing.rs` (struct at :113, `input_cell_output_bytes` at :131, test helper `sample_input` at :515)
- Modify: `rust-core/examples/pq_testnet.rs:301` (existing `MldsaInputCell` construction — keep the example compiling)
- Test: `rust-core/src/signing.rs` (`mod mldsa_v2_tests`)

**Interfaces:**
- Produces: `MldsaInputCell { capacity: u64, lock_code_hash, lock_hash_type, lock_args, type_code_hash, type_hash_type, type_args, data: String }` — three new `String` fields, `""` = no type script.
- Consumes: `crate::molecule::{ScriptSer, CellOutputSer}` (CellOutputSer.type_ is already `Option<ScriptSer>`), `crate::molecule::hex_to_byte32`, `hex_to_bytes`.

- [ ] **Step 1: Write the failing test**

In `rust-core/src/signing.rs`, inside `mod mldsa_v2_tests`, add:

```rust
    #[test]
    fn digest_commits_input_type_script() {
        let tx_hash = [0x11u8; 32];
        let base = sample_input(&"00".repeat(37)); // empty type script
        let mut with_type = base.clone();
        with_type.type_code_hash = format!("0x{}", "aa".repeat(32));
        with_type.type_hash_type = "type".to_string();
        with_type.type_args = "0xdeadbeef".to_string();

        let d_without = cighash_all_digest(&tx_hash, &[base]).unwrap();
        let d_with = cighash_all_digest(&tx_hash, &[with_type]).unwrap();
        assert_ne!(
            d_without, d_with,
            "the input's type script must be committed into the CighashAll digest"
        );
    }
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd rust-core && cargo test --lib digest_commits_input_type_script`
Expected: FAIL — compile error, `MldsaInputCell` has no field `type_code_hash`.

- [ ] **Step 3: Add the three fields to the struct**

In `rust-core/src/signing.rs`, replace the `MldsaInputCell` struct (around :113):

```rust
#[derive(uniffi::Record, Clone)]
pub struct MldsaInputCell {
    pub capacity: u64,
    pub lock_code_hash: String,
    pub lock_hash_type: String,
    pub lock_args: String,
    /// sUDT (or other) type script; "" = pure-CKB cell, no type script.
    pub type_code_hash: String,
    pub type_hash_type: String,
    pub type_args: String,
    pub data: String,
}
```

- [ ] **Step 4: Serialize the type script in `input_cell_output_bytes`**

Replace the body of `input_cell_output_bytes` (around :131):

```rust
fn input_cell_output_bytes(cell: &MldsaInputCell) -> Result<Vec<u8>, WalletError> {
    let lock = ScriptSer {
        code_hash: crate::molecule::hex_to_byte32(&cell.lock_code_hash)
            .map_err(WalletError::InvalidInput)?,
        hash_type: hash_type_byte(&cell.lock_hash_type),
        args: hex_to_bytes(&cell.lock_args).map_err(WalletError::InvalidInput)?,
    };
    let type_ = if cell.type_code_hash.trim_start_matches("0x").is_empty() {
        None
    } else {
        Some(ScriptSer {
            code_hash: crate::molecule::hex_to_byte32(&cell.type_code_hash)
                .map_err(WalletError::InvalidInput)?,
            hash_type: hash_type_byte(&cell.type_hash_type),
            args: hex_to_bytes(&cell.type_args).map_err(WalletError::InvalidInput)?,
        })
    };
    let out = CellOutputSer { capacity: cell.capacity, lock, type_ };
    let mut buf = Vec::new();
    out.serialize(&mut buf);
    Ok(buf)
}
```

- [ ] **Step 5: Update the test helper `sample_input` to set the new fields**

In `mod mldsa_v2_tests`, update `sample_input` (around :515) so it initializes the new fields to empty:

```rust
    fn sample_input(pq_args: &str) -> MldsaInputCell {
        MldsaInputCell {
            capacity: 100_0000_0000,
            lock_code_hash:
                "0xd70653f7fd51e173ec506b76081f37bf4acebb8a15dc79e6d4ad43ca4d3b78a4".into(),
            lock_hash_type: "type".into(),
            lock_args: format!("0x{pq_args}"),
            type_code_hash: String::new(),
            type_hash_type: String::new(),
            type_args: String::new(),
            data: String::new(),
        }
    }
```

- [ ] **Step 6: Update the harness PQ construction site to keep the example compiling**

In `rust-core/examples/pq_testnet.rs` (around :301), add the three empty fields to the existing `MldsaInputCell`:

```rust
        .map(|c| MldsaInputCell {
            capacity: c.capacity,
            lock_code_hash: MLDSA_CODE_HASH.to_string(),
            lock_hash_type: MLDSA_HASH_TYPE.to_string(),
            lock_args: pq_args.clone(),
            type_code_hash: String::new(),
            type_hash_type: String::new(),
            type_args: String::new(),
            data: String::new(),
        })
```

- [ ] **Step 7: Run the test + the existing digest tests to verify all pass**

Run: `cd rust-core && cargo test --lib && cargo build --example pq_testnet`
Expected: PASS — `digest_commits_input_type_script` green, `witness_has_flat_lock_layout` + `signature_verifies_over_cighash_digest` still green, example builds.

- [ ] **Step 8: Commit**

```bash
git add rust-core/src/signing.rs rust-core/examples/pq_testnet.rs
git commit -m "feat(pq): commit input type script into ML-DSA CighashAll digest"
```

---

## Task 2: Regenerate UniFFI bindings + rebuild the arm64 `.so` (Kotlin build)

**Files:**
- Regenerate: `app/src/main/java/com/wyltek/wallet/core/native/wyltekwalletcore.kt` (UniFFI-generated)
- Rebuild: the arm64 `.so` packaged with the app
- Modify: `app/src/main/java/com/wyltek/wallet/data/WalletRepository.kt:992` (existing `sendCkb` `MldsaInputCell` construction — add the three empty fields)

**Interfaces:**
- Consumes: the new `MldsaInputCell` Record from Task 1.
- Produces: a compiling app whose `sendCkb` behaviour is unchanged (empty type fields ⇒ identical pure-CKB serialization).

- [ ] **Step 1: Regenerate bindings + rebuild the native library**

Run the project's existing binding-generation + cross-compile step (the same one used after prior signing changes — see prior commits that regenerated `wyltekwalletcore.kt` + rebuilt the arm64 `.so`). Confirm the generated `MldsaInputCell` data class in `wyltekwalletcore.kt` now has `typeCodeHash`, `typeHashType`, `typeArgs` properties.

Verify: `grep -n "class MldsaInputCell" -A10 app/src/main/java/com/wyltek/wallet/core/native/wyltekwalletcore.kt`
Expected: the data class lists `typeCodeHash`, `typeHashType`, `typeArgs`.

- [ ] **Step 2: Update the existing `sendCkb` construction site**

In `WalletRepository.kt` (around :992), the `selected.map { MldsaInputCell(...) }` must pass the new fields. Replace that construction:

```kotlin
            val mldsaInputs = selected.map {
                MldsaInputCell(
                    capacity = it.capacity,
                    lockCodeHash = fromInfo.lockCodeHash,
                    lockHashType = fromInfo.lockHashType,
                    lockArgs = fromInfo.lockArgs,
                    typeCodeHash = "",
                    typeHashType = "",
                    typeArgs = "",
                    data = ""
                )
            }
```

- [ ] **Step 3: Verify the app compiles against the new bindings**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/wyltek/wallet/core/native/wyltekwalletcore.kt \
        app/src/main/java/com/wyltek/wallet/data/WalletRepository.kt
git commit -m "build(pq): regen uniffi bindings for MldsaInputCell type script + rebuild .so"
```

> Note: if the native `.so` is tracked in git (check `git status` for a changed `*.so`), add it to this commit too.

---

## Task 3: Route `sendToken` through `resolveSigningContext` (Kotlin)

**Files:**
- Modify: `app/src/main/java/com/wyltek/wallet/data/WalletRepository.kt` — `sendToken` (:1067–1323) and the stale `SigningContext` doc-comment (:1502–1505)

**Interfaces:**
- Consumes: `resolveSigningContext(seedHex, from, network): SigningContext?` with `{ cellDeps: List<TxCellDep>, cellDepsForJson: List<JsonCellDep>, minFeeEstimate: ULong, signWitness0: (BuiltTransaction, List<MldsaInputCell>) -> String }`; `MldsaInputCell` (8 fields, Task 2); `JsonCellDep(txHash, index, depType)`; `networkConfig.{sudtCellDepTxHash, sudtCellDepIndex}`.
- Produces: a `sendToken` that signs secp **or** PQ transparently.

- [ ] **Step 1: Replace the secp-only signing setup with the signing context**

In `sendToken`, replace the keypair line + the PQ refusal block (:1078–1094) with:

```kotlin
            val from = fromCkbAddress ?: fromAccount.addresses.firstOrNull()
                ?: return WalletResult.Error("No from address")

            // Pick secp vs PQ signing (deps + witness builder) the same way
            // sendCkb does — this is what unlocks sUDT-from-PQ.
            val signCtx = resolveSigningContext(seed, from, fromAccount.network)
                ?: return WalletResult.Error(
                    "PQ lock not deployed on ${fromAccount.network} — set NetworkConfig.mldsa65 to the testnet deployment."
                )
```

(Delete the now-unused `val keyPair = generateSecp256k1Keypair(...)` line and the whole `val mldsa = ...; if (...) return WalletResult.Error("Token sends from PQ sub-accounts...")` block.)

- [ ] **Step 2: Source the fee from the context**

Replace the flat fee (:1147):

```kotlin
            // Fee per the signing algorithm (1k secp / 10k PQ). build_transaction
            // only reports estimated_fee; the caller must hold it back from the
            // CKB change or the pool rejects a zero-fee tx.
            val sudtFeeEstimate = signCtx.minFeeEstimate
```

- [ ] **Step 3: Build cell-deps as lock-dep + sUDT-dep**

Replace the hard-coded `cellDeps` list (:1221–1233) with:

```kotlin
            val networkConfig = NetworkConfig.forNetwork(fromAccount.network)
            val sudtDep = TxCellDep(
                txHash = networkConfig.sudtCellDepTxHash,
                index = networkConfig.sudtCellDepIndex,
                depType = 0u
            )
            val cellDeps = signCtx.cellDeps + sudtDep
            val cellDepsForJson = signCtx.cellDepsForJson +
                JsonCellDep(networkConfig.sudtCellDepTxHash, networkConfig.sudtCellDepIndex, "code")
```

- [ ] **Step 4: Sign via the context with type-script-bearing inputs**

Replace the secp signing call (:1251–1257) with the context call, building `MldsaInputCell`s in the **same order** as `allInputs = sudtInputs + selectedCkb`:

```kotlin
            // CighashAll (PQ) hashes inputs positionally and commits each input's
            // full CellOutput. sUDT inputs MUST carry their type script + u128
            // data; CKB inputs carry none. secp ignores this (uses count only).
            val mldsaInputs = sudtInputs.map {
                MldsaInputCell(
                    capacity = it.capacity,
                    lockCodeHash = fromInfo.lockCodeHash,
                    lockHashType = fromInfo.lockHashType,
                    lockArgs = fromInfo.lockArgs,
                    typeCodeHash = tokenTypeScript.codeHash,
                    typeHashType = tokenTypeScript.hashType,
                    typeArgs = tokenTypeScript.args,
                    data = it.data ?: ""
                )
            } + selectedCkb.map {
                MldsaInputCell(
                    capacity = it.capacity,
                    lockCodeHash = fromInfo.lockCodeHash,
                    lockHashType = fromInfo.lockHashType,
                    lockArgs = fromInfo.lockArgs,
                    typeCodeHash = "", typeHashType = "", typeArgs = "", data = ""
                )
            }
            val witness0 = signCtx.signWitness0(built, mldsaInputs)
```

- [ ] **Step 5: Use the context cell-deps + signed witness in the broadcast JSON**

In the `txJson` builder: the `cell_deps` loop must iterate `cellDepsForJson` (each `JsonCellDep` has `.txHash`, `.index`, `.depType` already as the RPC string), and witnesses[0] becomes `witness0` (already `0x`-prefixed). Replace the `cell_deps` array body:

```kotlin
                put("cell_deps", buildJsonArray {
                    for (dep in cellDepsForJson) {
                        add(buildJsonObject {
                            put("out_point", buildJsonObject {
                                put("tx_hash", dep.txHash)
                                put("index", "0x${dep.index.toString(16)}")
                            })
                            put("dep_type", dep.depType)
                        })
                    }
                })
```

and the `witnesses` array body:

```kotlin
                put("witnesses", buildJsonArray {
                    add(witness0)
                    for (i in 1 until allInputs.size) add("0x")
                })
```

- [ ] **Step 6: Fix the stale SigningContext doc-comment**

In `resolveSigningContext` (:1502–1505), replace the PQ comment that describes the pre-v2 digest:

```kotlin
            // ML-DSA-65 v2-rust path. signCkbMldsa65 produces the flat
            // WitnessArgs(lock = [flag | pubkey | sig]) the deployed
            // mldsa65-lock-v2-rust contract verifies. Signing digest is
            // blake2b("ckb-mldsa-msg", generate_ckb_tx_message_all) — the
            // CighashAll stream over every input cell (lock + type + data).
```

- [ ] **Step 7: Verify the app compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

> No pure-unit test is added here: `sendToken` is I/O + signing, exercised end-to-end by the harness in Task 5 (the only meaningful test per the project's CKB rules).

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/wyltek/wallet/data/WalletRepository.kt
git commit -m "feat(transfer): sign sUDT sends via resolveSigningContext (enables sUDT-from-PQ)"
```

---

## Task 4: Harness `send-sudt-from-pq` command (Rust)

**Files:**
- Modify: `rust-core/examples/pq_testnet.rs` — dispatch (`match cmd` at :84) + new `cmd_send_sudt_from_pq`

**Interfaces:**
- Consumes (all already in the file): `mldsa65_from_seed`, `mldsa65_lock_args_v2`, `generate_secp256k1_keypair`, `public_key_to_ckb_address`, `decode_address`, `owner_lock_hash`, `get_sudt_cells`, `get_cells`, `SudtCell`, `Cell`, `TxOutput`, `TxInput`, `TransactionRequest`, `TxCellDep`, `build_transaction`, `sign_ckb_mldsa65`, `MldsaInputCell`, `build_tx_json`, `send_transaction`, `u128_le_hex`, and consts `MLDSA_CODE_HASH`, `MLDSA_HASH_TYPE`, `MLDSA_DEP_TX_HASH`, `MLDSA_DEP_INDEX`, `SUDT_CODE_HASH`, `SUDT_DEP_TX_HASH`, `SUDT_DEP_INDEX`, `SUDT_CELL_CAPACITY`, `SUDT_FEE_ESTIMATE`, `MIN_CELL_CAPACITY`, `SECP_DERIVATION_PATH`.

- [ ] **Step 1: Add the dispatch arm**

In `main`'s `match cmd` (after the `"send-sudt"` arm at :103):

```rust
        "send-sudt-from-pq" => cmd_send_sudt_from_pq(
            arg(&args, 1, "seed_hex"),
            arg(&args, 2, "to_address"),
            arg(&args, 3, "amount"),
        ),
```

- [ ] **Step 2: Add the command**

Append to `rust-core/examples/pq_testnet.rs`:

```rust
/// Spend sUDT FROM the PQ lock (PQ -> classic). Mirrors WalletRepository.sendToken's
/// PQ path: ML-DSA witness, dual cell-dep (MLDSA + SUDT), type-script-bearing inputs.
/// NOTE: the sUDT type_args is the CLASSIC owner lock hash (fixed at mint), even
/// though we spend from the PQ lock.
fn cmd_send_sudt_from_pq(seed_hex: String, to_address: String, amount_str: String) -> Result<(), String> {
    let seed_hex = seed_hex.trim_start_matches("0x").to_string();
    let amount: u128 = amount_str.parse().map_err(|_| "amount must be a whole number of tokens".to_string())?;

    // Classic lock — only used to recover the sUDT owner type_args.
    let secp = generate_secp256k1_keypair(seed_hex.clone(), SECP_DERIVATION_PATH.to_string())
        .map_err(|e| format!("secp keygen: {e}"))?;
    let classic = decode_address(
        public_key_to_ckb_address(secp.public_key_hex.clone(), "testnet".to_string())
            .map_err(|e| format!("address: {e}"))?,
    ).map_err(|e| format!("decode classic: {e}"))?;
    let type_args = owner_lock_hash(&classic.lock_code_hash, &classic.lock_hash_type, &classic.lock_args)?;

    // PQ lock — the SOURCE we spend from.
    let pq = mldsa65_from_seed(seed_hex).map_err(|e| format!("mldsa65_from_seed: {e}"))?;
    let pq_args = format!("0x{}", mldsa65_lock_args_v2(pq.public_key_hex.clone()).map_err(|e| format!("lock args: {e}"))?);
    let to = decode_address(to_address).map_err(|e| format!("decode to: {e}"))?;

    let sudt_cells = get_sudt_cells(MLDSA_CODE_HASH, MLDSA_HASH_TYPE, &pq_args, &type_args)?;
    if sudt_cells.is_empty() {
        return Err("no sUDT cells at the PQ lock — fund it via send-sudt (Classic->PQ) first".into());
    }
    let total: u128 = sudt_cells.iter().map(|c| c.amount).sum();
    if total < amount {
        return Err(format!("insufficient tokens at PQ lock: have {total}, need {amount}"));
    }

    let mut sudt_inputs: Vec<SudtCell> = Vec::new();
    let mut sel_amount: u128 = 0;
    for c in &sudt_cells {
        sudt_inputs.push(c.clone());
        sel_amount += c.amount;
        if sel_amount >= amount { break; }
    }
    let sudt_out_count: u64 = if sel_amount > amount { 2 } else { 1 };
    let sudt_input_cap: u64 = sudt_inputs.iter().map(|c| c.capacity).sum();

    let ckb_cells = get_cells(MLDSA_CODE_HASH, MLDSA_HASH_TYPE, &pq_args)?;
    let needed_ckb = sudt_out_count * SUDT_CELL_CAPACITY + SUDT_FEE_ESTIMATE;
    let mut sorted = ckb_cells.clone();
    sorted.sort_by_key(|c| c.capacity);
    let mut selected_ckb: Vec<&Cell> = Vec::new();
    let mut sel_ckb_cap: u64 = 0;
    for c in &sorted {
        if sel_ckb_cap + sudt_input_cap >= needed_ckb + MIN_CELL_CAPACITY { break; }
        selected_ckb.push(c);
        sel_ckb_cap += c.capacity;
    }
    let total_in_cap = sel_ckb_cap + sudt_input_cap;
    if total_in_cap < needed_ckb {
        return Err(format!("insufficient CKB at PQ lock: have {total_in_cap}, need {needed_ckb}"));
    }

    // Outputs: recipient sUDT (to classic), sUDT change (back to PQ), CKB change (back to PQ).
    let mut outputs = vec![TxOutput {
        capacity: SUDT_CELL_CAPACITY,
        lock_code_hash: to.lock_code_hash.clone(),
        lock_hash_type: to.lock_hash_type.clone(),
        lock_args: to.lock_args.clone(),
        type_code_hash: SUDT_CODE_HASH.to_string(),
        type_hash_type: "type".to_string(),
        type_args: type_args.clone(),
        data: u128_le_hex(amount),
    }];
    if sel_amount > amount {
        outputs.push(TxOutput {
            capacity: SUDT_CELL_CAPACITY,
            lock_code_hash: MLDSA_CODE_HASH.to_string(),
            lock_hash_type: MLDSA_HASH_TYPE.to_string(),
            lock_args: pq_args.clone(),
            type_code_hash: SUDT_CODE_HASH.to_string(),
            type_hash_type: "type".to_string(),
            type_args: type_args.clone(),
            data: u128_le_hex(sel_amount - amount),
        });
    }
    let ckb_change = total_in_cap as i64
        - (sudt_out_count as i64) * (SUDT_CELL_CAPACITY as i64)
        - (SUDT_FEE_ESTIMATE as i64);
    if ckb_change >= MIN_CELL_CAPACITY as i64 {
        outputs.push(TxOutput {
            capacity: ckb_change as u64,
            lock_code_hash: MLDSA_CODE_HASH.to_string(),
            lock_hash_type: MLDSA_HASH_TYPE.to_string(),
            lock_args: pq_args.clone(),
            type_code_hash: String::new(),
            type_hash_type: String::new(),
            type_args: String::new(),
            data: String::new(),
        });
    } else if ckb_change < 0 {
        return Err("insufficient CKB capacity for token transfer".into());
    }

    // Unified input list: sUDT first, then CKB (must match the MldsaInputCell order).
    let mut all_cells: Vec<Cell> = sudt_inputs.iter().map(SudtCell::as_cell).collect();
    all_cells.extend(selected_ckb.iter().map(|c| (*c).clone()));
    let input_refs: Vec<&Cell> = all_cells.iter().collect();

    let request = TransactionRequest {
        inputs: all_cells.iter().map(|c| TxInput {
            tx_hash: c.tx_hash.clone(), index: c.index, since: c.since, capacity: c.capacity,
        }).collect(),
        outputs: outputs.clone(),
        cell_deps: vec![
            TxCellDep { tx_hash: MLDSA_DEP_TX_HASH.to_string(), index: MLDSA_DEP_INDEX, dep_type: 0 },
            TxCellDep { tx_hash: SUDT_DEP_TX_HASH.to_string(), index: SUDT_DEP_INDEX, dep_type: 0 },
        ],
        header_deps: vec![],
        fee_rate: 1000,
    };
    let built = build_transaction(request).map_err(|e| format!("build_transaction: {e}"))?;
    println!("PQ->classic: {amount} tokens to {} ({} sUDT in, {} out)", to.bech32m, sudt_inputs.len(), sudt_out_count);
    println!("tx_hash (signed): 0x{}", built.tx_hash_hex);

    // ML-DSA witness: sUDT inputs carry the type script + data; CKB inputs do not.
    let input_cells: Vec<MldsaInputCell> = sudt_inputs.iter().map(|c| MldsaInputCell {
        capacity: c.capacity,
        lock_code_hash: MLDSA_CODE_HASH.to_string(),
        lock_hash_type: MLDSA_HASH_TYPE.to_string(),
        lock_args: pq_args.clone(),
        type_code_hash: SUDT_CODE_HASH.to_string(),
        type_hash_type: "type".to_string(),
        type_args: type_args.clone(),
        data: u128_le_hex(c.amount),
    }).chain(selected_ckb.iter().map(|c| MldsaInputCell {
        capacity: c.capacity,
        lock_code_hash: MLDSA_CODE_HASH.to_string(),
        lock_hash_type: MLDSA_HASH_TYPE.to_string(),
        lock_args: pq_args.clone(),
        type_code_hash: String::new(),
        type_hash_type: String::new(),
        type_args: String::new(),
        data: String::new(),
    })).collect();

    let witness0 = sign_ckb_mldsa65(
        built.tx_hash_hex.clone(), input_cells, pq.private_key_hex.clone(), pq.public_key_hex.clone(),
    ).map_err(|e| format!("sign_ckb_mldsa65: {e}"))?;

    let tx_json = build_tx_json(
        &input_refs, &outputs, &witness0,
        &[(MLDSA_DEP_TX_HASH, MLDSA_DEP_INDEX, "code"), (SUDT_DEP_TX_HASH, SUDT_DEP_INDEX, "code")],
        &[],
    );
    match send_transaction(&tx_json) {
        Ok(hash) => {
            println!("\n✅ accepted by pool — tx_hash: {hash}");
            println!("   https://pudge.explorer.nervos.org/transaction/{hash}");
            Ok(())
        }
        Err(e) => Err(format!("broadcast rejected: {e}")),
    }
}
```

> If `SudtCell::as_cell` reconstructs a `Cell` whose `data` is empty (it is built for capacity bookkeeping), that's fine — the `data` used for the digest comes from the `MldsaInputCell` above (`u128_le_hex(c.amount)`), not from `all_cells`. Confirm `build_tx_json` writes `outputs_data` from `outputs`, not from inputs (it does, mirroring `cmd_send_sudt`).

- [ ] **Step 3: Verify the harness builds**

Run: `cd rust-core && cargo build --example pq_testnet`
Expected: Finished — compiles (pre-existing uniffi warning only).

- [ ] **Step 4: Commit**

```bash
git add rust-core/examples/pq_testnet.rs
git commit -m "test(sudt): add send-sudt-from-pq harness command"
```

---

## Task 5: On-chain verification + docs (Pudge testnet)

**Files:**
- Modify: `PLAN.md` (the sUDT-from-PQ line, currently `:343`)

**Prerequisite:** a funded testnet hybrid wallet seed (classic lock holds CKB + sUDT; PQ lock holds ≥ ~410 CKB free to hold an sUDT cell + sUDT change + fee). Use `CKB_PRIVKEY` / the harness's seed convention as the other commands do.

- [ ] **Step 1: Confirm balances**

Run (from `rust-core`):
```bash
cargo run --example pq_testnet -- secp-balance <seed>
cargo run --example pq_testnet -- sudt-balance <seed>
cargo run --example pq_testnet -- balance <seed>   # PQ-lock CKB
```
Expected: classic lock shows CKB + sUDT; PQ lock shows enough CKB. If the classic lock has no sUDT, mint first: `cargo run --example pq_testnet -- mint-sudt <seed> 100000`.

- [ ] **Step 2: Fund the PQ lock with sUDT via the existing (working) Classic→PQ path**

Run: `cargo run --example pq_testnet -- send-sudt <seed> <PQ_bech32m_address> 40000`
(Get the PQ address from `cargo run --example pq_testnet -- derive <seed>` or the app.)
Expected: `✅ accepted by pool`. Wait ~60s, then `sudt-balance` / a fresh `get_sudt_cells` confirms the sUDT cell now sits on the PQ lock.

- [ ] **Step 3: The actual test — spend sUDT FROM the PQ lock**

Run: `cargo run --example pq_testnet -- send-sudt-from-pq <seed> <CLASSIC_bech32m_address> 15000`
Expected: `✅ accepted by pool — tx_hash: 0x…`.
- If `Inputs[0].Lock ... error code 46 (SignatureVerifyFailed)` → digest still wrong (type script not committed) — revisit Task 1.
- If `PoolRejectedTransactionByMinFeeRate` → fee not reserved — revisit Task 3 Step 2.

- [ ] **Step 4: Verify conservation on-chain**

Open the tx hash on `https://pudge.explorer.nervos.org`. Confirm: sUDT inputs (40000) = recipient (15000) + change (25000); the 25000 sUDT change cell is locked by the PQ lock; CKB change returned to the PQ lock.

- [ ] **Step 5: Update PLAN.md**

Replace the sUDT-from-PQ line (currently `:343`, `* ⏳ sUDT sends from a PQ sub-account — currently refused...`):

```markdown
* ✅ **sUDT sends from a PQ sub-account — fixed + verified on-chain (2026-06-18)** — the ML-DSA v2 CighashAll digest now commits each input's type script (`MldsaInputCell` gained an optional type script), and `sendToken` signs via the same `resolveSigningContext` as `sendCkb` with the sUDT cell-dep appended. **Live verified:** PQ→Classic sUDT send `0x<hash>` (pool-accepted; conservation 40000 = 15000 + 25000; change returned to the PQ lock).
```

- [ ] **Step 6: Commit**

```bash
git add PLAN.md
git commit -m "docs: sUDT-from-PQ verified on-chain"
```

---

## Self-Review Notes

- **Spec coverage:** Part 1 (Rust struct + digest) → Task 1; binding regen → Task 2; Part 2 (Kotlin `sendToken` reuse of `resolveSigningContext` + dual dep + ordered type-bearing inputs + stale-comment fix) → Task 3; Part 3 (Rust digest test → Task 1 Step 1; harness command → Task 4; on-chain sequence + PLAN.md → Task 5). All covered.
- **Type consistency:** `MldsaInputCell` has the same 8 fields everywhere (Rust struct Task 1; Kotlin `MldsaInputCell(..., typeCodeHash, typeHashType, typeArgs, data)` Tasks 2/3). `SigningContext.signWitness0(BuiltTransaction, List<MldsaInputCell>)` matches its definition. `JsonCellDep(txHash, index, depType)` matches `:1474`. Harness consts/fns all verified present in `pq_testnet.rs`.
- **Order invariant:** `mldsaInputs` (Task 3 Step 4) and `input_cells` (Task 4 Step 2) are both built sUDT-first-then-CKB, matching `allInputs`/`all_cells`.
- **No regression:** Task 1 leaves pure-CKB serialization byte-identical (`type_code_hash == "" ⇒ None`); Task 2 passes empty type fields in `sendCkb`; secp `signWitness0` ignores input contents.
- **Owner type_args subtlety:** captured in Task 4 (classic owner lock hash even when spending from PQ); the app path already receives the correct `tokenTypeScript`.
