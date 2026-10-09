//! The emitter must reproduce the committed fixture byte for byte, and the fixture's SHA-256 is
//! pinned here AND in blackbox-pos test/test_prepaid_core.cpp. If you change the protocol, regenerate,
//! update both constants, and copy the file to the POS repo.
use sha2::{Digest, Sha256};
use wyltekwalletcore::prepaid::vectors;

const FIXTURE: &str = include_str!("fixtures/prepaid_demo_vectors.txt");
const PINNED_SHA256: &str = "90937c92e909acf82a1b06c3f79e18840fd6e6d3c6d7bb6b9f27704fa98e60d8";

#[test]
fn emitter_reproduces_committed_fixture() {
    assert_eq!(vectors::emit(), FIXTURE);
}

#[test]
fn fixture_sha256_is_pinned() {
    let h = hex::encode(Sha256::digest(FIXTURE.as_bytes()));
    assert_eq!(h, PINNED_SHA256, "fixture changed: update this constant and the POS copy");
}
