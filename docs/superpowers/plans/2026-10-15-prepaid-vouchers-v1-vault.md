# Prepaid vouchers + staff keys, V1 (Vault) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The Vault issues item vouchers (1 × coffee) on NTAG21x tags, each backed by its own chain cell, enrols staff keys by NXP originality signature, serves the staff list to terminals, and settles voucher redemptions and price overrides from terminal journals.

**Architecture:**
- **rust-core** gains the byte formats, as a pure, vector-pinned oracle the POS will copy in V2:
  - the `BBV1` voucher record
  - journal kinds `0x03`/`0x04`
  - the voucher-aware settlement verifier
- **Kotlin** adds:
  - `VoucherService`, which reuses `PrepaidService`'s mutex, chain helpers and audit log through `internal` members
  - `StaffKeyService`
  - a hand-written verify-only secp128r1 check
  - DB v6 (three tables)
  - `GET /v1/prepaid/staff`
  - tap-sheet and screen additions
- `PrepaidService` learns about vouchers only through a small `VoucherHooks` interface, so its 727 lines barely move.

**Tech Stack:** Rust (rust-core, uniffi 0.28, blake2b-ref, aes/cmac), Kotlin + Jetpack Compose + Room 2 + SQLCipher, JUnit4 + kotlinx-coroutines-test, python-ecdsa 0.19 (test-vector generation only).

**Spec:** `~/blackbox-pos/docs/superpowers/specs/2026-10-15-blackbox-prepaid-vouchers-staff-keys-design.md` (commit 223ee85). The D3 spec as built is `~/blackbox-pos/docs/superpowers/specs/2026-10-13-prepaid-demo-d3-vault-design.md`.

## Global Constraints

**Phone, branch and build safety**
- Work on branch `feat/prepaid-vouchers-v1` in a worktree of `~/wyltek-wallet`, from `main` @ `a9af699`. Never push.
- **NEVER run `connectedAndroidTest` / `connectedDebugAndroidTest`.** AGP uninstalls the app afterwards and wipes Phill's wallet. Install only with `adb -s R5CTC07MPYD install -r`.
- The uniffi bindings live ONLY in `wallet-core/src/main/java/com/wyltek/wallet/core/native/wyltekwalletcore.kt`. `BindingsSingleSourceTest` fails if a copy appears under `app/`. The `.so` goes into both `jniLibs/arm64-v8a/` directories.
- `rust-core/tests/fixtures/prepaid_demo_vectors.txt` (D1, SHA-256 `90937c92e909acf82a1b06c3f79e18840fd6e6d3c6d7bb6b9f27704fa98e60d8`) must stay byte-identical. Vouchers get their own fixture and pin.

**Voucher cell**
- Merchant funding lock, no type script.
- Data `0x4242563100000000` (`"BBV1"` + 4 zero bytes).
- Capacity exactly `6_900_000_000` shannons (69 CKB = 8 capacity + 53 lock + 8 data).

**Voucher record (64 B, `K_debit`, same slots A/B and slot rules as the card record)**
- Layout, verbatim from spec §4:
  - 0 `"BBV1"` · 4 version `0x01` · 5 flags `0`
  - 6 writer_id u16 LE · 8 voucher_id 16 B · 24 product_hash 8 B
  - 32 issue_price u32 LE · 36 currency 3 B ASCII A–Z · 39 count u8
  - 40 counter u32 LE · 44 reserved 4 B zero · 48 merchant_id 8 B
  - 56 mac = CMAC8(K_debit, UID‖bytes 0..56)
- Refuse if flags ≠ 0, reserved ≠ 0, or currency is not three A–Z bytes.
- `voucher_id` = first 16 bytes of `reserve_id(tx_hash, index)`.
- `product_hash` = first 8 bytes of ckb-blake2b-256(normalised code).
- Normalised code: trim ASCII whitespace at both ends, upper-case ASCII letters, keep all other bytes. It must be 1..=31 bytes with no ASCII control byte (`< 0x20` or `0x7F`). Leading zeros are kept.

**Journal kinds (96 B, version `0x02`, tmv = CMAC8 over the entry with tmv zeroed)**
- Kinds 1 and 2 are unchanged; pad 84..96 must be zero.
- **Kind `0x03` (voucher redeem):**
  - uid = voucher tag
  - reserve_id = voucher_id(16) ‖ product_hash(8) ‖ 0(8)
  - tmc = voucher counter after
  - amount_units = 1
  - balance_after = count after (0..=254)
  - pad = key_id(8, non-zero) ‖ 0(4)
- **Kind `0x04` (price override):**
  - uid = staff tag
  - reserve_id = product_hash(8) ‖ 0(24)
  - tmc = 0
  - amount_units = old price ≥ 0
  - balance_after = new price ≥ 0
  - pad = key_id(8, non-zero) ‖ currency(3, A–Z) ‖ 0(1)

**Staff keys**
- `key_id` = first 8 bytes of ckb-blake2b-256(`uid(7) ‖ sig(32)`).
- NXP NTAG21x originality public key (AN11350): `04494E1A386D3D3CFE3DC10E5DE68A499B1C202DB5B132393E89ED19FE5BE8BC61`.
- The signature is `r(16)‖s(16)`, ECDSA on secp128r1 over the 7-byte UID unhashed.
- `/v1/prepaid/staff` returns active keys only, at most 16.

**Verdict wire names**
- New: `double_redeem`, `unknown_voucher`, `voucher_void`.
- An unknown kind whose MAC verifies → `unsupported_kind`, sent on the wire as `retry`.

