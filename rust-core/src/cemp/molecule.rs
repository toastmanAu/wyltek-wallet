//! Molecule serialization / deserialization for CEMP-PQ on-chain structures:
//! Profile, EncryptedMessage, MessagePointer.

use crate::cemp::CempError;
use crate::molecule::{read_table_fields, serialize_table, write_bytes};

// ---- Internal helpers ----

/// Serialize a byte slice as a molecule `Bytes` field (len prefix + raw bytes).
fn bytes_field(data: &[u8]) -> Vec<u8> {
    let mut v = Vec::new();
    write_bytes(&mut v, data);
    v
}

/// Parse a molecule `Bytes` field: read the u32 LE length prefix, return the payload.
fn read_bytes_field(field: &[u8]) -> Option<Vec<u8>> {
    if field.len() < 4 {
        return None;
    }
    let len = u32::from_le_bytes(field[0..4].try_into().ok()?) as usize;
    field.get(4..4 + len).map(|s| s.to_vec())
}

fn enc<E: std::fmt::Display>(e: E) -> CempError {
    CempError::Encoding(e.to_string())
}

// ---- EncryptedMessage (pub(crate) — used by crypto.rs) ----

/// Serialize an `EncryptedMessage` as a molecule table with fields:
/// - field 0: `kem_ct` (Bytes)
/// - field 1: `nonce`  (Bytes)
/// - field 2: `ct`     (Bytes)
pub(crate) fn serialize_encrypted_message(kem_ct: &[u8], nonce: &[u8], ct: &[u8]) -> Vec<u8> {
    serialize_table(&[bytes_field(kem_ct), bytes_field(nonce), bytes_field(ct)])
}

/// Parse an `EncryptedMessage` molecule back into `(kem_ct, nonce, ciphertext)`.
/// The nonce must be exactly 12 bytes.
pub(crate) fn parse_encrypted_message(
    blob: &[u8],
) -> Result<(Vec<u8>, [u8; 12], Vec<u8>), CempError> {
    let fields = read_table_fields(blob)
        .ok_or_else(|| CempError::Encoding("bad EncryptedMessage table".into()))?;
    if fields.len() != 3 {
        return Err(CempError::Encoding("EncryptedMessage needs 3 fields".into()));
    }
    let kem = read_bytes_field(fields[0]).ok_or_else(|| enc("kem_ct field"))?;
    let nonce_v = read_bytes_field(fields[1]).ok_or_else(|| enc("nonce field"))?;
    let ct = read_bytes_field(fields[2]).ok_or_else(|| enc("ct field"))?;
    let nonce: [u8; 12] = nonce_v
        .try_into()
        .map_err(|_| CempError::Encoding("nonce must be 12 bytes".into()))?;
    Ok((kem, nonce, ct))
}

// ---- Profile (UniFFI) ----
//
// Profile table field order:
//   field 0: dsa_pub (Bytes — ML-DSA-65 encapsulation key, 1952 bytes)
//   field 1: kem_pub (Bytes — ML-KEM-768 encapsulation key, 1184 bytes)
//   field 2: metadata (Bytes — arbitrary UTF-8)

/// Serialize a CEMP-PQ Profile cell data blob.
///
/// All hex inputs may optionally carry a `0x` prefix.
#[uniffi::export]
pub fn serialize_profile(
    dsa_pub_hex: String,
    kem_pub_hex: String,
    metadata_utf8: String,
) -> Result<String, CempError> {
    let dsa = hex::decode(dsa_pub_hex.trim_start_matches("0x")).map_err(enc)?;
    let kem = hex::decode(kem_pub_hex.trim_start_matches("0x")).map_err(enc)?;
    let table = serialize_table(&[
        bytes_field(&dsa),
        bytes_field(&kem),
        bytes_field(metadata_utf8.as_bytes()),
    ]);
    Ok(hex::encode(table))
}

/// Extract the ML-KEM-768 encapsulation key (field 1) from a Profile blob.
#[uniffi::export]
pub fn parse_profile_kem(profile_data_hex: String) -> Result<String, CempError> {
    field_hex(&profile_data_hex, 1)
}

