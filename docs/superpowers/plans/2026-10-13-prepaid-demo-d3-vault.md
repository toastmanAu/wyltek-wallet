# Prepaid Cards Page (demo tier D3, Vault) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give the Blackbox Vault app one page that issues, loads, voids and cashes out NTAG prepaid cards over the phone's NFC with a real testnet reserve cell per card, an append-only audit log, and the settlement endpoints the POS will sync to.

**Architecture:** rust-core owns the protocol (slot selection, record sealing, reserve arithmetic, `reserve_id`, journal verifier) behind seven uniffi exports; Kotlin owns NFC page I/O, two new Room tables in the encrypted agent database, and transaction build/sign/broadcast on the existing secp path. A `PrepaidService` with injected ports (store, chain, signer, protocol, tag) holds every flow and is unit-tested on the host JVM with fakes; the Android layer is thin adapters plus Compose screens.

**Tech Stack:** Rust 2021 + uniffi 0.31.1 (`rust-core`), Kotlin + Jetpack Compose (material3, compose BOM 2025.04.00), Room 2.6.1 over SQLCipher, kotlinx.serialization, NanoHTTPD router (`routeAgentRequest`), Android NFC reader mode (`NfcAdapter`, `MifareUltralight`), JUnit 4 + kotlinx-coroutines-test on the host JVM, androidx.test for instrumented tests.

**Spec:** `~/blackbox-pos/docs/superpowers/specs/2026-10-13-prepaid-demo-d3-vault-design.md` (the "D3 spec"), which specialises `~/blackbox-pos/docs/superpowers/specs/2026-10-09-blackbox-prepaid-demo-tier-design.md` (the "demo-tier spec"). Executors read both; the D3 spec wins where they differ.

## Global Constraints

