//! Live testnet harness for the ML-DSA-65 (ckb-mldsa-lock) send path.
//!
//! Exercises the EXACT Rust the Android app uses — `build_transaction` and
//! `signing::sign_ckb_mldsa65` — then broadcasts the same JSON-RPC envelope
//! `WalletRepository.sendCkb` assembles. This is the highest-fidelity check of
//! the PQ signing path short of running the app itself: if the raw-tx hash the
//! Rust builder signs diverges from the hash CKB computes for the submitted
//! envelope, the on-chain ML-DSA verification fails and we find out here.
//!
//! Usage:
//!   cargo run --example pq_testnet -- derive [<seed_hex>]
//!   cargo run --example pq_testnet -- balance <seed_hex>
//!   cargo run --example pq_testnet -- spend-pq <seed_hex> <to_address> <amount_ckb>
//!
//! `seed_hex` is the 32-byte wallet seed the app stores (the same value fed to
//! `mldsa65_from_seed` / `generate_secp256k1_keypair`). `derive` with no seed
//! mints a fresh random one and prints it.

use serde_json::{json, Value};
use wyltekwalletcore::pq_keys::{mldsa65_from_seed, mldsa65_lock_args_v2};
use wyltekwalletcore::signing::{sign_ckb_mldsa65, MldsaInputCell};
use wyltekwalletcore::transaction::{
    build_transaction, TransactionRequest, TxCellDep, TxInput, TxOutput,
};
use wyltekwalletcore::molecule::{
    CellDepSer, CellInputSer, CellOutputSer, OutPointSer, RawTransactionSer, ScriptSer,
};
use wyltekwalletcore::{
    ckb_hash, decode_address, generate_secp256k1_keypair, public_key_to_ckb_address,
};

// ── Testnet deployment constants (mirror NetworkConfig.testnet) ──────────────
const RPC_URL: &str = "https://testnet.ckbapp.dev";

// mldsa65-lock-v2-rust (the live, supported contract). The legacy C lock at
// 0x8984f4…d310d is deprecated (sighash gap, lost owner) — do not use.
const MLDSA_CODE_HASH: &str =
    "0xd70653f7fd51e173ec506b76081f37bf4acebb8a15dc79e6d4ad43ca4d3b78a4";
const MLDSA_HASH_TYPE: &str = "type";
// Session-10 deploy tx; mldsa65-lock-v2-rust binary sits at output index 3.
const MLDSA_DEP_TX_HASH: &str =
    "0x1074b1ac79213c22b5e32a0fde44a858a47f9575c9f54006a1deb80d32070cb1";
const MLDSA_DEP_INDEX: u32 = 3;
// Deployed as a plain code cell (not a dep_group).
const DEP_TYPE_CODE: u8 = 0;

// Cell-selection knobs lifted verbatim from WalletRepository.sendCkb.
const MIN_CELL_CAPACITY: u64 = 81_0000_0000; // 81 CKB
const PQ_FEE_ESTIMATE: u64 = 10_000; // shannons reserved for the ~5.4 KB PQ witness
const SECP_DERIVATION_PATH: &str = "m/44'/302'/0'/0/0";

fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let cmd = args.first().map(String::as_str).unwrap_or("");

    let result = match cmd {
        "derive" => cmd_derive(args.get(1).cloned()),
        "balance" => cmd_balance(arg(&args, 1, "seed_hex")),
        "spend-pq" => cmd_spend_pq(
            arg(&args, 1, "seed_hex"),
            arg(&args, 2, "to_address"),
            arg(&args, 3, "amount_ckb"),
        ),
        "checkhash" => cmd_checkhash(arg(&args, 1, "tx_hash")),
        other => Err(format!(
            "unknown command {:?}\n  derive [<seed_hex>]\n  balance <seed_hex>\n  spend-pq <seed_hex> <to_address> <amount_ckb>\n  checkhash <tx_hash>",
            other
        )),
    };

    if let Err(e) = result {
        eprintln!("error: {e}");
        std::process::exit(1);
    }
}

fn arg(args: &[String], i: usize, name: &str) -> String {
    args.get(i)
        .cloned()
        .unwrap_or_else(|| fatal(&format!("missing argument <{name}>")))
}

fn fatal(msg: &str) -> ! {
    eprintln!("error: {msg}");
    std::process::exit(1);
}

// ── Lock-script derivation ───────────────────────────────────────────────────

