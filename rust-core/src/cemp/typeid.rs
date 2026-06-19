use crate::cemp::CempError;
use crate::molecule::{CellInputSer, OutPointSer};
use blake2b_ref::Blake2bBuilder;

/// CKB Type ID = blake2b("ckb-default-hash", CellInput(first_input) ++ u64_le(output_index)).
#[uniffi::export]
pub fn compute_type_id(
    first_input_tx_hash_hex: String,
    first_input_index: u32,
    first_input_since: u64,
    output_index: u64,
) -> Result<String, CempError> {
    let txh = hex::decode(first_input_tx_hash_hex.trim_start_matches("0x"))
        .map_err(|e| CempError::Encoding(e.to_string()))?;
    let tx_hash: [u8; 32] = txh.try_into().map_err(|_| CempError::Encoding("tx_hash must be 32 bytes".into()))?;
    let cell_input = CellInputSer { since: first_input_since, previous_output: OutPointSer { tx_hash, index: first_input_index } };
    let mut data = cell_input.to_bytes();
    data.extend_from_slice(&output_index.to_le_bytes());
    let mut h = Blake2bBuilder::new(32).personal(b"ckb-default-hash").build();
    h.update(&data);
    let mut out = [0u8; 32];
    h.finalize(&mut out);
    Ok(format!("0x{}", hex::encode(out)))
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn deterministic_and_index_sensitive() {
        let tx = hex::encode([0x11u8; 32]);
        let a = compute_type_id(tx.clone(), 0, 0, 0).unwrap();
        let b = compute_type_id(tx.clone(), 0, 0, 0).unwrap();
        let c = compute_type_id(tx, 0, 0, 1).unwrap();
        assert_eq!(a, b);
        assert_ne!(a, c);
        assert!(a.starts_with("0x") && a.len() == 66);
    }
}
