use crate::WalletError;
use blake2b_ref::Blake2bBuilder;
use fips204::ml_dsa_65;
use fips204::traits::{KeyGen, SerDes, Signer, Verifier};
use hkdf::Hkdf;
use sha2::Sha256;

#[derive(uniffi::Record)]
pub struct PQKeyPair {
    pub public_key_hex: String,
    pub private_key_hex: String,
}

#[uniffi::export]
pub fn generate_mldsa65_keypair() -> Result<PQKeyPair, WalletError> {
    let (pk, sk) = ml_dsa_65::KG::try_keygen()
        .map_err(|e| WalletError::CryptoError(format!("ML-DSA-65 keygen failed: {}", e)))?;

    Ok(PQKeyPair {
        public_key_hex: hex::encode(pk.into_bytes()),
        private_key_hex: hex::encode(sk.into_bytes()),
    })
}

/// Derive an ML-DSA-65 keypair from a raw 32-byte FIPS 204 seed ξ (hex, no
/// `0x`). Used by the testnet harness and tests. App wallets derive from the
/// BIP39 mnemonic and must use [`mldsa65_from_bip39_seed`] instead.
#[uniffi::export]
pub fn mldsa65_from_seed(seed_hex: String) -> Result<PQKeyPair, WalletError> {
    let seed_bytes = hex::decode(&seed_hex)?;
    if seed_bytes.len() != 32 {
        return Err(WalletError::InvalidInput("Seed must be 32 bytes".into()));
    }

    let mut xi = [0u8; 32];
    xi.copy_from_slice(&seed_bytes);

    Ok(mldsa65_keypair_from_xi(&xi))
}

const MLDSA65_HKDF_SALT: &[u8] = b"wyltek-wallet/mldsa65";
const MLDSA65_HKDF_INFO: &[u8] = b"ckb/mldsa65/v1/0";

/// Derive an ML-DSA-65 keypair from the 64-byte BIP39 seed of the wallet
/// mnemonic (`mnemonic_to_seed`, hex, `0x` prefix tolerated).
///
/// This is the PERMANENT post-quantum derivation for app wallets; changing it
/// moves every app PQ address:
///
/// ```text
/// ξ  = HKDF-SHA256(salt = "wyltek-wallet/mldsa65",
///                  ikm  = seed64,
///                  info = "ckb/mldsa65/v1/0", L = 32)
/// (pk, sk) = ML-DSA-65.KeyGen_internal(ξ)      // FIPS 204 Algorithm 6
/// ```
///
/// The trailing `/0` in `info` is the account index, reserved for future
/// multi-account derivation. The input must be exactly 64 bytes; a raw 32-byte
/// ξ belongs in [`mldsa65_from_seed`].
#[uniffi::export]
pub fn mldsa65_from_bip39_seed(seed_hex: String) -> Result<PQKeyPair, WalletError> {
    let seed = hex::decode(seed_hex.trim().trim_start_matches("0x"))?;
    let xi = derive_mldsa65_xi(&seed)?;
    Ok(mldsa65_keypair_from_xi(&xi))
}

/// HKDF step of [`mldsa65_from_bip39_seed`]: 64-byte BIP39 seed → 32-byte ξ.
fn derive_mldsa65_xi(seed: &[u8]) -> Result<[u8; 32], WalletError> {
    if seed.len() != 64 {
        return Err(WalletError::InvalidInput("BIP39 seed must be 64 bytes".into()));
    }
    let hk = Hkdf::<Sha256>::new(Some(MLDSA65_HKDF_SALT), seed);
    let mut xi = [0u8; 32];
    hk.expand(MLDSA65_HKDF_INFO, &mut xi)
        .map_err(|e| WalletError::CryptoError(format!("HKDF expand failed: {}", e)))?;
    Ok(xi)
}

fn mldsa65_keypair_from_xi(xi: &[u8; 32]) -> PQKeyPair {
    let (pk, sk) = ml_dsa_65::KG::keygen_from_seed(xi);
    PQKeyPair {
        public_key_hex: hex::encode(pk.into_bytes()),
        private_key_hex: hex::encode(sk.into_bytes()),
    }
}

