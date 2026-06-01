use crate::WalletError;
use secp256k1::{SecretKey, Secp256k1, Message};
use blake2b_ref::Blake2bBuilder;

fn ckb_blake2b(data: &[u8]) -> [u8; 32] {
    let mut hasher = Blake2bBuilder::new(32)
        .personal(b"ckb-default-hash")
        .build();
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
        "mldsa65" => {
            Err(WalletError::CryptoError("ML-DSA-65 signing requires runtime key loading — implement via JNI callback".into()))
        }
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
            Err(WalletError::CryptoError("ML-DSA-65 message signing requires runtime key loading — implement via JNI callback".into()))
        }
        _ => Err(WalletError::InvalidInput(format!("Unsupported algorithm: {}", algorithm))),
    }
}
