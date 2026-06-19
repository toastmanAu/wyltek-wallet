//! AES-256-GCM encryption / decryption for CEMP-PQ messages.
//!
//! Encryption flow:
//!   1. ML-KEM-768 encapsulate against recipient's encapsulation key
//!      → shared secret (32 bytes) + KEM ciphertext (1088 bytes)
//!   2. Derive AES-256 symmetric key: blake2b(shared_secret, 32, personal="CEMP-PQ-SYM-KEY_")
//!   3. AES-256-GCM encrypt with a fresh random 12-byte nonce
//!   4. Wrap (kem_ct, nonce, aes_ct) in a molecule EncryptedMessage table → hex

use crate::cemp::molecule::{parse_encrypted_message, serialize_encrypted_message};
use crate::cemp::CempError;
use aes_gcm::aead::rand_core::RngCore;
use aes_gcm::aead::{Aead, KeyInit, OsRng};
use aes_gcm::{Aes256Gcm, Nonce};
use blake2b_ref::Blake2bBuilder;
use fips203::ml_kem_768;
use fips203::traits::{Decaps, Encaps, SerDes};

/// Derive a 32-byte AES key from the ML-KEM shared secret via blake2b.
fn sym_key(shared: &[u8]) -> [u8; 32] {
    let mut h = Blake2bBuilder::new(32)
        .personal(b"CEMP-PQ-SYM-KEY_")
        .build();
    h.update(shared);
    let mut out = [0u8; 32];
    h.finalize(&mut out);
    out
}

fn enc<E: std::fmt::Display>(e: E) -> CempError {
    CempError::Encoding(e.to_string())
}

/// Encrypt `plaintext_hex` for `recipient_kem_pub_hex` (ML-KEM-768 encapsulation key).
///
/// Returns the molecule-encoded `EncryptedMessage` as a lowercase hex string.
#[uniffi::export]
pub fn cemp_encrypt(
    plaintext_hex: String,
    recipient_kem_pub_hex: String,
) -> Result<String, CempError> {
    let plaintext = hex::decode(plaintext_hex.trim_start_matches("0x")).map_err(enc)?;
    let pub_bytes = hex::decode(recipient_kem_pub_hex.trim_start_matches("0x")).map_err(enc)?;

    // ML-KEM-768 encapsulation key is 1184 bytes.
    let pub_arr: [u8; ml_kem_768::EK_LEN] = pub_bytes
        .try_into()
        .map_err(|_| CempError::Encoding("KEM pubkey must be 1184 bytes".into()))?;
    let ek = ml_kem_768::EncapsKey::try_from_bytes(pub_arr)
        .map_err(|e| CempError::Crypto(format!("EncapsKey parse: {e}")))?;

    // Encapsulate: produces (shared_secret, kem_ciphertext)
    let (shared, kem_ct) = ek
        .try_encaps()
        .map_err(|e| CempError::Crypto(format!("encaps: {e}")))?;

    let key = sym_key(&shared.into_bytes());
    let cipher =
        Aes256Gcm::new_from_slice(&key).map_err(|e| CempError::Crypto(e.to_string()))?;

    // Generate a fresh 12-byte nonce.
    let mut nonce_bytes = [0u8; 12];
    OsRng.fill_bytes(&mut nonce_bytes);
    let aes_ct = cipher
        .encrypt(Nonce::from_slice(&nonce_bytes), plaintext.as_ref())
        .map_err(|e| CempError::Crypto(format!("aes-gcm encrypt: {e}")))?;

    let molecule =
        serialize_encrypted_message(&kem_ct.into_bytes(), &nonce_bytes, &aes_ct);
    Ok(hex::encode(molecule))
}

/// Decrypt an `EncryptedMessage` hex blob using the recipient's ML-KEM-768 decapsulation key.
///
/// Returns the plaintext as a lowercase hex string.
#[uniffi::export]
pub fn cemp_decrypt(
    encrypted_msg_hex: String,
    recipient_kem_sec_hex: String,
) -> Result<String, CempError> {
    let blob = hex::decode(encrypted_msg_hex.trim_start_matches("0x")).map_err(enc)?;
    let (kem_ct_bytes, nonce_bytes, aes_ct) = parse_encrypted_message(&blob)?;

    let sec_bytes = hex::decode(recipient_kem_sec_hex.trim_start_matches("0x")).map_err(enc)?;

    // ML-KEM-768 decapsulation key is 2400 bytes.
    let sec_arr: [u8; ml_kem_768::DK_LEN] = sec_bytes
        .try_into()
        .map_err(|_| CempError::Encoding("KEM seckey must be 2400 bytes".into()))?;
    let dk = ml_kem_768::DecapsKey::try_from_bytes(sec_arr)
        .map_err(|e| CempError::Crypto(format!("DecapsKey parse: {e}")))?;

    // ML-KEM-768 ciphertext is 1088 bytes.
    let ct_arr: [u8; ml_kem_768::CT_LEN] = kem_ct_bytes
        .try_into()
        .map_err(|_| CempError::Encoding("KEM ciphertext must be 1088 bytes".into()))?;
    let kem_ct = ml_kem_768::CipherText::try_from_bytes(ct_arr)
        .map_err(|e| CempError::Crypto(format!("CipherText parse: {e}")))?;

    let shared = dk
        .try_decaps(&kem_ct)
        .map_err(|e| CempError::Crypto(format!("decaps: {e}")))?;

    let key = sym_key(&shared.into_bytes());
    let cipher =
        Aes256Gcm::new_from_slice(&key).map_err(|e| CempError::Crypto(e.to_string()))?;
    let pt = cipher
        .decrypt(Nonce::from_slice(&nonce_bytes), aes_ct.as_ref())
        .map_err(|e| CempError::Crypto(format!("aes-gcm decrypt: {e}")))?;

    Ok(hex::encode(pt))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::cemp::keys::cemp_mlkem_from_seed;

    #[test]
    fn encrypt_then_decrypt_roundtrip() {
        let kp = cemp_mlkem_from_seed("33".repeat(32)).unwrap();
        let msg_hex = hex::encode(b"hello agent");
        let enc_hex = cemp_encrypt(msg_hex.clone(), kp.public_key_hex.clone()).unwrap();
        let dec_hex = cemp_decrypt(enc_hex, kp.secret_key_hex).unwrap();
        assert_eq!(dec_hex, msg_hex);
        assert_eq!(
            String::from_utf8(hex::decode(&dec_hex).unwrap()).unwrap(),
            "hello agent"
        );
    }

    #[test]
    fn wrong_key_fails() {
        let a = cemp_mlkem_from_seed("33".repeat(32)).unwrap();
        let b = cemp_mlkem_from_seed("44".repeat(32)).unwrap();
        let enc_hex = cemp_encrypt(hex::encode(b"secret"), a.public_key_hex).unwrap();
        // Decrypting with a different key should fail (AES-GCM auth tag mismatch)
        assert!(cemp_decrypt(enc_hex, b.secret_key_hex).is_err());
    }

    #[test]
    fn different_encryptions_of_same_plaintext_differ() {
        // Two encryptions of the same plaintext should produce different ciphertexts (random nonce).
        let kp = cemp_mlkem_from_seed("55".repeat(32)).unwrap();
        let msg = hex::encode(b"determinism test");
        let e1 = cemp_encrypt(msg.clone(), kp.public_key_hex.clone()).unwrap();
        let e2 = cemp_encrypt(msg, kp.public_key_hex).unwrap();
        assert_ne!(e1, e2);
    }
}