/// The ML-DSA-65 lock script (code_hash/hash_type/args) for a given seed.
fn pq_lock_for_seed(seed_hex: &str) -> Result<(String, String), String> {
    let pq = mldsa65_from_seed(seed_hex.to_string())
        .map_err(|e| format!("mldsa65_from_seed: {e}"))?;
    let args = format!(
        "0x{}",
        mldsa65_lock_args_v2(pq.public_key_hex.clone())
            .map_err(|e| format!("mldsa65_lock_args_v2: {e}"))?
    );
    Ok((args, pq.public_key_hex))
}

/// Encode a full-payload (CKB2021) bech32m address for an arbitrary lock.
/// data = [0x00 | code_hash(32) | hash_type_byte | args].
fn encode_full_address(code_hash_hex: &str, hash_type: &str, args_hex: &str) -> Result<String, String> {
    let code_hash = hex::decode(code_hash_hex.trim_start_matches("0x"))
        .map_err(|e| format!("code_hash hex: {e}"))?;
    let args = hex::decode(args_hex.trim_start_matches("0x")).map_err(|e| format!("args hex: {e}"))?;
    let ht_byte: u8 = match hash_type {
        "data" => 0x00,
        "type" => 0x01,
        "data1" => 0x02,
        other => return Err(format!("unknown hash_type {other}")),
    };
    let mut data = Vec::with_capacity(1 + 32 + 1 + args.len());
    data.push(0x00); // full-payload format
    data.extend_from_slice(&code_hash);
    data.push(ht_byte);
    data.extend_from_slice(&args);
    let hrp = bech32::Hrp::parse("ckt").map_err(|e| format!("hrp: {e}"))?;
    bech32::encode_lower::<bech32::Bech32m>(hrp, &data).map_err(|e| format!("bech32m: {e}"))
}

// ── Commands ─────────────────────────────────────────────────────────────────

fn cmd_derive(seed_arg: Option<String>) -> Result<(), String> {
    let seed_hex = match seed_arg {
        Some(s) => s.trim_start_matches("0x").to_string(),
        None => {
            use rand::RngCore;
            let mut seed = [0u8; 32];
            rand::rngs::OsRng.fill_bytes(&mut seed);
            hex::encode(seed)
        }
    };
    if hex::decode(&seed_hex).map(|b| b.len()).unwrap_or(0) != 32 {
        return Err("seed must be 32 bytes (64 hex chars)".into());
    }

    let classic_kp = generate_secp256k1_keypair(seed_hex.clone(), SECP_DERIVATION_PATH.to_string())
        .map_err(|e| format!("secp keygen: {e}"))?;
    let classic_addr = public_key_to_ckb_address(classic_kp.public_key_hex, "testnet".to_string())
        .map_err(|e| format!("classic address: {e}"))?;

    let (pq_args, _pk) = pq_lock_for_seed(&seed_hex)?;
    let pq_addr = encode_full_address(MLDSA_CODE_HASH, MLDSA_HASH_TYPE, &pq_args)?;

    println!("seed_hex:        {seed_hex}");
    println!("classic address: {classic_addr}");
    println!("PQ lock args:    {pq_args}");
    println!("PQ address:      {pq_addr}");
    println!();
    println!("Fund the PQ address from https://faucet.nervos.org, then:");
    println!("  cargo run --example pq_testnet -- balance {seed_hex}");
    Ok(())
}

fn cmd_balance(seed_hex: String) -> Result<(), String> {
    let seed_hex = seed_hex.trim_start_matches("0x").to_string();
    let (pq_args, _) = pq_lock_for_seed(&seed_hex)?;
    let cells = get_cells(MLDSA_CODE_HASH, MLDSA_HASH_TYPE, &pq_args)?;
    let total: u64 = cells.iter().map(|c| c.capacity).sum();
    println!("PQ lock args: {pq_args}");
    println!("live cells:   {}", cells.len());
    println!("balance:      {} CKB ({} shannons)", total / 1_0000_0000, total);
    for c in &cells {
        println!("  - {}:{} = {} CKB", c.tx_hash, c.index, c.capacity / 1_0000_0000);
    }
    Ok(())
}

