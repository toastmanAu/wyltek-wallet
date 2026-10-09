//! DemoCardRecord — the 64-byte terminal-attested record stored twice (slots A/B) on an NTAG21x.
//! Layout and rules: demo-tier spec §4.1–4.2. Byte oracle for blackbox-pos prepaid_core.
use super::cmac::cmac8;

pub const RECORD_SIZE: usize = 64;
pub const MAGIC: [u8; 4] = *b"BBD1";
pub const VERSION_DEMO: u8 = 0x02;
pub const SCHEME_CKB_MILLI: u8 = 0x01;
const MAC_OFF: usize = 56;

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct DemoCardRecord {
    pub version: u8, pub scheme: u8, pub writer_id: u16, pub reserve_id: [u8; 32],
    pub balance_units: i32, pub counter: u32, pub merchant_id: [u8; 8], pub mac: [u8; 8],
}

pub fn encode(r: &DemoCardRecord) -> [u8; RECORD_SIZE] {
    let mut raw = [0u8; RECORD_SIZE];
    raw[0..4].copy_from_slice(&MAGIC);
    raw[4] = r.version; raw[5] = r.scheme;
    raw[6..8].copy_from_slice(&r.writer_id.to_le_bytes());
    raw[8..40].copy_from_slice(&r.reserve_id);
    raw[40..44].copy_from_slice(&r.balance_units.to_le_bytes());
    raw[44..48].copy_from_slice(&r.counter.to_le_bytes());
    raw[48..56].copy_from_slice(&r.merchant_id);
    raw[56..64].copy_from_slice(&r.mac);
    raw
}

/// Structural decode only (magic, version, scheme). Does NOT check the MAC; use `verify`.
pub fn decode(raw: &[u8; RECORD_SIZE]) -> Option<DemoCardRecord> {
    if raw[0..4] != MAGIC || raw[4] != VERSION_DEMO || raw[5] != SCHEME_CKB_MILLI { return None; }
    Some(DemoCardRecord {
        version: raw[4], scheme: raw[5],
        writer_id: u16::from_le_bytes([raw[6], raw[7]]),
        reserve_id: raw[8..40].try_into().unwrap(),
        balance_units: i32::from_le_bytes(raw[40..44].try_into().unwrap()),
        counter: u32::from_le_bytes(raw[44..48].try_into().unwrap()),
        merchant_id: raw[48..56].try_into().unwrap(),
        mac: raw[56..64].try_into().unwrap(),
    })
}

/// MAC input = UID(7) || raw[0..56]. Binds the record to its tag.
pub fn compute_mac(key: &[u8; 16], uid: &[u8; 7], raw: &[u8; RECORD_SIZE]) -> [u8; 8] {
    let mut msg = [0u8; 7 + MAC_OFF];
    msg[..7].copy_from_slice(uid);
    msg[7..].copy_from_slice(&raw[..MAC_OFF]);
    cmac8(key, &msg)
}

pub fn verify(key: &[u8; 16], uid: &[u8; 7], raw: &[u8; RECORD_SIZE]) -> bool {
    use subtle_ct::ct_eq8;
    ct_eq8(&compute_mac(key, uid, raw), raw[MAC_OFF..].try_into().unwrap())
}

pub(crate) mod subtle_ct {
    /// Constant-time 8-byte compare (no early exit).
    pub fn ct_eq8(a: &[u8; 8], b: &[u8; 8]) -> bool {
        let mut d = 0u8;
        for i in 0..8 { d |= a[i] ^ b[i]; }
        d == 0
    }
}

pub fn seal(key: &[u8; 16], uid: &[u8; 7], r: &mut DemoCardRecord) -> [u8; RECORD_SIZE] {
    r.mac = [0; 8];
    let raw = encode(r);
    r.mac = compute_mac(key, uid, &raw);
    encode(r)
}

