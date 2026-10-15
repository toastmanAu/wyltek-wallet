//! The voucher fixture is the V2 POS's contract. Its SHA-256 is pinned here and (in V2) in blackbox-pos.
use sha2::{Digest, Sha256};
use wyltekwalletcore::prepaid::vectors;

const FIXTURE: &str = include_str!("fixtures/prepaid_voucher_vectors.txt");
const PINNED_SHA256: &str = "2ec9f2d0d5495ee6abcbf335711a11199f87d0e216d29422cef377bd48d12a32";

#[test]
fn emitter_reproduces_committed_voucher_fixture() { assert_eq!(vectors::emit_voucher(), FIXTURE); }

#[test]
fn voucher_fixture_sha256_is_pinned() {
    assert_eq!(hex::encode(Sha256::digest(FIXTURE.as_bytes())), PINNED_SHA256, "fixture changed: update this constant (and the POS copy in V2)");
}
