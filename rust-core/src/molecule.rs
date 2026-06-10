// Minimal CKB Molecule serialization helpers for a simple transfer transaction.
// Molecule spec: https://github.com/nervosnetwork/molecule

fn write_u32(buf: &mut Vec<u8>, val: u32) {
    buf.extend_from_slice(&val.to_le_bytes());
}

fn write_u64(buf: &mut Vec<u8>, val: u64) {
    buf.extend_from_slice(&val.to_le_bytes());
}

fn write_bytes(buf: &mut Vec<u8>, data: &[u8]) {
    write_u32(buf, data.len() as u32);
    buf.extend_from_slice(data);
}

fn write_byte32(buf: &mut Vec<u8>, data: &[u8; 32]) {
    buf.extend_from_slice(data);
}

// option<T>: empty for None, item bytes for Some
fn write_option_script(buf: &mut Vec<u8>, script: Option<&ScriptSer>) {
    match script {
        Some(s) => s.serialize(buf),
        None => {}
    }
}

#[derive(Clone)]
pub struct ScriptSer {
    pub code_hash: [u8; 32],
    pub hash_type: u8,
    pub args: Vec<u8>,
}

impl ScriptSer {
    pub fn serialize(&self, buf: &mut Vec<u8>) {
        let item_count = 3u32;
        let offset_0 = 4 + item_count * 4; // 16
        let offset_1 = offset_0 + 32; // 48
        let offset_2 = offset_1 + 1; // 49
        write_u32(buf, item_count);
        write_u32(buf, offset_0);
        write_u32(buf, offset_1);
        write_u32(buf, offset_2);
        write_byte32(buf, &self.code_hash);
        buf.push(self.hash_type);
        write_bytes(buf, &self.args);
    }

    pub fn serialized_size(&self) -> usize {
        16 + 32 + 1 + 4 + self.args.len()
    }
}

pub struct CellOutputSer {
    pub capacity: u64,
    pub lock: ScriptSer,
    pub type_: Option<ScriptSer>,
}

impl CellOutputSer {
    pub fn serialize(&self, buf: &mut Vec<u8>) {
        let lock_size = self.lock.serialized_size();
        let _ = match &self.type_ {
            Some(t) => t.serialized_size(),
            None => 0,
        };
        let item_count = 3u32;
        let offset_0 = 4 + item_count * 4; // 16
        let offset_1 = offset_0 + 8; // 24
        let offset_2 = offset_1 + lock_size as u32;
        write_u32(buf, item_count);
        write_u32(buf, offset_0);
        write_u32(buf, offset_1);
        write_u32(buf, offset_2);
        write_u64(buf, self.capacity);
        self.lock.serialize(buf);
        write_option_script(buf, self.type_.as_ref());
    }

    pub fn serialized_size(&self) -> usize {
        let lock_size = self.lock.serialized_size();
        let type_size = match &self.type_ {
            Some(t) => t.serialized_size(),
            None => 0,
        };
        16 + 8 + lock_size + type_size
    }
}

pub struct OutPointSer {
    pub tx_hash: [u8; 32],
    pub index: u32,
}

impl OutPointSer {
    pub fn serialize(&self, buf: &mut Vec<u8>) {
        write_byte32(buf, &self.tx_hash);
        write_u32(buf, self.index);
    }
}

pub struct CellDepSer {
    pub out_point: OutPointSer,
    pub dep_type: u8,
}

impl CellDepSer {
    pub fn serialize(&self, buf: &mut Vec<u8>) {
        self.out_point.serialize(buf);
        buf.push(self.dep_type);
    }
}

pub struct CellInputSer {
    pub since: u64,
    pub previous_output: OutPointSer,
}

impl CellInputSer {
    pub fn serialize(&self, buf: &mut Vec<u8>) {
        write_u64(buf, self.since);
        self.previous_output.serialize(buf);
    }
}

pub struct RawTransactionSer {
    pub version: u32,
    pub cell_deps: Vec<CellDepSer>,
    pub header_deps: Vec<[u8; 32]>,
    pub inputs: Vec<CellInputSer>,
    pub outputs: Vec<CellOutputSer>,
    pub outputs_data: Vec<Vec<u8>>,
}

impl RawTransactionSer {
    pub fn serialize(&self, buf: &mut Vec<u8>) {
        let item_count = 6u32;
        let header_size = 4 + item_count * 4; // 28
        let cell_deps_size = 4 + self.cell_deps.len() * 37;
        let header_deps_size = 4 + self.header_deps.len() * 32;
        let inputs_size = 4 + self.inputs.len() * 44;
        let outputs_size: usize = 4 + self.outputs.iter().map(|o| o.serialized_size()).sum::<usize>();
        let _outputs_data_size: usize = 4 + self.outputs_data.iter().map(|d| 4 + d.len()).sum::<usize>();

        let offset_0 = header_size as u32;
        let offset_1 = offset_0 + 4;
        let offset_2 = offset_1 + cell_deps_size as u32;
        let offset_3 = offset_2 + header_deps_size as u32;
        let offset_4 = offset_3 + inputs_size as u32;
        let offset_5 = offset_4 + outputs_size as u32;

        write_u32(buf, item_count);
        write_u32(buf, offset_0);
        write_u32(buf, offset_1);
        write_u32(buf, offset_2);
        write_u32(buf, offset_3);
        write_u32(buf, offset_4);
        write_u32(buf, offset_5);

        write_u32(buf, self.version);
        write_u32(buf, self.cell_deps.len() as u32);
        for dep in &self.cell_deps { dep.serialize(buf); }
        write_u32(buf, self.header_deps.len() as u32);
        for hd in &self.header_deps { write_byte32(buf, hd); }
        write_u32(buf, self.inputs.len() as u32);
        for input in &self.inputs { input.serialize(buf); }
        write_u32(buf, self.outputs.len() as u32);
        for output in &self.outputs { output.serialize(buf); }
        write_u32(buf, self.outputs_data.len() as u32);
        for data in &self.outputs_data { write_bytes(buf, data); }
    }