#[uniffi::export]
pub fn mldsa65_sign(
    message_hex: String,
    private_key_hex: String,
) -> Result<String, WalletError> {
    let message = hex::decode(&message_hex)?;
    let sk_bytes = hex::decode(&private_key_hex)?;

    let sk_array: [u8; 4032] = sk_bytes.try_into()
        .map_err(|_| WalletError::InvalidInput("Invalid private key length".into()))?;

    let sk = ml_dsa_65::PrivateKey::try_from_bytes(sk_array)
        .map_err(|e| WalletError::CryptoError(format!("Invalid private key: {}", e)))?;

    let sig = sk.try_sign(&message, &[])
        .map_err(|e| WalletError::CryptoError(format!("ML-DSA-65 signing failed: {}", e)))?;

    Ok(hex::encode(sig))
}

#[uniffi::export]
pub fn mldsa65_verify(
    message_hex: String,
    signature_hex: String,
    public_key_hex: String,
) -> Result<bool, WalletError> {
    let message = hex::decode(&message_hex)?;
    let sig_bytes = hex::decode(&signature_hex)?;
    let pk_bytes = hex::decode(&public_key_hex)?;

    let pk_array: [u8; 1952] = pk_bytes.try_into()
        .map_err(|_| WalletError::InvalidInput("Invalid public key length".into()))?;

    let pk = ml_dsa_65::PublicKey::try_from_bytes(pk_array)
        .map_err(|e| WalletError::CryptoError(format!("Invalid public key: {}", e)))?;

    let sig_array: [u8; 3309] = sig_bytes.try_into()
        .map_err(|_| WalletError::InvalidInput("Invalid signature length".into()))?;

    Ok(pk.verify(&message, &sig_array, &[]))
}

/// Build the 37-byte lock args for the deployed `mldsa65-lock-v2-rust`
/// contract (code_hash 0xd70653f7…78a4).
/// Layout: [0x80, 0x01, 0x01, 0x01, flag, blake2b256_personal("ckb-mldsa-sct", pubkey)]
/// where flag = (param_id(61) << 1) | has_signature(0) = 0x7a.
/// Matches contracts/mldsa-lock-v2-rust/src/{entry,helpers}.rs in
/// toastmanAu/ckb-mldsa-lock — NOT the legacy C lock the JS SDK targets.
#[uniffi::export]
pub fn mldsa65_lock_args_v2(public_key_hex: String) -> Result<String, WalletError> {
    let pk_bytes = hex::decode(&public_key_hex)?;
    if pk_bytes.len() != 1952 {
        return Err(WalletError::InvalidInput(format!(
            "Expected 1952-byte ML-DSA-65 public key, got {}",
            pk_bytes.len()
        )));
    }
    // Script-args pubkey hash uses the "ckb-mldsa-sct" personalization
    // (helpers::Hasher::script_args_hasher), NOT "ckb-default-hash".
    let pk_hash = {
        let mut hasher = Blake2bBuilder::new(32).personal(b"ckb-mldsa-sct").build();
        hasher.update(&pk_bytes);
        let mut out = [0u8; 32];
        hasher.finalize(&mut out);
        out
    };

    const MLDSA65_PARAM_ID: u8 = 61;
    let flag = (MLDSA65_PARAM_ID << 1) | 0; // no-signature bit in args

    let mut args = Vec::with_capacity(37);
    args.push(0x80);
    args.push(0x01);
    args.push(0x01);
    args.push(0x01);
    args.push(flag);
    args.extend_from_slice(&pk_hash);

    Ok(hex::encode(args))
}

#[uniffi::export]
pub fn pq_lock_args(public_key_hex: String, algorithm_id: u8) -> Result<String, WalletError> {
    let pk_bytes = hex::decode(&public_key_hex)?;

    let personal: &[u8] = match algorithm_id {
        60..=62 => b"ckb-mldsa-sct",
        63..=64 => b"ckb-falcon-sct",
        _ => b"ckb-mldsa-sct",
    };

    let pk_hash = {
        let mut hasher = Blake2bBuilder::new(32)
            .personal(personal)
            .build();
        hasher.update(&pk_bytes);
        let mut out = [0u8; 32];
        hasher.finalize(&mut out);
        out
    };

    let flag = (algorithm_id << 1) | 0;

    let mut args = Vec::with_capacity(37);
    args.push(0x80);
    args.push(0x01);
    args.push(0x01);
    args.push(0x01);
    args.push(flag);
    args.extend_from_slice(&pk_hash);

    Ok(hex::encode(args))
}