**Deviations from the spec, decided in this plan:**
1. **Uses left are counted down by the Vault.** Each accepted redeem decrements the voucher's count in the index. The entry's `balance_after` is not trusted, so a copied tag can't raise the count.
2. **Used-up vouchers close in their own transaction**, run straight after the settlement batch inside the same lock (`afterSettle`). The spec said "the same settlement transaction batch". A failed close is retried at the next settlement or page open, and it never changes a verdict.
3. **Product codes are typed, or scanned as QR.** The existing scanner is QR-only, so 1D barcode scanning on the phone is deferred.
4. **The currency field defaults to `AUD`** (the Vault doesn't know the POS currency).
5. **Older Vaults.** A pre-V1 Vault answers `bad_mac` for kinds 3/4. That's safe: terminals delete entries only on `accepted`/`duplicate` (main spec §8.1), so they keep them.

**Commits.** End every commit message with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. **Phill's phone is at DB v5 with two live cards.** After upgrading, the app must open, run 5→6, and still list both cards. Task 7 pins the migration SQL against Room's generated schema; Task 16 checks it on the phone.
2. **A tag carrying a voucher must never classify as Blank**, otherwise "Issue card" would overwrite it. This covers any merchant's voucher, one that fails its MAC, and a wiped or torn tag whose UID has a live voucher in the index. Task 9.
3. **The voucher cell must never be picked as a fee or change input** by `PrepaidTxPlanner`. It has data, so it isn't "pure". Task 10.
4. **A voucher cell `cellAt` cannot find must never close the voucher in the index.** An RPC outage looks exactly the same. Void or close must fail and leave the row unchanged. Task 11.
5. **A resent identical redeem or override entry must answer `duplicate`.** No second count decrement and no second audit row. Tasks 3 and 13.

---

### Task 1: rust-core voucher record, product-code hash, voucher_id, staff key_id

**Files:**
- Create: `rust-core/src/prepaid/voucher.rs`
- Modify: `rust-core/src/prepaid/mod.rs`
- Test: inline `#[cfg(test)]` in `voucher.rs`

**Interfaces:**
- Consumes:
  - `record::{RECORD_SIZE, SlotPick, SlotDiag, SlotNoneReason, subtle_ct::ct_eq8}`
  - `cmac::cmac8`
  - `reserve::{ckb_blake2b, reserve_id}`
- Produces, in `crate::prepaid::voucher`:
  - `MAGIC`, `VERSION`
  - `struct VoucherRecord { writer_id: u16, voucher_id: [u8;16], product_hash: [u8;8], issue_price: u32, currency: [u8;3], count: u8, counter: u32, merchant_id: [u8;8], mac: [u8;8] }`
  - `encode`, `decode`, `compute_mac`, `verify`, `seal(&key, &uid, &mut VoucherRecord) -> [u8;64]`
  - `select_slot_diag(&key, &uid, &a, &b) -> (SlotPick, Option<VoucherRecord>, SlotDiag)`
  - `normalise_product_code(&str) -> Option<String>`, `product_hash(&str) -> Option<[u8;8]>`
  - `voucher_id(&[u8;32], u32) -> [u8;16]`
  - `staff_key_id(&[u8;7], &[u8;32]) -> [u8;8]`

- [ ] **Step 1: Write the failing tests** (create `rust-core/src/prepaid/voucher.rs` with only the test module and `use super::*;`, and add `pub mod voucher;` to `mod.rs`)

```rust
#[cfg(test)]
mod tests {
    use super::*;
    use crate::prepaid::record::{self, SlotPick, SlotNoneReason};
    fn key() -> [u8; 16] { core::array::from_fn(|i| i as u8) }
    fn uid() -> [u8; 7] { [0x04, 0x95, 0x8C, 0xAA, 0x5C, 0x5E, 0x80] }
    fn sample() -> VoucherRecord {
        VoucherRecord { writer_id: 0, voucher_id: core::array::from_fn(|i| 0xC0 + i as u8),
            product_hash: product_hash("COF01").unwrap(), issue_price: 450, currency: *b"AUD", count: 1, counter: 0,
            merchant_id: [0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88], mac: [0; 8] }
    }

    #[test]
    fn layout_is_64_bytes_little_endian() {
        let mut r = sample(); r.writer_id = 3; r.counter = 5; r.count = 2;
        let raw = encode(&r);
        assert_eq!(&raw[0..4], b"BBV1"); assert_eq!(raw[4], 0x01); assert_eq!(raw[5], 0);
        assert_eq!(&raw[6..8], &[3, 0]);
        assert_eq!(raw[8], 0xC0); assert_eq!(raw[23], 0xCF);
        assert_eq!(&raw[24..32], &product_hash("COF01").unwrap());
        assert_eq!(&raw[32..36], &450u32.to_le_bytes());
        assert_eq!(&raw[36..39], b"AUD"); assert_eq!(raw[39], 2);
        assert_eq!(&raw[40..44], &[5, 0, 0, 0]); assert_eq!(&raw[44..48], &[0, 0, 0, 0]);
        assert_eq!(&raw[48..56], &[0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88]);
        assert_eq!(decode(&raw).unwrap(), r);
    }

    #[test]
    fn decode_refuses_wrong_magic_version_flags_reserved_currency() {
        let raw = encode(&sample());
        let mut t = raw; t[0] = b'X'; assert!(decode(&t).is_none());
        let mut t = raw; t[4] = 0x02; assert!(decode(&t).is_none());
        let mut t = raw; t[5] = 1; assert!(decode(&t).is_none(), "flags must be zero");
        let mut t = raw; t[45] = 1; assert!(decode(&t).is_none(), "reserved must be zero");
        let mut t = raw; t[36] = b'a'; assert!(decode(&t).is_none(), "currency must be A-Z");
        assert!(record::decode(&raw).is_none(), "a voucher is never a card record");
    }

    #[test]
    fn mac_covers_product_price_count_and_uid() {
        let mut r = sample();
        let raw = seal(&key(), &uid(), &mut r);
        assert!(verify(&key(), &uid(), &raw));
        for i in [24usize, 31, 32, 36, 39, 40, 8] { let mut t = raw; t[i] ^= 1; assert!(!verify(&key(), &uid(), &t), "byte {i}"); }
        assert!(!verify(&key(), &[0x04, 0xEB, 0xBB, 0x02, 0xCD, 0x2A, 0x81], &raw));
    }

    #[test]
    fn slot_rules_match_the_card_record() {
        let (k, u) = (key(), uid());
        let mut r0 = sample(); let a0 = seal(&k, &u, &mut r0);
        let mut r1 = sample(); r1.counter = 1; r1.count = 0; let b1 = seal(&k, &u, &mut r1);
        assert_eq!(select_slot_diag(&k, &u, &a0, &b1).0, SlotPick::B);
        assert_eq!(select_slot_diag(&k, &u, &b1, &a0).0, SlotPick::A);
        assert_eq!(select_slot_diag(&k, &u, &a0, &a0).0, SlotPick::A);
        let mut r0b = sample(); r0b.issue_price = 1; let a0b = seal(&k, &u, &mut r0b);
        assert_eq!(select_slot_diag(&k, &u, &a0, &a0b).0, SlotPick::Ambiguous);
        let zero = [0u8; 64];
        let d = select_slot_diag(&k, &u, &zero, &zero);
        assert_eq!((d.0, d.2.reason), (SlotPick::None, SlotNoneReason::NoValidSlot));
        let mut bad = a0; bad[4] = 0x09;
        assert_eq!(select_slot_diag(&k, &u, &bad, &zero).2.reason, SlotNoneReason::BadVersion);
        let mut ro = sample(); let other = seal(&k, &[0x04, 0xEB, 0xBB, 0x02, 0xCD, 0x2A, 0x81], &mut ro);
        let d = select_slot_diag(&k, &u, &other, &zero);
        assert_eq!(d.2.reason, SlotNoneReason::MacFail); assert_eq!(d.2.merchant_hint, Some(sample().merchant_id));
    }

    #[test]
    fn a_card_record_is_not_a_voucher_slot() {
        let (k, u) = (key(), uid());
        let mut c = record::DemoCardRecord { version: record::VERSION_DEMO, scheme: record::SCHEME_CKB_MILLI, writer_id: 0,
            reserve_id: [0xA0; 32], balance_units: 1000, counter: 1, merchant_id: [0x11; 8], mac: [0; 8] };
        let card = record::seal(&k, &u, &mut c);
        let d = select_slot_diag(&k, &u, &card, &[0u8; 64]);
        assert_eq!((d.0, d.2.reason), (SlotPick::None, SlotNoneReason::NoValidSlot));
    }

    #[test]
    fn product_code_normalisation() {
        assert_eq!(normalise_product_code("  cof01 \t").as_deref(), Some("COF01"));
        assert_eq!(normalise_product_code("0123").as_deref(), Some("0123"), "leading zero kept");
        assert_eq!(normalise_product_code("Flat-White_1").as_deref(), Some("FLAT-WHITE_1"));
        assert_eq!(normalise_product_code("a b").as_deref(), Some("A B"), "inner space kept");
        assert_eq!(normalise_product_code(&"a".repeat(31)).map(|s| s.len()), Some(31));
        assert!(normalise_product_code("").is_none());
        assert!(normalise_product_code("   ").is_none());
        assert!(normalise_product_code(&"a".repeat(32)).is_none());
        assert!(normalise_product_code("A\u{7}B").is_none());
        assert_eq!(product_hash(" cof01"), product_hash("COF01"));
        assert_ne!(product_hash("0123"), product_hash("123"));
    }

    #[test]
    fn voucher_id_is_the_reserve_id_prefix_and_staff_key_id_is_stable() {
        let th = [7u8; 32];
        assert_eq!(voucher_id(&th, 0), crate::prepaid::reserve::reserve_id(&th, 0)[..16]);
        let a = staff_key_id(&uid(), &[1u8; 32]);
        assert_eq!(a, crate::prepaid::reserve::ckb_blake2b(&[&uid()[..], &[1u8; 32][..]].concat())[..8]);
        assert_ne!(a, staff_key_id(&uid(), &[2u8; 32]));
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd ~/wyltek-wallet/rust-core && cargo test --lib prepaid::voucher 2>&1 | tail -5`
Expected: compile errors: `cannot find function encode` / `VoucherRecord`.

- [ ] **Step 3: Implement** (put this above the test module)

```rust
//! VoucherRecord: the 64-byte item-voucher record (vouchers spec §4). Same two slots, slot rules and K_debit
//! as the card record; the magic differs so neither can pass as the other. Byte oracle for the POS (V2).
use super::cmac::cmac8;
use super::record::{subtle_ct::ct_eq8, SlotDiag, SlotNoneReason, SlotPick, RECORD_SIZE};
use super::reserve::{ckb_blake2b, reserve_id};

pub const MAGIC: [u8; 4] = *b"BBV1";
pub const VERSION: u8 = 0x01;
pub const PRODUCT_CODE_MAX: usize = 31;
const MAC_OFF: usize = 56;

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct VoucherRecord {
    pub writer_id: u16, pub voucher_id: [u8; 16], pub product_hash: [u8; 8], pub issue_price: u32,
    pub currency: [u8; 3], pub count: u8, pub counter: u32, pub merchant_id: [u8; 8], pub mac: [u8; 8],
}

pub fn encode(r: &VoucherRecord) -> [u8; RECORD_SIZE] {
    let mut raw = [0u8; RECORD_SIZE];
    raw[0..4].copy_from_slice(&MAGIC);
    raw[4] = VERSION;                                   // raw[5] flags = 0
    raw[6..8].copy_from_slice(&r.writer_id.to_le_bytes());
    raw[8..24].copy_from_slice(&r.voucher_id);
    raw[24..32].copy_from_slice(&r.product_hash);
    raw[32..36].copy_from_slice(&r.issue_price.to_le_bytes());
    raw[36..39].copy_from_slice(&r.currency);
    raw[39] = r.count;
    raw[40..44].copy_from_slice(&r.counter.to_le_bytes()); // raw[44..48] reserved = 0
    raw[48..56].copy_from_slice(&r.merchant_id);
    raw[56..64].copy_from_slice(&r.mac);
    raw
}

/// Structural decode only. Does NOT check the MAC; use `verify`.
pub fn decode(raw: &[u8; RECORD_SIZE]) -> Option<VoucherRecord> {
    if raw[0..4] != MAGIC || raw[4] != VERSION || raw[5] != 0 || raw[44..48] != [0; 4] { return None; }
    if !raw[36..39].iter().all(|b| b.is_ascii_uppercase()) { return None; }
    Some(VoucherRecord {
        writer_id: u16::from_le_bytes([raw[6], raw[7]]),
        voucher_id: raw[8..24].try_into().unwrap(),
        product_hash: raw[24..32].try_into().unwrap(),
        issue_price: u32::from_le_bytes(raw[32..36].try_into().unwrap()),
        currency: raw[36..39].try_into().unwrap(),
        count: raw[39],
        counter: u32::from_le_bytes(raw[40..44].try_into().unwrap()),
        merchant_id: raw[48..56].try_into().unwrap(),
        mac: raw[56..64].try_into().unwrap(),
    })
}

pub fn compute_mac(key: &[u8; 16], uid: &[u8; 7], raw: &[u8; RECORD_SIZE]) -> [u8; 8] {
    let mut msg = [0u8; 7 + MAC_OFF];
    msg[..7].copy_from_slice(uid);
    msg[7..].copy_from_slice(&raw[..MAC_OFF]);
    cmac8(key, &msg)
}

pub fn verify(key: &[u8; 16], uid: &[u8; 7], raw: &[u8; RECORD_SIZE]) -> bool {
    ct_eq8(&compute_mac(key, uid, raw), raw[MAC_OFF..].try_into().unwrap())
}

pub fn seal(key: &[u8; 16], uid: &[u8; 7], r: &mut VoucherRecord) -> [u8; RECORD_SIZE] {
    r.mac = [0; 8];
    let raw = encode(r);
    r.mac = compute_mac(key, uid, &raw);
    encode(r)
}

/// The card record's rules (record.rs `select_slot_diag`) over voucher records. BadVersion = magic "BBV1" but
/// structurally invalid; MacFail carries the slot's clear-text merchant_id as an UNAUTHENTICATED hint.
pub fn select_slot_diag(key: &[u8; 16], uid: &[u8; 7], a: &[u8; RECORD_SIZE], b: &[u8; RECORD_SIZE]) -> (SlotPick, Option<VoucherRecord>, SlotDiag) {
    let va = decode(a).filter(|_| verify(key, uid, a));
    let vb = decode(b).filter(|_| verify(key, uid, b));
    let none_diag = SlotDiag { reason: SlotNoneReason::NoValidSlot, merchant_hint: None };
    match (va, vb) {
        (None, None) => {
            let bad_ver = |x: &[u8; RECORD_SIZE]| x[0..4] == MAGIC && decode(x).is_none();
            if bad_ver(a) || bad_ver(b) {
                return (SlotPick::None, None, SlotDiag { reason: SlotNoneReason::BadVersion, merchant_hint: None });
            }
            match [a, b].into_iter().find_map(|x| decode(x).map(|r| r.merchant_id)) {
                Some(m) => (SlotPick::None, None, SlotDiag { reason: SlotNoneReason::MacFail, merchant_hint: Some(m) }),
                None => (SlotPick::None, None, none_diag),
            }
        }
        (Some(ra), None) => (SlotPick::A, Some(ra), none_diag),
        (None, Some(rb)) => (SlotPick::B, Some(rb), none_diag),
        (Some(ra), Some(rb)) => {
            if ra.counter > rb.counter { (SlotPick::A, Some(ra), none_diag) }
            else if rb.counter > ra.counter { (SlotPick::B, Some(rb), none_diag) }
            else if a == b { (SlotPick::A, Some(ra), none_diag) }
            else { (SlotPick::Ambiguous, None, none_diag) }
        }
    }
}

/// Spec §4.1: trim ASCII whitespace at both ends, upper-case ASCII letters, keep every other byte.
/// None if the result is empty, longer than 31 bytes, or contains an ASCII control byte.
pub fn normalise_product_code(code: &str) -> Option<String> {
    let s = code.trim_matches(|c: char| c.is_ascii_whitespace()).to_ascii_uppercase();
    if s.is_empty() || s.len() > PRODUCT_CODE_MAX || s.bytes().any(|b| b < 0x20 || b == 0x7F) { return None; }
    Some(s)
}

pub fn product_hash(code: &str) -> Option<[u8; 8]> {
    normalise_product_code(code).map(|s| ckb_blake2b(s.as_bytes())[..8].try_into().unwrap())
}

pub fn voucher_id(tx_hash: &[u8; 32], index: u32) -> [u8; 16] { reserve_id(tx_hash, index)[..16].try_into().unwrap() }

/// Spec §5.1: first 8 bytes of ckb-blake2b-256(uid ‖ sig).
pub fn staff_key_id(uid: &[u8; 7], sig: &[u8; 32]) -> [u8; 8] {
    let mut m = [0u8; 39];
    m[..7].copy_from_slice(uid); m[7..].copy_from_slice(sig);
    ckb_blake2b(&m)[..8].try_into().unwrap()
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd ~/wyltek-wallet/rust-core && cargo test --lib prepaid:: 2>&1 | tail -3`
Expected: `test result: ok.` with every pre-existing prepaid test still passing.

- [ ] **Step 5: Mutation probe (MAC covers the product).** In `compute_mac`, temporarily zero `msg[7 + 24..7 + 32]` before `cmac8`. Run `cargo test --lib prepaid::voucher::tests::mac_covers_product_price_count_and_uid` → FAIL. Restore it and run again → PASS.

- [ ] **Step 6: Commit**

```bash
cd ~/wyltek-wallet && git add rust-core/src/prepaid/voucher.rs rust-core/src/prepaid/mod.rs
git commit -m "feat(prepaid): BBV1 voucher record, product-code hash, voucher and staff key ids

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: rust-core journal kinds 0x03 and 0x04

**Files:**
- Modify:
  - `rust-core/src/prepaid/journal.rs`
  - `rust-core/src/prepaid/verifier.rs` (test helper only)
  - `rust-core/src/prepaid/vectors.rs` (struct literal only)
- Test: inline in `journal.rs`

**Interfaces:**
- Produces:
  - `KIND_VOUCHER_REDEEM: u8 = 0x03`, `KIND_PRICE_OVERRIDE: u8 = 0x04`
  - `JournalEntry` gains `pub pad: [u8; 12]`
  - `journal::is_known_kind(u8) -> bool`
  - `journal::mac_ok(&key, &raw) -> bool` (MAC only, any kind)
  - `decode` and `verify` now accept kinds 3/4 with their shape rules

- [ ] **Step 1: Write the failing tests** (append to `journal.rs` tests)

```rust
    fn redeem() -> JournalEntry {
        let mut rid = [0u8; 32]; rid[..16].copy_from_slice(&[0xC0; 16]); rid[16..24].copy_from_slice(&[0x5A; 8]);
        let mut pad = [0u8; 12]; pad[..8].copy_from_slice(&[0x77; 8]);
        JournalEntry { kind: KIND_VOUCHER_REDEEM, reserve_id: rid, tmc: 1, amount_units: 1, balance_after: 0, pad, ..sample() }
    }
    fn override_() -> JournalEntry {
        let mut rid = [0u8; 32]; rid[..8].copy_from_slice(&[0x5A; 8]);
        let mut pad = [0u8; 12]; pad[..8].copy_from_slice(&[0x77; 8]); pad[8..11].copy_from_slice(b"AUD");
        JournalEntry { kind: KIND_PRICE_OVERRIDE, reserve_id: rid, tmc: 0, amount_units: 500, balance_after: 0, pad, ..sample() }
    }

    #[test]
    fn redeem_and_override_round_trip_and_verify() {
        for mut e in [redeem(), override_()] {
            let raw = attest(&key(), &mut e);
            assert_eq!(&raw[84..96], &e.pad);
            assert_eq!(decode(&raw).unwrap(), e);
            assert!(verify(&key(), &raw));
            for i in 84..96 { let mut t = raw; t[i] ^= 1; assert!(!verify(&key(), &t), "pad byte {i} is MAC-covered"); }
        }
    }

    #[test]
    fn redeem_shape_rules() {
        let bad = |f: fn(&mut JournalEntry)| { let mut e = redeem(); f(&mut e); let raw = attest(&key(), &mut e); verify(&key(), &raw) };
        assert!(!bad(|e| e.amount_units = 2), "a redeem is exactly one use");
        assert!(!bad(|e| e.balance_after = -1));
        assert!(!bad(|e| e.balance_after = 255));
        assert!(!bad(|e| e.reserve_id[30] = 1), "reserve_id tail must be zero");
        assert!(!bad(|e| e.pad = [0; 12]), "key_id must be non-zero");
        assert!(!bad(|e| e.pad[11] = 1));
    }

    #[test]
    fn override_shape_rules() {
        let bad = |f: fn(&mut JournalEntry)| { let mut e = override_(); f(&mut e); let raw = attest(&key(), &mut e); verify(&key(), &raw) };
        assert!(!bad(|e| e.amount_units = -1));
        assert!(!bad(|e| e.balance_after = -1));
        assert!(!bad(|e| e.tmc = 1));
        assert!(!bad(|e| e.reserve_id[8] = 1));
        assert!(!bad(|e| e.pad[8] = b'a'), "currency must be A-Z");
        assert!(!bad(|e| e.pad[11] = 1));
        assert!(!bad(|e| e.pad[..8].copy_from_slice(&[0; 8])));
    }

    #[test]
    fn debit_with_nonzero_pad_still_refused_and_unknown_kind_is_mac_checkable() {
        let mut e = sample(); e.pad[0] = 1; let raw = attest(&key(), &mut e);
        assert!(decode(&raw).is_none() && !verify(&key(), &raw));
        let mut e = sample(); e.kind = 0x05; let raw = attest(&key(), &mut e);
        assert!(!is_known_kind(raw[1]) && decode(&raw).is_none());
        assert!(mac_ok(&key(), &raw), "MAC is kind-independent");
        let mut k2 = key(); k2[0] ^= 1; assert!(!mac_ok(&k2, &raw));
    }
```

Also update `sample()` in the same test module to add `pad: [0; 12]`.

- [ ] **Step 2: Run them to verify they fail**

Run: `cd ~/wyltek-wallet/rust-core && cargo test --lib prepaid::journal 2>&1 | tail -5`
Expected: compile errors (`no field pad`, `KIND_VOUCHER_REDEEM`).

- [ ] **Step 3: Implement.** In `journal.rs`:

```rust
pub const KIND_VOUCHER_REDEEM: u8 = 0x03;
pub const KIND_PRICE_OVERRIDE: u8 = 0x04;

pub fn is_known_kind(kind: u8) -> bool { (KIND_DEBIT..=KIND_PRICE_OVERRIDE).contains(&kind) }
```

Add `pub pad: [u8; 12],` as the last field of `JournalEntry`. In `encode`, replace the trailing comment with `r[84..96].copy_from_slice(&e.pad);`. Replace `decode` and `verify` with:

```rust
pub fn decode(r: &[u8; ENTRY_SIZE]) -> Option<JournalEntry> {
    if r[0] != VERSION_DEMO || !is_known_kind(r[1]) || r[19] != 0 { return None; }
    let e = JournalEntry {
        version: r[0], kind: r[1], terminal_id: u16::from_le_bytes([r[2], r[3]]),
        merchant_id: r[4..12].try_into().unwrap(), uid: r[12..19].try_into().unwrap(),
        reserve_id: r[20..52].try_into().unwrap(),
        tmc: u32::from_le_bytes(r[52..56].try_into().unwrap()),
        amount_units: i32::from_le_bytes(r[56..60].try_into().unwrap()),
        balance_after: i32::from_le_bytes(r[60..64].try_into().unwrap()),
        ts: u32::from_le_bytes(r[64..68].try_into().unwrap()),
        tmv: r[68..76].try_into().unwrap(), sale_ref: r[76..84].try_into().unwrap(),
        pad: r[84..96].try_into().unwrap(),
    };
    shape_ok(&e).then_some(e)
}

/// Per-kind byte rules (vouchers spec §7). Kinds 1/2: pad all zero, as before.
fn shape_ok(e: &JournalEntry) -> bool {
    let key_id_set = e.pad[..8].iter().any(|&b| b != 0);
    match e.kind {
        KIND_DEBIT | KIND_REFUND => e.pad == [0; 12],
        KIND_VOUCHER_REDEEM => e.reserve_id[24..].iter().all(|&b| b == 0) && key_id_set && e.pad[8..].iter().all(|&b| b == 0),
        KIND_PRICE_OVERRIDE => e.reserve_id[8..].iter().all(|&b| b == 0) && e.tmc == 0 && key_id_set
            && e.pad[8..11].iter().all(|b| b.is_ascii_uppercase()) && e.pad[11] == 0,
        _ => false,
    }
}

/// MAC only, whatever the kind: lets the verifier tell an unknown-but-authentic kind from a forgery.
pub fn mac_ok(key: &[u8; 16], raw: &[u8; ENTRY_SIZE]) -> bool {
    ct_eq8(&mac_over(key, raw), raw[TMV_OFF..TMV_OFF + 8].try_into().unwrap())
}

/// MAC must verify AND the amounts must fit the kind.
pub fn verify(key: &[u8; 16], raw: &[u8; ENTRY_SIZE]) -> bool {
    let Some(e) = decode(raw) else { return false; };
    let amounts_ok = match e.kind {
        KIND_DEBIT => e.amount_units > 0,
        KIND_REFUND => e.amount_units < 0,
        KIND_VOUCHER_REDEEM => e.amount_units == 1 && (0..=254).contains(&e.balance_after),
        KIND_PRICE_OVERRIDE => e.amount_units >= 0 && e.balance_after >= 0,
        _ => false,
    };
    mac_ok(key, raw) & amounts_ok
}
```

Then add `pad: [0; 12]` to every other `JournalEntry { … }` literal. Find them with `grep -rn "JournalEntry {" rust-core/src`. Expect `verifier.rs` (test helper `entry`) and `vectors.rs` (`mk_e`). In `decode_rejects_bad_version_kind_pad`, the `raw[1] = 0x03` case still returns `None` (a debit sample has no key_id). Leave it, and add `let mut raw = encode(&sample()); raw[1] = 0x05; assert!(decode(&raw).is_none());`.

- [ ] **Step 4: Run all rust-core tests**

Run: `cd ~/wyltek-wallet/rust-core && cargo test 2>&1 | grep -E "test result|FAILED|panicked" | head`
Expected: every `test result: ok`, including `prepaid_vectors_pinned` (the D1 fixture is unchanged because its pads are zero).

- [ ] **Step 5: Commit**

```bash
git add rust-core/src/prepaid/journal.rs rust-core/src/prepaid/verifier.rs rust-core/src/prepaid/vectors.rs
git commit -m "feat(prepaid): journal kinds 0x03 voucher redeem and 0x04 price override

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: rust-core verifier: redeems, overrides, unsupported kinds

**Files:**
- Modify: `rust-core/src/prepaid/verifier.rs`
- Test: inline in `verifier.rs`

**Interfaces:**
- Consumes:
  - `journal::{is_known_kind, mac_ok, KIND_VOUCHER_REDEEM, KIND_PRICE_OVERRIDE}`
  - `reserve::ckb_blake2b`
- Produces:
  - `struct VoucherState { voucher_id: [u8;16], count: u8, void: bool, settled: Vec<(u32, [u8;32])> }`
  - `Verdict` gains `DoubleRedeem`, `UnknownVoucher`, `VoucherVoid`, `UnsupportedKind`
  - `struct RedeemOut { index: u32, terminal_id: u16, voucher_id: [u8;16], counter: u32, entry_hash: [u8;32] }`
  - `struct OverrideOut { index: u32, terminal_id: u16, product_hash: [u8;8], old_price: i32, new_price: i32, currency: [u8;3], key_id: [u8;8], entry_hash: [u8;32] }`
  - `BatchResult` gains `redeems: Vec<RedeemOut>` and `overrides: Vec<OverrideOut>`
  - `verify_batch(key, entries, cards, vouchers: &[VoucherState])`

- [ ] **Step 1: Write the failing tests.** Add to the test module, and change every existing `verify_batch(k, b, cards)` call in it to `verify_batch(k, b, cards, &[])`.

```rust
    use crate::prepaid::journal::{KIND_PRICE_OVERRIDE, KIND_VOUCHER_REDEEM};
    use crate::prepaid::reserve::ckb_blake2b;

    fn vid(b: u8) -> [u8; 16] { [b; 16] }
    fn redeem(v: u8, counter: u32, terminal: u16) -> Vec<u8> {
        let mut rid = [0u8; 32]; rid[..16].copy_from_slice(&vid(v)); rid[16..24].copy_from_slice(&[0x5A; 8]);
        let mut pad = [0u8; 12]; pad[..8].copy_from_slice(&[0x77; 8]);
        let mut e = JournalEntry { version: 0x02, kind: KIND_VOUCHER_REDEEM, terminal_id: terminal, merchant_id: [0x11; 8],
            uid: [4, 1, 2, 3, 4, 5, 6], reserve_id: rid, tmc: counter, amount_units: 1, balance_after: 0, ts: terminal as u32,
            tmv: [0; 8], sale_ref: [0; 8], pad };
        attest(&key(), &mut e).to_vec()
    }
    fn vstate(v: u8, count: u8, void: bool, settled: &[Vec<u8>]) -> VoucherState {
        VoucherState { voucher_id: vid(v), count, void,
            settled: settled.iter().map(|raw| (u32::from_le_bytes(raw[52..56].try_into().unwrap()), ckb_blake2b(raw))).collect() }
    }

    #[test]
    fn a_redeem_is_accepted_once_and_reported() {
        let r = verify_batch(&key(), &redeem(1, 1, 3), &[], &[vstate(1, 1, false, &[])]).unwrap();
        assert_eq!(r.verdicts, vec![Verdict::Accepted]);
        assert_eq!(r.redeems.len(), 1);
        assert_eq!((r.redeems[0].index, r.redeems[0].terminal_id, r.redeems[0].voucher_id, r.redeems[0].counter), (0, 3, vid(1), 1));
        assert_eq!(r.redeems[0].entry_hash, ckb_blake2b(&redeem(1, 1, 3)));
        assert!(r.nets.is_empty(), "redeems never touch card nets");
    }

    #[test]
    fn identical_resend_is_duplicate_but_a_different_entry_on_a_settled_counter_is_double_redeem() {
        let first = redeem(1, 1, 3);
        let v = vstate(1, 0, false, &[first.clone()]);
        assert_eq!(verify_batch(&key(), &first, &[], &[v.clone()]).unwrap().verdicts, vec![Verdict::Duplicate]);
        let copy_elsewhere = redeem(1, 1, 9);                     // same counter, other terminal → different bytes
        assert_eq!(verify_batch(&key(), &copy_elsewhere, &[], &[v]).unwrap().verdicts, vec![Verdict::DoubleRedeem]);
    }

    #[test]
    fn within_one_batch_same_counter_twice_is_double_redeem_and_identical_is_duplicate() {
        let a = redeem(1, 1, 3); let b = redeem(1, 1, 9);
        let r = verify_batch(&key(), &[a.clone(), b, a].concat(), &[], &[vstate(1, 5, false, &[])]).unwrap();
        assert_eq!(r.verdicts, vec![Verdict::Accepted, Verdict::DoubleRedeem, Verdict::Duplicate]);
        assert_eq!(r.redeems.len(), 1);
    }

    #[test]
    fn more_redeems_than_uses_left_is_double_redeem() {
        let r = verify_batch(&key(), &[redeem(1, 1, 3), redeem(1, 2, 3)].concat(), &[], &[vstate(1, 1, false, &[])]).unwrap();
        assert_eq!(r.verdicts, vec![Verdict::Accepted, Verdict::DoubleRedeem]);
    }

    #[test]
    fn out_of_order_counter_in_a_batch_is_stale_and_unknown_and_void_vouchers_are_named() {
        let r = verify_batch(&key(), &[redeem(1, 3, 3), redeem(1, 2, 3)].concat(), &[], &[vstate(1, 5, false, &[])]).unwrap();
        assert_eq!(r.verdicts, vec![Verdict::Accepted, Verdict::StaleCounter]);
        assert_eq!(verify_batch(&key(), &redeem(2, 1, 3), &[], &[]).unwrap().verdicts, vec![Verdict::UnknownVoucher]);
        assert_eq!(verify_batch(&key(), &redeem(1, 1, 3), &[], &[vstate(1, 1, true, &[])]).unwrap().verdicts, vec![Verdict::VoucherVoid]);
    }

    #[test]
    fn overrides_are_accepted_and_reported() {
        let mut rid = [0u8; 32]; rid[..8].copy_from_slice(&[0x5A; 8]);
        let mut pad = [0u8; 12]; pad[..8].copy_from_slice(&[0x77; 8]); pad[8..11].copy_from_slice(b"AUD");
        let mut e = JournalEntry { version: 0x02, kind: KIND_PRICE_OVERRIDE, terminal_id: 4, merchant_id: [0x11; 8], uid: [4, 9, 9, 9, 9, 9, 9],
            reserve_id: rid, tmc: 0, amount_units: 500, balance_after: 0, ts: 0, tmv: [0; 8], sale_ref: [0; 8], pad };
        let raw = attest(&key(), &mut e).to_vec();
        let r = verify_batch(&key(), &raw, &[], &[]).unwrap();
        assert_eq!(r.verdicts, vec![Verdict::Accepted]);
        let o = &r.overrides[0];
        assert_eq!((o.index, o.terminal_id, o.product_hash, o.old_price, o.new_price, o.currency, o.key_id), (0, 4, [0x5A; 8], 500, 0, *b"AUD", [0x77; 8]));
        assert_eq!(o.entry_hash, ckb_blake2b(&raw));
    }

    #[test]
    fn unknown_kind_with_good_mac_is_unsupported_and_with_bad_mac_is_bad_mac() {
        let mut e = JournalEntry { version: 0x02, kind: 0x07, terminal_id: 1, merchant_id: [0x11; 8], uid: [4, 1, 2, 3, 4, 5, 6],
            reserve_id: [0; 32], tmc: 1, amount_units: 1, balance_after: 0, ts: 0, tmv: [0; 8], sale_ref: [0; 8], pad: [0; 12] };
        let raw = attest(&key(), &mut e).to_vec();
        assert_eq!(verify_batch(&key(), &raw, &[], &[]).unwrap().verdicts, vec![Verdict::UnsupportedKind]);
        let mut t = raw; t[30] ^= 1;
        assert_eq!(verify_batch(&key(), &t, &[], &[]).unwrap().verdicts, vec![Verdict::BadMac]);
    }
```

- [ ] **Step 2: Run them to verify they fail**

Run: `cd ~/wyltek-wallet/rust-core && cargo test --lib prepaid::verifier 2>&1 | tail -5`
Expected: compile errors (`VoucherState`, the 4th argument).

- [ ] **Step 3: Implement.** Replace the types and `verify_batch` in `verifier.rs` (keep the card path's logic byte for byte):

```rust
use super::journal::{self, ENTRY_SIZE, KIND_PRICE_OVERRIDE, KIND_VOUCHER_REDEEM};
use super::reserve::ckb_blake2b;
use std::collections::HashMap;

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct CardState { pub reserve_id: [u8; 32], pub balance_units: i32, pub settled_counter: u32, pub revoked: bool }

/// A voucher as the Vault index knows it. `settled` = every (counter, blake2b(entry)) already settled.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct VoucherState { pub voucher_id: [u8; 16], pub count: u8, pub void: bool, pub settled: Vec<(u32, [u8; 32])> }

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Verdict { Accepted, Duplicate, BadMac, UnknownCard, StaleCounter, OverBalance, DoubleRedeem, UnknownVoucher, VoucherVoid, UnsupportedKind }

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct CardNet { pub reserve_id: [u8; 32], pub net_units: i64, pub max_tmc: u32, pub accepted: u32 }
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct RedeemOut { pub index: u32, pub terminal_id: u16, pub voucher_id: [u8; 16], pub counter: u32, pub entry_hash: [u8; 32] }
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct OverrideOut { pub index: u32, pub terminal_id: u16, pub product_hash: [u8; 8], pub old_price: i32, pub new_price: i32,
    pub currency: [u8; 3], pub key_id: [u8; 8], pub entry_hash: [u8; 32] }

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct BatchResult { pub verdicts: Vec<Verdict>, pub nets: Vec<CardNet>, pub redeems: Vec<RedeemOut>, pub overrides: Vec<OverrideOut> }

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum BatchError { BadLength }

struct Running { net: i64, max_tmc: Option<u32>, accepted: u32 }
#[derive(Default)]
struct VoucherRun { max_counter: Option<u32>, accepted: u32, seen: HashMap<u32, [u8; 32]> }

pub fn verify_batch(key: &[u8; 16], entries: &[u8], cards: &[CardState], vouchers: &[VoucherState]) -> Result<BatchResult, BatchError> {
    if entries.len() % ENTRY_SIZE != 0 { return Err(BatchError::BadLength); }
    let index: HashMap<[u8; 32], &CardState> = cards.iter().map(|c| (c.reserve_id, c)).collect();
    let vindex: HashMap<[u8; 16], &VoucherState> = vouchers.iter().map(|v| (v.voucher_id, v)).collect();
    let mut running: HashMap<[u8; 32], Running> = HashMap::new();
    let mut vrun: HashMap<[u8; 16], VoucherRun> = HashMap::new();
    let mut order: Vec<[u8; 32]> = Vec::new();
    let mut verdicts = Vec::with_capacity(entries.len() / ENTRY_SIZE);
    let (mut redeems, mut overrides) = (Vec::new(), Vec::new());

    for (i, chunk) in entries.chunks_exact(ENTRY_SIZE).enumerate() {
        let raw: &[u8; ENTRY_SIZE] = chunk.try_into().unwrap();
        if !journal::verify(key, raw) {
            let unsupported = raw[0] == journal::VERSION_DEMO && !journal::is_known_kind(raw[1]) && journal::mac_ok(key, raw);
            verdicts.push(if unsupported { Verdict::UnsupportedKind } else { Verdict::BadMac });
            continue;
        }
        let e = journal::decode(raw).expect("verify implies decode");
        let hash = ckb_blake2b(raw);
        match e.kind {
            KIND_VOUCHER_REDEEM => {
                let v_id: [u8; 16] = e.reserve_id[..16].try_into().unwrap();
                let Some(v) = vindex.get(&v_id) else { verdicts.push(Verdict::UnknownVoucher); continue; };
                if v.void { verdicts.push(Verdict::VoucherVoid); continue; }
                if let Some((_, h)) = v.settled.iter().find(|(c, _)| *c == e.tmc) {
                    verdicts.push(if *h == hash { Verdict::Duplicate } else { Verdict::DoubleRedeem }); continue;
                }
                let run = vrun.entry(v_id).or_default();
                if let Some(h) = run.seen.get(&e.tmc) {
                    verdicts.push(if *h == hash { Verdict::Duplicate } else { Verdict::DoubleRedeem }); continue;
                }
                if run.max_counter.map_or(false, |m| e.tmc <= m) { verdicts.push(Verdict::StaleCounter); continue; }
                if run.accepted >= v.count as u32 { verdicts.push(Verdict::DoubleRedeem); continue; }
                run.max_counter = Some(e.tmc); run.accepted += 1; run.seen.insert(e.tmc, hash);
                redeems.push(RedeemOut { index: i as u32, terminal_id: e.terminal_id, voucher_id: v_id, counter: e.tmc, entry_hash: hash });
                verdicts.push(Verdict::Accepted);
            }
            KIND_PRICE_OVERRIDE => {
                overrides.push(OverrideOut { index: i as u32, terminal_id: e.terminal_id, product_hash: e.reserve_id[..8].try_into().unwrap(),
                    old_price: e.amount_units, new_price: e.balance_after, currency: e.pad[8..11].try_into().unwrap(),
                    key_id: e.pad[..8].try_into().unwrap(), entry_hash: hash });
                verdicts.push(Verdict::Accepted);
            }
            _ => {
                let card = match index.get(&e.reserve_id) {
                    Some(c) if !c.revoked => *c,
                    _ => { verdicts.push(Verdict::UnknownCard); continue; }
                };
                if e.tmc <= card.settled_counter { verdicts.push(Verdict::Duplicate); continue; }
                let run = running.entry(e.reserve_id).or_insert_with(|| {
                    order.push(e.reserve_id);
                    Running { net: 0, max_tmc: None, accepted: 0 }
                });
                if run.max_tmc.map_or(false, |m| e.tmc <= m) { verdicts.push(Verdict::StaleCounter); continue; }
                let proposed = run.net + e.amount_units as i64;
                if proposed > card.balance_units as i64 { verdicts.push(Verdict::OverBalance); continue; }
                run.net = proposed; run.max_tmc = Some(e.tmc); run.accepted += 1;
                verdicts.push(Verdict::Accepted);
            }
        }
    }

    let nets = order.into_iter().filter_map(|rid| {
        let r = &running[&rid];
        (r.accepted > 0).then(|| CardNet { reserve_id: rid, net_units: r.net, max_tmc: r.max_tmc.unwrap(), accepted: r.accepted })
    }).collect();
    Ok(BatchResult { verdicts, nets, redeems, overrides })
}
```

Update the doc comment at the top of the file: add "Redeems: MAC → known voucher → not void → settled counter (same hash = duplicate, else double_redeem) → batch counter → uses left. Overrides: MAC only."

- [ ] **Step 4: Run the tests**

Run: `cd ~/wyltek-wallet/rust-core && cargo test --lib prepaid::verifier 2>&1 | tail -3`
Expected: all pass. The `ffi.rs` call site still fails to compile; Task 5 fixes it. Use `cargo test --lib prepaid::verifier` alone until then. If it doesn't compile, temporarily pass `&[]` at `ffi.rs`'s `verify_batch` call and add `redeems: vec![], overrides: vec![]` to nothing else; Task 5 replaces it.

- [ ] **Step 5: Mutation probes**
  - **(a)** Replace the settled check's `if *h == hash { Duplicate } else { DoubleRedeem }` with plain `Verdict::Duplicate`. → `identical_resend_is_duplicate_but_a_different_entry…` FAILS.
  - **(b)** Delete the `run.accepted >= v.count` line. → `more_redeems_than_uses_left_is_double_redeem` FAILS.

  Restore both, and confirm green.

- [ ] **Step 6: Commit**

```bash
git add rust-core/src/prepaid/verifier.rs rust-core/src/prepaid/ffi.rs
git commit -m "feat(prepaid): settle voucher redeems and price overrides; unknown kinds answer unsupported

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: Voucher golden vectors, plus a journal-entry tool for the device checkpoint

**Files:**
- Modify: `rust-core/src/prepaid/vectors.rs` (add `emit_voucher`)
- Create:
  - `rust-core/examples/emit_prepaid_voucher_vectors.rs`
  - `rust-core/examples/prepaid_journal_entry.rs`
  - `rust-core/tests/fixtures/prepaid_voucher_vectors.txt` (generated)
  - `rust-core/tests/prepaid_voucher_vectors_pinned.rs`

**Interfaces:**
- Produces:
  - `vectors::emit_voucher() -> String`
  - The tool `cargo run --example prepaid_journal_entry -- <k_debit_hex> redeem <voucher_id_hex16B> <product_hash_hex8B> <counter> <count_after> <terminal_id> <key_id_hex8B>`, which prints one 96-byte entry as hex. Task 16 uses it.

- [ ] **Step 1: Write the pinned test first** (`rust-core/tests/prepaid_voucher_vectors_pinned.rs`)

```rust
//! The voucher fixture is the V2 POS's contract. Its SHA-256 is pinned here and (in V2) in blackbox-pos.
use sha2::{Digest, Sha256};
use wyltekwalletcore::prepaid::vectors;

const FIXTURE: &str = include_str!("fixtures/prepaid_voucher_vectors.txt");
const PINNED_SHA256: &str = "REPLACE_IN_STEP_4";

#[test]
fn emitter_reproduces_committed_voucher_fixture() { assert_eq!(vectors::emit_voucher(), FIXTURE); }

#[test]
fn voucher_fixture_sha256_is_pinned() {
    assert_eq!(hex::encode(Sha256::digest(FIXTURE.as_bytes())), PINNED_SHA256, "fixture changed: update this constant (and the POS copy in V2)");
}
```

- [ ] **Step 2: Add `emit_voucher` to `vectors.rs`**

```rust
use super::{verifier, voucher};

pub fn emit_voucher() -> String {
    let key: [u8; 16] = core::array::from_fn(|i| i as u8);
    let uid: [u8; 7] = [0x04, 0x95, 0x8C, 0xAA, 0x5C, 0x5E, 0x80];
    let merchant_id: [u8; 8] = [0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88];
    let voucher_id: [u8; 16] = core::array::from_fn(|i| 0xC0 + i as u8);
    let mut s = String::new();
    writeln!(s, "# prepaid voucher golden vectors v1 (generated by rust-core: cargo run --example emit_prepaid_voucher_vectors -- tests/fixtures/prepaid_voucher_vectors.txt)").unwrap();
    writeln!(s, "k_debit={}", hx(&key)).unwrap();
    writeln!(s, "uid={}", hx(&uid)).unwrap();
    for (i, input) in ["  cof01 \t", "0123", "Flat-White_1", "a b", &"a".repeat(31)].iter().enumerate() {
        let n = i + 1;
        writeln!(s, "code{n}.input_hex={}", hx(input.as_bytes())).unwrap();
        writeln!(s, "code{n}.normalised={}", voucher::normalise_product_code(input).unwrap()).unwrap();
        writeln!(s, "code{n}.product_hash={}", hx(&voucher::product_hash(input).unwrap())).unwrap();
    }
    for (i, input) in ["", "   ", &"a".repeat(32), "A\u{7}B"].iter().enumerate() {
        assert!(voucher::normalise_product_code(input).is_none(), "emitter self-check invalid code {i}");
        writeln!(s, "code_invalid{}.input_hex={}", i + 1, hx(input.as_bytes())).unwrap();
    }
    let ph = voucher::product_hash("COF01").unwrap();
    let mk = |count: u8, counter: u32, writer_id: u16| voucher::VoucherRecord { writer_id, voucher_id, product_hash: ph,
        issue_price: 450, currency: *b"AUD", count, counter, merchant_id, mac: [0; 8] };
    let mut raws = Vec::new();
    for (i, (count, counter, writer)) in [(1u8, 0u32, 0u16), (0, 1, 3)].iter().enumerate() {
        let mut r = mk(*count, *counter, *writer);
        let raw = voucher::seal(&key, &uid, &mut r);
        writeln!(s, "voucher{}.raw={}", i + 1, hx(&raw)).unwrap();
        writeln!(s, "voucher{}.mac8={}", i + 1, hx(&r.mac)).unwrap();
        raws.push(raw);
    }
    let zeros = [0u8; 64];
    let mut flags = raws[0]; flags[5] = 1;
    let card = { let mut c = record::DemoCardRecord { version: record::VERSION_DEMO, scheme: record::SCHEME_CKB_MILLI, writer_id: 0,
        reserve_id: [0xA0; 32], balance_units: 1000, counter: 1, merchant_id, mac: [0; 8] }; record::seal(&key, &uid, &mut c) };
    let cases: [(&[u8; 64], &[u8; 64], &str); 4] = [(&raws[0], &raws[1], "B"), (&flags, &zeros, "NONE_BAD_VERSION"), (&card, &zeros, "NONE_NO_VALID_SLOT"), (&raws[1], &raws[0], "A")];
    for (i, (a, b, want)) in cases.iter().enumerate() {
        let (pick, _, diag) = voucher::select_slot_diag(&key, &uid, a, b);
        let got = match (pick, diag.reason) {
            (record::SlotPick::A, _) => "A", (record::SlotPick::B, _) => "B",
            (record::SlotPick::None, record::SlotNoneReason::BadVersion) => "NONE_BAD_VERSION",
            (record::SlotPick::None, record::SlotNoneReason::NoValidSlot) => "NONE_NO_VALID_SLOT", _ => "OTHER" };
        assert_eq!(got, *want, "emitter self-check vslots.case{}", i + 1);
        writeln!(s, "vslots.case{}.a={}", i + 1, hx(*a)).unwrap();
        writeln!(s, "vslots.case{}.b={}", i + 1, hx(*b)).unwrap();
        writeln!(s, "vslots.case{}.pick={want}", i + 1).unwrap();
    }
    let key_id = voucher::staff_key_id(&uid, &[0x5Au8; 32]);
    writeln!(s, "staff.uid={}", hx(&uid)).unwrap();
    writeln!(s, "staff.sig={}", hx(&[0x5Au8; 32])).unwrap();
    writeln!(s, "staff.key_id={}", hx(&key_id)).unwrap();
    let mut rid = [0u8; 32]; rid[..16].copy_from_slice(&voucher_id); rid[16..24].copy_from_slice(&ph);
    let mut pad = [0u8; 12]; pad[..8].copy_from_slice(&key_id);
    let mut redeem = journal::JournalEntry { version: journal::VERSION_DEMO, kind: journal::KIND_VOUCHER_REDEEM, terminal_id: 3, merchant_id, uid,
        reserve_id: rid, tmc: 1, amount_units: 1, balance_after: 0, ts: 1_791_370_000, tmv: [0; 8], sale_ref: [0xDE, 0xAD, 0xBE, 0xEF, 1, 2, 3, 4], pad };
    let raw = journal::attest(&key, &mut redeem);
    writeln!(s, "entry_redeem.raw={}", hx(&raw)).unwrap();
    let mut orid = [0u8; 32]; orid[..8].copy_from_slice(&ph);
    let mut opad = [0u8; 12]; opad[..8].copy_from_slice(&key_id); opad[8..11].copy_from_slice(b"AUD");
    let mut ovr = journal::JournalEntry { kind: journal::KIND_PRICE_OVERRIDE, reserve_id: orid, tmc: 0, amount_units: 500, balance_after: 0, pad: opad, ..redeem.clone() };
    let raw = journal::attest(&key, &mut ovr);
    writeln!(s, "entry_override.raw={}", hx(&raw)).unwrap();
    let _ = verifier::Verdict::Accepted;               // the verdict set is pinned by the verifier tests, not this file
    s
}
```

- [ ] **Step 3: Add the examples**

`rust-core/examples/emit_prepaid_voucher_vectors.rs`:
```rust
//! cargo run --example emit_prepaid_voucher_vectors -- <output path>
fn main() {
    let path = std::env::args().nth(1).expect("usage: emit_prepaid_voucher_vectors <out.txt>");
    std::fs::write(&path, wyltekwalletcore::prepaid::vectors::emit_voucher()).expect("write");
    eprintln!("wrote {path}");
}
```

`rust-core/examples/prepaid_journal_entry.rs`:
```rust
//! Builds one attested journal entry for a manual settlement test (device checkpoint, no POS needed).
//! cargo run --example prepaid_journal_entry -- <k_debit> redeem <voucher_id16> <product_hash8> <counter> <count_after> <terminal_id> <key_id8>
//! cargo run --example prepaid_journal_entry -- <k_debit> override <product_hash8> <old_minor> <new_minor> <currency> <terminal_id> <key_id8>
use wyltekwalletcore::prepaid::journal::{attest, JournalEntry, KIND_PRICE_OVERRIDE, KIND_VOUCHER_REDEEM, VERSION_DEMO};

fn h<const N: usize>(s: &str) -> [u8; N] { hex::decode(s.trim_start_matches("0x")).unwrap().try_into().expect("length") }

fn main() {
    let a: Vec<String> = std::env::args().skip(1).collect();
    let key: [u8; 16] = h(&a[0]);
    let mut e = JournalEntry { version: VERSION_DEMO, kind: 0, terminal_id: 0, merchant_id: [0; 8], uid: [4, 0, 0, 0, 0, 0, 1],
        reserve_id: [0; 32], tmc: 0, amount_units: 0, balance_after: 0, ts: 0, tmv: [0; 8], sale_ref: [0; 8], pad: [0; 12] };
    match a[1].as_str() {
        "redeem" => {
            e.kind = KIND_VOUCHER_REDEEM;
            e.reserve_id[..16].copy_from_slice(&h::<16>(&a[2])); e.reserve_id[16..24].copy_from_slice(&h::<8>(&a[3]));
            e.tmc = a[4].parse().unwrap(); e.amount_units = 1; e.balance_after = a[5].parse().unwrap();
            e.terminal_id = a[6].parse().unwrap(); e.pad[..8].copy_from_slice(&h::<8>(&a[7]));
        }
        "override" => {
            e.kind = KIND_PRICE_OVERRIDE;
            e.reserve_id[..8].copy_from_slice(&h::<8>(&a[2]));
            e.amount_units = a[3].parse().unwrap(); e.balance_after = a[4].parse().unwrap();
            e.pad[8..11].copy_from_slice(a[5].as_bytes()); e.terminal_id = a[6].parse().unwrap(); e.pad[..8].copy_from_slice(&h::<8>(&a[7]));
        }
        other => panic!("kind {other}"),
    }
    println!("{}", hex::encode(attest(&key, &mut e)));
}
```

- [ ] **Step 4: Generate, pin, run**

```bash
cd ~/wyltek-wallet/rust-core
cargo run -q --example emit_prepaid_voucher_vectors -- tests/fixtures/prepaid_voucher_vectors.txt
sha256sum tests/fixtures/prepaid_voucher_vectors.txt
```
Paste the printed hash into `PINNED_SHA256`. Then run `cargo test --test prepaid_voucher_vectors_pinned --test prepaid_vectors_pinned 2>&1 | tail -3` → 4 passed. Also run `cargo run -q --example prepaid_journal_entry -- 000102030405060708090a0b0c0d0e0f redeem $(printf 'c0%.0s' {1..16}) 5a5a5a5a5a5a5a5a 1 0 3 7777777777777777 | wc -c` → `193` (192 hex + newline).

- [ ] **Step 5: Commit**

```bash
git add rust-core/src/prepaid/vectors.rs rust-core/examples/emit_prepaid_voucher_vectors.rs rust-core/examples/prepaid_journal_entry.rs rust-core/tests/fixtures/prepaid_voucher_vectors.txt rust-core/tests/prepaid_voucher_vectors_pinned.rs
git commit -m "test(prepaid): pinned voucher golden vectors and a journal-entry tool

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: FFI surface, bindings, Kotlin `Protocol` and fakes

**Files:**
- Modify:
  - `rust-core/src/prepaid/ffi.rs`
  - `wallet-core/src/main/java/com/wyltek/wallet/core/native/wyltekwalletcore.kt` (regenerated)
  - both `jniLibs/arm64-v8a/libwyltekwalletcore.so`
  - `app/src/main/java/com/wyltek/wallet/prepaid/Ports.kt`
  - `app/src/main/java/com/wyltek/wallet/prepaid/NativeProtocol.kt`
  - `app/src/main/java/com/wyltek/wallet/prepaid/PrepaidService.kt` (the `verifyBatch` call only)
  - `app/src/test/java/com/wyltek/wallet/prepaid/fakes/FakeProtocol.kt`
  - every test constructing `BatchResultFfi(…)`
  - `docs/superpowers/plans/2026-10-13-prepaid-demo-d3-vault.md` (line 758)
- Test: `rust-core/src/prepaid/ffi.rs` tests; `app/src/test/java/com/wyltek/wallet/prepaid/FakeVoucherProtocolTest.kt`

**Interfaces:**
- Produces (uniffi, Kotlin names in brackets):
  - Records:
    - `VoucherRecordInfo { writer_id: u16, voucher_id_hex, product_hash_hex, issue_price: u32, currency: String, count: u8, counter: u32, merchant_id_hex }`
    - `VoucherSlotResult { pick: SlotPickFfi, record: Option<VoucherRecordInfo>, reason: SlotReason, merchant_hint_hex: Option<String> }`
    - `SettledRedeemFfi { counter: u32, entry_hash_hex }`
    - `VoucherStateFfi { voucher_id_hex, count: u8, void: bool, settled: Vec<SettledRedeemFfi> }`
    - `RedeemFfi { index: u32, terminal_id: u16, voucher_id_hex, counter: u32, entry_hash_hex }`
    - `OverrideFfi { index: u32, terminal_id: u16, product_hash_hex, old_price: i32, new_price: i32, currency, key_id_hex, entry_hash_hex }`
    - `BatchResultFfi { verdicts, nets, redeems: Vec<RedeemFfi>, overrides: Vec<OverrideFfi> }`
  - `JournalVerdict` gains `DoubleRedeem`, `UnknownVoucher`, `VoucherVoid`, `UnsupportedKind`.
  - Functions:
    - `prepaid_voucher_select_slot` [`prepaidVoucherSelectSlot`]
    - `prepaid_voucher_seal` [`prepaidVoucherSeal`]
    - `prepaid_normalise_product_code` [`prepaidNormaliseProductCode`]
    - `prepaid_product_hash` [`prepaidProductHash`]
    - `prepaid_voucher_id` [`prepaidVoucherId`]
    - `prepaid_staff_key_id` [`prepaidStaffKeyId`]
    - `prepaid_verify_batch(k, entries, cards, vouchers)`
- Kotlin `Protocol` gains:
  - `voucherSelectSlot(kHex, uidHex, raw128): VoucherSlotResult`
  - `voucherSeal(kHex, uidHex, rec: VoucherRecordInfo): ByteArray`
  - `normaliseProductCode(code): String` and `productHash(code): String` (both throw `PrepaidException.InvalidInput`)
  - `voucherId(txHashHex, index: UInt): String`
  - `staffKeyId(uidHex, sigHex): String`
  - `verifyBatch(kHex, entriesHex, cards, vouchers: List<VoucherStateFfi>)`

- [ ] **Step 1: Write the failing Rust FFI test** (append to `ffi.rs` tests)

```rust
    #[test]
    fn voucher_seal_select_and_ids_cross_the_boundary() {
        let rec = VoucherRecordInfo { writer_id: 0, voucher_id_hex: "c0".repeat(16), product_hash_hex: prepaid_product_hash(" cof01".into()).unwrap(),
            issue_price: 450, currency: "AUD".into(), count: 1, counter: 0, merchant_id_hex: "11".repeat(8) };
        let raw64 = prepaid_voucher_seal(K.into(), UID.into(), rec.clone()).unwrap();
        let r = prepaid_voucher_select_slot(K.into(), UID.into(), format!("{}{}", raw64, "00".repeat(64))).unwrap();
        assert_eq!(r.pick, SlotPickFfi::A); assert_eq!(r.record.unwrap(), rec);
        assert!(prepaid_select_slot(K.into(), UID.into(), format!("{}{}", raw64, "00".repeat(64))).unwrap().record.is_none(), "a voucher is not a card");
        assert_eq!(prepaid_normalise_product_code(" cof01".into()).unwrap(), "COF01");
        assert!(prepaid_product_hash("".into()).is_err());
        let bad = VoucherRecordInfo { currency: "aud".into(), ..rec };
        assert!(prepaid_voucher_seal(K.into(), UID.into(), bad).is_err());
        assert_eq!(prepaid_voucher_id("07".repeat(32), 0).unwrap(), prepaid_reserve_id("07".repeat(32), 0).unwrap()[..32]);
        assert_eq!(prepaid_staff_key_id(UID.into(), "5a".repeat(32)).unwrap().len(), 16);
        let r = prepaid_verify_batch(K.into(), "".into(), vec![], vec![]).unwrap();
        assert!(r.redeems.is_empty() && r.overrides.is_empty());
    }
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd ~/wyltek-wallet/rust-core && cargo test --lib prepaid::ffi 2>&1 | tail -3`
Expected: compile errors (`VoucherRecordInfo`).

- [ ] **Step 3: Implement in `ffi.rs`.** Add `use super::voucher;`. Add `fn hex16b(h: &str) -> Result<[u8; 16], PrepaidError> { fixed::<16>(h, "voucher_id") }` (`hex16` is the key parser). Then:

```rust
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct VoucherRecordInfo { pub writer_id: u16, pub voucher_id_hex: String, pub product_hash_hex: String, pub issue_price: u32,
    pub currency: String, pub count: u8, pub counter: u32, pub merchant_id_hex: String }
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct VoucherSlotResult { pub pick: SlotPickFfi, pub record: Option<VoucherRecordInfo>, pub reason: SlotReason, pub merchant_hint_hex: Option<String> }
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct SettledRedeemFfi { pub counter: u32, pub entry_hash_hex: String }
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct VoucherStateFfi { pub voucher_id_hex: String, pub count: u8, pub void: bool, pub settled: Vec<SettledRedeemFfi> }
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct RedeemFfi { pub index: u32, pub terminal_id: u16, pub voucher_id_hex: String, pub counter: u32, pub entry_hash_hex: String }
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct OverrideFfi { pub index: u32, pub terminal_id: u16, pub product_hash_hex: String, pub old_price: i32, pub new_price: i32,
    pub currency: String, pub key_id_hex: String, pub entry_hash_hex: String }

fn pick_ffi(p: record::SlotPick) -> SlotPickFfi { match p { record::SlotPick::None => SlotPickFfi::None, record::SlotPick::A => SlotPickFfi::A, record::SlotPick::B => SlotPickFfi::B, record::SlotPick::Ambiguous => SlotPickFfi::Ambiguous } }
fn reason_ffi(r: record::SlotNoneReason) -> SlotReason { match r { record::SlotNoneReason::NoValidSlot => SlotReason::NoValidSlot, record::SlotNoneReason::BadVersion => SlotReason::BadVersion, record::SlotNoneReason::MacFail => SlotReason::MacFail } }
fn currency_of(s: &str) -> Result<[u8; 3], PrepaidError> {
    let b = s.as_bytes();
    if b.len() != 3 || !b.iter().all(|c| c.is_ascii_uppercase()) { return Err(PrepaidError::InvalidInput("currency must be three letters A-Z".into())); }
    Ok([b[0], b[1], b[2]])
}
const CODE_ERR: &str = "product code must be 1-31 printable characters";

#[uniffi::export]
pub fn prepaid_voucher_select_slot(k_debit_hex: String, uid_hex: String, raw128_hex: String) -> Result<VoucherSlotResult, PrepaidError> {
    let key = hex16(&k_debit_hex)?; let uid = hex7(&uid_hex)?;
    let raw: [u8; 128] = fixed::<128>(&raw128_hex, "slots")?;
    let (pick, rec, diag) = voucher::select_slot_diag(&key, &uid, raw[..64].try_into().unwrap(), raw[64..].try_into().unwrap());
    Ok(VoucherSlotResult { pick: pick_ffi(pick), reason: reason_ffi(diag.reason), merchant_hint_hex: diag.merchant_hint.map(hex::encode),
        record: rec.map(|r| VoucherRecordInfo { writer_id: r.writer_id, voucher_id_hex: hex::encode(r.voucher_id), product_hash_hex: hex::encode(r.product_hash),
            issue_price: r.issue_price, currency: String::from_utf8_lossy(&r.currency).into_owned(), count: r.count, counter: r.counter,
            merchant_id_hex: hex::encode(r.merchant_id) }) })
}

#[uniffi::export]
pub fn prepaid_voucher_seal(k_debit_hex: String, uid_hex: String, record: VoucherRecordInfo) -> Result<String, PrepaidError> {
    let key = hex16(&k_debit_hex)?; let uid = hex7(&uid_hex)?;
    let mut r = voucher::VoucherRecord { writer_id: record.writer_id, voucher_id: hex16b(&record.voucher_id_hex)?,
        product_hash: fixed::<8>(&record.product_hash_hex, "product_hash")?, issue_price: record.issue_price, currency: currency_of(&record.currency)?,
        count: record.count, counter: record.counter, merchant_id: fixed::<8>(&record.merchant_id_hex, "merchant_id")?, mac: [0; 8] };
    Ok(hex::encode(voucher::seal(&key, &uid, &mut r)))
}

#[uniffi::export]
pub fn prepaid_normalise_product_code(code: String) -> Result<String, PrepaidError> {
    voucher::normalise_product_code(&code).ok_or_else(|| PrepaidError::InvalidInput(CODE_ERR.into()))
}
#[uniffi::export]
pub fn prepaid_product_hash(code: String) -> Result<String, PrepaidError> {
    voucher::product_hash(&code).map(hex::encode).ok_or_else(|| PrepaidError::InvalidInput(CODE_ERR.into()))
}
#[uniffi::export]
pub fn prepaid_voucher_id(tx_hash_hex: String, index: u32) -> Result<String, PrepaidError> {
    Ok(hex::encode(voucher::voucher_id(&fixed::<32>(&tx_hash_hex, "tx_hash")?, index)))
}
#[uniffi::export]
pub fn prepaid_staff_key_id(uid_hex: String, sig_hex: String) -> Result<String, PrepaidError> {
    Ok(hex::encode(voucher::staff_key_id(&hex7(&uid_hex)?, &fixed::<32>(&sig_hex, "signature")?)))
}
```

Extend `JournalVerdict` with `DoubleRedeem, UnknownVoucher, VoucherVoid, UnsupportedKind`. Extend `BatchResultFfi` with `pub redeems: Vec<RedeemFfi>, pub overrides: Vec<OverrideFfi>`. Change `prepaid_verify_batch` to take `vouchers: Vec<VoucherStateFfi>`:

```rust
    let vstates = vouchers.iter().map(|v| Ok(verifier::VoucherState { voucher_id: hex16b(&v.voucher_id_hex)?, count: v.count, void: v.void,
        settled: v.settled.iter().map(|s| Ok((s.counter, fixed::<32>(&s.entry_hash_hex, "entry_hash")?))).collect::<Result<Vec<_>, PrepaidError>>()? }))
        .collect::<Result<Vec<_>, PrepaidError>>()?;
    let r = verifier::verify_batch(&key, &entries, &states, &vstates).map_err(|_| PrepaidError::InvalidInput("entries length is not a multiple of 96".into()))?;
```

Map the four new verdicts in the `verdicts` mapping. Add:

```rust
        redeems: r.redeems.into_iter().map(|x| RedeemFfi { index: x.index, terminal_id: x.terminal_id, voucher_id_hex: hex::encode(x.voucher_id), counter: x.counter, entry_hash_hex: hex::encode(x.entry_hash) }).collect(),
        overrides: r.overrides.into_iter().map(|x| OverrideFfi { index: x.index, terminal_id: x.terminal_id, product_hash_hex: hex::encode(x.product_hash),
            old_price: x.old_price, new_price: x.new_price, currency: String::from_utf8_lossy(&x.currency).into_owned(), key_id_hex: hex::encode(x.key_id), entry_hash_hex: hex::encode(x.entry_hash) }).collect(),
```

Fix the existing `plan_and_verify_batch_cross_the_boundary` test: give its `prepaid_verify_batch` calls a 4th `vec![]`.

- [ ] **Step 4: Run all of rust-core**

Run: `cd ~/wyltek-wallet/rust-core && cargo test 2>&1 | grep -E "test result|FAILED" | head`
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
grep -cE "fun prepaidVoucherSelectSlot|fun prepaidVoucherSeal|fun prepaidProductHash|fun prepaidNormaliseProductCode|fun prepaidVoucherId|fun prepaidStaffKeyId|class VoucherStateFfi|class OverrideFfi|DOUBLE_REDEEM" ../wallet-core/src/main/java/com/wyltek/wallet/core/native/wyltekwalletcore.kt
```
Expected: the last command prints `9`.

In `docs/superpowers/plans/2026-10-13-prepaid-demo-d3-vault.md`, delete the line that copies the bindings into `../app/src/main/java/…/wyltekwalletcore.kt` (line 758). In its place, put the comment line `# bindings live ONLY in wallet-core (a second copy in :app caused a NoSuchFieldError crash; BindingsSingleSourceTest)`.

- [ ] **Step 6: Extend `Protocol`, `NativeProtocol`, `FakeProtocol`; fix the call site and old tests**

In `Ports.kt`, `interface Protocol`, replace the `verifyBatch` line with these and add:

```kotlin
    fun verifyBatch(kHex: String, entriesHex: String, cards: List<CardStateFfi>, vouchers: List<VoucherStateFfi>): BatchResultFfi
    fun voucherSelectSlot(kHex: String, uidHex: String, raw128: ByteArray): VoucherSlotResult
    fun voucherSeal(kHex: String, uidHex: String, rec: VoucherRecordInfo): ByteArray
    /** Throws PrepaidException.InvalidInput for an empty, over-long or control-character code. */
    fun normaliseProductCode(code: String): String
    fun productHash(code: String): String
    fun voucherId(txHashHex: String, index: UInt): String
    fun staffKeyId(uidHex: String, sigHex: String): String
```

In `NativeProtocol.kt`:

```kotlin
    override fun verifyBatch(kHex: String, entriesHex: String, cards: List<CardStateFfi>, vouchers: List<VoucherStateFfi>) = prepaidVerifyBatch(kHex, entriesHex, cards, vouchers)
    override fun voucherSelectSlot(kHex: String, uidHex: String, raw128: ByteArray) = prepaidVoucherSelectSlot(kHex, uidHex, hex(raw128))
    override fun voucherSeal(kHex: String, uidHex: String, rec: VoucherRecordInfo) = unhex(prepaidVoucherSeal(kHex, uidHex, rec))
    override fun normaliseProductCode(code: String) = prepaidNormaliseProductCode(code)
    override fun productHash(code: String) = prepaidProductHash(code)
    override fun voucherId(txHashHex: String, index: UInt) = prepaidVoucherId(txHashHex, index)
    override fun staffKeyId(uidHex: String, sigHex: String) = prepaidStaffKeyId(uidHex, sigHex)
```

In `PrepaidService.settleLocked`, change the call to `protocol.verifyBatch(kHex, hexBody, eligible.map { … }, emptyList())`; Task 13 wires the real list. In `verdictName`, add:

```kotlin
        JournalVerdict.DOUBLE_REDEEM -> "double_redeem"; JournalVerdict.UNKNOWN_VOUCHER -> "unknown_voucher"
        JournalVerdict.VOUCHER_VOID -> "voucher_void"; JournalVerdict.UNSUPPORTED_KIND -> "retry"
```

In `FakeProtocol.kt`, add these (the same fake MAC as card records):

```kotlin
    var lastVouchers: List<VoucherStateFfi> = emptyList()
    override fun verifyBatch(kHex: String, entriesHex: String, cards: List<CardStateFfi>, vouchers: List<VoucherStateFfi>): BatchResultFfi {
        lastCards = cards; lastVouchers = vouchers; return verifyResult
    }
    private fun decodeV(raw: ByteArray): VoucherRecordInfo? {
        if (String(raw, 0, 4, Charsets.US_ASCII) != "BBV1" || raw[4] != 0x01.toByte() || raw[5] != 0.toByte()) return null
        val bb = java.nio.ByteBuffer.wrap(raw).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        return VoucherRecordInfo(writerId = bb.getShort(6).toUShort(), voucherIdHex = hex(raw.copyOfRange(8, 24)), productHashHex = hex(raw.copyOfRange(24, 32)),
            issuePrice = bb.getInt(32).toUInt(), currency = String(raw, 36, 3, Charsets.US_ASCII), count = raw[39].toUByte(), counter = bb.getInt(40).toUInt(),
            merchantIdHex = hex(raw.copyOfRange(48, 56)))
    }
    private fun validV(uidHex: String, raw: ByteArray) = decodeV(raw)?.takeIf { mac(uidHex, raw).contentEquals(raw.copyOfRange(56, 64)) }
    override fun voucherSelectSlot(kHex: String, uidHex: String, raw128: ByteArray): VoucherSlotResult {
        val a = raw128.copyOfRange(0, 64); val b = raw128.copyOfRange(64, 128)
        val va = validV(uidHex, a); val vb = validV(uidHex, b)
        val none = VoucherSlotResult(SlotPickFfi.NONE, null, SlotReason.NO_VALID_SLOT, null)
        return when {
            va == null && vb == null -> {
                if (listOf(a, b).any { String(it, 0, 4, Charsets.US_ASCII) == "BBV1" && decodeV(it) == null }) none.copy(reason = SlotReason.BAD_VERSION)
                else listOf(a, b).firstNotNullOfOrNull { decodeV(it) }?.let { none.copy(reason = SlotReason.MAC_FAIL, merchantHintHex = it.merchantIdHex) } ?: none
            }
            va != null && vb == null -> VoucherSlotResult(SlotPickFfi.A, va, SlotReason.NO_VALID_SLOT, null)
            va == null -> VoucherSlotResult(SlotPickFfi.B, vb, SlotReason.NO_VALID_SLOT, null)
            va!!.counter > vb!!.counter -> VoucherSlotResult(SlotPickFfi.A, va, SlotReason.NO_VALID_SLOT, null)
            vb.counter > va.counter -> VoucherSlotResult(SlotPickFfi.B, vb, SlotReason.NO_VALID_SLOT, null)
            a.contentEquals(b) -> VoucherSlotResult(SlotPickFfi.A, va, SlotReason.NO_VALID_SLOT, null)
            else -> VoucherSlotResult(SlotPickFfi.AMBIGUOUS, null, SlotReason.NO_VALID_SLOT, null)
        }
    }
    override fun voucherSeal(kHex: String, uidHex: String, rec: VoucherRecordInfo): ByteArray {
        require(rec.currency.matches(Regex("[A-Z]{3}"))) { "currency" }
        val raw = ByteArray(64)
        "BBV1".toByteArray(Charsets.US_ASCII).copyInto(raw, 0); raw[4] = 0x01
        raw[6] = rec.writerId.toByte(); raw[7] = (rec.writerId.toInt() shr 8).toByte()
        unhex(rec.voucherIdHex).copyInto(raw, 8); unhex(rec.productHashHex).copyInto(raw, 24)
        le32(rec.issuePrice.toInt()).copyInto(raw, 32); rec.currency.toByteArray(Charsets.US_ASCII).copyInto(raw, 36)
        raw[39] = rec.count.toByte(); le32(rec.counter.toInt()).copyInto(raw, 40)
        unhex(rec.merchantIdHex).copyInto(raw, 48); mac(uidHex, raw).copyInto(raw, 56)
        return raw
    }
    override fun normaliseProductCode(code: String): String {
        val s = code.trim { it == ' ' || it == '\t' || it == '\n' || it == '\r' || it == '\u000B' || it == '\u000C' }.uppercase(java.util.Locale.ROOT)
        val b = s.toByteArray(Charsets.UTF_8)
        if (b.isEmpty() || b.size > 31 || b.any { it in 0..0x1F || it == 0x7F.toByte() }) throw PrepaidException.InvalidInput("product code must be 1-31 printable characters")
        return s
    }
    override fun productHash(code: String): String {
        val d = java.security.MessageDigest.getInstance("SHA-256"); d.update(normaliseProductCode(code).toByteArray(Charsets.UTF_8)); return hex(d.digest().copyOf(8))
    }
    override fun voucherId(txHashHex: String, index: UInt) = reserveId(txHashHex, index).take(32)
    override fun staffKeyId(uidHex: String, sigHex: String): String {
        val d = java.security.MessageDigest.getInstance("SHA-256"); d.update((uidHex + sigHex).lowercase().toByteArray()); return hex(d.digest().copyOf(8))
    }
```

(`FakeProtocol`'s upper-casing uses `Locale.ROOT` for the fake only. Real normalisation is rust-core's ASCII-only rule, pinned by vectors.)

Fix every `BatchResultFfi(` construction in tests to pass `emptyList(), emptyList()` for the two new fields. Find them with `grep -rn "BatchResultFfi(" app/src/test`.

- [ ] **Step 7: Write the fake round-trip test** (`app/src/test/java/com/wyltek/wallet/prepaid/FakeVoucherProtocolTest.kt`)

```kotlin
package com.wyltek.wallet.prepaid

import com.wyltek.wallet.core.native.SlotPickFfi
import com.wyltek.wallet.core.native.VoucherRecordInfo
import com.wyltek.wallet.prepaid.fakes.FakeProtocol
import org.junit.Assert.*
import org.junit.Test

class FakeVoucherProtocolTest {
    private val p = FakeProtocol(); private val uid = "04958caa5c5e80"
    private val rec = VoucherRecordInfo(0u, "c0".repeat(16), "5a".repeat(8), 450u, "AUD", 1u, 0u, "1122334455667788")
    @Test fun voucher_seal_select_round_trips_and_is_never_a_card() {
        val raw = p.voucherSeal("", uid, rec) + ByteArray(64)
        assertEquals(SlotPickFfi.A, p.voucherSelectSlot("", uid, raw).pick)
        assertEquals(rec, p.voucherSelectSlot("", uid, raw).record)
        assertNull(p.selectSlot("", uid, raw).record)
        assertNull(p.voucherSelectSlot("", uid, p.seal("", uid, com.wyltek.wallet.core.native.CardRecordInfo(0u, "a0".repeat(32), 1, 0u, "1122334455667788")) + ByteArray(64)).record)
    }
    @Test fun product_codes_normalise_and_refuse() {
        assertEquals("COF01", p.normaliseProductCode("  cof01 "))
        assertEquals(p.productHash("cof01"), p.productHash(" COF01"))
        assertThrows(PrepaidException::class.java) { p.normaliseProductCode("   ") }
        assertThrows(PrepaidException::class.java) { p.normaliseProductCode("a".repeat(32)) }
    }
}
```

- [ ] **Step 8: Run the app JVM tests**

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
Expected: `failures 0`, and the test count is 217 + 2.

- [ ] **Step 9: Commit**

```bash
git add rust-core/src/prepaid/ffi.rs wallet-core/src/main/java/com/wyltek/wallet/core/native/wyltekwalletcore.kt app/src/main/java/com/wyltek/wallet/prepaid/Ports.kt app/src/main/java/com/wyltek/wallet/prepaid/NativeProtocol.kt app/src/main/java/com/wyltek/wallet/prepaid/PrepaidService.kt app/src/test docs/superpowers/plans/2026-10-13-prepaid-demo-d3-vault.md
git commit -m "feat(prepaid): voucher FFI surface, regenerated bindings, Protocol and fakes

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

(The `.so` files are gitignored. They are rebuilt above but not committed.)

---

### Task 6: secp128r1 verify-only check for NXP originality signatures

**Files:**
- Create:
  - `app/src/main/java/com/wyltek/wallet/prepaid/Secp128r1.kt`
  - `app/src/test/resources/secp128r1/gen_vectors.py`
  - `app/src/test/resources/secp128r1/vectors.json` (generated)
- Modify: `app/src/main/java/com/wyltek/wallet/prepaid/PrepaidHex.kt` (add `hexBytes`, `bytesHex`)
- Test: `app/src/test/java/com/wyltek/wallet/prepaid/Secp128r1Test.kt`

**Interfaces:**
- Produces:
  - `object Secp128r1 { NXP_NTAG21X_PUBKEY; data class Pt; fun pointOf(ByteArray): Pt?; fun verify(Pt, ByteArray, ByteArray): Boolean; fun verifyNtagOriginality(uid: ByteArray, sig: ByteArray): Boolean }`
  - `fun hexBytes(hex: String): ByteArray`, `fun bytesHex(b: ByteArray): String`

- [ ] **Step 1: Write the vector generator** (`app/src/test/resources/secp128r1/gen_vectors.py`). It's an independent oracle: python-ecdsa 0.19's SECP128r1, signing the raw 7-byte UID with no hash.

```python
"""Independent secp128r1 vectors for Secp128r1Test. Regenerate: python3 -I gen_vectors.py > vectors.json"""
import hashlib, json
from ecdsa import SigningKey
from ecdsa.curves import SECP128r1
from ecdsa.util import sigencode_string

sk = SigningKey.from_secret_exponent(0x1234567890ABCDEF1122334455667788 % SECP128r1.order, curve=SECP128r1)
vk = sk.get_verifying_key()
pub = "04" + vk.to_string().hex()
out = {"pub": pub, "cases": []}
for uid_hex in ["04958caa5c5e80", "04ebbb02cd2a81", "040c5f825c1391"]:
    uid = bytes.fromhex(uid_hex)
    sig = sk.sign_digest_deterministic(uid, hashfunc=hashlib.sha256, sigencode=sigencode_string, allow_truncate=True)
    assert vk.verify_digest(sig, uid, allow_truncate=True)
    out["cases"].append({"uid": uid_hex, "sig": sig.hex()})
print(json.dumps(out, indent=1))
```

Run: `cd ~/wyltek-wallet/app/src/test/resources/secp128r1 && python3 -I gen_vectors.py > vectors.json && head -3 vectors.json`
Expected: a JSON whose `pub` starts with `"04` and has 66 hex chars.

- [ ] **Step 2: Write the failing test** (`app/src/test/java/com/wyltek/wallet/prepaid/Secp128r1Test.kt`)

```kotlin
package com.wyltek.wallet.prepaid

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class Secp128r1Test {
    private val v = Json.parseToJsonElement(javaClass.getResource("/secp128r1/vectors.json")!!.readText()).jsonObject
    private val pub = Secp128r1.pointOf(hexBytes(v["pub"]!!.jsonPrimitive.content))!!
    private val cases = v["cases"]!!.jsonArray.map { it.jsonObject["uid"]!!.jsonPrimitive.content to it.jsonObject["sig"]!!.jsonPrimitive.content }

    @Test fun python_ecdsa_signatures_verify() {
        for ((uid, sig) in cases) assertTrue(uid, Secp128r1.verify(pub, hexBytes(uid), hexBytes(sig)))
    }
    @Test fun any_flipped_byte_or_wrong_uid_fails() {
        val (uid, sig) = cases[0]
        for (i in 0 until 32) { val s = hexBytes(sig); s[i] = (s[i].toInt() xor 1).toByte(); assertFalse("sig byte $i", Secp128r1.verify(pub, hexBytes(uid), s)) }
        assertFalse(Secp128r1.verify(pub, hexBytes(cases[1].first), hexBytes(sig)))
        assertFalse(Secp128r1.verify(pub, hexBytes(uid), ByteArray(32)))
        assertFalse(Secp128r1.verify(pub, hexBytes(uid), ByteArray(31)))
    }
    @Test fun nxp_key_is_on_the_curve_and_rejects_these_test_signatures() {
        assertNotNull(Secp128r1.pointOf(hexBytes(Secp128r1.NXP_NTAG21X_PUBKEY)))
        for ((uid, sig) in cases) assertFalse(Secp128r1.verifyNtagOriginality(hexBytes(uid), hexBytes(sig)))
        assertFalse(Secp128r1.verifyNtagOriginality(ByteArray(4), ByteArray(32)))
    }
    @Test fun points_off_the_curve_are_refused() {
        val bad = hexBytes(Secp128r1.NXP_NTAG21X_PUBKEY); bad[32] = (bad[32].toInt() xor 1).toByte()
        assertNull(Secp128r1.pointOf(bad))
        assertNull(Secp128r1.pointOf(ByteArray(33)))
    }
}
```

Task 16 adds `real_ring_signature_verifies_against_nxp_key` once a real capture exists.

- [ ] **Step 3: Run it to verify it fails**

Run: `cd ~/wyltek-wallet && ./gradlew :app:testDebugUnitTest --tests '*Secp128r1Test*' -q 2>&1 | tail -3`
Expected: compile error (`Secp128r1`, `hexBytes`).

- [ ] **Step 4: Implement.** Append to `PrepaidHex.kt`:

```kotlin
/** Even-length hex (optional 0x) → bytes. Throws IllegalArgumentException on anything else. */
fun hexBytes(hex: String): ByteArray {
    val h = hex.removePrefix("0x").removePrefix("0X")
    require(h.length % 2 == 0 && h.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) { "not hex" }
    return ByteArray(h.length / 2) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
fun bytesHex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }
```

`app/src/main/java/com/wyltek/wallet/prepaid/Secp128r1.kt`:

```kotlin
package com.wyltek.wallet.prepaid

import java.math.BigInteger

/**
 * Verify-only ECDSA on secp128r1 (SEC 2), for NXP NTAG21x originality signatures (AN11350, `READ_SIG`).
 * The signed message is the 7-byte UID, unhashed. Inputs are public, so constant time is not a goal.
 * The wallet ships no library with this curve (BouncyCastle is not a dependency).
 */
object Secp128r1 {
    private val P = BigInteger("FFFFFFFDFFFFFFFFFFFFFFFFFFFFFFFF", 16)
    private val A = BigInteger("FFFFFFFDFFFFFFFFFFFFFFFFFFFFFFFC", 16)
    private val B = BigInteger("E87579C11079F43DD824993C2CEE5ED3", 16)
    private val N = BigInteger("FFFFFFFE0000000075A30D1B9038A115", 16)
    private val G = Pt(BigInteger("161FF7528B899B2D0C28607CA52C5B86", 16), BigInteger("CF5AC8395BAFEB13C02DA292DDED7A83", 16))

    /** NXP NTAG21x originality key (AN11350), 04 ‖ X ‖ Y. */
    const val NXP_NTAG21X_PUBKEY = "04494E1A386D3D3CFE3DC10E5DE68A499B1C202DB5B132393E89ED19FE5BE8BC61"

    data class Pt(val x: BigInteger, val y: BigInteger)

    private fun onCurve(p: Pt) = p.y.pow(2).subtract(p.x.pow(3).add(A.multiply(p.x)).add(B)).mod(P).signum() == 0

    /** Null = point at infinity. */
    private fun add(p: Pt?, q: Pt?): Pt? {
        if (p == null) return q
        if (q == null) return p
        val l = if (p.x == q.x) {
            if (p.y.add(q.y).mod(P).signum() == 0) return null
            p.x.pow(2).multiply(BigInteger.valueOf(3)).add(A).multiply(p.y.shiftLeft(1).modInverse(P)).mod(P)
        } else q.y.subtract(p.y).multiply(q.x.subtract(p.x).mod(P).modInverse(P)).mod(P)
        val x = l.pow(2).subtract(p.x).subtract(q.x).mod(P)
        return Pt(x, l.multiply(p.x.subtract(x)).subtract(p.y).mod(P))
    }

    private fun mul(k: BigInteger, p: Pt): Pt? {
        var r: Pt? = null; var a: Pt? = p
        for (i in 0 until k.bitLength()) { if (k.testBit(i)) r = add(r, a); a = add(a, a) }
        return r
    }

    fun pointOf(uncompressed: ByteArray): Pt? {
        if (uncompressed.size != 33 || uncompressed[0] != 0x04.toByte()) return null
        val p = Pt(BigInteger(1, uncompressed.copyOfRange(1, 17)), BigInteger(1, uncompressed.copyOfRange(17, 33)))
        return p.takeIf { it.x < P && it.y < P && onCurve(it) }
    }

    /** [sig] = r(16) ‖ s(16). [msg] is used whole when shorter than the order (a 7-byte UID always is). */
    fun verify(pub: Pt, msg: ByteArray, sig: ByteArray): Boolean {
        if (sig.size != 32) return false
        val r = BigInteger(1, sig.copyOfRange(0, 16)); val s = BigInteger(1, sig.copyOfRange(16, 32))
        if (r.signum() == 0 || s.signum() == 0 || r >= N || s >= N) return false
        val excess = msg.size * 8 - N.bitLength()
        val e = BigInteger(1, msg).let { if (excess > 0) it.shiftRight(excess) else it }
        val w = s.modInverse(N)
        val q = add(mul(e.multiply(w).mod(N), G), mul(r.multiply(w).mod(N), pub)) ?: return false
        return q.x.mod(N) == r
    }

    private val nxp: Pt by lazy { pointOf(hexBytes(NXP_NTAG21X_PUBKEY))!! }

    fun verifyNtagOriginality(uid: ByteArray, sig: ByteArray): Boolean = uid.size == 7 && verify(nxp, uid, sig)
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests '*Secp128r1Test*' -q 2>&1 | tail -3`
Expected: BUILD SUCCESSFUL. Check `app/build/test-results/testDebugUnitTest/TEST-com.wyltek.wallet.prepaid.Secp128r1Test.xml` shows `tests="4" failures="0"`.

- [ ] **Step 6: Mutation probe.** Change `return q.x.mod(N) == r` to `return true`. → `any_flipped_byte_or_wrong_uid_fails` FAILS. Restore it.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/wyltek/wallet/prepaid/Secp128r1.kt app/src/main/java/com/wyltek/wallet/prepaid/PrepaidHex.kt app/src/test/resources/secp128r1 app/src/test/java/com/wyltek/wallet/prepaid/Secp128r1Test.kt
git commit -m "feat(prepaid): verify-only secp128r1 check for NXP NTAG21x originality signatures

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: DB v6: vouchers, staff keys, settled journal entries

**Files:**
- Create:
  - `app/src/main/java/com/wyltek/wallet/prepaid/db/VoucherEntities.kt`
  - `app/src/main/java/com/wyltek/wallet/prepaid/db/VoucherDao.kt`
  - `app/src/main/java/com/wyltek/wallet/prepaid/VoucherStore.kt`
  - `app/src/test/java/com/wyltek/wallet/prepaid/fakes/InMemoryVoucherStore.kt`
- Modify:
  - `app/src/main/java/com/wyltek/wallet/agent/db/AgentDatabase.kt`
  - `app/src/main/java/com/wyltek/wallet/prepaid/db/PrepaidEntities.kt` (new kind constants)
- Test: `app/src/test/java/com/wyltek/wallet/agent/db/MigrationSqlMatchesRoomTest.kt`

**Interfaces:**
- Produces:
  - **Constants:**
    - States `VOUCHER_ISSUING|ACTIVE|USED|CLOSED|VOID` (`"issuing","active","used","closed","void"`)
    - Kinds `KIND_VOUCHER_ISSUE="voucher_issue"`, `KIND_VOUCHER_VOID="voucher_void"`, `KIND_VOUCHER_REDEEM="voucher_redeem"`, `KIND_VOUCHER_CLOSE="voucher_close"`, `KIND_PRICE_OVERRIDE="price_override"`, `KIND_STAFF_ENROL="staff_enrol"`, `KIND_STAFF_REVOKE="staff_revoke"`
  - **Entities:** `VoucherEntity`, `StaffKeyEntity`, `JournalSeenEntity` (fields below)
  - **`interface VoucherStore`:** `insertVoucher`, `updateVoucher`, `deleteIssuing(voucherId): Int`, `voucher(id)`, `voucherByUid(uid)` (newest), `allVouchers()`, `vouchersWithPending()`, `vouchersFlow()`, `applyRedeems(vouchers, seen)` (one transaction), `seenForVoucher(id)`, `seen(hash)`, `insertSeen(e)`, `insertStaffKey`, `updateStaffKey`, `staffKey(id)`, `activeStaffKeys()`, `staffKeysFlow()`
  - `RoomVoucherStore(db)`, `InMemoryVoucherStore`
  - `MIGRATION_5_6`, `MIGRATION_5_6_SQL: List<String>`

- [ ] **Step 1: Write the entities and constants**

`app/src/main/java/com/wyltek/wallet/prepaid/db/VoucherEntities.kt`:

```kotlin
package com.wyltek.wallet.prepaid.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

const val VOUCHER_ISSUING = "issuing"; const val VOUCHER_ACTIVE = "active"; const val VOUCHER_USED = "used"
const val VOUCHER_CLOSED = "closed"; const val VOUCHER_VOID = "void"
/** States whose cell is still live and whose tag the Vault still stands behind. */
val VOUCHER_LIVE = setOf(VOUCHER_ISSUING, VOUCHER_ACTIVE, VOUCHER_USED)

/** Vouchers spec §3, §8. `voucherId` = 32 hex (16 bytes); `outpoint` = the voucher cell. `countLeft` counts down
 *  per accepted redeem (the Vault's own count; a terminal's `balance_after` is not trusted). */
@Entity(tableName = "prepaid_vouchers", indices = [Index(value = ["uid"])])
data class VoucherEntity(
    @PrimaryKey val voucherId: String,
    val uid: String,
    val productCode: String,
    val productName: String,
    val productHash: String,
    val issuePriceMinor: Long,
    val currency: String,
    val countIssued: Int,
    val countLeft: Int,
    val outpoint: String,
    val state: String,
    val tagCounter: Long = 0,
    val pendingKind: String? = null,
    val pendingTxHash: String? = null,
    val pendingActionId: String? = null,
    val pendingCommitted: Boolean = false,
    val pendingSince: Long? = null,
    val issuedAt: Long,
    val updatedAt: Long,
)

/** Spec §5.1. Nothing is written to the tag; `sigHex` is its NXP originality signature. */
@Entity(tableName = "prepaid_staff_keys")
data class StaffKeyEntity(
    @PrimaryKey val keyId: String,
    val uid: String,
    val sigHex: String,
    val label: String,
    val enrolledAt: Long,
    val revokedAt: Long? = null,
)

/** Every settled voucher-redeem and price-override entry, by blake2b of its 96 bytes: the settled set the verifier
 *  needs for `duplicate` vs `double_redeem`, and the dedupe for override audit rows. */
@Entity(tableName = "prepaid_journal_seen", indices = [Index(value = ["voucherId"])])
data class JournalSeenEntity(
    @PrimaryKey val entryHash: String,
    val kind: String,
    val voucherId: String?,
    val counter: Long?,
    val terminalId: Int,
    val seenAt: Long,
)
```

Append to `PrepaidEntities.kt` (next to the other kinds):

```kotlin
const val KIND_VOUCHER_ISSUE = "voucher_issue"; const val KIND_VOUCHER_VOID = "voucher_void"; const val KIND_VOUCHER_REDEEM = "voucher_redeem"
const val KIND_VOUCHER_CLOSE = "voucher_close"; const val KIND_PRICE_OVERRIDE = "price_override"
const val KIND_STAFF_ENROL = "staff_enrol"; const val KIND_STAFF_REVOKE = "staff_revoke"
```

- [ ] **Step 2: DAO and store**

`app/src/main/java/com/wyltek/wallet/prepaid/db/VoucherDao.kt`:

```kotlin
package com.wyltek.wallet.prepaid.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
abstract class VoucherDao {
    @Insert(onConflict = OnConflictStrategy.ABORT) abstract suspend fun insertVoucher(v: VoucherEntity)
    @Update abstract suspend fun updateVoucher(v: VoucherEntity)
    @Update abstract suspend fun updateVouchers(v: List<VoucherEntity>)
    @Query("DELETE FROM prepaid_vouchers WHERE voucherId = :id AND state = 'issuing'") abstract suspend fun deleteIssuing(id: String): Int
    @Query("SELECT * FROM prepaid_vouchers WHERE voucherId = :id LIMIT 1") abstract suspend fun voucher(id: String): VoucherEntity?
    @Query("SELECT * FROM prepaid_vouchers WHERE uid = :uid ORDER BY issuedAt DESC LIMIT 1") abstract suspend fun voucherByUid(uid: String): VoucherEntity?
    @Query("SELECT * FROM prepaid_vouchers ORDER BY updatedAt DESC") abstract suspend fun allVouchers(): List<VoucherEntity>
    @Query("SELECT * FROM prepaid_vouchers WHERE pendingTxHash IS NOT NULL") abstract suspend fun vouchersWithPending(): List<VoucherEntity>
    @Query("SELECT * FROM prepaid_vouchers ORDER BY updatedAt DESC") abstract fun vouchersFlow(): Flow<List<VoucherEntity>>

    @Insert(onConflict = OnConflictStrategy.ABORT) abstract suspend fun insertSeen(e: JournalSeenEntity)
    @Insert(onConflict = OnConflictStrategy.ABORT) abstract suspend fun insertSeenAll(e: List<JournalSeenEntity>)
    @Query("SELECT * FROM prepaid_journal_seen WHERE voucherId = :id") abstract suspend fun seenForVoucher(id: String): List<JournalSeenEntity>
    @Query("SELECT * FROM prepaid_journal_seen WHERE entryHash = :hash LIMIT 1") abstract suspend fun seen(hash: String): JournalSeenEntity?

    /** Counts and settled entries land together or not at all. */
    @Transaction open suspend fun applyRedeems(vouchers: List<VoucherEntity>, seen: List<JournalSeenEntity>) { updateVouchers(vouchers); insertSeenAll(seen) }

    @Insert(onConflict = OnConflictStrategy.ABORT) abstract suspend fun insertStaffKey(k: StaffKeyEntity)
    @Update abstract suspend fun updateStaffKey(k: StaffKeyEntity)
    @Query("SELECT * FROM prepaid_staff_keys WHERE keyId = :id LIMIT 1") abstract suspend fun staffKey(id: String): StaffKeyEntity?
    @Query("SELECT * FROM prepaid_staff_keys WHERE revokedAt IS NULL ORDER BY enrolledAt ASC") abstract suspend fun activeStaffKeys(): List<StaffKeyEntity>
    @Query("SELECT * FROM prepaid_staff_keys ORDER BY enrolledAt DESC") abstract fun staffKeysFlow(): Flow<List<StaffKeyEntity>>
}
```

`app/src/main/java/com/wyltek/wallet/prepaid/VoucherStore.kt`:

```kotlin
package com.wyltek.wallet.prepaid

import com.wyltek.wallet.agent.db.AgentDatabase
import com.wyltek.wallet.prepaid.db.*
import kotlinx.coroutines.flow.Flow

interface VoucherStore {
    suspend fun insertVoucher(v: VoucherEntity)
    suspend fun updateVoucher(v: VoucherEntity)
    suspend fun deleteIssuing(voucherId: String): Int
    suspend fun voucher(id: String): VoucherEntity?
    /** The newest voucher row for this tag (a reissued tag keeps its older, closed rows). */
    suspend fun voucherByUid(uid: String): VoucherEntity?
    suspend fun allVouchers(): List<VoucherEntity>
    suspend fun vouchersWithPending(): List<VoucherEntity>
    fun vouchersFlow(): Flow<List<VoucherEntity>>
    /** One transaction. */
    suspend fun applyRedeems(vouchers: List<VoucherEntity>, seen: List<JournalSeenEntity>)
    suspend fun seenForVoucher(id: String): List<JournalSeenEntity>
    suspend fun seen(hash: String): JournalSeenEntity?
    suspend fun insertSeen(e: JournalSeenEntity)
    suspend fun insertStaffKey(k: StaffKeyEntity)
    suspend fun updateStaffKey(k: StaffKeyEntity)
    suspend fun staffKey(id: String): StaffKeyEntity?
    suspend fun activeStaffKeys(): List<StaffKeyEntity>
    fun staffKeysFlow(): Flow<List<StaffKeyEntity>>
}

class RoomVoucherStore(db: AgentDatabase) : VoucherStore {
    private val dao = db.voucherDao()
    override suspend fun insertVoucher(v: VoucherEntity) = dao.insertVoucher(v)
    override suspend fun updateVoucher(v: VoucherEntity) = dao.updateVoucher(v)
    override suspend fun deleteIssuing(voucherId: String) = dao.deleteIssuing(voucherId)
    override suspend fun voucher(id: String) = dao.voucher(id)
    override suspend fun voucherByUid(uid: String) = dao.voucherByUid(uid)
    override suspend fun allVouchers() = dao.allVouchers()
    override suspend fun vouchersWithPending() = dao.vouchersWithPending()
    override fun vouchersFlow() = dao.vouchersFlow()
    override suspend fun applyRedeems(vouchers: List<VoucherEntity>, seen: List<JournalSeenEntity>) = dao.applyRedeems(vouchers, seen)
    override suspend fun seenForVoucher(id: String) = dao.seenForVoucher(id)
    override suspend fun seen(hash: String) = dao.seen(hash)
    override suspend fun insertSeen(e: JournalSeenEntity) = dao.insertSeen(e)
    override suspend fun insertStaffKey(k: StaffKeyEntity) = dao.insertStaffKey(k)
    override suspend fun updateStaffKey(k: StaffKeyEntity) = dao.updateStaffKey(k)
    override suspend fun staffKey(id: String) = dao.staffKey(id)
    override suspend fun activeStaffKeys() = dao.activeStaffKeys()
    override fun staffKeysFlow() = dao.staffKeysFlow()
}
```

`app/src/test/java/com/wyltek/wallet/prepaid/fakes/InMemoryVoucherStore.kt`:

```kotlin
package com.wyltek.wallet.prepaid.fakes

import com.wyltek.wallet.prepaid.VoucherStore
import com.wyltek.wallet.prepaid.db.*
import kotlinx.coroutines.flow.MutableStateFlow

class InMemoryVoucherStore : VoucherStore {
    val vouchers = linkedMapOf<String, VoucherEntity>(); val seen = linkedMapOf<String, JournalSeenEntity>(); val keys = linkedMapOf<String, StaffKeyEntity>()
    private val vf = MutableStateFlow<List<VoucherEntity>>(emptyList()); private val kf = MutableStateFlow<List<StaffKeyEntity>>(emptyList())
    private fun emit() { vf.value = vouchers.values.sortedByDescending { it.updatedAt }; kf.value = keys.values.sortedByDescending { it.enrolledAt } }
    override suspend fun insertVoucher(v: VoucherEntity) { check(v.voucherId !in vouchers); vouchers[v.voucherId] = v; emit() }
    override suspend fun updateVoucher(v: VoucherEntity) { check(v.voucherId in vouchers); vouchers[v.voucherId] = v; emit() }
    override suspend fun deleteIssuing(voucherId: String) = if (vouchers[voucherId]?.state == VOUCHER_ISSUING) { vouchers.remove(voucherId); emit(); 1 } else 0
    override suspend fun voucher(id: String) = vouchers[id]
    override suspend fun voucherByUid(uid: String) = vouchers.values.filter { it.uid.equals(uid, true) }.maxByOrNull { it.issuedAt }
    override suspend fun allVouchers() = vouchers.values.toList()
    override suspend fun vouchersWithPending() = vouchers.values.filter { it.pendingTxHash != null }
    override fun vouchersFlow() = vf
    override suspend fun applyRedeems(vouchers: List<VoucherEntity>, seen: List<JournalSeenEntity>) {
        check(vouchers.all { it.voucherId in this.vouchers }); check(seen.none { it.entryHash in this.seen })
        vouchers.forEach { this.vouchers[it.voucherId] = it }; seen.forEach { this.seen[it.entryHash] = it }; emit()
    }
    override suspend fun seenForVoucher(id: String) = seen.values.filter { it.voucherId == id }
    override suspend fun seen(hash: String) = seen[hash]
    override suspend fun insertSeen(e: JournalSeenEntity) { check(e.entryHash !in seen); seen[e.entryHash] = e }
    override suspend fun insertStaffKey(k: StaffKeyEntity) { check(k.keyId !in keys); keys[k.keyId] = k; emit() }
    override suspend fun updateStaffKey(k: StaffKeyEntity) { check(k.keyId in keys); keys[k.keyId] = k; emit() }
    override suspend fun staffKey(id: String) = keys[id]
    override suspend fun activeStaffKeys() = keys.values.filter { it.revokedAt == null }.sortedBy { it.enrolledAt }
    override fun staffKeysFlow() = kf
}
```

- [ ] **Step 3: Write the migration test first** (`app/src/test/java/com/wyltek/wallet/agent/db/MigrationSqlMatchesRoomTest.kt`)

```kotlin
package com.wyltek.wallet.agent.db

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Room validates the schema when the DB opens; a migration whose SQL differs from what Room generated crashes the app
 * on Phill's v5 phone (connected tests are banned there). Room's own CREATE statements are in the KSP output, so pin
 * MIGRATION_5_6 to them, statement for statement.
 */
class MigrationSqlMatchesRoomTest {
    private val impl = File("build/generated/ksp/debug/java/com/wyltek/wallet/agent/db/AgentDatabase_Impl.java").readText()
    private val roomSql = Regex("execSQL\\(\"(CREATE (?:TABLE|INDEX) IF NOT EXISTS `(?:index_)?(prepaid_vouchers|prepaid_staff_keys|prepaid_journal_seen)[^\"]*)\"\\)")
        .findAll(impl).map { it.groupValues[1].replace("\\\"", "\"") }.toSet()

    @Test fun migration_5_6_creates_exactly_what_room_expects() {
        assertEquals(5, roomSql.size)
        assertEquals(roomSql, MIGRATION_5_6_SQL.toSet())
    }
    @Test fun database_is_version_6_and_registers_the_migration() {
        assertEquals(6, MIGRATION_5_6.endVersion); assertEquals(5, MIGRATION_5_6.startVersion)
    }
}
```

- [ ] **Step 4: Write the migration and register the entities.** In `AgentDatabase.kt`:

```kotlin
/** v5 → v6: vouchers, staff keys, settled voucher/override entries (vouchers spec §8). Pinned to Room's own SQL by
 *  MigrationSqlMatchesRoomTest. */
val MIGRATION_5_6_SQL = listOf(
    "CREATE TABLE IF NOT EXISTS `prepaid_vouchers` (`voucherId` TEXT NOT NULL, `uid` TEXT NOT NULL, `productCode` TEXT NOT NULL, `productName` TEXT NOT NULL, `productHash` TEXT NOT NULL, `issuePriceMinor` INTEGER NOT NULL, `currency` TEXT NOT NULL, `countIssued` INTEGER NOT NULL, `countLeft` INTEGER NOT NULL, `outpoint` TEXT NOT NULL, `state` TEXT NOT NULL, `tagCounter` INTEGER NOT NULL, `pendingKind` TEXT, `pendingTxHash` TEXT, `pendingActionId` TEXT, `pendingCommitted` INTEGER NOT NULL, `pendingSince` INTEGER, `issuedAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`voucherId`))",
    "CREATE INDEX IF NOT EXISTS `index_prepaid_vouchers_uid` ON `prepaid_vouchers` (`uid`)",
    "CREATE TABLE IF NOT EXISTS `prepaid_staff_keys` (`keyId` TEXT NOT NULL, `uid` TEXT NOT NULL, `sigHex` TEXT NOT NULL, `label` TEXT NOT NULL, `enrolledAt` INTEGER NOT NULL, `revokedAt` INTEGER, PRIMARY KEY(`keyId`))",
    "CREATE TABLE IF NOT EXISTS `prepaid_journal_seen` (`entryHash` TEXT NOT NULL, `kind` TEXT NOT NULL, `voucherId` TEXT, `counter` INTEGER, `terminalId` INTEGER NOT NULL, `seenAt` INTEGER NOT NULL, PRIMARY KEY(`entryHash`))",
    "CREATE INDEX IF NOT EXISTS `index_prepaid_journal_seen_voucherId` ON `prepaid_journal_seen` (`voucherId`)",
)
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) { MIGRATION_5_6_SQL.forEach(db::execSQL) }
}
```

In `@Database`, add `VoucherEntity::class, StaffKeyEntity::class, JournalSeenEntity::class` to `entities` and set `version = 6`. Add `abstract fun voucherDao(): VoucherDao`, and `MIGRATION_5_6` to `addMigrations(…)`. Import `com.wyltek.wallet.prepaid.db.*`.

- [ ] **Step 5: Run the test**

Run: `cd ~/wyltek-wallet && ./gradlew :app:testDebugUnitTest --tests '*MigrationSqlMatchesRoomTest*' -q 2>&1 | tail -5`
Expected: 2 passed. If `migration_5_6_creates_exactly_what_room_expects` fails, the assertion diff shows Room's exact string. Copy Room's string into `MIGRATION_5_6_SQL`; Room's output is the truth.

- [ ] **Step 6: Mutation probe.** Remove `` `tagCounter` INTEGER NOT NULL, `` from the first statement. → FAIL. Restore it.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/wyltek/wallet/prepaid/db app/src/main/java/com/wyltek/wallet/prepaid/VoucherStore.kt app/src/main/java/com/wyltek/wallet/agent/db/AgentDatabase.kt app/src/test/java/com/wyltek/wallet/prepaid/fakes/InMemoryVoucherStore.kt app/src/test/java/com/wyltek/wallet/agent/db/MigrationSqlMatchesRoomTest.kt
git commit -m "feat(prepaid): agent DB v6 with voucher, staff key and settled-entry tables

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 8: `TagPort.readSignature` (NTAG `READ_SIG`)

**Files:**
- Modify:
  - `app/src/main/java/com/wyltek/wallet/prepaid/Ports.kt`
  - `app/src/main/java/com/wyltek/wallet/prepaid/nfc/MifareTagPort.kt`
  - `app/src/test/java/com/wyltek/wallet/prepaid/fakes/FakeTag.kt`
- Test: `app/src/test/java/com/wyltek/wallet/prepaid/FakesSelfTest.kt` (add a case)

**Interfaces:**
- Produces:
  - `TagPort.readSignature(): ByteArray`: 32 bytes, throws `TagLostException`
  - `FakeTag.signature: ByteArray` (default 32 × `0x5A`)

- [ ] **Step 1: Failing test** (add to `FakesSelfTest`)

```kotlin
    @Test fun fake_tag_reads_its_signature_and_loses_it_when_absent() = kotlinx.coroutines.test.runTest {
        val t = com.wyltek.wallet.prepaid.fakes.FakeTag("04958caa5c5e80")
        org.junit.Assert.assertEquals(32, t.readSignature().size)
        t.present = false
        org.junit.Assert.assertThrows(com.wyltek.wallet.prepaid.TagLostException::class.java) { kotlinx.coroutines.runBlocking { t.readSignature() } }
    }
