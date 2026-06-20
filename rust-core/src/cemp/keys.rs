use crate::cemp::CempError;
use fips203::ml_kem_768;
use fips203::traits::{KeyGen, SerDes};
use hkdf::Hkdf;
use sha2::Sha256;

/// ML-KEM-768 key pair: encapsulation key (public) + decapsulation key (private).
/// Both are returned as hex strings for UniFFI boundary crossing.
#[derive(Clone, uniffi::Record)]
pub struct KemKeyPair {
    pub public_key_hex: String,
    pub secret_key_hex: String,
}

/// Deterministically derive an ML-KEM-768 keypair from the wallet seed via
/// HKDF-SHA256 (so it restores from the mnemonic). Independent of the ML-DSA key.
///
/// The seed is the 64-byte BIP-39 seed exported from the mnemonic (passed as hex).
/// HKDF expands it into two 32-byte values d and z, which are the ML-KEM-768
/// internal seeds per FIPS 203 Algorithm 16 (ML-KEM.KeyGen_internal(d,z)).
#[uniffi::export]
pub fn cemp_mlkem_from_seed(seed_hex: String) -> Result<KemKeyPair, CempError> {
    let seed = hex::decode(seed_hex.trim_start_matches("0x"))
        .map_err(|e| CempError::Encoding(e.to_string()))?;
    let hk = Hkdf::<Sha256>::new(None, &seed);
    // ML-KEM-768 keygen_from_seed(d, z) takes two independent 32-byte arrays.
    let mut d = [0u8; 32];
    let mut z = [0u8; 32];
    hk.expand(b"CEMP-PQ-KEM-D", &mut d).map_err(|e| CempError::Crypto(e.to_string()))?;
    hk.expand(b"CEMP-PQ-KEM-Z", &mut z).map_err(|e| CempError::Crypto(e.to_string()))?;
    // fips203 0.4: keygen_from_seed takes owned [u8;32] values (not references).
    let (ek, dk) = ml_kem_768::KG::keygen_from_seed(d, z);
    Ok(KemKeyPair {
        public_key_hex: hex::encode(ek.into_bytes()),
        secret_key_hex: hex::encode(dk.into_bytes()),
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn deterministic_from_seed() {
        let s = "11".repeat(32);
        let a = cemp_mlkem_from_seed(s.clone()).unwrap();
        let b = cemp_mlkem_from_seed(s).unwrap();
        assert_eq!(a.public_key_hex, b.public_key_hex);
        // ML-KEM-768 encapsulation key is 1184 bytes -> 2368 hex chars.
        assert_eq!(a.public_key_hex.len(), 1184 * 2);
    }

    #[test]
    fn distinct_seeds_distinct_keys() {
        let a = cemp_mlkem_from_seed("11".repeat(32)).unwrap();
        let b = cemp_mlkem_from_seed("22".repeat(32)).unwrap();
        assert_ne!(a.public_key_hex, b.public_key_hex);
    }
}