pub fn is_formatted(r: &DemoCardRecord) -> bool {
    r.reserve_id.iter().any(|&b| b != 0) && r.merchant_id.iter().any(|&b| b != 0)
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum SlotPick { None, A, B, Ambiguous }

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum SlotNoneReason { NoValidSlot, BadVersion, MacFail }

/// Diagnostic for a `SlotPick::None` result. `merchant_hint` is the clear-text merchant_id of a slot
/// that decoded structurally but failed the MAC. It is UNAUTHENTICATED: use it only to choose a
/// refusal reason, never to accept a card.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct SlotDiag { pub reason: SlotNoneReason, pub merchant_hint: Option<[u8; 8]> }

/// Spec §4.2: valid = decodes AND MAC verifies; higher counter wins; equal counters with identical
/// bytes -> A; equal counters with different bytes -> Ambiguous (refuse); none valid -> None.
pub fn select_slot(key: &[u8; 16], uid: &[u8; 7], a: &[u8; RECORD_SIZE], b: &[u8; RECORD_SIZE]) -> (SlotPick, Option<DemoCardRecord>) {
    let (p, r, _) = select_slot_diag(key, uid, a, b);
    (p, r)
}

/// As `select_slot`, plus a diagnostic that is only meaningful when the pick is `None`.
/// BadVersion if either slot has magic "BBD1" but a wrong version/scheme; else MacFail (with the
/// slot's merchant_id as an unauthenticated hint) if either slot decodes but fails the MAC; else NoValidSlot.
pub fn select_slot_diag(key: &[u8; 16], uid: &[u8; 7], a: &[u8; RECORD_SIZE], b: &[u8; RECORD_SIZE]) -> (SlotPick, Option<DemoCardRecord>, SlotDiag) {
    let va = decode(a).filter(|_| verify(key, uid, a));
    let vb = decode(b).filter(|_| verify(key, uid, b));
    let none_diag = SlotDiag { reason: SlotNoneReason::NoValidSlot, merchant_hint: None };
    match (va, vb) {
        (None, None) => {
            let bad_ver = |x: &[u8; RECORD_SIZE]| x[0..4] == MAGIC && decode(x).is_none();
            if bad_ver(a) || bad_ver(b) {
                return (SlotPick::None, None, SlotDiag { reason: SlotNoneReason::BadVersion, merchant_hint: None });
            }
            let mac_fail = [a, b].into_iter().find_map(|x| decode(x).map(|r| r.merchant_id));
            match mac_fail {
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

#[cfg(test)]
mod tests {
    use super::*;
    fn key() -> [u8; 16] { core::array::from_fn(|i| i as u8) }           // 000102..0f
    fn uid() -> [u8; 7] { [0x04, 0x95, 0x8C, 0xAA, 0x5C, 0x5E, 0x80] }
    fn sample() -> DemoCardRecord {
        DemoCardRecord { version: VERSION_DEMO, scheme: SCHEME_CKB_MILLI, writer_id: 3,
            reserve_id: core::array::from_fn(|i| 0xA0 + i as u8), balance_units: 1_000_000, counter: 5,
            merchant_id: [0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88], mac: [0; 8] }
    }

    #[test]
    fn layout_is_64_bytes_little_endian() {
        let raw = encode(&sample());
        assert_eq!(&raw[0..4], b"BBD1");
        assert_eq!(raw[4], 0x02); assert_eq!(raw[5], 0x01);
        assert_eq!(&raw[6..8], &[0x03, 0x00]);                 // writer_id LE
        assert_eq!(raw[8], 0xA0); assert_eq!(raw[39], 0xBF);     // reserve_id
        assert_eq!(&raw[40..44], &[0x40, 0x42, 0x0F, 0x00]);     // 1_000_000 LE
        assert_eq!(&raw[44..48], &[5, 0, 0, 0]);
        assert_eq!(&raw[48..56], &[0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88]);
        assert_eq!(decode(&raw).unwrap(), sample());
    }

    #[test]
    fn decode_rejects_bad_magic_version_scheme() {
        let mut raw = encode(&sample());
        raw[0] = b'X'; assert!(decode(&raw).is_none());
        let mut raw = encode(&sample()); raw[4] = 0x01; assert!(decode(&raw).is_none());
        let mut raw = encode(&sample()); raw[5] = 0x02; assert!(decode(&raw).is_none());
    }

    #[test]
    fn seal_then_verify_and_mac_covers_uid() {
        let mut r = sample();
        let raw = seal(&key(), &uid(), &mut r);
        assert_eq!(&raw[56..64], &r.mac);
        assert!(verify(&key(), &uid(), &raw));
        let other_uid = [0x04, 0xEB, 0xBB, 0x02, 0xCD, 0x2A, 0x81];
        assert!(!verify(&key(), &other_uid, &raw), "record copied to another tag must fail");
        let mut tampered = raw; tampered[40] ^= 0x01;                   // balance bit
        assert!(!verify(&key(), &uid(), &tampered));
        let mut wrong_key = key(); wrong_key[0] ^= 1;
        assert!(!verify(&wrong_key, &uid(), &raw));
    }

    #[test]
    fn is_formatted_requires_nonzero_ids() {
        let mut r = sample(); assert!(is_formatted(&r));
        r.reserve_id = [0; 32]; assert!(!is_formatted(&r));
        let mut r = sample(); r.merchant_id = [0; 8]; assert!(!is_formatted(&r));
    }

    #[test]
    fn slot_selection_rules() {
        let (k, u) = (key(), uid());
        let mut r5 = sample(); let a5 = seal(&k, &u, &mut r5);
        let mut r6 = sample(); r6.counter = 6; r6.balance_units = 998_500; let b6 = seal(&k, &u, &mut r6);
        // higher counter wins regardless of slot
        assert_eq!(select_slot(&k, &u, &a5, &b6).0, SlotPick::B);
        assert_eq!(select_slot(&k, &u, &b6, &a5).0, SlotPick::A);
        assert_eq!(select_slot(&k, &u, &a5, &b6).1.unwrap().counter, 6);
        // one invalid slot: the valid one wins
        let mut bad = b6; bad[60] ^= 0xFF;
        assert_eq!(select_slot(&k, &u, &a5, &bad).0, SlotPick::A);
        // both invalid
        let zero = [0u8; 64];
        assert_eq!(select_slot(&k, &u, &zero, &bad), (SlotPick::None, None));
        // equal counters, identical bytes -> A
        assert_eq!(select_slot(&k, &u, &a5, &a5).0, SlotPick::A);
        // equal counters, different bytes -> Ambiguous, no record
        let mut r5b = sample(); r5b.balance_units = 1; let a5b = seal(&k, &u, &mut r5b);
        assert_eq!(select_slot(&k, &u, &a5, &a5b), (SlotPick::Ambiguous, None));
    }

    #[test]
    fn slot_selection_extra_orderings() {
        let (k, u) = (key(), uid());
        let uo = [0x04, 0xEB, 0xBB, 0x02, 0xCD, 0x2A, 0x81];
        let mut r1 = sample(); let rec1 = seal(&k, &u, &mut r1);
        let mut r2 = sample(); r2.counter = 6; r2.balance_units = 998_500; let rec2 = seal(&k, &u, &mut r2);
        let mut bad = rec2; bad[60] ^= 0xFF;
        assert_eq!(select_slot(&k, &u, &rec2, &rec1).0, SlotPick::A);
        assert_eq!(select_slot(&k, &u, &rec1, &bad).0, SlotPick::A);
        assert_eq!(select_slot(&k, &u, &bad, &rec1).0, SlotPick::B);
        let mut r2o = r2.clone(); let rec2o = seal(&k, &uo, &mut r2o);
        // higher-counter slot invalid for this tag must lose
        assert_eq!(select_slot(&k, &u, &rec2o, &rec1).0, SlotPick::B);
    }

    #[test]
    fn slot_diagnostics() {
        let (k, u) = (key(), uid());
        let uo = [0x04, 0xEB, 0xBB, 0x02, 0xCD, 0x2A, 0x81];
        let zero = [0u8; 64];
        let mut r = sample(); let good = seal(&k, &u, &mut r);
        let mut badver = good; badver[4] = 0x01;
        let d = select_slot_diag(&k, &u, &badver, &zero);
        assert_eq!((d.0, d.2.reason), (SlotPick::None, SlotNoneReason::BadVersion));
        let mut ro = sample(); let other = seal(&k, &uo, &mut ro);
        let d = select_slot_diag(&k, &u, &other, &zero);
        assert_eq!(d.0, SlotPick::None);
        assert_eq!(d.2, SlotDiag { reason: SlotNoneReason::MacFail, merchant_hint: Some(sample().merchant_id) });
        let d = select_slot_diag(&k, &u, &zero, &zero);
        assert_eq!(d.2, SlotDiag { reason: SlotNoneReason::NoValidSlot, merchant_hint: None });
    }
}