```

- [ ] **Step 2: Run** `./gradlew :app:testDebugUnitTest --tests '*FakesSelfTest*' -q` → compile error.

- [ ] **Step 3: Implement.**
  - In `TagPort`: `/** NTAG21x READ_SIG (0x3C 0x00): the 32-byte NXP originality signature. Throws [TagLostException]. */ suspend fun readSignature(): ByteArray`.
  - In `FakeTag`: `var signature = ByteArray(32) { 0x5A }` and `override suspend fun readSignature(): ByteArray { if (!present) throw TagLostException("gone"); return signature.copyOf() }`.
  - In `MifareTagPort`:

```kotlin
    override suspend fun readSignature(): ByteArray = session { mu ->
        val sig = mu.transceive(byteArrayOf(0x3C, 0x00))
        if (sig.size != 32) throw IOException("READ_SIG returned ${sig.size} bytes")
        sig
    }
```

- [ ] **Step 4: Run** → PASS. Then run the whole JVM suite: failures 0.

- [ ] **Step 5: Commit** `feat(prepaid): read the NTAG21x originality signature` (with the attribution line).

---

### Task 9: Classify voucher tags; a voucher is never Blank

**Files:**
- Create: `app/src/main/java/com/wyltek/wallet/prepaid/VoucherClassifier.kt`
- Modify: `app/src/main/java/com/wyltek/wallet/prepaid/TapClassifier.kt` (three `TapClass` subclasses)
- Test: `app/src/test/java/com/wyltek/wallet/prepaid/VoucherClassifierTest.kt`

**Interfaces:**
- Produces:
  - `TapClass.Voucher(voucher: VoucherEntity, record: VoucherRecordInfo)`
  - `TapClass.VoucherResume(voucher: VoucherEntity)`
  - `TapClass.VoucherDamaged(voucher: VoucherEntity, reason: String)`
  - `object VoucherClassifier { REASON_* ; fun classify(uidHex, vslot: VoucherSlotResult, byUid: VoucherEntity?, byId: VoucherEntity?, ourMerchantIdHex): TapClass? }`. Null = not a voucher tag; the card classifier decides.

- [ ] **Step 1: Failing tests**

```kotlin
package com.wyltek.wallet.prepaid