    pub fn serialized_size(&self) -> usize {
        let cell_deps_size = 4 + self.cell_deps.len() * 37;
        let header_deps_size = 4 + self.header_deps.len() * 32;
        let inputs_size = 4 + self.inputs.len() * 44;
        let outputs_size: usize = 4 + self.outputs.iter().map(|o| o.serialized_size()).sum::<usize>();
        let outputs_data_size: usize = 4 + self.outputs_data.iter().map(|d| 4 + d.len()).sum::<usize>();
        28 + 4 + cell_deps_size + header_deps_size + inputs_size + outputs_size + outputs_data_size
    }
}

pub struct TransactionSer {
    pub raw: RawTransactionSer,
    pub witnesses: Vec<Vec<u8>>,
}

impl TransactionSer {
    pub fn serialize(&self, buf: &mut Vec<u8>) {
        // CKB Transaction is a table with 2 fields: raw (RawTransaction) and witnesses (BytesVec)
        let item_count = 2u32;
        let header_size = 4 + item_count * 4; // 12
        let raw_size = self.raw.serialized_size();

        let offset_0 = header_size as u32;
        let offset_1 = offset_0 + raw_size as u32;

        write_u32(buf, item_count);
        write_u32(buf, offset_0);
        write_u32(buf, offset_1);

        self.raw.serialize(buf);

        write_u32(buf, self.witnesses.len() as u32);
        for witness in &self.witnesses {
            write_bytes(buf, witness);
        }
    }

    pub fn serialized_size(&self) -> usize {
        let raw_size = self.raw.serialized_size();
        let witnesses_size: usize = 4 + self.witnesses.iter().map(|w| 4 + w.len()).sum::<usize>();
        12 + raw_size + witnesses_size
    }
}

// Testnet / mainnet cell dep constants for SECP256K1_BLAKE160_SIGHASH_ALL dep_group
pub const TESTNET_SECP256K1_DEP_TX_HASH: &str = "f8de3bb47d055c46ebd0ddbd51c390d5818c9133f385013cde9c99d02f640995";
pub const MAINNET_SECP256K1_DEP_TX_HASH: &str = "71a7ba8fc96349fea0ed3a5c47992e3b4084b031a42264a018e0072e8172e46c";
pub const SECP256K1_DEP_INDEX: u32 = 0;
pub const DEP_TYPE_DEP_GROUP: u8 = 1;

// Minimum cell capacity for secp256k1 lock with 20-byte args (no type, empty data)
pub const MIN_CELL_CAPACITY: u64 = 81_0000_0000; // 81 CKB in shannons

fn strip_0x(s: &str) -> &str {
    s.strip_prefix("0x").unwrap_or(s)
}

pub fn hex_to_byte32(hex: &str) -> Result<[u8; 32], String> {
    let clean = strip_0x(hex);
    let bytes = hex::decode(clean).map_err(|e| format!("Invalid hex: {}", e))?;
    if bytes.len() != 32 {
        return Err(format!("Expected 32 bytes, got {}", bytes.len()));
    }
    let mut arr = [0u8; 32];
    arr.copy_from_slice(&bytes);
    Ok(arr)
}

pub fn hex_to_bytes(hex: &str) -> Result<Vec<u8>, String> {
    let clean = strip_0x(hex);
    hex::decode(clean).map_err(|e| format!("Invalid hex: {}", e))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_script_size() {
        let script = ScriptSer {
            code_hash: [0u8; 32],
            hash_type: 1,
            args: vec![0u8; 20],
        };
        let mut buf = Vec::new();
        script.serialize(&mut buf);
        assert_eq!(buf.len(), script.serialized_size());
        assert_eq!(script.serialized_size(), 16 + 32 + 1 + 4 + 20);
    }

    #[test]
    fn test_raw_transaction_size() {
        let raw = RawTransactionSer {
            version: 0,
            cell_deps: vec![CellDepSer {
                out_point: OutPointSer { tx_hash: [0u8; 32], index: 0 },
                dep_type: 1,
            }],
            header_deps: vec![],
            inputs: vec![CellInputSer {
                since: 0,
                previous_output: OutPointSer { tx_hash: [0u8; 32], index: 0 },
            }],
            outputs: vec![CellOutputSer {
                capacity: 100_0000_0000,
                lock: ScriptSer {
                    code_hash: [0u8; 32],
                    hash_type: 1,
                    args: vec![0u8; 20],
                },
                type_: None,
            }],
            outputs_data: vec![vec![]],
        };
        let mut buf = Vec::new();
        raw.serialize(&mut buf);
        assert_eq!(buf.len(), raw.serialized_size());
    }

    #[test]
    fn test_full_transaction_size() {
        let raw = RawTransactionSer {
            version: 0,
            cell_deps: vec![],
            header_deps: vec![],
            inputs: vec![],
            outputs: vec![],
            outputs_data: vec![],
        };
        let tx = TransactionSer {
            raw,
            witnesses: vec![vec![0u8; 65]],
        };
        let mut buf = Vec::new();
        tx.serialize(&mut buf);
        assert_eq!(buf.len(), tx.serialized_size());
    }
}
