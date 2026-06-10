use crate::WalletError;
use secp256k1::{SecretKey, Secp256k1, Message};
use blake2b_ref::Blake2bBuilder;
use fips204::ml_dsa_65;
use fips204::traits::{SerDes, Signer, Verifier};

fn ckb_blake2b(data: &[u8]) -> [u8; 32] {
    let mut hasher = Blake2bBuilder::new(32)
        .personal(b"ckb-default-hash")
        .build();
    hasher.update(data);
    let mut out = [0u8; 32];
    hasher.finalize(&mut out);
    out
}

fn standard_blake2b_256(data: &[u8]) -> [u8; 32] {
    let mut hasher = Blake2bBuilder::new(32).build();
    hasher.update(data);
    let mut out = [0u8; 32];
    hasher.finalize(&mut out);
    out
}

#[uniffi::export]
pub fn sign_transaction(
    raw_tx_hex: String,
    private_key_hex: String,
    algorithm: String,
) -> Result<String, WalletError> {
    let raw_tx = hex::decode(&raw_tx_hex)?;

    match algorithm.as_str() {
        "secp256k1" => sign_secp256k1(raw_tx, private_key_hex),
        "mldsa65" => sign_mldsa65(raw_tx, private_key_hex),
        _ => Err(WalletError::InvalidInput(format!("Unsupported signing algorithm: {}", algorithm))),
    }
}

fn sign_secp256k1(raw_tx: Vec<u8>, private_key_hex: String) -> Result<String, WalletError> {
    let hash = ckb_blake2b(&raw_tx);

    let secp = Secp256k1::new();
    let sk = SecretKey::from_slice(&hex::decode(&private_key_hex)?)?;
    let msg = Message::from_digest(hash);
    let signature = secp.sign_ecdsa_recoverable(&msg, &sk);

    let (recovery_id, serialized) = signature.serialize_compact();
    let mut result = Vec::with_capacity(65);
    result.extend_from_slice(&serialized);
    result.push(recovery_id.to_i32() as u8);

    Ok(hex::encode(result))
}

fn sign_mldsa65(raw_tx: Vec<u8>, private_key_hex: String) -> Result<String, WalletError> {
    let sk_bytes = hex::decode(&private_key_hex)?;

    let sk_array: [u8; 4032] = sk_bytes.try_into()
        .map_err(|_| WalletError::InvalidInput("Invalid ML-DSA-65 private key length".into()))?;

    let sk = ml_dsa_65::PrivateKey::try_from_bytes(sk_array)
        .map_err(|e| WalletError::CryptoError(format!("Invalid ML-DSA-65 private key: {}", e)))?;

    let sig = sk.try_sign(&raw_tx, &[])
        .map_err(|e| WalletError::CryptoError(format!("ML-DSA-65 signing failed: {}", e)))?;

    Ok(hex::encode(sig))
}

/// Sign a CKB SECP256K1_BLAKE160_SIGHASH_ALL transaction.
/// tx_hash_hex: the transaction hash (32 bytes, hex)
/// witness_placeholders_hex: list of witness data as hex strings.
///   For the input group being signed, the first witness should be a 65-byte placeholder.
///   Other witnesses in the same group should be empty ("0x").
/// private_key_hex: secp256k1 private key (32 bytes, hex)
/// Returns: 65-byte recoverable signature as hex string.
#[uniffi::export]
pub fn sign_ckb_secp256k1(
    tx_hash_hex: String,
    witness_placeholders_hex: Vec<String>,
    private_key_hex: String,
) -> Result<String, WalletError> {
    let tx_hash = hex::decode(&tx_hash_hex)?;
    if tx_hash.len() != 32 {
        return Err(WalletError::InvalidInput(format!(
            "tx_hash must be 32 bytes, got {}", tx_hash.len()
        )));
    }

    let mut sign_data = Vec::new();
    sign_data.extend_from_slice(&tx_hash);

    for witness_hex in &witness_placeholders_hex {
        let witness = hex::decode(witness_hex.strip_prefix("0x").unwrap_or(witness_hex))?;
        let len = witness.len() as u64;
        sign_data.extend_from_slice(&len.to_le_bytes());
        sign_data.extend_from_slice(&witness);
    }

    let hash = standard_blake2b_256(&sign_data);

    let secp = Secp256k1::new();
    let sk = SecretKey::from_slice(&hex::decode(&private_key_hex)?)?;
    let msg = Message::from_digest(hash);
    let signature = secp.sign_ecdsa_recoverable(&msg, &sk);

    let (recovery_id, serialized) = signature.serialize_compact();
    let mut result = Vec::with_capacity(65);
    result.extend_from_slice(&serialized);
    result.push(recovery_id.to_i32() as u8);

    Ok(hex::encode(result))
}