import com.wyltek.wallet.core.native.*
import com.wyltek.wallet.prepaid.db.*
import org.junit.Assert.*
import org.junit.Test

class VoucherClassifierTest {
    private val me = "1122334455667788"; private val uid = "04958caa5c5e80"
    private fun v(state: String = VOUCHER_ACTIVE, pending: String? = null) = VoucherEntity("c0".repeat(16), uid, "COF01", "Flat white", "5a".repeat(8),
        450, "AUD", 1, 1, "0x" + "aa".repeat(32) + ":0", state, pendingTxHash = pending, issuedAt = 0, updatedAt = 0)
    private fun rec(merchant: String = me) = VoucherRecordInfo(0u, "c0".repeat(16), "5a".repeat(8), 450u, "AUD", 1u, 0u, merchant)
    private fun slot(pick: SlotPickFfi, r: VoucherRecordInfo? = null, reason: SlotReason = SlotReason.NO_VALID_SLOT, hint: String? = null) = VoucherSlotResult(pick, r, reason, hint)

    @Test fun our_voucher_classifies_as_voucher() =
        assertEquals(TapClass.Voucher(v(), rec()), VoucherClassifier.classify(uid, slot(SlotPickFfi.A, rec()), v(), v(), me))

    @Test fun a_voucher_tag_is_never_blank() {
        val cases = listOf(
            slot(SlotPickFfi.A, rec("99".repeat(8))) to null,                                    // another merchant's voucher
            slot(SlotPickFfi.NONE, reason = SlotReason.MAC_FAIL, hint = me) to null,              // ours, failing MAC, unknown UID
            slot(SlotPickFfi.NONE, reason = SlotReason.MAC_FAIL, hint = "99".repeat(8)) to null,
            slot(SlotPickFfi.NONE, reason = SlotReason.BAD_VERSION) to null,
            slot(SlotPickFfi.AMBIGUOUS) to null,
            slot(SlotPickFfi.A, rec()) to null,                                                   // valid but not in this Vault
            slot(SlotPickFfi.NONE) to v(),                                                        // wiped/torn tag of a live voucher
        )
        for ((s, byUid) in cases) {
            val c = VoucherClassifier.classify(uid, s, byUid, null, me)
            assertNotNull("$s must not fall through to the card classifier", c)
            assertFalse(c is TapClass.Blank)
        }
    }