fn cmd_spend_pq(seed_hex: String, to_address: String, amount_ckb: String) -> Result<(), String> {
    let seed_hex = seed_hex.trim_start_matches("0x").to_string();
    let amount: u64 = amount_ckb
        .parse::<u64>()
        .map_err(|_| "amount_ckb must be a whole number of CKB".to_string())?
        .checked_mul(1_0000_0000)
        .ok_or("amount overflow")?;

    let pq = mldsa65_from_seed(seed_hex.clone()).map_err(|e| format!("mldsa65_from_seed: {e}"))?;
    let (pq_args, _) = pq_lock_for_seed(&seed_hex)?;
    let to = decode_address(to_address.clone()).map_err(|e| format!("decode to_address: {e}"))?;

    // 1. Gather live cells at the PQ lock (pure-CKB only).
    let cells = get_cells(MLDSA_CODE_HASH, MLDSA_HASH_TYPE, &pq_args)?;
    if cells.is_empty() {
        return Err("no spendable cells at the PQ lock — fund it first".into());
    }

    // 2. Cell selection — mirrors WalletRepository.sendCkb exactly.
    let mut sorted = cells.clone();
    sorted.sort_by_key(|c| c.capacity);
    let mut selected: Vec<&Cell> = Vec::new();
    let mut selected_cap: u64 = 0;
    for c in &sorted {
        selected.push(c);
        selected_cap += c.capacity;
        if selected_cap >= amount + PQ_FEE_ESTIMATE + MIN_CELL_CAPACITY {
            break;
        }
    }
    if selected_cap < amount + PQ_FEE_ESTIMATE {
        return Err(format!(
            "insufficient balance: have {} shannons, need {}",
            selected_cap,
            amount + PQ_FEE_ESTIMATE
        ));
    }
    let needs_change = selected_cap >= amount + PQ_FEE_ESTIMATE + MIN_CELL_CAPACITY;

    // 3. Outputs: recipient, then optional PQ change.
    let mut outputs = vec![TxOutput {
        capacity: amount,
        lock_code_hash: to.lock_code_hash.clone(),
        lock_hash_type: to.lock_hash_type.clone(),
        lock_args: to.lock_args.clone(),
        type_code_hash: String::new(),
        type_hash_type: String::new(),
        type_args: String::new(),
        data: String::new(),
    }];
    if needs_change {
        outputs.push(TxOutput {
            capacity: selected_cap - amount - PQ_FEE_ESTIMATE,
            lock_code_hash: MLDSA_CODE_HASH.to_string(),
            lock_hash_type: MLDSA_HASH_TYPE.to_string(),
            lock_args: pq_args.clone(),
            type_code_hash: String::new(),
            type_hash_type: String::new(),
            type_args: String::new(),
            data: String::new(),
        });
    }

    // 4. Build via the app's real builder → canonical raw-tx hash.
    let request = TransactionRequest {
        inputs: selected
            .iter()
            .map(|c| TxInput {
                tx_hash: c.tx_hash.clone(),
                index: c.index,
                since: 0,
                capacity: c.capacity,
            })
            .collect(),
        outputs: outputs.clone(),
        cell_deps: vec![TxCellDep {
            tx_hash: MLDSA_DEP_TX_HASH.to_string(),
            index: MLDSA_DEP_INDEX,
            dep_type: DEP_TYPE_CODE,
        }],
        fee_rate: 1000,
    };
    let built = build_transaction(request).map_err(|e| format!("build_transaction: {e}"))?;
    println!("tx_hash (signed): 0x{}", built.tx_hash_hex);

    // 5. Sign with the app's real ML-DSA-65 witness builder. The v2-rust lock
    //    signs the CighashAll stream, so the signer needs every input cell.
    let input_cells: Vec<MldsaInputCell> = selected
        .iter()
        .map(|c| MldsaInputCell {
            capacity: c.capacity,
            lock_code_hash: MLDSA_CODE_HASH.to_string(),
            lock_hash_type: MLDSA_HASH_TYPE.to_string(),
            lock_args: pq_args.clone(),
            data: String::new(),
        })
        .collect();
    let witness0 = sign_ckb_mldsa65(
        built.tx_hash_hex.clone(),
        input_cells,
        pq.private_key_hex.clone(),
        pq.public_key_hex.clone(),
    )
    .map_err(|e| format!("sign_ckb_mldsa65: {e}"))?;

    // 6. Assemble the JSON-RPC envelope exactly as WalletRepository.sendCkb does.
    let tx_json = build_tx_json(&selected, &outputs, &witness0);
    println!("broadcasting {} input(s), {} output(s)…", selected.len(), outputs.len());

    match send_transaction(&tx_json) {
        Ok(hash) => {
            println!("\n✅ accepted by pool — tx_hash: {hash}");
            println!("   https://pudge.explorer.nervos.org/transaction/{hash}");
            Ok(())
        }
        Err(e) => Err(format!("send_transaction rejected: {e}")),
    }
}

// ── Molecule canonicality check against a real on-chain tx ───────────────────

fn h32(s: &str) -> Result<[u8; 32], String> {
    let b = hex::decode(s.trim_start_matches("0x")).map_err(|e| format!("hex: {e}"))?;
    b.try_into().map_err(|_| "expected 32 bytes".to_string())
}

