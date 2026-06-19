//! On-chain CEMP-PQ encrypted messaging: ML-KEM-768 key agreement + AES-256-GCM,
//! Profile / EncryptedMessage / MessagePointer molecule, and CKB Type ID. Pure
//! functions; tx assembly + signing reuse the existing v2 ML-DSA path in Kotlin.

pub mod keys;
pub mod crypto;
pub mod molecule;
pub mod typeid;

pub use keys::{cemp_mlkem_from_seed, KemKeyPair};
// Task 2: pub use crypto::{cemp_encrypt, cemp_decrypt};
// Task 3: pub use molecule::{serialize_profile, parse_profile_kem, parse_profile_dsa, serialize_message_pointer, parse_message_pointer};
// Task 4: pub use typeid::compute_type_id;

#[derive(Debug, thiserror::Error, uniffi::Error)]
#[uniffi(flat_error)]
pub enum CempError {
    #[error("CEMP crypto error: {0}")]
    Crypto(String),
    #[error("CEMP encoding error: {0}")]
    Encoding(String),
}
