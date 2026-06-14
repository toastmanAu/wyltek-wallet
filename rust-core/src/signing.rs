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

// ── ckb-mldsa-lock-v2-rust witness construction ─────────────────────────────
//
// Matches the DEPLOYED contract `mldsa65-lock-v2-rust`
// (code_hash 0xd70653f7…78a4) at toastmanAu/ckb-mldsa-lock — see
// contracts/mldsa-lock-v2-rust/src/{entry,helpers,streamer}.rs.
//
// NOT the legacy C lock (0x8984f4…d310d) the bundled JS SDK targets — that
// lock is deprecated, has a known sighash coverage gap, and has a lost owner.
//
// Protocol:
//   • digest  = blake2b("ckb-mldsa-msg", generate_ckb_tx_message_all stream)
//   • sign    = ML-DSA-65 over `digest` with ctx = "CKB-MLDSA-LOCK"
//               (FIPS-204 §5.4 M' framing applied by the signer)
//   • witness = WitnessArgs(lock = [flag(0x7b) | pubkey(1952) | sig(3309)])
//
// The CighashAll stream (streamer.rs::generate_ckb_tx_message_all) is, for a
// transaction whose inputs are ALL locked by this single PQ lock (one script
// group) and carry empty data:
//
//   tx_hash(32)
//   for each input cell:  CellOutput_molecule_bytes  ‖ u32le(data_len) ‖ data
//   u32le(0) ‖ u32le(0)                  // first group witness input_type/output_type (absent)
//   for inputs 1..N:      u32le(0)        // remaining group-input witnesses (empty "0x")
//
// (No orphan witnesses: witnesses.len() == inputs.len() in our envelope.)

use crate::molecule::{hex_to_bytes, CellOutputSer, ScriptSer};

/// FIPS-204 §5.4 context string the lock passes to verify_with_context.
const CKB_MLDSA_DOMAIN: &[u8] = b"CKB-MLDSA-LOCK";
/// blake2b personalization for the v2 signing digest (helpers::message_hasher).
const MLDSA_MSG_PERSONAL: &[u8] = b"ckb-mldsa-msg";
/// ML-DSA-65 param id (FIPS-204). Flag byte = (param_id << 1) | has_signature.
const MLDSA65_PARAM_ID: u8 = 61;
/// Witness flag: param 65 + signature bit set → (61 << 1) | 1 = 0x7b.
const MLDSA65_WITNESS_FLAG: u8 = (MLDSA65_PARAM_ID << 1) | 1;
const MLDSA65_PUBKEY_BYTES: usize = 1952;
const MLDSA65_SIG_BYTES: usize = 3309;

/// A live input cell being spent, as needed to reconstruct the CighashAll
/// stream off-chain. `data` is hex (empty / "0x" for a pure-CKB cell).
#[derive(uniffi::Record, Clone)]
pub struct MldsaInputCell {
    pub capacity: u64,
    pub lock_code_hash: String,
    pub lock_hash_type: String,
    pub lock_args: String,
    pub data: String,
}

fn hash_type_byte(hash_type: &str) -> u8 {
    match hash_type {
        "type" => 1,
        "data1" => 2,
        _ => 0, // "data"
    }
}

/// Serialize the molecule `CellOutput` of an input cell (no type script for a
/// pure-CKB cell), matching what `load_cell(Source::Input)` returns on-chain.
fn input_cell_output_bytes(cell: &MldsaInputCell) -> Result<Vec<u8>, WalletError> {
    let lock = ScriptSer {
        code_hash: crate::molecule::hex_to_byte32(&cell.lock_code_hash)
            .map_err(WalletError::InvalidInput)?,
        hash_type: hash_type_byte(&cell.lock_hash_type),
        args: hex_to_bytes(&cell.lock_args).map_err(WalletError::InvalidInput)?,
    };
    let out = CellOutputSer { capacity: cell.capacity, lock, type_: None };
    let mut buf = Vec::new();
    out.serialize(&mut buf);
    Ok(buf)
}

