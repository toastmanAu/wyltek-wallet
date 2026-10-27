//! The staff PIN fixture is the V2 POS's contract. Its SHA-256 is pinned here and (in V2) in blackbox-pos.
use sha2::{Digest, Sha256};
use wyltekwalletcore::prepaid::{cmac::aes_cmac, journal, staff, vectors};

const FIXTURE: &str = include_str!("fixtures/prepaid_staff_pin_vectors.txt");
const PINNED_SHA256: &str = "4714b5a267f27c9441610f491a3aab0234ea855a1a606a53a03805cd3cc97acb";

fn get(k: &str) -> &'static str {
    FIXTURE.lines().find_map(|l| l.strip_prefix(&format!("{k}=")[..])).unwrap_or_else(|| panic!("missing {k}"))
}
fn hx<const N: usize>(k: &str) -> [u8; N] { hex::decode(get(k)).unwrap().try_into().unwrap() }

#[test]
fn emitter_reproduces_committed_staff_fixture() { assert_eq!(vectors::emit_staff(), FIXTURE); }

#[test]
fn staff_fixture_sha256_is_pinned() {
    assert_eq!(hex::encode(Sha256::digest(FIXTURE.as_bytes())), PINNED_SHA256, "fixture changed: update this constant (and the POS copy in V2)");
}

#[test]
fn fixture_values_recompute_from_the_derivation() {
    let k_debit: [u8; 16] = hx("k_debit");
    let ks = staff::k_staff(&k_debit);
    assert_eq!(ks, hx::<16>("k_staff"));
    let key_id: [u8; 8] = hx("staff.key_id");
    for n in 1..=3 {
        let ver: u16 = get(&format!("pin{n}.pin_ver")).parse().unwrap();
        let pin = get(&format!("pin{n}.pin")).as_bytes();
        let want: [u8; 16] = hx(&format!("pin{n}.pin_check"));
        assert_eq!(staff::pin_check(&ks, &key_id, ver, pin), Some(want), "pin{n}");
    }
    assert_ne!(get("pin1.pin_check"), get("pin2.pin_check"), "pin_ver must change the check");
    assert_ne!(get("pin1.pin_check"), get("pin3.pin_check"));
    let raw: [u8; 96] = hx("entry_lockout.raw");
    assert!(journal::verify(&k_debit, &raw));
    let e = journal::decode(&raw).expect("decodes");
    assert_eq!(e.kind, 5);
    assert_eq!(e.kind, get("entry_lockout.kind").parse::<u8>().unwrap());
    assert_eq!(&e.pad[..8], &key_id);
    assert_eq!(u16::from_le_bytes([e.pad[8], e.pad[9]]), 1);
    assert_eq!(e.amount_units, 3);
}

#[test]
fn k_staff_derived_from_a_constant_key_fails_the_vector() {
    // spec §9 mutation probe: a K_staff derived from an all-zero key must NOT reproduce the vector.
    let bad = aes_cmac(&[0u8; 16], &staff::K_STAFF_MSG);
    assert_ne!(bad, hx::<16>("k_staff"));
    let key_id: [u8; 8] = hx("staff.key_id");
    let got = staff::pin_check(&bad, &key_id, 1, get("pin1.pin").as_bytes()).unwrap();
    assert_ne!(got, hx::<16>("pin1.pin_check"));
}
