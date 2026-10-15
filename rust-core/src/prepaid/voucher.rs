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