#[cfg(test)]
mod tests {
    use super::*;

    /// 64-byte BIP39 seed of "abandon ×11 about" with an empty passphrase
    /// (the canonical BIP39 test vector).
    const ABANDON_SEED_HEX: &str = "5eb00bbddcf069084889a8ab9155568165f5c453ccb85e70811aaed6f6da5fc19a5ac40b389cd370d086206dec8aa6c43daea6690f20ad3d8d48b2d2ce9e38e4";

    // ξ independently reproduced with a hand-rolled RFC 5869 HKDF in Python.
    // PERMANENT VECTORS. A change to either constant moves EVERY app
    // post-quantum address (funds at the old address become unreachable from
    // the restored wallet). Never edit these to make a test pass.
    const ABANDON_XI_HEX: &str = "912eafbe30c8bc53c6a670c84dcd78278bf6a8322fcce202717a1e3fa45d3cfa";
    const ABANDON_ARGS_HEX: &str = "800101017ad56bddea080b97761e047425269a5e4aa71ffc852fc0cc91b2bcb8615f08c468";

    #[test]
    fn bip39_seed_derivation_is_pinned() {
        let seed = crate::mnemonic::mnemonic_to_seed(
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about".into(),
            String::new(),
        )
        .unwrap();
        assert_eq!(seed, ABANDON_SEED_HEX);

        let xi = derive_mldsa65_xi(&hex::decode(&seed).unwrap()).unwrap();
        let kp = mldsa65_from_bip39_seed(seed).unwrap();
        let args = mldsa65_lock_args_v2(kp.public_key_hex.clone()).unwrap();
        assert_eq!(hex::encode(xi), ABANDON_XI_HEX);
        assert_eq!(args, ABANDON_ARGS_HEX);
        // The BIP39 path is exactly the raw path applied to ξ.
        let raw = mldsa65_from_seed(hex::encode(xi)).unwrap();
        assert_eq!(raw.public_key_hex, kp.public_key_hex);
    }

    #[test]
    fn bip39_seed_derivation_rejects_other_lengths() {
        for bad in ["11".repeat(32), "11".repeat(63)] {
            match mldsa65_from_bip39_seed(bad) {
                Err(WalletError::InvalidInput(m)) => assert_eq!(m, "BIP39 seed must be 64 bytes"),
                Err(e) => panic!("wrong error: {:?}", e),
                Ok(_) => panic!("accepted a non-64-byte seed"),
            }
        }
        let plain = mldsa65_from_bip39_seed(ABANDON_SEED_HEX.into()).unwrap();
        let prefixed = mldsa65_from_bip39_seed(format!("0x{}", ABANDON_SEED_HEX)).unwrap();
        assert_eq!(plain.public_key_hex, prefixed.public_key_hex);
    }

    #[test]
    fn different_mnemonics_give_different_xi() {
        let a = crate::mnemonic::generate_mnemonic(24).unwrap();
        let b = crate::mnemonic::generate_mnemonic(24).unwrap();
        assert_ne!(a.mnemonic, b.mnemonic);
        let ka = mldsa65_from_bip39_seed(a.seed_hex).unwrap();
        let kb = mldsa65_from_bip39_seed(b.seed_hex).unwrap();
        assert_ne!(ka.public_key_hex, kb.public_key_hex);
    }

    /// Captured from main@2e34acb BEFORE this change: the raw 32-byte ξ path
    /// (used by the harness and signing tests) must not move.
    #[test]
    fn raw_seed_function_unchanged() {
        let kp = mldsa65_from_seed("11".repeat(32)).unwrap();
        assert_eq!(&kp.public_key_hex[..16], "b1f7a2c606aef9f2");
        assert_eq!(
            mldsa65_lock_args_v2(kp.public_key_hex).unwrap(),
            "800101017a93a1058e3570c5c931ff42d5ee15c0d4730733406a40d3a675cddefbf7f2fb04"
        );
    }
}
