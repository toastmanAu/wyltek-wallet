# Staff key PIN, V1.1 (Vault + rust-core) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Every staff key gets a PIN set in the Vault; terminals receive a keyed check value and a version per key, and a terminal's lockout report (journal kind `0x05`) lands in the Vault audit log and on the Staff keys screen.

**Architecture:**
- **rust-core** owns the derivation (`prepaid::staff`: `k_staff`, `pin_check`), journal kind `0x05` in the codec and verifier, and emits golden vectors the POS pins in V2. The Vault reaches the derivation through one new uniffi function; the Vault never stores `K_staff` or the PIN.
- **Kotlin** adds three columns to `prepaid_staff_keys` (DB v6 → v7), PIN entry at enrol and a Set PIN action in `StaffKeyService`, two audit kinds, lockout settlement in `VoucherService` (same shape as price overrides), the new `/staff` wire shape, and the list states on `StaffKeysScreen`.
- The POS side (§5.5) is V2 and is out of scope here.

**Tech Stack:** Rust (rust-core, uniffi 0.31.1 proc-macros, aes/cmac, blake2b-ref, hex, sha2), Kotlin + Jetpack Compose + Room 2 + SQLCipher, JUnit4 + kotlinx-coroutines-test.

**Spec:** `~/blackbox-pos/docs/superpowers/specs/2026-10-15-blackbox-prepaid-vouchers-staff-keys-design.md` (commit 1fbac9f; §5.1, §5.2, §5.4, §7 column `0x05`, §8.3 kind `0x05`, §9, §12 "V1.1"). V1 as built: `docs/superpowers/plans/2026-10-15-prepaid-vouchers-v1-vault.md` and `docs/superpowers/PREPAID-VOUCHERS-V1-CHECKPOINT.md`.

## Global Constraints

**Phone, branch and build safety**
- Work on branch `feat/staff-pin-v1_1` in a worktree of `~/wyltek-wallet`, from `main` @ `0955126`. Never push.
- **NEVER run `connectedAndroidTest` / `connectedDebugAndroidTest`.** AGP uninstalls the app afterwards and wipes Phill's wallet. Install only with `adb -s R5CTC07MPYD install -r`.
- The uniffi bindings live ONLY in `wallet-core/src/main/java/com/wyltek/wallet/core/native/wyltekwalletcore.kt`. `BindingsSingleSourceTest` fails if a copy appears under `app/`. The `.so` goes into both `jniLibs/arm64-v8a/` directories and is gitignored.
- `rust-core/tests/fixtures/prepaid_demo_vectors.txt` (SHA-256 `90937c92e909acf82a1b06c3f79e18840fd6e6d3c6d7bb6b9f27704fa98e60d8`) and `rust-core/tests/fixtures/prepaid_voucher_vectors.txt` (SHA-256 `2ec9f2d0d5495ee6abcbf335711a11199f87d0e216d29422cef377bd48d12a32`) must stay byte-identical. Staff PIN vectors get their own fixture and pin.
- Commit messages: conventional (`feat:`, `fix:`, `test:`, `docs:`), no attribution trailer.

**PIN rules (spec §5.4)**
- A PIN is 4 to 8 ASCII digits `0`–`9`. Leading zeros are part of the PIN. Nothing else is a PIN: not a Unicode digit, not whitespace, not a letter.
- The Vault never stores the PIN. It stores `pin_check` and `pin_ver`. **Nothing about the PIN ever goes into an audit row, a log line, a crash report or an exception message.**
- `pin_ver` starts at 1 at the first set and goes up by one on every later set, including a re-enrol after revoke (a revoked key keeps its PIN columns). It is a u16 on the wire: a set that would make it 65536 is refused.

**Derivation (spec §5.4, verbatim)**
```
K_staff   = aes_cmac(K_debit, "BBSTAFF1" ‖ 0x00 × 8)                                   # full 16 bytes, 16-byte message
pin_check = aes_cmac(K_staff, key_id(8) ‖ pin_ver u16 LE(2) ‖ pin ASCII digits(4..8))   # full 16 bytes, no truncation
```

**Journal kind `0x05` staff lockout (spec §7)**
- 96 bytes, version `0x02`, `tmv` = CMAC8 over the entry with `tmv` zeroed, as for every kind.
- `uid` = staff tag UID; `reserve_id` = 32 zero bytes; `tmc` = 0; `amount_units` = wrong attempts (3); `balance_after` = 0; `sale_ref` = 0 when no sale was open; `pad` = `key_id(8)` ‖ `pin_ver` u16 LE(2) ‖ `0(2)`.
- Verifier (spec §8.3): MAC and merchant checked (any other merchant answers `bad_mac`), then `accepted`. Deduplicated by entry hash in `prepaid_journal_seen`; a resend answers `duplicate` with no second audit row. An unknown `key_id` is still `accepted` and audited. No chain action.

**Wire (spec §5.2)**
- `GET /v1/prepaid/staff` → `{"staff":[{"key_id":"<16 hex>","pin_ver":<int>,"pin_check":"<32 hex>"}]}`, active keys that have a PIN only, at most 16. A key without a PIN is not served and does not use one of the 16 slots. The V1 shape (`key_id` only) is retired.

**Storage (spec §5.4, verbatim SQL)**
```
ALTER TABLE `prepaid_staff_keys` ADD COLUMN `pinVer` INTEGER NOT NULL DEFAULT 0
ALTER TABLE `prepaid_staff_keys` ADD COLUMN `pinCheckHex` TEXT
ALTER TABLE `prepaid_staff_keys` ADD COLUMN `pinSetAt` INTEGER
```
Agent DB version 7. `MIGRATION_6_7_SQL` lists the three statements; `MigrationSqlMatchesRoomTest` holds them against Room's generated schema.

**Audit kinds**
- `staff_pin_set`: `reserveId` = `key_id`, `detail` = `"pin_ver <n>"`.
- `staff_lockout`: `reserveId` = `key_id`, `uid` = staff tag UID, `amountUnits` = attempts, `balanceAfter` = terminal id, `counterAfter` = `pin_ver`.

## Review Focus