fn hash_type_to_byte(ht: &str) -> u8 {
    match ht {
        "type" => 1,
        "data1" => 2,
        _ => 0,
    }
}

fn script_from_json(v: &Value) -> Result<ScriptSer, String> {
    Ok(ScriptSer {
        code_hash: h32(v.get("code_hash").and_then(Value::as_str).ok_or("no code_hash")?)?,
        hash_type: hash_type_to_byte(v.get("hash_type").and_then(Value::as_str).unwrap_or("data")),
        args: hex::decode(
            v.get("args").and_then(Value::as_str).unwrap_or("0x").trim_start_matches("0x"),
        )
        .map_err(|e| format!("args hex: {e}"))?,
    })
}

/// Fetch a confirmed tx, rebuild its RawTransaction with our molecule encoder,
/// and confirm the recomputed tx_hash matches — proving canonical serialization.
fn cmd_checkhash(tx_hash: String) -> Result<(), String> {
    let result = rpc("get_transaction", json!([tx_hash]))?;
    let tx = result.pointer("/transaction").ok_or("tx not found / not confirmed")?;

    let version = parse_hex_u32(tx.pointer("/version").and_then(Value::as_str).unwrap_or("0x0"))?;

    let cell_deps = tx
        .pointer("/cell_deps")
        .and_then(Value::as_array)
        .ok_or("no cell_deps")?
        .iter()
        .map(|d| {
            Ok(CellDepSer {
                out_point: OutPointSer {
                    tx_hash: h32(d.pointer("/out_point/tx_hash").and_then(Value::as_str).ok_or("dep tx_hash")?)?,
                    index: parse_hex_u32(d.pointer("/out_point/index").and_then(Value::as_str).unwrap_or("0x0"))?,
                },
                dep_type: if d.get("dep_type").and_then(Value::as_str) == Some("dep_group") { 1 } else { 0 },
            })
        })
        .collect::<Result<Vec<_>, String>>()?;

    let header_deps = tx
        .pointer("/header_deps")
        .and_then(Value::as_array)
        .unwrap_or(&vec![])
        .iter()
        .map(|h| h32(h.as_str().unwrap_or("")))
        .collect::<Result<Vec<_>, String>>()?;

    let inputs = tx
        .pointer("/inputs")
        .and_then(Value::as_array)
        .ok_or("no inputs")?
        .iter()
        .map(|i| {
            Ok(CellInputSer {
                since: parse_hex_u64(i.pointer("/since").and_then(Value::as_str).unwrap_or("0x0"))?,
                previous_output: OutPointSer {
                    tx_hash: h32(i.pointer("/previous_output/tx_hash").and_then(Value::as_str).ok_or("in tx_hash")?)?,
                    index: parse_hex_u32(i.pointer("/previous_output/index").and_then(Value::as_str).unwrap_or("0x0"))?,
                },
            })
        })
        .collect::<Result<Vec<_>, String>>()?;

    let outputs = tx
        .pointer("/outputs")
        .and_then(Value::as_array)
        .ok_or("no outputs")?
        .iter()
        .map(|o| {
            Ok(CellOutputSer {
                capacity: parse_hex_u64(o.get("capacity").and_then(Value::as_str).unwrap_or("0x0"))?,
                lock: script_from_json(o.get("lock").ok_or("no lock")?)?,
                type_: match o.get("type") {
                    Some(t) if !t.is_null() => Some(script_from_json(t)?),
                    _ => None,
                },
            })
        })
        .collect::<Result<Vec<_>, String>>()?;

    let outputs_data = tx
        .pointer("/outputs_data")
        .and_then(Value::as_array)
        .ok_or("no outputs_data")?
        .iter()
        .map(|d| hex::decode(d.as_str().unwrap_or("0x").trim_start_matches("0x")).map_err(|e| format!("data hex: {e}")))
        .collect::<Result<Vec<_>, String>>()?;

    let raw = RawTransactionSer { version, cell_deps, header_deps, inputs, outputs, outputs_data };
    let raw_hex = hex::encode(raw.to_bytes());
    let computed = format!("0x{}", ckb_hash(raw_hex).map_err(|e| format!("ckb_hash: {e}"))?);

    let want = if tx_hash.starts_with("0x") { tx_hash.clone() } else { format!("0x{tx_hash}") };
    println!("on-chain tx_hash: {want}");
    println!("recomputed:       {computed}");
    if computed.eq_ignore_ascii_case(&want) {
        println!("✅ molecule serialization is canonical");
        Ok(())
    } else {
        Err("hash mismatch — molecule serialization still non-canonical".into())
    }
}

// ── JSON-RPC envelope ────────────────────────────────────────────────────────