#[uniffi::export]
pub fn sign_message(
    message_hex: String,
    private_key_hex: String,
    algorithm: String,
) -> Result<String, WalletError> {
    let message = hex::decode(&message_hex)?;

    match algorithm.as_str() {
        "secp256k1" => {
            let secp = Secp256k1::new();
            let sk = SecretKey::from_slice(&hex::decode(&private_key_hex)?)?;

            let hash = ckb_blake2b(&message);

            let msg = Message::from_digest(hash);
            let sig = secp.sign_ecdsa_recoverable(&msg, &sk);
            let (rid, ser) = sig.serialize_compact();

            let mut result = Vec::with_capacity(65);
            result.extend_from_slice(&ser);
            result.push(rid.to_i32() as u8);
            Ok(hex::encode(result))
        }
        "mldsa65" => {
            let sk_bytes = hex::decode(&private_key_hex)?;

            let sk_array: [u8; 4032] = sk_bytes.try_into()
                .map_err(|_| WalletError::InvalidInput("Invalid ML-DSA-65 private key length".into()))?;

            let sk = ml_dsa_65::PrivateKey::try_from_bytes(sk_array)
                .map_err(|e| WalletError::CryptoError(format!("Invalid ML-DSA-65 private key: {}", e)))?;

            let sig = sk.try_sign(&message, &[])
                .map_err(|e| WalletError::CryptoError(format!("ML-DSA-65 signing failed: {}", e)))?;

            Ok(hex::encode(sig))
        }
        _ => Err(WalletError::InvalidInput(format!("Unsupported algorithm: {}", algorithm))),
    }
}

#[uniffi::export]
pub fn verify_signature(
    message_hex: String,
    signature_hex: String,
    public_key_hex: String,
    algorithm: String,
) -> Result<bool, WalletError> {
    let message = hex::decode(&message_hex)?;

    match algorithm.as_str() {
        "secp256k1" => {
            let sig_bytes = hex::decode(&signature_hex)?;
            if sig_bytes.len() != 65 {
                return Err(WalletError::InvalidInput("Invalid secp256k1 signature length".into()));
            }

            let pk_bytes = hex::decode(&public_key_hex)?;
            let secp = Secp256k1::new();
            let pk = secp256k1::PublicKey::from_slice(&pk_bytes)?;

            let hash = ckb_blake2b(&message);
            let msg = Message::from_digest(hash);

            let recovery_id = secp256k1::ecdsa::RecoveryId::from_i32(sig_bytes[64] as i32)
                .map_err(|e| WalletError::CryptoError(format!("Invalid recovery id: {}", e)))?;
            let signature = secp256k1::ecdsa::RecoverableSignature::from_compact(&sig_bytes[..64], recovery_id)?;

            let recovered = secp.recover_ecdsa(&msg, &signature)?;
            Ok(recovered == pk)
        }
        "mldsa65" => {
            let pk_bytes = hex::decode(&public_key_hex)?;
            let pk_array: [u8; 1952] = pk_bytes.try_into()
                .map_err(|_| WalletError::InvalidInput("Invalid ML-DSA-65 public key length".into()))?;

            let pk = ml_dsa_65::PublicKey::try_from_bytes(pk_array)
                .map_err(|e| WalletError::CryptoError(format!("Invalid ML-DSA-65 public key: {}", e)))?;

            let sig_bytes = hex::decode(&signature_hex)?;
            let sig_array: [u8; 3309] = sig_bytes.try_into()
                .map_err(|_| WalletError::InvalidInput("Invalid ML-DSA-65 signature length".into()))?;

            Ok(pk.verify(&message, &sig_array, &[]))
        }
        _ => Err(WalletError::InvalidInput(format!("Unsupported algorithm: {}", algorithm))),
    }
}
