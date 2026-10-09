//! JournalEntry — 96 bytes, parent spec §8.1 with pad = 12 (the spec's "pad 8" summed to 92 and
//! was corrected). Version 0x02 = terminal-attested: tmv = CMAC8(K_debit, raw with tmv zeroed).
use super::cmac::cmac8;
use super::record::subtle_ct::ct_eq8;

pub const ENTRY_SIZE: usize = 96;
pub const VERSION_DEMO: u8 = 0x02;
pub const KIND_DEBIT: u8 = 0x01;
pub const KIND_REFUND: u8 = 0x02;
const TMV_OFF: usize = 68;

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct JournalEntry {
    pub version: u8, pub kind: u8, pub terminal_id: u16, pub merchant_id: [u8; 8], pub uid: [u8; 7],
    pub reserve_id: [u8; 32], pub tmc: u32, pub amount_units: i32, pub balance_after: i32, pub ts: u32,
    pub tmv: [u8; 8], pub sale_ref: [u8; 8],
}

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
    r[76..84].copy_from_slice(&e.sale_ref);           // r[84..96] pad
    r
}

pub fn decode(r: &[u8; ENTRY_SIZE]) -> Option<JournalEntry> {
    if r[0] != VERSION_DEMO || !(r[1] == KIND_DEBIT || r[1] == KIND_REFUND) { return None; }
    if r[19] != 0 || r[84..96].iter().any(|&b| b != 0) { return None; }
    Some(JournalEntry {
        version: r[0], kind: r[1], terminal_id: u16::from_le_bytes([r[2], r[3]]),
        merchant_id: r[4..12].try_into().unwrap(), uid: r[12..19].try_into().unwrap(),
        reserve_id: r[20..52].try_into().unwrap(),
        tmc: u32::from_le_bytes(r[52..56].try_into().unwrap()),
        amount_units: i32::from_le_bytes(r[56..60].try_into().unwrap()),
        balance_after: i32::from_le_bytes(r[60..64].try_into().unwrap()),
        ts: u32::from_le_bytes(r[64..68].try_into().unwrap()),
        tmv: r[68..76].try_into().unwrap(), sale_ref: r[76..84].try_into().unwrap(),
    })
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

/// MAC must verify AND the amount sign must match the kind (debit > 0, refund < 0).
pub fn verify(key: &[u8; 16], raw: &[u8; ENTRY_SIZE]) -> bool {
    let Some(e) = decode(raw) else { return false; };
    let sign_ok = match e.kind { KIND_DEBIT => e.amount_units > 0, KIND_REFUND => e.amount_units < 0, _ => false };
    let mac_ok = ct_eq8(&mac_over(key, raw), &e.tmv);
    mac_ok & sign_ok
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
            sale_ref: [0xDE, 0xAD, 0xBE, 0xEF, 0x01, 0x02, 0x03, 0x04] }
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
        let mut raw = encode(&sample()); raw[90] = 1; assert!(decode(&raw).is_none());
        let mut raw = encode(&sample()); raw[19] = 1; assert!(decode(&raw).is_none());
        let mut raw = encode(&sample()); raw[84] = 1; assert!(decode(&raw).is_none());
        let mut raw = encode(&sample()); raw[95] = 1; assert!(decode(&raw).is_none());
        let mut raw = encode(&sample()); raw[83] ^= 1; assert!(decode(&raw).is_some(), "byte 83 is sale_ref, not pad");
    }
}