    @Test fun a_tag_with_no_voucher_and_no_live_voucher_row_falls_through() {
        assertNull(VoucherClassifier.classify(uid, slot(SlotPickFfi.NONE), null, null, me))
        assertNull(VoucherClassifier.classify(uid, slot(SlotPickFfi.NONE), v(VOUCHER_CLOSED), null, me))
        assertNull(VoucherClassifier.classify(uid, slot(SlotPickFfi.NONE), v(VOUCHER_VOID), null, me))
    }

    @Test fun pending_voucher_resumes_and_damaged_live_voucher_is_damaged() {
        assertEquals(TapClass.VoucherResume(v(pending = "0xab")), VoucherClassifier.classify(uid, slot(SlotPickFfi.NONE), v(pending = "0xab"), null, me))
        assertTrue(VoucherClassifier.classify(uid, slot(SlotPickFfi.NONE), v(), null, me) is TapClass.VoucherDamaged)
        assertTrue(VoucherClassifier.classify(uid, slot(SlotPickFfi.NONE, reason = SlotReason.MAC_FAIL, hint = me), v(), null, me) is TapClass.VoucherDamaged)
    }

    @Test fun record_moved_to_another_tag_is_refused() {
        val other = v().copy(uid = "04ebbb02cd2a81")
        assertEquals(TapClass.Refused(VoucherClassifier.REASON_MOVED), VoucherClassifier.classify(uid, slot(SlotPickFfi.A, rec()), null, other, me))
    }
}
```

- [ ] **Step 2: Run** → compile errors.

- [ ] **Step 3: Implement.** In `TapClassifier.kt`, add these inside `sealed class TapClass`:

```kotlin
    data class Voucher(val voucher: com.wyltek.wallet.prepaid.db.VoucherEntity, val record: com.wyltek.wallet.core.native.VoucherRecordInfo) : TapClass()
    data class VoucherResume(val voucher: com.wyltek.wallet.prepaid.db.VoucherEntity) : TapClass()
    data class VoucherDamaged(val voucher: com.wyltek.wallet.prepaid.db.VoucherEntity, val reason: String) : TapClass()
```

`VoucherClassifier.kt`:

```kotlin
package com.wyltek.wallet.prepaid

import com.wyltek.wallet.core.native.SlotPickFfi
import com.wyltek.wallet.core.native.SlotReason
import com.wyltek.wallet.core.native.VoucherSlotResult
import com.wyltek.wallet.prepaid.db.VOUCHER_LIVE
import com.wyltek.wallet.prepaid.db.VoucherEntity

/** Vouchers spec §4: a tag that carries anything voucher-shaped, or whose UID has a live voucher, is classified here and
 *  never reaches the card classifier (which would call it Blank and offer to overwrite it). Pure. */
object VoucherClassifier {
    const val REASON_OTHER_MERCHANT = "another merchant's voucher"
    const val REASON_BAD_VERSION = "unsupported voucher version"
    const val REASON_AMBIGUOUS = "voucher state ambiguous"
    const val REASON_AUTH = "voucher failed authentication"
    const val REASON_UNKNOWN = "voucher not issued by this Vault"
    const val REASON_MOVED = "voucher record belongs to a different tag"
    const val REASON_NO_SLOT = "no valid voucher slot"

    fun classify(uidHex: String, vslot: VoucherSlotResult, byUid: VoucherEntity?, byId: VoucherEntity?, ourMerchantIdHex: String): TapClass? {
        if (byUid?.pendingTxHash != null) return TapClass.VoucherResume(byUid)
        val live = byUid?.takeIf { it.state in VOUCHER_LIVE }
        return when (vslot.pick) {
            SlotPickFfi.A, SlotPickFfi.B -> {
                val rec = vslot.record ?: return TapClass.Refused(REASON_NO_SLOT)
                when {
                    !rec.merchantIdHex.equals(ourMerchantIdHex, true) -> TapClass.Refused(REASON_OTHER_MERCHANT)
                    byId == null -> TapClass.Refused(REASON_UNKNOWN)
                    !byId.uid.equals(uidHex, true) -> TapClass.Refused(REASON_MOVED)
                    else -> TapClass.Voucher(byId, rec)
                }
            }
            SlotPickFfi.AMBIGUOUS -> live?.let { TapClass.VoucherDamaged(it, REASON_AMBIGUOUS) } ?: TapClass.Refused(REASON_AMBIGUOUS)
            SlotPickFfi.NONE -> when (vslot.reason) {
                SlotReason.BAD_VERSION -> TapClass.Refused(REASON_BAD_VERSION)
                SlotReason.MAC_FAIL -> when {
                    vslot.merchantHintHex != null && !vslot.merchantHintHex.equals(ourMerchantIdHex, true) -> TapClass.Refused(REASON_OTHER_MERCHANT)
                    live != null -> TapClass.VoucherDamaged(live, REASON_AUTH)
                    else -> TapClass.Refused(REASON_AUTH)
                }
                SlotReason.NO_VALID_SLOT -> live?.let { TapClass.VoucherDamaged(it, REASON_NO_SLOT) }
            }
        }
    }
}
```

Fix the now-non-exhaustive `when (tap)` expressions so the app compiles. Task 15 gives them real behaviour; for now, map the three new classes to `emptyList()` / `"Voucher"` text:
- `PrepaidUiReducer.tapActionsWithTag`
- `PrepaidTapSheet.headline`
- `PrepaidTapSheet.detail`

- [ ] **Step 4: Run** `--tests '*VoucherClassifierTest*'` → 5 passed. Then run the whole suite: failures 0.

- [ ] **Step 5: Mutation probe.** In `NO_VALID_SLOT -> live?.let { … }`, replace the body with `null`. → `a_voucher_tag_is_never_blank` FAILS (last case). Restore it.

- [ ] **Step 6: Commit** `feat(prepaid): classify voucher tags; a voucher is never offered as blank` (with the attribution line).

---

### Task 10: `VoucherService`: issue, write, resume, recover

**Files:**
- Create:
  - `app/src/main/java/com/wyltek/wallet/prepaid/VoucherService.kt`
  - `app/src/main/java/com/wyltek/wallet/prepaid/VoucherHooks.kt`
- Modify:
  - `app/src/main/java/com/wyltek/wallet/prepaid/PrepaidService.kt` (visibility, `classifyRaw`, hooks field, card-issue guard)
  - `app/src/main/java/com/wyltek/wallet/prepaid/PrepaidTxPlanner.kt` (`output` with data)
- Test:
  - `app/src/test/java/com/wyltek/wallet/prepaid/VoucherServiceIssueTest.kt`
  - `app/src/test/java/com/wyltek/wallet/prepaid/fakes/VoucherHarness.kt` (shared by Tasks 10, 11, 13)
  - `app/src/test/java/com/wyltek/wallet/prepaid/fakes/FakeChain.kt` (keep output data)

**Interfaces:**
- Consumes:
  - `PrepaidService` internals, made `internal` in this task: `store`, `chain`, `signer`, `protocol`, `clock`, `newActionId`, `kHex`, `build`, `exclusive`, `log`, `Audit`, `broadcastAndAwait`, `statusOrNull`, `excludedOutpoints`, `merchantIdHex`, `PENDING_UNKNOWN_GIVE_UP_MS`
  - `VoucherStore`, `VoucherClassifier`
- Produces:
  - `data class VoucherForm(productCode: String, productName: String, issuePriceMinor: Long, currency: String, count: Int)`
  - `class VoucherService(core: PrepaidService, vouchers: VoucherStore)` with:
    - `classify(tag): TapClass`
    - `issue(tag, form, progress): ActionOutcome`
    - `resume(tag, voucher, progress): ActionOutcome`
    - `recoverPending(): Int`
    - `VOUCHER_CELL_SHANNONS = 6_900_000_000uL`, `VOUCHER_CELL_DATA = "0x4242563100000000"`
  - `interface VoucherHooks` (`uidHoldsVoucher`, `voucherStates`, `apply`, `afterSettle`) and `NoVoucherHooks`
  - `PrepaidService.voucherHooks: VoucherHooks` (settable, internal)
  - `PrepaidService.classifyRaw(uidHex, raw): TapClass`
  - `PrepaidTxPlanner.output(capacity, lock, dataHex)`

- [ ] **Step 0: Make `FakeChain` node-faithful about cell data.** Today `applyOnce` drops `outputs_data`, so a voucher cell would look "pure" and every data-dependent test would mean nothing. In `FakeChain.applyOnce`, read `val data = j["outputs_data"]?.jsonArray?.getOrNull(idx)?.jsonPrimitive?.content` inside the outputs loop. Pass `data = data?.takeIf { it != "0x" }` to the `Utxo(...)` it adds (this mirrors `ChainProvider`, which maps empty data to null). Run the whole suite: failures 0, since every existing output has empty data.

Then create the shared harness `app/src/test/java/com/wyltek/wallet/prepaid/fakes/VoucherHarness.kt`:

```kotlin
package com.wyltek.wallet.prepaid.fakes

import com.wyltek.wallet.core.model.LockScript
import com.wyltek.wallet.core.model.OutPoint
import com.wyltek.wallet.core.model.Utxo
import com.wyltek.wallet.prepaid.PrepaidKeys
import com.wyltek.wallet.prepaid.PrepaidService
import com.wyltek.wallet.prepaid.VoucherService

val VOUCHER_TEST_LOCK = LockScript("0x9bd7e06f3ecf4be0f2fcd2188b23f1b9fcc88e5d4b65a8637b17723bbda3cce8", "type", "0x" + "ab".repeat(20))

/** One merchant with a 1000 CKB wallet cell, the card service and the voucher service over in-memory fakes. */
class VoucherHarness(walletCkb: ULong = 1000uL, val lock: LockScript = VOUCHER_TEST_LOCK) {
    val store = InMemoryPrepaidStore(); val vstore = InMemoryVoucherStore(); val chain = FakeChain(); val protocol = FakeProtocol()
    var now = 1_000L; var ids = 0
    val keys = PrepaidKeys(load = { null }, store = { _, _ -> }, random = { ByteArray(16) { 1 } })
    val core = PrepaidService(store, chain, FakeSigner(lock), protocol, keys, clock = { now++ }, newActionId = { "a${++ids}" })
    val svc = VoucherService(core, vstore)
    init { chain.add(Utxo(OutPoint("0x" + "aa".repeat(32), 0u), walletCkb * 100_000_000uL, lock)) }
}
```

- [ ] **Step 1: Failing tests** (`VoucherServiceIssueTest.kt`)

```kotlin
package com.wyltek.wallet.prepaid

import com.wyltek.wallet.core.native.SlotPickFfi
import com.wyltek.wallet.prepaid.db.*
import com.wyltek.wallet.prepaid.fakes.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class VoucherServiceIssueTest {
    private val lock = VOUCHER_TEST_LOCK
    private val uid = "04958caa5c5e80"
    private val form = VoucherForm(" cof01 ", "Flat white", 450, "AUD", 1)
    private fun H() = VoucherHarness()

    @Test fun issue_creates_a_69_ckb_bbv1_cell_writes_the_tag_and_logs_phases() = runTest {
        val h = H(); val tag = FakeTag(uid)
        assertTrue(h.svc.classify(tag) is TapClass.Blank)
        assertEquals(ActionOutcome.Done, h.svc.issue(tag, form))
        val v = h.vstore.voucherByUid(uid)!!
        assertEquals(VOUCHER_ACTIVE, v.state); assertEquals("COF01", v.productCode); assertEquals(1, v.countLeft); assertNull(v.pendingTxHash)
        val cell = h.chain.cellAt(v.outpoint)!!
        assertEquals(VoucherService.VOUCHER_CELL_SHANNONS, cell.capacity); assertEquals(VoucherService.VOUCHER_CELL_DATA, cell.data)
        val slot = h.protocol.voucherSelectSlot("", uid, tag.readSlots())
        assertEquals(SlotPickFfi.A, slot.pick); assertEquals(v.voucherId, slot.record!!.voucherIdHex); assertEquals(h.protocol.productHash("COF01"), slot.record!!.productHashHex)
        assertEquals(listOf(PHASE_REQUESTED, PHASE_BROADCAST, PHASE_COMMITTED, PHASE_WRITTEN, PHASE_DONE), h.store.phases("a1"))
        assertTrue(h.store.events.all { it.kind == KIND_VOUCHER_ISSUE && it.reserveId == v.voucherId })
        assertTrue(h.svc.classify(tag) is TapClass.Voucher)
    }

    @Test fun torn_write_awaits_the_tag_and_resume_finishes_it() = runTest {
        val h = H(); val tag = FakeTag(uid); tag.failAfterPages = 3
        val out = h.svc.issue(tag, form)
        assertTrue(out is ActionOutcome.AwaitingTag)
        val v = h.vstore.voucherByUid(uid)!!
        assertTrue(v.pendingCommitted); assertEquals(PHASE_FAILED, h.store.phases("a1").last())
        tag.present = true
        assertTrue(h.svc.classify(tag) is TapClass.VoucherResume)
        assertEquals(ActionOutcome.Done, h.svc.resume(tag, v))
        assertEquals(PHASE_DONE, h.store.phases("a1").last())
        assertNull(h.vstore.voucher(v.voucherId)!!.pendingTxHash)
    }

    @Test fun bad_form_inputs_are_refused_before_any_chain_work() = runTest {
        val h = H()
        for (f in listOf(form.copy(productCode = "  "), form.copy(count = 0), form.copy(count = 256), form.copy(currency = "aud"), form.copy(issuePriceMinor = -1), form.copy(issuePriceMinor = 4_294_967_296))) {
            assertTrue(f.toString(), h.svc.issue(FakeTag(uid), f) is ActionOutcome.Failed)
        }
        assertTrue(h.chain.broadcasts.isEmpty())
    }

    @Test fun a_tag_that_is_a_live_card_or_live_voucher_is_refused_and_a_card_cannot_be_issued_over_a_voucher() = runTest {
        val h = H(); val tag = FakeTag(uid)
        assertEquals(ActionOutcome.Done, h.svc.issue(tag, form))
        assertTrue(h.svc.issue(FakeTag(uid), form) is ActionOutcome.Failed)
        assertEquals(ActionOutcome.Failed("this tag holds a voucher"), h.core.issue(FakeTag(uid), 1000, null))
    }

    @Test fun the_voucher_cell_is_never_a_fee_input() = runTest {
        val h = H()
        assertEquals(ActionOutcome.Done, h.svc.issue(FakeTag(uid), form))
        val vcell = h.chain.cellAt(h.vstore.voucherByUid(uid)!!.outpoint)!!
        val planned = PrepaidTxPlanner.plan(emptyList(), h.chain.cellsByLock(lock), emptySet(), listOf(PrepaidTxPlanner.output(100uL * 100_000_000uL, lock)), lock)
        assertFalse(planned.inputs.any { PrepaidTxPlanner.outpointKey(it) == PrepaidTxPlanner.outpointKey(vcell) })
    }

    @Test fun recover_applies_a_commit_that_landed_while_the_page_was_closed() = runTest {
        val h = H(); h.chain.confirmAfterPolls = null
        val out = h.svc.issue(FakeTag(uid), form)
        assertTrue(out is ActionOutcome.StillConfirming)
        h.chain.confirmAfterPolls = 0
        assertEquals(1, h.svc.recoverPending())
        val v = h.vstore.voucherByUid(uid)!!
        assertEquals(VOUCHER_ACTIVE, v.state); assertTrue(v.pendingCommitted)        // written on the next tap
    }
}
```

`FakeChain.confirmAfterPolls = null` keeps it PENDING forever; `0` confirms on the next poll. Check `FakeChain.status`: with `confirmAfterPolls = 0` it must return CONFIRMED on the first poll. If its counting needs `1`, use `1` (it applies the tx on confirm).

- [ ] **Step 2: Run** → compile errors.

- [ ] **Step 3: Implement.**

**`PrepaidService.kt` edits**
- Constructor `private val` → `internal val` for `store`, `chain`, `signer`, `protocol`, `clock`, `newActionId`.
- `private val kHex` → `internal val kHex`.
- `private fun build` → `internal fun build`.
- Add the field `@Volatile internal var voucherHooks: VoucherHooks = NoVoucherHooks`.
- Split `classify`:

```kotlin
    suspend fun classify(tag: TagPort): TapClass {
        val raw = try { tag.readSlots() } catch (e: CancellationException) { throw e } catch (e: Exception) { return TapClass.Refused("tag lost while reading") }
        return classifyRaw(tag.uidHex, raw)
    }

    internal suspend fun classifyRaw(uidHex: String, raw: ByteArray): TapClass {
        val slot = protocol.selectSlot(kHex, uidHex, raw)
        val byUid = store.cardByUid(uidHex)
        val byReserve = slot.record?.let { store.card(it.reserveIdHex) }
        return TapClassifier.classify(uidHex, slot, byUid, byReserve, merchantIdHex)
    }
