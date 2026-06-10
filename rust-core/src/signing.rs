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

// ── ckb-mldsa-lock witness construction ─────────────────────────────────────
//
// Matches the deployed contract at toastmanAu/ckb-mldsa-lock (sdk/js/src).
// Signing message = blake2b("ckb-default-hash", DOMAIN || tx_hash).
// Signature is then ML-DSA over that digest with DOMAIN as ctx.
// Returned witness bytes are WitnessArgs(MldsaWitness(...)) ready for the
// witnesses[0] slot of the JSON-RPC transaction envelope.

const CKB_MLDSA_DOMAIN: &[u8] = b"CKB-MLDSA-LOCK";
const ARGS_VERSION: u8 = 0x01;
const ARGS_ALGO_ID: u8 = 0x02;
const ARGS_PARAM_ID: u8 = 0x02;
const MLDSA65_PUBKEY_BYTES: usize = 1952;
const MLDSA65_SIG_BYTES: usize = 3309;

fn ckb_mldsa_signing_message(tx_hash: &[u8; 32]) -> [u8; 32] {
    let mut hasher = Blake2bBuilder::new(32)
        .personal(b"ckb-default-hash")
        .build();
    hasher.update(CKB_MLDSA_DOMAIN);
    hasher.update(tx_hash);
    let mut out = [0u8; 32];
    hasher.finalize(&mut out);
    out
}

fn serialize_mldsa_witness(pubkey: &[u8], sig: &[u8]) -> Vec<u8> {
    // Layout: full_size(4) | offsets[6](24) | version(1) | algo_id(1) |
    //         param_id(1) | flags(1) | pubkey_len(4)+pubkey | sig_len(4)+sig
    const HDR: usize = 4 + 6 * 4;
    let total = HDR + 1 + 1 + 1 + 1 + 4 + MLDSA65_PUBKEY_BYTES + 4 + MLDSA65_SIG_BYTES;

    let mut buf = vec![0u8; total];
    buf[0..4].copy_from_slice(&(total as u32).to_le_bytes());

    let mut off = HDR as u32;
    buf[4..8].copy_from_slice(&off.to_le_bytes());      // version
    off += 1;
    buf[8..12].copy_from_slice(&off.to_le_bytes());     // algo_id
    off += 1;
    buf[12..16].copy_from_slice(&off.to_le_bytes());    // param_id
    off += 1;
    buf[16..20].copy_from_slice(&off.to_le_bytes());    // flags
    off += 1;
    buf[20..24].copy_from_slice(&off.to_le_bytes());    // pubkey
    off += 4 + MLDSA65_PUBKEY_BYTES as u32;
    buf[24..28].copy_from_slice(&off.to_le_bytes());    // sig

    let mut cursor = HDR;
    buf[cursor] = ARGS_VERSION;   cursor += 1;
    buf[cursor] = ARGS_ALGO_ID;   cursor += 1;
    buf[cursor] = ARGS_PARAM_ID;  cursor += 1;
    buf[cursor] = 0x00;           cursor += 1;
    buf[cursor..cursor + 4].copy_from_slice(&(MLDSA65_PUBKEY_BYTES as u32).to_le_bytes());
    cursor += 4;
    buf[cursor..cursor + MLDSA65_PUBKEY_BYTES].copy_from_slice(pubkey);
    cursor += MLDSA65_PUBKEY_BYTES;
    buf[cursor..cursor + 4].copy_from_slice(&(MLDSA65_SIG_BYTES as u32).to_le_bytes());
    cursor += 4;
    buf[cursor..cursor + MLDSA65_SIG_BYTES].copy_from_slice(sig);

    buf
}

