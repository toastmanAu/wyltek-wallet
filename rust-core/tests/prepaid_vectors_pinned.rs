//! The emitter must reproduce the committed fixture byte for byte, and the fixture's SHA-256 is
//! pinned here AND in blackbox-pos test/test_prepaid_core.cpp. If you change the protocol, regenerate,
//! update both constants, and copy the file to the POS repo.
use sha2::{Digest, Sha256};
use wyltekwalletcore::prepaid::vectors;

const FIXTURE: &str = include_str!("fixtures/prepaid_demo_vectors.txt");
const PINNED_SHA256: &str = "c1146fb9c8964df03fa7ba8ad76029541c6c494f9077d8f24e6dc979005487fb";   // filled in Step 4

#[test]
fn emitter_reproduces_committed_fixture() {
    assert_eq!(vectors::emit(), FIXTURE);
}

#[test]
fn fixture_sha256_is_pinned() {
    let h = hex::encode(Sha256::digest(FIXTURE.as_bytes()));
    assert_eq!(h, PINNED_SHA256, "fixture changed: update this constant and the POS copy");
}