fn build_tx_json(selected: &[&Cell], outputs: &[TxOutput], witness0_hex: &str) -> Value {
    let inputs: Vec<Value> = selected
        .iter()
        .map(|c| {
            json!({
                "previous_output": { "tx_hash": c.tx_hash, "index": hex_u32(c.index) },
                "since": "0x0"
            })
        })
        .collect();

    let out_json: Vec<Value> = outputs
        .iter()
        .map(|o| {
            json!({
                "capacity": hex_u64(o.capacity),
                "lock": {
                    "code_hash": o.lock_code_hash,
                    "hash_type": o.lock_hash_type,
                    "args": o.lock_args
                }
            })
        })
        .collect();

    let outputs_data: Vec<Value> = outputs.iter().map(|_| json!("0x")).collect();

    let mut witnesses = vec![json!(format!("0x{witness0_hex}"))];
    for _ in 1..selected.len() {
        witnesses.push(json!("0x"));
    }

    json!({
        "version": "0x0",
        "cell_deps": [{
            "out_point": { "tx_hash": MLDSA_DEP_TX_HASH, "index": hex_u32(MLDSA_DEP_INDEX) },
            "dep_type": "code"
        }],
        "header_deps": [],
        "inputs": inputs,
        "outputs": out_json,
        "outputs_data": outputs_data,
        "witnesses": witnesses
    })
}

// ── CKB JSON-RPC client ──────────────────────────────────────────────────────

#[derive(Clone)]
struct Cell {
    tx_hash: String,
    index: u32,
    capacity: u64,
}

fn rpc(method: &str, params: Value) -> Result<Value, String> {
    let body = json!({ "id": 1, "jsonrpc": "2.0", "method": method, "params": params });
    let resp: Value = ureq::post(RPC_URL)
        .set("content-type", "application/json")
        .send_json(body)
        .map_err(|e| format!("http {method}: {e}"))?
        .into_json()
        .map_err(|e| format!("decode {method}: {e}"))?;
    if let Some(err) = resp.get("error").filter(|e| !e.is_null()) {
        return Err(format!("{err}"));
    }
    resp.get("result")
        .cloned()
        .ok_or_else(|| format!("{method}: no result field"))
}

/// Indexer `get_cells` for a lock script — returns pure-CKB cells only
/// (no type script, empty data) to avoid spending asset/sUDT cells.
fn get_cells(code_hash: &str, hash_type: &str, args: &str) -> Result<Vec<Cell>, String> {
    let search_key = json!({
        "script": { "code_hash": code_hash, "hash_type": hash_type, "args": args },
        "script_type": "lock",
        "filter": null,
        "with_data": false
    });
    let result = rpc("get_cells", json!([search_key, "asc", "0x3e8"]))?;
    let empty = vec![];
    let objects = result.get("objects").and_then(Value::as_array).unwrap_or(&empty);

    let mut cells = Vec::new();
    for o in objects {
        // Skip cells carrying a type script (sUDT / assets).
        if o.pointer("/output/type").map(|t| !t.is_null()).unwrap_or(false) {
            continue;
        }
        let cap_hex = o.pointer("/output/capacity").and_then(Value::as_str).unwrap_or("0x0");
        let tx_hash = o.pointer("/out_point/tx_hash").and_then(Value::as_str).unwrap_or("");
        let idx_hex = o.pointer("/out_point/index").and_then(Value::as_str).unwrap_or("0x0");
        cells.push(Cell {
            tx_hash: tx_hash.to_string(),
            index: parse_hex_u32(idx_hex)?,
            capacity: parse_hex_u64(cap_hex)?,
        });
    }
    Ok(cells)
}

fn send_transaction(tx: &Value) -> Result<String, String> {
    // "passthrough" outputs_validator skips the default scripts-only check.
    let result = rpc("send_transaction", json!([tx, "passthrough"]))?;
    result
        .as_str()
        .map(String::from)
        .ok_or_else(|| format!("unexpected result: {result}"))
}

// ── hex helpers ──────────────────────────────────────────────────────────────

fn hex_u32(v: u32) -> String {
    format!("0x{:x}", v)
}
fn hex_u64(v: u64) -> String {
    format!("0x{:x}", v)
}
fn parse_hex_u32(s: &str) -> Result<u32, String> {
    u32::from_str_radix(s.trim_start_matches("0x"), 16).map_err(|e| format!("u32 {s}: {e}"))
}
fn parse_hex_u64(s: &str) -> Result<u64, String> {
    u64::from_str_radix(s.trim_start_matches("0x"), 16).map_err(|e| format!("u64 {s}: {e}"))
}
