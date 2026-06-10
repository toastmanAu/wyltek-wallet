use crate::WalletError;
use blake2b_ref::Blake2bBuilder;
use fips204::ml_dsa_65;
use fips204::traits::{KeyGen, SerDes, Signer, Verifier};

fn ckb_blake2b(data: &[u8]) -> [u8; 32] {
    let mut hasher = Blake2bBuilder::new(32)
        .personal(b"ckb-default-hash")
        .build();
    hasher.update(data);
    let mut out = [0u8; 32];
    hasher.finalize(&mut out);
    out
}

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

#[uniffi::export]
pub fn mldsa65_from_seed(seed_hex: String) -> Result<PQKeyPair, WalletError> {
    let seed_bytes = hex::decode(&seed_hex)?;
    if seed_bytes.len() != 32 {
        return Err(WalletError::InvalidInput("Seed must be 32 bytes".into()));
    }

    let mut xi = [0u8; 32];
    xi.copy_from_slice(&seed_bytes);

    let (pk, sk) = ml_dsa_65::KG::keygen_from_seed(&xi);

    Ok(PQKeyPair {
        public_key_hex: hex::encode(pk.into_bytes()),
        private_key_hex: hex::encode(sk.into_bytes()),
    })
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

/// Build the 36-byte lock args for the deployed ckb-mldsa-lock contract.
/// Layout: version(1) | algo_id(1) | param_id(1) | flags(1) | blake2b256(pubkey).
/// Matches sdk/js/src/index.ts in toastmanAu/ckb-mldsa-lock.
#[uniffi::export]
pub fn mldsa65_lock_args_v2(public_key_hex: String) -> Result<String, WalletError> {
    let pk_bytes = hex::decode(&public_key_hex)?;
    if pk_bytes.len() != 1952 {
        return Err(WalletError::InvalidInput(format!(
            "Expected 1952-byte ML-DSA-65 public key, got {}",
            pk_bytes.len()
        )));
    }
    let pk_hash = ckb_blake2b(&pk_bytes);

    let mut args = Vec::with_capacity(36);
    args.push(0x01); // version
    args.push(0x02); // algo_id  = ML-DSA
    args.push(0x02); // param_id = ML-DSA-65
    args.push(0x00); // flags
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
