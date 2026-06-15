use crate::WalletError;
use crate::molecule::*;
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
    pub capacity: u64,
}

#[derive(uniffi::Record, Clone)]
pub struct TxOutput {
    pub capacity: u64,
    pub lock_code_hash: String,
    pub lock_hash_type: String,
    pub lock_args: String,
    pub type_code_hash: String,
    pub type_hash_type: String,
    pub type_args: String,
    pub data: String,
}

#[derive(uniffi::Record)]
pub struct TxCellDep {
    pub tx_hash: String,
    pub index: u32,
    pub dep_type: u8,
}

#[derive(uniffi::Record)]
pub struct TransactionRequest {
    pub inputs: Vec<TxInput>,
    pub outputs: Vec<TxOutput>,
    pub cell_deps: Vec<TxCellDep>,
    /// Block hashes referenced as header_deps (e.g. NervosDAO deposit/withdraw
    /// headers). MUST be included so the hashed RawTransaction — and therefore
    /// the signing tx_hash — matches the broadcast transaction.
    #[uniffi(default = [])]
    pub header_deps: Vec<String>,
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
    let total_input: u64 = request.inputs.iter().map(|i| i.capacity).sum();

    let cell_deps: Vec<CellDepSer> = request.cell_deps.iter().map(|dep| {
        Ok(CellDepSer {
            out_point: OutPointSer {
                tx_hash: hex_to_byte32(&dep.tx_hash)?,
                index: dep.index,
            },
            dep_type: dep.dep_type,
        })
    }).collect::<Result<Vec<_>, String>>()
    .map_err(|e| WalletError::InvalidInput(e))?;

    let inputs: Vec<CellInputSer> = request.inputs.iter().map(|input| {
        Ok(CellInputSer {
            since: input.since,
            previous_output: OutPointSer {
                tx_hash: hex_to_byte32(&input.tx_hash)?,
                index: input.index,
            },
        })
    }).collect::<Result<Vec<_>, String>>()
    .map_err(|e| WalletError::InvalidInput(e))?;

    let outputs: Vec<CellOutputSer> = request.outputs.iter().map(|output| {
        let lock = ScriptSer {
            code_hash: hex_to_byte32(&output.lock_code_hash)?,
            hash_type: match output.lock_hash_type.as_str() {
                "type" => 1,
                _ => 0,
            },
            args: hex_to_bytes(&output.lock_args)?,
        };
        let type_ = if output.type_code_hash.is_empty() {
            None
        } else {
            Some(ScriptSer {
                code_hash: hex_to_byte32(&output.type_code_hash)?,
                hash_type: match output.type_hash_type.as_str() {
                    "type" => 1,
                    _ => 0,
                },
                args: hex_to_bytes(&output.type_args)?,
            })
        };
        Ok(CellOutputSer {
            capacity: output.capacity,
            lock,
            type_,
        })
    }).collect::<Result<Vec<_>, String>>()
    .map_err(|e| WalletError::InvalidInput(e))?;

    let outputs_data: Vec<Vec<u8>> = request.outputs.iter()
        .map(|o| hex_to_bytes(&o.data).unwrap_or_default())
        .collect();

    let header_deps: Vec<[u8; 32]> = request.header_deps.iter()
        .map(|h| hex_to_byte32(h))
        .collect::<Result<Vec<_>, String>>()
        .map_err(WalletError::InvalidInput)?;

    let raw = RawTransactionSer {
        version: 0,
        cell_deps,
        header_deps,
        inputs,
        outputs,
        outputs_data,
    };

    // Serialize raw transaction
    let mut raw_bytes = Vec::new();
    raw.serialize(&mut raw_bytes);

    // Compute tx_hash
    let tx_hash = ckb_blake2b(&raw_bytes);

    // Estimate fee from actual transaction size (including placeholder witnesses)
    let witness_placeholder = vec![vec![0u8; 65]; request.inputs.len()];
    let tx_with_witnesses = TransactionSer {
        raw,
        witnesses: witness_placeholder,
    };
    let mut full_tx_bytes = Vec::new();
    tx_with_witnesses.serialize(&mut full_tx_bytes);
    let fee = (full_tx_bytes.len() as u64) * request.fee_rate / 1000;

    Ok(BuiltTransaction {
        raw_transaction_hex: hex::encode(&raw_bytes),
        tx_hash_hex: hex::encode(&tx_hash),
        estimated_fee: fee,
        total_input_capacity: total_input,
        total_output_capacity: total_output,
    })
}
