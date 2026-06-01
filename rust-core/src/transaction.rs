use crate::WalletError;
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

#[derive(uniffi::Record)]
pub struct TxInput {
    pub tx_hash: String,
    pub index: u32,
    pub since: u64,
}

#[derive(uniffi::Record)]
pub struct TxOutput {
    pub capacity: u64,
    pub lock_code_hash: String,
    pub lock_hash_type: String,
    pub lock_args: String,
    pub type_code_hash: String,
    pub type_hash_type: String,
    pub type_args: String,
}

#[derive(uniffi::Record)]
pub struct TransactionRequest {
    pub inputs: Vec<TxInput>,
    pub outputs: Vec<TxOutput>,
    pub fee_rate: u64,
}

#[derive(uniffi::Record)]
pub struct BuiltTransaction {
    pub raw_transaction_hex: String,
    pub tx_hash_hex: String,
    pub estimated_fee: u64,
    pub total_input_capacity: u64,
    pub total_output_capacity: u64,
}

#[uniffi::export]
pub fn build_transaction(request: TransactionRequest) -> Result<BuiltTransaction, WalletError> {
    let total_output: u64 = request.outputs.iter().map(|o| o.capacity).sum();

    let estimated_size = (request.inputs.len() * 44 + request.outputs.len() * 68 + 4) as u64;
    let fee = estimated_size * request.fee_rate / 1000;

    let mut raw_tx = Vec::new();

    raw_tx.extend_from_slice(&0u32.to_le_bytes());

    raw_tx.extend_from_slice(&(request.inputs.len() as u32).to_le_bytes());
    for input in &request.inputs {
        let tx_hash_bytes = hex::decode(&input.tx_hash)?;
        raw_tx.extend_from_slice(&tx_hash_bytes);
        raw_tx.extend_from_slice(&input.index.to_le_bytes());
        raw_tx.extend_from_slice(&input.since.to_le_bytes());
    }

    raw_tx.extend_from_slice(&(request.outputs.len() as u32).to_le_bytes());
    for output in &request.outputs {
        raw_tx.extend_from_slice(&output.capacity.to_le_bytes());

        let code_hash = hex::decode(&output.lock_code_hash)?;
        raw_tx.extend_from_slice(&code_hash);
        let hash_type = match output.lock_hash_type.as_str() {
            "data" => 0x00u8,
            "type" => 0x01u8,
            _ => 0x00u8,
        };
        raw_tx.push(hash_type);
        let args = hex::decode(&output.lock_args)?;
        raw_tx.extend_from_slice(&(args.len() as u32).to_le_bytes());
        raw_tx.extend_from_slice(&args);

        if !output.type_code_hash.is_empty() {
            let type_code_hash = hex::decode(&output.type_code_hash)?;
            raw_tx.extend_from_slice(&type_code_hash);
            let type_hash_type = match output.type_hash_type.as_str() {
                "data" => 0x00u8,
                "type" => 0x01u8,
                _ => 0x00u8,
            };
            raw_tx.push(type_hash_type);
            let type_args = hex::decode(&output.type_args)?;
            raw_tx.extend_from_slice(&(type_args.len() as u32).to_le_bytes());
            raw_tx.extend_from_slice(&type_args);
        } else {
            raw_tx.push(0x00);
        }
    }

    let tx_hash = ckb_blake2b(&raw_tx);

    Ok(BuiltTransaction {
        raw_transaction_hex: hex::encode(&raw_tx),
        tx_hash_hex: hex::encode(&tx_hash),
        estimated_fee: fee,
        total_input_capacity: 0,
        total_output_capacity: total_output,
    })
}