```

- In `issue`, after the `previous` check, add `if (voucherHooks.uidHoldsVoucher(tag.uidHex)) return@exclusive ActionOutcome.Failed("this tag holds a voucher")`.

**`PrepaidTxPlanner.kt`**: add

```kotlin
    fun output(capacity: ULong, lock: LockScript, dataHex: String) = TxOutput(capacity, lock.codeHash, lock.hashType, lock.args, "", "", "", dataHex)
```

**`VoucherHooks.kt`**:

```kotlin
package com.wyltek.wallet.prepaid

import com.wyltek.wallet.core.native.BatchResultFfi
import com.wyltek.wallet.core.native.VoucherStateFfi

/** What PrepaidService needs to know about vouchers, and nothing more. VoucherService installs itself. */
interface VoucherHooks {
    suspend fun uidHoldsVoucher(uidHex: String): Boolean
    suspend fun voucherStates(): List<VoucherStateFfi>
    /** Applies accepted redeems and overrides; returns [names] with this hook's own entries finalised. Runs inside the settle lock. */
    suspend fun apply(res: BatchResultFfi, names: List<String>, terminalLabel: String): List<String>
    /** After the settlement batch, inside the same lock: close vouchers whose last use settled. Must not throw. */
    suspend fun afterSettle()
}

object NoVoucherHooks : VoucherHooks {
    override suspend fun uidHoldsVoucher(uidHex: String) = false
    override suspend fun voucherStates() = emptyList<VoucherStateFfi>()
    override suspend fun apply(res: BatchResultFfi, names: List<String>, terminalLabel: String) = names
    override suspend fun afterSettle() {}
}
```

**`VoucherService.kt`** (issue, write, resume and recover here; void, close and settle hooks come in Tasks 11 and 13):

```kotlin
package com.wyltek.wallet.prepaid

import com.wyltek.wallet.core.chain.TxStatus
import com.wyltek.wallet.core.native.*
import com.wyltek.wallet.prepaid.db.*
import kotlinx.coroutines.CancellationException

data class VoucherForm(val productCode: String, val productName: String, val issuePriceMinor: Long, val currency: String, val count: Int)

/**
 * Item vouchers (vouchers spec §3, §8). Shares PrepaidService's lock, chain helpers and audit log, so a voucher action
 * and a card action never build transactions at the same time. Chain first, tag second, as for cards.
 */
class VoucherService(private val core: PrepaidService, private val vouchers: VoucherStore) : VoucherHooks {
    companion object {
        val VOUCHER_CELL_SHANNONS: ULong = 6_900_000_000uL
        const val VOUCHER_CELL_DATA = "0x4242563100000000"
        private const val MAX_PRICE_MINOR = 4_294_967_295L
    }

    init { core.voucherHooks = this }

    private fun busy() = ActionOutcome.Failed("busy")
    private fun cleared(v: VoucherEntity) = v.copy(pendingKind = null, pendingTxHash = null, pendingActionId = null, pendingCommitted = false,
        pendingSince = null, updatedAt = core.clock())
    private fun auditOf(v: VoucherEntity, kind: String) = PrepaidService.Audit(v.pendingActionId ?: core.newActionId(), kind, v.uid, v.voucherId,
        v.countLeft.toLong(), v.countLeft, v.tagCounter)

    // ------------------------------------------------------------ classify
    suspend fun classify(tag: TagPort): TapClass {
        val raw = try { tag.readSlots() } catch (e: CancellationException) { throw e } catch (e: Exception) { return TapClass.Refused("tag lost while reading") }
        val vslot = core.protocol.voucherSelectSlot(core.kHex, tag.uidHex, raw)
        val byId = vslot.record?.let { vouchers.voucher(it.voucherIdHex) }
        return VoucherClassifier.classify(tag.uidHex, vslot, vouchers.voucherByUid(tag.uidHex), byId, core.merchantIdHex)
            ?: core.classifyRaw(tag.uidHex, raw)
    }

    // ------------------------------------------------------------ issue
    private fun formError(f: VoucherForm): String? = when {
        f.count !in 1..255 -> "uses must be 1 to 255"
        f.issuePriceMinor !in 0..MAX_PRICE_MINOR -> "price out of range"
        !f.currency.matches(Regex("[A-Z]{3}")) -> "currency must be three letters, e.g. AUD"
        f.productName.isBlank() -> "enter a product name"
        else -> null
    }

    suspend fun issue(tag: TagPort, form: VoucherForm, progress: (Phase) -> Unit = {}): ActionOutcome = core.exclusive {
        formError(form)?.let { return@exclusive ActionOutcome.Failed(it) }
        val code = try { core.protocol.normaliseProductCode(form.productCode) } catch (e: PrepaidException) { return@exclusive ActionOutcome.Failed("product code must be 1-31 printable characters") }
        core.store.cardByUid(tag.uidHex)?.takeIf { it.state != CARD_CLOSED }?.let { return@exclusive ActionOutcome.Failed("this tag is a prepaid card") }
        vouchers.voucherByUid(tag.uidHex)?.takeIf { it.state in VOUCHER_LIVE }?.let { return@exclusive ActionOutcome.Failed("this tag already holds a voucher") }
        val lock = core.signer.lock
        val planned = try {
            PrepaidTxPlanner.plan(emptyList(), core.chain.cellsByLock(lock), core.excludedOutpoints(), listOf(PrepaidTxPlanner.output(VOUCHER_CELL_SHANNONS, lock, VOUCHER_CELL_DATA)), lock)
        } catch (e: InsufficientFunds) { return@exclusive ActionOutcome.Failed(e.message!!) }
        val built = core.build(planned)
        val voucherId = core.protocol.voucherId(built.txHashHex, 0u)
        val a = PrepaidService.Audit(core.newActionId(), KIND_VOUCHER_ISSUE, tag.uidHex, voucherId, form.count.toLong(), form.count, 0)
        val now = core.clock()
        vouchers.insertVoucher(VoucherEntity(voucherId = voucherId, uid = tag.uidHex, productCode = code, productName = form.productName.trim(),
            productHash = core.protocol.productHash(code), issuePriceMinor = form.issuePriceMinor, currency = form.currency, countIssued = form.count,
            countLeft = form.count, outpoint = "${built.txHashHex}:0", state = VOUCHER_ISSUING, pendingKind = KIND_VOUCHER_ISSUE,
            pendingTxHash = built.txHashHex, pendingActionId = a.actionId, pendingSince = now, issuedAt = now, updatedAt = now))
        core.log(a, PHASE_REQUESTED, detail = "${form.count} × $code ${form.productName.trim()}; issue price ${form.issuePriceMinor} ${form.currency} (minor units)"); progress(Phase.REQUESTED)
        when (val r = core.broadcastAndAwait(a, planned, built, progress)) {
            is PrepaidService.TxResult.Refused -> { vouchers.deleteIssuing(voucherId); ActionOutcome.Failed(r.reason) }
            is PrepaidService.TxResult.Rejected -> { vouchers.deleteIssuing(voucherId); ActionOutcome.Failed("rejected by the node") }
            is PrepaidService.TxResult.Timeout -> ActionOutcome.StillConfirming(r.txHash)
            is PrepaidService.TxResult.Committed -> {
                val v = vouchers.voucher(voucherId)!!.copy(state = VOUCHER_ACTIVE, pendingCommitted = true, updatedAt = core.clock())
                vouchers.updateVoucher(v)
                writeTag(tag, v, a, progress)
            }
        }
    } ?: busy()

    /** Writes the issue record (counter 0) to slot A, zeroes B, reads back. Idempotent: an already-landed record finalises. */
    internal suspend fun writeTag(tag: TagPort, v: VoucherEntity, a: PrepaidService.Audit, progress: (Phase) -> Unit): ActionOutcome {
        if (!tag.uidHex.equals(v.uid, true)) return ActionOutcome.Failed("this is a different tag; present the voucher ${v.productName}")
        val before = try { core.protocol.voucherSelectSlot(core.kHex, tag.uidHex, tag.readSlots()) }
            catch (e: CancellationException) { throw e } catch (e: Exception) { return ActionOutcome.AwaitingTag(v.voucherId) }
        if (before.record?.voucherIdHex.equals(v.voucherId, true) && before.record?.counter == 0u) return finaliseIssue(v, a, progress, "already on tag")
        val raw = core.protocol.voucherSeal(core.kHex, tag.uidHex, VoucherRecordInfo(0u, v.voucherId, v.productHash, v.issuePriceMinor.toUInt(),
            v.currency, v.countIssued.toUByte(), 0u, core.merchantIdHex))
        try { tag.writeSlot(SlotPickFfi.A, raw); tag.zeroSlot(SlotPickFfi.B) }
        catch (e: CancellationException) { throw e } catch (e: Exception) {
            core.log(a, PHASE_FAILED, txHash = v.pendingTxHash, detail = "write interrupted: ${e.message}"); progress(Phase.FAILED)
            return ActionOutcome.AwaitingTag(v.voucherId)
        }
        val back = try { core.protocol.voucherSelectSlot(core.kHex, tag.uidHex, tag.readSlots()) }
            catch (e: CancellationException) { throw e } catch (e: Exception) {
                core.log(a, PHASE_FAILED, txHash = v.pendingTxHash, detail = "readback interrupted"); progress(Phase.FAILED)
                return ActionOutcome.AwaitingTag(v.voucherId)
            }
        if (!(back.record?.voucherIdHex.equals(v.voucherId, true) && back.record?.counter == 0u)) {
            core.log(a, PHASE_FAILED, txHash = v.pendingTxHash, detail = "readback mismatch"); progress(Phase.FAILED)
            return ActionOutcome.AwaitingTag(v.voucherId)
        }
        return finaliseIssue(v, a, progress, "")
    }

    private suspend fun finaliseIssue(v: VoucherEntity, a: PrepaidService.Audit, progress: (Phase) -> Unit, detail: String): ActionOutcome {
        val hash = v.pendingTxHash
        vouchers.updateVoucher(cleared(v).copy(state = VOUCHER_ACTIVE))
        core.log(a, PHASE_WRITTEN, txHash = hash, detail = detail); progress(Phase.WRITTEN)
        core.log(a, PHASE_DONE, txHash = hash, detail = detail); progress(Phase.DONE)
        return ActionOutcome.Done
    }

    // ------------------------------------------------------------ resume / recover
    /** One status check of an unconfirmed issue. True = now committed (row updated), false = gone (row deleted), null = still waiting. */
    private suspend fun checkIssue(v: VoucherEntity, a: PrepaidService.Audit): Boolean? {
        val hash = v.pendingTxHash!!
        val st = core.statusOrNull(hash)
        return when {
            st == TxStatus.CONFIRMED -> { vouchers.updateVoucher(v.copy(state = VOUCHER_ACTIVE, pendingCommitted = true, updatedAt = core.clock()))
                core.log(a, PHASE_COMMITTED, txHash = hash); true }
            st == TxStatus.REJECTED -> { core.log(a, PHASE_FAILED, txHash = hash, detail = "rejected by the node"); vouchers.deleteIssuing(v.voucherId); false }
            st == TxStatus.UNKNOWN && v.pendingSince != null && core.clock() - v.pendingSince >= PrepaidService.PENDING_UNKNOWN_GIVE_UP_MS -> {
                core.log(a, PHASE_FAILED, txHash = hash, detail = "transaction unknown to the node for 30 min; given up"); vouchers.deleteIssuing(v.voucherId); false }
            else -> null
        }
    }

    suspend fun resume(tag: TagPort, voucher: VoucherEntity, progress: (Phase) -> Unit = {}): ActionOutcome = core.exclusive {
        val v = vouchers.voucher(voucher.voucherId) ?: return@exclusive ActionOutcome.Failed("voucher not in the index")
        val hash = v.pendingTxHash ?: return@exclusive ActionOutcome.Done
        if (v.pendingKind != KIND_VOUCHER_ISSUE) return@exclusive resumeSpend(v, tag, progress)
        val a = auditOf(v, KIND_VOUCHER_ISSUE)
        if (!v.pendingCommitted) when (checkIssue(v, a)) {
            null -> return@exclusive ActionOutcome.StillConfirming(hash)
            false -> return@exclusive ActionOutcome.Failed("the issue did not commit")
            true -> progress(Phase.COMMITTED)
        }
        writeTag(tag, vouchers.voucher(v.voucherId)!!, a, progress)
    } ?: busy()

    /** Task 11 replaces this with the void/close status check. */
    internal suspend fun resumeSpend(v: VoucherEntity, tag: TagPort?, progress: (Phase) -> Unit): ActionOutcome = ActionOutcome.StillConfirming(v.pendingTxHash!!)

    /** On page open: re-poll every unconfirmed voucher transaction once. Returns how many rows changed. */
    suspend fun recoverPending(): Int = core.exclusive {
        var changed = 0
        for (v in vouchers.vouchersWithPending()) {
            if (v.pendingKind == KIND_VOUCHER_ISSUE) { if (!v.pendingCommitted && checkIssue(v, auditOf(v, KIND_VOUCHER_ISSUE)) != null) changed++ }
            else if (resumeSpend(v, null, {}) !is ActionOutcome.StillConfirming) changed++
        }
        changed
    } ?: 0

    // ------------------------------------------------------------ VoucherHooks (settlement: Task 13)
    override suspend fun uidHoldsVoucher(uidHex: String) = vouchers.voucherByUid(uidHex)?.state in VOUCHER_LIVE
    override suspend fun voucherStates(): List<VoucherStateFfi> = emptyList()
    override suspend fun apply(res: BatchResultFfi, names: List<String>, terminalLabel: String) = names
    override suspend fun afterSettle() {}
}
```

`PrepaidService.TxResult` and `Audit` must be `internal`. They already are; `TxResult` is `internal sealed class`, so check it's accessible from the same module. Make `PENDING_UNKNOWN_GIVE_UP_MS` reachable (it is a `const val` in the companion; it's public).

- [ ] **Step 4: Run** `--tests '*VoucherServiceIssueTest*'` → 6 passed. Then run the whole suite: failures 0.

- [ ] **Step 5: Mutation probe.** Remove the `voucherHooks.uidHoldsVoucher` line from `PrepaidService.issue`. → `a_tag_that_is_a_live_card_or_live_voucher…` FAILS. Restore it.

- [ ] **Step 6: Commit** `feat(prepaid): issue item vouchers backed by a 69 CKB chain cell` (with the attribution line).

---

### Task 11: `VoucherService`: void, close used-up vouchers, wipe

**Files:**
- Modify: `app/src/main/java/com/wyltek/wallet/prepaid/VoucherService.kt`
- Test: `app/src/test/java/com/wyltek/wallet/prepaid/VoucherServiceRetireTest.kt`

**Interfaces:**
- Produces:
  - `void(voucher, tag: TagPort?, progress): ActionOutcome`
  - `closeUsed(): Int` and `internal closeUsedLocked(): Int`
  - `wipe(tag, voucher): ActionOutcome`
  - a real `resumeSpend`

- [ ] **Step 1: Failing tests**

```kotlin
package com.wyltek.wallet.prepaid

import com.wyltek.wallet.core.chain.TxStatus
import com.wyltek.wallet.core.native.SlotPickFfi
import com.wyltek.wallet.prepaid.db.*
import com.wyltek.wallet.prepaid.fakes.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class VoucherServiceRetireTest {
    private val uid = "04958caa5c5e80"
    private val form = VoucherForm("COF01", "Flat white", 450, "AUD", 1)
    private suspend fun issued(): Pair<VoucherHarness, FakeTag> { val h = VoucherHarness(); val tag = FakeTag(uid); assertEquals(ActionOutcome.Done, h.svc.issue(tag, form)); return h to tag }

    @Test fun void_spends_the_cell_to_the_merchant_and_wipes_the_tag() = runTest {
        val (h, tag) = issued(); val v = h.vstore.voucherByUid(uid)!!
        assertEquals(ActionOutcome.Done, h.svc.void(v, tag))
        assertNull(h.chain.cellAt(v.outpoint))
        val after = h.vstore.voucher(v.voucherId)!!
        assertEquals(VOUCHER_VOID, after.state); assertEquals(0, after.countLeft); assertNull(after.pendingTxHash)
        assertTrue(tag.slot(SlotPickFfi.A).all { it == 0.toByte() })
        assertTrue(h.svc.classify(tag) is TapClass.Blank)
    }

    @Test fun a_missing_cell_never_closes_the_voucher() = runTest {
        val (h, _) = issued(); val v = h.vstore.voucherByUid(uid)!!
        h.chain.cells.remove(v.outpoint)                                  // indistinguishable from an RPC outage
        val out = h.svc.void(v, null)
        assertTrue(out is ActionOutcome.Failed)
        assertEquals(v, h.vstore.voucher(v.voucherId))                    // row untouched: still active, count intact
        assertTrue(h.store.events.none { it.kind == KIND_VOUCHER_VOID })
    }

    @Test fun close_used_spends_only_used_vouchers_and_marks_them_closed() = runTest {
        val (h, _) = issued(); val v = h.vstore.voucherByUid(uid)!!
        assertEquals(0, h.svc.closeUsed())
        h.vstore.updateVoucher(v.copy(state = VOUCHER_USED, countLeft = 0))
        assertEquals(1, h.svc.closeUsed())
        assertEquals(VOUCHER_CLOSED, h.vstore.voucher(v.voucherId)!!.state); assertNull(h.chain.cellAt(v.outpoint))
        assertTrue(h.store.events.any { it.kind == KIND_VOUCHER_CLOSE && it.phase == PHASE_DONE })
    }

    @Test fun a_timed_out_void_is_parked_and_recovered_on_commit() = runTest {
        val (h, _) = issued(); val v = h.vstore.voucherByUid(uid)!!
        h.chain.confirmAfterPolls = null
        assertTrue(h.svc.void(v, null) is ActionOutcome.StillConfirming)
        assertEquals(KIND_VOUCHER_VOID, h.vstore.voucher(v.voucherId)!!.pendingKind)
        h.chain.confirmAfterPolls = 0
        assertEquals(1, h.svc.recoverPending())
        assertEquals(VOUCHER_VOID, h.vstore.voucher(v.voucherId)!!.state)
    }

    @Test fun wipe_only_clears_this_vouchers_record_once_it_is_void() = runTest {
        val (h, tag) = issued(); val v = h.vstore.voucherByUid(uid)!!
        assertTrue(h.svc.wipe(tag, v) is ActionOutcome.Failed)             // still active
        assertEquals(ActionOutcome.Done, h.svc.void(v, null))              // tag absent: record stays
        assertTrue(h.svc.classify(tag) is TapClass.Voucher)
        assertEquals(ActionOutcome.Done, h.svc.wipe(tag, h.vstore.voucher(v.voucherId)!!))
        assertTrue(h.svc.classify(tag) is TapClass.Blank)
    }
}
```

- [ ] **Step 2: Run** → compile errors.

- [ ] **Step 3: Implement.** Add to `VoucherService` (replacing the placeholder `resumeSpend`):

```kotlin
    /** Spend [v]'s cell back to the merchant, ending in [endState]. A cell the indexer cannot see is a FAILURE, never a
     *  close: an RPC outage looks the same (Review Focus 4). Marker before broadcast, as for card retires. */
    private suspend fun spendCell(v: VoucherEntity, kind: String, endState: String, tag: TagPort?, progress: (Phase) -> Unit): ActionOutcome {
        val cell = core.chain.cellAt(v.outpoint) ?: return ActionOutcome.Failed("voucher cell not found on chain; check the RPC and try again")
        val lock = core.signer.lock
        val planned = try {
            PrepaidTxPlanner.plan(listOf(cell), core.chain.cellsByLock(lock), core.excludedOutpoints() - normalizeOutpoint(v.outpoint), emptyList(), lock)
        } catch (e: InsufficientFunds) { return ActionOutcome.Failed(e.message!!) }
        val built = core.build(planned)
        val a = PrepaidService.Audit(core.newActionId(), kind, v.uid, v.voucherId, -v.countLeft.toLong(), v.countLeft, v.tagCounter)
        core.log(a, PHASE_REQUESTED, detail = "${v.countLeft} of ${v.countIssued} uses left"); progress(Phase.REQUESTED)
        vouchers.updateVoucher(v.copy(pendingKind = kind, pendingTxHash = built.txHashHex, pendingActionId = a.actionId, pendingCommitted = false,
            pendingSince = core.clock(), updatedAt = core.clock()))
        return when (val r = core.broadcastAndAwait(a, planned, built, progress)) {
            is PrepaidService.TxResult.Refused -> { vouchers.updateVoucher(v.copy(updatedAt = core.clock())); ActionOutcome.Failed(r.reason) }
            is PrepaidService.TxResult.Rejected -> { vouchers.updateVoucher(v.copy(updatedAt = core.clock())); ActionOutcome.Failed("rejected by the node") }
            is PrepaidService.TxResult.Timeout -> ActionOutcome.StillConfirming(r.txHash)
            is PrepaidService.TxResult.Committed -> finishSpend(vouchers.voucher(v.voucherId)!!, a, endState, tag, r.txHash, progress)
        }
    }

    private fun endStateOf(kind: String?) = if (kind == KIND_VOUCHER_VOID) VOUCHER_VOID else VOUCHER_CLOSED

    private suspend fun finishSpend(v: VoucherEntity, a: PrepaidService.Audit, endState: String, tag: TagPort?, hash: String, progress: (Phase) -> Unit): ActionOutcome {
        vouchers.updateVoucher(cleared(v).copy(state = endState, countLeft = 0))
        val wiped = if (endState == VOUCHER_VOID) wipeTag(tag, v) else "tag left as is"
        core.log(a, PHASE_DONE, balanceAfter = 0, txHash = hash, detail = wiped); progress(Phase.DONE)
        return ActionOutcome.Done
    }

    private suspend fun wipeTag(tag: TagPort?, v: VoucherEntity): String {
        if (tag == null || !tag.uidHex.equals(v.uid, true)) return "tag absent"
        return try { tag.zeroSlot(SlotPickFfi.A); tag.zeroSlot(SlotPickFfi.B); "tag wiped" } catch (e: TagLostException) { "tag wipe interrupted: ${e.message}" }
    }

    internal suspend fun resumeSpend(v: VoucherEntity, tag: TagPort?, progress: (Phase) -> Unit): ActionOutcome {
        val hash = v.pendingTxHash!!
        val a = auditOf(v, v.pendingKind!!)
        val st = core.statusOrNull(hash)
        return when {
            st == TxStatus.CONFIRMED -> { core.log(a, PHASE_COMMITTED, txHash = hash); finishSpend(v, a, endStateOf(v.pendingKind), tag, hash, progress) }
            st == TxStatus.REJECTED -> { core.log(a, PHASE_FAILED, txHash = hash, detail = "rejected by the node"); vouchers.updateVoucher(cleared(v)); ActionOutcome.Failed("rejected by the node") }
            st == TxStatus.UNKNOWN && v.pendingSince != null && core.clock() - v.pendingSince >= PrepaidService.PENDING_UNKNOWN_GIVE_UP_MS -> {
                core.log(a, PHASE_FAILED, txHash = hash, detail = "transaction unknown to the node for 30 min; given up"); vouchers.updateVoucher(cleared(v))
                ActionOutcome.Failed("transaction unknown to the node; given up") }
            else -> ActionOutcome.StillConfirming(hash)
        }
    }

    suspend fun void(voucher: VoucherEntity, tag: TagPort?, progress: (Phase) -> Unit = {}): ActionOutcome = core.exclusive {
        val v = vouchers.voucher(voucher.voucherId) ?: return@exclusive ActionOutcome.Failed("voucher not in the index")
        if (v.state != VOUCHER_ACTIVE) return@exclusive ActionOutcome.Failed("voucher is ${v.state}")
        if (v.pendingTxHash != null && !(v.pendingKind == KIND_VOUCHER_ISSUE && v.pendingCommitted))
            return@exclusive ActionOutcome.Failed("finish the pending action first (tap the voucher)")
        spendCell(cleared(v), KIND_VOUCHER_VOID, VOUCHER_VOID, tag?.takeIf { it.uidHex.equals(v.uid, true) }, progress)
    } ?: busy()

    /** Spend the cell of every used-up voucher. Called after each settlement (inside its lock) and on page open. */
    internal suspend fun closeUsedLocked(): Int {
        var closed = 0
        for (v in vouchers.allVouchers().filter { it.state == VOUCHER_USED && it.pendingTxHash == null })
            if (spendCell(v, KIND_VOUCHER_CLOSE, VOUCHER_CLOSED, null, {}) == ActionOutcome.Done) closed++
        return closed
    }
    suspend fun closeUsed(): Int = core.exclusive { closeUsedLocked() } ?: 0

    /** Wipe a void, used or closed voucher's record from its tag. Only this voucher's own record is wiped. */
    suspend fun wipe(tag: TagPort, voucher: VoucherEntity): ActionOutcome = core.exclusive {
        val v = vouchers.voucher(voucher.voucherId) ?: return@exclusive ActionOutcome.Failed("voucher not in the index")
        if (v.state !in setOf(VOUCHER_VOID, VOUCHER_USED, VOUCHER_CLOSED)) return@exclusive ActionOutcome.Failed("voucher is ${v.state}; void it first")
        if (!tag.uidHex.equals(v.uid, true)) return@exclusive ActionOutcome.Failed("this is a different tag")
        val slot = try { core.protocol.voucherSelectSlot(core.kHex, tag.uidHex, tag.readSlots()) }
            catch (e: CancellationException) { throw e } catch (e: Exception) { return@exclusive ActionOutcome.NeedsTag(v.voucherId) }
        if (slot.pick == SlotPickFfi.NONE && slot.reason == SlotReason.NO_VALID_SLOT) return@exclusive ActionOutcome.Done
        if (slot.record?.voucherIdHex?.equals(v.voucherId, true) != true) return@exclusive ActionOutcome.Failed("the tag does not carry this voucher; not wiped")
        val a = PrepaidService.Audit(core.newActionId(), KIND_REPAIR, v.uid, v.voucherId, 0, 0, v.tagCounter)
        core.log(a, PHASE_REQUESTED, detail = "wipe ${v.state} voucher")
        val wiped = wipeTag(tag, v)
        if (wiped != "tag wiped") { core.log(a, PHASE_FAILED, detail = wiped); return@exclusive ActionOutcome.NeedsTag(v.voucherId) }
        core.log(a, PHASE_DONE, detail = wiped)
        ActionOutcome.Done
    } ?: busy()
