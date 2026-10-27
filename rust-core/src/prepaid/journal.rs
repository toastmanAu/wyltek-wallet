//! JournalEntry — 96 bytes, parent spec §8.1 with pad = 12 (the spec's "pad 8" summed to 92 and
//! was corrected). Version 0x02 = terminal-attested: tmv = CMAC8(K_debit, raw with tmv zeroed).
use super::cmac::cmac8;
use super::record::subtle_ct::ct_eq8;

pub const ENTRY_SIZE: usize = 96;
pub const VERSION_DEMO: u8 = 0x02;
pub const KIND_DEBIT: u8 = 0x01;
pub const KIND_REFUND: u8 = 0x02;
pub const KIND_VOUCHER_REDEEM: u8 = 0x03;
pub const KIND_PRICE_OVERRIDE: u8 = 0x04;
pub const KIND_STAFF_LOCKOUT: u8 = 0x05;
const TMV_OFF: usize = 68;

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct JournalEntry {
    pub version: u8, pub kind: u8, pub terminal_id: u16, pub merchant_id: [u8; 8], pub uid: [u8; 7],
    pub reserve_id: [u8; 32], pub tmc: u32, pub amount_units: i32, pub balance_after: i32, pub ts: u32,
    pub tmv: [u8; 8], pub sale_ref: [u8; 8], pub pad: [u8; 12],
}

pub fn is_known_kind(kind: u8) -> bool { (KIND_DEBIT..=KIND_STAFF_LOCKOUT).contains(&kind) }

pub fn encode(e: &JournalEntry) -> [u8; ENTRY_SIZE] {
    let mut r = [0u8; ENTRY_SIZE];
    r[0] = e.version; r[1] = e.kind;
    r[2..4].copy_from_slice(&e.terminal_id.to_le_bytes());
    r[4..12].copy_from_slice(&e.merchant_id);
    r[12..19].copy_from_slice(&e.uid);                 // r[19] pad0
    r[20..52].copy_from_slice(&e.reserve_id);
    r[52..56].copy_from_slice(&e.tmc.to_le_bytes());
    r[56..60].copy_from_slice(&e.amount_units.to_le_bytes());
    r[60..64].copy_from_slice(&e.balance_after.to_le_bytes());
    r[64..68].copy_from_slice(&e.ts.to_le_bytes());
    r[68..76].copy_from_slice(&e.tmv);
    r[76..84].copy_from_slice(&e.sale_ref);
    r[84..96].copy_from_slice(&e.pad);
    r
}

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
        // pin_ver (pad 8..10) is unconstrained: the terminal reports what it holds. amount_units = failed attempts.
        KIND_STAFF_LOCKOUT => e.reserve_id == [0; 32] && e.tmc == 0 && e.balance_after == 0 && e.amount_units > 0
            && key_id_set && e.pad[10..12] == [0, 0],
        _ => false,
    }
}

fn mac_over(key: &[u8; 16], raw: &[u8; ENTRY_SIZE]) -> [u8; 8] {
    let mut m = *raw;
    m[TMV_OFF..TMV_OFF + 8].fill(0);
    cmac8(key, &m)
}

pub fn attest(key: &[u8; 16], e: &mut JournalEntry) -> [u8; ENTRY_SIZE] {
    e.tmv = [0; 8];
    let raw = encode(e);
    e.tmv = mac_over(key, &raw);
    encode(e)
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
        KIND_STAFF_LOCKOUT => e.amount_units > 0,
        _ => false,
    };
    mac_ok(key, raw) & amounts_ok
}

#[cfg(test)]
mod tests {
    use super::*;
    fn key() -> [u8; 16] { core::array::from_fn(|i| i as u8) }
    fn sample() -> JournalEntry {
        JournalEntry { version: 0x02, kind: KIND_DEBIT, terminal_id: 3,
            merchant_id: [0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88],
            uid: [0x04, 0x95, 0x8C, 0xAA, 0x5C, 0x5E, 0x80], reserve_id: core::array::from_fn(|i| 0xA0 + i as u8),
            tmc: 6, amount_units: 1500, balance_after: 998_500, ts: 1_791_370_000, tmv: [0; 8],
            sale_ref: [0xDE, 0xAD, 0xBE, 0xEF, 0x01, 0x02, 0x03, 0x04], pad: [0; 12] }
    }

    #[test]
    fn layout_is_96_bytes() {
        let raw = encode(&sample());
        assert_eq!(raw.len(), 96);
        assert_eq!(raw[0], 0x02); assert_eq!(raw[1], 0x01);
        assert_eq!(&raw[2..4], &[3, 0]);
        assert_eq!(&raw[4..12], &[0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88]);
        assert_eq!(&raw[12..19], &[0x04, 0x95, 0x8C, 0xAA, 0x5C, 0x5E, 0x80]); assert_eq!(raw[19], 0);
        assert_eq!(raw[20], 0xA0); assert_eq!(raw[51], 0xBF);
        assert_eq!(&raw[52..56], &[6, 0, 0, 0]);
        assert_eq!(&raw[56..60], &1500i32.to_le_bytes());
        assert_eq!(&raw[60..64], &998_500i32.to_le_bytes());
        assert_eq!(&raw[64..68], &1_791_370_000u32.to_le_bytes());
        assert_eq!(&raw[76..84], &[0xDE, 0xAD, 0xBE, 0xEF, 0x01, 0x02, 0x03, 0x04]);
        assert!(raw[84..96].iter().all(|&b| b == 0));
        assert_eq!(decode(&raw).unwrap(), sample());
    }

