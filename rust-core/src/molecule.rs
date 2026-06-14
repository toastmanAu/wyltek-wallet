// CKB Molecule serialization. Spec: https://github.com/nervosnetwork/molecule
//
// Encoding rules (the parts CKB transactions use):
//   • struct  — fixed-size fields, raw concatenation, NO header.
//   • fixvec  — vector of fixed-size items: item_count(u32 LE) ++ items.
//   • dynvec  — vector of dynamic items: full_size(u32) ++ offset_i(u32 each) ++ items.
//   • table   — full_size(u32) ++ offset_i(u32 each) ++ fields (in declared order).
//   • Bytes   — fixvec of byte: len(u32 LE) ++ raw bytes.
//   • option  — empty for None, the item's own bytes for Some.
//
// A previous hand-rolled version wrote item_count where full_size belongs and
// encoded dynvecs as fixvecs, producing a non-canonical tx_hash that no node
// agreed with. These helpers implement the spec; validated against a real
// on-chain tx_hash (see examples/pq_testnet.rs `checkhash`).

fn write_u32(buf: &mut Vec<u8>, val: u32) {
    buf.extend_from_slice(&val.to_le_bytes());
}

/// Molecule `Bytes` (fixvec of byte): len prefix + raw bytes.
fn write_bytes(buf: &mut Vec<u8>, data: &[u8]) {
    write_u32(buf, data.len() as u32);
    buf.extend_from_slice(data);
}

/// Encode a molecule `table` from its already-serialized fields, in order.
fn serialize_table(fields: &[Vec<u8>]) -> Vec<u8> {
    let n = fields.len();
    let header = 4 + n * 4; // full_size + n offsets
    let full_size = header + fields.iter().map(Vec::len).sum::<usize>();

    let mut buf = Vec::with_capacity(full_size);
    write_u32(&mut buf, full_size as u32);
    let mut offset = header;
    for f in fields {
        write_u32(&mut buf, offset as u32);
        offset += f.len();
    }
    for f in fields {
        buf.extend_from_slice(f);
    }
    buf
}

/// Encode a molecule `dynvec` from its already-serialized items.
fn serialize_dynvec(items: &[Vec<u8>]) -> Vec<u8> {
    let n = items.len();
    let header = 4 + n * 4; // full_size + n offsets
    let full_size = header + items.iter().map(Vec::len).sum::<usize>();

    let mut buf = Vec::with_capacity(full_size);
    write_u32(&mut buf, full_size as u32);
    let mut offset = header;
    for it in items {
        write_u32(&mut buf, offset as u32);
        offset += it.len();
    }
    for it in items {
        buf.extend_from_slice(it);
    }
    buf
}

/// Encode a molecule `fixvec` from fixed-size items (already serialized).
fn serialize_fixvec(items: &[Vec<u8>]) -> Vec<u8> {
    let mut buf = Vec::new();
    write_u32(&mut buf, items.len() as u32);
    for it in items {
        buf.extend_from_slice(it);
    }
    buf
}

#[derive(Clone)]
pub struct ScriptSer {
    pub code_hash: [u8; 32],
    pub hash_type: u8,
    pub args: Vec<u8>,
}

impl ScriptSer {
    /// `Script` table: code_hash (Byte32), hash_type (byte), args (Bytes).
    pub fn to_bytes(&self) -> Vec<u8> {
        let mut args_field = Vec::new();
        write_bytes(&mut args_field, &self.args);
        serialize_table(&[
            self.code_hash.to_vec(),
            vec![self.hash_type],
            args_field,
        ])
    }

    pub fn serialize(&self, buf: &mut Vec<u8>) {
        buf.extend_from_slice(&self.to_bytes());
    }

    pub fn serialized_size(&self) -> usize {
        self.to_bytes().len()
    }
}

pub struct CellOutputSer {
    pub capacity: u64,
    pub lock: ScriptSer,
    pub type_: Option<ScriptSer>,
}

impl CellOutputSer {
    /// `CellOutput` table: capacity (Uint64), lock (Script), type (ScriptOpt).
    pub fn to_bytes(&self) -> Vec<u8> {
        let type_field = match &self.type_ {
            Some(t) => t.to_bytes(),
            None => Vec::new(), // option None = empty
        };
        serialize_table(&[
            self.capacity.to_le_bytes().to_vec(),
            self.lock.to_bytes(),
            type_field,
        ])
    }