```

In `recoverPending`, the existing `else if (resumeSpend(…))` branch now does real work. Also call `closeUsedLocked()` at the end of `recoverPending` and add its count to `changed`.

- [ ] **Step 4: Run** `--tests '*VoucherService*'` → 11 passed; the whole suite: failures 0.

- [ ] **Step 5: Mutation probe.** Change `spendCell`'s first line to `val cell = core.chain.cellAt(v.outpoint) ?: run { vouchers.updateVoucher(cleared(v).copy(state = endState)); return ActionOutcome.Done }`. → `a_missing_cell_never_closes_the_voucher` FAILS. Restore it.

- [ ] **Step 6: Commit** `feat(prepaid): void, close and wipe vouchers; a missing cell never closes one` (with the attribution line).

---

### Task 12: `StaffKeyService`: enrol, revoke, list

**Files:**
- Create: `app/src/main/java/com/wyltek/wallet/prepaid/StaffKeyService.kt`
- Test: `app/src/test/java/com/wyltek/wallet/prepaid/StaffKeyServiceTest.kt`

**Interfaces:**
- Consumes: `Secp128r1.verifyNtagOriginality`, `Protocol.staffKeyId`, `VoucherStore` staff methods, `PrepaidStore.event`
- Produces:
  - `class StaffKeyService(store: PrepaidStore, vouchers: VoucherStore, protocol: Protocol, clock, newActionId, verifySig: (ByteArray, ByteArray) -> Boolean = Secp128r1::verifyNtagOriginality)`
  - `enrol(tag, label): ActionOutcome`, `revoke(keyId): ActionOutcome`, `active(): List<StaffKeyEntity>`

- [ ] **Step 1: Failing tests**

```kotlin
package com.wyltek.wallet.prepaid

import com.wyltek.wallet.prepaid.db.*
import com.wyltek.wallet.prepaid.fakes.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class StaffKeyServiceTest {
    private val store = InMemoryPrepaidStore(); private val vstore = InMemoryVoucherStore(); private var now = 1L; private var ids = 0
    private var genuine = true
    private val svc = StaffKeyService(store, vstore, FakeProtocol(), { now++ }, { "s${++ids}" }, verifySig = { _, _ -> genuine })

    @Test fun enrol_stores_uid_and_signature_and_audits() = runTest {
        val tag = FakeTag("04958caa5c5e80")
        assertEquals(ActionOutcome.Done, svc.enrol(tag, "Phill's ring"))
        val k = svc.active().single()
        assertEquals("04958caa5c5e80", k.uid); assertEquals("5a".repeat(32), k.sigHex); assertEquals("Phill's ring", k.label); assertEquals(16, k.keyId.length)
        assertEquals(KIND_STAFF_ENROL, store.events.single().kind)
    }
    @Test fun a_tag_that_fails_nxp_verification_is_refused_and_nothing_is_stored() = runTest {
        genuine = false
        assertEquals(ActionOutcome.Failed(StaffKeyService.NOT_GENUINE), svc.enrol(FakeTag("04958caa5c5e80"), "x"))
        assertTrue(svc.active().isEmpty()); assertTrue(store.events.isEmpty())
    }
    @Test fun enrolling_twice_is_refused_and_a_blank_label_is_refused() = runTest {
        assertEquals(ActionOutcome.Done, svc.enrol(FakeTag("04958caa5c5e80"), "a"))
        assertTrue(svc.enrol(FakeTag("04958caa5c5e80"), "b") is ActionOutcome.Failed)
        assertTrue(svc.enrol(FakeTag("04ebbb02cd2a81"), "  ") is ActionOutcome.Failed)
    }
    @Test fun a_lost_tag_needs_the_tag_again() = runTest {
        val t = FakeTag("04958caa5c5e80").also { it.present = false }
        assertTrue(svc.enrol(t, "a") is ActionOutcome.NeedsTag)
    }
    @Test fun revoke_drops_it_from_active_and_audits_and_a_revoked_key_can_be_enrolled_again() = runTest {
        svc.enrol(FakeTag("04958caa5c5e80"), "a"); val id = svc.active().single().keyId
        assertEquals(ActionOutcome.Done, svc.revoke(id))
        assertTrue(svc.active().isEmpty()); assertEquals(KIND_STAFF_REVOKE, store.events.last().kind)
        assertTrue(svc.revoke(id) is ActionOutcome.Failed)
        assertEquals(ActionOutcome.Done, svc.enrol(FakeTag("04958caa5c5e80"), "again"))
        assertEquals(1, svc.active().size)
    }
}
```

- [ ] **Step 2: Run** → compile errors.

- [ ] **Step 3: Implement**

```kotlin
package com.wyltek.wallet.prepaid

import com.wyltek.wallet.prepaid.db.*
import kotlinx.coroutines.CancellationException

/** Vouchers spec §5.1. A staff key is any genuine NXP NTAG21x tag, identified by (UID, originality signature).
 *  Nothing is written to the tag. The POS only compares bytes; the ECC check happens here, once. */
class StaffKeyService(
    private val store: PrepaidStore,
    private val vouchers: VoucherStore,
    private val protocol: Protocol,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val newActionId: () -> String = { java.util.UUID.randomUUID().toString() },
    private val verifySig: (ByteArray, ByteArray) -> Boolean = Secp128r1::verifyNtagOriginality,
) {
    companion object { const val NOT_GENUINE = "not a genuine NXP tag"; const val MAX_ACTIVE = 16 }

    private suspend fun audit(kind: String, uid: String, keyId: String, detail: String) = store.event(PrepaidEventEntity(atMs = clock(),
        actionId = newActionId(), kind = kind, phase = PHASE_DONE, reserveId = keyId, uid = uid, amountUnits = 0, balanceBefore = 0,
        balanceAfter = 0, counterBefore = 0, counterAfter = 0, txHash = null, detail = detail))

    suspend fun enrol(tag: TagPort, label: String): ActionOutcome {
        if (label.isBlank()) return ActionOutcome.Failed("enter a label")
        val sig = try { tag.readSignature() } catch (e: CancellationException) { throw e } catch (e: Exception) { return ActionOutcome.NeedsTag(tag.uidHex) }
        if (!verifySig(hexBytes(tag.uidHex), sig)) return ActionOutcome.Failed(NOT_GENUINE)
        val keyId = protocol.staffKeyId(tag.uidHex, bytesHex(sig))
        val existing = vouchers.staffKey(keyId)
        if (existing?.revokedAt == null && existing != null) return ActionOutcome.Failed("this tag is already a staff key (${existing.label})")
        if (vouchers.activeStaffKeys().size >= MAX_ACTIVE) return ActionOutcome.Failed("limit of $MAX_ACTIVE staff keys reached")
        val row = StaffKeyEntity(keyId, tag.uidHex.lowercase(), bytesHex(sig), label.trim(), clock(), null)
        if (existing != null) vouchers.updateStaffKey(row) else vouchers.insertStaffKey(row)
        audit(KIND_STAFF_ENROL, row.uid, keyId, "enrolled \"${row.label}\"")
        return ActionOutcome.Done
    }

    suspend fun revoke(keyId: String): ActionOutcome {
        val k = vouchers.staffKey(keyId)?.takeIf { it.revokedAt == null } ?: return ActionOutcome.Failed("no active staff key $keyId")
        vouchers.updateStaffKey(k.copy(revokedAt = clock()))
        audit(KIND_STAFF_REVOKE, k.uid, keyId, "revoked \"${k.label}\"")
        return ActionOutcome.Done
    }

    suspend fun active(): List<StaffKeyEntity> = vouchers.activeStaffKeys()
}
```

- [ ] **Step 4: Run** → 5 passed; the whole suite: failures 0.

- [ ] **Step 5: Mutation probe.** Replace `if (!verifySig(…)) return …` with nothing. → `a_tag_that_fails_nxp_verification…` FAILS. Restore it.

- [ ] **Step 6: Commit** `feat(prepaid): enrol and revoke staff keys by NXP originality signature` (with the attribution line).

---

### Task 13: Settlement: redeems, overrides, closing used-up vouchers

**Files:**
- Modify:
  - `app/src/main/java/com/wyltek/wallet/prepaid/PrepaidService.kt` (`settleLocked`)
  - `app/src/main/java/com/wyltek/wallet/prepaid/VoucherService.kt` (the three hook methods)
- Test: `app/src/test/java/com/wyltek/wallet/prepaid/VoucherSettleTest.kt`

**Interfaces:**
- Consumes: `BatchResultFfi.redeems/overrides`, `VoucherStore.applyRedeems/seen/insertSeen/seenForVoucher`, `closeUsedLocked`
- Produces: `settle(...)` answers voucher and override verdicts; `retry` turns only card entries (kinds 1/2) into retries

- [ ] **Step 1: Failing tests** (`FakeProtocol.verifyResult` drives the verdicts; the real verifier is pinned in Rust)

```kotlin
package com.wyltek.wallet.prepaid

import com.wyltek.wallet.core.native.*
import com.wyltek.wallet.prepaid.db.*
import com.wyltek.wallet.prepaid.fakes.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class VoucherSettleTest {
    private val uid = "04958caa5c5e80"
    private val form = VoucherForm("COF01", "Flat white", 450, "AUD", 1)
    /** A 96-byte entry of [kind] (only byte 1 matters to Kotlin; the fake verifier decides the verdicts). */
    private fun entry(kind: Int) = "02" + "%02x".format(kind) + "00".repeat(94)

    @Test fun accepted_redeem_counts_down_records_the_entry_and_closes_the_used_voucher() = runTest {
        val h = VoucherHarness(); assertEquals(ActionOutcome.Done, h.svc.issue(FakeTag(uid), form))
        val v = h.vstore.voucherByUid(uid)!!
        h.protocol.verifyResult = BatchResultFfi(listOf(JournalVerdict.ACCEPTED), emptyList(),
            listOf(RedeemFfi(0u, 3u, v.voucherId, 1u, "ee".repeat(32))), emptyList())
        val r = h.core.settle(entry(3), "t")!!
        assertEquals(listOf("accepted"), r.verdicts)
        assertEquals(listOf(VoucherStateFfi(v.voucherId, 1u, false, emptyList())), h.protocol.lastVouchers)
        val after = h.vstore.voucher(v.voucherId)!!
        assertEquals(0, after.countLeft); assertEquals(1L, after.tagCounter); assertEquals(VOUCHER_CLOSED, after.state)
        assertNull(h.chain.cellAt(v.outpoint))
        assertEquals("ee".repeat(32), h.vstore.seenForVoucher(v.voucherId).single().entryHash)
        assertTrue(h.store.events.any { it.kind == KIND_VOUCHER_REDEEM && it.phase == PHASE_DONE })
    }

    @Test fun settled_entries_are_handed_back_to_the_verifier() = runTest {
        val h = VoucherHarness(); h.svc.issue(FakeTag(uid), form.copy(count = 3))
        val v = h.vstore.voucherByUid(uid)!!
        h.protocol.verifyResult = BatchResultFfi(listOf(JournalVerdict.ACCEPTED), emptyList(), listOf(RedeemFfi(0u, 3u, v.voucherId, 1u, "ee".repeat(32))), emptyList())
        h.core.settle(entry(3), "t")
        h.protocol.verifyResult = BatchResultFfi(listOf(JournalVerdict.DUPLICATE), emptyList(), emptyList(), emptyList())
        assertEquals(listOf("duplicate"), h.core.settle(entry(3), "t")!!.verdicts)
        assertEquals(listOf(SettledRedeemFfi(1u, "ee".repeat(32))), h.protocol.lastVouchers.single().settled)
        assertEquals(2, h.vstore.voucher(v.voucherId)!!.countLeft)
    }

    @Test fun an_override_is_audited_once_and_a_resend_is_duplicate() = runTest {
        val h = VoucherHarness()
        val o = OverrideFfi(0u, 4u, "5a".repeat(8), 500, 0, "AUD", "77".repeat(8), "dd".repeat(32))
        h.protocol.verifyResult = BatchResultFfi(listOf(JournalVerdict.ACCEPTED), emptyList(), emptyList(), listOf(o))
        assertEquals(listOf("accepted"), h.core.settle(entry(4), "t")!!.verdicts)
        assertEquals(listOf("duplicate"), h.core.settle(entry(4), "t")!!.verdicts)
        assertEquals(1, h.store.events.count { it.kind == KIND_PRICE_OVERRIDE })
    }

    @Test fun new_verdicts_have_wire_names_and_unsupported_kinds_are_retried() = runTest {
        val h = VoucherHarness()
        h.protocol.verifyResult = BatchResultFfi(listOf(JournalVerdict.DOUBLE_REDEEM, JournalVerdict.UNKNOWN_VOUCHER, JournalVerdict.VOUCHER_VOID, JournalVerdict.UNSUPPORTED_KIND),
            emptyList(), emptyList(), emptyList())
        assertEquals(listOf("double_redeem", "unknown_voucher", "voucher_void", "retry"), h.core.settle(entry(3) + entry(3) + entry(3) + entry(9), "t")!!.verdicts)
    }

    @Test fun a_failed_card_batch_retries_card_entries_only() = runTest {
        val h = VoucherHarness(); h.svc.issue(FakeTag(uid), form.copy(count = 2))
        val v = h.vstore.voucherByUid(uid)!!
        val cardEntry = "0201" + "00".repeat(94)
        h.store.insertCard(PrepaidCardEntity("ab".repeat(32), "04ebbb02cd2a81", null, "0xff:0", "0xff:0", 1000, 0, 0, CARD_ACTIVE, issuedAt = 0, updatedAt = 0))
        h.protocol.verifyResult = BatchResultFfi(listOf(JournalVerdict.ACCEPTED, JournalVerdict.ACCEPTED),
            listOf(CardNetFfi("ab".repeat(32), 100, 1u, 1u)), listOf(RedeemFfi(1u, 3u, v.voucherId, 1u, "ee".repeat(32))), emptyList())
        val r = h.core.settle(cardEntry + entry(3), "t")!!                // card reserve cell "0xff:0" is missing → card entry retried
        assertEquals(listOf("retry", "accepted"), r.verdicts)
        assertEquals(1, h.vstore.voucher(v.voucherId)!!.countLeft)
    }
}
```

- [ ] **Step 2: Run** → failures (hooks are no-ops).

- [ ] **Step 3: Implement.** In `PrepaidService`:

```kotlin
    private fun entryKind(hexBody: String, i: Int): Int = hexBody.substring(i * 192 + 2, i * 192 + 4).toInt(16)
    private val CARD_KINDS = setOf(0x01, 0x02)

    private suspend fun settleLocked(hexBody: String, terminalLabel: String): SettleResult {
        val r = settleBatchLocked(hexBody, terminalLabel)
        try { voucherHooks.afterSettle() } catch (e: CancellationException) { throw e } catch (e: Exception) { /* retried next settle / page open */ }
        return r
    }
```

Rename the existing `settleLocked` body to `private suspend fun settleBatchLocked(hexBody: String, terminalLabel: String): SettleResult`. Inside it:
- Pass `voucherHooks.voucherStates()` as the 4th `verifyBatch` argument.
- Directly after `var names = …`, add `names = voucherHooks.apply(res, names, terminalLabel)`.
- Change `fun retryAll() = SettleResult(names.map { if (it == "accepted") "retry" else it }, null)` to `fun retryAll() = SettleResult(names.mapIndexed { i, v -> if (v == "accepted" && entryKind(hexBody, i) in CARD_KINDS) "retry" else v }, null)`.
- The existing per-card `missing` mapping already filters by reserve id, so leave it.

In `VoucherService`, replace the three placeholder hooks:

```kotlin
    override suspend fun voucherStates(): List<VoucherStateFfi> = vouchers.allVouchers().filter { it.state != VOUCHER_ISSUING }.map { v ->
        VoucherStateFfi(v.voucherId, v.countLeft.coerceIn(0, 255).toUByte(), v.state == VOUCHER_VOID,
            vouchers.seenForVoucher(v.voucherId).mapNotNull { s -> s.counter?.let { SettledRedeemFfi(it.toUInt(), s.entryHash) } })
    }

    override suspend fun apply(res: BatchResultFfi, names: List<String>, terminalLabel: String): List<String> {
        val out = names.toMutableList()
        val now = core.clock()
        if (res.redeems.isNotEmpty()) {
            val updated = res.redeems.groupBy { it.voucherIdHex.lowercase() }.mapNotNull { (id, rs) ->
                vouchers.voucher(id)?.let { v -> v to rs.sortedBy { it.counter } } }
            val rows = updated.map { (v, rs) ->
                val left = maxOf(0, v.countLeft - rs.size)
                v.copy(countLeft = left, state = if (left == 0 && v.state == VOUCHER_ACTIVE) VOUCHER_USED else v.state,
                    tagCounter = maxOf(v.tagCounter, rs.maxOf { it.counter.toLong() }), updatedAt = now)
            }
            val seen = updated.flatMap { (v, rs) -> rs.map { JournalSeenEntity(it.entryHashHex, KIND_VOUCHER_REDEEM, v.voucherId, it.counter.toLong(), it.terminalId.toInt(), now) } }
            vouchers.applyRedeems(rows, seen)
            for ((v, rs) in updated) rs.forEachIndexed { i, r ->
                val before = v.countLeft - i
                core.log(PrepaidService.Audit(core.newActionId(), KIND_VOUCHER_REDEEM, v.uid, v.voucherId, 1, before, r.counter.toLong() - 1), PHASE_DONE,
                    balanceAfter = maxOf(0, before - 1), counterAfter = r.counter.toLong(), detail = "$terminalLabel; terminal ${r.terminalId}; counter ${r.counter}")
            }
        }
        for (o in res.overrides) {
            if (vouchers.seen(o.entryHashHex) != null) { out[o.index.toInt()] = "duplicate"; continue }
            vouchers.insertSeen(JournalSeenEntity(o.entryHashHex, KIND_PRICE_OVERRIDE, null, null, o.terminalId.toInt(), now))
            core.log(PrepaidService.Audit(core.newActionId(), KIND_PRICE_OVERRIDE, "", null, o.newPrice.toLong(), o.oldPrice, 0), PHASE_DONE,
                balanceAfter = o.newPrice, detail = "terminal ${o.terminalId}; product ${o.productHashHex}; ${o.oldPrice} -> ${o.newPrice} ${o.currency} (minor units); staff key ${o.keyIdHex}; $terminalLabel")
        }
        return out
    }

    override suspend fun afterSettle() { closeUsedLocked() }
```

- [ ] **Step 4: Run** `--tests '*VoucherSettleTest*' --tests '*PrepaidServiceSettleTest*'` → all pass; the whole suite: failures 0.

- [ ] **Step 5: Mutation probe.** In `apply`, delete the `if (vouchers.seen(o.entryHashHex) != null) …` line. → `an_override_is_audited_once…` FAILS. Restore it.

- [ ] **Step 6: Commit** `feat(prepaid): settle voucher redeems and price overrides; close used-up vouchers` (with the attribution line).

---

### Task 14: Gateway: `GET /v1/prepaid/staff` and service wiring

**Files:**
- Modify:
  - `app/src/main/java/com/wyltek/wallet/agent/server/AgentDtos.kt`
  - `app/src/main/java/com/wyltek/wallet/agent/server/AgentDispatchPort.kt`
  - `app/src/main/java/com/wyltek/wallet/agent/server/AgentRouter.kt`
  - `app/src/main/java/com/wyltek/wallet/agent/AgentGateway.kt`
  - `app/src/main/java/com/wyltek/wallet/agent/PrepaidGatewayMapping.kt`
- Test:
  - `app/src/test/java/com/wyltek/wallet/agent/server/PrepaidRoutesTest.kt`
  - `app/src/test/java/com/wyltek/wallet/agent/PrepaidGatewayMappingTest.kt`

**Interfaces:**
- Produces:
  - `StaffKeyDto(key_id, uid, sig)`, `StaffKeysResponse(staff)`, `PrepaidStaffResult { BadToken; Ok(list) }`
  - `AgentDispatchPort.prepaidStaff(token, sourceIp, nowUnix)`
  - `PrepaidGatewayMapping.staffDtoOf(StaffKeyEntity): StaffKeyDto`
  - `AgentGateway.vouchers: VoucherService?`, `AgentGateway.staffKeys: StaffKeyService?`, `AgentGateway.voucherStore(): VoucherStore`

- [ ] **Step 1: Failing tests.** Add to `PrepaidRoutesTest`, and extend its `port(...)` helper's object with `override suspend fun prepaidStaff(token: String, sourceIp: String, nowUnix: Long) = staff` (add a third parameter `staff: PrepaidStaffResult = PrepaidStaffResult.BadToken`):

```kotlin
    @Test fun staff_ok_lists_keys_and_needs_a_token() = runBlocking {
        val p = port(PrepaidJournalResult.BadToken, PrepaidCardsResult.BadToken, PrepaidStaffResult.Ok(listOf(StaffKeyDto("77".repeat(8), "04958caa5c5e80", "5a".repeat(32)))))
        val r = routeAgentRequest("GET", "/v1/prepaid/staff", hdr, "", p)
        assertEquals(200, r.status)
        assertEquals("""{"staff":[{"key_id":"${"77".repeat(8)}","uid":"04958caa5c5e80","sig":"${"5a".repeat(32)}"}]}""", r.body)
        assertEquals(401, routeAgentRequest("GET", "/v1/prepaid/staff", noHdr, "", p).status)
        assertEquals(401, routeAgentRequest("GET", "/v1/prepaid/staff", hdr, "", port(PrepaidJournalResult.BadToken, PrepaidCardsResult.BadToken)).status)
    }
```

And to `PrepaidGatewayMappingTest`:

```kotlin
    @Test fun staff_dto_carries_uid_and_signature_lower_case() {
        val d = PrepaidGatewayMapping.staffDtoOf(com.wyltek.wallet.prepaid.db.StaffKeyEntity("77".repeat(8), "04958CAA5C5E80", "5A".repeat(32), "ring", 1, null))
        assertEquals(StaffKeyDto("77".repeat(8), "04958caa5c5e80", "5a".repeat(32)), d)
    }
```

- [ ] **Step 2: Run** → compile errors.

- [ ] **Step 3: Implement.**

`AgentDtos.kt`:
```kotlin
@Serializable data class StaffKeyDto(@SerialName("key_id") val keyId: String, val uid: String, val sig: String)
@Serializable data class StaffKeysResponse(val staff: List<StaffKeyDto>)
sealed class PrepaidStaffResult {
    data object BadToken : PrepaidStaffResult()
    data class Ok(val staff: List<StaffKeyDto>) : PrepaidStaffResult()
}
```

`AgentDispatchPort.kt`:
```kotlin
    /** Vouchers spec §5.2: active staff keys for terminals. Token in `x-device-token`, scope `prepaid_settle`. */
    suspend fun prepaidStaff(token: String, sourceIp: String, nowUnix: Long): PrepaidStaffResult = PrepaidStaffResult.BadToken
```

`AgentRouter.kt` (next to `/v1/prepaid/cards`; add the route to the KDoc list at the top):
```kotlin
        method == "GET" && path == "/v1/prepaid/staff" -> {
            val token = header("x-device-token") ?: return RouteResult(401)
            when (val r = port.prepaidStaff(token, sourceIp, System.currentTimeMillis() / 1000)) {
                PrepaidStaffResult.BadToken -> RouteResult(401)
                is PrepaidStaffResult.Ok -> RouteResult(200, "application/json", json.encodeToString(StaffKeysResponse.serializer(), StaffKeysResponse(r.staff)))
            }
        }
```

`PrepaidGatewayMapping.kt`:
```kotlin
    fun staffDtoOf(k: com.wyltek.wallet.prepaid.db.StaffKeyEntity) = com.wyltek.wallet.agent.server.StaffKeyDto(k.keyId.lowercase(), k.uid.lowercase(), k.sigHex.lowercase())
```

`AgentGateway.kt`:
```kotlin
    fun voucherStore(): VoucherStore = RoomVoucherStore(db)
    @Volatile private var voucherHolder: VoucherService? = null
    /** Built on first use from the prepaid service; installing it sets the settle hooks. */
    val vouchers: VoucherService?
        get() = voucherHolder ?: synchronized(this) { voucherHolder ?: prepaid?.let { VoucherService(it, voucherStore()) }.also { voucherHolder = it } }
    val staffKeys: StaffKeyService?
        get() = prepaid?.let { StaffKeyService(prepaidStore(), voucherStore(), NativeProtocol()) }

    override suspend fun prepaidStaff(token: String, sourceIp: String, nowUnix: Long): PrepaidStaffResult {
        if (!prepaidAuth(token, sourceIp, nowUnix)) return PrepaidStaffResult.BadToken
        return PrepaidStaffResult.Ok(voucherStore().activeStaffKeys().take(StaffKeyService.MAX_ACTIVE).map(PrepaidGatewayMapping::staffDtoOf))
    }
