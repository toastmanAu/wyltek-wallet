//! The voucher fixture is the V2 POS's contract. Its SHA-256 is pinned here and (in V2) in blackbox-pos.
use sha2::{Digest, Sha256};
use wyltekwalletcore::prepaid::vectors;

const FIXTURE: &str = include_str!("fixtures/prepaid_voucher_vectors.txt");
const PINNED_SHA256: &str = "a02cdea1df464e4459f48abd5aac5a37b6e448769991b9202fe73e6fe8209bbe";

#[test]
fn emitter_reproduces_committed_voucher_fixture() { assert_eq!(vectors::emit_voucher(), FIXTURE); }

#[test]
fn voucher_fixture_sha256_is_pinned() {
    assert_eq!(hex::encode(Sha256::digest(FIXTURE.as_bytes())), PINNED_SHA256, "fixture changed: update this constant (and the POS copy in V2)");
}