/// Reconstruct `blake2b("ckb-mldsa-msg", generate_ckb_tx_message_all)` for a
/// single-script-group transaction with empty-data inputs.
fn cighash_all_digest(
    tx_hash: &[u8; 32],
    inputs: &[MldsaInputCell],
) -> Result<[u8; 32], WalletError> {
    let mut h = Blake2bBuilder::new(32).personal(MLDSA_MSG_PERSONAL).build();
    h.update(tx_hash);
    for cell in inputs {
        let co = input_cell_output_bytes(cell)?;
        h.update(&co);
        let data = hex_to_bytes(&cell.data).map_err(WalletError::InvalidInput)?;
        h.update(&(data.len() as u32).to_le_bytes());
        h.update(&data);
    }
    // First group witness: input_type + output_type fields, both absent → len 0.
    h.update(&0u32.to_le_bytes());
    h.update(&0u32.to_le_bytes());
    // Remaining group-input witnesses (inputs 1..N) are empty "0x" → full_length 0.
    for _ in 1..inputs.len() {
        h.update(&0u32.to_le_bytes());
    }
    let mut out = [0u8; 32];
    h.finalize(&mut out);
    Ok(out)
}

fn serialize_witness_args(lock_data: &[u8]) -> Vec<u8> {
    // WitnessArgs table, lock field only: total(4) | offsets[3](12) | lock_len(4) | lock_data
    const HDR: usize = 4 + 3 * 4;
    let total = HDR + 4 + lock_data.len();

    let mut buf = vec![0u8; total];
    buf[0..4].copy_from_slice(&(total as u32).to_le_bytes());
    buf[4..8].copy_from_slice(&(HDR as u32).to_le_bytes()); // lock offset
    let after_lock = HDR + 4 + lock_data.len();
    buf[8..12].copy_from_slice(&(after_lock as u32).to_le_bytes()); // input_type (absent)
    buf[12..16].copy_from_slice(&(after_lock as u32).to_le_bytes()); // output_type (absent)
    buf[HDR..HDR + 4].copy_from_slice(&(lock_data.len() as u32).to_le_bytes());
    buf[HDR + 4..].copy_from_slice(lock_data);

    buf
}

/// Sign a CKB transaction for the deployed `mldsa65-lock-v2-rust` contract.
/// Returns the fully-formed WitnessArgs hex (no `0x` prefix) for witnesses[0].
///
/// tx_hash_hex:     32-byte raw-transaction hash (from `build_transaction`).
/// inputs:          every input cell being spent (all locked by this PQ lock).
/// private_key_hex: 4032-byte ML-DSA-65 secret key.
/// public_key_hex:  1952-byte ML-DSA-65 public key (embedded flat in the lock).
#[uniffi::export]
pub fn sign_ckb_mldsa65(
    tx_hash_hex: String,
    inputs: Vec<MldsaInputCell>,
    private_key_hex: String,
    public_key_hex: String,
) -> Result<String, WalletError> {
    if inputs.is_empty() {
        return Err(WalletError::InvalidInput("no input cells supplied".into()));
    }

    let tx_hash_bytes = hex::decode(tx_hash_hex.trim_start_matches("0x"))?;
    let tx_hash: [u8; 32] = tx_hash_bytes
        .try_into()
        .map_err(|_| WalletError::InvalidInput("tx_hash must be 32 bytes".into()))?;

    let pk_bytes = hex::decode(public_key_hex.trim_start_matches("0x"))?;
    if pk_bytes.len() != MLDSA65_PUBKEY_BYTES {
        return Err(WalletError::InvalidInput(format!(
            "Expected {}-byte ML-DSA-65 public key, got {}",
            MLDSA65_PUBKEY_BYTES,
            pk_bytes.len()
        )));
    }

    let sk_bytes = hex::decode(private_key_hex.trim_start_matches("0x"))?;
    let sk_array: [u8; 4032] = sk_bytes
        .try_into()
        .map_err(|_| WalletError::InvalidInput("ML-DSA-65 secret key must be 4032 bytes".into()))?;
    let sk = ml_dsa_65::PrivateKey::try_from_bytes(sk_array)
        .map_err(|e| WalletError::CryptoError(format!("Invalid ML-DSA-65 secret key: {}", e)))?;

    let digest = cighash_all_digest(&tx_hash, &inputs)?;
    let sig = sk
        .try_sign(&digest, CKB_MLDSA_DOMAIN)
        .map_err(|e| WalletError::CryptoError(format!("ML-DSA-65 signing failed: {}", e)))?;

    // Flat lock: [flag | pubkey | sig].
    let mut lock = Vec::with_capacity(1 + MLDSA65_PUBKEY_BYTES + MLDSA65_SIG_BYTES);
    lock.push(MLDSA65_WITNESS_FLAG);
    lock.extend_from_slice(&pk_bytes);
    lock.extend_from_slice(&sig);

    Ok(hex::encode(serialize_witness_args(&lock)))
}

