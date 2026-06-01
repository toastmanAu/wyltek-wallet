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
pub fn blake2b_256(data_hex: String) -> Result<String, crate::WalletError> {
    let data = hex::decode(&data_hex)?;
    let hash = ckb_blake2b(&data);
    Ok(hex::encode(hash))
}

#[uniffi::export]
pub fn ckb_hash(data_hex: String) -> Result<String, crate::WalletError> {
    let data = hex::decode(&data_hex)?;
    let hash = ckb_blake2b(&data);
    Ok(hex::encode(hash))
}