    #[test]
    fn attest_verify_and_tamper() {
        let mut e = sample();
        let raw = attest(&key(), &mut e);
        assert_eq!(&raw[68..76], &e.tmv);
        assert!(verify(&key(), &raw));
        let mut t = raw; t[56] ^= 1; assert!(!verify(&key(), &t));          // amount
        let mut t = raw; t[68] ^= 1; assert!(!verify(&key(), &t));          // tmv itself
        let mut k2 = key(); k2[15] ^= 1; assert!(!verify(&k2, &raw));
    }

    #[test]
    fn sign_must_match_kind() {
        let mut e = sample(); e.amount_units = -1500;                      // debit with negative amount
        let raw = attest(&key(), &mut e);
        assert!(!verify(&key(), &raw), "valid MAC but debit with amount <= 0 must fail");
        let mut e = sample(); e.kind = KIND_REFUND; e.amount_units = 300;   // refund must be negative
        let raw = attest(&key(), &mut e);
        assert!(!verify(&key(), &raw));
        let mut e = sample(); e.kind = KIND_REFUND; e.amount_units = -300;
        let raw = attest(&key(), &mut e);
        assert!(verify(&key(), &raw));
    }

    #[test]
    fn zero_amount_fails_for_both_kinds() {
        let mut e = sample(); e.amount_units = 0;
        let raw = attest(&key(), &mut e);
        assert!(!verify(&key(), &raw), "zero-amount debit must fail");
        let mut e = sample(); e.kind = KIND_REFUND; e.amount_units = 0;
        let raw = attest(&key(), &mut e);
        assert!(!verify(&key(), &raw), "zero-amount refund must fail");
    }

    #[test]
    fn mac_covers_every_byte_except_tmv() {
        let mut e = sample();
        let raw = attest(&key(), &mut e);
        assert!(verify(&key(), &raw));
        for i in (0..84).filter(|i| !(68..76).contains(i)) {
            let mut t = raw; t[i] ^= 1;
            assert!(!verify(&key(), &t), "flip of byte {i} must fail verify");
        }
        for i in [67usize, 76] {
            let mut t = raw; t[i] ^= 1;
            assert!(!verify(&key(), &t), "tmv neighbour byte {i} must fail verify");
        }
    }

    #[test]
    fn decode_rejects_bad_version_kind_pad() {
        let mut raw = encode(&sample()); raw[0] = 0x01; assert!(decode(&raw).is_none());
        let mut raw = encode(&sample()); raw[1] = 0x03; assert!(decode(&raw).is_none());
        let mut raw = encode(&sample()); raw[1] = 0x06; assert!(decode(&raw).is_none());
        let mut raw = encode(&sample()); raw[90] = 1; assert!(decode(&raw).is_none());
        let mut raw = encode(&sample()); raw[19] = 1; assert!(decode(&raw).is_none());
        let mut raw = encode(&sample()); raw[84] = 1; assert!(decode(&raw).is_none());
        let mut raw = encode(&sample()); raw[95] = 1; assert!(decode(&raw).is_none());
        let mut raw = encode(&sample()); raw[83] ^= 1; assert!(decode(&raw).is_some(), "byte 83 is sale_ref, not pad");
    }

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
        let mut e = sample(); e.kind = 0x06; let raw = attest(&key(), &mut e);
        assert!(!is_known_kind(raw[1]) && decode(&raw).is_none());
        assert!(mac_ok(&key(), &raw), "MAC is kind-independent");
        let mut k2 = key(); k2[0] ^= 1; assert!(!mac_ok(&k2, &raw));
    }

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
        fn ok(f: impl FnOnce(&mut JournalEntry)) -> bool { let mut e = lockout(); f(&mut e); decode(&encode(&e)).is_some() }
        assert!(ok(|_| {}));
        assert!(!ok(|e| e.reserve_id[0] = 1)); assert!(!ok(|e| e.tmc = 1)); assert!(!ok(|e| e.balance_after = 1));
        assert!(!ok(|e| e.amount_units = 0)); assert!(!ok(|e| e.pad[..8].fill(0))); assert!(!ok(|e| e.pad[10] = 1)); assert!(!ok(|e| e.pad[11] = 1));
        assert!(ok(|e| e.pad[8..10].copy_from_slice(&0u16.to_le_bytes())), "pin_ver is reported, not validated");
        assert!(ok(|e| e.sale_ref = [1; 8]), "a sale may have been open");
    }
}