```

Also, in `prepaidJournal`, call `vouchers` before `svc.settle(...)` (`vouchers` is a no-op read that installs the hooks): `val svc = prepaid ?: …; vouchers` on its own line, with the comment `// installs the voucher settle hooks`.

- [ ] **Step 4: Run** the two test classes, then the whole suite: failures 0.

- [ ] **Step 5: Commit** `feat(prepaid): GET /v1/prepaid/staff and gateway wiring for vouchers` (with the attribution line).

---

### Task 15: UI: issue vouchers, voucher actions, voucher list, staff keys screen

**Files:**
- Create:
  - `app/src/main/java/com/wyltek/wallet/prepaid/VoucherMoney.kt`
  - `app/src/main/java/com/wyltek/wallet/prepaid/ui/StaffKeysScreen.kt`
  - `app/src/main/java/com/wyltek/wallet/prepaid/ui/VoucherDetailScreen.kt`
- Modify:
  - `app/src/main/java/com/wyltek/wallet/prepaid/ui/PrepaidViewModel.kt`
  - `app/src/main/java/com/wyltek/wallet/prepaid/ui/PrepaidTapSheet.kt`
  - `app/src/main/java/com/wyltek/wallet/prepaid/ui/PrepaidScreen.kt`
  - `app/src/main/java/com/wyltek/wallet/ui/navigation/AppNavigation.kt` (and its `Screen` sealed class)
- Test:
  - `app/src/test/java/com/wyltek/wallet/prepaid/VoucherMoneyTest.kt`
  - `app/src/test/java/com/wyltek/wallet/prepaid/PrepaidUiStateTest.kt` (new cases)

**Interfaces:**
- Produces:
  - `TapAction` gains `ISSUE_VOUCHER, VOID_VOUCHER, WIPE_VOUCHER, RESUME_VOUCHER`
  - `VoucherMoney.parse(text, currency): Long?`, `VoucherMoney.format(minor, currency): String`
  - `PrepaidViewModel.issueVoucher(c, form)`, `voidVoucher(v, c)`, `wipeVoucher(c)`, `resumeVoucher(c)`, `vouchersFlow()`, `voucher(id)`
  - `StaffKeysViewModel`
  - Routes `prepaid-staff` and `prepaid-voucher/{voucherId}`

- [ ] **Step 1: Failing pure tests**

`VoucherMoneyTest.kt`:
```kotlin
package com.wyltek.wallet.prepaid

import org.junit.Assert.*
import org.junit.Test

class VoucherMoneyTest {
    @Test fun two_decimal_currencies() {
        assertEquals(450L, VoucherMoney.parse("4.50", "AUD")); assertEquals(400L, VoucherMoney.parse("4", "AUD")); assertEquals(5L, VoucherMoney.parse("0.05", "USD"))
        assertNull(VoucherMoney.parse("4.555", "AUD")); assertNull(VoucherMoney.parse("-1", "AUD")); assertNull(VoucherMoney.parse("abc", "AUD")); assertNull(VoucherMoney.parse("", "AUD"))
        assertNull(VoucherMoney.parse("42949672.96", "AUD"), "above u32 minor units")
        assertEquals("4.50 AUD", VoucherMoney.format(450, "AUD"))
    }
    @Test fun zero_decimal_currencies() {
        assertEquals(500L, VoucherMoney.parse("500", "JPY")); assertNull(VoucherMoney.parse("500.5", "JPY"))
        assertEquals("500 JPY", VoucherMoney.format(500, "JPY"))
    }
}
```

Add to `PrepaidUiStateTest` (it already has `card(...)`, `rec`, `slot`):

```kotlin
    private fun voucher(state: String, pending: String? = null) = VoucherEntity("c0".repeat(16), "u", "COF01", "Flat white", "5a".repeat(8), 450, "AUD", 1, 1, "x:0", state, pendingTxHash = pending, issuedAt = 0, updatedAt = 0)
    private val vrec = VoucherRecordInfo(0u, "c0".repeat(16), "5a".repeat(8), 450u, "AUD", 1u, 0u, "m")

    @Test fun voucher_actions_follow_their_state() {
        assertEquals(listOf(TapAction.ISSUE, TapAction.ISSUE_VOUCHER), PrepaidUiReducer.tapActions(TapClass.Blank("u"), true))
        assertEquals(listOf(TapAction.VOID_VOUCHER), PrepaidUiReducer.tapActions(TapClass.Voucher(voucher(VOUCHER_ACTIVE), vrec), true))
        for (s in listOf(VOUCHER_VOID, VOUCHER_USED, VOUCHER_CLOSED)) assertEquals(listOf(TapAction.WIPE_VOUCHER), PrepaidUiReducer.tapActions(TapClass.Voucher(voucher(s), vrec), true))
        assertEquals(listOf(TapAction.RESUME_VOUCHER), PrepaidUiReducer.tapActions(TapClass.VoucherResume(voucher(VOUCHER_ACTIVE, "0xab")), false))
        assertEquals(listOf(TapAction.VOID_VOUCHER), PrepaidUiReducer.tapActions(TapClass.VoucherDamaged(voucher(VOUCHER_ACTIVE), "x"), true))
    }
```

Also update `actions_follow_the_classification_table`: Blank now expects `listOf(TapAction.ISSUE, TapAction.ISSUE_VOUCHER)`.

- [ ] **Step 2: Run** → compile errors.

- [ ] **Step 3: Implement `VoucherMoney`**

```kotlin
package com.wyltek.wallet.prepaid

import java.math.BigDecimal

/** Shop-currency prices in minor units (vouchers spec §4: issue_price u32). Zero-decimal currencies per ISO 4217. */
object VoucherMoney {
    private val ZERO_DECIMAL = setOf("JPY", "KRW", "VND", "CLP", "ISK", "UGX")
    fun exponent(currency: String) = if (currency in ZERO_DECIMAL) 0 else 2
    fun parse(text: String, currency: String): Long? {
        val d = text.trim().toBigDecimalOrNull() ?: return null
        if (d.signum() < 0 || d.scale() > exponent(currency)) return null
        val minor = d.movePointRight(exponent(currency))
        return minor.toLong().takeIf { minor <= BigDecimal(4_294_967_295L) }
    }
    fun format(minor: Long, currency: String): String = BigDecimal(minor).movePointLeft(exponent(currency)).toPlainString() + " " + currency
}
```

- [ ] **Step 4: Reducer and view model.**

In `PrepaidUiReducer.tapActionsWithTag`:
- `is TapClass.Blank -> listOf(TapAction.ISSUE, TapAction.ISSUE_VOUCHER)`
- `is TapClass.Voucher -> if (tap.voucher.state == VOUCHER_ACTIVE) listOf(TapAction.VOID_VOUCHER) else if (tap.voucher.state in setOf(VOUCHER_VOID, VOUCHER_USED, VOUCHER_CLOSED)) listOf(TapAction.WIPE_VOUCHER) else emptyList()`
- `is TapClass.VoucherResume -> listOf(TapAction.RESUME_VOUCHER)`
- `is TapClass.VoucherDamaged -> if (tap.voucher.state == VOUCHER_ACTIVE) listOf(TapAction.VOID_VOUCHER) else emptyList()`

In `tapActions`, the no-tag branch: `else if (tap is TapClass.Resume) listOf(TapAction.RESUME) else if (tap is TapClass.VoucherResume) listOf(TapAction.RESUME_VOUCHER) else emptyList()`.

`PrepaidViewModel`:

```kotlin
    private val vsvc: VoucherService? = gateway.vouchers
```
- In `init`, after `svc?.recoverPending()`, add `vsvc?.recoverPending()`.
- In `onTag`, replace `s.classify(port)` with `(vsvc?.classify(port) ?: s.classify(port))`.
- Add:

```kotlin
    fun issueVoucher(c: TapContext?, code: String, name: String, priceText: String, currency: String, countText: String) = act(c) { _, t ->
        val v = vsvc ?: return@act ActionOutcome.Failed("vouchers unavailable")
        val cur = currency.trim().uppercase()
        val price = VoucherMoney.parse(priceText, cur) ?: return@act ActionOutcome.Failed("enter the price, e.g. 4.50")
        val count = countText.trim().toIntOrNull() ?: return@act ActionOutcome.Failed("enter how many uses (1-255)")
        v.issue(t.tag, VoucherForm(code, name, price, cur, count), ::phase)
    }
    fun resumeVoucher(c: TapContext?) = act(c) { _, t ->
        val v = (t.tap as? TapClass.VoucherResume)?.voucher ?: return@act ActionOutcome.Failed("no voucher"); vsvc!!.resume(t.tag, v, ::phase) }
    fun wipeVoucher(c: TapContext?) = act(c) { _, t ->
        val v = (t.tap as? TapClass.Voucher)?.voucher ?: return@act ActionOutcome.Failed("no voucher"); vsvc!!.wipe(t.tag, v) }
    /** [c] captured at click time, before the biometric prompt. */
    fun voidVoucher(v: VoucherEntity, c: TapContext? = ctx) = viewModelScope.launch {
        vsvc?.let { s -> run("void voucher") { s.void(v, c?.tag?.takeIf { it.uidHex.equals(v.uid, true) }, ::phase) } } }
    fun vouchersFlow(): Flow<List<VoucherEntity>> = gateway.voucherStore().vouchersFlow()
    suspend fun voucher(id: String) = gateway.voucherStore().voucher(id)
    fun tappedVoucher(): VoucherEntity? = when (val t = ui.value.tap) { is TapClass.Voucher -> t.voucher; is TapClass.VoucherDamaged -> t.voucher; else -> null }
```

Extend `onTag`'s pending-resume shortcut so it also covers `TapClass.VoucherResume`, mirroring the card case: when `_ui.value.tap is TapClass.VoucherResume` for this UID and the outcome is `AwaitingTag`, run `vsvc.resume`.

- [ ] **Step 5: Tap sheet.** In `PrepaidTapSheet`:
- Add saveable fields `var vCode by rememberSaveable { mutableStateOf("") }`, plus `vName`, `vPrice`, `vCurrency` (`"AUD"`), `vCount` (`"1"`), and `var issueKind by remember { mutableStateOf("card") }`.
- `LaunchedEffect(ui.scannedAddress) { if (issueKind == "voucher" && ui.scannedAddress != null) { vCode = ui.scannedAddress; viewModel.clearScannedAddress() } }`. The QR scanner returns any text; the code field takes it.
- When `TapAction.ISSUE_VOUCHER in actions`, show two `FilterChip`s, "Card" and "Voucher", setting `issueKind`. When `issueKind == "voucher"`, show:
  - `OutlinedTextField`s for Product code (with a trailing `TextButton("Scan") { onScanAddress() }`), Product name, Price, Currency and Uses
  - and hide the card amount and label fields
- Action buttons:
  - `TapAction.ISSUE` only when `issueKind == "card"`
  - `TapAction.ISSUE_VOUCHER -> if (issueKind == "voucher") Button(onClick = { val c = viewModel.currentContext(); val f = listOf(vCode, vName, vPrice, vCurrency, vCount); gated("Issue voucher") { viewModel.issueVoucher(c, f[0], f[1], f[2], f[3], f[4]) } }) { Text("Issue voucher") }`
  - `TapAction.RESUME_VOUCHER -> Button(onClick = { viewModel.resumeVoucher(viewModel.currentContext()) }) { Text("Finish pending action") }`
  - `TapAction.VOID_VOUCHER -> OutlinedButton(colors = …ErrorRed, onClick = { val v = viewModel.tappedVoucher(); val c = viewModel.currentContext(); if (v != null) gated("Void voucher") { viewModel.voidVoucher(v, c) } }) { Text("Void") }`
  - `TapAction.WIPE_VOUCHER -> OutlinedButton(onClick = { val c = viewModel.currentContext(); gated("Wipe voucher") { viewModel.wipeVoucher(c) } }) { Text("Wipe") }`
- `headline`: `is TapClass.Voucher -> "Voucher: ${t.voucher.productName}"`; `is TapClass.VoucherResume -> "Pending voucher"`; `is TapClass.VoucherDamaged -> "Damaged voucher"`.
- `detail`:
  - `is TapClass.Voucher -> "${t.record.count} of ${t.voucher.countIssued} × ${t.voucher.productCode} · issued at ${VoucherMoney.format(t.voucher.issuePriceMinor, t.voucher.currency)} · index ${t.voucher.countLeft} left · ${t.voucher.state}"`
  - `is TapClass.VoucherResume -> if (t.voucher.pendingCommitted) "Committed on chain, not yet on the tag. Tap Finish." else "Waiting for the chain. Tap Finish to check."`
  - `is TapClass.VoucherDamaged -> "${t.reason}. Index: ${t.voucher.countLeft} of ${t.voucher.countIssued} uses left."`

- [ ] **Step 6: Voucher list, detail, and staff keys screens.**

In `PrepaidScreen`:
- Add `onVoucher: (String) -> Unit` and `onStaff: () -> Unit` parameters.
- Add a top-bar action `IconButton(onClick = onStaff) { Icon(Icons.Default.Lock, "Staff keys") }`.
- Below the card list, add a "Vouchers" section: `val vouchers by viewModel.vouchersFlow().collectAsState(initial = emptyList())`, one `ListItem` per voucher: headline `productName`, supporting `"${countLeft}/${countIssued} × ${productCode} · ${VoucherMoney.format(issuePriceMinor, currency)}"`, trailing state chip, `clickable { onVoucher(voucherId) }`.

`VoucherDetailScreen.kt`:

```kotlin
package com.wyltek.wallet.prepaid.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wyltek.wallet.prepaid.VoucherMoney
import com.wyltek.wallet.prepaid.db.*
import com.wyltek.wallet.ui.security.BiometricResult
import com.wyltek.wallet.ui.security.rememberBiometricAuth
import com.wyltek.wallet.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoucherDetailScreen(voucherId: String, onBack: () -> Unit, viewModel: PrepaidViewModel = viewModel()) {
    val biometric = rememberBiometricAuth()
    val ui by viewModel.ui.collectAsState()
    var v by remember { mutableStateOf<VoucherEntity?>(null) }
    var events by remember { mutableStateOf<List<PrepaidEventEntity>>(emptyList()) }
    LaunchedEffect(voucherId, ui.outcome) { v = viewModel.voucher(voucherId); events = viewModel.eventsFor(voucherId) }
    Scaffold(topBar = { TopAppBar(title = { Text(v?.productName ?: "Voucher") },
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } }) }) { pad ->
        val x = v ?: return@Scaffold
        Column(Modifier.padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${x.countLeft} of ${x.countIssued} × ${x.productCode}", color = TextPrimary)
            Text("Issued at ${VoucherMoney.format(x.issuePriceMinor, x.currency)} · ${x.state}", color = TextSecondary)
            Text("Cell ${x.outpoint.take(14)}…", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
            if (x.state == VOUCHER_ACTIVE && x.pendingTxHash == null) OutlinedButton(colors = ButtonDefaults.outlinedButtonColors(contentColor = ErrorRed), enabled = !ui.busy,
                onClick = { biometric.authenticate(title = "Void voucher", subtitle = "Spends the voucher cell back to you") { r ->
                    if (r is BiometricResult.Success || r is BiometricResult.Unavailable) viewModel.voidVoucher(x, null) } }) { Text("Void") }
            (ui.outcome as? com.wyltek.wallet.prepaid.ActionOutcome.Failed)?.let { Text(it.reason, color = ErrorRed) }
            LazyColumn { items(events) { e -> Text("${e.kind} ${e.phase} ${e.detail}", color = TextSecondary, style = MaterialTheme.typography.bodySmall) } }
        }
    }
}
```

`StaffKeysScreen.kt` (own small view model; enrol arms the next tap):

```kotlin
package com.wyltek.wallet.prepaid.ui

import android.app.Application
import android.nfc.Tag
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wyltek.wallet.WyltekWalletApp
import com.wyltek.wallet.prepaid.ActionOutcome
import com.wyltek.wallet.prepaid.db.StaffKeyEntity
import com.wyltek.wallet.prepaid.nfc.MifareTagPort
import com.wyltek.wallet.prepaid.nfc.NfcReaderMode
import com.wyltek.wallet.ui.security.BiometricResult
import com.wyltek.wallet.ui.security.rememberBiometricAuth
import com.wyltek.wallet.ui.theme.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class StaffUi(val armedLabel: String? = null, val busy: Boolean = false, val outcome: ActionOutcome? = null)

class StaffKeysViewModel(app: Application) : AndroidViewModel(app) {
    private val gateway = (app as WyltekWalletApp).agentGateway
    private val svc = gateway.staffKeys
    val keys: StateFlow<List<StaffKeyEntity>> = gateway.voucherStore().staffKeysFlow().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private val _ui = MutableStateFlow(StaffUi()); val ui: StateFlow<StaffUi> = _ui.asStateFlow()
    val available = svc != null
    fun arm(label: String) { _ui.update { it.copy(armedLabel = label, outcome = null) } }
    fun disarm() { _ui.update { it.copy(armedLabel = null) } }
    fun onTag(raw: Tag) {
        val label = _ui.value.armedLabel ?: return
        val s = svc ?: return
        if (_ui.value.busy) return
        if (!MifareTagPort.accepts(raw)) { _ui.update { it.copy(outcome = ActionOutcome.Failed("not an NTAG21x tag")) }; return }
        _ui.update { it.copy(busy = true) }
        viewModelScope.launch {
            val out = try { s.enrol(MifareTagPort(raw), label) } catch (e: Exception) { ActionOutcome.Failed("enrol failed: ${e.message}") }
            _ui.update { it.copy(busy = false, outcome = out, armedLabel = if (out is ActionOutcome.NeedsTag) label else null) }
        }
    }
    fun revoke(k: StaffKeyEntity) = viewModelScope.launch { svc?.let { s -> val out = s.revoke(k.keyId); _ui.update { it.copy(outcome = out) } } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StaffKeysScreen(onBack: () -> Unit, vm: StaffKeysViewModel = viewModel()) {
    val keys by vm.keys.collectAsState(); val ui by vm.ui.collectAsState()
    val biometric = rememberBiometricAuth()
    var label by remember { mutableStateOf("") }
    NfcReaderMode(enabled = vm.available, onTag = vm::onTag)
    Scaffold(topBar = { TopAppBar(title = { Row { Text("Staff keys"); Spacer(Modifier.width(8.dp)); DemoBadge() } },
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } }) }) { pad ->
        Column(Modifier.padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("A staff key approves voucher redemptions and price overrides at the POS. Any genuine NXP NTAG21x tag works; nothing is written to it.", color = TextSecondary)
            if (ui.armedLabel == null) {
                OutlinedTextField(value = label, onValueChange = { label = it }, label = { Text("Label, e.g. Phill's ring") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Button(enabled = label.isNotBlank() && !ui.busy, onClick = { val l = label; biometric.authenticate(title = "Enrol staff key", subtitle = "Lets this tag approve overrides") { r ->
                    if (r is BiometricResult.Success || r is BiometricResult.Unavailable) vm.arm(l) } }) { Text("Enrol") }
            } else {
                Text("Hold the staff tag to the phone…", color = NeonCyan)
                OutlinedButton(onClick = vm::disarm) { Text("Cancel") }
            }
            when (val o = ui.outcome) {
                ActionOutcome.Done -> Text("Done", color = SuccessGreen)
                is ActionOutcome.Failed -> Text(o.reason, color = ErrorRed)
                is ActionOutcome.NeedsTag -> Text("Could not read the tag. Hold it still.", color = WarningOrange)
                else -> {}
            }
            if (ui.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(keys, key = { it.keyId }) { k ->
                    ListItem(headlineContent = { Text(k.label) },
                        supportingContent = { Text("${k.uid} · key ${k.keyId}" + (k.revokedAt?.let { " · revoked" } ?: ""), color = TextSecondary) },
                        trailingContent = { if (k.revokedAt == null) TextButton(onClick = { biometric.authenticate(title = "Revoke ${k.label}", subtitle = "Terminals drop it at their next sync") { r ->
                            if (r is BiometricResult.Success || r is BiometricResult.Unavailable) vm.revoke(k) } }) { Text("Revoke", color = ErrorRed) } },
                        colors = ListItemDefaults.colors(containerColor = DarkSurfaceVariant))
                }
            }
        }
    }
}
```

`AppNavigation.kt`:
- Add `Screen.PrepaidStaff("prepaid-staff", …)` and `Screen.PrepaidVoucher("prepaid-voucher/{voucherId}", …) { fun route(id: String) = "prepaid-voucher/$id" }`, following the `PrepaidCard` pattern.
- Add their `composable(...)` blocks: `StaffKeysScreen(onBack = { navController.popBackStack() })` and `VoucherDetailScreen(voucherId = …, onBack = …)`.
- Pass `onVoucher = { navController.navigate(Screen.PrepaidVoucher.route(it)) }` and `onStaff = { navController.navigate(Screen.PrepaidStaff.route) }` to `PrepaidScreen`.

- [ ] **Step 7: Build and test**

Run: `cd ~/wyltek-wallet && ./gradlew :app:testDebugUnitTest :app:assembleDebug -q 2>&1 | tail -5`, then the failure-count script from Task 5.
Expected: BUILD SUCCESSFUL, failures 0.

- [ ] **Step 8: Commit** `feat(prepaid): voucher issue/void/wipe in the tap sheet, voucher list and detail, staff keys screen` (with the attribution line).

---

### Task 16: Review, security review, and the device checkpoint

**Files:**
- Create: `docs/superpowers/PREPAID-VOUCHERS-V1-CHECKPOINT.md`
- Modify: `app/src/test/java/com/wyltek/wallet/prepaid/Secp128r1Test.kt` (real capture)

- [ ] **Step 1: Whole-branch review.** Dispatch a `code-reviewer` over `git diff main...feat/prepaid-vouchers-v1` with the spec. Dispatch a `security-reviewer` over the same diff, focused on these:
  - **Paying and settling:** the void/close spend paths and settlement apply
  - **Signatures:** `Secp128r1`, and `StaffKeyService`'s trust decision
  - **Gateway:** the `/staff` route's auth

  Fix CRITICAL and HIGH findings, each with a test. Re-run the whole suite: failures 0.

- [ ] **Step 2: Build and install, preserving data**

```bash
cd ~/wyltek-wallet && ./gradlew :app:assembleDebug -q
adb -s R5CTC07MPYD install -r app/build/outputs/apk/debug/app-debug.apk
adb -s R5CTC07MPYD logcat -c
```

- [ ] **Step 3: Migration on the real v5 DB** (Phill opens Prepaid Cards). Expected:
  - Both live cards are still listed (`7adcde50…` 1502.000 and `ca09acda…` 1000.000).
  - `adb -s R5CTC07MPYD logcat -d | grep -E "FATAL|Room|IllegalState"` is empty.

- [ ] **Step 4: Issue 1 × coffee.**
  - Phill taps a blank NTAG and picks **Voucher**: code `COF01`, name `Flat white`, price `4.50`, currency `AUD`, uses `1`. Then biometric.
  - Check the tx with `get_transaction`: one output of `0x19b45a500` shannons (69 CKB) under the funding lock, data `0x4242563100000000`.
  - Re-tap the tag → "Voucher: Flat white".

- [ ] **Step 5: Enrol the ring and capture its real signature.**
  - Staff keys → label `Phill's ring` → Enrol → tap the ring's working NTAG chip.
  - Then re-provision a token, or reuse the kept `prepaid_settle` token: Phill shows its QR, decode it with `adb exec-out screencap -p | zbarimg -q --raw -`, and keep the token in a scratch file only.
  - `curl -sk -H "x-device-token: $TOKEN" https://192.168.68.113:8443/v1/prepaid/staff` → one entry.
  - Add to `Secp128r1Test` (with the real `uid`/`sig` from that response) and run it:

```kotlin
    @Test fun real_ring_signature_verifies_against_nxp_key() {
        assertTrue(Secp128r1.verifyNtagOriginality(hexBytes("<uid from /staff>"), hexBytes("<sig from /staff>")))
    }
```

  If this fails while the python vectors pass, the message convention differs: NXP may hash or pad the UID. Record that and stop. Do not weaken the check.

- [ ] **Step 6: Settle a redeem without a POS.**
  - `k_debit` and `merchant_id` come from the same QR.
  - Take `voucher_id` and `product_hash` from the voucher detail screen or the audit CSV, and `key_id` from `/staff`. Then:

```bash
cd ~/wyltek-wallet/rust-core
E=$(cargo run -q --example prepaid_journal_entry -- $K_DEBIT redeem $VOUCHER_ID $PRODUCT_HASH 1 0 7 $KEY_ID)
curl -sk -H "x-device-token: $TOKEN" -H 'content-type: application/json' -d "{\"entries_hex\":\"$E\"}" https://192.168.68.113:8443/v1/prepaid/journal   # accepted
curl -sk -H "x-device-token: $TOKEN" -H 'content-type: application/json' -d "{\"entries_hex\":\"$E\"}" https://192.168.68.113:8443/v1/prepaid/journal   # duplicate
E2=$(cargo run -q --example prepaid_journal_entry -- $K_DEBIT redeem $VOUCHER_ID $PRODUCT_HASH 1 0 9 $KEY_ID)
curl -sk -H "x-device-token: $TOKEN" -H 'content-type: application/json' -d "{\"entries_hex\":\"$E2\"}" https://192.168.68.113:8443/v1/prepaid/journal  # double_redeem
O=$(cargo run -q --example prepaid_journal_entry -- $K_DEBIT override $PRODUCT_HASH 500 0 AUD 7 $KEY_ID)
curl -sk -H "x-device-token: $TOKEN" -H 'content-type: application/json' -d "{\"entries_hex\":\"$O\"}" https://192.168.68.113:8443/v1/prepaid/journal   # accepted, then duplicate on resend
```

  The redeem entry needs the voucher tag's real UID only if the verifier checked it; it doesn't (the MAC covers what the POS wrote). The `uid` field is informational.

  Expected:
  - The verdicts as commented.
  - The voucher shows 0 of 1 left and is `closed`.
  - A `voucher_close` audit row with a tx hash whose input is the 69 CKB voucher cell.
  - A `price_override` audit row.

- [ ] **Step 7: Void and wipe** a second voucher (issue another, then Void with the tag present → tag Blank). **Revoke** the ring → `/staff` is empty.

- [ ] **Step 8: Write `docs/superpowers/PREPAID-VOUCHERS-V1-CHECKPOINT.md`.** Include:
  - date, phone, build
  - every tx hash with explorer links (`https://pudge.explorer.nervos.org/transaction/<hash>`)
  - verdicts seen
  - the real-signature result
  - migration result
  - deviations and follow-ups

  Commit it with the Secp128r1 test addition. **Do not push**; Phill decides on merge and push.