- Repo: `~/wyltek-wallet`, branch `feat/prepaid-demo-d3` from `main` (D1 at 92845c5). Commit format `<type>(<scope>): <description>`; every commit message ends with `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`.
- Never edit `rust-core/src/prepaid/{cmac,record,journal,vectors}.rs` or `rust-core/tests/fixtures/prepaid_demo_vectors.txt`: they are the D1 contract pinned by SHA-256 `90937c92e909acf82a1b06c3f79e18840fd6e6d3c6d7bb6b9f27704fa98e60d8` on both repos.
- Constants (D3 spec §2.6): `UNIT_SHANNONS = 100_000`; `FLOOR_SHANNONS = 6_100_000_000` (61 CKB); `CUSTOMER_MIN_SHANNONS = 6_100_000_000`; `LOAD_MAX_UNITS = 1_000_000` (1,000 CKB); `MAX_ACTIVE_CARDS = 64`; commit poll budget 90 s at 3 s; wallet change output minimum 81 CKB (`sendCkb`'s existing `minCellCapacity`).
- Invariant kept by every transaction this plan builds: `reserve cell capacity == FLOOR_SHANNONS + index.balanceUnits × UNIT_SHANNONS`. The planner refuses when it does not hold (`BalanceMismatch`).
- Deviation from demo-tier spec §6.3, recorded here and in the D3 spec update (Task 16): a settled card whose net is **negative** (refunds exceed debits) also gets a successor cell, of `capacity + |net| × UNIT`, so the invariant holds; only a net of exactly 0 is index-only.
- Deviation from D3 spec §6 wire format (Task 16 updates the spec): `POST /v1/prepaid/journal` takes JSON `{"entries_hex":"<hex>"}` with the token in the `x-device-token` header (as `/relay/ckb` does), because `AgentHttpServer` hands `routeAgentRequest` a `String` body and the router must stay testable without NanoHTTPD. `GET /v1/prepaid/cards` takes the same header.
- `reserve_id` = ckb-default-hash blake2b-256 over `tx_hash(32) || index u32 LE`; `merchant_id` = first 8 bytes of blake2b-256 over the molecule `Script` of the funding lock.
- Room column names follow the repo's camelCase convention (`tokenId`, `createdAt`), not the spec's snake_case.
- The page runs only for a **testnet** classic (secp) account. Mainnet, no classic account, or no NFC: the page opens read-only with the reason.
- Every prepaid surface shows the text `DEMO` (demo-tier spec §1).
- Audit rows are never updated or deleted. `PrepaidDao` has no `@Update`/`@Delete` and no `UPDATE`/`DELETE` query touching `prepaid_events`.
- The uniffi bindings are **checked in and duplicated** at `wallet-core/src/main/java/com/wyltek/wallet/core/native/wyltekwalletcore.kt` and `app/src/main/java/com/wyltek/wallet/core/native/wyltekwalletcore.kt`; the `.so` at `{wallet-core,app}/src/main/jniLibs/arm64-v8a/libwyltekwalletcore.so`. Both copies are regenerated together (Task 3).
- Serial/NFC/device steps need the sandbox off; nothing is pushed without Phill saying so.

## Review Focus

1. **A wallet whose pure-CKB cells include other cards' reserve cells.** Input selection for any prepaid transaction must exclude every `currentOutpoint` in the index, or a load could spend a different card's reserve as a fee input. Test added in Task 10 (`load_never_spends_another_cards_reserve`).
2. **A different tag presented at resume.** A pending credit for UID X must never be written to a tag with UID Y even if Y is blank. Test in Task 10 (`resume_refuses_a_different_uid`).
3. **Two actions started concurrently** (double tap, or a settlement arriving while a load is in flight). The service holds one mutex; the second caller gets `Failed("busy")` and writes no rows. Test in Task 10 (`second_action_while_busy_is_refused_without_rows`).
4. **Journal entries for a card that is `closed` or still `issuing`.** Both must verify as `unknown_card`; a closed card's reserve is spent and an issuing card's tag was never written. Test in Task 12 (`closed_and_issuing_cards_are_unknown_to_settlement`).
5. **Amount text with decimals, blanks, negatives or more than three decimal places.** `parseCkbToUnits` must return null for anything that does not map exactly to milli-CKB units, before any row is written. Test in Task 14 (`parseCkbToUnits_rejects_bad_input`).

---

## File structure

**rust-core (new):**
- `rust-core/src/prepaid/reserve.rs` — constants, `reserve_id`, `merchant_id`, `plan` (pure).
- `rust-core/src/prepaid/verifier.rs` — `verify_batch` (pure).
- `rust-core/src/prepaid/ffi.rs` — uniffi records/enums/exports wrapping record.rs, reserve.rs, verifier.rs.
- `rust-core/src/agent/token.rs` (modify) — `authorize_op` export; `rust-core/src/agent/types.rs` (modify) — `Scope::PrepaidSettle`.

**app (new package `com.wyltek.wallet.prepaid`):**
- `db/PrepaidEntities.kt`, `db/PrepaidDao.kt` — Room; `agent/db/AgentDatabase.kt` (modify) — v4 migration.
- `PrepaidStore.kt` — interface + `RoomPrepaidStore`.
- `PrepaidKeys.kt` — `K_debit` in the secure store.
- `TapClassifier.kt` — pure classification (D3 spec §5.1).
- `PrepaidTxPlanner.kt`, `CkbTxJson.kt` — pure input selection, outputs, JSON.
- `Ports.kt` — `ChainPort`, `SignerPort`, `Protocol`, `TagPort` interfaces + `Phase`, `ActionOutcome`.
- `NativeProtocol.kt`, `RpcChainPort.kt`, `SecpSigner.kt` — Android/native adapters.
- `nfc/SlotPages.kt`, `nfc/MifareTagPort.kt`, `nfc/NfcReaderMode.kt` — NTAG I/O and reader mode.
- `PrepaidService.kt` — all flows (issue, load, resume, recover, void, cash-out, revoke, reinstate, repair, adopt, settle).
- `PrepaidAmount.kt` — `parseCkbToUnits`, `formatUnits`.
- `ui/PrepaidViewModel.kt`, `ui/PrepaidScreen.kt`, `ui/PrepaidTapSheet.kt`, `ui/PrepaidCardDetailScreen.kt`, `ui/PrepaidAuditScreen.kt`, `ui/AuditCsv.kt`.
- Modified: `agent/AgentGateway.kt`, `agent/server/AgentDispatchPort.kt`, `agent/server/AgentDtos.kt`, `agent/server/AgentRouter.kt`, `agent/provisioning/AgentProvisioning.kt`, `agent/ui/AgentViewModel.kt`, `ui/screens/AgentScreen.kt`, `ui/screens/SettingsScreen.kt`, `ui/navigation/AppNavigation.kt`, `AndroidManifest.xml`, `app/build.gradle.kts`.

**Tests:** `rust-core` unit tests inline; `app/src/test/java/com/wyltek/wallet/prepaid/*Test.kt` (JVM, fakes in `app/src/test/java/com/wyltek/wallet/prepaid/fakes/`); `app/src/androidTest/java/com/wyltek/wallet/prepaid/PrepaidDaoTest.kt`.

---

### Task 0: Branch

- [ ] **Step 1: Create the branch**

```bash
cd ~/wyltek-wallet && git status --short | head && git checkout -b feat/prepaid-demo-d3 main && git log --oneline -1
```
Expected: clean tree, `92845c5` as the base.

---

### Task 1: rust-core `prepaid::reserve` — identifiers and reserve arithmetic

**Files:**
- Create: `rust-core/src/prepaid/reserve.rs`
- Modify: `rust-core/src/prepaid/mod.rs` (add `pub mod reserve;`)

**Interfaces:**
- Consumes: `crate::molecule::ScriptSer { code_hash: [u8;32], hash_type: u8, args: Vec<u8> }.to_bytes()`.
- Produces: `UNIT_SHANNONS`, `FLOOR_SHANNONS`, `CUSTOMER_MIN_SHANNONS`, `LOAD_MAX_UNITS`, `MAX_ACTIVE_CARDS`; `reserve_id(&[u8;32], u32) -> [u8;32]`; `merchant_id(&[u8;32], u8, &[u8]) -> [u8;8]`; `enum Action { Issue, Load, Void, CashOut }`; `struct Plan { successor_capacity: u64, customer_shannons: u64, balance_after: i32 }`; `enum PlanError { AmountNotPositive, OverCap, Overflow, BelowFloor, CustomerBelowMinimum, BalanceMismatch }`; `plan(Action, i32, u64, i32) -> Result<Plan, PlanError>`.

- [ ] **Step 1: Write the failing tests**

Create `rust-core/src/prepaid/reserve.rs` with only the test module first:

```rust
//! Reserve identifiers and arithmetic for the demo-tier prepaid card (D3 spec §2.6, §2.9, §2.10, §4).
//! Pure; the uniffi surface is in `ffi.rs`.

#[cfg(test)]
mod tests {
    use super::*;

    fn tx_hash() -> [u8; 32] { core::array::from_fn(|i| i as u8) }

    #[test]
    fn reserve_id_is_blake2b_over_outpoint_molecule() {
        // ckb-default-hash blake2b-256 over tx_hash || index LE, pinned once by hand (python below) and never changed:
        // python3 -c "import hashlib;print(hashlib.blake2b(bytes(range(32))+(1).to_bytes(4,'little'),digest_size=32,person=b'ckb-default-hash').hexdigest())"
        let id = reserve_id(&tx_hash(), 1);
        assert_eq!(hex::encode(id), "d0a4145cf09b6b16800d77952806fd37522e263271b8ac660edae69fade914d6");
        assert_ne!(reserve_id(&tx_hash(), 0), reserve_id(&tx_hash(), 1));
    }

    #[test]
    fn merchant_id_is_first_8_bytes_of_script_hash() {
        // secp256k1_blake160_sighash_all testnet lock with a 20-byte zero args:
        // script hash via ckb-cli / explorer for args 0x00*20 (same value on every network, code_hash is the system one)
        let code: [u8; 32] = hex::decode("9bd7e06f3ecf4be0f2fcd2188b23f1b9fcc88e5d4b65a8637b17723bbda3cce8").unwrap().try_into().unwrap();
        let m = merchant_id(&code, 1, &[0u8; 20]);
        let full = {
            let s = crate::molecule::ScriptSer { code_hash: code, hash_type: 1, args: vec![0u8; 20] }.to_bytes();
            ckb_blake2b(&s)
        };
        assert_eq!(&m[..], &full[..8]);
        assert_ne!(merchant_id(&code, 1, &[0u8; 20]), merchant_id(&code, 1, &[1u8; 20]));
    }

    #[test]
    fn issue_plan_is_floor_plus_amount() {
        let p = plan(Action::Issue, 0, 0, 250_000).unwrap(); // 250 CKB
        assert_eq!(p.successor_capacity, FLOOR_SHANNONS + 250_000 * UNIT_SHANNONS);
        assert_eq!(p.customer_shannons, 0);
        assert_eq!(p.balance_after, 250_000);
    }

    #[test]
    fn issue_and_load_refuse_bad_amounts() {
        assert_eq!(plan(Action::Issue, 0, 0, 0).unwrap_err(), PlanError::AmountNotPositive);
        assert_eq!(plan(Action::Issue, 0, 0, -5).unwrap_err(), PlanError::AmountNotPositive);
        assert_eq!(plan(Action::Issue, 0, 0, LOAD_MAX_UNITS + 1).unwrap_err(), PlanError::OverCap);
        assert!(plan(Action::Issue, 0, 0, LOAD_MAX_UNITS).is_ok());
        let cap = FLOOR_SHANNONS + (i32::MAX as u64 - 10) * UNIT_SHANNONS;
        assert_eq!(plan(Action::Load, i32::MAX - 10, cap, 100).unwrap_err(), PlanError::Overflow);
    }

    #[test]
    fn load_plan_requires_capacity_to_match_index_balance() {
        let cap = FLOOR_SHANNONS + 100_000 * UNIT_SHANNONS;
        let p = plan(Action::Load, 100_000, cap, 50_000).unwrap();
        assert_eq!(p.successor_capacity, cap + 50_000 * UNIT_SHANNONS);
        assert_eq!(p.balance_after, 150_000);
        assert_eq!(plan(Action::Load, 100_000, cap + 1, 50_000).unwrap_err(), PlanError::BalanceMismatch);
        assert_eq!(plan(Action::Load, 100_000, FLOOR_SHANNONS - 1, 50_000).unwrap_err(), PlanError::BelowFloor);
    }

    #[test]
    fn void_plan_has_no_outputs_and_zero_balance() {
        let cap = FLOOR_SHANNONS + 12_345 * UNIT_SHANNONS;
        let p = plan(Action::Void, 12_345, cap, 0).unwrap();
        assert_eq!(p, Plan { successor_capacity: 0, customer_shannons: 0, balance_after: 0 });
        assert_eq!(plan(Action::Void, 0, FLOOR_SHANNONS - 1, 0).unwrap_err(), PlanError::BelowFloor);
    }

    #[test]
    fn cash_out_pays_balance_to_customer_at_or_above_minimum() {
        let bal = 61_000; // exactly 61 CKB
        let cap = FLOOR_SHANNONS + bal as u64 * UNIT_SHANNONS;
        let p = plan(Action::CashOut, bal, cap, 0).unwrap();
        assert_eq!(p, Plan { successor_capacity: 0, customer_shannons: CUSTOMER_MIN_SHANNONS, balance_after: 0 });
        let bal2 = 60_999;
        let cap2 = FLOOR_SHANNONS + bal2 as u64 * UNIT_SHANNONS;
        assert_eq!(plan(Action::CashOut, bal2, cap2, 0).unwrap_err(), PlanError::CustomerBelowMinimum);
        assert_eq!(plan(Action::CashOut, 0, FLOOR_SHANNONS, 0).unwrap_err(), PlanError::AmountNotPositive);
        assert_eq!(plan(Action::CashOut, bal, cap + 5, 0).unwrap_err(), PlanError::BalanceMismatch);
    }
}
```

Add `pub mod reserve;` to `rust-core/src/prepaid/mod.rs`.

- [ ] **Step 2: Run to verify failure**

Run: `cd ~/wyltek-wallet/rust-core && cargo test prepaid::reserve 2>&1 | tail -15`
Expected: compile errors (`reserve_id`, `plan`, `Action` not found).

- [ ] **Step 3: Implement**

Insert above the test module:

```rust
use crate::molecule::ScriptSer;
use blake2b_ref::Blake2bBuilder;

/// 1 unit = 1 milli-CKB (demo-tier spec §2.8).
pub const UNIT_SHANNONS: u64 = 100_000;
/// secp256k1_blake160 minimum cell, funded by the merchant at issue and returned at close.
pub const FLOOR_SHANNONS: u64 = 61 * 100_000_000;
/// A cash-out output is a plain secp cell for the customer: same minimum.
pub const CUSTOMER_MIN_SHANNONS: u64 = 61 * 100_000_000;
/// 1,000 CKB per issue or load (D3 spec §2.6).
pub const LOAD_MAX_UNITS: i32 = 1_000 * 1_000;
/// Demo-tier spec §2.7.
pub const MAX_ACTIVE_CARDS: u32 = 64;

pub(crate) fn ckb_blake2b(data: &[u8]) -> [u8; 32] {
    let mut h = Blake2bBuilder::new(32).personal(b"ckb-default-hash").build();
    h.update(data);
    let mut out = [0u8; 32];
    h.finalize(&mut out);
    out
}

/// D3 spec §2.9: blake2b-256 over the 36-byte molecule OutPoint (tx_hash || index u32 LE).
pub fn reserve_id(tx_hash: &[u8; 32], index: u32) -> [u8; 32] {
    let mut m = [0u8; 36];
    m[..32].copy_from_slice(tx_hash);
    m[32..].copy_from_slice(&index.to_le_bytes());
    ckb_blake2b(&m)
}

/// Main spec §4.1: first 8 bytes of blake2b-256 of the merchant lock script (molecule Script).
pub fn merchant_id(code_hash: &[u8; 32], hash_type: u8, args: &[u8]) -> [u8; 8] {
    let bytes = ScriptSer { code_hash: *code_hash, hash_type, args: args.to_vec() }.to_bytes();
    ckb_blake2b(&bytes)[..8].try_into().unwrap()
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Action { Issue, Load, Void, CashOut }

/// Fixed outputs of one reserve transaction. The merchant's change output is computed by the
/// caller (total inputs − these − fee); `successor_capacity == 0` means no successor cell.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Plan { pub successor_capacity: u64, pub customer_shannons: u64, pub balance_after: i32 }

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum PlanError { AmountNotPositive, OverCap, Overflow, BelowFloor, CustomerBelowMinimum, BalanceMismatch }

fn expected_capacity(balance_units: i32) -> Result<u64, PlanError> {
    if balance_units < 0 { return Err(PlanError::BalanceMismatch); }
    (balance_units as u64).checked_mul(UNIT_SHANNONS).and_then(|v| v.checked_add(FLOOR_SHANNONS)).ok_or(PlanError::Overflow)
}

/// The invariant every reserve transaction keeps: capacity == FLOOR + balance × UNIT.
fn check_invariant(balance_units: i32, capacity: u64) -> Result<(), PlanError> {
    if capacity < FLOOR_SHANNONS { return Err(PlanError::BelowFloor); }
    if capacity != expected_capacity(balance_units)? { return Err(PlanError::BalanceMismatch); }
    Ok(())
}

pub fn plan(action: Action, balance_units: i32, capacity_shannons: u64, amount_units: i32) -> Result<Plan, PlanError> {
    match action {
        Action::Issue => {
            if amount_units <= 0 { return Err(PlanError::AmountNotPositive); }
            if amount_units > LOAD_MAX_UNITS { return Err(PlanError::OverCap); }
            Ok(Plan { successor_capacity: expected_capacity(amount_units)?, customer_shannons: 0, balance_after: amount_units })
        }
        Action::Load => {
            if amount_units <= 0 { return Err(PlanError::AmountNotPositive); }
            if amount_units > LOAD_MAX_UNITS { return Err(PlanError::OverCap); }
            check_invariant(balance_units, capacity_shannons)?;
            let after = balance_units.checked_add(amount_units).ok_or(PlanError::Overflow)?;
            Ok(Plan { successor_capacity: expected_capacity(after)?, customer_shannons: 0, balance_after: after })
        }
        Action::Void => {
            if capacity_shannons < FLOOR_SHANNONS { return Err(PlanError::BelowFloor); }
            Ok(Plan { successor_capacity: 0, customer_shannons: 0, balance_after: 0 })
        }
        Action::CashOut => {
            if balance_units <= 0 { return Err(PlanError::AmountNotPositive); }
            check_invariant(balance_units, capacity_shannons)?;
            let customer = balance_units as u64 * UNIT_SHANNONS;
            if customer < CUSTOMER_MIN_SHANNONS { return Err(PlanError::CustomerBelowMinimum); }
            Ok(Plan { successor_capacity: 0, customer_shannons: customer, balance_after: 0 })
        }
    }
}
```

The `reserve_id` vector in the test was produced by the python one-liner in its comment; if it ever fails, the encoding changed, not the vector.

- [ ] **Step 4: Run to verify pass**

Run: `cd ~/wyltek-wallet/rust-core && cargo test prepaid::reserve 2>&1 | tail -15`
Expected: 7 passed. Then `cargo test 2>&1 | grep -E "test result|FAILED"` — every suite green, including `prepaid_vectors_pinned`.

- [ ] **Step 5: Mutation probe** — in `check_invariant` change `!=` to `<`, run `cargo test prepaid::reserve`; `load_plan_requires_capacity_to_match_index_balance` and `cash_out_pays_balance_to_customer_at_or_above_minimum` must go red. Restore. Change `customer < CUSTOMER_MIN_SHANNONS` to `<=`; the 61 CKB-exact case must go red. Restore.

- [ ] **Step 6: Commit**

```bash
cd ~/wyltek-wallet && git add rust-core/src/prepaid/reserve.rs rust-core/src/prepaid/mod.rs && git commit -m "feat(prepaid): reserve arithmetic, reserve_id and merchant_id (D3)

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 2: rust-core `prepaid::verifier` — journal batch verifier

**Files:**
- Create: `rust-core/src/prepaid/verifier.rs`
- Modify: `rust-core/src/prepaid/mod.rs` (add `pub mod verifier;`)

**Interfaces:**
- Consumes: `journal::{ENTRY_SIZE, decode, verify, attest, JournalEntry, KIND_DEBIT, KIND_REFUND}`.
- Produces: `struct CardState { reserve_id: [u8;32], balance_units: i32, settled_counter: u32, revoked: bool }`; `enum Verdict { Accepted, Duplicate, BadMac, UnknownCard, StaleCounter, OverBalance }`; `struct CardNet { reserve_id: [u8;32], net_units: i64, max_tmc: u32, accepted: u32 }`; `struct BatchResult { verdicts: Vec<Verdict>, nets: Vec<CardNet> }`; `enum BatchError { BadLength }`; `verify_batch(&[u8;16], &[u8], &[CardState]) -> Result<BatchResult, BatchError>`.

- [ ] **Step 1: Write the failing tests**

```rust
//! Settlement verifier for version-0x02 (terminal-attested) journal entries. D3 spec §6, demo-tier §6.3.
//! Verdict order per entry: length → MAC (covers decode + sign rule) → known & not revoked →
//! tmc above settled → tmc above this batch's running max for the card → cumulative net within balance.

#[cfg(test)]
mod tests {
    use super::*;
    use crate::prepaid::journal::{attest, JournalEntry, KIND_DEBIT, KIND_REFUND};

    fn key() -> [u8; 16] { core::array::from_fn(|i| i as u8) }
    fn rid(b: u8) -> [u8; 32] { [b; 32] }
    fn entry(rid_b: u8, tmc: u32, amount: i32) -> Vec<u8> {
        let mut e = JournalEntry { version: 0x02, kind: if amount < 0 { KIND_REFUND } else { KIND_DEBIT }, terminal_id: 7,
            merchant_id: [0x11; 8], uid: [4, 1, 2, 3, 4, 5, 6], reserve_id: rid(rid_b), tmc, amount_units: amount,
            balance_after: 0, ts: 0, tmv: [0; 8], sale_ref: [0; 8] };
        attest(&key(), &mut e).to_vec()
    }
    fn card(b: u8, balance: i32, settled: u32, revoked: bool) -> CardState {
        CardState { reserve_id: rid(b), balance_units: balance, settled_counter: settled, revoked }
    }
    fn cat(v: &[Vec<u8>]) -> Vec<u8> { v.concat() }

    #[test]
    fn good_entries_net_per_card() {
        let batch = cat(&[entry(1, 3, 1500), entry(1, 4, -500), entry(2, 1, 700)]);
        let r = verify_batch(&key(), &batch, &[card(1, 100_000, 2, false), card(2, 100_000, 0, false)]).unwrap();
        assert_eq!(r.verdicts, vec![Verdict::Accepted; 3]);
        assert_eq!(r.nets.len(), 2);
        let n1 = r.nets.iter().find(|n| n.reserve_id == rid(1)).unwrap();
        assert_eq!((n1.net_units, n1.max_tmc, n1.accepted), (1000, 4, 2));
        let n2 = r.nets.iter().find(|n| n.reserve_id == rid(2)).unwrap();
        assert_eq!((n2.net_units, n2.max_tmc, n2.accepted), (700, 1, 1));
    }

    #[test]
    fn bad_mac_wrong_key_and_tamper() {
        let mut e = entry(1, 3, 1500);
        e[56] ^= 1;
        let r = verify_batch(&key(), &e, &[card(1, 100_000, 0, false)]).unwrap();
        assert_eq!(r.verdicts, vec![Verdict::BadMac]);
        assert!(r.nets.is_empty());
        let mut k2 = key(); k2[0] ^= 1;
        let r = verify_batch(&k2, &entry(1, 3, 1500), &[card(1, 100_000, 0, false)]).unwrap();
        assert_eq!(r.verdicts, vec![Verdict::BadMac]);
    }

    #[test]
    fn bad_mac_wins_over_unknown_card() {
        let mut e = entry(9, 3, 1500);
        e[56] ^= 1;
        let r = verify_batch(&key(), &e, &[]).unwrap();
        assert_eq!(r.verdicts, vec![Verdict::BadMac]);
    }

    #[test]
    fn unknown_and_revoked_cards() {
        let r = verify_batch(&key(), &entry(9, 1, 100), &[card(1, 100_000, 0, false)]).unwrap();
        assert_eq!(r.verdicts, vec![Verdict::UnknownCard]);
        let r = verify_batch(&key(), &entry(1, 1, 100), &[card(1, 100_000, 0, true)]).unwrap();
        assert_eq!(r.verdicts, vec![Verdict::UnknownCard], "revoked must read as unknown");
    }

    #[test]
    fn tmc_at_or_below_settled_is_duplicate() {
        let batch = cat(&[entry(1, 5, 100), entry(1, 4, 100), entry(1, 6, 100)]);
        let r = verify_batch(&key(), &batch, &[card(1, 100_000, 5, false)]).unwrap();
        assert_eq!(r.verdicts, vec![Verdict::Duplicate, Verdict::Duplicate, Verdict::Accepted]);
        assert_eq!(r.nets[0].net_units, 100);
    }

    #[test]
    fn out_of_order_tmc_within_batch_is_stale() {
        let batch = cat(&[entry(1, 7, 100), entry(1, 6, 100), entry(1, 7, 100), entry(1, 8, 100)]);
        let r = verify_batch(&key(), &batch, &[card(1, 100_000, 0, false)]).unwrap();
        assert_eq!(r.verdicts, vec![Verdict::Accepted, Verdict::StaleCounter, Verdict::StaleCounter, Verdict::Accepted]);
        assert_eq!((r.nets[0].net_units, r.nets[0].max_tmc, r.nets[0].accepted), (200, 8, 2));
    }

    #[test]
    fn cumulative_net_above_balance_is_over_balance() {
        let batch = cat(&[entry(1, 1, 60_000), entry(1, 2, 50_000), entry(1, 3, -20_000), entry(1, 4, 50_000)]);
        let r = verify_batch(&key(), &batch, &[card(1, 100_000, 0, false)]).unwrap();
        // 60k ok (60k), +50k would be 110k > 100k → OverBalance and NOT counted, −20k → 40k, +50k → 90k ok
        assert_eq!(r.verdicts, vec![Verdict::Accepted, Verdict::OverBalance, Verdict::Accepted, Verdict::Accepted]);
        assert_eq!((r.nets[0].net_units, r.nets[0].max_tmc), (90_000, 4));
    }

    #[test]
    fn length_not_multiple_of_96_is_an_error() {
        assert_eq!(verify_batch(&key(), &[0u8; 95], &[]).unwrap_err(), BatchError::BadLength);
        let r = verify_batch(&key(), &[], &[]).unwrap();
        assert!(r.verdicts.is_empty() && r.nets.is_empty());
    }

    #[test]
    fn refund_only_card_has_negative_net() {
        let r = verify_batch(&key(), &entry(1, 2, -300), &[card(1, 1_000, 1, false)]).unwrap();
        assert_eq!(r.verdicts, vec![Verdict::Accepted]);
        assert_eq!(r.nets[0].net_units, -300);
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `cd ~/wyltek-wallet/rust-core && cargo test prepaid::verifier 2>&1 | tail -8`
Expected: compile errors.

- [ ] **Step 3: Implement** (above the tests)

```rust
use super::journal::{self, ENTRY_SIZE};
use std::collections::HashMap;

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct CardState { pub reserve_id: [u8; 32], pub balance_units: i32, pub settled_counter: u32, pub revoked: bool }

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Verdict { Accepted, Duplicate, BadMac, UnknownCard, StaleCounter, OverBalance }

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct CardNet { pub reserve_id: [u8; 32], pub net_units: i64, pub max_tmc: u32, pub accepted: u32 }

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct BatchResult { pub verdicts: Vec<Verdict>, pub nets: Vec<CardNet> }

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum BatchError { BadLength }

struct Running { net: i64, max_tmc: Option<u32>, accepted: u32 }

pub fn verify_batch(key: &[u8; 16], entries: &[u8], cards: &[CardState]) -> Result<BatchResult, BatchError> {
    if entries.len() % ENTRY_SIZE != 0 { return Err(BatchError::BadLength); }
    let index: HashMap<[u8; 32], &CardState> = cards.iter().map(|c| (c.reserve_id, c)).collect();
    let mut running: HashMap<[u8; 32], Running> = HashMap::new();
    let mut order: Vec<[u8; 32]> = Vec::new();
    let mut verdicts = Vec::with_capacity(entries.len() / ENTRY_SIZE);

    for chunk in entries.chunks_exact(ENTRY_SIZE) {
        let raw: &[u8; ENTRY_SIZE] = chunk.try_into().unwrap();
        if !journal::verify(key, raw) { verdicts.push(Verdict::BadMac); continue; }
        let e = journal::decode(raw).expect("verify implies decode");
        let card = match index.get(&e.reserve_id) { Some(c) if !c.revoked => *c, _ => { verdicts.push(Verdict::UnknownCard); continue; } };
        if e.tmc <= card.settled_counter { verdicts.push(Verdict::Duplicate); continue; }
        let run = running.entry(e.reserve_id).or_insert_with(|| { order.push(e.reserve_id); Running { net: 0, max_tmc: None, accepted: 0 } });
        if run.max_tmc.map_or(false, |m| e.tmc <= m) { verdicts.push(Verdict::StaleCounter); continue; }
        let proposed = run.net + e.amount_units as i64;
        if proposed > card.balance_units as i64 { verdicts.push(Verdict::OverBalance); continue; }
        run.net = proposed;
        run.max_tmc = Some(e.tmc);
        run.accepted += 1;
        verdicts.push(Verdict::Accepted);
    }

    let nets = order.into_iter().filter_map(|rid| {
        let r = &running[&rid];
        (r.accepted > 0).then(|| CardNet { reserve_id: rid, net_units: r.net, max_tmc: r.max_tmc.unwrap(), accepted: r.accepted })
    }).collect();
    Ok(BatchResult { verdicts, nets })
}
```

- [ ] **Step 4: Run to verify pass**

Run: `cargo test prepaid::verifier 2>&1 | tail -15` → 9 passed. Then full `cargo test`.

- [ ] **Step 5: Mutation probes** (each: edit, run `cargo test prepaid::verifier`, confirm the named test is the one that fails, restore)
  - Delete `if !c.revoked` → `unknown_and_revoked_cards` red.
  - Change `e.tmc <= card.settled_counter` to `<` → `tmc_at_or_below_settled_is_duplicate` red.
  - Delete the `StaleCounter` branch → `out_of_order_tmc_within_batch_is_stale` red.
  - Change `proposed > card.balance_units` to `>=` → `cumulative_net_above_balance_is_over_balance` still passes? It must: 90k < 100k. Instead change to `proposed > i64::MAX` (disable) → red. Restore.
  - Move the `journal::verify` check after the card lookup → `bad_mac_wins_over_unknown_card` red.

- [ ] **Step 6: Commit**

```bash
git add rust-core/src/prepaid/verifier.rs rust-core/src/prepaid/mod.rs && git commit -m "feat(prepaid): terminal-attested journal batch verifier (D3)

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 3: uniffi surface (`prepaid::ffi`), `Scope::PrepaidSettle`, `authorize_op`, binding regeneration

**Files:**
- Create: `rust-core/src/prepaid/ffi.rs`
- Modify: `rust-core/src/prepaid/mod.rs`, `rust-core/src/agent/types.rs`, `rust-core/src/agent/token.rs`, `rust-core/src/lib.rs`
- Regenerate: both `wyltekwalletcore.kt` copies and both `libwyltekwalletcore.so` copies

**Interfaces:**
- Consumes: `record::{select_slot_diag, seal, DemoCardRecord, SlotPick, SlotNoneReason, RECORD_SIZE, VERSION_DEMO, SCHEME_CKB_MILLI}`, `reserve::*`, `verifier::*`, `agent::token::parse_and_authorize`.
- Produces (Kotlin names after regen, package `com.wyltek.wallet.core.native`): records `CardRecordInfo(writerId: UShort, reserveIdHex: String, balanceUnits: Int, counter: UInt, merchantIdHex: String)`, `SlotResult(pick: SlotPickFfi, record: CardRecordInfo?, reason: SlotReason, merchantHintHex: String?)`, `ReservePlan(successorCapacity: ULong, customerShannons: ULong, balanceAfter: Int)`, `CardStateFfi(reserveIdHex: String, balanceUnits: Int, settledCounter: UInt, revoked: Boolean)`, `CardNetFfi(reserveIdHex: String, netUnits: Long, maxTmc: UInt, accepted: UInt)`, `BatchResultFfi(verdicts: List<JournalVerdict>, nets: List<CardNetFfi>)`; enums `SlotPickFfi { NONE, A, B, AMBIGUOUS }`, `SlotReason { NO_VALID_SLOT, BAD_VERSION, MAC_FAIL }`, `PrepaidAction { ISSUE, LOAD, VOID, CASH_OUT }`, `JournalVerdict { ACCEPTED, DUPLICATE, BAD_MAC, UNKNOWN_CARD, STALE_COUNTER, OVER_BALANCE }`; error `PrepaidException`; functions `prepaidSelectSlot(kDebitHex, uidHex, raw128Hex): SlotResult`, `prepaidSealRecord(kDebitHex, uidHex, record): String` (64-byte hex), `prepaidNextSlot(pick): SlotPickFfi`, `prepaidReserveId(txHashHex, index: UInt): String`, `prepaidMerchantId(codeHashHex, hashType: String, argsHex): String`, `prepaidPlan(action, balanceUnits: Int, capacityShannons: ULong, amountUnits: Int): ReservePlan`, `prepaidVerifyBatch(kDebitHex, entriesHex, cards: List<CardStateFfi>): BatchResultFfi`, `authorizeOp(token, rootPubHex, op, account, sourceIp, nowUnix: Long): String` (token id, throws `AgentException`), and `Scope.PREPAID_SETTLE`.
- All hex arguments accept an optional `0x` prefix.

- [ ] **Step 1: Write the failing tests** at the bottom of the new `rust-core/src/prepaid/ffi.rs`

```rust
#[cfg(test)]
mod tests {
    use super::*;
    use crate::prepaid::record;

    const K: &str = "000102030405060708090a0b0c0d0e0f";
    const UID: &str = "04958caa5c5e80";

    fn sealed(counter: u32, balance: i32) -> String {
        let mut r = record::DemoCardRecord { version: record::VERSION_DEMO, scheme: record::SCHEME_CKB_MILLI, writer_id: 0,
            reserve_id: [0xA0; 32], balance_units: balance, counter, merchant_id: [0x11; 8], mac: [0; 8] };
        hex::encode(record::seal(&hex16(K).unwrap(), &hex7(UID).unwrap(), &mut r))
    }

    #[test]
    fn select_slot_round_trips_through_hex() {
        let raw = format!("0x{}{}", sealed(5, 1000), "00".repeat(64));
        let r = prepaid_select_slot(K.into(), UID.into(), raw).unwrap();
        assert_eq!(r.pick, SlotPickFfi::A);
        let rec = r.record.unwrap();
        assert_eq!((rec.counter, rec.balance_units, rec.writer_id), (5, 1000, 0));
        assert_eq!(rec.reserve_id_hex, "a0".repeat(32));
        assert_eq!(rec.merchant_id_hex, "11".repeat(8));
    }

    #[test]
    fn select_slot_reports_mac_fail_hint_and_bad_version() {
        let other_uid = "04ebbb02cd2a81";
        let raw = format!("{}{}", sealed(5, 1000), "00".repeat(64));
        let r = prepaid_select_slot(K.into(), other_uid.into(), raw).unwrap();
        assert_eq!((r.pick, r.reason), (SlotPickFfi::None, SlotReason::MacFail));
        assert_eq!(r.merchant_hint_hex.as_deref(), Some("1111111111111111"));
        let mut bad = sealed(5, 1000); bad.replace_range(8..10, "01");
        let r = prepaid_select_slot(K.into(), UID.into(), format!("{}{}", bad, "00".repeat(64))).unwrap();
        assert_eq!(r.reason, SlotReason::BadVersion);
    }

    #[test]
    fn seal_then_select_agrees() {
        let info = CardRecordInfo { writer_id: 0, reserve_id_hex: "a0".repeat(32), balance_units: 250_000, counter: 0, merchant_id_hex: "11".repeat(8) };
        let raw64 = prepaid_seal_record(K.into(), UID.into(), info).unwrap();
        assert_eq!(raw64.len(), 128);
        let r = prepaid_select_slot(K.into(), UID.into(), format!("{}{}", "00".repeat(64), raw64)).unwrap();
        assert_eq!(r.pick, SlotPickFfi::B);
        assert_eq!(r.record.unwrap().balance_units, 250_000);
    }

    #[test]
    fn next_slot_is_the_other_one_and_a_by_default() {
        assert_eq!(prepaid_next_slot(SlotPickFfi::A), SlotPickFfi::B);
        assert_eq!(prepaid_next_slot(SlotPickFfi::B), SlotPickFfi::A);
        assert_eq!(prepaid_next_slot(SlotPickFfi::None), SlotPickFfi::A);
        assert_eq!(prepaid_next_slot(SlotPickFfi::Ambiguous), SlotPickFfi::A);
    }

    #[test]
    fn bad_hex_and_lengths_are_errors() {
        assert!(prepaid_select_slot("zz".into(), UID.into(), "00".repeat(128)).is_err());
        assert!(prepaid_select_slot(K.into(), UID.into(), "00".repeat(127)).is_err());
        assert!(prepaid_select_slot(K.into(), "0001".into(), "00".repeat(128)).is_err());
        assert!(prepaid_reserve_id("00".repeat(31), 0).is_err());
        assert!(prepaid_merchant_id("00".repeat(32), "weird".into(), "".into()).is_err());
    }

    #[test]
    fn reserve_id_and_merchant_id_match_pure_functions() {
        let th = "0x".to_string() + &hex::encode([7u8; 32]);
        assert_eq!(prepaid_reserve_id(th, 2).unwrap(), hex::encode(crate::prepaid::reserve::reserve_id(&[7u8; 32], 2)));
        let code = "9bd7e06f3ecf4be0f2fcd2188b23f1b9fcc88e5d4b65a8637b17723bbda3cce8";
        let m = prepaid_merchant_id(code.into(), "type".into(), "0x".to_string() + &"00".repeat(20)).unwrap();
        assert_eq!(m.len(), 16);
        let code_b: [u8; 32] = hex::decode(code).unwrap().try_into().unwrap();
        assert_eq!(m, hex::encode(crate::prepaid::reserve::merchant_id(&code_b, 1, &[0u8; 20])));
        assert_ne!(m, prepaid_merchant_id(code.into(), "data".into(), "00".repeat(20)).unwrap());
    }

    #[test]
    fn plan_and_verify_batch_cross_the_boundary() {
        let p = prepaid_plan(PrepaidAction::Issue, 0, 0, 1000).unwrap();
        assert_eq!(p.successor_capacity, 6_100_000_000 + 1000 * 100_000);
        let e = prepaid_plan(PrepaidAction::Issue, 0, 0, 0).unwrap_err();
        assert!(matches!(e, PrepaidError::Plan(_)));
        let r = prepaid_verify_batch(K.into(), "".into(), vec![]).unwrap();
        assert!(r.verdicts.is_empty());
        assert!(prepaid_verify_batch(K.into(), "00".repeat(95), vec![]).is_err());
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `cd ~/wyltek-wallet/rust-core && cargo test prepaid::ffi 2>&1 | tail -5` → compile errors.

- [ ] **Step 3: Implement `ffi.rs`** (above the tests)

```rust
//! uniffi surface for the prepaid demo tier (D3 spec §4). Thin hex/record adapters over the pure modules.
use super::{record, reserve, verifier};

#[derive(Debug, thiserror::Error, uniffi::Error)]
#[uniffi(flat_error)]
pub enum PrepaidError {
    #[error("Invalid input: {0}")] InvalidInput(String),
    #[error("Plan refused: {0}")] Plan(String),
}

fn strip(h: &str) -> &str { h.strip_prefix("0x").or_else(|| h.strip_prefix("0X")).unwrap_or(h) }
fn hexv(h: &str) -> Result<Vec<u8>, PrepaidError> { hex::decode(strip(h)).map_err(|e| PrepaidError::InvalidInput(format!("hex: {e}"))) }
fn fixed<const N: usize>(h: &str, what: &str) -> Result<[u8; N], PrepaidError> {
    hexv(h)?.try_into().map_err(|_| PrepaidError::InvalidInput(format!("{what} must be {N} bytes")))
}
pub(crate) fn hex16(h: &str) -> Result<[u8; 16], PrepaidError> { fixed::<16>(h, "k_debit") }
pub(crate) fn hex7(h: &str) -> Result<[u8; 7], PrepaidError> { fixed::<7>(h, "uid") }

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Enum)]
pub enum SlotPickFfi { None, A, B, Ambiguous }
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Enum)]
pub enum SlotReason { NoValidSlot, BadVersion, MacFail }
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct CardRecordInfo { pub writer_id: u16, pub reserve_id_hex: String, pub balance_units: i32, pub counter: u32, pub merchant_id_hex: String }
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct SlotResult { pub pick: SlotPickFfi, pub record: Option<CardRecordInfo>, pub reason: SlotReason, pub merchant_hint_hex: Option<String> }
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum PrepaidAction { Issue, Load, Void, CashOut }
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct ReservePlan { pub successor_capacity: u64, pub customer_shannons: u64, pub balance_after: i32 }
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct CardStateFfi { pub reserve_id_hex: String, pub balance_units: i32, pub settled_counter: u32, pub revoked: bool }
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum JournalVerdict { Accepted, Duplicate, BadMac, UnknownCard, StaleCounter, OverBalance }
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct CardNetFfi { pub reserve_id_hex: String, pub net_units: i64, pub max_tmc: u32, pub accepted: u32 }
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct BatchResultFfi { pub verdicts: Vec<JournalVerdict>, pub nets: Vec<CardNetFfi> }

fn info_of(r: &record::DemoCardRecord) -> CardRecordInfo {
    CardRecordInfo { writer_id: r.writer_id, reserve_id_hex: hex::encode(r.reserve_id), balance_units: r.balance_units,
        counter: r.counter, merchant_id_hex: hex::encode(r.merchant_id) }
}

#[uniffi::export]
pub fn prepaid_select_slot(k_debit_hex: String, uid_hex: String, raw128_hex: String) -> Result<SlotResult, PrepaidError> {
    let key = hex16(&k_debit_hex)?; let uid = hex7(&uid_hex)?;
    let raw: [u8; 128] = fixed::<128>(&raw128_hex, "slots")?;
    let a: &[u8; 64] = raw[..64].try_into().unwrap(); let b: &[u8; 64] = raw[64..].try_into().unwrap();
    let (pick, rec, diag) = record::select_slot_diag(&key, &uid, a, b);
    Ok(SlotResult {
        pick: match pick { record::SlotPick::None => SlotPickFfi::None, record::SlotPick::A => SlotPickFfi::A, record::SlotPick::B => SlotPickFfi::B, record::SlotPick::Ambiguous => SlotPickFfi::Ambiguous },
        record: rec.as_ref().map(info_of),
        reason: match diag.reason { record::SlotNoneReason::NoValidSlot => SlotReason::NoValidSlot, record::SlotNoneReason::BadVersion => SlotReason::BadVersion, record::SlotNoneReason::MacFail => SlotReason::MacFail },
        merchant_hint_hex: diag.merchant_hint.map(hex::encode),
    })
}

#[uniffi::export]
pub fn prepaid_seal_record(k_debit_hex: String, uid_hex: String, record: CardRecordInfo) -> Result<String, PrepaidError> {
    let key = hex16(&k_debit_hex)?; let uid = hex7(&uid_hex)?;
    let mut r = record::DemoCardRecord { version: record::VERSION_DEMO, scheme: record::SCHEME_CKB_MILLI, writer_id: record.writer_id,
        reserve_id: fixed::<32>(&record.reserve_id_hex, "reserve_id")?, balance_units: record.balance_units, counter: record.counter,
        merchant_id: fixed::<8>(&record.merchant_id_hex, "merchant_id")?, mac: [0; 8] };
    Ok(hex::encode(record::seal(&key, &uid, &mut r)))
}

#[uniffi::export]
pub fn prepaid_next_slot(pick: SlotPickFfi) -> SlotPickFfi {
    match pick { SlotPickFfi::A => SlotPickFfi::B, _ => SlotPickFfi::A }
}

#[uniffi::export]
pub fn prepaid_reserve_id(tx_hash_hex: String, index: u32) -> Result<String, PrepaidError> {
    Ok(hex::encode(reserve::reserve_id(&fixed::<32>(&tx_hash_hex, "tx_hash")?, index)))
}

#[uniffi::export]
pub fn prepaid_merchant_id(code_hash_hex: String, hash_type: String, args_hex: String) -> Result<String, PrepaidError> {
    let ht = match hash_type.as_str() { "data" => 0u8, "type" => 1, "data1" => 2, "data2" => 4,
        other => return Err(PrepaidError::InvalidInput(format!("hash_type {other}"))) };
    Ok(hex::encode(reserve::merchant_id(&fixed::<32>(&code_hash_hex, "code_hash")?, ht, &hexv(&args_hex)?)))
}

#[uniffi::export]
pub fn prepaid_plan(action: PrepaidAction, balance_units: i32, capacity_shannons: u64, amount_units: i32) -> Result<ReservePlan, PrepaidError> {
    let a = match action { PrepaidAction::Issue => reserve::Action::Issue, PrepaidAction::Load => reserve::Action::Load,
        PrepaidAction::Void => reserve::Action::Void, PrepaidAction::CashOut => reserve::Action::CashOut };
    let p = reserve::plan(a, balance_units, capacity_shannons, amount_units).map_err(|e| PrepaidError::Plan(match e {
        reserve::PlanError::AmountNotPositive => "amount must be positive".into(),
        reserve::PlanError::OverCap => format!("amount above the demo cap of {} CKB", reserve::LOAD_MAX_UNITS / 1000),
        reserve::PlanError::Overflow => "balance overflow".into(),
        reserve::PlanError::BelowFloor => "reserve cell below the 61 CKB floor".into(),
        reserve::PlanError::CustomerBelowMinimum => "balance below the 61 CKB minimum cell; pay out in cash and void".into(),
        reserve::PlanError::BalanceMismatch => "reserve capacity does not match the index balance; use Inspect".into(),
    }))?;
    Ok(ReservePlan { successor_capacity: p.successor_capacity, customer_shannons: p.customer_shannons, balance_after: p.balance_after })
}

#[uniffi::export]
pub fn prepaid_verify_batch(k_debit_hex: String, entries_hex: String, cards: Vec<CardStateFfi>) -> Result<BatchResultFfi, PrepaidError> {
    let key = hex16(&k_debit_hex)?;
    let entries = hexv(&entries_hex)?;
    let states = cards.iter().map(|c| Ok(verifier::CardState { reserve_id: fixed::<32>(&c.reserve_id_hex, "reserve_id")?,
        balance_units: c.balance_units, settled_counter: c.settled_counter, revoked: c.revoked })).collect::<Result<Vec<_>, PrepaidError>>()?;
    let r = verifier::verify_batch(&key, &entries, &states).map_err(|_| PrepaidError::InvalidInput("entries length is not a multiple of 96".into()))?;
    Ok(BatchResultFfi {
        verdicts: r.verdicts.into_iter().map(|v| match v { verifier::Verdict::Accepted => JournalVerdict::Accepted, verifier::Verdict::Duplicate => JournalVerdict::Duplicate,
            verifier::Verdict::BadMac => JournalVerdict::BadMac, verifier::Verdict::UnknownCard => JournalVerdict::UnknownCard,
            verifier::Verdict::StaleCounter => JournalVerdict::StaleCounter, verifier::Verdict::OverBalance => JournalVerdict::OverBalance }).collect(),
        nets: r.nets.into_iter().map(|n| CardNetFfi { reserve_id_hex: hex::encode(n.reserve_id), net_units: n.net_units, max_tmc: n.max_tmc, accepted: n.accepted }).collect(),
    })
}
```

Add `pub mod ffi;` to `prepaid/mod.rs`. In `lib.rs` add `pub use prepaid::ffi::{PrepaidError, SlotResult, CardRecordInfo, ReservePlan, BatchResultFfi};` next to the other re-exports.

In `agent/types.rs` add `PrepaidSettle` to `Scope` with tag `"prepaid_settle"` and extend `scope_tags_are_stable` with `assert_eq!(Scope::PrepaidSettle.as_tag(), "prepaid_settle");`.

In `agent/token.rs` add after `token_id_of`:

```rust
/// UniFFI export: authorize a non-spending operation (e.g. "prepaid_settle") for a token.
/// Runs every caveat the token carries (scope, account, ttl, allow_ip; `allow_to` is satisfied
/// with the account itself since these ops have no recipient). Returns the token_id.
#[uniffi::export]
pub fn authorize_op(token: String, root_pub_hex: String, op: String, account: String, source_ip: String, now_unix: i64) -> Result<String, AgentError> {
    parse_and_authorize(&token, &root_pub_hex, &op, &account, &account, &source_ip, now_unix)
        .map(|a| a.token_id)
        .map_err(AgentError::TokenError)
}
```
and a test beside `authorizes_in_scope_op`:
```rust
#[test]
fn authorize_op_requires_the_scope() {
    let kp = agent_root_keypair();
    let mut spec = sample_spec(); spec.scopes = vec![Scope::PrepaidSettle];
    let token = mint_token(spec.clone(), kp.secret_hex.clone()).unwrap();
    assert!(authorize_op(token.clone(), kp.public_hex.clone(), "prepaid_settle".into(), spec.account.clone(), "10.0.0.2".into(), 1_700_000_000).is_ok());
    assert!(authorize_op(token.clone(), kp.public_hex.clone(), "send_ckb".into(), spec.account.clone(), "10.0.0.2".into(), 1_700_000_000).is_err());
    assert!(authorize_op(token, kp.public_hex, "prepaid_settle".into(), "ckb1other".into(), "10.0.0.2".into(), 1_700_000_000).is_err());
}
```

- [ ] **Step 4: Run to verify pass**

Run: `cargo test 2>&1 | grep -E "test result|FAILED|panicked"` → all green (prepaid::ffi 7, agent::token +1).

- [ ] **Step 5: Regenerate bindings and the `.so`**

> Needs the Android NDK, the `aarch64-linux-android` target and `cargo-ndk`. If missing, STOP and report this step as an operator action with these commands; Tasks 4–16 compile against the generated Kotlin, so nothing after this can be `assembleDebug`-verified until it is done.

```bash
cd ~/wyltek-wallet/rust-core
cargo build --release
cargo run --bin uniffi-bindgen generate --library target/release/libwyltekwalletcore.so --language kotlin --out-dir /tmp/uniffi-out
cp /tmp/uniffi-out/com/wyltek/wallet/core/native/wyltekwalletcore.kt ../wallet-core/src/main/java/com/wyltek/wallet/core/native/wyltekwalletcore.kt
# bindings live ONLY in wallet-core (a second copy in :app caused a NoSuchFieldError crash; BindingsSingleSourceTest)
cargo ndk -t arm64-v8a build --release
cp target/aarch64-linux-android/release/libwyltekwalletcore.so ../wallet-core/src/main/jniLibs/arm64-v8a/libwyltekwalletcore.so
cp target/aarch64-linux-android/release/libwyltekwalletcore.so ../app/src/main/jniLibs/arm64-v8a/libwyltekwalletcore.so
```

- [ ] **Step 6: Confirm the symbols**

Run: `grep -nE "fun (prepaidSelectSlot|prepaidSealRecord|prepaidNextSlot|prepaidReserveId|prepaidMerchantId|prepaidPlan|prepaidVerifyBatch|authorizeOp)|class (CardRecordInfo|SlotResult|ReservePlan|CardStateFfi|BatchResultFfi)|PREPAID_SETTLE|PrepaidException" ~/wyltek-wallet/app/src/main/java/com/wyltek/wallet/core/native/wyltekwalletcore.kt | head -20`
Expected: every name present. Then `cd ~/wyltek-wallet && ./gradlew :app:compileDebugKotlin -q` → OK (AgentScreen's `when` over `Scope` is a list build, not exhaustive, so the new variant compiles).

- [ ] **Step 7: Commit**

```bash
git add rust-core/src app/src/main/java/com/wyltek/wallet/core/native wallet-core/src/main/java/com/wyltek/wallet/core/native app/src/main/jniLibs wallet-core/src/main/jniLibs
git commit -m "feat(prepaid): uniffi surface for slots, plans, verifier; prepaid_settle scope; regen bindings + .so

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 4: Room tables `prepaid_cards` and `prepaid_events`, migration 3→4

**Files:**
- Create: `app/src/main/java/com/wyltek/wallet/prepaid/db/PrepaidEntities.kt`, `app/src/main/java/com/wyltek/wallet/prepaid/db/PrepaidDao.kt`
- Modify: `app/src/main/java/com/wyltek/wallet/agent/db/AgentDatabase.kt`
- Test: `app/src/test/java/com/wyltek/wallet/prepaid/PrepaidDaoAppendOnlyTest.kt` (JVM), `app/src/androidTest/java/com/wyltek/wallet/prepaid/PrepaidDaoTest.kt`

**Interfaces:**
- Produces: `PrepaidCardEntity`, `PrepaidEventEntity`, string constants `CARD_ISSUING/ACTIVE/REVOKED/CLOSED`, `KIND_*`, `PHASE_*`; `PrepaidDao`; `AgentDatabase.prepaidDao()`; `MIGRATION_3_4`.

- [ ] **Step 1: Write the failing JVM test**

`app/src/test/java/com/wyltek/wallet/prepaid/PrepaidDaoAppendOnlyTest.kt`:
```kotlin
package com.wyltek.wallet.prepaid

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The audit table is append-only (D3 spec §3.2). Room annotations are not retained at runtime,
 *  so this test reads the DAO source. Gradle runs unit tests with the module dir as CWD. */
class PrepaidDaoAppendOnlyTest {
    private val src = File("src/main/java/com/wyltek/wallet/prepaid/db/PrepaidDao.kt").readText()

    @Test fun `dao source exists and names the events table`() {
        assertTrue(src.contains("prepaid_events"))
    }

    @Test fun `no update or delete annotation takes an event entity`() {
        val annotated = Regex("""@(Update|Delete)[^\n]*\n[^\n]*""").findAll(src).map { it.value }.toList()
        annotated.forEach { assertFalse("append-only violated: $it", it.contains("PrepaidEventEntity")) }
    }

    @Test fun `every query touching prepaid_events is a select`() {
        val queries = Regex("""@Query\("""(.*?)"""\)|@Query\("(.*?)"\)""", RegexOption.DOT_MATCHES_ALL)
            .findAll(src).map { it.groupValues[1].ifEmpty { it.groupValues[2] } }.toList()
        assertTrue(queries.isNotEmpty())
        queries.filter { it.contains("prepaid_events") }.forEach { q ->
            assertTrue("non-select on prepaid_events: $q", q.trim().uppercase().startsWith("SELECT"))
        }
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `cd ~/wyltek-wallet && ./gradlew :app:testDebugUnitTest --tests '*PrepaidDaoAppendOnlyTest*' -q 2>&1 | tail -5`
Expected: FAIL (`FileNotFoundException` for PrepaidDao.kt).

- [ ] **Step 3: Implement entities, DAO, migration**

`PrepaidEntities.kt`:
```kotlin
package com.wyltek.wallet.prepaid.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

const val CARD_ISSUING = "issuing"
const val CARD_ACTIVE = "active"
const val CARD_REVOKED = "revoked"
const val CARD_CLOSED = "closed"

const val KIND_ISSUE = "issue"; const val KIND_LOAD = "load"; const val KIND_ADOPT = "adopt"; const val KIND_REPAIR = "repair"
const val KIND_REVOKE = "revoke"; const val KIND_REINSTATE = "reinstate"; const val KIND_VOID = "void"
const val KIND_CASH_OUT = "cash_out"; const val KIND_SETTLE = "settle"; const val KIND_KEY_GENERATED = "key_generated"

const val PHASE_REQUESTED = "requested"; const val PHASE_BROADCAST = "broadcast"; const val PHASE_COMMITTED = "committed"
const val PHASE_WRITTEN = "written"; const val PHASE_DONE = "done"; const val PHASE_FAILED = "failed"

/** The card index (D3 spec §3.1). `pendingTxHash` non-null = a transaction of ours is unconfirmed, or
 *  confirmed with `pendingUnits` still unwritten to the tag; `pendingActionId` groups its audit rows. */
@Entity(tableName = "prepaid_cards", indices = [Index(value = ["uid"], unique = true)])
data class PrepaidCardEntity(
    @PrimaryKey val reserveId: String,
    val uid: String,
    val label: String? = null,
    val firstOutpoint: String,
    val currentOutpoint: String,
    val balanceUnits: Int,
    val counter: Long,
    val settledCounter: Long,
    val state: String,
    val pendingUnits: Int? = null,
    val pendingTxHash: String? = null,
    val pendingActionId: String? = null,
    val pendingCommitted: Boolean = false,
    val issuedAt: Long,
    val updatedAt: Long,
)

/** Append-only audit log (D3 spec §3.2). One row per phase of one merchant action. */
@Entity(tableName = "prepaid_events", indices = [Index(value = ["actionId"]), Index(value = ["reserveId"])])
data class PrepaidEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val atMs: Long,
    val actionId: String,
    val kind: String,
    val phase: String,
    val reserveId: String?,
    val uid: String,
    val amountUnits: Long,
    val balanceBefore: Int,
    val balanceAfter: Int,
    val counterBefore: Long,
    val counterAfter: Long,
    val txHash: String?,
    val detail: String,
)
```

`PrepaidDao.kt`:
```kotlin
package com.wyltek.wallet.prepaid.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface PrepaidDao {
    // ---- index ----
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCard(c: PrepaidCardEntity)

    @Update
    suspend fun updateCard(c: PrepaidCardEntity)

    /** Only a card whose issue never left the wallet may be removed (broadcast refused / rejected). */
    @Query("DELETE FROM prepaid_cards WHERE reserveId = :reserveId AND state = 'issuing'")
    suspend fun deleteIssuing(reserveId: String): Int

    @Query("SELECT * FROM prepaid_cards WHERE reserveId = :reserveId LIMIT 1")
    suspend fun card(reserveId: String): PrepaidCardEntity?

    @Query("SELECT * FROM prepaid_cards WHERE uid = :uid LIMIT 1")
    suspend fun cardByUid(uid: String): PrepaidCardEntity?

    @Query("SELECT * FROM prepaid_cards ORDER BY updatedAt DESC")
    suspend fun allCards(): List<PrepaidCardEntity>

    @Query("SELECT * FROM prepaid_cards ORDER BY updatedAt DESC")
    fun cardsFlow(): Flow<List<PrepaidCardEntity>>

    @Query("SELECT * FROM prepaid_cards WHERE pendingTxHash IS NOT NULL")
    suspend fun cardsWithPending(): List<PrepaidCardEntity>

    @Query("SELECT COUNT(*) FROM prepaid_cards WHERE state IN ('issuing','active','revoked')")
    suspend fun activeCount(): Int

    // ---- audit log: insert + select only ----
    @Insert
    suspend fun insertEvent(e: PrepaidEventEntity): Long

    @Query("SELECT * FROM prepaid_events ORDER BY id DESC")
    fun eventsFlow(): Flow<List<PrepaidEventEntity>>

    @Query("SELECT * FROM prepaid_events ORDER BY id ASC")
    suspend fun allEvents(): List<PrepaidEventEntity>

    @Query("SELECT * FROM prepaid_events WHERE reserveId = :reserveId ORDER BY id DESC")
    suspend fun eventsForCard(reserveId: String): List<PrepaidEventEntity>

    @Query("SELECT * FROM prepaid_events WHERE actionId = :actionId ORDER BY id ASC")
    suspend fun eventsForAction(actionId: String): List<PrepaidEventEntity>
}
```

`AgentDatabase.kt`: add `PrepaidCardEntity::class, PrepaidEventEntity::class` to `entities`, bump `version = 4`, add `abstract fun prepaidDao(): PrepaidDao`, add `MIGRATION_3_4` to `addMigrations(...)`, and:

```kotlin
/** v3 → v4: prepaid card index + append-only audit log (D3). DDL must match Room's expectation
 *  exactly; copy it from build/generated/.../AgentDatabase_Impl.java `createAllTables` after the
 *  first ksp run if in doubt. */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS `prepaid_cards` (`reserveId` TEXT NOT NULL, `uid` TEXT NOT NULL, `label` TEXT,
            `firstOutpoint` TEXT NOT NULL, `currentOutpoint` TEXT NOT NULL, `balanceUnits` INTEGER NOT NULL, `counter` INTEGER NOT NULL,
            `settledCounter` INTEGER NOT NULL, `state` TEXT NOT NULL, `pendingUnits` INTEGER, `pendingTxHash` TEXT, `pendingActionId` TEXT,
            `pendingCommitted` INTEGER NOT NULL, `issuedAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`reserveId`))""")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_prepaid_cards_uid` ON `prepaid_cards` (`uid`)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS `prepaid_events` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `atMs` INTEGER NOT NULL,
            `actionId` TEXT NOT NULL, `kind` TEXT NOT NULL, `phase` TEXT NOT NULL, `reserveId` TEXT, `uid` TEXT NOT NULL,
            `amountUnits` INTEGER NOT NULL, `balanceBefore` INTEGER NOT NULL, `balanceAfter` INTEGER NOT NULL, `counterBefore` INTEGER NOT NULL,
            `counterAfter` INTEGER NOT NULL, `txHash` TEXT, `detail` TEXT NOT NULL)""")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_prepaid_events_actionId` ON `prepaid_events` (`actionId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_prepaid_events_reserveId` ON `prepaid_events` (`reserveId`)")
    }
}
```

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests '*PrepaidDaoAppendOnlyTest*' -q 2>&1 | tail -3` → PASS. Then `./gradlew :app:compileDebugKotlin -q` → OK, and `diff <(grep -A12 "prepaid_cards" app/build/generated/ksp/debug/java/com/wyltek/wallet/agent/db/AgentDatabase_Impl.java | head -14)` against the migration DDL by eye: every column, type and NOT NULL identical.

- [ ] **Step 5: Instrumented DAO test** — `app/src/androidTest/java/com/wyltek/wallet/prepaid/PrepaidDaoTest.kt`, same shape as `AgentLedgerTest` (delete `agent_gateway.db` in `@Before`, open via `AgentDatabaseFactory.open(ctx, ByteArray(32) { 7 })`):

```kotlin
@RunWith(AndroidJUnit4::class)
class PrepaidDaoTest {
    private lateinit var db: AgentDatabase
    @Before fun open() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.deleteDatabase("agent_gateway.db")
        db = AgentDatabaseFactory.open(ctx, ByteArray(32) { 7 })
    }
    @After fun close() = db.close()

    private fun card(rid: String, uid: String, state: String = CARD_ACTIVE) = PrepaidCardEntity(
        reserveId = rid, uid = uid, firstOutpoint = "0xaa:0", currentOutpoint = "0xaa:0", balanceUnits = 1000,
        counter = 0, settledCounter = 0, state = state, issuedAt = 1, updatedAt = 1)

    @Test fun card_round_trip_and_uid_unique() = runBlocking {
        val dao = db.prepaidDao()
        dao.insertCard(card("r1", "u1"))
        assertEquals("u1", dao.card("r1")!!.uid)
        assertEquals("r1", dao.cardByUid("u1")!!.reserveId)
        try { dao.insertCard(card("r2", "u1")); fail("uid must be unique") } catch (e: android.database.sqlite.SQLiteConstraintException) {}
        assertEquals(1, dao.activeCount())
    }

    @Test fun delete_issuing_only_removes_issuing_rows() = runBlocking {
        val dao = db.prepaidDao()
        dao.insertCard(card("r1", "u1", CARD_ISSUING)); dao.insertCard(card("r2", "u2", CARD_ACTIVE))
        assertEquals(1, dao.deleteIssuing("r1")); assertEquals(0, dao.deleteIssuing("r2"))
        assertNotNull(dao.card("r2"))
    }

    @Test fun events_append_and_read_newest_first() = runBlocking {
        val dao = db.prepaidDao()
        val e = PrepaidEventEntity(atMs = 1, actionId = "a", kind = KIND_ISSUE, phase = PHASE_REQUESTED, reserveId = "r1", uid = "u1",
            amountUnits = 1000, balanceBefore = 0, balanceAfter = 0, counterBefore = 0, counterAfter = 0, txHash = null, detail = "")
        dao.insertEvent(e); dao.insertEvent(e.copy(atMs = 2, phase = PHASE_BROADCAST, txHash = "0xbb"))
        val forCard = dao.eventsForCard("r1")
        assertEquals(listOf(PHASE_BROADCAST, PHASE_REQUESTED), forCard.map { it.phase })
        assertEquals(2, dao.eventsForAction("a").size)
    }
}
```
Run on a connected device: `./gradlew :app:connectedDebugAndroidTest --tests '*PrepaidDaoTest*' 2>&1 | tail -5` → 3 passed. (Needs the phone; if unavailable, record it as pending for Task 16's device session.)

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/wyltek/wallet/prepaid/db app/src/main/java/com/wyltek/wallet/agent/db/AgentDatabase.kt app/src/test/java/com/wyltek/wallet/prepaid app/src/androidTest/java/com/wyltek/wallet/prepaid
git commit -m "feat(prepaid): card index and append-only audit tables, agent db v4

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 5: `PrepaidKeys` (K_debit) and the provisioning bundle fields

**Files:**
- Create: `app/src/main/java/com/wyltek/wallet/prepaid/PrepaidKeys.kt`
- Modify: `app/src/main/java/com/wyltek/wallet/agent/provisioning/AgentProvisioning.kt`
- Test: `app/src/test/java/com/wyltek/wallet/prepaid/PrepaidKeysTest.kt`, `app/src/test/java/com/wyltek/wallet/agent/provisioning/AgentProvisioningTest.kt` (extend)

**Interfaces:**
- Produces: `class PrepaidKeys(load: (String) -> ByteArray?, store: (String, ByteArray) -> Unit, random: () -> ByteArray)` with `fun hasKDebit(): Boolean`, `fun kDebitHex(): String` (generates on first call), `fun kDebit(): ByteArray`; `PrepaidKeys.KEY_K_DEBIT = "prepaid.k_debit"`; `buildProvisioningBundle(deviceId, token, kDebitHex: String? = null, merchantIdHex: String? = null)`.

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.wyltek.wallet.prepaid

import org.junit.Assert.*
import org.junit.Test

class PrepaidKeysTest {
    private val blobs = mutableMapOf<String, ByteArray>()
    private var draws = 0
    private fun keys() = PrepaidKeys(load = { blobs[it] }, store = { k, v -> blobs[k] = v }, random = { draws++; ByteArray(16) { (it + 1).toByte() } })

    @Test fun `generates once and is stable`() {
        val k = keys()
        assertFalse(k.hasKDebit())
        val a = k.kDebitHex()
        assertEquals("0102030405060708090a0b0c0d0e0f10", a)
        assertTrue(k.hasKDebit())
        assertEquals(a, keys().kDebitHex())
        assertEquals(1, draws)
    }

    @Test fun `rejects a stored blob of the wrong length`() {
        blobs[PrepaidKeys.KEY_K_DEBIT] = ByteArray(15)
        try { keys().kDebitHex(); fail() } catch (e: IllegalStateException) {}
    }
}
```
Add to `AgentProvisioningTest`:
```kotlin
    @Test
    fun `bundle carries k_debit and merchant_id after the original three fields`() {
        val out = buildProvisioningBundle("dev-123", "tok-abc", kDebitHex = "00112233445566778899aabbccddeeff", merchantIdHex = "1122334455667788")
        assertEquals("""{"device_id":"dev-123","token":"tok-abc","service_name":"blackbox-dev123","k_debit":"00112233445566778899aabbccddeeff","merchant_id":"1122334455667788"}""", out)
    }

    @Test
    fun `bundle without prepaid fields is byte-identical to before`() {
        assertEquals("""{"device_id":"dev-123","token":"tok-abc","service_name":"blackbox-dev123"}""", buildProvisioningBundle("dev-123", "tok-abc"))
        assertEquals(buildProvisioningBundle("dev-123", "tok-abc"), buildProvisioningBundle("dev-123", "tok-abc", kDebitHex = "aa", merchantIdHex = null))
    }
```

- [ ] **Step 2: Run to verify failure** — `./gradlew :app:testDebugUnitTest --tests '*PrepaidKeysTest*' --tests '*AgentProvisioningTest*' -q 2>&1 | tail -5` → compile errors.

- [ ] **Step 3: Implement**

```kotlin
package com.wyltek.wallet.prepaid

import java.security.SecureRandom

/** K_debit: 16 random bytes, generated once, kept in the agent secure store (D3 spec §2.10, §3.3).
 *  Storage is injected so the class is host-testable; on device it is AgentSecureStore::loadBlob/storeBlob. */
class PrepaidKeys(
    private val load: (String) -> ByteArray?,
    private val store: (String, ByteArray) -> Unit,
    private val random: () -> ByteArray = { ByteArray(16).also { SecureRandom().nextBytes(it) } },
) {
    fun hasKDebit(): Boolean = load(KEY_K_DEBIT) != null

    fun kDebit(): ByteArray {
        load(KEY_K_DEBIT)?.let { check(it.size == 16) { "stored k_debit is ${it.size} bytes" }; return it }
        val k = random()
        check(k.size == 16)
        store(KEY_K_DEBIT, k)
        return k
    }

    fun kDebitHex(): String = kDebit().joinToString("") { "%02x".format(it) }

    companion object { const val KEY_K_DEBIT = "prepaid.k_debit" }
}
```
`buildProvisioningBundle`:
```kotlin
fun buildProvisioningBundle(deviceId: String?, token: String, kDebitHex: String? = null, merchantIdHex: String? = null): String? {
    val id = deviceId?.ifBlank { null } ?: return null
    val svc = serviceNameFor(id)
    val prepaid = if (kDebitHex != null && merchantIdHex != null) ""","k_debit":"$kDebitHex","merchant_id":"$merchantIdHex"""" else ""
    return """{"device_id":"$id","token":"$token","service_name":"$svc"$prepaid}"""
}
```
Update the KDoc: the two extra keys are matched by the POS with the same `strstr` rule.

- [ ] **Step 4: Run to verify pass** — same command → PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/wyltek/wallet/prepaid/PrepaidKeys.kt app/src/main/java/com/wyltek/wallet/agent/provisioning/AgentProvisioning.kt app/src/test
git commit -m "feat(prepaid): K_debit key store and bundle fields k_debit/merchant_id

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 6: `TapClassifier` (pure, D3 spec §5.1)

**Files:**
- Create: `app/src/main/java/com/wyltek/wallet/prepaid/TapClassifier.kt`
- Test: `app/src/test/java/com/wyltek/wallet/prepaid/TapClassifierTest.kt`

**Interfaces:**
- Consumes: `SlotResult`, `SlotPickFfi`, `SlotReason`, `CardRecordInfo` (Task 3 bindings), `PrepaidCardEntity` (Task 4).
- Produces: `sealed class TapClass { Resume(card, slot); Blank(uidHex); Damaged(card, reason); Known(card, record, slot); Bench(record, slot); Refused(reason) }`; `TapClassifier.classify(uidHex, slot, cardByUid, cardByReserve, ourMerchantIdHex): TapClass`; reason constants `REASON_OTHER_MERCHANT`, `REASON_BAD_VERSION`, `REASON_AMBIGUOUS`, `REASON_UNFORMATTED`, `REASON_AUTH`, `REASON_MOVED`.

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.wyltek.wallet.prepaid

import com.wyltek.wallet.core.native.CardRecordInfo
import com.wyltek.wallet.core.native.SlotPickFfi
import com.wyltek.wallet.core.native.SlotReason
import com.wyltek.wallet.core.native.SlotResult
import com.wyltek.wallet.prepaid.db.CARD_ACTIVE
import com.wyltek.wallet.prepaid.db.PrepaidCardEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TapClassifierTest {
    private val ours = "1122334455667788"
    private val uid = "04958caa5c5e80"
    private val rid = "a0".repeat(32)
    private fun card(uid: String = this.uid, pending: String? = null) = PrepaidCardEntity(reserveId = rid, uid = uid, firstOutpoint = "0x1:0",
        currentOutpoint = "0x1:0", balanceUnits = 100, counter = 1, settledCounter = 0, state = CARD_ACTIVE, pendingTxHash = pending,
        pendingUnits = if (pending != null) 50 else null, issuedAt = 0, updatedAt = 0)
    private fun rec(merchant: String = ours, reserve: String = rid) = CardRecordInfo(writerId = 0u, reserveIdHex = reserve, balanceUnits = 100, counter = 1u, merchantIdHex = merchant)
    private fun slot(pick: SlotPickFfi, record: CardRecordInfo? = null, reason: SlotReason = SlotReason.NO_VALID_SLOT, hint: String? = null) =
        SlotResult(pick = pick, record = record, reason = reason, merchantHintHex = hint)
    private fun go(s: SlotResult, byUid: PrepaidCardEntity? = null, byReserve: PrepaidCardEntity? = null) = TapClassifier.classify(uid, s, byUid, byReserve, ours)

    @Test fun `pending credit wins over everything`() {
        val c = card(pending = "0xabc")
        assertTrue(go(slot(SlotPickFfi.NONE), byUid = c) is TapClass.Resume)
        assertTrue(go(slot(SlotPickFfi.A, rec()), byUid = c, byReserve = c) is TapClass.Resume)
    }
    @Test fun `blank tag unknown uid is Blank`() { assertEquals(TapClass.Blank(uid), go(slot(SlotPickFfi.NONE))) }
    @Test fun `blank tag known uid is Damaged`() { assertTrue(go(slot(SlotPickFfi.NONE), byUid = card()) is TapClass.Damaged) }
    @Test fun `bad version is refused`() { assertEquals(TapClass.Refused(TapClassifier.REASON_BAD_VERSION), go(slot(SlotPickFfi.NONE, reason = SlotReason.BAD_VERSION))) }
    @Test fun `mac fail with another merchant hint is refused`() {
        assertEquals(TapClass.Refused(TapClassifier.REASON_OTHER_MERCHANT), go(slot(SlotPickFfi.NONE, reason = SlotReason.MAC_FAIL, hint = "ffffffffffffffff")))
    }
    @Test fun `mac fail with our hint is auth failure, damaged if known`() {
        assertEquals(TapClass.Refused(TapClassifier.REASON_AUTH), go(slot(SlotPickFfi.NONE, reason = SlotReason.MAC_FAIL, hint = ours)))
        assertTrue(go(slot(SlotPickFfi.NONE, reason = SlotReason.MAC_FAIL, hint = ours), byUid = card()) is TapClass.Damaged)
    }
    @Test fun `ambiguous is refused, damaged if known`() {
        assertEquals(TapClass.Refused(TapClassifier.REASON_AMBIGUOUS), go(slot(SlotPickFfi.AMBIGUOUS)))
        assertEquals(TapClassifier.REASON_AMBIGUOUS, (go(slot(SlotPickFfi.AMBIGUOUS), byUid = card()) as TapClass.Damaged).reason)
    }
    @Test fun `valid record known reserve is Known`() {
        val r = go(slot(SlotPickFfi.B, rec()), byUid = card(), byReserve = card())
        assertTrue(r is TapClass.Known); assertEquals(SlotPickFfi.B, (r as TapClass.Known).slot.pick)
    }
    @Test fun `valid record unknown reserve is Bench`() { assertTrue(go(slot(SlotPickFfi.A, rec())) is TapClass.Bench) }
    @Test fun `valid record other merchant is refused`() { assertEquals(TapClass.Refused(TapClassifier.REASON_OTHER_MERCHANT), go(slot(SlotPickFfi.A, rec(merchant = "ffffffffffffffff")))) }
    @Test fun `unformatted record is refused`() { assertEquals(TapClass.Refused(TapClassifier.REASON_UNFORMATTED), go(slot(SlotPickFfi.A, rec(reserve = "00".repeat(32))))) }
    @Test fun `reserve known under a different uid is refused`() {
        assertEquals(TapClass.Refused(TapClassifier.REASON_MOVED), go(slot(SlotPickFfi.A, rec()), byReserve = card(uid = "04ebbb02cd2a81")))
    }
    @Test fun `merchant id comparison is case-insensitive`() {
        assertTrue(go(slot(SlotPickFfi.A, rec(merchant = ours.uppercase())), byReserve = card()) is TapClass.Known)
    }
}
```

- [ ] **Step 2: Run to verify failure** — `./gradlew :app:testDebugUnitTest --tests '*TapClassifierTest*' -q 2>&1 | tail -5` → compile errors.

- [ ] **Step 3: Implement**

```kotlin
package com.wyltek.wallet.prepaid

import com.wyltek.wallet.core.native.CardRecordInfo
import com.wyltek.wallet.core.native.SlotPickFfi
import com.wyltek.wallet.core.native.SlotReason
import com.wyltek.wallet.core.native.SlotResult
import com.wyltek.wallet.prepaid.db.PrepaidCardEntity

sealed class TapClass {
    data class Resume(val card: PrepaidCardEntity, val slot: SlotResult) : TapClass()
    data class Blank(val uidHex: String) : TapClass()
    data class Damaged(val card: PrepaidCardEntity, val reason: String) : TapClass()
    data class Known(val card: PrepaidCardEntity, val record: CardRecordInfo, val slot: SlotResult) : TapClass()
    data class Bench(val record: CardRecordInfo, val slot: SlotResult) : TapClass()
    data class Refused(val reason: String) : TapClass()
}

/** D3 spec §5.1. Pure: the service supplies the two index lookups. Refusals never reach the audit log. */
object TapClassifier {
    const val REASON_OTHER_MERCHANT = "another merchant's card"
    const val REASON_BAD_VERSION = "unsupported card version"
    const val REASON_AMBIGUOUS = "card state ambiguous"
    const val REASON_UNFORMATTED = "unformatted card"
    const val REASON_AUTH = "card failed authentication"
    const val REASON_MOVED = "record belongs to a different tag in the index"
    const val REASON_NO_SLOT = "no valid slot"

    fun classify(uidHex: String, slot: SlotResult, cardByUid: PrepaidCardEntity?, cardByReserve: PrepaidCardEntity?, ourMerchantIdHex: String): TapClass {
        if (cardByUid?.pendingTxHash != null) return TapClass.Resume(cardByUid, slot)
        return when (slot.pick) {
            SlotPickFfi.AMBIGUOUS -> cardByUid?.let { TapClass.Damaged(it, REASON_AMBIGUOUS) } ?: TapClass.Refused(REASON_AMBIGUOUS)
            SlotPickFfi.NONE -> when (slot.reason) {
                SlotReason.BAD_VERSION -> TapClass.Refused(REASON_BAD_VERSION)
                SlotReason.MAC_FAIL -> {
                    val hint = slot.merchantHintHex
                    if (hint != null && !hint.equals(ourMerchantIdHex, ignoreCase = true)) TapClass.Refused(REASON_OTHER_MERCHANT)
                    else cardByUid?.let { TapClass.Damaged(it, REASON_AUTH) } ?: TapClass.Refused(REASON_AUTH)
                }
                SlotReason.NO_VALID_SLOT -> cardByUid?.let { TapClass.Damaged(it, REASON_NO_SLOT) } ?: TapClass.Blank(uidHex)
            }
            SlotPickFfi.A, SlotPickFfi.B -> {
                val rec = slot.record ?: return TapClass.Refused(REASON_NO_SLOT)
                when {
                    rec.reserveIdHex.all { it == '0' } || rec.merchantIdHex.all { it == '0' } -> TapClass.Refused(REASON_UNFORMATTED)
                    !rec.merchantIdHex.equals(ourMerchantIdHex, ignoreCase = true) -> TapClass.Refused(REASON_OTHER_MERCHANT)
                    cardByReserve == null -> TapClass.Bench(rec, slot)
                    !cardByReserve.uid.equals(uidHex, ignoreCase = true) -> TapClass.Refused(REASON_MOVED)
                    else -> TapClass.Known(cardByReserve, rec, slot)
                }
            }
        }
    }
}
```

- [ ] **Step 4: Run to verify pass** → 13 passed.

- [ ] **Step 5: Commit** — `git add app/src/main/java/com/wyltek/wallet/prepaid/TapClassifier.kt app/src/test/java/com/wyltek/wallet/prepaid/TapClassifierTest.kt && git commit -m "feat(prepaid): pure tap classifier (D3 §5.1)" -m "Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"`

---

### Task 7: `PrepaidTxPlanner` and `CkbTxJson` (pure)

**Files:**
- Create: `app/src/main/java/com/wyltek/wallet/prepaid/PrepaidTxPlanner.kt`, `app/src/main/java/com/wyltek/wallet/prepaid/CkbTxJson.kt`
- Test: `app/src/test/java/com/wyltek/wallet/prepaid/PrepaidTxPlannerTest.kt`, `app/src/test/java/com/wyltek/wallet/prepaid/CkbTxJsonTest.kt`

**Interfaces:**
- Consumes: `Utxo`, `OutPoint`, `LockScript` (wallet-core models), `TxOutput` (binding record), `CellSelection.isPureCkb(hasType, data)`.
- Produces: `data class CellDepRef(txHash: String, index: UInt, depType: String)`; `data class PlannedTx(inputs: List<Utxo>, outputs: List<TxOutput>, feeShannons: ULong, changeIndex: Int)`; `class InsufficientFunds(shortfallShannons: ULong): Exception`; `PrepaidTxPlanner.MIN_CHANGE_SHANNONS`, `fee(numInputs, numOutputs): ULong`, `output(capacity: ULong, lock: LockScript): TxOutput`, `outpointKey(Utxo): String` (`"txhash:index"` decimal index), `plan(reserveInputs, walletCells, excludeOutpoints: Set<String>, fixedOutputs, merchantLock): PlannedTx`; `CkbTxJson.build(inputs, outputs, dep: CellDepRef, witness0: String): JsonObject`.

- [ ] **Step 1: Write the failing tests**

`PrepaidTxPlannerTest.kt`:
```kotlin
package com.wyltek.wallet.prepaid

import com.wyltek.wallet.core.model.LockScript
import com.wyltek.wallet.core.model.OutPoint
import com.wyltek.wallet.core.model.Utxo
import org.junit.Assert.*
import org.junit.Test

class PrepaidTxPlannerTest {
    private val lock = LockScript("0x9bd7e06f3ecf4be0f2fcd2188b23f1b9fcc88e5d4b65a8637b17723bbda3cce8", "type", "0x" + "ab".repeat(20))
    private val ckb = 100_000_000uL
    private fun utxo(tx: String, idx: Int, capCkb: ULong, type: LockScript? = null, data: String? = null) =
        Utxo(OutPoint("0x$tx", idx.toUInt()), capCkb * ckb, lock, type, data)
    private val floor = 61uL * ckb

    @Test fun `reserve that covers fee and change funds a void alone`() {
        val reserve = utxo("r", 0, 200uL)
        val p = PrepaidTxPlanner.plan(listOf(reserve), listOf(utxo("w", 0, 500uL)), emptySet(), emptyList(), lock)
        assertEquals(listOf(reserve), p.inputs)
        assertEquals(1, p.outputs.size)
        assertEquals(200uL * ckb - p.feeShannons, p.outputs[0].capacity)
        assertEquals(0, p.changeIndex)
    }

    @Test fun `small reserve pulls the smallest sufficient wallet cells`() {
        val reserve = utxo("r", 0, 61uL)
        val wallet = listOf(utxo("w", 0, 900uL), utxo("w", 1, 90uL), utxo("w", 2, 30uL))
        val p = PrepaidTxPlanner.plan(listOf(reserve), wallet, emptySet(), emptyList(), lock)
        assertEquals(listOf("0xr:0", "0xw:2", "0xw:1"), p.inputs.map { PrepaidTxPlanner.outpointKey(it) })
        assertTrue(p.outputs[0].capacity >= PrepaidTxPlanner.MIN_CHANGE_SHANNONS)
    }

    @Test fun `issue output plus change, change goes last`() {
        val fixed = listOf(PrepaidTxPlanner.output(floor + 250_000uL * 100_000uL, lock))
        val p = PrepaidTxPlanner.plan(emptyList(), listOf(utxo("w", 0, 1000uL)), emptySet(), fixed, lock)
        assertEquals(2, p.outputs.size); assertEquals(1, p.changeIndex)
        assertEquals(fixed[0], p.outputs[0])
        assertEquals(1000uL * ckb - fixed[0].capacity - p.feeShannons, p.outputs[1].capacity)
    }

    @Test fun `index reserves and non pure cells are never wallet inputs`() {
        val wallet = listOf(utxo("other", 0, 300uL), utxo("sudt", 0, 300uL, type = lock), utxo("data", 0, 300uL, data = "0x01"), utxo("w", 0, 300uL))
        val p = PrepaidTxPlanner.plan(emptyList(), wallet, setOf("0xother:0"), emptyList(), lock)
        assertEquals(listOf("0xw:0"), p.inputs.map { PrepaidTxPlanner.outpointKey(it) })
    }

    @Test fun `insufficient reports the shortfall`() {
        try {
            PrepaidTxPlanner.plan(emptyList(), listOf(utxo("w", 0, 50uL)), emptySet(), listOf(PrepaidTxPlanner.output(floor, lock)), lock)
            fail()
        } catch (e: InsufficientFunds) {
            assertEquals(floor + PrepaidTxPlanner.fee(1, 2) + PrepaidTxPlanner.MIN_CHANGE_SHANNONS - 50uL * ckb, e.shortfallShannons)
        }
    }

    @Test fun `fee grows with inputs and outputs`() {
        assertTrue(PrepaidTxPlanner.fee(1, 1) < PrepaidTxPlanner.fee(3, 1))
        assertEquals(1_000uL + 300uL * 4uL, PrepaidTxPlanner.fee(2, 2))
    }
}
```
`CkbTxJsonTest.kt`:
```kotlin
package com.wyltek.wallet.prepaid

import com.wyltek.wallet.core.model.LockScript
import com.wyltek.wallet.core.model.OutPoint
import com.wyltek.wallet.core.model.Utxo
import kotlinx.serialization.json.*
import org.junit.Assert.assertEquals
import org.junit.Test

class CkbTxJsonTest {
    @Test fun `shape matches the node's send_transaction expectations`() {
        val lock = LockScript("0x" + "11".repeat(32), "type", "0xabcd")
        val inputs = listOf(Utxo(OutPoint("0x" + "aa".repeat(32), 1u), 100uL, lock), Utxo(OutPoint("0x" + "bb".repeat(32), 0u), 200uL, lock))
        val outputs = listOf(PrepaidTxPlanner.output(255uL, lock))
        val j = CkbTxJson.build(inputs, outputs, CellDepRef("0x" + "cc".repeat(32), 0u, "dep_group"), "0x55")
        assertEquals("0x0", j["version"]!!.jsonPrimitive.content)
        assertEquals("0x1", j["inputs"]!!.jsonArray[0].jsonObject["previous_output"]!!.jsonObject["index"]!!.jsonPrimitive.content)
        assertEquals("0xff", j["outputs"]!!.jsonArray[0].jsonObject["capacity"]!!.jsonPrimitive.content)
        assertEquals(null, j["outputs"]!!.jsonArray[0].jsonObject["type"])
        assertEquals("0x", j["outputs_data"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals(listOf("0x55", "0x"), j["witnesses"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("dep_group", j["cell_deps"]!!.jsonArray[0].jsonObject["dep_type"]!!.jsonPrimitive.content)
        assertEquals(0, j["header_deps"]!!.jsonArray.size)
    }
}
```

- [ ] **Step 2: Run to verify failure** — both test classes: compile errors.

- [ ] **Step 3: Implement**

`PrepaidTxPlanner.kt`:
```kotlin
package com.wyltek.wallet.prepaid

import com.wyltek.wallet.core.model.LockScript
import com.wyltek.wallet.core.model.Utxo
import com.wyltek.wallet.core.native.TxOutput
import com.wyltek.wallet.data.CellSelection

data class CellDepRef(val txHash: String, val index: UInt, val depType: String)

data class PlannedTx(val inputs: List<Utxo>, val outputs: List<TxOutput>, val feeShannons: ULong, val changeIndex: Int)

class InsufficientFunds(val shortfallShannons: ULong) :
    Exception("insufficient wallet funds: short by ${shortfallShannons / 100_000_000uL} CKB")

/** Input selection and output layout for every prepaid reserve transaction (D3 spec §2.3, §5).
 *  Inputs: the reserve cells first, then the smallest pure-CKB wallet cells until the fixed outputs,
 *  a size-based fee and a change output of at least [MIN_CHANGE_SHANNONS] are covered. The merchant
 *  change is always the LAST output, so settlement successors keep their planned indices. */
object PrepaidTxPlanner {
    /** Same floor `sendCkb` keeps for its change (`minCellCapacity`). */
    val MIN_CHANGE_SHANNONS: ULong = 81uL * 100_000_000uL

    /** Over-estimate at 1 shannon/byte: ~85 B secp witness + ~44 B per input + ~100 B per output. */
    fun fee(numInputs: Int, numOutputs: Int): ULong = 1_000uL + 300uL * (numInputs + numOutputs).toULong()

    fun output(capacity: ULong, lock: LockScript) =
        TxOutput(capacity, lock.codeHash, lock.hashType, lock.args, "", "", "", "")

    fun outpointKey(u: Utxo) = "${u.outPoint.txHash}:${u.outPoint.index}"

    fun plan(reserveInputs: List<Utxo>, walletCells: List<Utxo>, excludeOutpoints: Set<String>, fixedOutputs: List<TxOutput>, merchantLock: LockScript): PlannedTx {
        val need = fixedOutputs.fold(0uL) { a, o -> a + o.capacity }
        val reserveKeys = reserveInputs.map { outpointKey(it) }.toSet()
        val candidates = walletCells
            .filter { CellSelection.isPureCkb(it.type_ != null, it.data) }
            .filter { outpointKey(it) !in excludeOutpoints && outpointKey(it) !in reserveKeys }
            .sortedBy { it.capacity }
        val selected = reserveInputs.toMutableList()
        var total = selected.fold(0uL) { a, u -> a + u.capacity }
        fun required() = need + fee(selected.size, fixedOutputs.size + 1) + MIN_CHANGE_SHANNONS
        for (c in candidates) {
            if (total >= required()) break
            selected += c; total += c.capacity
        }
        if (total < required()) throw InsufficientFunds(required() - total)
        val f = fee(selected.size, fixedOutputs.size + 1)
        return PlannedTx(selected, fixedOutputs + output(total - need - f, merchantLock), f, fixedOutputs.size)
    }
}
```
`CkbTxJson.kt` (the `sendCkb` JSON shape, lifted verbatim):
```kotlin
package com.wyltek.wallet.prepaid

import com.wyltek.wallet.core.model.Utxo
import com.wyltek.wallet.core.native.TxOutput
import kotlinx.serialization.json.*

object CkbTxJson {
    fun build(inputs: List<Utxo>, outputs: List<TxOutput>, dep: CellDepRef, witness0: String): JsonObject = buildJsonObject {
        put("version", "0x0")
        put("cell_deps", buildJsonArray {
            add(buildJsonObject {
                put("out_point", buildJsonObject { put("tx_hash", dep.txHash); put("index", "0x${dep.index.toString(16)}") })
                put("dep_type", dep.depType)
            })
        })
        put("header_deps", buildJsonArray {})
        put("inputs", buildJsonArray {
            for (i in inputs) add(buildJsonObject {
                put("previous_output", buildJsonObject { put("tx_hash", i.outPoint.txHash); put("index", "0x${i.outPoint.index.toString(16)}") })
                put("since", "0x0")
            })
        })
        put("outputs", buildJsonArray {
            for (o in outputs) add(buildJsonObject {
                put("capacity", "0x${o.capacity.toString(16)}")
                put("lock", buildJsonObject { put("code_hash", o.lockCodeHash); put("hash_type", o.lockHashType); put("args", o.lockArgs) })
                if (o.typeCodeHash.isNotBlank()) put("type", buildJsonObject { put("code_hash", o.typeCodeHash); put("hash_type", o.typeHashType); put("args", o.typeArgs) })
            })
        })
        put("outputs_data", buildJsonArray { for (o in outputs) add(o.data.ifEmpty { "0x" }) })
        put("witnesses", buildJsonArray { add(witness0); for (i in 1 until inputs.size) add("0x") })
    }
}
```

- [ ] **Step 4: Run to verify pass** → 7 passed.

- [ ] **Step 5: Commit** — `git add app/src/main/java/com/wyltek/wallet/prepaid/PrepaidTxPlanner.kt app/src/main/java/com/wyltek/wallet/prepaid/CkbTxJson.kt app/src/test/java/com/wyltek/wallet/prepaid && git commit -m "feat(prepaid): reserve transaction planner and tx JSON builder" -m "Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"`

---

### Task 8: Ports, `PrepaidStore`, and the test fakes

**Files:**
- Create: `app/src/main/java/com/wyltek/wallet/prepaid/Ports.kt`, `app/src/main/java/com/wyltek/wallet/prepaid/PrepaidStore.kt`
- Create (test utilities): `app/src/test/java/com/wyltek/wallet/prepaid/fakes/InMemoryPrepaidStore.kt`, `FakeChain.kt`, `FakeSigner.kt`, `FakeProtocol.kt`, `FakeTag.kt`
- Modify: `app/build.gradle.kts` (add `testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")`)
- Test: `app/src/test/java/com/wyltek/wallet/prepaid/FakesSelfTest.kt`

**Interfaces (Produces):**

`Ports.kt`:
```kotlin
package com.wyltek.wallet.prepaid

import com.wyltek.wallet.core.chain.TxStatus
import com.wyltek.wallet.core.model.LockScript
import com.wyltek.wallet.core.model.Utxo
import com.wyltek.wallet.core.native.*
import kotlinx.serialization.json.JsonObject

interface ChainPort {
    suspend fun cellsByLock(lock: LockScript): List<Utxo>
    /** `"txhash:index"` (decimal index). Null when spent or unknown. */
    suspend fun cellAt(outpoint: String): Utxo?
    /** [txHash] is the hash rust-core computed at build time; a real node returns the same hash.
     *  True when the node accepted the transaction, false when it refused. */
    suspend fun broadcast(txJson: JsonObject, txHash: String): Boolean
    suspend fun status(txHash: String): TxStatus?
}

interface SignerPort {
    val lock: LockScript
    val cellDep: CellDepRef
    fun build(inputs: List<Utxo>, outputs: List<TxOutput>): BuiltTransaction
    fun witness0(txHashHex: String, numInputs: Int): String
}

/** The rust-core protocol behind an interface so the service is host-testable. `NativeProtocol` is the real one. */
interface Protocol {
    fun selectSlot(kHex: String, uidHex: String, raw128: ByteArray): SlotResult
    fun seal(kHex: String, uidHex: String, rec: CardRecordInfo): ByteArray
    fun nextSlot(pick: SlotPickFfi): SlotPickFfi
    fun reserveId(txHashHex: String, index: UInt): String
    fun merchantId(lock: LockScript): String
    /** Throws PrepaidException on refusal. */
    fun plan(action: PrepaidAction, balanceUnits: Int, capacityShannons: ULong, amountUnits: Int): ReservePlan
    fun verifyBatch(kHex: String, entriesHex: String, cards: List<CardStateFfi>): BatchResultFfi
    fun lockOf(address: String): LockScript?
}

class TagLostException(message: String) : Exception(message)

interface TagPort {
    val uidHex: String
    fun isPresent(): Boolean
    /** Pages 4..35 as 128 bytes: slot A then slot B. */
    suspend fun readSlots(): ByteArray
    /** Writes the 16 pages of one slot in order, MAC pages (14, 15) last. Throws [TagLostException]. */
    suspend fun writeSlot(slot: SlotPickFfi, raw64: ByteArray)
    suspend fun zeroSlot(slot: SlotPickFfi)
}

enum class Phase { REQUESTED, BROADCAST, COMMITTED, WRITTEN, DONE, FAILED }

sealed class ActionOutcome {
    data object Done : ActionOutcome()
    data class AwaitingTag(val reserveId: String) : ActionOutcome()
    data class StillConfirming(val txHash: String) : ActionOutcome()
    data class Failed(val reason: String) : ActionOutcome()
}
```

`PrepaidStore.kt`:
```kotlin
package com.wyltek.wallet.prepaid

import com.wyltek.wallet.agent.db.AgentDatabase
import com.wyltek.wallet.prepaid.db.PrepaidCardEntity
import com.wyltek.wallet.prepaid.db.PrepaidEventEntity
import kotlinx.coroutines.flow.Flow

interface PrepaidStore {
    suspend fun card(reserveId: String): PrepaidCardEntity?
    suspend fun cardByUid(uid: String): PrepaidCardEntity?
    suspend fun insertCard(c: PrepaidCardEntity)
    suspend fun updateCard(c: PrepaidCardEntity)
    suspend fun deleteIssuing(reserveId: String): Int
    suspend fun allCards(): List<PrepaidCardEntity>
    suspend fun cardsWithPending(): List<PrepaidCardEntity>
    suspend fun activeCount(): Int
    suspend fun event(e: PrepaidEventEntity)
    suspend fun allEvents(): List<PrepaidEventEntity>
    suspend fun eventsForCard(reserveId: String): List<PrepaidEventEntity>
    fun cardsFlow(): Flow<List<PrepaidCardEntity>>
    fun eventsFlow(): Flow<List<PrepaidEventEntity>>
}

class RoomPrepaidStore(db: AgentDatabase) : PrepaidStore {
    private val dao = db.prepaidDao()
    override suspend fun card(reserveId: String) = dao.card(reserveId)
    override suspend fun cardByUid(uid: String) = dao.cardByUid(uid)
    override suspend fun insertCard(c: PrepaidCardEntity) = dao.insertCard(c)
    override suspend fun updateCard(c: PrepaidCardEntity) = dao.updateCard(c)
    override suspend fun deleteIssuing(reserveId: String) = dao.deleteIssuing(reserveId)
    override suspend fun allCards() = dao.allCards()
    override suspend fun cardsWithPending() = dao.cardsWithPending()
    override suspend fun activeCount() = dao.activeCount()
    override suspend fun event(e: PrepaidEventEntity) { dao.insertEvent(e) }
    override suspend fun allEvents() = dao.allEvents()
    override suspend fun eventsForCard(reserveId: String) = dao.eventsForCard(reserveId)
    override fun cardsFlow() = dao.cardsFlow()
    override fun eventsFlow() = dao.eventsFlow()
}
```

Fakes (test source set, package `com.wyltek.wallet.prepaid.fakes`):

```kotlin
// InMemoryPrepaidStore.kt
class InMemoryPrepaidStore : PrepaidStore {
    val cards = linkedMapOf<String, PrepaidCardEntity>()
    val events = mutableListOf<PrepaidEventEntity>()
    private val cardsState = MutableStateFlow<List<PrepaidCardEntity>>(emptyList())
    private val eventsState = MutableStateFlow<List<PrepaidEventEntity>>(emptyList())
    private fun emit() { cardsState.value = cards.values.sortedByDescending { it.updatedAt }; eventsState.value = events.reversed() }
    override suspend fun card(reserveId: String) = cards[reserveId]
    override suspend fun cardByUid(uid: String) = cards.values.firstOrNull { it.uid.equals(uid, true) }
    override suspend fun insertCard(c: PrepaidCardEntity) {
        check(c.reserveId !in cards) { "duplicate reserveId" }
        check(cards.values.none { it.uid.equals(c.uid, true) }) { "duplicate uid" }
        cards[c.reserveId] = c; emit()
    }
    override suspend fun updateCard(c: PrepaidCardEntity) { check(c.reserveId in cards); cards[c.reserveId] = c; emit() }
    override suspend fun deleteIssuing(reserveId: String): Int = if (cards[reserveId]?.state == CARD_ISSUING) { cards.remove(reserveId); emit(); 1 } else 0
    override suspend fun allCards() = cards.values.toList()
    override suspend fun cardsWithPending() = cards.values.filter { it.pendingTxHash != null }
    override suspend fun activeCount() = cards.values.count { it.state in setOf(CARD_ISSUING, CARD_ACTIVE, CARD_REVOKED) }
    override suspend fun event(e: PrepaidEventEntity) { events += e.copy(id = events.size + 1L); emit() }
    override suspend fun allEvents() = events.toList()
    override suspend fun eventsForCard(reserveId: String) = events.filter { it.reserveId == reserveId }.reversed()
    override fun cardsFlow() = cardsState
    override fun eventsFlow() = eventsState
    fun phases(actionId: String) = events.filter { it.actionId == actionId }.map { it.phase }
}

// FakeChain.kt — a tiny UTXO set that applies a broadcast tx the first time it reports CONFIRMED.
class FakeChain : ChainPort {
    val cells = linkedMapOf<String, Utxo>()                       // key "txhash:index"
    val broadcasts = mutableListOf<Pair<String, JsonObject>>()
    val statusQueue = mutableMapOf<String, ArrayDeque<TxStatus>>() // explicit per-hash script; last value repeats
    /** Without an explicit queue: PENDING for this many polls, then CONFIRMED. Null = PENDING forever. */
    var confirmAfterPolls: Int? = 1
    var refuseBroadcast = false
    private val polls = mutableMapOf<String, Int>()
    private val applied = mutableSetOf<String>()

    fun add(u: Utxo) { cells[PrepaidTxPlanner.outpointKey(u)] = u }
    override suspend fun cellsByLock(lock: LockScript) = cells.values.filter { it.lock == lock }
    override suspend fun cellAt(outpoint: String) = cells[outpoint]
    override suspend fun broadcast(txJson: JsonObject, txHash: String): Boolean {
        if (refuseBroadcast) return false
        broadcasts += txHash to txJson
        return true
    }
    override suspend fun status(txHash: String): TxStatus? {
        statusQueue[txHash]?.let { q ->
            val s = if (q.size > 1) q.removeFirst() else q.first()
            if (s == TxStatus.CONFIRMED) applyOnce(txHash)
            return s
        }
        val n = (polls[txHash] ?: 0) + 1; polls[txHash] = n
        val c = confirmAfterPolls ?: return TxStatus.PENDING
        return if (n >= c) { applyOnce(txHash); TxStatus.CONFIRMED } else TxStatus.PENDING
    }
    fun confirm(txHash: String) { statusQueue[txHash] = ArrayDeque(listOf(TxStatus.CONFIRMED)) }
    fun reject(txHash: String) { statusQueue[txHash] = ArrayDeque(listOf(TxStatus.REJECTED)) }
    private fun applyOnce(txHash: String) {
        if (!applied.add(txHash)) return
        val j = broadcasts.firstOrNull { it.first == txHash }?.second ?: return
        j["inputs"]!!.jsonArray.forEach { i ->
            val po = i.jsonObject["previous_output"]!!.jsonObject
            cells.remove(po["tx_hash"]!!.jsonPrimitive.content + ":" + po["index"]!!.jsonPrimitive.content.removePrefix("0x").toUInt(16))
        }
        j["outputs"]!!.jsonArray.forEachIndexed { idx, o ->
            val lo = o.jsonObject["lock"]!!.jsonObject
            add(Utxo(OutPoint(txHash, idx.toUInt()), o.jsonObject["capacity"]!!.jsonPrimitive.content.removePrefix("0x").toULong(16),
                LockScript(lo["code_hash"]!!.jsonPrimitive.content, lo["hash_type"]!!.jsonPrimitive.content, lo["args"]!!.jsonPrimitive.content)))
        }
    }
}

// FakeSigner.kt
class FakeSigner(override val lock: LockScript) : SignerPort {
    override val cellDep = CellDepRef("0x" + "cc".repeat(32), 0u, "dep_group")
    var builds = 0
    override fun build(inputs: List<Utxo>, outputs: List<TxOutput>): BuiltTransaction {
        builds++
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        inputs.forEach { digest.update(PrepaidTxPlanner.outpointKey(it).toByteArray()) }
        outputs.forEach { digest.update(it.capacity.toString().toByteArray()); digest.update(it.lockArgs.toByteArray()) }
        digest.update(builds.toString().toByteArray())
        val h = "0x" + digest.digest().joinToString("") { "%02x".format(it) }
        return BuiltTransaction(rawTransactionHex = "", txHashHex = h, estimatedFee = 0uL,
            totalInputCapacity = inputs.fold(0uL) { a, u -> a + u.capacity }, totalOutputCapacity = outputs.fold(0uL) { a, o -> a + o.capacity })
    }
    override fun witness0(txHashHex: String, numInputs: Int) = "0x" + "77".repeat(85)
}

// FakeProtocol.kt — the record layout of demo-tier §4.1 with a fake 8-byte MAC (sha256 of uid||bytes[0..56]),
// so flows can be tested without the native library. Crypto itself is proven in rust-core.
class FakeProtocol : Protocol {
    val locks = mutableMapOf<String, LockScript>()    // address -> lock for lockOf
    var verifyResult: BatchResultFfi = BatchResultFfi(emptyList(), emptyList())
    private fun mac(uidHex: String, raw: ByteArray): ByteArray {
        val d = java.security.MessageDigest.getInstance("SHA-256"); d.update(uidHex.lowercase().toByteArray()); d.update(raw, 0, 56); return d.digest().copyOf(8)
    }
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun unhex(s: String) = s.removePrefix("0x").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun le32(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
    private fun decode(raw: ByteArray): CardRecordInfo? {
        if (String(raw, 0, 4, Charsets.US_ASCII) != "BBD1" || raw[4] != 0x02.toByte() || raw[5] != 0x01.toByte()) return null
        val bb = java.nio.ByteBuffer.wrap(raw).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        return CardRecordInfo(writerId = bb.getShort(6).toUShort(), reserveIdHex = hex(raw.copyOfRange(8, 40)), balanceUnits = bb.getInt(40),
            counter = bb.getInt(44).toUInt(), merchantIdHex = hex(raw.copyOfRange(48, 56)))
    }
    private fun valid(uidHex: String, raw: ByteArray) = decode(raw)?.takeIf { mac(uidHex, raw).contentEquals(raw.copyOfRange(56, 64)) }

    override fun selectSlot(kHex: String, uidHex: String, raw128: ByteArray): SlotResult {
        require(raw128.size == 128)
        val a = raw128.copyOfRange(0, 64); val b = raw128.copyOfRange(64, 128)
        val va = valid(uidHex, a); val vb = valid(uidHex, b)
        val none = SlotResult(SlotPickFfi.NONE, null, SlotReason.NO_VALID_SLOT, null)
        return when {
            va == null && vb == null -> {
                val badVer = listOf(a, b).any { String(it, 0, 4, Charsets.US_ASCII) == "BBD1" && decode(it) == null }
                if (badVer) none.copy(reason = SlotReason.BAD_VERSION)
                else listOf(a, b).firstNotNullOfOrNull { decode(it) }?.let { none.copy(reason = SlotReason.MAC_FAIL, merchantHintHex = it.merchantIdHex) } ?: none
            }
            va != null && vb == null -> SlotResult(SlotPickFfi.A, va, SlotReason.NO_VALID_SLOT, null)
            va == null -> SlotResult(SlotPickFfi.B, vb, SlotReason.NO_VALID_SLOT, null)
            va!!.counter > vb!!.counter -> SlotResult(SlotPickFfi.A, va, SlotReason.NO_VALID_SLOT, null)
            vb.counter > va.counter -> SlotResult(SlotPickFfi.B, vb, SlotReason.NO_VALID_SLOT, null)
            a.contentEquals(b) -> SlotResult(SlotPickFfi.A, va, SlotReason.NO_VALID_SLOT, null)
            else -> SlotResult(SlotPickFfi.AMBIGUOUS, null, SlotReason.NO_VALID_SLOT, null)
        }
    }
    override fun seal(kHex: String, uidHex: String, rec: CardRecordInfo): ByteArray {
        val raw = ByteArray(64)
        "BBD1".toByteArray(Charsets.US_ASCII).copyInto(raw, 0); raw[4] = 0x02; raw[5] = 0x01
        raw[6] = rec.writerId.toByte(); raw[7] = (rec.writerId.toInt() shr 8).toByte()
        unhex(rec.reserveIdHex).copyInto(raw, 8); le32(rec.balanceUnits).copyInto(raw, 40); le32(rec.counter.toInt()).copyInto(raw, 44)
        unhex(rec.merchantIdHex).copyInto(raw, 48); mac(uidHex, raw).copyInto(raw, 56)
        return raw
    }
    override fun nextSlot(pick: SlotPickFfi) = if (pick == SlotPickFfi.A) SlotPickFfi.B else SlotPickFfi.A
    override fun reserveId(txHashHex: String, index: UInt): String {
        val d = java.security.MessageDigest.getInstance("SHA-256"); d.update((txHashHex + ":" + index).toByteArray()); return hex(d.digest())
    }
    override fun merchantId(lock: LockScript) = "1122334455667788"
    override fun plan(action: PrepaidAction, balanceUnits: Int, capacityShannons: ULong, amountUnits: Int): ReservePlan {
        val unit = 100_000uL; val floor = 6_100_000_000uL; val max = 1_000_000
        fun expected(b: Int) = floor + b.toULong() * unit
        fun bad(m: String): Nothing = throw PrepaidException.Plan(m)
        return when (action) {
            PrepaidAction.ISSUE -> { if (amountUnits <= 0) bad("amount must be positive"); if (amountUnits > max) bad("over cap"); ReservePlan(expected(amountUnits), 0uL, amountUnits) }
            PrepaidAction.LOAD -> { if (amountUnits <= 0) bad("amount must be positive"); if (amountUnits > max) bad("over cap")
                if (capacityShannons != expected(balanceUnits)) bad("reserve capacity does not match the index balance; use Inspect")
                val after = balanceUnits.toLong() + amountUnits; if (after > Int.MAX_VALUE) bad("balance overflow"); ReservePlan(expected(after.toInt()), 0uL, after.toInt()) }
            PrepaidAction.VOID -> { if (capacityShannons < floor) bad("below floor"); ReservePlan(0uL, 0uL, 0) }
            PrepaidAction.CASH_OUT -> { if (balanceUnits <= 0) bad("amount must be positive"); if (capacityShannons != expected(balanceUnits)) bad("mismatch")
                val c = balanceUnits.toULong() * unit; if (c < floor) bad("balance below the 61 CKB minimum cell; pay out in cash and void"); ReservePlan(0uL, c, 0) }
        }
    }
    var lastCards: List<CardStateFfi> = emptyList()
    override fun verifyBatch(kHex: String, entriesHex: String, cards: List<CardStateFfi>): BatchResultFfi { lastCards = cards; return verifyResult }
    override fun lockOf(address: String) = locks[address]
}

// FakeTag.kt — 36 pages of 4 bytes (0..35); slots at pages 4..19 and 20..35; optional torn write.
class FakeTag(override val uidHex: String) : TagPort {
    val pages = Array(36) { ByteArray(4) }
    var present = true
    var failAfterPages: Int? = null       // throw TagLost after this many page writes in the next writeSlot
    var pageWrites = 0
    override fun isPresent() = present
    private fun first(slot: SlotPickFfi) = if (slot == SlotPickFfi.A) 4 else 20
    override suspend fun readSlots(): ByteArray { if (!present) throw TagLostException("gone"); return (4 until 36).fold(ByteArray(0)) { a, p -> a + pages[p] } }
    override suspend fun writeSlot(slot: SlotPickFfi, raw64: ByteArray) {
        require(slot == SlotPickFfi.A || slot == SlotPickFfi.B); require(raw64.size == 64)
        if (!present) throw TagLostException("gone")
        for (p in 0 until 16) {
            failAfterPages?.let { if (p >= it) { failAfterPages = null; present = false; throw TagLostException("torn at page $p") } }
            pages[first(slot) + p] = raw64.copyOfRange(p * 4, p * 4 + 4); pageWrites++
        }
    }
    override suspend fun zeroSlot(slot: SlotPickFfi) = writeSlot(slot, ByteArray(64))
    fun slot(slot: SlotPickFfi): ByteArray = (0 until 16).fold(ByteArray(0)) { a, p -> a + pages[first(slot) + p] }
}
```
- [ ] **Step 1: Write the fakes' self-test** `FakesSelfTest.kt` (so later tasks can trust them):

```kotlin
class FakesSelfTest {
    private val uid = "04958caa5c5e80"
    private val rec = CardRecordInfo(0u, "a0".repeat(32), 1000, 3u, "1122334455667788")

    @Test fun `fake protocol seal and select agree and bind the uid`() {
        val p = FakeProtocol()
        val raw = p.seal("k", uid, rec)
        val r = p.selectSlot("k", uid, raw + ByteArray(64))
        assertEquals(SlotPickFfi.A, r.pick); assertEquals(rec, r.record)
        val other = p.selectSlot("k", "04ebbb02cd2a81", raw + ByteArray(64))
        assertEquals(SlotPickFfi.NONE, other.pick); assertEquals(SlotReason.MAC_FAIL, other.reason); assertEquals(rec.merchantIdHex, other.merchantHintHex)
        val r2 = p.selectSlot("k", uid, raw + p.seal("k", uid, rec.copy(counter = 4u, balanceUnits = 900)))
        assertEquals(SlotPickFfi.B, r2.pick); assertEquals(900, r2.record!!.balanceUnits)
    }

    @Test fun `fake tag torn write keeps the other slot and goes absent`() = runTest {
        val p = FakeProtocol(); val t = FakeTag(uid)
        t.writeSlot(SlotPickFfi.A, p.seal("k", uid, rec))
        t.failAfterPages = 9
        try { t.writeSlot(SlotPickFfi.B, p.seal("k", uid, rec.copy(counter = 4u))); fail() } catch (e: TagLostException) {}
        assertFalse(t.isPresent()); t.present = true
        val r = p.selectSlot("k", uid, t.readSlots())
        assertEquals(SlotPickFfi.A, r.pick); assertEquals(3u, r.record!!.counter)
    }

    @Test fun `fake chain applies a confirmed broadcast`() = runTest {
        val lock = LockScript("0x" + "11".repeat(32), "type", "0xab")
        val c = FakeChain(); c.add(Utxo(OutPoint("0x" + "aa".repeat(32), 0u), 10_000_000_000uL, lock))
        val tx = CkbTxJson.build(c.cells.values.toList(), listOf(PrepaidTxPlanner.output(9_000_000_000uL, lock)), CellDepRef("0x" + "cc".repeat(32), 0u, "dep_group"), "0x")
        val h = "0x" + "dd".repeat(32)
        c.confirmAfterPolls = null
        assertTrue(c.broadcast(tx, h))
        assertEquals(TxStatus.PENDING, c.status(h))
        c.confirm(h); assertEquals(TxStatus.CONFIRMED, c.status(h))
        assertEquals(listOf("$h:0"), c.cells.keys.toList()); assertEquals(9_000_000_000uL, c.cellAt("$h:0")!!.capacity)
    }
}
```

- [ ] **Step 2: Run to verify failure** — `./gradlew :app:testDebugUnitTest --tests '*FakesSelfTest*' -q` → compile errors (ports and fakes missing).

- [ ] **Step 3: Implement** Ports.kt, PrepaidStore.kt, the five fakes, and the gradle test dependency as listed above.

- [ ] **Step 4: Run to verify pass** → 3 passed; `./gradlew :app:compileDebugKotlin -q` OK.

- [ ] **Step 5: Commit** — `git add app/build.gradle.kts app/src/main/java/com/wyltek/wallet/prepaid/Ports.kt app/src/main/java/com/wyltek/wallet/prepaid/PrepaidStore.kt app/src/test/java/com/wyltek/wallet/prepaid && git commit -m "feat(prepaid): ports, store interface and host test fakes" -m "Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"`

---

### Task 9: NTAG page I/O — `SlotPages`, `MifareTagPort`, tag filter

**Files:**
- Create: `app/src/main/java/com/wyltek/wallet/prepaid/nfc/SlotPages.kt`, `app/src/main/java/com/wyltek/wallet/prepaid/nfc/MifareTagPort.kt`
- Test: `app/src/test/java/com/wyltek/wallet/prepaid/SlotPagesTest.kt`

**Interfaces:**
- Produces: `SlotPages.FIRST_USER_PAGE = 4`, `PAGES_PER_SLOT = 16`, `firstPage(slot: SlotPickFfi): Int` (A→4, B→20), `writeOrder(): List<Int>` = `0..15` (MAC pages 14 and 15 are last by construction), `readOffsets(): List<Int>` = `[4, 8, 12, 16, 20, 24, 28, 32]`, `uidHexOf(id: ByteArray): String`, `isNtag21x(idLen: Int, sak: Short, hasUltralight: Boolean): Boolean`; `class MifareTagPort(tag: android.nfc.Tag) : TagPort`.

- [ ] **Step 1: Write the failing tests**

```kotlin
class SlotPagesTest {
    @Test fun `slot pages and read offsets cover exactly pages 4 to 35`() {
        assertEquals(4, SlotPages.firstPage(SlotPickFfi.A)); assertEquals(20, SlotPages.firstPage(SlotPickFfi.B))
        assertEquals(listOf(4, 8, 12, 16, 20, 24, 28, 32), SlotPages.readOffsets())
        assertEquals((0..15).toList(), SlotPages.writeOrder())
        assertEquals(listOf(14, 15), SlotPages.writeOrder().takeLast(2))
    }
    @Test fun `only 7 byte uid type a with sak 0 and ultralight tech is an ntag`() {
        assertTrue(SlotPages.isNtag21x(7, 0, true))
        assertFalse(SlotPages.isNtag21x(4, 0x08, false))   // MIFARE Classic
        assertFalse(SlotPages.isNtag21x(7, 0x20, false))   // DESFire
        assertFalse(SlotPages.isNtag21x(7, 0, false))
    }
    @Test fun `uid hex is lowercase without prefix`() { assertEquals("04958caa5c5e80", SlotPages.uidHexOf(byteArrayOf(0x04, 0x95.toByte(), 0x8c.toByte(), 0xaa.toByte(), 0x5c, 0x5e, 0x80.toByte()))) }
}
```

- [ ] **Step 2: Run to verify failure** — compile errors.

- [ ] **Step 3: Implement**

`SlotPages.kt`:
```kotlin
package com.wyltek.wallet.prepaid.nfc

import com.wyltek.wallet.core.native.SlotPickFfi

/** NTAG213/215/216 user memory layout for the two 64-byte record slots (demo-tier spec §4.1). */
object SlotPages {
    const val FIRST_USER_PAGE = 4
    const val PAGES_PER_SLOT = 16
    fun firstPage(slot: SlotPickFfi): Int = when (slot) { SlotPickFfi.A -> 4; SlotPickFfi.B -> 20; else -> throw IllegalArgumentException("slot $slot") }
    /** Page index within a slot, in write order. The MAC occupies bytes 56..63 = pages 14 and 15, written last. */
    fun writeOrder(): List<Int> = (0 until PAGES_PER_SLOT).toList()
    /** `readPages(offset)` returns 4 pages; 8 reads cover 4..35. */
    fun readOffsets(): List<Int> = (0 until 8).map { FIRST_USER_PAGE + it * 4 }
    fun uidHexOf(id: ByteArray): String = id.joinToString("") { "%02x".format(it) }
    /** Demo-tier §2.4: 7-byte UID, ISO 14443-3A, SAK 0x00, Ultralight command set. */
    fun isNtag21x(idLen: Int, sak: Short, hasUltralight: Boolean): Boolean = idLen == 7 && sak.toInt() == 0 && hasUltralight
}
```
`MifareTagPort.kt`:
```kotlin
package com.wyltek.wallet.prepaid.nfc

import android.nfc.Tag
import android.nfc.tech.MifareUltralight
import android.nfc.tech.NfcA
import com.wyltek.wallet.core.native.SlotPickFfi
import com.wyltek.wallet.prepaid.TagLostException
import com.wyltek.wallet.prepaid.TagPort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/** [TagPort] over Android's MifareUltralight. One connection per call; every I/O error is a [TagLostException]. */
class MifareTagPort(private val tag: Tag) : TagPort {
    override val uidHex: String = SlotPages.uidHexOf(tag.id)

    override fun isPresent(): Boolean = try {
        val mu = MifareUltralight.get(tag) ?: return false
        mu.connect(); val ok = mu.isConnected; mu.close(); ok
    } catch (e: Exception) { false }

    private suspend fun <T> session(block: (MifareUltralight) -> T): T = withContext(Dispatchers.IO) {
        val mu = MifareUltralight.get(tag) ?: throw TagLostException("not an Ultralight tag")
        try { mu.connect(); block(mu) } catch (e: IOException) { throw TagLostException(e.message ?: "tag lost") }
        finally { try { mu.close() } catch (_: IOException) {} }
    }

    override suspend fun readSlots(): ByteArray = session { mu ->
        SlotPages.readOffsets().fold(ByteArray(0)) { acc, off -> acc + mu.readPages(off) }
    }

    override suspend fun writeSlot(slot: SlotPickFfi, raw64: ByteArray) {
        require(raw64.size == 64)
        session { mu ->
            val first = SlotPages.firstPage(slot)
            for (p in SlotPages.writeOrder()) mu.writePage(first + p, raw64.copyOfRange(p * 4, p * 4 + 4))
        }
    }

    override suspend fun zeroSlot(slot: SlotPickFfi) = writeSlot(slot, ByteArray(64))

    companion object {
        fun accepts(tag: Tag): Boolean {
            val a = NfcA.get(tag) ?: return false
            return SlotPages.isNtag21x(tag.id.size, a.sak, tag.techList.contains(MifareUltralight::class.java.name))
        }
    }
}
```

- [ ] **Step 4: Run to verify pass** → 3 passed; `compileDebugKotlin` OK.

- [ ] **Step 5: Commit** — `git add app/src/main/java/com/wyltek/wallet/prepaid/nfc app/src/test/java/com/wyltek/wallet/prepaid/SlotPagesTest.kt && git commit -m "feat(prepaid): NTAG slot page map and MifareUltralight tag port" -m "Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"`

---

### Task 10: `PrepaidService` — classify, issue, load, resume, recover (chain ahead of card)

**Files:**
- Create: `app/src/main/java/com/wyltek/wallet/prepaid/PrepaidService.kt`
- Test: `app/src/test/java/com/wyltek/wallet/prepaid/PrepaidServiceIssueLoadTest.kt`

**Interfaces:**
- Consumes: everything from Tasks 4–8.
- Produces: `class PrepaidService(store, chain, signer, protocol, keys, clock, newActionId, pollDelayMs = 3_000, pollBudgetMs = 90_000)` with `val merchantIdHex`, `val lock`, `suspend fun classify(tag: TagPort): TapClass`, `suspend fun issue(tag, amountUnits: Int, label: String?, progress: (Phase) -> Unit = {}): ActionOutcome`, `suspend fun load(tag, card, amountUnits, progress): ActionOutcome`, `suspend fun resume(tag, card, progress): ActionOutcome`, `suspend fun recoverPending(): Int`, plus the internal helpers Task 11 and 12 extend: `exclusive`, `Audit`, `log`, `broadcastAndAwait`, `awaitCommit`, `writePending`, `excludedOutpoints`, `clearPending`, `kindOfPending`.

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.wyltek.wallet.prepaid

import com.wyltek.wallet.core.model.LockScript
import com.wyltek.wallet.core.model.OutPoint
import com.wyltek.wallet.core.model.Utxo
import com.wyltek.wallet.core.native.CardRecordInfo
import com.wyltek.wallet.core.native.SlotPickFfi
import com.wyltek.wallet.prepaid.db.*
import com.wyltek.wallet.prepaid.fakes.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PrepaidServiceIssueLoadTest {
    private val ckb = 100_000_000uL
    private val unit = 100_000uL
    private val floor = 61uL * ckb
    private val lock = LockScript("0x9bd7e06f3ecf4be0f2fcd2188b23f1b9fcc88e5d4b65a8637b17723bbda3cce8", "type", "0x" + "ab".repeat(20))
    private val uid1 = "04958caa5c5e80"; private val uid2 = "04ebbb02cd2a81"

    class H(walletCkb: ULong = 1000uL, val lock: LockScript) {
        val store = InMemoryPrepaidStore(); val chain = FakeChain(); val signer = FakeSigner(lock); val protocol = FakeProtocol()
        var now = 1_000L; var ids = 0
        val keys = PrepaidKeys(load = { null }, store = { _, _ -> }, random = { ByteArray(16) { 1 } })
        val svc = PrepaidService(store, chain, signer, protocol, keys, clock = { now++ }, newActionId = { "a${++ids}" }, pollDelayMs = 3_000, pollBudgetMs = 90_000)
        init { chain.add(Utxo(OutPoint("0x" + "aa".repeat(32), 0u), walletCkb * 100_000_000uL, lock)) }
        fun phases(actionId: String) = store.phases(actionId)
    }
    private fun h(walletCkb: ULong = 1000uL) = H(walletCkb, lock)

    @Test fun issue_happy_path_writes_tag_and_logs_phases() = runTest {
        val h = h(); val tag = FakeTag(uid1)
        assertTrue(h.svc.classify(tag) is TapClass.Blank)
        val out = h.svc.issue(tag, 250_000, "Card 1")
        assertEquals(ActionOutcome.Done, out)
        val card = h.store.cardByUid(uid1)!!
        assertEquals(CARD_ACTIVE, card.state); assertEquals(250_000, card.balanceUnits); assertEquals(0L, card.counter)
        assertNull(card.pendingUnits); assertNull(card.pendingTxHash); assertEquals("Card 1", card.label)
        assertEquals(floor + 250_000uL * unit, h.chain.cellAt(card.currentOutpoint)!!.capacity)
        val slot = h.protocol.selectSlot("", uid1, tag.readSlots())
        assertEquals(SlotPickFfi.A, slot.pick); assertEquals(250_000, slot.record!!.balanceUnits); assertEquals(0u, slot.record!!.counter)
        assertTrue(tag.slot(SlotPickFfi.B).all { it == 0.toByte() })
        assertEquals(listOf(PHASE_REQUESTED, PHASE_BROADCAST, PHASE_COMMITTED, PHASE_WRITTEN, PHASE_DONE), h.phases("a1"))
        assertTrue(h.store.events.all { it.kind == KIND_ISSUE && it.reserveId == card.reserveId })
        assertTrue(h.svc.classify(tag) is TapClass.Known)
    }

    @Test fun issue_refused_broadcast_leaves_no_card_and_logs_failed() = runTest {
        val h = h(); h.chain.refuseBroadcast = true
        val out = h.svc.issue(FakeTag(uid1), 1000, null)
        assertTrue(out is ActionOutcome.Failed)
        assertNull(h.store.cardByUid(uid1))
        assertEquals(listOf(PHASE_REQUESTED, PHASE_FAILED), h.phases("a1"))
    }

    @Test fun issue_timeout_keeps_pending_then_resume_completes() = runTest {
        val h = h(); h.chain.confirmAfterPolls = null; val tag = FakeTag(uid1)
        val out = h.svc.issue(tag, 1000, null)
        assertTrue(out is ActionOutcome.StillConfirming)
        val card = h.store.cardByUid(uid1)!!
        assertEquals(CARD_ISSUING, card.state); assertEquals(1000, card.pendingUnits); assertFalse(card.pendingCommitted)
        assertEquals(listOf(PHASE_REQUESTED, PHASE_BROADCAST), h.phases("a1"))
        assertTrue(h.svc.classify(tag) is TapClass.Resume)
        assertTrue(h.svc.resume(tag, card) is ActionOutcome.StillConfirming)
        h.chain.confirm(card.pendingTxHash!!)
        assertEquals(ActionOutcome.Done, h.svc.resume(tag, h.store.cardByUid(uid1)!!))
        assertEquals(listOf(PHASE_REQUESTED, PHASE_BROADCAST, PHASE_COMMITTED, PHASE_WRITTEN, PHASE_DONE), h.phases("a1"))
        assertEquals(CARD_ACTIVE, h.store.cardByUid(uid1)!!.state)
    }

    @Test fun issue_rejected_after_broadcast_removes_the_issuing_row() = runTest {
        val h = h(); h.chain.confirmAfterPolls = null; val tag = FakeTag(uid1)
        h.svc.issue(tag, 1000, null)
        val hash = h.store.cardByUid(uid1)!!.pendingTxHash!!
        h.chain.reject(hash)
        assertTrue(h.svc.resume(tag, h.store.cardByUid(uid1)!!) is ActionOutcome.Failed)
        assertNull(h.store.cardByUid(uid1))
        assertEquals(PHASE_FAILED, h.phases("a1").last())
    }

  @Test fun torn_write_after_commit_resumes_on_next_tap() = runTest {
        val h = h(); val tag = FakeTag(uid1); tag.failAfterPages = 5
        val out = h.svc.issue(tag, 1000, null)
        assertTrue(out is ActionOutcome.AwaitingTag)
        val card = h.store.cardByUid(uid1)!!
        assertEquals(CARD_ACTIVE, card.state); assertEquals(1000, card.pendingUnits); assertTrue(card.pendingCommitted)
        assertEquals(1000, card.balanceUnits)   // chain view already credited
        assertEquals(PHASE_FAILED, h.phases("a1").last())
        tag.present = true
        assertTrue(h.svc.classify(tag) is TapClass.Resume)
        assertEquals(ActionOutcome.Done, h.svc.resume(tag, h.store.cardByUid(uid1)!!))
        val slot = h.protocol.selectSlot("", uid1, tag.readSlots())
        assertEquals(SlotPickFfi.A, slot.pick); assertEquals(1000, slot.record!!.balanceUnits)
        assertTrue(h.phases("a1").takeLast(2) == listOf(PHASE_WRITTEN, PHASE_DONE))
    }

    @Test fun load_happy_path_grows_reserve_and_advances_counter() = runTest {
        val h = h(); val tag = FakeTag(uid1)
        h.svc.issue(tag, 250_000, null)
        val before = h.store.cardByUid(uid1)!!
        assertEquals(ActionOutcome.Done, h.svc.load(tag, before, 100_000))
        val after = h.store.cardByUid(uid1)!!
        assertEquals(350_000, after.balanceUnits); assertEquals(1L, after.counter); assertNotEquals(before.currentOutpoint, after.currentOutpoint)
        assertNull(h.chain.cellAt(before.currentOutpoint)); assertEquals(floor + 350_000uL * unit, h.chain.cellAt(after.currentOutpoint)!!.capacity)
        val slot = h.protocol.selectSlot("", uid1, tag.readSlots())
        assertEquals(SlotPickFfi.B, slot.pick); assertEquals(350_000, slot.record!!.balanceUnits); assertEquals(1u, slot.record!!.counter)
        assertEquals(listOf(PHASE_REQUESTED, PHASE_BROADCAST, PHASE_COMMITTED, PHASE_WRITTEN, PHASE_DONE), h.phases("a2"))
        assertEquals(KIND_LOAD, h.store.events.last().kind)
    }

    @Test fun load_uses_the_tag_balance_when_terminal_taps_are_unsettled() = runTest {
        val h = h(); val tag = FakeTag(uid1)
        h.svc.issue(tag, 1000, null)
        val card = h.store.cardByUid(uid1)!!
        // a POS wrote counter 5 / balance 900 into slot B since
        tag.writeSlot(SlotPickFfi.B, h.protocol.seal("", uid1, CardRecordInfo(3u, card.reserveId, 900, 5u, h.svc.merchantIdHex)))
        assertEquals(ActionOutcome.Done, h.svc.load(tag, card, 100))
        val slot = h.protocol.selectSlot("", uid1, tag.readSlots())
        assertEquals(SlotPickFfi.A, slot.pick); assertEquals(1000, slot.record!!.balanceUnits); assertEquals(6u, slot.record!!.counter)
        val after = h.store.cardByUid(uid1)!!
        assertEquals(1100, after.balanceUnits); assertEquals(6L, after.counter)   // index = chain view; counter follows the tag
    }

    @Test fun load_never_spends_another_cards_reserve() = runTest {
        val h = h(1000uL); val t1 = FakeTag(uid1); val t2 = FakeTag(uid2)
        assertEquals(ActionOutcome.Done, h.svc.issue(t1, 250_000, null))
        assertEquals(ActionOutcome.Done, h.svc.issue(t2, 250_000, null))
        val c1 = h.store.cardByUid(uid1)!!; val c2 = h.store.cardByUid(uid2)!!
        val out = h.svc.load(t1, c1, 310_000)   // wallet change (~378 CKB) < 310 + fee + 81 change; c2's 311 CKB reserve must NOT be touched
        assertTrue(out.toString(), out is ActionOutcome.Failed && (out as ActionOutcome.Failed).reason.contains("insufficient"))
        assertEquals(2, h.chain.broadcasts.size)
        assertNotNull(h.chain.cellAt(c2.currentOutpoint))
        assertNull(h.store.cardByUid(uid1)!!.pendingTxHash)
    }

    @Test fun resume_refuses_a_different_uid() = runTest {
        val h = h(); h.chain.confirmAfterPolls = null
        h.svc.issue(FakeTag(uid1), 1000, null)
        val card = h.store.cardByUid(uid1)!!; h.chain.confirm(card.pendingTxHash!!)
        val n = h.store.events.size
        val out = h.svc.resume(FakeTag(uid2), card)
        assertTrue(out is ActionOutcome.Failed && (out as ActionOutcome.Failed).reason.contains("different tag"))
        assertEquals(n + 1, h.store.events.size)              // only the COMMITTED row was added by the status check
        assertEquals(1000, h.store.cardByUid(uid1)!!.pendingUnits)
        assertNull(h.store.cardByUid(uid2))
    }

    @Test fun second_action_while_busy_is_refused_without_rows() = runTest {
        val h = h(); h.chain.confirmAfterPolls = null
        val job = launch { h.svc.issue(FakeTag(uid1), 1000, null) }
        runCurrent()
        val n = h.store.events.size
        assertEquals(ActionOutcome.Failed("busy"), h.svc.issue(FakeTag(uid2), 1000, null))
        assertEquals(n, h.store.events.size); assertNull(h.store.cardByUid(uid2))
        job.cancel()
    }

    @Test fun recover_pending_marks_committed_without_a_tag() = runTest {
        val h = h(); h.chain.confirmAfterPolls = null
        h.svc.issue(FakeTag(uid1), 1000, null)
        val card = h.store.cardByUid(uid1)!!; h.chain.confirm(card.pendingTxHash!!)
        assertEquals(1, h.svc.recoverPending())
        val c = h.store.cardByUid(uid1)!!
        assertTrue(c.pendingCommitted); assertEquals(CARD_ACTIVE, c.state); assertEquals(1000, c.pendingUnits); assertEquals(1000, c.balanceUnits)
        assertEquals(PHASE_COMMITTED, h.phases("a1").last())
        assertEquals(0, h.svc.recoverPending())   // idempotent: nothing new to do
    }

    @Test fun card_limit_and_duplicate_uid_are_refused_before_any_row() = runTest {
        val h = h(); 
        repeat(64) { i -> h.store.cards["r$i"] = PrepaidCardEntity("r$i", "u$i", null, "x:0", "x:0", 0, 0, 0, CARD_ACTIVE, issuedAt = 0, updatedAt = 0) }
        val out = h.svc.issue(FakeTag(uid1), 1000, null)
        assertTrue(out is ActionOutcome.Failed && (out as ActionOutcome.Failed).reason.contains("limit"))
        assertTrue(h.store.events.isEmpty())
        val out2 = h.svc.issue(FakeTag("u3"), 1000, null)
        assertTrue(out2 is ActionOutcome.Failed && (out2 as ActionOutcome.Failed).reason.contains("already"))
    }

    @Test fun load_is_refused_while_a_credit_is_pending_or_card_not_active() = runTest {
        val h = h(); h.chain.confirmAfterPolls = null; val tag = FakeTag(uid1)
        h.svc.issue(tag, 1000, null)
        val out = h.svc.load(tag, h.store.cardByUid(uid1)!!, 10)
        assertTrue(out is ActionOutcome.Failed && (out as ActionOutcome.Failed).reason.contains("pending"))
    }
}
```

- [ ] **Step 2: Run to verify failure** — `./gradlew :app:testDebugUnitTest --tests '*PrepaidServiceIssueLoadTest*' -q 2>&1 | tail -5` → compile errors.

- [ ] **Step 3: Implement `PrepaidService.kt`**

```kotlin
package com.wyltek.wallet.prepaid

import com.wyltek.wallet.core.chain.TxStatus
import com.wyltek.wallet.core.model.LockScript
import com.wyltek.wallet.core.model.Utxo
import com.wyltek.wallet.core.native.*
import com.wyltek.wallet.prepaid.db.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex

/**
 * Every prepaid flow (D3 spec §5–§6). One action at a time (a mutex; a second caller gets
 * `Failed("busy")`). Chain first, tag second: a credit is committed on chain before the tag is
 * written, and a credit the tag never received stays in `pendingUnits` until the next tap.
 * Audit rows are appended at every phase and never touched again.
 */
class PrepaidService(
    private val store: PrepaidStore,
    private val chain: ChainPort,
    private val signer: SignerPort,
    private val protocol: Protocol,
    private val keys: PrepaidKeys,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val newActionId: () -> String = { java.util.UUID.randomUUID().toString() },
    private val pollDelayMs: Long = 3_000,
    private val pollBudgetMs: Long = 90_000,
) {
    companion object {
        const val MAX_ACTIVE_CARDS = 64
        const val UNIT_SHANNONS = 100_000L
    }

    val lock: LockScript get() = signer.lock
    val merchantIdHex: String by lazy { protocol.merchantId(signer.lock) }
    private val kHex: String get() = keys.kDebitHex()
    private val mutex = Mutex()

    // ---------------------------------------------------------------- audit
    internal class Audit(val actionId: String, val kind: String, val uid: String, val reserveId: String?, val amount: Long,
                         val balanceBefore: Int, val counterBefore: Long)

    internal suspend fun log(a: Audit, phase: String, balanceAfter: Int = a.balanceBefore, counterAfter: Long = a.counterBefore,
                             txHash: String? = null, detail: String = "") {
        store.event(PrepaidEventEntity(atMs = clock(), actionId = a.actionId, kind = a.kind, phase = phase, reserveId = a.reserveId,
            uid = a.uid, amountUnits = a.amount, balanceBefore = a.balanceBefore, balanceAfter = balanceAfter,
            counterBefore = a.counterBefore, counterAfter = counterAfter, txHash = txHash, detail = detail))
    }

    /** Runs [block] if no other action is in flight; null means busy. */
    internal suspend fun <T> exclusive(block: suspend () -> T): T? =
        if (mutex.tryLock()) try { block() } finally { mutex.unlock() } else null

    private fun busy() = ActionOutcome.Failed("busy")

    // ---------------------------------------------------------------- classify
    suspend fun classify(tag: TagPort): TapClass {
        val raw = try { tag.readSlots() } catch (e: TagLostException) { return TapClass.Refused("tag lost while reading") }
        val slot = protocol.selectSlot(kHex, tag.uidHex, raw)
        val byUid = store.cardByUid(tag.uidHex)
        val byReserve = slot.record?.let { store.card(it.reserveIdHex) }
        return TapClassifier.classify(tag.uidHex, slot, byUid, byReserve, merchantIdHex)
    }

    // ---------------------------------------------------------------- chain helpers
    internal sealed class TxResult {
        data class Committed(val txHash: String) : TxResult()
        data class Timeout(val txHash: String) : TxResult()
        data class Refused(val reason: String) : TxResult()
        data class Rejected(val txHash: String) : TxResult()
    }

    /** Every reserve cell in the index; never a fee input for another card's transaction. */
    internal suspend fun excludedOutpoints(): Set<String> = store.allCards().map { it.currentOutpoint }.toSet()

    internal suspend fun awaitCommit(txHash: String): TxStatus {
        var waited = 0L
        while (true) {
            when (val s = chain.status(txHash)) { TxStatus.CONFIRMED, TxStatus.REJECTED -> return s; else -> {} }
            if (waited >= pollBudgetMs) return TxStatus.PENDING
            delay(pollDelayMs); waited += pollDelayMs
        }
    }

    internal suspend fun broadcastAndAwait(a: Audit, planned: PlannedTx, built: BuiltTransaction, progress: (Phase) -> Unit): TxResult {
        val json = CkbTxJson.build(planned.inputs, planned.outputs, signer.cellDep, signer.witness0(built.txHashHex, planned.inputs.size))
        if (!chain.broadcast(json, built.txHashHex)) {
            log(a, PHASE_FAILED, detail = "broadcast refused"); progress(Phase.FAILED)
            return TxResult.Refused("the node refused the transaction")
        }
        log(a, PHASE_BROADCAST, txHash = built.txHashHex); progress(Phase.BROADCAST)
        return when (awaitCommit(built.txHashHex)) {
            TxStatus.CONFIRMED -> { log(a, PHASE_COMMITTED, txHash = built.txHashHex); progress(Phase.COMMITTED); TxResult.Committed(built.txHashHex) }
            TxStatus.REJECTED -> { log(a, PHASE_FAILED, txHash = built.txHashHex, detail = "rejected by the node"); progress(Phase.FAILED); TxResult.Rejected(built.txHashHex) }
            else -> TxResult.Timeout(built.txHashHex)
        }
    }

    /** Which action a pending row belongs to. A committed issue awaiting its first tag write still sits on its
     *  first cell with counter 0; a load has moved the reserve to a new outpoint. */
    internal fun kindOfPending(card: PrepaidCardEntity): String = when {
        card.pendingUnits == null -> KIND_ADOPT
        card.state == CARD_ISSUING -> KIND_ISSUE
        card.firstOutpoint == card.currentOutpoint && card.counter == 0L && card.settledCounter == 0L -> KIND_ISSUE
        else -> KIND_LOAD
    }

    internal fun auditForPending(card: PrepaidCardEntity) = Audit(card.pendingActionId ?: newActionId(), kindOfPending(card), card.uid,
        card.reserveId, (card.pendingUnits ?: 0).toLong(), card.balanceUnits, card.counter)

    internal suspend fun clearPending(card: PrepaidCardEntity, activate: Boolean) {
        store.updateCard(card.copy(pendingUnits = null, pendingTxHash = null, pendingActionId = null, pendingCommitted = false,
            state = if (activate && card.state == CARD_ISSUING) CARD_ACTIVE else card.state, updatedAt = clock()))
    }

    // ---------------------------------------------------------------- tag write (the "card second" half)
    /** Writes `card.pendingUnits` onto the tag. Requires the pending transaction to be committed already. */
    internal suspend fun writePending(tag: TagPort, card: PrepaidCardEntity, a: Audit, progress: (Phase) -> Unit): ActionOutcome {
        val pending = card.pendingUnits
        if (pending == null) { clearPending(card, activate = true); log(a, PHASE_DONE, txHash = card.pendingTxHash); progress(Phase.DONE); return ActionOutcome.Done }
        if (!tag.uidHex.equals(card.uid, ignoreCase = true))
            return ActionOutcome.Failed("this is a different tag; present the card ${card.label ?: card.uid}")
        if (!tag.isPresent()) return ActionOutcome.AwaitingTag(card.reserveId)
        val raw = try { tag.readSlots() } catch (e: TagLostException) { return ActionOutcome.AwaitingTag(card.reserveId) }
        val slot = protocol.selectSlot(kHex, tag.uidHex, raw)
        val onTag = slot.record
        // Tag balance follows the tag (unsettled terminal taps live there); the index follows the chain.
        val tagBalance = (onTag?.balanceUnits ?: (card.balanceUnits - pending)) + pending
        val counter: Long = when {
            onTag != null -> onTag.counter.toLong() + 1
            a.kind == KIND_ISSUE -> 0                          // first record on a fresh tag
            else -> maxOf(card.counter, card.settledCounter) + 1
        }
        val raw64 = protocol.seal(kHex, tag.uidHex, CardRecordInfo(0u, card.reserveId, tagBalance, counter.toUInt(), merchantIdHex))
        val target = protocol.nextSlot(slot.pick)
        try {
            tag.writeSlot(target, raw64)
            if (onTag == null) tag.zeroSlot(if (target == SlotPickFfi.A) SlotPickFfi.B else SlotPickFfi.A)
        } catch (e: TagLostException) {
            log(a, PHASE_FAILED, txHash = card.pendingTxHash, detail = "write interrupted: ${e.message}"); progress(Phase.FAILED)
            return ActionOutcome.AwaitingTag(card.reserveId)
        }
        val back = try { tag.readSlots() } catch (e: TagLostException) {
            log(a, PHASE_FAILED, txHash = card.pendingTxHash, detail = "readback interrupted"); progress(Phase.FAILED)
            return ActionOutcome.AwaitingTag(card.reserveId)
        }
        val sel2 = protocol.selectSlot(kHex, tag.uidHex, back)
        val ok = sel2.record?.let { it.counter.toLong() == counter && it.balanceUnits == tagBalance && it.reserveIdHex.equals(card.reserveId, true) } ?: false
        if (!ok) {
            log(a, PHASE_FAILED, txHash = card.pendingTxHash, detail = "readback mismatch A=${hex(back, 0, 64)} B=${hex(back, 64, 128)}"); progress(Phase.FAILED)
            return ActionOutcome.AwaitingTag(card.reserveId)
        }
        val updated = card.copy(counter = counter, pendingUnits = null, pendingTxHash = null, pendingActionId = null, pendingCommitted = false,
            state = if (card.state == CARD_ISSUING) CARD_ACTIVE else card.state, updatedAt = clock())
        store.updateCard(updated)
        log(a, PHASE_WRITTEN, balanceAfter = updated.balanceUnits, counterAfter = counter, txHash = card.pendingTxHash); progress(Phase.WRITTEN)
        log(a, PHASE_DONE, balanceAfter = updated.balanceUnits, counterAfter = counter, txHash = card.pendingTxHash); progress(Phase.DONE)
        return ActionOutcome.Done
    }

    private fun hex(b: ByteArray, from: Int, to: Int) = b.copyOfRange(from, to).joinToString("") { "%02x".format(it) }

    // ---------------------------------------------------------------- issue
    suspend fun issue(tag: TagPort, amountUnits: Int, label: String?, progress: (Phase) -> Unit = {}): ActionOutcome = exclusive {
        if (store.cardByUid(tag.uidHex) != null) return@exclusive ActionOutcome.Failed("this tag is already indexed")
        if (store.activeCount() >= MAX_ACTIVE_CARDS) return@exclusive ActionOutcome.Failed("card limit of $MAX_ACTIVE_CARDS reached")
        val plan = try { protocol.plan(PrepaidAction.ISSUE, 0, 0uL, amountUnits) } catch (e: PrepaidException) { return@exclusive ActionOutcome.Failed(e.message ?: "refused") }
        val planned = try {
            PrepaidTxPlanner.plan(emptyList(), chain.cellsByLock(signer.lock), excludedOutpoints(), listOf(PrepaidTxPlanner.output(plan.successorCapacity, signer.lock)), signer.lock)
        } catch (e: InsufficientFunds) { return@exclusive ActionOutcome.Failed(e.message!!) }
        val built = signer.build(planned.inputs, planned.outputs)
        val reserveId = protocol.reserveId(built.txHashHex, 0u)
        val a = Audit(newActionId(), KIND_ISSUE, tag.uidHex, reserveId, amountUnits.toLong(), 0, 0)
        val now = clock()
        store.insertCard(PrepaidCardEntity(reserveId = reserveId, uid = tag.uidHex, label = label?.ifBlank { null },
            firstOutpoint = "${built.txHashHex}:0", currentOutpoint = "${built.txHashHex}:0", balanceUnits = 0, counter = 0, settledCounter = 0,
            state = CARD_ISSUING, pendingUnits = amountUnits, pendingTxHash = built.txHashHex, pendingActionId = a.actionId, pendingCommitted = false,
            issuedAt = now, updatedAt = now))
        log(a, PHASE_REQUESTED); progress(Phase.REQUESTED)
        when (val r = broadcastAndAwait(a, planned, built, progress)) {
            is TxResult.Refused -> { store.deleteIssuing(reserveId); ActionOutcome.Failed(r.reason) }
            is TxResult.Rejected -> { store.deleteIssuing(reserveId); ActionOutcome.Failed("rejected by the node") }
            is TxResult.Timeout -> ActionOutcome.StillConfirming(r.txHash)
            is TxResult.Committed -> {
                val card = store.card(reserveId)!!.copy(state = CARD_ACTIVE, balanceUnits = plan.balanceAfter, pendingCommitted = true, updatedAt = clock())
                store.updateCard(card)
                writePending(tag, card, a, progress)
            }
        }
    } ?: busy()

    // ---------------------------------------------------------------- load
    suspend fun load(tag: TagPort, card: PrepaidCardEntity, amountUnits: Int, progress: (Phase) -> Unit = {}): ActionOutcome = exclusive {
        val fresh = store.card(card.reserveId) ?: return@exclusive ActionOutcome.Failed("card not in the index")
        if (fresh.state != CARD_ACTIVE) return@exclusive ActionOutcome.Failed("card is ${fresh.state}")
        if (fresh.pendingTxHash != null) return@exclusive ActionOutcome.Failed("finish the pending credit first (tap the card)")
        if (!tag.uidHex.equals(fresh.uid, true)) return@exclusive ActionOutcome.Failed("this is a different tag")
        val reserve = chain.cellAt(fresh.currentOutpoint) ?: return@exclusive ActionOutcome.Failed("reserve cell not found on chain; use Inspect")
        val plan = try { protocol.plan(PrepaidAction.LOAD, fresh.balanceUnits, reserve.capacity, amountUnits) } catch (e: PrepaidException) { return@exclusive ActionOutcome.Failed(e.message ?: "refused") }
        val planned = try {
            PrepaidTxPlanner.plan(listOf(reserve), chain.cellsByLock(signer.lock), excludedOutpoints() - fresh.currentOutpoint,
                listOf(PrepaidTxPlanner.output(plan.successorCapacity, signer.lock)), signer.lock)
        } catch (e: InsufficientFunds) { return@exclusive ActionOutcome.Failed(e.message!!) }
        val built = signer.build(planned.inputs, planned.outputs)
        val a = Audit(newActionId(), KIND_LOAD, fresh.uid, fresh.reserveId, amountUnits.toLong(), fresh.balanceUnits, fresh.counter)
        store.updateCard(fresh.copy(pendingUnits = amountUnits, pendingTxHash = built.txHashHex, pendingActionId = a.actionId, pendingCommitted = false, updatedAt = clock()))
        log(a, PHASE_REQUESTED); progress(Phase.REQUESTED)
        when (val r = broadcastAndAwait(a, planned, built, progress)) {
            is TxResult.Refused -> { clearPending(store.card(fresh.reserveId)!!, activate = false); ActionOutcome.Failed(r.reason) }
            is TxResult.Rejected -> { clearPending(store.card(fresh.reserveId)!!, activate = false); ActionOutcome.Failed("rejected by the node") }
            is TxResult.Timeout -> ActionOutcome.StillConfirming(r.txHash)
            is TxResult.Committed -> {
                val c = store.card(fresh.reserveId)!!.copy(currentOutpoint = "${r.txHash}:0", balanceUnits = plan.balanceAfter, pendingCommitted = true, updatedAt = clock())
                store.updateCard(c)
                writePending(tag, c, a, progress)
            }
        }
    } ?: busy()

    // ---------------------------------------------------------------- resume / recover
    /** Finish a pending credit on the tag of [card]. Checks the transaction once (no 90 s wait). */
    suspend fun resume(tag: TagPort, card: PrepaidCardEntity, progress: (Phase) -> Unit = {}): ActionOutcome = exclusive {
        val fresh = store.card(card.reserveId) ?: return@exclusive ActionOutcome.Failed("card not in the index")
        val hash = fresh.pendingTxHash ?: return@exclusive ActionOutcome.Done
        val a = auditForPending(fresh)
        val committed = fresh.pendingCommitted || when (chain.status(hash)) {
            TxStatus.CONFIRMED -> { log(a, PHASE_COMMITTED, txHash = hash); progress(Phase.COMMITTED); true }
            TxStatus.REJECTED -> {
                log(a, PHASE_FAILED, txHash = hash, detail = "rejected by the node"); progress(Phase.FAILED)
                if (fresh.state == CARD_ISSUING) store.deleteIssuing(fresh.reserveId) else clearPending(fresh, activate = false)
                return@exclusive ActionOutcome.Failed("rejected by the node")
            }
            else -> return@exclusive ActionOutcome.StillConfirming(hash)
        }
        check(committed)
        val c = if (fresh.pendingCommitted) fresh else onCommitted(fresh, hash)
        writePending(tag, c, a, progress)
    } ?: busy()

    /** Apply the chain view of a just-confirmed pending transaction to the index. */
    private suspend fun onCommitted(card: PrepaidCardEntity, hash: String): PrepaidCardEntity {
        val pending = card.pendingUnits ?: 0
        val c = card.copy(pendingCommitted = true, balanceUnits = card.balanceUnits + pending,
            currentOutpoint = if (card.state == CARD_ISSUING) card.currentOutpoint else "$hash:0",
            state = if (card.state == CARD_ISSUING) CARD_ACTIVE else card.state, updatedAt = clock())
        store.updateCard(c)
        return c
    }

    /** On page open: re-poll every pending transaction once. Returns how many rows changed. */
    suspend fun recoverPending(): Int = exclusive {
        var changed = 0
        for (card in store.cardsWithPending()) {
            val hash = card.pendingTxHash!!
            val a = auditForPending(card)
            if (card.pendingCommitted) {
                if (card.pendingUnits == null) { clearPending(card, activate = true); log(a, PHASE_DONE, txHash = hash); changed++ }
                continue
            }
            when (chain.status(hash)) {
                TxStatus.CONFIRMED -> {
                    log(a, PHASE_COMMITTED, txHash = hash)
                    val c = onCommitted(card, hash)
                    if (c.pendingUnits == null) { clearPending(c, activate = true); log(a, PHASE_DONE, txHash = hash) }
                    changed++
                }
                TxStatus.REJECTED -> {
                    log(a, PHASE_FAILED, txHash = hash, detail = "rejected by the node")
                    if (card.state == CARD_ISSUING) store.deleteIssuing(card.reserveId) else clearPending(card, activate = false)
                    changed++
                }
                else -> {}
            }
        }
        changed
    } ?: 0
}
```

Note the index balance is credited at commit (`onCommitted` / the `Committed` branches), so `writePending` only moves the tag and the counter; the test `torn_write_after_commit_resumes_on_next_tap` pins that order.

- [ ] **Step 4: Run to verify pass** → 13 passed. Run the whole unit suite: `./gradlew :app:testDebugUnitTest -q 2>&1 | tail -5` → green.

- [ ] **Step 5: Mutation probes**
  - Remove `excludedOutpoints()` from `load`'s planner call → `load_never_spends_another_cards_reserve` red. Restore.
  - Remove the UID check in `writePending` → `resume_refuses_a_different_uid` red. Restore.
  - Replace `mutex.tryLock()` with `true` → `second_action_while_busy_is_refused_without_rows` red. Restore.

- [ ] **Step 6: Commit** — `git add app/src/main/java/com/wyltek/wallet/prepaid/PrepaidService.kt app/src/test/java/com/wyltek/wallet/prepaid/PrepaidServiceIssueLoadTest.kt && git commit -m "feat(prepaid): service core — classify, issue, load, resume, recover (chain ahead of card)" -m "Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"`

---

### Task 11: `PrepaidService` — revoke, reinstate, void, cash-out, repair, adopt

**Files:**
- Modify: `app/src/main/java/com/wyltek/wallet/prepaid/PrepaidService.kt`
- Test: `app/src/test/java/com/wyltek/wallet/prepaid/PrepaidServiceRetireTest.kt`

**Interfaces:**
- Produces: `suspend fun revoke(card): ActionOutcome`, `reinstate(card)`, `void(card, tag: TagPort?, progress)`, `cashOut(tag, card, customerAddress: String, progress)`, `repair(tag, card, progress)`, `adopt(tag, record: CardRecordInfo, progress)`; `data class CashOutPreview(balanceUnits: Int, unsettledTaps: Long, belowMinimum: Boolean)` from `suspend fun inspectForRetire(tag: TagPort?, card): CashOutPreview`.

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.wyltek.wallet.prepaid

import com.wyltek.wallet.core.model.LockScript
import com.wyltek.wallet.core.native.CardRecordInfo
import com.wyltek.wallet.core.native.SlotPickFfi
import com.wyltek.wallet.prepaid.db.*
import com.wyltek.wallet.prepaid.fakes.FakeTag
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class PrepaidServiceRetireTest {
    private val ckb = 100_000_000uL; private val unit = 100_000uL; private val floor = 61uL * ckb
    private val lock = LockScript("0x9bd7e06f3ecf4be0f2fcd2188b23f1b9fcc88e5d4b65a8637b17723bbda3cce8", "type", "0x" + "ab".repeat(20))
    private val customerLock = LockScript("0x9bd7e06f3ecf4be0f2fcd2188b23f1b9fcc88e5d4b65a8637b17723bbda3cce8", "type", "0x" + "cd".repeat(20))
    private val uid = "04958caa5c5e80"
    private fun h() = PrepaidServiceIssueLoadTest.H(1000uL, lock).also { it.protocol.locks["ckt1customer"] = customerLock }
    private suspend fun issued(h: PrepaidServiceIssueLoadTest.H, tag: FakeTag, units: Int = 250_000): PrepaidCardEntity {
        assertEquals(ActionOutcome.Done, h.svc.issue(tag, units, null)); return h.store.cardByUid(tag.uidHex)!!
    }

    @Test fun revoke_and_reinstate_flip_state_and_log() = runTest {
        val h = h(); val card = issued(h, FakeTag(uid))
        assertEquals(ActionOutcome.Done, h.svc.revoke(card))
        assertEquals(CARD_REVOKED, h.store.card(card.reserveId)!!.state)
        assertTrue(h.svc.revoke(h.store.card(card.reserveId)!!) is ActionOutcome.Failed)
        assertEquals(ActionOutcome.Done, h.svc.reinstate(h.store.card(card.reserveId)!!))
        assertEquals(CARD_ACTIVE, h.store.card(card.reserveId)!!.state)
        assertEquals(listOf(KIND_REVOKE, KIND_REVOKE, KIND_REINSTATE, KIND_REINSTATE), h.store.events.filter { it.kind != KIND_ISSUE }.map { it.kind })
        assertEquals(listOf(PHASE_REQUESTED, PHASE_DONE), h.store.phases("a2"))
        assertEquals(0, h.chain.broadcasts.size - 1)
    }

    @Test fun void_without_tag_sweeps_reserve_to_merchant_and_closes() = runTest {
        val h = h(); val card = issued(h, FakeTag(uid))
        val walletBefore = h.chain.cellsByLock(lock).fold(0uL) { a, u -> a + u.capacity }
        assertEquals(ActionOutcome.Done, h.svc.void(card, tag = null))
        val c = h.store.card(card.reserveId)!!
        assertEquals(CARD_CLOSED, c.state); assertEquals(0, c.balanceUnits); assertNull(h.chain.cellAt(card.currentOutpoint))
        val walletAfter = h.chain.cellsByLock(lock).fold(0uL) { a, u -> a + u.capacity }
        assertTrue(walletAfter > walletBefore - 1_000_000uL && walletAfter <= walletBefore)   // everything back minus the fee
        val ev = h.store.events.filter { it.kind == KIND_VOID }
        assertEquals(listOf(PHASE_REQUESTED, PHASE_BROADCAST, PHASE_COMMITTED, PHASE_DONE), ev.map { it.phase })
        assertEquals(-250_000L, ev.first().amountUnits); assertTrue(ev.first().detail.contains("forfeit"))
    }

    @Test fun void_with_tag_zeroes_both_slots() = runTest {
        val h = h(); val tag = FakeTag(uid); val card = issued(h, tag)
        assertEquals(ActionOutcome.Done, h.svc.void(card, tag))
        assertTrue(tag.readSlots().all { it == 0.toByte() })
    }

    @Test fun void_is_refused_while_a_credit_is_pending() = runTest {
        val h = h(); h.chain.confirmAfterPolls = null
        h.svc.issue(FakeTag(uid), 1000, null)
        assertTrue(h.svc.void(h.store.cardByUid(uid)!!, null) is ActionOutcome.Failed)
    }

    @Test fun cash_out_pays_customer_returns_floor_and_zeroes_tag() = runTest {
        val h = h(); val tag = FakeTag(uid); val card = issued(h, tag, 100_000)   // 100 CKB
        val pv = h.svc.inspectForRetire(tag, card)
        assertEquals(CashOutPreview(100_000, 0, false), pv)
        assertEquals(ActionOutcome.Done, h.svc.cashOut(tag, card, "ckt1customer"))
        val customerCells = h.chain.cellsByLock(customerLock)
        assertEquals(1, customerCells.size); assertEquals(100_000uL * unit, customerCells[0].capacity)
        assertEquals(CARD_CLOSED, h.store.card(card.reserveId)!!.state)
        assertTrue(tag.readSlots().all { it == 0.toByte() })
        val ev = h.store.events.filter { it.kind == KIND_CASH_OUT }
        assertTrue(ev.first().detail.contains("ckt1customer")); assertEquals(-100_000L, ev.first().amountUnits)
    }

    @Test fun cash_out_refuses_unsettled_taps_below_minimum_and_unknown_address() = runTest {
        val h = h(); val tag = FakeTag(uid); val card = issued(h, tag, 100_000)
        tag.writeSlot(SlotPickFfi.B, h.protocol.seal("", uid, CardRecordInfo(3u, card.reserveId, 99_000, 1u, h.svc.merchantIdHex)))
        assertEquals(1L, h.svc.inspectForRetire(tag, card).unsettledTaps)
        val r1 = h.svc.cashOut(tag, card, "ckt1customer")
        assertTrue(r1 is ActionOutcome.Failed && (r1 as ActionOutcome.Failed).reason.contains("sync the terminal"))
        val tag2 = FakeTag("04ebbb02cd2a81"); val small = issued(h, tag2, 50_000)
        assertTrue(h.svc.inspectForRetire(tag2, small).belowMinimum)
        val r2 = h.svc.cashOut(tag2, small, "ckt1customer")
        assertTrue(r2 is ActionOutcome.Failed && (r2 as ActionOutcome.Failed).reason.contains("minimum"))
        val r3 = h.svc.cashOut(tag2, issued(h, FakeTag("04aaaaaaaaaaaa"), 100_000), "ckt1customer")
        assertTrue(r3 is ActionOutcome.Failed && (r3 as ActionOutcome.Failed).reason.contains("different tag"))
        val tag4 = FakeTag("04bbbbbbbbbbbb"); val c4 = issued(h, tag4, 100_000)
        val r4 = h.svc.cashOut(tag4, c4, "ckt1nobody")
        assertTrue(r4 is ActionOutcome.Failed && (r4 as ActionOutcome.Failed).reason.contains("address"))
        assertEquals(CARD_ACTIVE, h.store.card(c4.reserveId)!!.state)
    }

    @Test fun repair_rewrites_a_damaged_tag_from_the_index() = runTest {
        val h = h(); val tag = FakeTag(uid); val card = issued(h, tag, 1000)
        tag.pages.indices.forEach { tag.pages[it] = ByteArray(4) }           // wiped
        h.store.updateCard(card.copy(counter = 3, settledCounter = 5))
        assertTrue(h.svc.classify(tag) is TapClass.Damaged)
        assertEquals(ActionOutcome.Done, h.svc.repair(tag, h.store.card(card.reserveId)!!))
        val slot = h.protocol.selectSlot("", uid, tag.readSlots())
        assertEquals(SlotPickFfi.A, slot.pick); assertEquals(6u, slot.record!!.counter); assertEquals(1000, slot.record!!.balanceUnits)
        assertEquals(6L, h.store.card(card.reserveId)!!.counter)
        assertEquals(KIND_REPAIR, h.store.events.last().kind); assertEquals(PHASE_DONE, h.store.events.last().phase)
    }

    @Test fun adopt_funds_a_reserve_under_the_records_own_id() = runTest {
        val h = h(); val tag = FakeTag(uid)
        val rid = "b1".repeat(32)
        tag.writeSlot(SlotPickFfi.A, h.protocol.seal("", uid, CardRecordInfo(3u, rid, 500_000, 2u, h.svc.merchantIdHex)))
        val cls = h.svc.classify(tag)
        assertTrue(cls is TapClass.Bench)
        assertEquals(ActionOutcome.Done, h.svc.adopt(tag, (cls as TapClass.Bench).record))
        val c = h.store.card(rid)!!
        assertEquals(CARD_ACTIVE, c.state); assertEquals(500_000, c.balanceUnits); assertEquals(2L, c.counter); assertEquals(0L, c.settledCounter)
        assertEquals(floor + 500_000uL * unit, h.chain.cellAt(c.currentOutpoint)!!.capacity)
        assertEquals(2u, h.protocol.selectSlot("", uid, tag.readSlots()).record!!.counter)   // tag untouched
        assertEquals(listOf(PHASE_REQUESTED, PHASE_BROADCAST, PHASE_COMMITTED, PHASE_DONE), h.store.phases("a1"))
        assertTrue(h.svc.classify(tag) is TapClass.Known)
    }

    @Test fun adopt_timeout_recovers_without_a_tag_write() = runTest {
        val h = h(); h.chain.confirmAfterPolls = null; val tag = FakeTag(uid); val rid = "b1".repeat(32)
        tag.writeSlot(SlotPickFfi.A, h.protocol.seal("", uid, CardRecordInfo(3u, rid, 500_000, 2u, h.svc.merchantIdHex)))
        val rec = (h.svc.classify(tag) as TapClass.Bench).record
        assertTrue(h.svc.adopt(tag, rec) is ActionOutcome.StillConfirming)
        val c = h.store.card(rid)!!; assertEquals(CARD_ISSUING, c.state); assertNull(c.pendingUnits)
        h.chain.confirm(c.pendingTxHash!!)
        assertEquals(1, h.svc.recoverPending())
        val d = h.store.card(rid)!!
        assertEquals(CARD_ACTIVE, d.state); assertNull(d.pendingTxHash); assertEquals(500_000, d.balanceUnits)
        assertEquals(PHASE_DONE, h.store.phases("a1").last())
    }
}
```

- [ ] **Step 2: Run to verify failure** — compile errors (`revoke`, `void`, ... missing).

- [ ] **Step 3: Implement** — add to `PrepaidService`:

```kotlin
    data class CashOutPreview(val balanceUnits: Int, val unsettledTaps: Long, val belowMinimum: Boolean)

    /** What the confirm sheets show: index balance, how far the tag's counter is ahead of the index, and the cash-out minimum. */
    suspend fun inspectForRetire(tag: TagPort?, card: PrepaidCardEntity): CashOutPreview {
        val fresh = store.card(card.reserveId) ?: card
        val tagCounter = tag?.takeIf { it.uidHex.equals(fresh.uid, true) }?.let { t ->
            try { protocol.selectSlot(kHex, t.uidHex, t.readSlots()).record?.counter?.toLong() } catch (e: TagLostException) { null }
        }
        val ahead = tagCounter?.let { maxOf(0L, it - fresh.counter) } ?: 0L
        return CashOutPreview(fresh.balanceUnits, ahead, fresh.balanceUnits.toLong() * UNIT_SHANNONS < 6_100_000_000L)
    }

    private suspend fun flip(card: PrepaidCardEntity, kind: String, from: String, to: String): ActionOutcome = exclusive {
        val fresh = store.card(card.reserveId) ?: return@exclusive ActionOutcome.Failed("card not in the index")
        if (fresh.state != from) return@exclusive ActionOutcome.Failed("card is ${fresh.state}")
        val a = Audit(newActionId(), kind, fresh.uid, fresh.reserveId, 0, fresh.balanceUnits, fresh.counter)
        log(a, PHASE_REQUESTED)
        store.updateCard(fresh.copy(state = to, updatedAt = clock()))
        log(a, PHASE_DONE)
        ActionOutcome.Done
    } ?: busy()

    suspend fun revoke(card: PrepaidCardEntity) = flip(card, KIND_REVOKE, CARD_ACTIVE, CARD_REVOKED)
    suspend fun reinstate(card: PrepaidCardEntity) = flip(card, KIND_REINSTATE, CARD_REVOKED, CARD_ACTIVE)

    /** Retire a card and sweep its whole reserve to the merchant. The tag may be absent (lost card). */
    suspend fun void(card: PrepaidCardEntity, tag: TagPort?, progress: (Phase) -> Unit = {}): ActionOutcome = exclusive {
        val fresh = store.card(card.reserveId) ?: return@exclusive ActionOutcome.Failed("card not in the index")
        if (fresh.state != CARD_ACTIVE && fresh.state != CARD_REVOKED) return@exclusive ActionOutcome.Failed("card is ${fresh.state}")
        if (fresh.pendingTxHash != null) return@exclusive ActionOutcome.Failed("finish the pending credit first (tap the card)")
        val reserve = chain.cellAt(fresh.currentOutpoint) ?: return@exclusive ActionOutcome.Failed("reserve cell not found on chain; use Inspect")
        try { protocol.plan(PrepaidAction.VOID, fresh.balanceUnits, reserve.capacity, 0) } catch (e: PrepaidException) { return@exclusive ActionOutcome.Failed(e.message ?: "refused") }
        val preview = inspectForRetire(tag, fresh)
        val planned = try {
            PrepaidTxPlanner.plan(listOf(reserve), chain.cellsByLock(signer.lock), excludedOutpoints() - fresh.currentOutpoint, emptyList(), signer.lock)
        } catch (e: InsufficientFunds) { return@exclusive ActionOutcome.Failed(e.message!!) }
        val built = signer.build(planned.inputs, planned.outputs)
        val a = Audit(newActionId(), KIND_VOID, fresh.uid, fresh.reserveId, -fresh.balanceUnits.toLong(), fresh.balanceUnits, fresh.counter)
        log(a, PHASE_REQUESTED, detail = "forfeit ${fresh.balanceUnits} units; unsettled taps ${preview.unsettledTaps}"); progress(Phase.REQUESTED)
        when (val r = broadcastAndAwait(a, planned, built, progress)) {
            is TxResult.Refused -> ActionOutcome.Failed(r.reason)
            is TxResult.Rejected -> ActionOutcome.Failed("rejected by the node")
            is TxResult.Timeout -> ActionOutcome.StillConfirming(r.txHash)
            is TxResult.Committed -> {
                store.updateCard(fresh.copy(state = CARD_CLOSED, balanceUnits = 0, updatedAt = clock()))
                val wiped = wipeTag(tag, fresh)
                log(a, PHASE_DONE, balanceAfter = 0, txHash = r.txHash, detail = wiped); progress(Phase.DONE)
                ActionOutcome.Done
            }
        }
    } ?: busy()

    /** Best effort; a closed card is dead on chain whether or not its tag is wiped. */
    private suspend fun wipeTag(tag: TagPort?, card: PrepaidCardEntity): String {
        if (tag == null || !tag.uidHex.equals(card.uid, true)) return "tag absent"
        return try { tag.zeroSlot(SlotPickFfi.A); tag.zeroSlot(SlotPickFfi.B); "tag wiped" } catch (e: TagLostException) { "tag wipe interrupted: ${e.message}" }
    }

    /** Retire a card and pay its balance to [customerAddress]; the floor (minus fee) returns to the merchant. Tag required. */
    suspend fun cashOut(tag: TagPort, card: PrepaidCardEntity, customerAddress: String, progress: (Phase) -> Unit = {}): ActionOutcome = exclusive {
        val fresh = store.card(card.reserveId) ?: return@exclusive ActionOutcome.Failed("card not in the index")
        if (fresh.state != CARD_ACTIVE && fresh.state != CARD_REVOKED) return@exclusive ActionOutcome.Failed("card is ${fresh.state}")
        if (fresh.pendingTxHash != null) return@exclusive ActionOutcome.Failed("finish the pending credit first (tap the card)")
        if (!tag.uidHex.equals(fresh.uid, true)) return@exclusive ActionOutcome.Failed("this is a different tag")
        val preview = inspectForRetire(tag, fresh)
        if (preview.unsettledTaps > 0) return@exclusive ActionOutcome.Failed("${preview.unsettledTaps} unsettled taps: sync the terminal first")
        val customerLock = protocol.lockOf(customerAddress) ?: return@exclusive ActionOutcome.Failed("customer address is not a valid CKB address")
        val reserve = chain.cellAt(fresh.currentOutpoint) ?: return@exclusive ActionOutcome.Failed("reserve cell not found on chain; use Inspect")
        val plan = try { protocol.plan(PrepaidAction.CASH_OUT, fresh.balanceUnits, reserve.capacity, 0) } catch (e: PrepaidException) { return@exclusive ActionOutcome.Failed(e.message ?: "refused") }
        val planned = try {
            PrepaidTxPlanner.plan(listOf(reserve), chain.cellsByLock(signer.lock), excludedOutpoints() - fresh.currentOutpoint,
                listOf(PrepaidTxPlanner.output(plan.customerShannons, customerLock)), signer.lock)
        } catch (e: InsufficientFunds) { return@exclusive ActionOutcome.Failed(e.message!!) }
        val built = signer.build(planned.inputs, planned.outputs)
        val a = Audit(newActionId(), KIND_CASH_OUT, fresh.uid, fresh.reserveId, -fresh.balanceUnits.toLong(), fresh.balanceUnits, fresh.counter)
        log(a, PHASE_REQUESTED, detail = "to $customerAddress"); progress(Phase.REQUESTED)
        when (val r = broadcastAndAwait(a, planned, built, progress)) {
            is TxResult.Refused -> ActionOutcome.Failed(r.reason)
            is TxResult.Rejected -> ActionOutcome.Failed("rejected by the node")
            is TxResult.Timeout -> ActionOutcome.StillConfirming(r.txHash)
            is TxResult.Committed -> {
                store.updateCard(fresh.copy(state = CARD_CLOSED, balanceUnits = 0, updatedAt = clock()))
                val wiped = wipeTag(tag, fresh)
                log(a, PHASE_DONE, balanceAfter = 0, txHash = r.txHash, detail = "to $customerAddress; $wiped"); progress(Phase.DONE)
                ActionOutcome.Done
            }
        }
    } ?: busy()

    /** Rewrite a tag with no valid slot from the index (D3 spec §5.5). */
    suspend fun repair(tag: TagPort, card: PrepaidCardEntity, progress: (Phase) -> Unit = {}): ActionOutcome = exclusive {
        val fresh = store.card(card.reserveId) ?: return@exclusive ActionOutcome.Failed("card not in the index")
        if (fresh.state == CARD_CLOSED) return@exclusive ActionOutcome.Failed("card is closed")
        if (fresh.pendingTxHash != null) return@exclusive ActionOutcome.Failed("finish the pending credit first (tap the card)")
        if (!tag.uidHex.equals(fresh.uid, true)) return@exclusive ActionOutcome.Failed("this is a different tag")
        val counter = maxOf(fresh.counter, fresh.settledCounter) + 1
        val a = Audit(newActionId(), KIND_REPAIR, fresh.uid, fresh.reserveId, 0, fresh.balanceUnits, fresh.counter)
        log(a, PHASE_REQUESTED); progress(Phase.REQUESTED)
        val raw64 = protocol.seal(kHex, tag.uidHex, CardRecordInfo(0u, fresh.reserveId, fresh.balanceUnits, counter.toUInt(), merchantIdHex))
        try { tag.writeSlot(SlotPickFfi.A, raw64); tag.zeroSlot(SlotPickFfi.B) } catch (e: TagLostException) {
            log(a, PHASE_FAILED, detail = "write interrupted: ${e.message}"); progress(Phase.FAILED); return@exclusive ActionOutcome.Failed("write interrupted; tap again")
        }
        val sel = try { protocol.selectSlot(kHex, tag.uidHex, tag.readSlots()) } catch (e: TagLostException) {
            log(a, PHASE_FAILED, detail = "readback interrupted"); progress(Phase.FAILED); return@exclusive ActionOutcome.Failed("readback interrupted; tap again")
        }
        if (sel.record?.counter?.toLong() != counter) { log(a, PHASE_FAILED, detail = "readback mismatch"); progress(Phase.FAILED); return@exclusive ActionOutcome.Failed("readback mismatch; tap again") }
        store.updateCard(fresh.copy(counter = counter, updatedAt = clock()))
        log(a, PHASE_WRITTEN, counterAfter = counter); progress(Phase.WRITTEN)
        log(a, PHASE_DONE, counterAfter = counter); progress(Phase.DONE)
        ActionOutcome.Done
    } ?: busy()

    /** Fund a reserve for a terminal-formatted card under the record's own reserve_id (D3 spec §5.5). */
    suspend fun adopt(tag: TagPort, record: CardRecordInfo, progress: (Phase) -> Unit = {}): ActionOutcome = exclusive {
        if (store.card(record.reserveIdHex) != null) return@exclusive ActionOutcome.Failed("reserve already indexed")
        if (store.cardByUid(tag.uidHex) != null) return@exclusive ActionOutcome.Failed("this tag is already indexed")
        if (store.activeCount() >= MAX_ACTIVE_CARDS) return@exclusive ActionOutcome.Failed("card limit of $MAX_ACTIVE_CARDS reached")
        val plan = try { protocol.plan(PrepaidAction.ISSUE, 0, 0uL, record.balanceUnits) } catch (e: PrepaidException) { return@exclusive ActionOutcome.Failed(e.message ?: "refused") }
        val planned = try {
            PrepaidTxPlanner.plan(emptyList(), chain.cellsByLock(signer.lock), excludedOutpoints(), listOf(PrepaidTxPlanner.output(plan.successorCapacity, signer.lock)), signer.lock)
        } catch (e: InsufficientFunds) { return@exclusive ActionOutcome.Failed(e.message!!) }
        val built = signer.build(planned.inputs, planned.outputs)
        val a = Audit(newActionId(), KIND_ADOPT, tag.uidHex, record.reserveIdHex, record.balanceUnits.toLong(), 0, record.counter.toLong())
        val now = clock()
        store.insertCard(PrepaidCardEntity(reserveId = record.reserveIdHex, uid = tag.uidHex, label = null,
            firstOutpoint = "${built.txHashHex}:0", currentOutpoint = "${built.txHashHex}:0", balanceUnits = 0, counter = record.counter.toLong(),
            settledCounter = 0, state = CARD_ISSUING, pendingUnits = null, pendingTxHash = built.txHashHex, pendingActionId = a.actionId,
            pendingCommitted = false, issuedAt = now, updatedAt = now))
        log(a, PHASE_REQUESTED, detail = "terminal ${record.writerId}"); progress(Phase.REQUESTED)
        when (val r = broadcastAndAwait(a, planned, built, progress)) {
            is TxResult.Refused -> { store.deleteIssuing(record.reserveIdHex); ActionOutcome.Failed(r.reason) }
            is TxResult.Rejected -> { store.deleteIssuing(record.reserveIdHex); ActionOutcome.Failed("rejected by the node") }
            is TxResult.Timeout -> ActionOutcome.StillConfirming(r.txHash)
            is TxResult.Committed -> {
                store.updateCard(store.card(record.reserveIdHex)!!.copy(state = CARD_ACTIVE, balanceUnits = record.balanceUnits,
                    pendingUnits = null, pendingTxHash = null, pendingActionId = null, pendingCommitted = false, updatedAt = clock()))
                log(a, PHASE_DONE, balanceAfter = record.balanceUnits, txHash = r.txHash); progress(Phase.DONE)
                ActionOutcome.Done
            }
        }
    } ?: busy()
```

`onCommitted` (Task 10) already credits `pendingUnits ?: 0`, so an adopt that times out and later confirms via `recoverPending` needs its balance from somewhere: change `onCommitted` to use `card.pendingUnits ?: adoptBalance(card)` where the adopt balance is the reserve capacity: `((chain.cellAt(card.currentOutpoint)?.capacity ?: 0uL) - 6_100_000_000uL).toLong() / UNIT_SHANNONS` when `pendingUnits == null && card.state == CARD_ISSUING`. Implement as:

```kotlin
    private suspend fun creditedUnits(card: PrepaidCardEntity): Int = card.pendingUnits
        ?: if (card.state == CARD_ISSUING) (((chain.cellAt(card.currentOutpoint)?.capacity ?: 0uL) - minOf(chain.cellAt(card.currentOutpoint)?.capacity ?: 0uL, 6_100_000_000uL)).toLong() / UNIT_SHANNONS).toInt() else 0
```
and in `onCommitted` replace `val pending = card.pendingUnits ?: 0` with `val pending = creditedUnits(card)`.

- [ ] **Step 4: Run to verify pass** → 9 passed; full unit suite green.

- [ ] **Step 5: Mutation probe** — remove the `preview.unsettledTaps > 0` guard in `cashOut` → `cash_out_refuses_unsettled_taps_below_minimum_and_unknown_address` red. Restore.

- [ ] **Step 6: Commit** — `git add app/src/main/java/com/wyltek/wallet/prepaid/PrepaidService.kt app/src/test/java/com/wyltek/wallet/prepaid/PrepaidServiceRetireTest.kt && git commit -m "feat(prepaid): revoke, reinstate, void, cash-out, repair, adopt" -m "Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"`

---

### Task 12: Settlement — `PrepaidService.settle`, port methods, DTOs, routes, gateway auth

**Files:**
- Modify: `app/src/main/java/com/wyltek/wallet/prepaid/PrepaidService.kt`, `app/src/main/java/com/wyltek/wallet/agent/server/AgentDispatchPort.kt`, `app/src/main/java/com/wyltek/wallet/agent/server/AgentDtos.kt`, `app/src/main/java/com/wyltek/wallet/agent/server/AgentRouter.kt`
- Test: `app/src/test/java/com/wyltek/wallet/prepaid/PrepaidServiceSettleTest.kt`, `app/src/test/java/com/wyltek/wallet/agent/server/PrepaidRoutesTest.kt`

**Interfaces:**
- Produces: `data class SettleResult(verdicts: List<String>, txHash: String?)` with verdict strings `accepted | duplicate | bad_mac | unknown_card | stale_counter | over_balance | retry`; `suspend fun PrepaidService.settle(entriesHex: String, terminalLabel: String): SettleResult?` (null = entries not a multiple of 96 bytes); `sealed class PrepaidJournalResult { BadToken; BadRequest(reason); Ok(results: List<JournalVerdictDto>) }`; `sealed class PrepaidCardsResult { BadToken; Ok(cards: List<PrepaidCardDto>) }`; DTOs `PrepaidJournalRequest(entriesHex: String)`, `JournalVerdictDto(index: Int, verdict: String, txHash: String? = null)`, `PrepaidJournalResponse(results)`, `PrepaidCardDto(reserveId: String, balanceUnits: Int, revoked: Boolean)`, `PrepaidCardsResponse(cards)`; port methods with defaults `suspend fun prepaidJournal(token, entriesHex, sourceIp, nowUnix): PrepaidJournalResult = PrepaidJournalResult.BadToken` and `suspend fun prepaidCards(token, sourceIp, nowUnix): PrepaidCardsResult = PrepaidCardsResult.BadToken`; routes `POST /v1/prepaid/journal` and `GET /v1/prepaid/cards` (token in `x-device-token`; 401/400/200).

- [ ] **Step 1: Write the failing tests**

`PrepaidServiceSettleTest.kt`:
```kotlin
package com.wyltek.wallet.prepaid

import com.wyltek.wallet.core.model.LockScript
import com.wyltek.wallet.core.native.*
import com.wyltek.wallet.prepaid.db.*
import com.wyltek.wallet.prepaid.fakes.FakeTag
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class PrepaidServiceSettleTest {
    private val ckb = 100_000_000uL; private val unit = 100_000uL; private val floor = 61uL * ckb
    private val lock = LockScript("0x9bd7e06f3ecf4be0f2fcd2188b23f1b9fcc88e5d4b65a8637b17723bbda3cce8", "type", "0x" + "ab".repeat(20))
    private fun h() = PrepaidServiceIssueLoadTest.H(2000uL, lock)
    /** 96-byte entries with only the reserve_id (bytes 20..52) filled; FakeProtocol never parses them. */
    private fun entries(vararg rids: String) = rids.joinToString("") { rid -> "00".repeat(20) + rid + "00".repeat(44) }

    @Test fun positive_net_builds_one_tx_and_advances_counters() = runTest {
        val h = h()
        h.svc.issue(FakeTag("04000000000001"), 300_000, null); h.svc.issue(FakeTag("04000000000002"), 300_000, null)
        val (c1, c2) = h.store.allCards().sortedBy { it.uid }
        h.protocol.verifyResult = BatchResultFfi(
            verdicts = listOf(JournalVerdict.ACCEPTED, JournalVerdict.ACCEPTED, JournalVerdict.DUPLICATE, JournalVerdict.ACCEPTED),
            nets = listOf(CardNetFfi(c1.reserveId, 50_000, 7u, 2u), CardNetFfi(c2.reserveId, -10_000, 3u, 1u)))
        val r = h.svc.settle(entries(c1.reserveId, c1.reserveId, c1.reserveId, c2.reserveId), "terminal 7")!!
        assertEquals(listOf("accepted", "accepted", "duplicate", "accepted"), r.verdicts)
        assertNotNull(r.txHash)
        val d1 = h.store.card(c1.reserveId)!!; val d2 = h.store.card(c2.reserveId)!!
        assertEquals(250_000, d1.balanceUnits); assertEquals(7L, d1.settledCounter); assertEquals(7L, d1.counter)
        assertEquals(310_000, d2.balanceUnits); assertEquals(3L, d2.settledCounter)
        assertEquals(floor + 250_000uL * unit, h.chain.cellAt(d1.currentOutpoint)!!.capacity)
        assertEquals(floor + 310_000uL * unit, h.chain.cellAt(d2.currentOutpoint)!!.capacity)
        assertEquals("${r.txHash}:0", d1.currentOutpoint); assertEquals("${r.txHash}:1", d2.currentOutpoint)
        val ev = h.store.events.filter { it.kind == KIND_SETTLE && it.phase == PHASE_DONE }
        assertEquals(2, ev.size); assertEquals(-50_000L, ev.first { it.reserveId == c1.reserveId }.amountUnits)
        assertTrue(ev.all { it.phase == PHASE_DONE && it.txHash == r.txHash && it.detail.contains("terminal 7") })
    }

    @Test fun zero_net_only_advances_counters_without_a_transaction() = runTest {
        val h = h(); h.svc.issue(FakeTag("04000000000001"), 300_000, null)
        val c1 = h.store.allCards().single()
        h.protocol.verifyResult = BatchResultFfi(listOf(JournalVerdict.ACCEPTED, JournalVerdict.ACCEPTED), listOf(CardNetFfi(c1.reserveId, 0, 9u, 2u)))
        val r = h.svc.settle(entries(c1.reserveId, c1.reserveId), "t")!!
        assertNull(r.txHash); assertEquals(listOf("accepted", "accepted"), r.verdicts)
        assertEquals(9L, h.store.card(c1.reserveId)!!.settledCounter); assertEquals(1, h.chain.broadcasts.size)
    }

    @Test fun failed_transaction_turns_accepted_into_retry_and_advances_nothing() = runTest {
        val h = h(); h.svc.issue(FakeTag("04000000000001"), 300_000, null)
        val c1 = h.store.allCards().single()
        h.protocol.verifyResult = BatchResultFfi(listOf(JournalVerdict.ACCEPTED, JournalVerdict.BAD_MAC), listOf(CardNetFfi(c1.reserveId, 1000, 2u, 1u)))
        h.chain.refuseBroadcast = true
        val r = h.svc.settle(entries(c1.reserveId, c1.reserveId), "t")!!
        assertEquals(listOf("retry", "bad_mac"), r.verdicts); assertNull(r.txHash)
        assertEquals(0L, h.store.card(c1.reserveId)!!.settledCounter); assertEquals(300_000, h.store.card(c1.reserveId)!!.balanceUnits)
        assertEquals(PHASE_FAILED, h.store.events.last().phase); assertEquals(KIND_SETTLE, h.store.events.last().kind)
    }

    @Test fun closed_and_issuing_cards_are_unknown_to_settlement() = runTest {
        val h = h()
        h.svc.issue(FakeTag("04000000000001"), 300_000, null); val closed = h.store.allCards().single()
        h.svc.void(closed, null)
        h.chain.confirmAfterPolls = null
        h.svc.issue(FakeTag("04000000000002"), 300_000, null); val issuing = h.store.cardByUid("04000000000002")!!
        h.chain.confirmAfterPolls = 1
        h.svc.issue(FakeTag("04000000000003"), 300_000, null); val active = h.store.cardByUid("04000000000003")!!
        h.protocol.verifyResult = BatchResultFfi(listOf(JournalVerdict.UNKNOWN_CARD, JournalVerdict.UNKNOWN_CARD, JournalVerdict.ACCEPTED), listOf(CardNetFfi(active.reserveId, 0, 1u, 1u)))
        val r = h.svc.settle(entries(closed.reserveId, issuing.reserveId, active.reserveId), "t")!!
        assertEquals(listOf(active.reserveId), h.protocol.lastCards.map { it.reserveIdHex })
        assertEquals(listOf("unknown_card", "retry", "accepted"), r.verdicts)   // a pending card's entries are retried, not refused
    }

    @Test fun bad_length_is_null() = runTest {
        val h = h()
        h.protocol.verifyResult = BatchResultFfi(emptyList(), emptyList())
        assertNull(h.svc.settle("00".repeat(95), "t"))
        assertNotNull(h.svc.settle("", "t"))
    }
}
```
`PrepaidRoutesTest.kt`:
```kotlin
package com.wyltek.wallet.agent.server

import com.wyltek.wallet.agent.DispatchResult
import com.wyltek.wallet.core.native.Intent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PrepaidRoutesTest {
    private fun port(journal: PrepaidJournalResult, cards: PrepaidCardsResult) = object : AgentDispatchPort {
        var seenToken: String? = null; var seenEntries: String? = null
        override suspend fun dispatch(token: String, intent: Intent, sourceIp: String, nowUnix: Long) = DispatchResult.Denied("n/a")
        override suspend fun pendingStatus(id: Long): IntentStatusResponse? = null
        override suspend fun accounts(): List<AccountInfo> = emptyList()
        override suspend fun submitRelayIntent(token: String, intent: Intent, sourceIp: String, nowUnix: Long) = "x"
        override suspend fun relayIntentStatus(intentId: String): IntentStatusResponse? = null
        override suspend fun proxyChainRead(token: String, method: String, rawBody: String) = ChainProxyResult.BadToken
        override suspend fun prepaidJournal(token: String, entriesHex: String, sourceIp: String, nowUnix: Long): PrepaidJournalResult { seenToken = token; seenEntries = entriesHex; return journal }
        override suspend fun prepaidCards(token: String, sourceIp: String, nowUnix: Long): PrepaidCardsResult { seenToken = token; return cards }
    }
    private val hdr: (String) -> String? = { if (it == "x-device-token") "tok" else null }
    private val noHdr: (String) -> String? = { null }

    @Test fun journal_ok_returns_per_entry_verdicts() = runBlocking {
        val p = port(PrepaidJournalResult.Ok(listOf(JournalVerdictDto(0, "accepted", "0xab"), JournalVerdictDto(1, "bad_mac"))), PrepaidCardsResult.BadToken)
        val r = routeAgentRequest("POST", "/v1/prepaid/journal", hdr, """{"entries_hex":"00ff"}""", p)
        assertEquals(200, r.status)
        val body = Json.decodeFromString(PrepaidJournalResponse.serializer(), r.body)
        assertEquals(listOf("accepted", "bad_mac"), body.results.map { it.verdict }); assertEquals("0xab", body.results[0].txHash)
        assertEquals("tok", p.seenToken); assertEquals("00ff", p.seenEntries)
    }

    @Test fun journal_bad_token_is_401_and_bad_body_is_400() = runBlocking {
        assertEquals(401, routeAgentRequest("POST", "/v1/prepaid/journal", noHdr, """{"entries_hex":"00"}""", port(PrepaidJournalResult.BadToken, PrepaidCardsResult.BadToken)).status)
        assertEquals(400, routeAgentRequest("POST", "/v1/prepaid/journal", hdr, "not json", port(PrepaidJournalResult.Ok(emptyList()), PrepaidCardsResult.BadToken)).status)
        assertEquals(400, routeAgentRequest("POST", "/v1/prepaid/journal", hdr, """{"entries_hex":"00"}""", port(PrepaidJournalResult.BadRequest("length"), PrepaidCardsResult.BadToken)).status)
    }

    @Test fun cards_lists_active_set() = runBlocking {
        val p = port(PrepaidJournalResult.BadToken, PrepaidCardsResult.Ok(listOf(PrepaidCardDto("aa", 1000, false), PrepaidCardDto("bb", 0, true))))
        val r = routeAgentRequest("GET", "/v1/prepaid/cards", hdr, "", p)
        assertEquals(200, r.status)
        val body = Json.decodeFromString(PrepaidCardsResponse.serializer(), r.body)
        assertEquals(2, body.cards.size); assertTrue(body.cards[1].revoked)
        assertEquals(401, routeAgentRequest("GET", "/v1/prepaid/cards", noHdr, "", p).status)
    }

    @Test fun existing_fake_ports_still_compile_without_prepaid_overrides() = runBlocking {
        val p = object : AgentDispatchPort {
            override suspend fun dispatch(token: String, intent: Intent, sourceIp: String, nowUnix: Long) = DispatchResult.Denied("n/a")
            override suspend fun pendingStatus(id: Long): IntentStatusResponse? = null
            override suspend fun accounts(): List<AccountInfo> = emptyList()
            override suspend fun submitRelayIntent(token: String, intent: Intent, sourceIp: String, nowUnix: Long) = "x"
            override suspend fun relayIntentStatus(intentId: String): IntentStatusResponse? = null
            override suspend fun proxyChainRead(token: String, method: String, rawBody: String) = ChainProxyResult.BadToken
        }
        assertEquals(401, routeAgentRequest("GET", "/v1/prepaid/cards", hdr, "", p).status)
    }
}
```

- [ ] **Step 2: Run to verify failure** — both classes: compile errors.

- [ ] **Step 3: Implement**

`PrepaidService.settle`:
```kotlin
    data class SettleResult(val verdicts: List<String>, val txHash: String?)

    private fun verdictName(v: JournalVerdict) = when (v) {
        JournalVerdict.ACCEPTED -> "accepted"; JournalVerdict.DUPLICATE -> "duplicate"; JournalVerdict.BAD_MAC -> "bad_mac"
        JournalVerdict.UNKNOWN_CARD -> "unknown_card"; JournalVerdict.STALE_COUNTER -> "stale_counter"; JournalVerdict.OVER_BALANCE -> "over_balance"
    }

    /** reserve_id of entry i = bytes 20..52 of the 96-byte entry (main spec §8.1). */
    private fun entryReserveId(entriesHex: String, i: Int): String = entriesHex.removePrefix("0x").substring(i * 192 + 40, i * 192 + 104).lowercase()

    /** D3 spec §6. Null when the body is not valid hex or not a multiple of 96 bytes. Every accepted entry
     *  becomes `retry` when the batch transaction does not commit, when another action holds the service,
     *  and for entries of a card with a pending credit (settled next time). */
    suspend fun settle(entriesHex: String, terminalLabel: String): SettleResult? {
        val hexBody = entriesHex.removePrefix("0x").lowercase()
        if (hexBody.length % 192 != 0 || !hexBody.all { it in '0'..'9' || it in 'a'..'f' }) return null
        return exclusive { settleLocked(hexBody, terminalLabel) } ?: SettleResult(List(hexBody.length / 192) { "retry" }, null)
    }

    private suspend fun settleLocked(hexBody: String, terminalLabel: String): SettleResult {
        val all = store.allCards()
        val pendingIds = all.filter { it.pendingTxHash != null }.map { it.reserveId.lowercase() }.toSet()
        val eligible = all.filter { (it.state == CARD_ACTIVE || it.state == CARD_REVOKED) && it.pendingTxHash == null }
        val res = try {
            protocol.verifyBatch(kHex, hexBody, eligible.map { CardStateFfi(it.reserveId, it.balanceUnits, it.settledCounter.toUInt(), it.state == CARD_REVOKED) })
        } catch (e: PrepaidException) { return SettleResult(List(hexBody.length / 192) { "retry" }, null) }
        val names = res.verdicts.mapIndexed { i, v -> if (v == JournalVerdict.UNKNOWN_CARD && entryReserveId(hexBody, i) in pendingIds) "retry" else verdictName(v) }
        fun retryAll() = SettleResult(names.map { if (it == "accepted") "retry" else it }, null)
        if (res.nets.isEmpty()) return SettleResult(names, null)

        val byId = eligible.associateBy { it.reserveId.lowercase() }
        val moving = res.nets.filter { it.netUnits != 0L }
        val actionId = newActionId()
        suspend fun advance(card: PrepaidCardEntity, net: CardNetFfi, outpoint: String?, txHash: String?) {
            val newBalance = (card.balanceUnits - net.netUnits).toInt()
            store.updateCard(card.copy(balanceUnits = newBalance, settledCounter = net.maxTmc.toLong(), counter = maxOf(card.counter, net.maxTmc.toLong()),
                currentOutpoint = outpoint ?: card.currentOutpoint, updatedAt = clock()))
            log(Audit(actionId, KIND_SETTLE, card.uid, card.reserveId, -net.netUnits, card.balanceUnits, card.counter), PHASE_DONE,
                balanceAfter = newBalance, counterAfter = net.maxTmc.toLong(), txHash = txHash, detail = "$terminalLabel; ${net.accepted} entries; tmc ≤ ${net.maxTmc}")
        }
        if (moving.isEmpty()) { res.nets.forEach { n -> byId[n.reserveIdHex.lowercase()]?.let { advance(it, n, null, null) } }; return SettleResult(names, null) }

        val cards = moving.map { byId[it.reserveIdHex.lowercase()] ?: return retryAll() }
        val reserves = cards.map { chain.cellAt(it.currentOutpoint) ?: return retryAll() }
        val fixed = moving.mapIndexed { i, n -> PrepaidTxPlanner.output((reserves[i].capacity.toLong() - n.netUnits * UNIT_SHANNONS).toULong(), signer.lock) }
        val planned = try {
            PrepaidTxPlanner.plan(reserves, chain.cellsByLock(signer.lock), excludedOutpoints() - cards.map { it.currentOutpoint }.toSet(), fixed, signer.lock)
        } catch (e: InsufficientFunds) {
            log(Audit(actionId, KIND_SETTLE, "", null, 0, 0, 0), PHASE_FAILED, detail = "$terminalLabel; ${e.message}"); return retryAll()
        }
        val built = signer.build(planned.inputs, planned.outputs)
        val batchAudit = Audit(actionId, KIND_SETTLE, "", null, moving.sumOf { -it.netUnits }, 0, 0)
        log(batchAudit, PHASE_REQUESTED, detail = "$terminalLabel; ${moving.size} cards")
        when (val r = broadcastAndAwait(batchAudit, planned, built, {})) {
            is TxResult.Committed -> {
                moving.forEachIndexed { i, n -> advance(cards[i], n, "${r.txHash}:$i", r.txHash) }
                res.nets.filter { it.netUnits == 0L }.forEach { n -> byId[n.reserveIdHex.lowercase()]?.let { advance(it, n, null, r.txHash) } }
                SettleResult(names, r.txHash)
            }
            is TxResult.Timeout -> { log(batchAudit, PHASE_FAILED, txHash = r.txHash, detail = "$terminalLabel; not committed in time"); retryAll() }
            else -> retryAll()
        }
    }
```
(`settle` never blocks on a busy service: it answers `retry` so the terminal keeps its entries.)

`AgentDtos.kt` additions:
```kotlin
@Serializable data class PrepaidJournalRequest(@SerialName("entries_hex") val entriesHex: String)
@Serializable data class JournalVerdictDto(val index: Int, val verdict: String, @SerialName("tx_hash") val txHash: String? = null)
@Serializable data class PrepaidJournalResponse(val results: List<JournalVerdictDto>)
@Serializable data class PrepaidCardDto(@SerialName("reserve_id") val reserveId: String, @SerialName("balance_units") val balanceUnits: Int, val revoked: Boolean)
@Serializable data class PrepaidCardsResponse(val cards: List<PrepaidCardDto>)

sealed class PrepaidJournalResult {
    data object BadToken : PrepaidJournalResult()
    data class BadRequest(val reason: String) : PrepaidJournalResult()
    data class Ok(val results: List<JournalVerdictDto>) : PrepaidJournalResult()
}
sealed class PrepaidCardsResult {
    data object BadToken : PrepaidCardsResult()
    data class Ok(val cards: List<PrepaidCardDto>) : PrepaidCardsResult()
}
```
`AgentDispatchPort` additions (with defaults so existing fakes compile):
```kotlin
    /** D3 spec §6: verify + settle a journal batch. Token in `x-device-token`, scope `prepaid_settle`. */
    suspend fun prepaidJournal(token: String, entriesHex: String, sourceIp: String, nowUnix: Long): PrepaidJournalResult = PrepaidJournalResult.BadToken
    suspend fun prepaidCards(token: String, sourceIp: String, nowUnix: Long): PrepaidCardsResult = PrepaidCardsResult.BadToken
```
`AgentRouter` additions before `else -> RouteResult(404)`:
```kotlin
        method == "POST" && path == "/v1/prepaid/journal" -> {
            val token = header("x-device-token") ?: return RouteResult(401)
            val req = try { json.decodeFromString(PrepaidJournalRequest.serializer(), body) } catch (e: Exception) { return RouteResult(400) }
            when (val r = port.prepaidJournal(token, req.entriesHex, sourceIp, System.currentTimeMillis() / 1000)) {
                PrepaidJournalResult.BadToken -> RouteResult(401)
                is PrepaidJournalResult.BadRequest -> RouteResult(400, "application/json", """{"error":"${r.reason}"}""")
                is PrepaidJournalResult.Ok -> RouteResult(200, "application/json", json.encodeToString(PrepaidJournalResponse.serializer(), PrepaidJournalResponse(r.results)))
            }
        }
        method == "GET" && path == "/v1/prepaid/cards" -> {
            val token = header("x-device-token") ?: return RouteResult(401)
            when (val r = port.prepaidCards(token, sourceIp, System.currentTimeMillis() / 1000)) {
                PrepaidCardsResult.BadToken -> RouteResult(401)
                is PrepaidCardsResult.Ok -> RouteResult(200, "application/json", json.encodeToString(PrepaidCardsResponse.serializer(), PrepaidCardsResponse(r.cards)))
            }
        }
```
Update the router's KDoc route list with the two routes.

- [ ] **Step 4: Run to verify pass** → 9 passed; full unit suite (including the untouched `AgentServerTest`) green.

- [ ] **Step 5: Mutation probe** — in `settle`, drop `&& it.pendingTxHash == null` from `eligible` → `closed_and_issuing_cards_are_unknown_to_settlement` red (the issuing card reaches `lastCards`). Restore.

- [ ] **Step 6: Commit** — `git add app/src/main/java/com/wyltek/wallet/prepaid/PrepaidService.kt app/src/main/java/com/wyltek/wallet/agent/server app/src/test && git commit -m "feat(prepaid): settlement batches, /v1/prepaid/journal and /v1/prepaid/cards routes" -m "Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"`

---

### Task 13: Android wiring — native adapters, gateway, auth, manifest, navigation, scope checkbox, bundle

**Files:**
- Create: `app/src/main/java/com/wyltek/wallet/prepaid/NativeProtocol.kt`, `RpcChainPort.kt`, `SecpSigner.kt`, `PrepaidAmount.kt`
- Modify: `app/src/main/java/com/wyltek/wallet/agent/AgentGateway.kt`, `app/src/main/java/com/wyltek/wallet/agent/ui/AgentViewModel.kt`, `app/src/main/java/com/wyltek/wallet/ui/screens/AgentScreen.kt`, `app/src/main/java/com/wyltek/wallet/ui/screens/SettingsScreen.kt`, `app/src/main/java/com/wyltek/wallet/ui/navigation/AppNavigation.kt`, `app/src/main/AndroidManifest.xml`
- Test: `app/src/test/java/com/wyltek/wallet/prepaid/PrepaidAmountTest.kt`

**Interfaces:**
- Produces: `object PrepaidAmount { fun parseCkbToUnits(text: String): Int?; fun formatUnits(units: Long): String }`; `class NativeProtocol : Protocol`; `class RpcChainPort(chainManager: ChainManager) : ChainPort`; `class SecpSigner(seedHex: String, address: CkbAddress, network: NetworkType) : SignerPort`; `AgentGateway.prepaid: PrepaidService?` and `AgentGateway.prepaidUnavailableReason: String?`; `AgentGateway.prepaidBundleFields(): Pair<String, String>?` (k_debit hex, merchant_id hex); `Screen.Prepaid("prepaid")`, `Screen.PrepaidCard("prepaid-card/{reserveId}")`, `Screen.PrepaidAudit("prepaid-audit")`; Settings item "Prepaid Cards (DEMO)"; Agent screen section "Prepaid cards (DEMO)" with an "Open" button; mint checkbox `prepaid_settle`.

- [ ] **Step 1: Write the failing test** (Review Focus 5)

```kotlin
package com.wyltek.wallet.prepaid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PrepaidAmountTest {
    @Test fun parseCkbToUnits_accepts_up_to_three_decimals() {
        assertEquals(250_000, PrepaidAmount.parseCkbToUnits("250"))
        assertEquals(250_500, PrepaidAmount.parseCkbToUnits("250.5"))
        assertEquals(1, PrepaidAmount.parseCkbToUnits("0.001"))
        assertEquals(1_000_000, PrepaidAmount.parseCkbToUnits(" 1000 "))
    }
    @Test fun parseCkbToUnits_rejects_bad_input() {
        listOf("", " ", "abc", "-5", "0", "0.0004", "1.2345", "1,000", "1e3", "2147483648", "+5", ".5", "5.").forEach {
            assertNull("should reject '$it'", PrepaidAmount.parseCkbToUnits(it))
        }
    }
    @Test fun formatUnits_is_ckb_with_three_decimals() {
        assertEquals("250.000 CKB", PrepaidAmount.formatUnits(250_000))
        assertEquals("-0.001 CKB", PrepaidAmount.formatUnits(-1))
    }
}
```

- [ ] **Step 2: Run to verify failure** — compile error.

- [ ] **Step 3: Implement**

`PrepaidAmount.kt`:
```kotlin
package com.wyltek.wallet.prepaid

/** CKB text ⇄ milli-CKB units. Strict: digits, at most three decimals, positive, no separators or exponents. */
object PrepaidAmount {
    private val pattern = Regex("""^(\d+)(?:\.(\d{1,3}))?$""")
    fun parseCkbToUnits(text: String): Int? {
        val m = pattern.matchEntire(text.trim()) ?: return null
        val whole = m.groupValues[1].toLongOrNull() ?: return null
        val frac = m.groupValues[2].padEnd(3, '0').toLong()
        val units = whole * 1000 + frac
        return if (units <= 0 || units > Int.MAX_VALUE) null else units.toInt()
    }
    fun formatUnits(units: Long): String {
        val sign = if (units < 0) "-" else ""
        val a = kotlin.math.abs(units)
        return "%s%d.%03d CKB".format(sign, a / 1000, a % 1000)
    }
}
```
`NativeProtocol.kt`:
```kotlin
package com.wyltek.wallet.prepaid

import com.wyltek.wallet.core.model.LockScript
import com.wyltek.wallet.core.native.*

class NativeProtocol : Protocol {
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun unhex(s: String) = s.removePrefix("0x").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    override fun selectSlot(kHex: String, uidHex: String, raw128: ByteArray) = prepaidSelectSlot(kHex, uidHex, hex(raw128))
    override fun seal(kHex: String, uidHex: String, rec: CardRecordInfo) = unhex(prepaidSealRecord(kHex, uidHex, rec))
    override fun nextSlot(pick: SlotPickFfi) = prepaidNextSlot(pick)
    override fun reserveId(txHashHex: String, index: UInt) = prepaidReserveId(txHashHex, index)
    override fun merchantId(lock: LockScript) = prepaidMerchantId(lock.codeHash, lock.hashType, lock.args)
    override fun plan(action: PrepaidAction, balanceUnits: Int, capacityShannons: ULong, amountUnits: Int) = prepaidPlan(action, balanceUnits, capacityShannons, amountUnits)
    override fun verifyBatch(kHex: String, entriesHex: String, cards: List<CardStateFfi>) = prepaidVerifyBatch(kHex, entriesHex, cards)
    override fun lockOf(address: String): LockScript? = try {
        val i = decodeAddress(address); LockScript(i.lockCodeHash, i.lockHashType, i.lockArgs)
    } catch (e: Exception) { null }
}
```
`RpcChainPort.kt`:
```kotlin
package com.wyltek.wallet.prepaid

import com.wyltek.wallet.core.chain.ChainManager
import com.wyltek.wallet.core.chain.TxStatus
import com.wyltek.wallet.core.model.LockScript
import com.wyltek.wallet.core.model.Utxo
import kotlinx.serialization.json.JsonObject

class RpcChainPort(private val chainManager: ChainManager) : ChainPort {
    override suspend fun cellsByLock(lock: LockScript): List<Utxo> = chainManager.getCellsByLock(lock)
    override suspend fun cellAt(outpoint: String): Utxo? {
        // Reserve cells always carry the merchant lock; the index records only the outpoint, so scan by lock.
        val (tx, idx) = outpoint.split(":").let { it[0] to it[1].toUInt() }
        return cachedLock?.let { lock -> chainManager.getCellsByLock(lock).firstOrNull { it.outPoint.txHash == tx && it.outPoint.index == idx } }
    }
    /** Set by the gateway once the merchant lock is known; cellAt needs a lock to query the indexer. */
    var cachedLock: LockScript? = null
    override suspend fun broadcast(txJson: JsonObject, txHash: String): Boolean {
        val sent = chainManager.sendTransactionJson(txJson) ?: return false
        return sent.equals(txHash, ignoreCase = true)
    }
    override suspend fun status(txHash: String): TxStatus? = chainManager.getActiveProvider()?.getTransactionStatus(txHash)
}
```
`SecpSigner.kt`:
```kotlin
package com.wyltek.wallet.prepaid

import com.wyltek.wallet.core.chain.NetworkConfig
import com.wyltek.wallet.core.model.CkbAddress
import com.wyltek.wallet.core.model.LockScript
import com.wyltek.wallet.core.model.NetworkType
import com.wyltek.wallet.core.model.Utxo
import com.wyltek.wallet.core.native.*

/** The classic secp256k1_blake160 path `sendCkb` uses, for one address. */
class SecpSigner(seedHex: String, address: CkbAddress, network: NetworkType) : SignerPort {
    private val keyPair = generateSecp256k1Keypair(seedHex, "m/44'/302'/0'/0/0")
    private val cfg = NetworkConfig.forNetwork(network)
    override val lock: LockScript = address.lockScript
    override val cellDep = CellDepRef(cfg.secp256k1DepGroupTxHash, cfg.secp256k1DepGroupIndex, "dep_group")
    override fun build(inputs: List<Utxo>, outputs: List<TxOutput>): BuiltTransaction = buildTransaction(TransactionRequest(
        inputs = inputs.map { TxInput(it.outPoint.txHash, it.outPoint.index, 0uL, it.capacity) },
        outputs = outputs,
        cellDeps = listOf(TxCellDep(cfg.secp256k1DepGroupTxHash, cfg.secp256k1DepGroupIndex, 1.toUByte())),
        feeRate = 1000uL))
    override fun witness0(txHashHex: String, numInputs: Int): String = "0x" + signCkbSecp256k1Witness(txHashHex, numInputs.toUInt(), keyPair.privateKeyHex)
}
```
`AgentGateway` additions:
```kotlin
    // ---- Prepaid cards (D3) ----
    private val prepaidKeys by lazy { PrepaidKeys(load = { secure.loadBlob(it) }, store = { k, v -> secure.storeBlob(k, v) }) }
    @Volatile var prepaidUnavailableReason: String? = null; private set
    val prepaid: PrepaidService? by lazy {
        val account = repository.getAllAccounts().firstOrNull { it.network == NetworkType.TESTNET && it.addresses.any { a -> !NetworkConfig.isPqLock(a.lockScript.codeHash, it.network) } }
        if (account == null) { prepaidUnavailableReason = "Prepaid demo runs on testnet only; add or select a testnet classic account"; return@lazy null }
        val address = account.addresses.first { !NetworkConfig.isPqLock(it.lockScript.codeHash, account.network) }
        val seed = com.wyltek.wallet.core.keystore.SeedVault(app).loadSeed(account.id)
        if (seed == null) { prepaidUnavailableReason = "Seed for ${account.name} is not available"; return@lazy null }
        val chainPort = RpcChainPort(repository.getChainManager()).also { it.cachedLock = address.lockScript }
        PrepaidService(RoomPrepaidStore(db), chainPort, SecpSigner(seed, address, account.network), NativeProtocol(), prepaidKeys)
    }
    fun prepaidBundleFields(): Pair<String, String>? = prepaid?.let { prepaidKeys.kDebitHex() to it.merchantIdHex }

    private suspend fun prepaidAuth(token: String, sourceIp: String, nowUnix: Long): Boolean {
        val reg = db.agentDao().tokenByString(token) ?: return false
        if (reg.revoked) return false
        return try { authorizeOp(token, keyStore.rootPublicHex(), "prepaid_settle", reg.account, sourceIp, nowUnix); true } catch (e: Exception) { false }
    }

    override suspend fun prepaidJournal(token: String, entriesHex: String, sourceIp: String, nowUnix: Long): PrepaidJournalResult {
        if (!prepaidAuth(token, sourceIp, nowUnix)) return PrepaidJournalResult.BadToken
        val svc = prepaid ?: return PrepaidJournalResult.BadRequest(prepaidUnavailableReason ?: "prepaid unavailable")
        val tokenId = try { tokenIdOf(token, keyStore.rootPublicHex()).take(8) } catch (e: Exception) { "?" }
        val r = svc.settle(entriesHex, "terminal token $tokenId") ?: return PrepaidJournalResult.BadRequest("entries must be hex and a multiple of 96 bytes")
        return PrepaidJournalResult.Ok(r.verdicts.mapIndexed { i, v -> JournalVerdictDto(i, v, r.txHash.takeIf { v == "accepted" }) })
    }

    override suspend fun prepaidCards(token: String, sourceIp: String, nowUnix: Long): PrepaidCardsResult {
        if (!prepaidAuth(token, sourceIp, nowUnix)) return PrepaidCardsResult.BadToken
        val cards = db.prepaidDao().allCards().filter { it.state == CARD_ACTIVE || it.state == CARD_REVOKED }.take(64)
        return PrepaidCardsResult.Ok(cards.map { PrepaidCardDto(it.reserveId, it.balanceUnits, it.state == CARD_REVOKED) })
    }
```
`AgentViewModel`: in the bundle path, pass `gateway.prepaidBundleFields()` to `buildProvisioningBundle(deviceId, token, fields?.first, fields?.second)`. Find the single `buildProvisioningBundle(` call in `AgentScreen.kt` (around line 254): it builds from `uiState.deviceId` and `token`; add `kDebitHex = uiState.prepaidKDebit, merchantIdHex = uiState.prepaidMerchantId` with two new nullable fields on `AgentUiState` that `refresh()` fills from `gateway.prepaidBundleFields()`.

`AgentScreen` mint section: a third row of `ScopeCheckbox(label = "prepaid_settle", checked = scopePrepaid, ...)`; `if (scopePrepaid) add(Scope.PREPAID_SETTLE)`; include `scopePrepaid` in the `enabled` condition. Add a section after Relay:
```kotlin
            AgentSettingsSection(title = "Prepaid cards (DEMO)") {
                ListItem(headlineContent = { Text("Issue, load, void and audit NTAG prepaid cards") },
                    supportingContent = { Text("Testnet only. Terminal-attested demo tier.", color = TextSecondary) },
                    modifier = Modifier.clickable(onClick = onPrepaid), colors = ListItemDefaults.colors(containerColor = DarkSurfaceVariant))
            }
```
with a new `onPrepaid: () -> Unit = {}` parameter on `AgentScreen`. `SettingsScreen`: a `SettingsItem(icon = Icons.Default.CreditCard, title = "Prepaid Cards (DEMO)", subtitle = "NFC cards backed by testnet reserves", onClick = onPrepaid)` in the Agent section with a new `onPrepaid` parameter.

`AppNavigation`: add `Screen.Prepaid`, `Screen.PrepaidCard`, `Screen.PrepaidAudit` and pass `onPrepaid = { navController.navigate(Screen.Prepaid.route) }` to both screens. The three composables are filled in Tasks 14 and 15; for this task register them with placeholder calls to `PrepaidScreen`, `PrepaidCardDetailScreen`, `PrepaidAuditScreen` only after those exist — so in this task add the routes and the two `onPrepaid` callbacks, and leave the `composable(Screen.Prepaid.route)` block to Task 14.

`AndroidManifest.xml`: add `<uses-permission android:name="android.permission.NFC" />` and `<uses-feature android:name="android.hardware.nfc" android:required="false" />`.

- [ ] **Step 4: Run to verify pass** — `./gradlew :app:testDebugUnitTest --tests '*PrepaidAmountTest*' -q` → PASS; `./gradlew :app:compileDebugKotlin -q` → OK.

- [ ] **Step 5: Commit** — `git add -A app/src/main app/src/test && git commit -m "feat(prepaid): native adapters, gateway wiring, auth, manifest NFC, nav routes, prepaid_settle scope" -m "Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"`

---

### Task 14: `PrepaidViewModel`, NFC reader mode, Cards screen, tap sheet

**Files:**
- Create: `app/src/main/java/com/wyltek/wallet/prepaid/nfc/NfcReaderMode.kt`, `app/src/main/java/com/wyltek/wallet/prepaid/ui/PrepaidViewModel.kt`, `app/src/main/java/com/wyltek/wallet/prepaid/ui/PrepaidScreen.kt`, `app/src/main/java/com/wyltek/wallet/prepaid/ui/PrepaidTapSheet.kt`
- Modify: `app/src/main/java/com/wyltek/wallet/ui/navigation/AppNavigation.kt`
- Test: `app/src/test/java/com/wyltek/wallet/prepaid/PrepaidUiStateTest.kt`

**Interfaces:**
- Produces: `data class PrepaidUiState(cards, nfcAvailable: Boolean, unavailableReason: String?, tap: TapClass?, tagPresent: Boolean, phases: List<Pair<Phase, Long>>, outcome: ActionOutcome?, busy: Boolean, merchantIdHex: String, reserveTotalCkb: String, badge: String = "DEMO")`; `PrepaidViewModel(application)` with `onTag(tag: Tag)`, `dismissTap()`, `issue(amountText, label)`, `load(amountText)`, `resume()`, `adopt()`, `repair()`, `revoke(card)`, `reinstate(card)`, `voidCard(card)`, `cashOut(address)`, `preview(card): CashOutPreview`; `@Composable fun NfcReaderMode(enabled: Boolean, onTag: (Tag) -> Unit)`; `@Composable fun PrepaidScreen(onBack, onCard: (String) -> Unit, onAudit, onScanAddress, scannedAddress: String?, viewModel)`; a pure `object PrepaidUiReducer { fun tapActions(tap: TapClass?, tagPresent: Boolean): List<TapAction> }` with `enum class TapAction { ISSUE, LOAD, RESUME, ADOPT, REPAIR, REVOKE, REINSTATE, VOID, CASH_OUT }`.

- [ ] **Step 1: Write the failing test** for the one pure piece:

```kotlin
package com.wyltek.wallet.prepaid

import com.wyltek.wallet.core.native.*
import com.wyltek.wallet.prepaid.db.*
import com.wyltek.wallet.prepaid.ui.PrepaidUiReducer
import com.wyltek.wallet.prepaid.ui.TapAction
import org.junit.Assert.assertEquals
import org.junit.Test

class PrepaidUiStateTest {
    private val slot = SlotResult(SlotPickFfi.A, null, SlotReason.NO_VALID_SLOT, null)
    private fun card(state: String) = PrepaidCardEntity("r", "u", null, "x:0", "x:0", 100, 0, 0, state, issuedAt = 0, updatedAt = 0)
    private val rec = CardRecordInfo(0u, "r", 100, 0u, "m")

    @Test fun actions_follow_the_classification_table() {
        assertEquals(listOf(TapAction.ISSUE), PrepaidUiReducer.tapActions(TapClass.Blank("u"), true))
        assertEquals(listOf(TapAction.RESUME), PrepaidUiReducer.tapActions(TapClass.Resume(card(CARD_ACTIVE), slot), true))
        assertEquals(listOf(TapAction.REPAIR, TapAction.VOID, TapAction.CASH_OUT), PrepaidUiReducer.tapActions(TapClass.Damaged(card(CARD_ACTIVE), "x"), true))
        assertEquals(listOf(TapAction.LOAD, TapAction.REVOKE, TapAction.CASH_OUT, TapAction.VOID), PrepaidUiReducer.tapActions(TapClass.Known(card(CARD_ACTIVE), rec, slot), true))
        assertEquals(listOf(TapAction.REINSTATE, TapAction.CASH_OUT, TapAction.VOID), PrepaidUiReducer.tapActions(TapClass.Known(card(CARD_REVOKED), rec, slot), true))
        assertEquals(emptyList<TapAction>(), PrepaidUiReducer.tapActions(TapClass.Known(card(CARD_CLOSED), rec, slot), true))
        assertEquals(listOf(TapAction.ADOPT), PrepaidUiReducer.tapActions(TapClass.Bench(rec, slot), true))
        assertEquals(emptyList<TapAction>(), PrepaidUiReducer.tapActions(TapClass.Refused("x"), true))
        assertEquals(emptyList<TapAction>(), PrepaidUiReducer.tapActions(null, false))
    }
}
```

- [ ] **Step 2: Run to verify failure** — compile error.

- [ ] **Step 3: Implement**

`NfcReaderMode.kt`:
```kotlin
package com.wyltek.wallet.prepaid.nfc

import android.app.Activity
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext

/** Reader mode while [enabled] (D3 spec §5): NfcA only, NDEF check skipped, no platform sound. Disabled on dispose. */
@Composable
fun NfcReaderMode(enabled: Boolean, onTag: (Tag) -> Unit) {
    val activity = LocalContext.current as? Activity ?: return
    val adapter = NfcAdapter.getDefaultAdapter(activity) ?: return
    val latest by rememberUpdatedState(onTag)
    DisposableEffect(enabled) {
        if (enabled) {
            val flags = NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK or NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS
            val extras = Bundle().apply { putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 250) }
            adapter.enableReaderMode(activity, { tag -> latest(tag) }, flags, extras)
        }
        onDispose { adapter.disableReaderMode(activity) }
    }
}

fun hasNfc(activity: Activity): Boolean = NfcAdapter.getDefaultAdapter(activity) != null
```
`PrepaidViewModel.kt`:
```kotlin
package com.wyltek.wallet.prepaid.ui

import android.app.Application
import android.nfc.NfcAdapter
import android.nfc.Tag
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wyltek.wallet.WyltekWalletApp
import com.wyltek.wallet.prepaid.*
import com.wyltek.wallet.prepaid.db.*
import com.wyltek.wallet.prepaid.nfc.MifareTagPort
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

enum class TapAction { ISSUE, LOAD, RESUME, ADOPT, REPAIR, REVOKE, REINSTATE, VOID, CASH_OUT }

object PrepaidUiReducer {
    fun tapActions(tap: TapClass?, tagPresent: Boolean): List<TapAction> = when (tap) {
        null, is TapClass.Refused -> emptyList()
        is TapClass.Blank -> listOf(TapAction.ISSUE)
        is TapClass.Resume -> listOf(TapAction.RESUME)
        is TapClass.Damaged -> listOf(TapAction.REPAIR, TapAction.VOID, TapAction.CASH_OUT)
        is TapClass.Bench -> listOf(TapAction.ADOPT)
        is TapClass.Known -> when (tap.card.state) {
            CARD_ACTIVE -> listOf(TapAction.LOAD, TapAction.REVOKE, TapAction.CASH_OUT, TapAction.VOID)
            CARD_REVOKED -> listOf(TapAction.REINSTATE, TapAction.CASH_OUT, TapAction.VOID)
            else -> emptyList()
        }
    }
}

data class PrepaidUiState(
    val cards: List<PrepaidCardEntity> = emptyList(),
    val nfcAvailable: Boolean = false,
    val unavailableReason: String? = null,
    val tap: TapClass? = null,
    val tagPresent: Boolean = false,
    val phases: List<Pair<Phase, Long>> = emptyList(),
    val outcome: ActionOutcome? = null,
    val busy: Boolean = false,
    val merchantIdHex: String = "",
    val reserveTotalCkb: String = "",
    val badge: String = "DEMO",
)

class PrepaidViewModel(application: Application) : AndroidViewModel(application) {
    private val gateway = (application as WyltekWalletApp).agentGateway
    private val svc: PrepaidService? = gateway.prepaid
    private var tag: TagPort? = null
    private val _ui = MutableStateFlow(PrepaidUiState(
        nfcAvailable = NfcAdapter.getDefaultAdapter(application) != null,
        unavailableReason = if (gateway.prepaid == null) gateway.prepaidUnavailableReason else null,
        merchantIdHex = gateway.prepaid?.merchantIdHex ?: ""))
    val ui: StateFlow<PrepaidUiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch { gateway.prepaidStore().cardsFlow().collect { cards ->
            val total = cards.filter { it.state == CARD_ACTIVE || it.state == CARD_REVOKED }.sumOf { it.balanceUnits.toLong() }
            _ui.update { it.copy(cards = cards, reserveTotalCkb = PrepaidAmount.formatUnits(total)) } } }
        viewModelScope.launch { svc?.recoverPending() }
    }

    fun onTag(raw: Tag) {
        val s = svc ?: return
        if (!MifareTagPort.accepts(raw)) { _ui.update { it.copy(tap = TapClass.Refused("not an NTAG21x card"), tagPresent = false) }; return }
        val port = MifareTagPort(raw); tag = port
        val pending = _ui.value.tap as? TapClass.Resume
        viewModelScope.launch {
            if (pending != null && pending.card.uid.equals(port.uidHex, true) && _ui.value.outcome is ActionOutcome.AwaitingTag) { run("resume") { s.resume(port, pending.card, ::phase) }; return@launch }
            val cls = s.classify(port)
            _ui.update { it.copy(tap = cls, tagPresent = true, phases = emptyList(), outcome = null) }
        }
    }

    fun dismissTap() { tag = null; _ui.update { it.copy(tap = null, tagPresent = false, phases = emptyList(), outcome = null) } }

    private fun phase(p: Phase) { _ui.update { it.copy(phases = it.phases + (p to System.currentTimeMillis())) } }

    private suspend fun run(what: String, block: suspend () -> ActionOutcome) {
        _ui.update { it.copy(busy = true, outcome = null) }
        val out = try { block() } catch (e: Exception) { ActionOutcome.Failed("$what failed: ${e.message}") }
        _ui.update { it.copy(busy = false, outcome = out) }
    }

    fun issue(amountText: String, label: String) = act { s, t -> val u = PrepaidAmount.parseCkbToUnits(amountText) ?: return@act ActionOutcome.Failed("enter an amount in CKB, up to three decimals"); s.issue(t, u, label, ::phase) }
    fun load(amountText: String) = act { s, t -> val c = (ui.value.tap as? TapClass.Known)?.card ?: return@act ActionOutcome.Failed("no card")
        val u = PrepaidAmount.parseCkbToUnits(amountText) ?: return@act ActionOutcome.Failed("enter an amount in CKB, up to three decimals"); s.load(t, c, u, ::phase) }
    fun resume() = act { s, t -> val c = (ui.value.tap as? TapClass.Resume)?.card ?: return@act ActionOutcome.Failed("no card"); s.resume(t, c, ::phase) }
    fun adopt() = act { s, t -> val r = (ui.value.tap as? TapClass.Bench)?.record ?: return@act ActionOutcome.Failed("no record"); s.adopt(t, r, ::phase) }
    fun repair() = act { s, t -> val c = (ui.value.tap as? TapClass.Damaged)?.card ?: return@act ActionOutcome.Failed("no card"); s.repair(t, c, ::phase) }
    fun cashOut(address: String) = act { s, t -> val c = tappedCard() ?: return@act ActionOutcome.Failed("no card"); s.cashOut(t, c, address, ::phase) }
    fun revoke(card: PrepaidCardEntity) = viewModelScope.launch { svc?.let { s -> run("revoke") { s.revoke(card) } } }
    fun reinstate(card: PrepaidCardEntity) = viewModelScope.launch { svc?.let { s -> run("reinstate") { s.reinstate(card) } } }
    fun voidCard(card: PrepaidCardEntity) = viewModelScope.launch { svc?.let { s -> run("void") { s.void(card, tag?.takeIf { it.uidHex.equals(card.uid, true) }, ::phase) } } }
    suspend fun preview(card: PrepaidCardEntity): PrepaidService.CashOutPreview? = svc?.inspectForRetire(tag, card)

    fun tappedCard(): PrepaidCardEntity? = when (val t = ui.value.tap) { is TapClass.Known -> t.card; is TapClass.Damaged -> t.card; is TapClass.Resume -> t.card; else -> null }

    private fun act(block: suspend (PrepaidService, TagPort) -> ActionOutcome) = viewModelScope.launch {
        val s = svc ?: return@launch; val t = tag ?: run { _ui.update { it.copy(outcome = ActionOutcome.Failed("tap the card first")) }; return@launch }
        run("action") { block(s, t) }
    }
}
```
Add to `AgentGateway`: `fun prepaidStore(): PrepaidStore = RoomPrepaidStore(db)`.

`PrepaidScreen.kt` (Compose, material3; theme colours from `ui/theme/Color.kt`):
```kotlin
package com.wyltek.wallet.prepaid.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wyltek.wallet.prepaid.PrepaidAmount
import com.wyltek.wallet.prepaid.db.*
import com.wyltek.wallet.prepaid.nfc.NfcReaderMode
import com.wyltek.wallet.ui.theme.*

@Composable
fun DemoBadge() = AssistChip(onClick = {}, label = { Text("DEMO") }, colors = AssistChipDefaults.assistChipColors(containerColor = WarningOrange, labelColor = DarkBackground))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrepaidScreen(
    onBack: () -> Unit,
    onCard: (String) -> Unit,
    onAudit: () -> Unit,
    onScanAddress: () -> Unit,
    scannedAddress: String?,
    viewModel: PrepaidViewModel = viewModel(),
) {
    val ui by viewModel.ui.collectAsState()
    val readerOn = ui.nfcAvailable && ui.unavailableReason == null
    NfcReaderMode(enabled = readerOn, onTag = viewModel::onTag)

    Scaffold(topBar = {
        TopAppBar(title = { Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) { Text("Prepaid cards"); Spacer(Modifier.width(8.dp)); DemoBadge() } },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } },
            actions = { IconButton(onClick = onAudit) { Icon(Icons.Default.History, "Audit log") } })
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val active = ui.cards.count { it.state == CARD_ACTIVE }; val revoked = ui.cards.count { it.state == CARD_REVOKED }; val closed = ui.cards.count { it.state == CARD_CLOSED }
            Text("Reserves ${ui.reserveTotalCkb} · $active active · $revoked revoked · $closed closed", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
            when {
                ui.unavailableReason != null -> Card(colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)) { Text(ui.unavailableReason!!, Modifier.padding(12.dp), color = WarningOrange) }
                !ui.nfcAvailable -> Card(colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)) { Text("This phone has no NFC. Cards can be viewed and audited but not issued.", Modifier.padding(12.dp), color = WarningOrange) }
                else -> Card(colors = CardDefaults.cardColors(containerColor = DarkSurface)) { Text("Hold a card to the back of the phone", Modifier.padding(12.dp), color = NeonCyan) }
            }
            if (ui.cards.isEmpty()) Text("No cards yet.", color = TextSecondary)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(ui.cards, key = { it.reserveId }) { c ->
                    ListItem(
                        headlineContent = { Text(c.label ?: c.uid) },
                        supportingContent = { Text("${PrepaidAmount.formatUnits(c.balanceUnits.toLong())} · counter ${c.counter}" + (c.pendingUnits?.let { " · pending ${PrepaidAmount.formatUnits(it.toLong())}" } ?: ""), color = TextSecondary) },
                        trailingContent = { AssistChip(onClick = {}, label = { Text(c.state) }) },
                        modifier = Modifier.clickable { onCard(c.reserveId) },
                        colors = ListItemDefaults.colors(containerColor = DarkSurfaceVariant))
                }
            }
        }
    }
    if (ui.tap != null) PrepaidTapSheet(ui = ui, viewModel = viewModel, onDismiss = viewModel::dismissTap, onScanAddress = onScanAddress, scannedAddress = scannedAddress)
}
```
`PrepaidTapSheet.kt`:
```kotlin
package com.wyltek.wallet.prepaid.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.wyltek.wallet.prepaid.*
import com.wyltek.wallet.prepaid.db.PrepaidCardEntity
import com.wyltek.wallet.ui.security.BiometricResult
import com.wyltek.wallet.ui.security.rememberBiometricAuth
import com.wyltek.wallet.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrepaidTapSheet(ui: PrepaidUiState, viewModel: PrepaidViewModel, onDismiss: () -> Unit, onScanAddress: () -> Unit, scannedAddress: String?) {
    val biometric = rememberBiometricAuth()
    var amount by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }
    var confirmVoid by remember { mutableStateOf<PrepaidCardEntity?>(null) }
    var confirmCashOut by remember { mutableStateOf<PrepaidCardEntity?>(null) }
    var preview by remember { mutableStateOf<PrepaidService.CashOutPreview?>(null) }
    var authError by remember { mutableStateOf<String?>(null) }
    val actions = PrepaidUiReducer.tapActions(ui.tap, ui.tagPresent)
    val fmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    fun gated(title: String, block: () -> Unit) {
        authError = null
        biometric.authenticate(title = title, subtitle = "Confirm this prepaid transaction") { r ->
            when (r) {
                is BiometricResult.Success -> block()
                is BiometricResult.Unavailable -> block()      // no device credential set up: the demo tier does not block on it
                is BiometricResult.Cancelled -> {}
                is BiometricResult.Error -> authError = r.message
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = DarkSurface) {
        Column(Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row { Text(headline(ui.tap), style = MaterialTheme.typography.titleMedium, color = TextPrimary); Spacer(Modifier.width(8.dp)); DemoBadge() }
            detail(ui.tap)?.let { Text(it, color = TextSecondary) }
            if (TapAction.ISSUE in actions || TapAction.LOAD in actions) {
                OutlinedTextField(value = amount, onValueChange = { amount = it }, label = { Text("Amount (CKB, max 1000)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (TapAction.ISSUE in actions) OutlinedTextField(value = label, onValueChange = { label = it }, label = { Text("Label (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (a in actions) when (a) {
                    TapAction.ISSUE -> Button(enabled = !ui.busy, onClick = { gated("Issue card") { viewModel.issue(amount, label) } }) { Text("Issue") }
                    TapAction.LOAD -> Button(enabled = !ui.busy, onClick = { gated("Load card") { viewModel.load(amount) } }) { Text("Load") }
                    TapAction.RESUME -> Button(enabled = !ui.busy, onClick = { viewModel.resume() }) { Text("Finish pending write") }
                    TapAction.ADOPT -> Button(enabled = !ui.busy, onClick = { gated("Adopt card") { viewModel.adopt() } }) { Text("Adopt") }
                    TapAction.REPAIR -> OutlinedButton(enabled = !ui.busy, onClick = { viewModel.repair() }) { Text("Repair") }
                    TapAction.REVOKE -> OutlinedButton(enabled = !ui.busy, onClick = { viewModel.tappedCard()?.let(viewModel::revoke) }) { Text("Revoke") }
                    TapAction.REINSTATE -> OutlinedButton(enabled = !ui.busy, onClick = { viewModel.tappedCard()?.let(viewModel::reinstate) }) { Text("Reinstate") }
                    TapAction.VOID -> OutlinedButton(enabled = !ui.busy, colors = ButtonDefaults.outlinedButtonColors(contentColor = ErrorRed), onClick = { confirmVoid = viewModel.tappedCard() }) { Text("Void") }
                    TapAction.CASH_OUT -> OutlinedButton(enabled = !ui.busy, onClick = { confirmCashOut = viewModel.tappedCard() }) { Text("Cash out") }
                }
            }
            authError?.let { Text(it, color = ErrorRed) }
            if (ui.phases.isNotEmpty()) Column { for ((p, t) in ui.phases) Text("${fmt.format(Date(t))}  ${p.name.lowercase()}", color = if (p == Phase.FAILED) ErrorRed else NeonCyan, style = MaterialTheme.typography.bodySmall) }
            when (val o = ui.outcome) {
                ActionOutcome.Done -> Text("Done", color = SuccessGreen)
                is ActionOutcome.AwaitingTag -> Text("Committed on chain. Tap the card again to write it.", color = WarningOrange)
                is ActionOutcome.StillConfirming -> Text("Still confirming ${o.txHash.take(10)}…; tap the card again later.", color = WarningOrange)
                is ActionOutcome.Failed -> Text(o.reason, color = ErrorRed)
                null -> {}
            }
            if (ui.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }

    confirmVoid?.let { card ->
        LaunchedEffect(card.reserveId) { preview = viewModel.preview(card) }
        AlertDialog(onDismissRequest = { confirmVoid = null }, title = { Text("Void ${card.label ?: card.uid}?") },
            text = { Column { Text("Forfeits ${PrepaidAmount.formatUnits(card.balanceUnits.toLong())} to the merchant.", color = ErrorRed)
                preview?.takeIf { it.unsettledTaps > 0 }?.let { Text("${it.unsettledTaps} unsettled terminal taps will be swept too.", color = WarningOrange) } } },
            confirmButton = { TextButton(onClick = { confirmVoid = null; gated("Void card") { viewModel.voidCard(card) } }) { Text("Void", color = ErrorRed) } },
            dismissButton = { TextButton(onClick = { confirmVoid = null }) { Text("Cancel") } })
    }
    confirmCashOut?.let { card ->
        LaunchedEffect(card.reserveId) { preview = viewModel.preview(card) }
        val pv = preview
        AlertDialog(onDismissRequest = { confirmCashOut = null }, title = { Text("Cash out ${card.label ?: card.uid}") },
            text = { Column {
                Text("Pays ${PrepaidAmount.formatUnits(card.balanceUnits.toLong())} to the customer's address; the 61 CKB floor returns to the merchant.")
                if (pv?.belowMinimum == true) Text("Below the 61 CKB minimum cell: pay this out in cash and void the card instead.", color = ErrorRed)
                if ((pv?.unsettledTaps ?: 0) > 0) Text("${pv!!.unsettledTaps} unsettled taps: sync the terminal first.", color = ErrorRed)
                Text(scannedAddress?.let { "To: $it" } ?: "Scan the customer's address", color = TextSecondary)
                TextButton(onClick = onScanAddress) { Text("Scan address") } } },
            confirmButton = { TextButton(enabled = scannedAddress != null && pv?.belowMinimum == false && (pv.unsettledTaps == 0L),
                onClick = { confirmCashOut = null; gated("Cash out") { viewModel.cashOut(scannedAddress!!) } }) { Text("Pay out") } },
            dismissButton = { TextButton(onClick = { confirmCashOut = null }) { Text("Cancel") } })
    }
}

private fun headline(t: TapClass?) = when (t) {
    is TapClass.Blank -> "Blank card"; is TapClass.Resume -> "Pending credit"; is TapClass.Damaged -> "Damaged card"
    is TapClass.Known -> t.card.label ?: t.card.uid; is TapClass.Bench -> "Terminal-formatted card"; is TapClass.Refused -> "Refused"; null -> ""
}
private fun detail(t: TapClass?) = when (t) {
    is TapClass.Blank -> "UID ${t.uidHex}. Issue it with an opening balance."
    is TapClass.Resume -> "${PrepaidAmount.formatUnits((t.card.pendingUnits ?: 0).toLong())} committed on chain, not yet on the card."
    is TapClass.Damaged -> "${t.reason}. Index balance ${PrepaidAmount.formatUnits(t.card.balanceUnits.toLong())}."
    is TapClass.Known -> "Card ${PrepaidAmount.formatUnits(t.record.balanceUnits.toLong())} (counter ${t.record.counter}) · index ${PrepaidAmount.formatUnits(t.card.balanceUnits.toLong())} (counter ${t.card.counter}) · ${t.card.state}"
    is TapClass.Bench -> "Terminal ${t.record.writerId} wrote ${PrepaidAmount.formatUnits(t.record.balanceUnits.toLong())}. Adopting funds a reserve for it."
    is TapClass.Refused -> t.reason
    null -> null
}
```
`AppNavigation`: add
```kotlin
            composable(Screen.Prepaid.route) { backStackEntry ->
                val scanned = backStackEntry.savedStateHandle.get<String>("scannedAddress")
                PrepaidScreen(onBack = { navController.popBackStack() }, onCard = { navController.navigate("prepaid-card/$it") },
                    onAudit = { navController.navigate(Screen.PrepaidAudit.route) }, onScanAddress = { navController.navigate(Screen.QrScanner.route) }, scannedAddress = scanned)
            }
```
(the QR scanner already writes `scannedAddress` into the previous back-stack entry's saved state).

- [ ] **Step 4: Run to verify pass** — unit test PASS; `./gradlew :app:assembleDebug -q` → OK.

- [ ] **Step 5: Commit** — `git add -A app/src/main app/src/test && git commit -m "feat(prepaid): cards page, NFC reader mode, tap sheet with issue/load/void/cash-out" -m "Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"`

---

### Task 15: Card detail (inspect) and audit log with CSV export

**Files:**
- Create: `app/src/main/java/com/wyltek/wallet/prepaid/ui/PrepaidCardDetailScreen.kt`, `app/src/main/java/com/wyltek/wallet/prepaid/ui/PrepaidAuditScreen.kt`, `app/src/main/java/com/wyltek/wallet/prepaid/ui/AuditCsv.kt`
- Modify: `app/src/main/java/com/wyltek/wallet/prepaid/ui/PrepaidViewModel.kt`, `app/src/main/java/com/wyltek/wallet/ui/navigation/AppNavigation.kt`
- Test: `app/src/test/java/com/wyltek/wallet/prepaid/AuditCsvTest.kt`

**Interfaces:**
- Produces: `object AuditCsv { val HEADER: String; fun render(events: List<PrepaidEventEntity>): String }`; `PrepaidViewModel.eventsFlow(): Flow<List<PrepaidEventEntity>>`, `suspend fun liveCell(card): Utxo?`, `suspend fun eventsFor(reserveId)`; `@Composable PrepaidCardDetailScreen(reserveId, onBack, viewModel)`, `@Composable PrepaidAuditScreen(onBack, viewModel)`.

- [ ] **Step 1: Write the failing test**

```kotlin
class AuditCsvTest {
    @Test fun `csv has the spec columns in order and escapes quotes`() {
        val e = PrepaidEventEntity(id = 7, atMs = 1_700_000_000_000, actionId = "a", kind = KIND_CASH_OUT, phase = PHASE_DONE, reserveId = "r", uid = "u",
            amountUnits = -100, balanceBefore = 100, balanceAfter = 0, counterBefore = 2, counterAfter = 2, txHash = "0xab", detail = "to \"ckt1x\"; tag wiped")
        val out = AuditCsv.render(listOf(e))
        assertEquals("id,at_ms,action_id,kind,phase,reserve_id,uid,amount_units,balance_before,balance_after,counter_before,counter_after,tx_hash,detail", out.lines()[0])
        assertEquals("""7,1700000000000,a,cash_out,done,r,u,-100,100,0,2,2,0xab,"to ""ckt1x""; tag wiped"""", out.lines()[1])
        assertEquals(2, out.trimEnd().lines().size)
    }
    @Test fun `null fields are empty`() {
        val e = PrepaidEventEntity(atMs = 1, actionId = "a", kind = KIND_ISSUE, phase = PHASE_FAILED, reserveId = null, uid = "u", amountUnits = 0,
            balanceBefore = 0, balanceAfter = 0, counterBefore = 0, counterAfter = 0, txHash = null, detail = "")
        assertEquals("0,1,a,issue,failed,,u,0,0,0,0,0,,", AuditCsv.render(listOf(e)).lines()[1])
    }
}
```

- [ ] **Step 2: Run to verify failure** — compile error.

- [ ] **Step 3: Implement**

`AuditCsv.kt`:
```kotlin
package com.wyltek.wallet.prepaid.ui

import com.wyltek.wallet.prepaid.db.PrepaidEventEntity

object AuditCsv {
    const val HEADER = "id,at_ms,action_id,kind,phase,reserve_id,uid,amount_units,balance_before,balance_after,counter_before,counter_after,tx_hash,detail"
    private fun q(s: String?): String = when {
        s == null -> ""
        s.any { it == ',' || it == '"' || it == '\n' } -> "\"" + s.replace("\"", "\"\"") + "\""
        else -> s
    }
    fun render(events: List<PrepaidEventEntity>): String = buildString {
        appendLine(HEADER)
        for (e in events) appendLine(listOf(e.id.toString(), e.atMs.toString(), e.actionId, e.kind, e.phase, e.reserveId, e.uid, e.amountUnits.toString(),
            e.balanceBefore.toString(), e.balanceAfter.toString(), e.counterBefore.toString(), e.counterAfter.toString(), e.txHash, e.detail).joinToString(",") { q(it) })
    }
}
```
`PrepaidViewModel` additions:
```kotlin
    fun eventsFlow(): Flow<List<PrepaidEventEntity>> = gateway.prepaidStore().eventsFlow()
    suspend fun eventsFor(reserveId: String) = gateway.prepaidStore().eventsForCard(reserveId)
    suspend fun card(reserveId: String) = gateway.prepaidStore().card(reserveId)
    suspend fun liveCell(card: PrepaidCardEntity): Utxo? = gateway.prepaidLiveCell(card.currentOutpoint)
    suspend fun allEventsCsv(): String = AuditCsv.render(gateway.prepaidStore().allEvents())
    fun tagRecordFor(card: PrepaidCardEntity): TapClass.Known? = (ui.value.tap as? TapClass.Known)?.takeIf { it.card.reserveId == card.reserveId }
```
`AgentGateway`: `suspend fun prepaidLiveCell(outpoint: String): Utxo? = (prepaid?.let { RpcChainPort(repository.getChainManager()).also { c -> c.cachedLock = it.lock } })?.cellAt(outpoint)`.

`PrepaidCardDetailScreen.kt`:
```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrepaidCardDetailScreen(reserveId: String, onBack: () -> Unit, viewModel: PrepaidViewModel = viewModel()) {
    val ui by viewModel.ui.collectAsState()
    val card = ui.cards.firstOrNull { it.reserveId == reserveId }
    var live by remember { mutableStateOf<Utxo?>(null) }; var liveErr by remember { mutableStateOf<String?>(null) }
    var events by remember { mutableStateOf<List<PrepaidEventEntity>>(emptyList()) }
    var confirmVoid by remember { mutableStateOf(false) }
    val biometric = rememberBiometricAuth()
    LaunchedEffect(card?.currentOutpoint) { card?.let { live = try { viewModel.liveCell(it) } catch (e: Exception) { liveErr = e.message; null } } }
    LaunchedEffect(reserveId, ui.cards) { events = viewModel.eventsFor(reserveId) }
    Scaffold(topBar = { TopAppBar(title = { Row { Text(card?.label ?: card?.uid ?: "Card"); Spacer(Modifier.width(8.dp)); DemoBadge() } },
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } }) }) { pad ->
        if (card == null) { Text("Card not found", Modifier.padding(pad).padding(16.dp)); return@Scaffold }
        val tagRec = viewModel.tagRecordFor(card)?.record
        val expected = 6_100_000_000uL + card.balanceUnits.toULong() * 100_000uL
        LazyColumn(Modifier.padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) { Text("Tag", color = NeonCyan); Text(tagRec?.let { "${PrepaidAmount.formatUnits(it.balanceUnits.toLong())}\ncounter ${it.counter}" } ?: "tap to read", color = TextSecondary) }
                Column(Modifier.weight(1f)) { Text("Index", color = NeonCyan); Text("${PrepaidAmount.formatUnits(card.balanceUnits.toLong())}\ncounter ${card.counter}\nsettled ${card.settledCounter}\n${card.state}", color = TextSecondary) }
                Column(Modifier.weight(1f)) { Text("Live cell", color = NeonCyan)
                    Text(live?.let { "${it.capacity / 100_000_000uL} CKB\n${card.currentOutpoint.take(12)}…" } ?: (liveErr ?: "not found on chain"), color = if (live != null && live!!.capacity == expected) TextSecondary else ErrorRed) }
            } }
            if (tagRec != null && tagRec.counter.toLong() != card.counter) item { Text("Tag counter ${tagRec.counter} vs index ${card.counter}: ${tagRec.counter.toLong() - card.counter} unsettled taps", color = WarningOrange) }
            if (live != null && live!!.capacity != expected) item { Text("Live capacity does not match the index balance (expected ${expected / 100_000_000uL} CKB)", color = ErrorRed) }
            card.pendingUnits?.let { item { Text("Pending credit ${PrepaidAmount.formatUnits(it.toLong())} (${if (card.pendingCommitted) "committed, tap the card to write it" else "confirming"})", color = WarningOrange) } }
            item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (card.state == CARD_ACTIVE) OutlinedButton(onClick = { viewModel.revoke(card) }) { Text("Revoke") }
                if (card.state == CARD_REVOKED) OutlinedButton(onClick = { viewModel.reinstate(card) }) { Text("Reinstate") }
                if (card.state == CARD_ACTIVE || card.state == CARD_REVOKED) OutlinedButton(colors = ButtonDefaults.outlinedButtonColors(contentColor = ErrorRed), onClick = { confirmVoid = true }) { Text("Void") }
            } }
            item { Text("Load, cash-out and repair need the card: tap it on the Cards page.", color = TextSecondary, style = MaterialTheme.typography.bodySmall) }
            item { Text("Events", color = NeonCyan) }
            items(events, key = { it.id }) { e -> EventRow(e) }
        }
    }
    if (confirmVoid && card != null) AlertDialog(onDismissRequest = { confirmVoid = false }, title = { Text("Void ${card.label ?: card.uid}?") },
        text = { Text("Forfeits ${PrepaidAmount.formatUnits(card.balanceUnits.toLong())} to the merchant. The tag is not needed.", color = ErrorRed) },
        confirmButton = { TextButton(onClick = { confirmVoid = false; biometric.authenticate("Void card", "Confirm") { r -> if (r is BiometricResult.Success || r is BiometricResult.Unavailable) viewModel.voidCard(card) } }) { Text("Void", color = ErrorRed) } },
        dismissButton = { TextButton(onClick = { confirmVoid = false }) { Text("Cancel") } })
}

@Composable
fun EventRow(e: PrepaidEventEntity) {
    val fmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }
    ListItem(headlineContent = { Text("${e.kind} · ${e.phase}", color = if (e.phase == PHASE_FAILED) ErrorRed else TextPrimary) },
        supportingContent = { Text("${fmt.format(Date(e.atMs))} · ${PrepaidAmount.formatUnits(e.amountUnits)} · ${e.balanceBefore}→${e.balanceAfter} · ctr ${e.counterBefore}→${e.counterAfter}" +
            (e.txHash?.let { "\n$it" } ?: "") + (e.detail.takeIf { it.isNotBlank() }?.let { "\n$it" } ?: ""), color = TextSecondary, style = MaterialTheme.typography.bodySmall) },
        colors = ListItemDefaults.colors(containerColor = DarkSurfaceVariant))
}
```
`PrepaidAuditScreen.kt`:
```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrepaidAuditScreen(onBack: () -> Unit, viewModel: PrepaidViewModel = viewModel()) {
    val events by viewModel.eventsFlow().collectAsState(initial = emptyList())
    var kindFilter by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val shown = events.filter { kindFilter == null || it.kind == kindFilter }
    val grouped = shown.groupBy { it.actionId }.values.toList()
    Scaffold(topBar = { TopAppBar(title = { Row { Text("Audit log"); Spacer(Modifier.width(8.dp)); DemoBadge() } },
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } },
        actions = { IconButton(onClick = { scope.launch {
            val csv = viewModel.allEventsCsv()
            val send = Intent(Intent.ACTION_SEND).apply { type = "text/csv"; putExtra(Intent.EXTRA_SUBJECT, "blackbox-prepaid-audit.csv"); putExtra(Intent.EXTRA_TEXT, csv) }
            context.startActivity(Intent.createChooser(send, "Export audit CSV")) } }) { Icon(Icons.Default.Share, "Export CSV") } }) }) { pad ->
        Column(Modifier.padding(pad).padding(16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = kindFilter == null, onClick = { kindFilter = null }, label = { Text("all") })
                for (k in listOf(KIND_ISSUE, KIND_LOAD, KIND_VOID, KIND_CASH_OUT, KIND_SETTLE)) FilterChip(selected = kindFilter == k, onClick = { kindFilter = k }, label = { Text(k) })
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(grouped, key = { it.first().actionId }) { rows ->
                    Card(colors = CardDefaults.cardColors(containerColor = DarkSurface)) { Column(Modifier.padding(8.dp)) {
                        Text("${rows.first().kind} · ${rows.first().uid}", color = NeonCyan)
                        for (e in rows.sortedBy { it.id }) EventRow(e)
                    } }
                }
            }
        }
    }
}
```
`AppNavigation`: register `Screen.PrepaidCard` (`backStackEntry.arguments?.getString("reserveId")`) and `Screen.PrepaidAudit`.

- [ ] **Step 4: Run to verify pass** — unit tests PASS; `./gradlew :app:assembleDebug -q` OK.

- [ ] **Step 5: Commit** — `git add -A app/src/main app/src/test && git commit -m "feat(prepaid): card inspect screen and append-only audit log with CSV export" -m "Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"`

---

### Task 16: Device checkpoint (C1 + Vault half of C4), spec update, Kanban, memory

**Files:**
- Modify: `~/blackbox-pos/docs/superpowers/specs/2026-10-13-prepaid-demo-d3-vault-design.md` (§6 wire format, §6.3 netting note, `over_balance` and `retry` verdicts), `~/Documents/loacal-vault/Kanban/Blackbox.md`, `~/.claude/projects/-home-phill-blackbox-pos/memory/blackbox-prepaid-card-track.md`
- Create: `~/wyltek-wallet/docs/superpowers/PREPAID-D3-CHECKPOINT.md`

Needs Phill at the phone with NTAG tags and the testnet account funded (≥ 500 CKB for two issues, a load, a void and a cash-out). Sandbox off for `adb`.

- [ ] **Step 1: Build, install, run the instrumented DAO test**

```bash
cd ~/wyltek-wallet && ./gradlew :app:assembleDebug -q && adb install -r app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:connectedDebugAndroidTest --tests '*PrepaidDaoTest*' 2>&1 | tail -5
```
Expected: 3 passed (if Task 4 Step 5 was deferred, it runs here).

- [ ] **Step 2: C1 — Issue and Load on testnet**

On the phone: Settings → Prepaid Cards (DEMO). Hold a blank NTAG → "Blank card" sheet → amount `100`, label `Bench A` → Issue → biometric → stepper shows requested/broadcast/committed/written/done. Keep the card on the phone until "Done". Then tap again → "Bench A", amount `50` → Load → Done. Record both tx hashes (Card detail → Events) and check on `https://pudge.explorer.nervos.org/transaction/<hash>`: reserve cell = 161 CKB then 211 CKB under the merchant lock. Card detail: Tag 150.000 CKB counter 1, Index 150.000 counter 1, Live cell 211 CKB, no red.

- [ ] **Step 3: Torn write** — issue a second tag with `20` and pull it the moment "committed" shows. Expect "Committed on chain. Tap the card again to write it." and the card listed with `pending 20.000 CKB`. Tap again → Done. Events for that action show `failed` ("write interrupted") between `committed` and `written`.

- [ ] **Step 4: C4 (Vault half) — Cash-out and Void** — tap Bench A → Cash out → scan a second wallet's testnet address → Pay out → Done; explorer shows 150 CKB to that address and the floor minus fee back to the merchant; card state closed; tag reads as Blank on the next tap. Then open the second card's detail → Void → confirm → Done; explorer shows one input (the 81 CKB reserve) plus a fee input and one merchant output.

- [ ] **Step 5: Refusals** — tap a MIFARE Classic and a non-prepaid NTAG: sheet says "not an NTAG21x card" and "Blank card" respectively; nothing is added to the audit log for the Classic. Export CSV from the audit screen and open it in a spreadsheet: one row per phase, columns as `AuditCsv.HEADER`.

- [ ] **Step 6: Settlement endpoint smoke** — with the gateway server running and a token minted with `prepaid_settle` for the funding account, from a laptop on the LAN:

```bash
# token from the Agent screen's "Minted Token" card; host:port from the Server card
curl -sk -H "x-device-token: $TOKEN" https://$HOST:$PORT/v1/prepaid/cards
curl -sk -H "x-device-token: $TOKEN" -H 'content-type: application/json' -d '{"entries_hex":"00"}' https://$HOST:$PORT/v1/prepaid/journal
```
Expected: a JSON `cards` list with the issued cards (active/revoked only), then `400` for the 1-byte body. Then a 96-byte zero entry (`"00"` × 96) → `200` with `[{"index":0,"verdict":"bad_mac"}]`.

- [ ] **Step 7: Write the checkpoint report** `docs/superpowers/PREPAID-D3-CHECKPOINT.md`: date, phone model and Android version, tag types, every tx hash with explorer links, timings (tap → Done), what refused and why, and anything that deviated from the spec. Commit.

- [ ] **Step 8: Spec and vault updates** — in the D3 spec: §6 wire format = JSON `{"entries_hex"}` + `x-device-token` header; verdicts gain `over_balance` and `retry`; settlement successor rule for negative net (Global Constraints above); `reserve_id` test vector `d0a4145c…14d6`. Kanban: move the D3 card to Done with the commit range and checkpoint link; add D4 (end-to-end rehearsal, needs D2) to Backlog if absent. Memory: append a D3 status line to `blackbox-prepaid-card-track.md`. Commit the POS repo docs with `docs(prepaid): D3 spec wire-format and verdict updates`.

- [ ] **Step 9: Whole-branch review** — run the `cpp-reviewer`-equivalent for this stack: `code-reviewer` agent over `git diff main...feat/prepaid-demo-d3` with the D3 spec; fix Critical/High before reporting. Do not push; Phill decides when.

---

## Self-review notes (done while writing)

- Spec coverage: §3 → Tasks 4–5; §4 → Tasks 1–3; §5.1 → 6, 10; §5.2 → 10; §5.3–5.5 → 11; §5.6 → 10 (`recoverPending`, called from the view model's `init`); §6 → 12–13; §7 → 14–15; §8 → spread over 10–12 (each row has a test); §9 → tests per task plus Task 16 for the device rows; §10 criteria 1–3 → Task 16, 4 → Task 12, 5 → by construction (no POS files touched).
- Type names used consistently: `PrepaidCardEntity`, `PrepaidEventEntity`, `TapClass`, `ActionOutcome`, `Phase`, `PlannedTx`, `CellDepRef`, `SlotResult`, `CardRecordInfo`, `ReservePlan`, `CardStateFfi`, `BatchResultFfi`, `JournalVerdict`, `SettleResult`, `PrepaidJournalResult`, `PrepaidCardsResult`.
- Known seam: `RpcChainPort.cellAt` scans by the merchant lock; the indexer returns only live cells, so a spent reserve reads as `null`, which is the contract the service relies on.