fn serialize_witness_args(lock_data: &[u8]) -> Vec<u8> {
    // Layout: total(4) | offsets[3](12) | lock_len(4) | lock_data
    const HDR: usize = 4 + 3 * 4;
    let total = HDR + 4 + lock_data.len();

    let mut buf = vec![0u8; total];
    buf[0..4].copy_from_slice(&(total as u32).to_le_bytes());
    buf[4..8].copy_from_slice(&(HDR as u32).to_le_bytes());                              // lock offset
    let after_lock = HDR + 4 + lock_data.len();
    buf[8..12].copy_from_slice(&(after_lock as u32).to_le_bytes());                      // input_type (absent)
    buf[12..16].copy_from_slice(&(after_lock as u32).to_le_bytes());                     // output_type (absent)
    buf[HDR..HDR + 4].copy_from_slice(&(lock_data.len() as u32).to_le_bytes());
    buf[HDR + 4..].copy_from_slice(lock_data);

    buf
}

/// Sign a CKB transaction with ML-DSA-65 for the deployed ckb-mldsa-lock
/// contract. Returns the fully-formed WitnessArgs hex string (no `0x` prefix)
/// to drop directly into witnesses[0].
///
/// tx_hash_hex: 32-byte raw-transaction hash (the CKB tx_hash returned by
///              the Rust transaction builder).
/// private_key_hex: 4032-byte ML-DSA-65 secret key.
/// public_key_hex:  1952-byte ML-DSA-65 public key (embedded in witness).
#[uniffi::export]
pub fn sign_ckb_mldsa65(
    tx_hash_hex: String,
    private_key_hex: String,
    public_key_hex: String,
) -> Result<String, WalletError> {
    let tx_hash_bytes = hex::decode(tx_hash_hex.trim_start_matches("0x"))?;
    let tx_hash: [u8; 32] = tx_hash_bytes.try_into()
        .map_err(|_| WalletError::InvalidInput("tx_hash must be 32 bytes".into()))?;

    let pk_bytes = hex::decode(public_key_hex.trim_start_matches("0x"))?;
    if pk_bytes.len() != MLDSA65_PUBKEY_BYTES {
        return Err(WalletError::InvalidInput(format!(
            "Expected {}-byte ML-DSA-65 public key, got {}",
            MLDSA65_PUBKEY_BYTES, pk_bytes.len()
        )));
    }

    let sk_bytes = hex::decode(private_key_hex.trim_start_matches("0x"))?;
    let sk_array: [u8; 4032] = sk_bytes.try_into()
        .map_err(|_| WalletError::InvalidInput("ML-DSA-65 secret key must be 4032 bytes".into()))?;
    let sk = ml_dsa_65::PrivateKey::try_from_bytes(sk_array)
        .map_err(|e| WalletError::CryptoError(format!("Invalid ML-DSA-65 secret key: {}", e)))?;

    let msg = ckb_mldsa_signing_message(&tx_hash);
    let sig = sk.try_sign(&msg, CKB_MLDSA_DOMAIN)
        .map_err(|e| WalletError::CryptoError(format!("ML-DSA-65 signing failed: {}", e)))?;

    let mldsa_witness = serialize_mldsa_witness(&pk_bytes, &sig);
    let witness_args = serialize_witness_args(&mldsa_witness);

    Ok(hex::encode(witness_args))
}

/// Pre-signing witness placeholder of the exact size the final WitnessArgs
/// will be. Use this to size witnesses[0] before computing the tx_hash so the
/// fee estimate is accurate. Bytes are zeros — content doesn't matter since
/// the ckb-mldsa-lock signing message only hashes the tx_hash, not witnesses.
#[uniffi::export]
pub fn mldsa65_witness_placeholder_hex() -> String {
    const HDR_WITNESS: usize = 4 + 6 * 4;
    const MLDSA_WITNESS_LEN: usize =
        HDR_WITNESS + 1 + 1 + 1 + 1 + 4 + MLDSA65_PUBKEY_BYTES + 4 + MLDSA65_SIG_BYTES;
    const HDR_ARGS: usize = 4 + 3 * 4;
    const WITNESS_ARGS_LEN: usize = HDR_ARGS + 4 + MLDSA_WITNESS_LEN;
    hex::encode(vec![0u8; WITNESS_ARGS_LEN])
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