    pub fn serialize(&self, buf: &mut Vec<u8>) {
        buf.extend_from_slice(&self.to_bytes());
    }

    pub fn serialized_size(&self) -> usize {
        self.to_bytes().len()
    }
}

pub struct OutPointSer {
    pub tx_hash: [u8; 32],
    pub index: u32,
}

impl OutPointSer {
    /// `OutPoint` struct: tx_hash (Byte32) ++ index (Uint32). Fixed 36 bytes.
    pub fn to_bytes(&self) -> Vec<u8> {
        let mut buf = Vec::with_capacity(36);
        buf.extend_from_slice(&self.tx_hash);
        write_u32(&mut buf, self.index);
        buf
    }
}

pub struct CellDepSer {
    pub out_point: OutPointSer,
    pub dep_type: u8,
}

impl CellDepSer {
    /// `CellDep` struct: out_point (OutPoint) ++ dep_type (byte). Fixed 37 bytes.
    pub fn to_bytes(&self) -> Vec<u8> {
        let mut buf = self.out_point.to_bytes();
        buf.push(self.dep_type);
        buf
    }
}

pub struct CellInputSer {
    pub since: u64,
    pub previous_output: OutPointSer,
}

impl CellInputSer {
    /// `CellInput` struct: since (Uint64) ++ previous_output (OutPoint). Fixed 44 bytes.
    pub fn to_bytes(&self) -> Vec<u8> {
        let mut buf = Vec::with_capacity(44);
        buf.extend_from_slice(&self.since.to_le_bytes());
        buf.extend_from_slice(&self.previous_output.to_bytes());
        buf
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
    /// `RawTransaction` table: version, cell_deps (CellDepVec fixvec),
    /// header_deps (Byte32Vec fixvec), inputs (CellInputVec fixvec),
    /// outputs (CellOutputVec dynvec), outputs_data (BytesVec dynvec).
    pub fn to_bytes(&self) -> Vec<u8> {
        let cell_deps = serialize_fixvec(
            &self.cell_deps.iter().map(CellDepSer::to_bytes).collect::<Vec<_>>(),
        );
        let header_deps = serialize_fixvec(
            &self.header_deps.iter().map(|h| h.to_vec()).collect::<Vec<_>>(),
        );
        let inputs = serialize_fixvec(
            &self.inputs.iter().map(CellInputSer::to_bytes).collect::<Vec<_>>(),
        );
        let outputs = serialize_dynvec(
            &self.outputs.iter().map(CellOutputSer::to_bytes).collect::<Vec<_>>(),
        );
        let outputs_data = serialize_dynvec(
            &self
                .outputs_data
                .iter()
                .map(|d| {
                    let mut b = Vec::new();
                    write_bytes(&mut b, d);
                    b
                })
                .collect::<Vec<_>>(),
        );
        serialize_table(&[
            self.version.to_le_bytes().to_vec(),
            cell_deps,
            header_deps,
            inputs,
            outputs,
            outputs_data,
        ])
    }

    pub fn serialize(&self, buf: &mut Vec<u8>) {
        buf.extend_from_slice(&self.to_bytes());
    }

    pub fn serialized_size(&self) -> usize {
        self.to_bytes().len()
    }
}

pub struct TransactionSer {
    pub raw: RawTransactionSer,
    pub witnesses: Vec<Vec<u8>>,
}

impl TransactionSer {
    /// `Transaction` table: raw (RawTransaction) ++ witnesses (BytesVec dynvec).
    pub fn to_bytes(&self) -> Vec<u8> {
        let witnesses = serialize_dynvec(
            &self
                .witnesses
                .iter()
                .map(|w| {
                    let mut b = Vec::new();
                    write_bytes(&mut b, w);
                    b
                })
                .collect::<Vec<_>>(),
        );
        serialize_table(&[self.raw.to_bytes(), witnesses])
    }

    pub fn serialize(&self, buf: &mut Vec<u8>) {
        buf.extend_from_slice(&self.to_bytes());
    }

    pub fn serialized_size(&self) -> usize {
        self.to_bytes().len()
    }
}

// Testnet / mainnet cell dep constants for SECP256K1_BLAKE160_SIGHASH_ALL dep_group
// Testnet genesis secp256k1 dep group (Lumos AGGRON4). Prior value was wrong.
pub const TESTNET_SECP256K1_DEP_TX_HASH: &str = "f8de3bb47d055cdf460d93a2a6e1b05f7432f9777c8c474abf4eec1d4aee5d37";
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