/// Extract the ML-DSA-65 encapsulation key (field 0) from a Profile blob.
#[uniffi::export]
pub fn parse_profile_dsa(profile_data_hex: String) -> Result<String, CempError> {
    field_hex(&profile_data_hex, 0)
}

fn field_hex(profile_data_hex: &str, idx: usize) -> Result<String, CempError> {
    let blob = hex::decode(profile_data_hex.trim_start_matches("0x")).map_err(enc)?;
    let fields = read_table_fields(&blob)
        .ok_or_else(|| CempError::Encoding("bad Profile table".into()))?;
    let field = fields
        .get(idx)
        .ok_or_else(|| CempError::Encoding("profile field missing".into()))?;
    let v = read_bytes_field(field).ok_or_else(|| enc("profile field payload"))?;
    Ok(hex::encode(v))
}

// ---- MessagePointer (UniFFI) ----
//
// Fixed layout (not a molecule table): tx_hash[32] ++ index[4 LE] = 36 bytes total.

/// Parsed output of a MessagePointer.
#[derive(Clone, uniffi::Record)]
pub struct MessagePointerOut {
    pub tx_hash_hex: String,
    pub index: u32,
}

/// Serialize a MessagePointer as 36 raw bytes (tx_hash ++ index LE).
#[uniffi::export]
pub fn serialize_message_pointer(tx_hash_hex: String, index: u32) -> Result<String, CempError> {
    let tx = hex::decode(tx_hash_hex.trim_start_matches("0x")).map_err(enc)?;
    if tx.len() != 32 {
        return Err(CempError::Encoding("tx_hash must be 32 bytes".into()));
    }
    let mut buf = Vec::with_capacity(36);
    buf.extend_from_slice(&tx);
    buf.extend_from_slice(&index.to_le_bytes());
    Ok(hex::encode(buf))
}

/// Parse a 36-byte MessagePointer back into its components.
#[uniffi::export]
pub fn parse_message_pointer(hex_str: String) -> Result<MessagePointerOut, CempError> {
    let blob = hex::decode(hex_str.trim_start_matches("0x")).map_err(enc)?;
    if blob.len() != 36 {
        return Err(CempError::Encoding(format!(
            "MessagePointer must be 36 bytes, got {}",
            blob.len()
        )));
    }
    let tx_hash_hex = hex::encode(&blob[0..32]);
    let index = u32::from_le_bytes(blob[32..36].try_into().map_err(enc)?);
    Ok(MessagePointerOut { tx_hash_hex, index })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn profile_roundtrip() {
        let dsa = hex::encode([0xAAu8; 1952]);
        let kem = hex::encode([0xBBu8; 1184]);
        let p = serialize_profile(dsa.clone(), kem.clone(), "Phill".into()).unwrap();
        assert_eq!(parse_profile_dsa(p.clone()).unwrap(), dsa);
        assert_eq!(parse_profile_kem(p).unwrap(), kem);
    }

    #[test]
    fn pointer_roundtrip() {
        let tx = hex::encode([0x11u8; 32]);
        let mp = serialize_message_pointer(tx.clone(), 7).unwrap();
        let out = parse_message_pointer(mp).unwrap();
        assert_eq!(out.tx_hash_hex, tx);
        assert_eq!(out.index, 7);
    }

    #[test]
    fn encrypted_message_roundtrip() {
        let blob = serialize_encrypted_message(&[1, 2, 3], &[9u8; 12], &[4, 5, 6, 7]);
        let (k, n, c) = parse_encrypted_message(&blob).unwrap();
        assert_eq!(k, vec![1, 2, 3]);
        assert_eq!(n, [9u8; 12]);
        assert_eq!(c, vec![4, 5, 6, 7]);
    }

    #[test]
    fn pointer_wrong_length_fails() {
        assert!(parse_message_pointer("deadbeef".into()).is_err());
    }

    #[test]
    fn profile_with_empty_metadata() {
        let dsa = hex::encode([0u8; 1952]);
        let kem = hex::encode([0u8; 1184]);
        let p = serialize_profile(dsa.clone(), kem.clone(), String::new()).unwrap();
        assert_eq!(parse_profile_dsa(p.clone()).unwrap(), dsa);
        assert_eq!(parse_profile_kem(p).unwrap(), kem);
    }
}