/// Pre-signing witness placeholder of the exact size of the final WitnessArgs,
/// for fee sizing. WitnessArgs(lock = [flag | pubkey | sig]).
#[uniffi::export]
pub fn mldsa65_witness_placeholder_hex() -> String {
    const FLAT_LOCK_LEN: usize = 1 + MLDSA65_PUBKEY_BYTES + MLDSA65_SIG_BYTES;
    const HDR_ARGS: usize = 4 + 3 * 4;
    const WITNESS_ARGS_LEN: usize = HDR_ARGS + 4 + FLAT_LOCK_LEN;
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

/// secp256k1_blake160_sighash_all witness builder. Computes the canonical CKB
/// sighash and returns the FULL `WitnessArgs(lock = 65-byte recoverable sig)`
/// hex (no `0x`) to drop straight into witnesses[0].
///
/// The standard lock expects: message =
///   ckbhash(tx_hash || u64le(len(W0)) || W0 || [u64le(len(Wi)) || Wi ...])
/// where W0 is the WitnessArgs with its lock field zeroed to 65 bytes (85 bytes
/// serialized), and Wi are the remaining same-group witnesses. The final
/// witnesses[0] is the same WitnessArgs with lock = the real signature.
///
/// Assumes a single script group: all `num_inputs` inputs share this secp lock
/// and witnesses[1..num_inputs] are empty. (The previous code hashed a bare
/// 65-byte placeholder with un-personalized blake2b and emitted a bare sig —
/// both wrong; the secp lock rejected it.)
#[uniffi::export]
pub fn sign_ckb_secp256k1_witness(
    tx_hash_hex: String,
    num_inputs: u32,
    private_key_hex: String,
) -> Result<String, WalletError> {
    let tx_hash = hex::decode(tx_hash_hex.trim_start_matches("0x"))?;
    if tx_hash.len() != 32 {
        return Err(WalletError::InvalidInput(format!(
            "tx_hash must be 32 bytes, got {}",
            tx_hash.len()
        )));
    }

    // W0 = WitnessArgs(lock = 65 zero bytes), input_type/output_type absent → 85 bytes.
    let placeholder = serialize_witness_args(&[0u8; 65]);

    let mut hasher = Blake2bBuilder::new(32).personal(b"ckb-default-hash").build();
    hasher.update(&tx_hash);
    hasher.update(&(placeholder.len() as u64).to_le_bytes());
    hasher.update(&placeholder);
    // Remaining same-group witnesses (inputs 1..num_inputs) are empty.
    for _ in 1..num_inputs.max(1) {
        hasher.update(&0u64.to_le_bytes());
    }
    let mut message = [0u8; 32];
    hasher.finalize(&mut message);

    let secp = Secp256k1::new();
    let sk = SecretKey::from_slice(&hex::decode(private_key_hex.trim_start_matches("0x"))?)?;
    let signature = secp.sign_ecdsa_recoverable(&Message::from_digest(message), &sk);
    let (recovery_id, serialized) = signature.serialize_compact();

    let mut lock = Vec::with_capacity(65);
    lock.extend_from_slice(&serialized);
    lock.push(recovery_id.to_i32() as u8);

    Ok(hex::encode(serialize_witness_args(&lock)))
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

#[cfg(test)]
mod mldsa_v2_tests {
    use super::*;
    use crate::pq_keys::{mldsa65_from_seed, mldsa65_lock_args_v2};

    fn sample_input(pq_args: &str) -> MldsaInputCell {
        MldsaInputCell {
            capacity: 100_0000_0000,
            lock_code_hash:
                "0xd70653f7fd51e173ec506b76081f37bf4acebb8a15dc79e6d4ad43ca4d3b78a4".into(),
            lock_hash_type: "type".into(),
            lock_args: format!("0x{pq_args}"),
            data: String::new(),
        }
    }

    /// The witness the contract verifies must be WitnessArgs(lock=[flag|pk|sig]),
    /// flag = 0x7b, lock length exactly 1 + 1952 + 3309.
    #[test]
    fn witness_has_flat_lock_layout() {
        let seed = "11".repeat(32);
        let kp = mldsa65_from_seed(seed.clone()).unwrap();
        let args = mldsa65_lock_args_v2(kp.public_key_hex.clone()).unwrap();
        let tx_hash = "22".repeat(32);

        let witness_hex = sign_ckb_mldsa65(
            tx_hash,
            vec![sample_input(&args)],
            kp.private_key_hex,
            kp.public_key_hex,
        )
        .unwrap();
        let witness = hex::decode(&witness_hex).unwrap();

        // WitnessArgs: total(4) | lock_off(4)=16 | input_type_off | output_type_off | lock_len(4) | lock
        let lock_len = u32::from_le_bytes(witness[16..20].try_into().unwrap()) as usize;
        assert_eq!(lock_len, 1 + 1952 + 3309, "flat lock length");
        assert_eq!(witness[20], 0x7b, "flag = (61<<1)|1");
        // args side: 37-byte v2-rust layout
        let args_bytes = hex::decode(&args).unwrap();
        assert_eq!(args_bytes.len(), 37);
        assert_eq!(&args_bytes[0..4], &[0x80, 0x01, 0x01, 0x01]);
        assert_eq!(args_bytes[4], 0x7a, "args flag = (61<<1)|0");
    }

    /// The signature in the witness must verify against the SAME CighashAll
    /// digest under the FIPS-204 context — proves the sign side is self-consistent.
    #[test]
    fn signature_verifies_over_cighash_digest() {
        let seed = "33".repeat(32);
        let kp = mldsa65_from_seed(seed).unwrap();
        let args = mldsa65_lock_args_v2(kp.public_key_hex.clone()).unwrap();
        let tx_hash_hex = "44".repeat(32);
        let inputs = vec![sample_input(&args)];

        let witness_hex = sign_ckb_mldsa65(
            tx_hash_hex.clone(),
            inputs.clone(),
            kp.private_key_hex,
            kp.public_key_hex.clone(),
        )
        .unwrap();
        let witness = hex::decode(&witness_hex).unwrap();
        let sig = &witness[20 + 1 + MLDSA65_PUBKEY_BYTES..];

        let tx_hash: [u8; 32] = hex::decode(&tx_hash_hex).unwrap().try_into().unwrap();
        let digest = cighash_all_digest(&tx_hash, &inputs).unwrap();

        let pk_bytes = hex::decode(&kp.public_key_hex).unwrap();
        let pk = ml_dsa_65::PublicKey::try_from_bytes(pk_bytes.try_into().unwrap()).unwrap();
        let sig_arr: [u8; MLDSA65_SIG_BYTES] = sig.try_into().unwrap();
        assert!(pk.verify(&digest, &sig_arr, CKB_MLDSA_DOMAIN), "sig verifies over digest+ctx");
    }
}

#[cfg(test)]
mod secp_witness_tests {
    use super::*;
    use crate::keys::generate_secp256k1_keypair;
    use secp256k1::{ecdsa::RecoverableSignature, ecdsa::RecoveryId, Message, Secp256k1};

    /// Witness must be WitnessArgs(lock=65 bytes) = 85 bytes, and the embedded
    /// signature must recover to the signing key over the canonical sighash.
    #[test]
    fn secp_witness_layout_and_recovery() {
        let kp = generate_secp256k1_keypair("ab".repeat(32), "m/44'/302'/0'/0/0".into()).unwrap();
        let tx_hash_hex = "cd".repeat(32);

        let witness_hex =
            sign_ckb_secp256k1_witness(tx_hash_hex.clone(), 1, kp.private_key_hex).unwrap();
        let witness = hex::decode(&witness_hex).unwrap();

        // WitnessArgs(lock=65): total 85, lock offset 16, lock_len 65.
        assert_eq!(witness.len(), 85);
        assert_eq!(u32::from_le_bytes(witness[16..20].try_into().unwrap()), 65);
        let sig = &witness[20..85];

        // Recompute the canonical sighash and confirm the sig recovers to our key.
        let tx_hash = hex::decode(&tx_hash_hex).unwrap();
        let placeholder = serialize_witness_args(&[0u8; 65]);
        let mut h = Blake2bBuilder::new(32).personal(b"ckb-default-hash").build();
        h.update(&tx_hash);
        h.update(&(placeholder.len() as u64).to_le_bytes());
        h.update(&placeholder);
        let mut msg = [0u8; 32];
        h.finalize(&mut msg);

        let secp = Secp256k1::new();
        let rid = RecoveryId::from_i32(sig[64] as i32).unwrap();
        let rsig = RecoverableSignature::from_compact(&sig[..64], rid).unwrap();
        let recovered = secp.recover_ecdsa(&Message::from_digest(msg), &rsig).unwrap();
        let expected = secp256k1::PublicKey::from_slice(&hex::decode(&kp.public_key_hex).unwrap()).unwrap();
        assert_eq!(recovered, expected, "sig recovers to signing key over canonical sighash");
    }
}