1. A PIN with leading zeros (`0042`) must be kept as typed, never parsed as a number: Task 1 pins `0042` ≠ `1042` in rust; Task 6 pins the Kotlin service passes the string through unchanged.
2. A PIN typed with a non-ASCII digit (`١٢٣٤`, which Kotlin's `Char.isDigit()` accepts) or whitespace must be refused: Task 6's `a_bad_pin_is_refused_before_the_tag_is_read`.
3. A lockout reported for a `pin_ver` the Vault has since replaced must not show the key as locked: Task 9's `StaffKeyStatusTest`.
4. A key without a PIN must not consume one of the 16 wire slots: Task 8's `staff_omits_a_key_without_a_pin_and_a_pinless_key_does_not_use_a_slot`.
5. A `pin_ver` that would exceed the u16 wire field must be refused rather than wrapped: Task 6's `pin_ver_at_65535_refuses_set_pin`; Task 4's `NativeProtocol` range check.

---

### Task 1: rust-core `prepaid::staff`: `k_staff`, `pin_check`, PIN rules

**Files:**
- Create: `rust-core/src/prepaid/staff.rs`
- Modify: `rust-core/src/prepaid/mod.rs` (add `pub mod staff;`)

**Interfaces:**
- Consumes: `prepaid::cmac::{aes_cmac, mac_truncate}`.
- Produces:
  - `pub const PIN_MIN: usize = 4; pub const PIN_MAX: usize = 8;`
  - `pub const K_STAFF_MSG: [u8; 16] = *b"BBSTAFF1\0\0\0\0\0\0\0\0";`
  - `pub fn pin_ok(pin: &[u8]) -> bool` — length in `PIN_MIN..=PIN_MAX` and every byte `b'0'..=b'9'`.
  - `pub fn k_staff(k_debit: &[u8; 16]) -> [u8; 16]`
  - `pub fn pin_check(k_staff: &[u8; 16], key_id: &[u8; 8], pin_ver: u16, pin: &[u8]) -> Option<[u8; 16]>` — `None` unless `pin_ok(pin)`.

- [ ] **Step 1: Write the failing tests** in `staff.rs` (`#[cfg(test)] mod tests`)

```rust
fn kd() -> [u8; 16] { core::array::from_fn(|i| 0x10 + i as u8) }
fn kid() -> [u8; 8] { [0xC0, 0xFF, 0xEE, 0x01, 0x02, 0x03, 0x04, 0x05] }

#[test] fn k_staff_is_the_full_cmac_of_the_label_under_k_debit() {
    assert_eq!(k_staff(&kd()), aes_cmac(&kd(), &K_STAFF_MSG));
    assert_eq!(&K_STAFF_MSG[..8], b"BBSTAFF1"); assert_eq!(K_STAFF_MSG[8..], [0u8; 8]);
    let mut other = kd(); other[15] ^= 1; assert_ne!(k_staff(&other), k_staff(&kd()));
}
#[test] fn pin_check_message_is_key_id_then_pin_ver_le_then_the_digits() {
    let ks = k_staff(&kd());
    let mut msg = Vec::new(); msg.extend_from_slice(&kid()); msg.extend_from_slice(&0x0102u16.to_le_bytes()); msg.extend_from_slice(b"1234");
    assert_eq!(pin_check(&ks, &kid(), 0x0102, b"1234"), Some(aes_cmac(&ks, &msg)));
    assert_eq!(msg.len(), 14);
}
#[test] fn pin_check_is_the_full_16_bytes_whose_prefix_is_cmac8() {
    let ks = k_staff(&kd()); let pc = pin_check(&ks, &kid(), 1, b"1234").unwrap();
    assert_eq!(&mac_truncate(&pc)[..], &pc[..8]); assert_ne!(&pc[8..], &[0u8; 8][..]);
}
#[test] fn the_same_pin_at_pin_ver_1_and_2_differs() {
    let ks = k_staff(&kd());
    assert_ne!(pin_check(&ks, &kid(), 1, b"1234"), pin_check(&ks, &kid(), 2, b"1234"));
}
#[test] fn leading_zeros_are_part_of_the_pin() {
    let ks = k_staff(&kd());
    assert_ne!(pin_check(&ks, &kid(), 1, b"0042"), pin_check(&ks, &kid(), 1, b"1042"));
    assert!(pin_check(&ks, &kid(), 1, b"00000000").is_some());
}
#[test] fn pin_length_and_charset_are_enforced() {
    let ks = k_staff(&kd());
    for bad in [&b"123"[..], b"123456789", b"12a4", b"12 4", "１２３４".as_bytes(), b""] { assert!(pin_check(&ks, &kid(), 1, bad).is_none(), "{bad:?}"); assert!(!pin_ok(bad)); }
    for good in [&b"1234"[..], b"12345678", b"0000"] { assert!(pin_ok(good)); assert!(pin_check(&ks, &kid(), 1, good).is_some()); }
}
#[test] fn k_staff_is_not_a_record_or_journal_mac_key() {
    // domain separation: a pin_check never equals a CMAC under K_debit over the same message
    let ks = k_staff(&kd()); let mut msg = Vec::new(); msg.extend_from_slice(&kid()); msg.extend_from_slice(&1u16.to_le_bytes()); msg.extend_from_slice(b"1234");
    assert_ne!(pin_check(&ks, &kid(), 1, b"1234").unwrap(), aes_cmac(&kd(), &msg));
}
```

- [ ] **Step 2: Run to verify they fail**

Run: `cd ~/wyltek-wallet/rust-core && cargo test --lib prepaid::staff 2>&1 | tail -3`
Expected: compile error, `staff` module / functions not found.

- [ ] **Step 3: Implement `staff.rs`** with the constants and the three functions above. `pin_check` builds the message as `key_id ‖ pin_ver.to_le_bytes() ‖ pin` and returns `Some(aes_cmac(k_staff, &msg))`. Doc-comment the module with the spec §5.4 derivation block and the sentence "The Vault and the POS derive `K_staff` from `K_debit` and never store it".

- [ ] **Step 4: Run to verify they pass**

Run: `cd ~/wyltek-wallet/rust-core && cargo test --lib prepaid::staff 2>&1 | tail -3`
Expected: `test result: ok. 7 passed`.

- [ ] **Step 5: Commit**

```bash
git add rust-core/src/prepaid/staff.rs rust-core/src/prepaid/mod.rs
git commit -m "feat(prepaid): staff PIN derivation (K_staff, pin_check) with PIN rules"
```

---

### Task 2: Journal kind `0x05` in the codec and the verifier

**Files:**
- Modify: `rust-core/src/prepaid/journal.rs` (constants at lines 8–11, `is_known_kind` line 21, `shape_ok`, tests at lines ~176–184 and ~234–242)
- Modify: `rust-core/src/prepaid/verifier.rs` (imports line 6, `BatchResult` line 29, merchant check line 60, `match` arms from line 64)

**Interfaces:**
- Produces:
  - `journal::KIND_STAFF_LOCKOUT: u8 = 0x05`; `is_known_kind` covers `KIND_DEBIT..=KIND_STAFF_LOCKOUT`.
  - `verifier::LockoutOut { pub index: u32, pub terminal_id: u16, pub uid: [u8; 7], pub key_id: [u8; 8], pub pin_ver: u16, pub attempts: i32, pub entry_hash: [u8; 32] }`
  - `BatchResult` gains `pub lockouts: Vec<LockoutOut>`.
- Shape rule for `0x05` in `shape_ok`: `reserve_id == [0; 32]`, `tmc == 0`, `balance_after == 0`, `amount_units > 0`, `key_id_set`, `pad[10..12] == [0, 0]`. `pin_ver` (pad 8..10) is unconstrained: the terminal is reporting what it holds.

- [ ] **Step 1: Write the failing tests**

In `journal.rs` tests, add:

```rust
fn lockout() -> JournalEntry {
    let mut pad = [0u8; 12]; pad[..8].copy_from_slice(&[0xC0, 0xFF, 0xEE, 1, 2, 3, 4, 5]); pad[8..10].copy_from_slice(&7u16.to_le_bytes());
    JournalEntry { version: VERSION_DEMO, kind: KIND_STAFF_LOCKOUT, terminal_id: 3, merchant_id: [0x11; 8], uid: [4, 9, 9, 9, 9, 9, 9],
        reserve_id: [0; 32], tmc: 0, amount_units: 3, balance_after: 0, ts: 5, tmv: [0; 8], sale_ref: [0; 8], pad }
}
#[test] fn lockout_is_a_known_kind_that_round_trips_with_pin_ver_in_the_pad() {
    assert!(is_known_kind(KIND_STAFF_LOCKOUT)); assert!(!is_known_kind(0x06));
    let mut e = lockout(); let raw = attest(&key(), &mut e);
    assert!(verify(&key(), &raw)); let d = decode(&raw).unwrap();
    assert_eq!(d, e); assert_eq!(u16::from_le_bytes([d.pad[8], d.pad[9]]), 7); assert_eq!(raw[1], 0x05);
}
#[test] fn lockout_shape_rules() {
    let ok = |f: &dyn Fn(&mut JournalEntry)| { let mut e = lockout(); f(&mut e); decode(&encode(&e)).is_some() };
    assert!(ok(&|_| {}));
    assert!(!ok(&|e| e.reserve_id[0] = 1)); assert!(!ok(&|e| e.tmc = 1)); assert!(!ok(&|e| e.balance_after = 1));
    assert!(!ok(&|e| e.amount_units = 0)); assert!(!ok(&|e| e.pad[..8].fill(0))); assert!(!ok(&|e| e.pad[10] = 1)); assert!(!ok(&|e| e.pad[11] = 1));
    assert!(ok(&|e| e.pad[8..10].copy_from_slice(&0u16.to_le_bytes())), "pin_ver is reported, not validated");
    assert!(ok(&|e| e.sale_ref = [1; 8]), "a sale may have been open");
}
```

Change the two existing tests that use `0x05` as the unknown kind (`decode_rejects_bad_version_kind_pad`, `debit_with_nonzero_pad_still_refused_and_unknown_kind_is_mac_checkable`) to use `0x06`.

In `verifier.rs` tests, add (reuse that file's `key()`, `M` and `attest` helpers):

```rust
fn lockout_raw(merchant: [u8; 8], pin_ver: u16) -> Vec<u8> {
    let mut pad = [0u8; 12]; pad[..8].copy_from_slice(&[0xC0, 0xFF, 0xEE, 1, 2, 3, 4, 5]); pad[8..10].copy_from_slice(&pin_ver.to_le_bytes());
    let mut e = JournalEntry { version: 0x02, kind: KIND_STAFF_LOCKOUT, terminal_id: 9, merchant_id: merchant, uid: [4, 1, 2, 3, 4, 5, 6],
        reserve_id: [0; 32], tmc: 0, amount_units: 3, balance_after: 0, ts: 0, tmv: [0; 8], sale_ref: [0; 8], pad };
    attest(&key(), &mut e).to_vec()
}
#[test] fn lockout_is_accepted_and_reported_with_key_id_pin_ver_and_terminal() {
    let raw = lockout_raw(M, 7);
    let r = verify_batch(&key(), &M, &raw, &[], &[]).unwrap();
    assert_eq!(r.verdicts, vec![Verdict::Accepted]); assert!(r.nets.is_empty() && r.redeems.is_empty() && r.overrides.is_empty());
    let l = &r.lockouts[0];
    assert_eq!((l.index, l.terminal_id, l.pin_ver, l.attempts), (0, 9, 7, 3));
    assert_eq!(l.key_id, [0xC0, 0xFF, 0xEE, 1, 2, 3, 4, 5]); assert_eq!(l.uid, [4, 1, 2, 3, 4, 5, 6]);
    assert_eq!(l.entry_hash, ckb_blake2b(&raw));
}
#[test] fn lockout_from_another_merchant_is_bad_mac() {
    assert_eq!(verify_batch(&key(), &M, &lockout_raw([0x22; 8], 1), &[], &[]).unwrap().verdicts, vec![Verdict::BadMac]);
}
#[test] fn two_identical_lockouts_in_one_batch_are_both_accepted_here() {
    // dedupe by entry hash is the Vault's job (prepaid_journal_seen), as for 0x04
    let mut two = lockout_raw(M, 1); two.extend(lockout_raw(M, 1));
    assert_eq!(verify_batch(&key(), &M, &two, &[], &[]).unwrap().verdicts, vec![Verdict::Accepted, Verdict::Accepted]);
}
```

(`ckb_blake2b(&[u8]) -> [u8; 32]` is already imported in that test module.)

- [ ] **Step 2: Run to verify they fail**

Run: `cd ~/wyltek-wallet/rust-core && cargo test --lib prepaid:: 2>&1 | grep -E "error|FAILED|test result" | head`
Expected: compile errors (`KIND_STAFF_LOCKOUT`, `lockouts` unknown).

- [ ] **Step 3: Implement.** In `journal.rs`: the constant, the `is_known_kind` range, the `KIND_STAFF_LOCKOUT` arm of `shape_ok`. In `verifier.rs`: `LockoutOut`, `lockouts` in `BatchResult`, extend the merchant check to `KIND_STAFF_LOCKOUT`, and a `KIND_STAFF_LOCKOUT =>` arm beside the override arm that pushes `LockoutOut { pin_ver: u16::from_le_bytes([e.pad[8], e.pad[9]]), attempts: e.amount_units, .. }` and `Verdict::Accepted`. Update the `verify_batch` doc comment to name `0x05` among the merchant-checked kinds.

- [ ] **Step 4: Run to verify they pass**

Run: `cd ~/wyltek-wallet/rust-core && cargo test --lib prepaid:: 2>&1 | grep -E "FAILED|panicked|test result" | head`
Expected: all `ok`. (`ffi.rs` constructs `BatchResultFfi` from `BatchResult` and does not name `lockouts`, so it still compiles; Task 4 maps the new field.)

- [ ] **Step 5: Commit**

```bash
git add rust-core/src/prepaid/journal.rs rust-core/src/prepaid/verifier.rs
git commit -m "feat(prepaid): journal kind 0x05 staff lockout in the codec and verifier"
```

---

### Task 3: Staff PIN golden vectors and the `lockout` journal-entry tool

**Files:**
- Modify: `rust-core/src/prepaid/vectors.rs` (add `pub fn emit_staff() -> String`)
- Create: `rust-core/examples/emit_prepaid_staff_vectors.rs`
- Create: `rust-core/tests/fixtures/prepaid_staff_pin_vectors.txt`
- Create: `rust-core/tests/prepaid_staff_pin_vectors_pinned.rs`
- Modify: `rust-core/examples/prepaid_journal_entry.rs` (`USAGE`, `build`)

**Interfaces:**
- Consumes: Task 1 `staff::{k_staff, pin_check}`, Task 2 `KIND_STAFF_LOCKOUT`, the existing `emit_voucher` literals (`key`, `uid`, `merchant_id`, `staff.sig` = `[0x5A; 32]`).
- Produces: the fixture, which V2 copies into blackbox-pos. Fixture lines, in this order:

```
# prepaid staff PIN golden vectors v1 (generated by rust-core: cargo run --example emit_prepaid_staff_vectors -- tests/fixtures/prepaid_staff_pin_vectors.txt)
k_debit=<hex16>            # the same key literal emit_voucher uses
k_staff=<hex16>
staff.uid=<hex7>           # same uid as emit_voucher
staff.sig=<hex32>          # 5a × 32
staff.key_id=<hex8>
pin1.pin_ver=1
pin1.pin=1234
pin1.pin_check=<hex16>
pin2.pin_ver=2
pin2.pin=1234
pin2.pin_check=<hex16>
pin3.pin_ver=1
pin3.pin=00000000
pin3.pin_check=<hex16>
entry_lockout.raw=<hex96>  # then kind, terminal_id, merchant_id, uid, reserve_id, tmc, amount_units, balance_after, ts, sale_ref, pad, tmv — same field lines as entry_override
```
  The lockout entry: `terminal_id 3`, `merchant_id` as `emit_voucher`, `uid` the staff uid, `reserve_id` zero, `tmc 0`, `amount_units 3`, `balance_after 0`, `ts 1_791_370_000`, `sale_ref` zero, `pad = key_id ‖ 1u16 LE ‖ 0 0`. The emitter self-checks `verify && decode == entry` as `emit_voucher` does, and asserts `pin1.pin_check != pin2.pin_check`.

- [ ] **Step 1: Write the failing pin test** `rust-core/tests/prepaid_staff_pin_vectors_pinned.rs`, modelled on `prepaid_voucher_vectors_pinned.rs`:

```rust
const FIXTURE: &str = include_str!("fixtures/prepaid_staff_pin_vectors.txt");
const PINNED_SHA256: &str = "<fill after first emit>";
#[test] fn emitter_reproduces_committed_staff_fixture() { assert_eq!(vectors::emit_staff(), FIXTURE); }
#[test] fn staff_fixture_sha256_is_pinned() { /* as the voucher test */ }
#[test] fn fixture_values_recompute_from_the_derivation() {
    // parse k_debit, staff.key_id, pinN.* and check staff::k_staff / staff::pin_check reproduce k_staff and every pin_check;
    // also assert pin1.pin_check != pin2.pin_check and that entry_lockout.raw verifies under k_debit with kind 5
}
#[test] fn k_staff_derived_from_a_constant_key_fails_the_vector() {
    // mutation probe for spec §9: pin_check under aes_cmac(&[0u8;16], &K_STAFF_MSG) must NOT equal pin1.pin_check
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd ~/wyltek-wallet/rust-core && cargo test --test prepaid_staff_pin_vectors_pinned 2>&1 | tail -3`
Expected: compile error (`emit_staff` missing / fixture file missing).

- [ ] **Step 3: Implement `emit_staff`**, the example (copy `emit_prepaid_voucher_vectors.rs`, swap the function), then generate the fixture and pin it:

```bash
cd ~/wyltek-wallet/rust-core && cargo run --example emit_prepaid_staff_vectors -- tests/fixtures/prepaid_staff_pin_vectors.txt && sha256sum tests/fixtures/prepaid_staff_pin_vectors.txt
```
Put the printed hash into `PINNED_SHA256`.

- [ ] **Step 4: Extend `prepaid_journal_entry`** with a third subcommand:

```
prepaid_journal_entry <k_debit16> lockout <terminal_id u16> <key_id8 non-zero> <pin_ver u16> [flags]
```
`amount_units` fixed at 3, `reserve_id`/`tmc`/`balance_after` zero, `pad = key_id ‖ pin_ver LE ‖ 0 0`, flags as the other subcommands. Add to that example's existing tests (if it has a `#[cfg(test)]` block; otherwise add one): `lockout_builds_a_verifying_0x05_entry` (decode the hex, `kind == 5`, `pin_ver` round-trips) and `lockout_refuses_a_zero_key_id`.

- [ ] **Step 5: Run everything**

Run: `cd ~/wyltek-wallet/rust-core && cargo test 2>&1 | grep -E "FAILED|panicked|test result" | head -20`
Expected: every `test result: ok`; the voucher and demo pin tests still pass (their fixtures untouched: `git status` shows neither modified).

- [ ] **Step 6: Commit**

```bash
git add rust-core/src/prepaid/vectors.rs rust-core/examples/emit_prepaid_staff_vectors.rs rust-core/examples/prepaid_journal_entry.rs rust-core/tests/fixtures/prepaid_staff_pin_vectors.txt rust-core/tests/prepaid_staff_pin_vectors_pinned.rs
git commit -m "test(prepaid): staff PIN golden vectors, pinned; lockout subcommand for the journal tool"
```

---

### Task 4: FFI `prepaid_staff_pin_check`, `LockoutFfi`, bindings, Kotlin `Protocol` port and fakes

**Files:**
- Modify: `rust-core/src/prepaid/ffi.rs` (after `prepaid_staff_key_id` line ~159; `BatchResultFfi` line 38; `prepaid_verify_batch` mapping lines ~175–192; tests)
- Regenerate: `wallet-core/src/main/java/com/wyltek/wallet/core/native/wyltekwalletcore.kt`, both `jniLibs/arm64-v8a/libwyltekwalletcore.so`
- Modify: `app/src/main/java/com/wyltek/wallet/prepaid/Ports.kt` (`interface Protocol`, after `staffKeyId`)
- Modify: `app/src/main/java/com/wyltek/wallet/prepaid/NativeProtocol.kt`
- Modify: `app/src/test/java/com/wyltek/wallet/prepaid/fakes/FakeProtocol.kt`
- Test: `app/src/test/java/com/wyltek/wallet/prepaid/NativeProtocolRangeTest.kt` (new, pure Kotlin, no native load)

**Interfaces:**
- Produces (rust, uniffi):
  - `#[uniffi::export] pub fn prepaid_staff_pin_check(k_debit_hex: String, key_id_hex: String, pin_ver: u16, pin: String) -> Result<String, PrepaidError>` — derives `K_staff` internally, returns 32 hex; `PrepaidError::InvalidInput("PIN must be 4 to 8 digits")` when `!pin_ok`; `InvalidInput` for a bad hex length as the other functions do (`hex16`, `fixed::<8>`).
  - `LockoutFfi { pub index: u32, pub terminal_id: u16, pub uid_hex: String, pub key_id_hex: String, pub pin_ver: u16, pub attempts: i32, pub entry_hash_hex: String }` (`uniffi::Record`).
  - `BatchResultFfi` gains `#[uniffi(default = [])] pub lockouts: Vec<LockoutFfi>` (the repo already uses this attribute in `transaction.rs:50`; it gives the Kotlin data class a default, so the 38 existing `BatchResultFfi(...)` call sites in tests keep compiling).
- Produces (Kotlin):
  - `Protocol.staffPinCheck(kHex: String, keyIdHex: String, pinVer: Int, pin: String): String`
  - `NativeProtocol.staffPinCheck` = `require(pinVer in 1..65535) { "pin_ver out of range" }; prepaidStaffPinCheck(kHex, keyIdHex, pinVer.toUShort(), pin)`. Put the range check in a top-level `internal fun pinVerInRange(pinVer: Int): Boolean` in `NativeProtocol.kt` so it is testable without the native library.
  - `FakeProtocol.staffPinCheck` = first 16 bytes of SHA-256 of `"$kHex|$keyIdHex|$pinVer|$pin"` as 32 hex, with the same "NOT the native digest" note as `staffKeyId`.

- [ ] **Step 1: Write the failing rust FFI tests** in `ffi.rs`'s test module:

```rust
#[test] fn staff_pin_check_matches_the_pure_derivation_and_rejects_bad_pins() {
    let k = String::from("101112131415161718191a1b1c1d1e1f"); let kid = "c0ffee0102030405";
    let want = hex::encode(staff::pin_check(&staff::k_staff(&hex16(&k).unwrap()), &fixed::<8>(kid, "k").unwrap(), 2, b"0042").unwrap());
    assert_eq!(prepaid_staff_pin_check(k.clone(), kid.into(), 2, "0042".into()).unwrap(), want);
    assert!(matches!(prepaid_staff_pin_check(k.clone(), kid.into(), 1, "123".into()), Err(PrepaidError::InvalidInput(_))));
    assert!(matches!(prepaid_staff_pin_check(k.clone(), "c0ff".into(), 1, "1234".into()), Err(PrepaidError::InvalidInput(_))));
}
#[test] fn verify_batch_maps_lockouts() { /* attest a 0x05 entry as in Task 2, call prepaid_verify_batch, assert lockouts[0].{pin_ver, key_id_hex, uid_hex, attempts, terminal_id} */ }
```
- [ ] **Step 2: Run to verify they fail**

Run: `cd ~/wyltek-wallet/rust-core && cargo test --lib prepaid::ffi 2>&1 | tail -3`
Expected: compile error.

- [ ] **Step 3: Implement the FFI** (function, record, field, mapping in `prepaid_verify_batch`: `lockouts: r.lockouts.into_iter().map(|x| LockoutFfi { ..., uid_hex: hex::encode(x.uid), ... }).collect()`).

- [ ] **Step 4: Run to verify they pass**

Run: `cd ~/wyltek-wallet/rust-core && cargo test 2>&1 | grep -E "FAILED|panicked|test result" | head -20`
Expected: all `ok`.

- [ ] **Step 5: Regenerate the bindings (wallet-core ONLY) and the `.so`**

```bash
cd ~/wyltek-wallet/rust-core
cargo build --release
cargo run --bin uniffi-bindgen generate --library target/release/libwyltekwalletcore.so --language kotlin --out-dir /tmp/uniffi-out
cp /tmp/uniffi-out/com/wyltek/wallet/core/native/wyltekwalletcore.kt ../wallet-core/src/main/java/com/wyltek/wallet/core/native/wyltekwalletcore.kt
cargo ndk -t arm64-v8a build --release
cp target/aarch64-linux-android/release/libwyltekwalletcore.so ../wallet-core/src/main/jniLibs/arm64-v8a/libwyltekwalletcore.so
cp target/aarch64-linux-android/release/libwyltekwalletcore.so ../app/src/main/jniLibs/arm64-v8a/libwyltekwalletcore.so
grep -n "fun \`prepaidStaffPinCheck\`\|data class LockoutFfi\|lockouts" ../wallet-core/src/main/java/com/wyltek/wallet/core/native/wyltekwalletcore.kt | head
```
Expected: the three greps hit, and `BatchResultFfi`'s `lockouts` parameter has a default (`= listOf()`). If bindgen emits no default, say so in the report and update the 38 call sites instead (`grep -rln "BatchResultFfi(" app/src`), passing `lockouts = emptyList()`.

- [ ] **Step 6: Write the failing Kotlin tests**

`NativeProtocolRangeTest.kt`:
```kotlin
@Test fun pin_ver_must_fit_the_u16_wire_field_and_never_be_zero() {
    assertTrue(pinVerInRange(1)); assertTrue(pinVerInRange(65535))
    assertFalse(pinVerInRange(0)); assertFalse(pinVerInRange(65536)); assertFalse(pinVerInRange(-1))
}
```
In a new `FakeProtocolTest.kt` (or an existing fakes test file): `fake_pin_check_is_deterministic_and_changes_with_pin_ver_and_pin` — same inputs equal; `pinVer` 1 vs 2 differ; `"0042"` vs `"1042"` differ; length 32.

- [ ] **Step 7: Implement the Kotlin port, native adapter and fake; run**

Run: `cd ~/wyltek-wallet && ./gradlew :app:testDebugUnitTest -q 2>&1 | tail -5`, then count with:
```bash
python3 - <<'EOF'
import glob,re
t=f=0
for p in glob.glob('/home/phill/wyltek-wallet/app/build/test-results/testDebugUnitTest/*.xml'):
    s=open(p).read(); m=re.search(r'tests="(\d+)".*?failures="(\d+)"',s); t+=int(m[1]); f+=int(m[2])
print("tests",t,"failures",f)
EOF
```
Expected: `failures 0`; `BindingsSingleSourceTest` passes.

- [ ] **Step 8: Commit** (the `.so` files are gitignored and not committed)

```bash
git add rust-core/src/prepaid/ffi.rs wallet-core/src/main/java/com/wyltek/wallet/core/native/wyltekwalletcore.kt app/src/main/java/com/wyltek/wallet/prepaid/Ports.kt app/src/main/java/com/wyltek/wallet/prepaid/NativeProtocol.kt app/src/test/java/com/wyltek/wallet/prepaid/fakes/FakeProtocol.kt app/src/test/java/com/wyltek/wallet/prepaid/NativeProtocolRangeTest.kt app/src/test/java/com/wyltek/wallet/prepaid/fakes/FakeProtocolTest.kt
git commit -m "feat(prepaid): prepaid_staff_pin_check and lockout results over uniffi; Protocol.staffPinCheck"
```

---

### Task 5: DB v7: PIN columns on `prepaid_staff_keys`

**Files:**
- Modify: `app/src/main/java/com/wyltek/wallet/prepaid/db/VoucherEntities.kt:38-47` (`StaffKeyEntity`)
- Modify: `app/src/main/java/com/wyltek/wallet/agent/db/AgentDatabase.kt` (after `MIGRATION_5_6`, `version = 6`, `.addMigrations(...)`)
- Modify: `app/src/test/java/com/wyltek/wallet/agent/db/MigrationSqlMatchesRoomTest.kt`

**Interfaces:**
- Produces:
  - `StaffKeyEntity(keyId, uid, sigHex, label, enrolledAt, revokedAt: Long? = null, @ColumnInfo(defaultValue = "0") pinVer: Int = 0, pinCheckHex: String? = null, pinSetAt: Long? = null)` — the three new parameters last, with defaults, so every existing six-argument constructor call still compiles. `@ColumnInfo(defaultValue = "0")` is required: Room validates the column's default against the migrated table, and `ADD COLUMN ... NOT NULL` needs one.
  - `val MIGRATION_6_7_SQL: List<String>` (the three statements from Global Constraints, verbatim), `val MIGRATION_6_7 = object : Migration(6, 7)`, database `version = 7`, `.addMigrations(..., MIGRATION_5_6, MIGRATION_6_7)`.

- [ ] **Step 1: Rewrite the migration test.** Room's generated `CREATE TABLE` for `prepaid_staff_keys` now carries the v7 columns, so the 5→6 pin can no longer compare that statement verbatim. New shape:

```kotlin
private val v6StaffCreate = MIGRATION_5_6_SQL.single { it.startsWith("CREATE TABLE IF NOT EXISTS `prepaid_staff_keys`") }
private val addedColumns = MIGRATION_6_7_SQL.map { it.substringAfter("ADD COLUMN ") }
private val v7StaffCreate = v6StaffCreate.replace(", PRIMARY KEY", ", " + addedColumns.joinToString(", ") + ", PRIMARY KEY")

@Test fun migration_5_6_then_6_7_creates_exactly_what_room_expects() {
    assertEquals(5, roomSql.size)
    assertEquals(roomSql, MIGRATION_5_6_SQL.map { if (it == v6StaffCreate) v7StaffCreate else it }.toSet())
}
@Test fun migration_6_7_is_three_add_columns_on_staff_keys_in_room_order() {
    assertEquals(3, MIGRATION_6_7_SQL.size)
    MIGRATION_6_7_SQL.forEach { assertTrue(it, it.startsWith("ALTER TABLE `prepaid_staff_keys` ADD COLUMN `")) }
    assertEquals(listOf("`pinVer` INTEGER NOT NULL DEFAULT 0", "`pinCheckHex` TEXT", "`pinSetAt` INTEGER"), addedColumns)
    assertTrue(roomSql.single { it.contains("`prepaid_staff_keys`") }.contains(addedColumns.joinToString(", ")))
}
@Test fun database_is_version_7_and_registers_both_migrations() {
    assertTrue(impl.contains("RoomOpenHelper.Delegate(7)"))
    assertEquals(5 to 6, MIGRATION_5_6.startVersion to MIGRATION_5_6.endVersion)
    assertEquals(6 to 7, MIGRATION_6_7.startVersion to MIGRATION_6_7.endVersion)
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd ~/wyltek-wallet && ./gradlew :app:testDebugUnitTest --tests '*MigrationSqlMatchesRoomTest*' -q 2>&1 | tail -5`
Expected: compile error (`MIGRATION_6_7_SQL` unknown).

- [ ] **Step 3: Implement** the entity columns, `MIGRATION_6_7_SQL`, `MIGRATION_6_7`, version 7, registration. Doc-comment the migration "v6 → v7: staff PIN columns (vouchers spec §5.4)".

- [ ] **Step 4: Run to verify it passes**

Run: the Step 2 command.
Expected: 3 tests pass. The generated `AgentDatabase_Impl.java` must show `Delegate(7)`; if the KSP output is stale, run `./gradlew :app:kspDebugKotlin -q` first.

- [ ] **Step 5: Full unit suite** (the new entity defaults must leave every existing test green)

Run: the Task 4 Step 7 commands. Expected: `failures 0`.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/wyltek/wallet/prepaid/db/VoucherEntities.kt app/src/main/java/com/wyltek/wallet/agent/db/AgentDatabase.kt app/src/test/java/com/wyltek/wallet/agent/db/MigrationSqlMatchesRoomTest.kt
git commit -m "feat(prepaid): DB v7, staff key PIN columns"
```

---

### Task 6: `StaffKeyService`: enrol with a PIN, Set PIN, audits, `EnrolArm` carries the PIN

**Files:**
- Modify: `app/src/main/java/com/wyltek/wallet/prepaid/StaffKeyService.kt`
- Modify: `app/src/main/java/com/wyltek/wallet/prepaid/db/PrepaidEntities.kt:70` (audit kinds)
- Modify: `app/src/main/java/com/wyltek/wallet/agent/AgentGateway.kt:344` (constructor call)
- Modify: `app/src/test/java/com/wyltek/wallet/prepaid/StaffKeyServiceTest.kt`
- Modify: `app/src/test/java/com/wyltek/wallet/prepaid/VoucherFinalMinorsTest.kt:101-106` (`EnrolArm`)
- (The screen's `EnrolArm(label, now)` and `s.enrol(tag, label)` calls break here and are fixed in Task 9; until then `./gradlew :app:compileDebugKotlin` fails on `StaffKeysScreen.kt` only. Run the unit tests with `--tests` filters in this task, or make the two minimal call-site edits in the screen (pass `""` for the PIN) and let Task 9 replace them. Prefer the latter so the branch compiles at every commit; say which you did.)

**Interfaces:**
- Consumes: Task 4 `Protocol.staffPinCheck`, Task 5 entity columns, `PrepaidService.kHex` (internal, reachable from `AgentGateway`'s `prepaid?.let { ... }` as `it.kHex`).
- Produces:
  - `const val KIND_STAFF_PIN_SET = "staff_pin_set"; const val KIND_STAFF_LOCKOUT = "staff_lockout"` beside `KIND_STAFF_ENROL`.
  - `class StaffKeyService(store, vouchers, protocol, private val kHex: () -> String, clock = ..., newActionId = ..., verifySig = ...)` — `kHex` is the fourth positional parameter. `AgentGateway` passes `{ it.kHex }`; the test passes `{ "10".repeat(16) }`.
  - `companion`: `PIN_RULE = "PIN must be 4 to 8 digits"`, `PIN_VER_EXHAUSTED = "this tag's PIN version is used up; enrol a different tag"`, `fun pinError(pin: String): String?` = `null` when `pin.length in 4..8 && pin.all { it in '0'..'9' }`, else `PIN_RULE`.
  - `suspend fun enrol(tag: TagPort, label: String, pin: String): ActionOutcome`. Order: blank label → `Failed("enter a label")`; `pinError(pin)` → `Failed(PIN_RULE)` (before the tag is read); then as today through the cap check; `pinVer = (existing?.pinVer ?: 0) + 1`, `> 65535` → `Failed(PIN_VER_EXHAUSTED)`; `pinCheckHex = protocol.staffPinCheck(kHex(), keyId, pinVer, pin)`; row with `pinVer`, `pinCheckHex`, `pinSetAt = clock()`; insert or update; audit `KIND_STAFF_ENROL` as today; audit `KIND_STAFF_PIN_SET` with `detail = "pin_ver $pinVer"`.
  - `suspend fun setPin(keyId: String, pin: String): ActionOutcome` — active key required (`Failed("no active staff key $keyId")`), `pinError`, `pinVer + 1` with the 65535 refusal, `updateStaffKey(copy(pinVer, pinCheckHex, pinSetAt = clock()))`, audit `KIND_STAFF_PIN_SET` `"pin_ver $n"`.
  - `data class EnrolArm(val label: String, val pin: String, val armedAtMs: Long)` — unchanged TTL and `EXPIRED`.
  - `revoke` unchanged (the PIN columns survive).

- [ ] **Step 1: Write the failing tests.** Update every `svc.enrol(tag, label)` in `StaffKeyServiceTest` to pass `"1234"`, the constructor to pass `{ "10".repeat(16) }` as the fourth argument, `VoucherFinalMinorsTest`'s `EnrolArm("ring", armedAtMs = 1_000)` to `EnrolArm("ring", "1234", armedAtMs = 1_000)`. Add:

```kotlin
private val fake = FakeProtocol()   // same digest as the service's FakeProtocol: both are pure functions of the inputs
@Test fun enrol_sets_pin_ver_1_and_a_pin_check_and_audits_pin_set_after_enrol() = runTest {
    assertEquals(ActionOutcome.Done, svc.enrol(FakeTag("04958caa5c5e80"), "ring", "0042"))
    val k = svc.active().single()
    assertEquals(1, k.pinVer); assertEquals(fake.staffPinCheck("10".repeat(16), k.keyId, 1, "0042"), k.pinCheckHex); assertNotNull(k.pinSetAt)
    assertEquals(listOf(KIND_STAFF_ENROL, KIND_STAFF_PIN_SET), store.events.map { it.kind })
    assertEquals("pin_ver 1", store.events.last().detail); assertEquals(k.keyId, store.events.last().reserveId)
}
@Test fun a_bad_pin_is_refused_before_the_tag_is_read() = runTest {
    val gone = FakeTag("04958caa5c5e80").also { it.present = false }   // a read would answer NeedsTag
    for (bad in listOf("123", "123456789", "12a4", "12 4", "١٢٣٤", "１２３４", "")) assertEquals(bad, ActionOutcome.Failed(StaffKeyService.PIN_RULE), svc.enrol(gone, "ring", bad))
    assertTrue(svc.active().isEmpty()); assertTrue(store.events.isEmpty())
    for (good in listOf("1234", "12345678", "0000")) assertNull(StaffKeyService.pinError(good))
}
@Test fun leading_zeros_reach_the_derivation_unchanged() = runTest {
    svc.enrol(FakeTag("04958caa5c5e80"), "a", "0042"); val k = svc.active().single()
    assertEquals(fake.staffPinCheck("10".repeat(16), k.keyId, 1, "0042"), k.pinCheckHex)
    assertNotEquals(fake.staffPinCheck("10".repeat(16), k.keyId, 1, "1042"), k.pinCheckHex)
}
@Test fun set_pin_bumps_pin_ver_and_changes_pin_check_even_for_the_same_digits() = runTest {
    svc.enrol(FakeTag("04958caa5c5e80"), "a", "1234"); val before = svc.active().single()
    assertEquals(ActionOutcome.Done, svc.setPin(before.keyId, "1234")); val after = svc.active().single()
    assertEquals(2, after.pinVer); assertNotEquals(before.pinCheckHex, after.pinCheckHex); assertTrue(after.pinSetAt!! > before.pinSetAt!!)
    assertEquals("pin_ver 2", store.events.last().detail); assertEquals(KIND_STAFF_PIN_SET, store.events.last().kind)
    assertEquals(ActionOutcome.Failed(StaffKeyService.PIN_RULE), svc.setPin(before.keyId, "12"))
}
@Test fun set_pin_on_a_revoked_or_unknown_key_is_refused() = runTest {
    svc.enrol(FakeTag("04958caa5c5e80"), "a", "1234"); val id = svc.active().single().keyId; svc.revoke(id)
    assertTrue(svc.setPin(id, "1234") is ActionOutcome.Failed); assertTrue(svc.setPin("00".repeat(8), "1234") is ActionOutcome.Failed)
}
@Test fun re_enrol_after_revoke_continues_pin_ver() = runTest {
    svc.enrol(FakeTag("04958caa5c5e80"), "a", "1234"); val id = svc.active().single().keyId
    svc.setPin(id, "1234"); svc.revoke(id)
    assertEquals("revoke keeps the PIN columns", 2, vstore.keys[id]!!.pinVer)
    assertEquals(ActionOutcome.Done, svc.enrol(FakeTag("04958caa5c5e80"), "again", "9999"))
    assertEquals(3, svc.active().single().pinVer)
}
@Test fun pin_ver_at_65535_refuses_set_pin_and_re_enrol() = runTest {
    svc.enrol(FakeTag("04958caa5c5e80"), "a", "1234"); val k = svc.active().single()
    vstore.updateStaffKey(k.copy(pinVer = 65535))
    assertEquals(ActionOutcome.Failed(StaffKeyService.PIN_VER_EXHAUSTED), svc.setPin(k.keyId, "1234"))
    svc.revoke(k.keyId)
    assertEquals(ActionOutcome.Failed(StaffKeyService.PIN_VER_EXHAUSTED), svc.enrol(FakeTag("04958caa5c5e80"), "b", "1234"))
    assertEquals(65535, vstore.keys[k.keyId]!!.pinVer)
}
@Test fun no_audit_row_ever_contains_the_pin() = runTest {
    val pin = "97315084"
    svc.enrol(FakeTag("04958caa5c5e80"), "ring", pin); svc.setPin(svc.active().single().keyId, pin); svc.revoke(svc.active().single().keyId)
    assertTrue(store.events.size >= 4)
    for (e in store.events) for (s in listOf(e.detail, e.uid, e.reserveId ?: "", e.kind, e.txHash ?: "")) assertFalse("$e", s.contains(pin))
    assertFalse(vstore.keys.values.any { it.label.contains(pin) || it.sigHex.contains(pin) })
}
```

- [ ] **Step 2: Run to verify they fail**

Run: `cd ~/wyltek-wallet && ./gradlew :app:testDebugUnitTest --tests '*StaffKeyServiceTest*' --tests '*VoucherFinalMinorsTest*' -q 2>&1 | tail -5`
Expected: compile error (three-argument `enrol`, `setPin`, `EnrolArm` arity).

- [ ] **Step 3: Implement** per the Interfaces block. Keep `enrol` under 50 lines by extracting `private suspend fun storeWithPin(existing: StaffKeyEntity?, row: StaffKeyEntity, pin: String): ActionOutcome` that computes `pinVer`, refuses exhaustion, computes `pinCheckHex`, writes and audits `staff_pin_set`; `setPin` reuses it. Never put `pin` in a string, a log, or an exception.

- [ ] **Step 4: Run to verify they pass**

Run: the Step 2 command. Expected: all pass. Then the full suite (Task 4 Step 7): `failures 0`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/wyltek/wallet/prepaid/StaffKeyService.kt app/src/main/java/com/wyltek/wallet/prepaid/db/PrepaidEntities.kt app/src/main/java/com/wyltek/wallet/agent/AgentGateway.kt app/src/test/java/com/wyltek/wallet/prepaid/StaffKeyServiceTest.kt app/src/test/java/com/wyltek/wallet/prepaid/VoucherFinalMinorsTest.kt app/src/main/java/com/wyltek/wallet/prepaid/ui/StaffKeysScreen.kt
git commit -m "feat(prepaid): staff keys enrol with a PIN, Set PIN bumps pin_ver, staff_pin_set audit"
```

---

### Task 7: Settle kind `0x05`: one `staff_lockout` audit row, resend is `duplicate`

**Files:**
- Modify: `app/src/main/java/com/wyltek/wallet/prepaid/PrepaidService.kt:44` (`VOUCHER_ENTRY_KINDS`)
- Modify: `app/src/main/java/com/wyltek/wallet/prepaid/VoucherService.kt` (`apply`, new `settleLockout`)
- Modify: `app/src/main/java/com/wyltek/wallet/prepaid/VoucherHooks.kt` (doc comment of `apply`)
- Test: `app/src/test/java/com/wyltek/wallet/prepaid/StaffLockoutSettleTest.kt` (new; copy the fixture pattern of the override tests in `VoucherSettleTest.kt`)
- Test: `app/src/test/java/com/wyltek/wallet/prepaid/PrepaidServiceSettleTest.kt` (one test)

**Interfaces:**
- Consumes: Task 4 `LockoutFfi`, `BatchResultFfi.lockouts`; Task 6 `KIND_STAFF_LOCKOUT`.
- Produces:
  - `VOUCHER_ENTRY_KINDS = setOf(0x03, 0x04, 0x05)` — so without a voucher hook an accepted `0x05` answers `retry`, as the other voucher kinds do.
  - In `VoucherService.apply`: `for (l in res.lockouts) if (accepted(l.index)) out[l.index.toInt()] = settleLockout(l, terminalLabel)`.
  - `private suspend fun settleLockout(l: LockoutFfi, terminalLabel: String): String` — same structure as `settleOverride`: `seen(hash)` → `"duplicate"`; else `insertSeen(JournalSeenEntity(hash, KIND_STAFF_LOCKOUT, null, null, l.terminalId.toInt(), core.clock()))`, then `core.log(PrepaidService.Audit(core.newActionId(), KIND_STAFF_LOCKOUT, l.uidHex.lowercase(), l.keyIdHex.lowercase(), l.attempts.toLong(), 0, 0), PHASE_DONE, balanceAfter = l.terminalId.toInt(), counterAfter = l.pinVer.toLong(), detail = "terminal ${l.terminalId}; pin_ver ${l.pinVer}; ${l.attempts} wrong PINs; ${staffNote(l.keyIdHex)}; $terminalLabel")`, `"accepted"`; any exception → `"retry"`.

- [ ] **Step 1: Write the failing tests**

`StaffLockoutSettleTest` (build the hook + stores exactly as `VoucherSettleTest` does for overrides; a `LockoutFfi(index = 0u, terminalId = 9.toUShort(), uidHex = "04010203040506", keyIdHex = KEY, pinVer = 7.toUShort(), attempts = 3, entryHashHex = "ab".repeat(32))`):
```kotlin
@Test fun a_lockout_is_accepted_and_writes_one_staff_lockout_row_with_terminal_and_pin_ver()
    // names == ["accepted"]; one event: kind staff_lockout, reserveId KEY, uid "04010203040506", amountUnits 3, balanceAfter 9, counterAfter 7,
    // detail starts with "terminal 9; pin_ver 7; 3 wrong PINs; staff key <KEY> (enrolled)"; vstore.seen has the hash with kind staff_lockout
@Test fun a_resent_lockout_answers_duplicate_with_no_second_row()
    // apply twice (new BatchResultFfi each time): second names == ["duplicate"], events.size still 1
@Test fun a_lockout_for_an_unknown_or_revoked_key_is_still_accepted_and_says_so()
    // KEY not enrolled → accepted, detail contains "(unknown)"; enrol then revoke → "(revoked)"
@Test fun a_lockout_whose_verdict_was_not_accepted_is_left_alone()
    // names = ["bad_mac"] in → ["bad_mac"] out, no events, no seen
```
`PrepaidServiceSettleTest`: `an_accepted_0x05_without_a_voucher_hook_answers_retry` — mirror that file's existing "voucher kind without hook → retry" test for kind `0x04`, with an entry whose second hex byte is `05`. (Mutation probe: drop `0x05` from `VOUCHER_ENTRY_KINDS` and this test must go red.)

- [ ] **Step 2: Run to verify they fail**

Run: `cd ~/wyltek-wallet && ./gradlew :app:testDebugUnitTest --tests '*StaffLockoutSettleTest*' --tests '*PrepaidServiceSettleTest*' -q 2>&1 | tail -5`
Expected: the four new tests fail (`names` unchanged / `retry` not produced).

- [ ] **Step 3: Implement.** Update the `apply` doc comment in `VoucherHooks.kt` to "accepted redeems, overrides and staff lockouts".

- [ ] **Step 4: Run to verify they pass**, then the full suite: `failures 0`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/wyltek/wallet/prepaid/PrepaidService.kt app/src/main/java/com/wyltek/wallet/prepaid/VoucherService.kt app/src/main/java/com/wyltek/wallet/prepaid/VoucherHooks.kt app/src/test/java/com/wyltek/wallet/prepaid/StaffLockoutSettleTest.kt app/src/test/java/com/wyltek/wallet/prepaid/PrepaidServiceSettleTest.kt
git commit -m "feat(prepaid): settle staff lockout entries (kind 0x05) into the audit log, deduped by entry hash"
```

---

### Task 8: `GET /v1/prepaid/staff` serves `pin_ver` + `pin_check`, omits keys without a PIN

**Files:**
- Modify: `app/src/main/java/com/wyltek/wallet/agent/server/AgentDtos.kt:67` (`StaffKeyDto`)
- Modify: `app/src/main/java/com/wyltek/wallet/agent/PrepaidGatewayMapping.kt:40-44` (`staffDtoOf`, `staffDtosOf`)
- Modify: `app/src/main/java/com/wyltek/wallet/agent/server/AgentRouter.kt:34` (route doc line)
- Modify: `app/src/test/java/com/wyltek/wallet/agent/server/PrepaidRoutesTest.kt:64-79`

**Interfaces:**
- Produces:
  - `@Serializable data class StaffKeyDto(@SerialName("key_id") val keyId: String, @SerialName("pin_ver") val pinVer: Int, @SerialName("pin_check") val pinCheck: String)`
  - `fun staffDtoOf(k: StaffKeyEntity): StaffKeyDto?` — `null` when `k.pinCheckHex == null`, else lower-cased hex fields.
  - `fun staffDtosOf(keys): List<StaffKeyDto>` = `keys.filter { it.revokedAt == null && it.pinCheckHex != null }.take(MAX_ACTIVE).mapNotNull(::staffDtoOf)` — filter before `take`, so a PIN-less key never uses a slot.
  - `AgentGateway.prepaidStaff` unchanged (it already passes `activeStaffKeys()` through the mapping).

- [ ] **Step 1: Write the failing tests.** Update `staff_ok_lists_keys_and_needs_a_token` and `staff_wire_never_carries_the_uid_or_the_signature` to the new JSON, e.g. `{"staff":[{"key_id":"7777777777777777","pin_ver":1,"pin_check":"ab…ab"}]}` with `StaffKeyEntity(..., pinVer = 1, pinCheckHex = "ab".repeat(16), pinSetAt = 1)`. Add:

```kotlin
@Test fun staff_omits_a_key_without_a_pin_and_a_pinless_key_does_not_use_a_slot() {
    fun k(i: Int, pin: Boolean) = StaffKeyEntity("%016x".format(i), "04" + "%012x".format(i), "5a".repeat(32), "k$i", i.toLong(), null,
        pinVer = if (pin) 1 else 0, pinCheckHex = if (pin) "ab".repeat(16) else null, pinSetAt = if (pin) 1 else null)
    val keys = listOf(k(0, false)) + (1..16).map { k(it, true) } + listOf(k(17, true))
    val dtos = PrepaidGatewayMapping.staffDtosOf(keys)
    assertEquals(16, dtos.size); assertEquals((1..16).map { "%016x".format(it) }, dtos.map { it.keyId })
    assertTrue(dtos.all { it.pinVer == 1 && it.pinCheck == "ab".repeat(16) })
    assertTrue(PrepaidGatewayMapping.staffDtosOf(listOf(k(1, true).copy(revokedAt = 2))).isEmpty())
}
@Test fun staff_wire_carries_lowercase_hex() { /* pinCheckHex "AB".repeat(16) → pin_check "ab"… */ }
```

- [ ] **Step 2: Run to verify they fail**

Run: `cd ~/wyltek-wallet && ./gradlew :app:testDebugUnitTest --tests '*PrepaidRoutesTest*' -q 2>&1 | tail -5`
Expected: compile error (`StaffKeyDto` arity).

- [ ] **Step 3: Implement.** Router doc line 34 becomes `GET /v1/prepaid/staff — active staff keys that have a PIN (key_id, pin_ver, pin_check; max 16) for terminals (x-device-token)`.

- [ ] **Step 4: Run to verify they pass**, then the full suite: `failures 0`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/wyltek/wallet/agent/server/AgentDtos.kt app/src/main/java/com/wyltek/wallet/agent/PrepaidGatewayMapping.kt app/src/main/java/com/wyltek/wallet/agent/server/AgentRouter.kt app/src/test/java/com/wyltek/wallet/agent/server/PrepaidRoutesTest.kt
git commit -m "feat(agent): /v1/prepaid/staff serves pin_ver and pin_check; keys without a PIN are not served"
```

---

### Task 9: Staff keys screen: PIN at enrol, Set PIN, list states

**Files:**
- Create: `app/src/main/java/com/wyltek/wallet/prepaid/StaffKeyStatus.kt`
- Test: `app/src/test/java/com/wyltek/wallet/prepaid/StaffKeyStatusTest.kt`
- Modify: `app/src/main/java/com/wyltek/wallet/prepaid/ui/StaffKeysScreen.kt`

**Interfaces:**
- Consumes: Task 6 `StaffKeyService.enrol(tag, label, pin)`, `setPin`, `pinError`, `EnrolArm(label, pin, armedAtMs)`; Task 7's audit field convention (`counterAfter` = `pin_ver`, `balanceAfter` = terminal id); `AgentGateway.prepaidStore().eventsFlow()`.
- Produces (pure, JVM-testable):
  ```kotlin
  sealed class StaffKeyStatus {
      data object Revoked : StaffKeyStatus(); data object NoPin : StaffKeyStatus()
      data class Locked(val terminalId: Int, val atMs: Long) : StaffKeyStatus(); data class Served(val pinVer: Int) : StaffKeyStatus()
  }
  /** Newest staff_lockout event per key_id (events arrive newest first from eventsFlow). */
  fun latestLockouts(events: List<PrepaidEventEntity>): Map<String, PrepaidEventEntity>
  fun staffKeyStatus(k: StaffKeyEntity, latestLockout: PrepaidEventEntity?): StaffKeyStatus
  ```
  Rules, in order: `revokedAt != null` → `Revoked`; `pinCheckHex == null` → `NoPin`; `latestLockout != null && latestLockout.counterAfter == k.pinVer.toLong()` → `Locked(latestLockout.balanceAfter, latestLockout.atMs)`; else `Served(k.pinVer)`.
- Screen copy (exact):
  - Enrol form: `Label, e.g. Phill's ring`, `PIN (4–8 digits)`, `Repeat PIN`; both PIN fields `KeyboardType.NumberPassword` + `PasswordVisualTransformation()`; helper text under them when non-empty and wrong: `StaffKeyService.PIN_RULE` or `PINs do not match`. **Enrol** enabled when the label is non-blank, `pinError(pin) == null`, `pin == repeat` and not busy. After `vm.arm(label, pin)` the PIN fields are cleared.
  - List, per key, `supportingContent` second line by status: `NoPin` → `No PIN: not sent to terminals`; `Locked` → `Locked on terminal <terminalId> at <HH:mm d MMM>; set a new PIN to unlock`; `Served` → `PIN set (v<pinVer>)`; `Revoked` → existing `· revoked`.
  - Trailing: for active keys a **Set PIN** `TextButton` beside **Revoke**.
  - Set PIN dialog: title `Set PIN for <label>`, text `Terminals that locked this key unlock at their next sync.`, fields `New PIN` / `Repeat PIN` (same keyboard and masking), confirm **Set PIN** enabled on a valid, matching pair, then `gated("Set PIN for <label>", "Terminals unlock at their next sync") { vm.setPin(k, pin) }`.
- View model: `fun arm(label: String, pin: String)` (`EnrolArm(label, pin, now)`), `fun setPin(k: StaffKeyEntity, pin: String)` (launches `svc.setPin`, posts the outcome like `revoke`), `val lockouts: StateFlow<Map<String, PrepaidEventEntity>>` = `gateway.prepaidStore().eventsFlow().map(::latestLockouts).stateIn(...)`. `onTag` passes `a.pin` to `enrol`; `StaffUi.armedLabel` stays label-only (the PIN never reaches the UI state object or any `Text`).

- [ ] **Step 1: Write the failing status tests**

```kotlin
class StaffKeyStatusTest {
    private fun key(pin: Boolean, ver: Int = 1, revoked: Long? = null) = StaffKeyEntity("77".repeat(8), "04958caa5c5e80", "5a".repeat(32), "ring", 1, revoked,
        pinVer = if (pin) ver else 0, pinCheckHex = if (pin) "ab".repeat(16) else null, pinSetAt = if (pin) 1 else null)
    private fun lockout(id: Long, keyId: String, pinVer: Long, terminal: Int, at: Long) = PrepaidEventEntity(id = id, atMs = at, actionId = "a$id", kind = KIND_STAFF_LOCKOUT,
        phase = PHASE_DONE, reserveId = keyId, uid = "04010203040506", amountUnits = 3, balanceBefore = 0, balanceAfter = terminal, counterBefore = 0, counterAfter = pinVer, txHash = null, detail = "")
    @Test fun no_pin_wins_over_everything_but_revoked() { assertEquals(StaffKeyStatus.NoPin, staffKeyStatus(key(false), lockout(1, "77".repeat(8), 0, 9, 5))); assertEquals(StaffKeyStatus.Revoked, staffKeyStatus(key(false, revoked = 3), null)) }
    @Test fun a_lockout_at_the_current_pin_ver_shows_locked_with_terminal_and_time() { assertEquals(StaffKeyStatus.Locked(9, 5), staffKeyStatus(key(true, ver = 2), lockout(1, "77".repeat(8), 2, 9, 5))) }
    @Test fun a_lockout_at_an_older_pin_ver_is_cleared_by_set_pin() { assertEquals(StaffKeyStatus.Served(3), staffKeyStatus(key(true, ver = 3), lockout(1, "77".repeat(8), 2, 9, 5))) }
    @Test fun latest_lockouts_keeps_the_newest_per_key_and_ignores_other_kinds() {
        val a = lockout(3, "aa".repeat(8), 1, 1, 30); val older = lockout(2, "aa".repeat(8), 1, 2, 20); val b = lockout(1, "bb".repeat(8), 1, 3, 10)
        val other = older.copy(id = 4, kind = KIND_STAFF_PIN_SET)
        assertEquals(mapOf("aa".repeat(8) to a, "bb".repeat(8) to b), latestLockouts(listOf(other, a, older, b)))
    }
}
```

- [ ] **Step 2: Run to verify they fail**

Run: `cd ~/wyltek-wallet && ./gradlew :app:testDebugUnitTest --tests '*StaffKeyStatusTest*' -q 2>&1 | tail -5`
Expected: compile error.

- [ ] **Step 3: Implement `StaffKeyStatus.kt`**, then the view model and screen changes. The time format: `java.text.SimpleDateFormat("HH:mm d MMM", Locale.getDefault())`.

- [ ] **Step 4: Run the status tests, the full suite, and build the APK**

Run: `cd ~/wyltek-wallet && ./gradlew :app:testDebugUnitTest :app:assembleDebug -q 2>&1 | tail -5`, then the count script. Expected: `failures 0`, `app/build/outputs/apk/debug/app-debug.apk` present.

- [ ] **Step 5: Grep the screen for leaks**

Run: `grep -n "pin" app/src/main/java/com/wyltek/wallet/prepaid/ui/StaffKeysScreen.kt | grep -i "Log\.\|println\|Text(.*pin\b" || echo clean`
Expected: `clean` (the PIN appears only in field state, `pinError`, `arm`, `setPin`).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/wyltek/wallet/prepaid/StaffKeyStatus.kt app/src/test/java/com/wyltek/wallet/prepaid/StaffKeyStatusTest.kt app/src/main/java/com/wyltek/wallet/prepaid/ui/StaffKeysScreen.kt
git commit -m "feat(prepaid): Staff keys screen asks for a PIN, Set PIN, no-PIN and locked states"
```

---

### Task 10: Review, security review, and the device checkpoint

**Files:**
- Create: `docs/superpowers/STAFF-PIN-V1_1-CHECKPOINT.md`

- [ ] **Step 1: Whole-branch review.** Dispatch a `code-reviewer` over `git diff main...feat/staff-pin-v1_1` with the spec (§5.1, §5.2, §5.4, §7, §8.3, §9). Dispatch a `security-reviewer` over the same diff, focused on:
  - **PIN hygiene:** the PIN string's every path from the Compose field to `prepaidStaffPinCheck` (no log, no exception message, no audit detail, no UI state beyond the field); `EnrolArm` holds it only for 60 s.
  - **Derivation:** `staff.rs` against the spec block; domain separation from CMAC8; full 16 bytes; `pin_ver` in the message.
  - **Wire:** `/staff` auth unchanged; nothing but `key_id`, `pin_ver`, `pin_check` leaves the phone.
  - **Settlement:** `settleLockout`'s dedupe and the seen-before-audit order; an unknown key cannot make the Vault do anything but write one row.

  Fix CRITICAL and HIGH findings, each with a test. Re-run the whole suite and `cargo test`: failures 0.

- [ ] **Step 2: Write the checkpoint document** with these steps for Phill (he runs them; the executor writes the document and the exact commands). Each step has a `Result:` line to fill.
  1. Build and install, preserving data: `./gradlew :app:assembleDebug -q && adb -s R5CTC07MPYD install -r app/build/outputs/apk/debug/app-debug.apk`.
  2. Migration v6 → v7 on the real DB: open Prepaid Cards → Staff keys. Expected: the keys enrolled on 2026-10-16 are listed with `No PIN: not sent to terminals` and a **Set PIN** button; the two live cards are still listed.
  3. Set PIN on an existing key (twice, `1234` both times). Expected: `PIN set (v1)` then `PIN set (v2)`; two `staff_pin_set` audit rows with `pin_ver 1`, `pin_ver 2`.
  4. Enrol a new key with a PIN. Needs a genuine NTAG21x; the counterfeit NTAG215 stock is refused at `READ_SIG`. Record `REFUSED (counterfeit)` if that is still the only stock, and move on.
  5. `/staff` from the laptop with the device token (copy the curl from `PREPAID-VOUCHERS-V1-CHECKPOINT.md`). Expected: every served entry has `pin_ver` and a 32-hex `pin_check`; a key you have not set a PIN on is absent.
  6. Lockout report: build an entry with `cargo run --example prepaid_journal_entry -- <k_debit> lockout 7 <key_id> <pin_ver> --merchant <merchant_id>` (the Vault's real `K_debit` and `merchant_id`, as in the D3 checkpoint's manual settlement) and POST it as a one-entry journal. Expected: verdict `accepted`; a `staff_lockout` audit row; the Staff keys screen shows `Locked on terminal 7 at <time>; set a new PIN to unlock`.
  7. Resend the same entry. Expected: `duplicate`, no second row.
  8. Set PIN on that key. Expected: the locked line disappears (`PIN set (v<n+1>)`).
  9. PIN never logged: `adb -s R5CTC07MPYD logcat -d | grep -c 1234` during steps 3 and 8. Expected: `0` (choose a PIN unlikely to appear elsewhere, e.g. `73105`).

- [ ] **Step 3: Commit**

```bash
git add docs/superpowers/STAFF-PIN-V1_1-CHECKPOINT.md
git commit -m "docs(prepaid): staff PIN V1.1 device checkpoint"
```

Then `superpowers:finishing-a-development-branch`. Merging to `main` and pushing need Phill's say-so.
