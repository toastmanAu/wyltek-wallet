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
use wyltekwalletcore::signing::{
    sign_ckb_mldsa65, sign_ckb_secp256k1_dao_witness, sign_ckb_secp256k1_witness, MldsaInputCell,
};
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

// Classic secp256k1_blake160_sighash_all dep group (testnet system cells).
const SECP_DEP_TX_HASH: &str =
    "0xf8de3bb47d055cdf460d93a2a6e1b05f7432f9777c8c474abf4eec1d4aee5d37";
const SECP_DEP_INDEX: u32 = 0;
const SECP_FEE_ESTIMATE: u64 = 1_000;

// Nervos DAO (testnet): type script + genesis dep (tx[0] @ index 2).
const DAO_TYPE_CODE_HASH: &str =
    "0x82d76d1b75fe2fd9a27dfbaa65a039221a380d76c926f378d3f81cf3e7e13f2e";
const DAO_DEP_TX_HASH: &str =
    "0x8f8c79eb6671709633fe6a46de93c0fedc9c1b8a6527a18d3983879542635c9f";
const DAO_DEP_INDEX: u32 = 2;
// A DAO cell occupies ~102 CKB (8 cap + 53 lock + 33 type + 8 data).
const DAO_MIN_CAPACITY: u64 = 102_0000_0000;

// Simple UDT (testnet): standard sUDT type script + its code cell dep.
// Mirrors NetworkConfig.testnet.{sudtTypeCodeHash, sudtCellDepTxHash}.
const SUDT_CODE_HASH: &str =
    "0xc5e5dcf215925f7ef4dfaf5f4b4f105bc321c02776d6e7d52a1db3fcd9d011a4";
const SUDT_DEP_TX_HASH: &str =
    "0xe12877ebd2c3c364dc46c5c992bcfaf4fee33fa13eebdf82c591fc9825aab769";
const SUDT_DEP_INDEX: u32 = 0;
// sUDT cell occupies ~142 bytes; WalletRepository reserves 200 CKB per cell.
const SUDT_CELL_CAPACITY: u64 = 200_0000_0000;
const SUDT_FEE_ESTIMATE: u64 = 2_000;

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
        "spend-secp" => cmd_spend_secp(
            arg(&args, 1, "seed_hex"),
            arg(&args, 2, "to_address"),
            arg(&args, 3, "amount_ckb"),
        ),
        "deposit-dao" => cmd_deposit_dao(arg(&args, 1, "seed_hex"), arg(&args, 2, "amount_ckb")),
        "withdraw-dao-phase1" => cmd_withdraw_dao_phase1(arg(&args, 1, "seed_hex"), arg(&args, 2, "deposit_tx_hash")),
        "claim-dao" => cmd_claim_dao(arg(&args, 1, "seed_hex"), arg(&args, 2, "withdraw_tx_hash"), args.get(3).cloned()),
        "sudt-balance" => cmd_sudt_balance(arg(&args, 1, "seed_hex")),
        "mint-sudt" => cmd_mint_sudt(arg(&args, 1, "seed_hex"), arg(&args, 2, "amount")),
        "send-sudt" => cmd_send_sudt(arg(&args, 1, "seed_hex"), arg(&args, 2, "to_address"), arg(&args, 3, "amount")),
        "checkhash" => cmd_checkhash(arg(&args, 1, "tx_hash")),
        other => Err(format!(
            "unknown command {:?}\n  derive [<seed_hex>]\n  balance <seed_hex>\n  spend-pq <seed_hex> <to_address> <amount_ckb>\n  spend-secp <seed_hex> <to_address> <amount_ckb>\n  deposit-dao <seed_hex> <amount_ckb>\n  withdraw-dao-phase1 <seed_hex> <deposit_tx_hash>\n  claim-dao <seed_hex> <withdraw_tx_hash> [probe]\n  sudt-balance <seed_hex>\n  mint-sudt <seed_hex> <amount>\n  send-sudt <seed_hex> <to_address> <amount>\n  checkhash <tx_hash>",
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
        header_deps: vec![],
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
    let tx_json = build_tx_json(&selected, &outputs, &witness0, &[(MLDSA_DEP_TX_HASH, MLDSA_DEP_INDEX, "code")], &[]);
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

/// Classic secp256k1 spend — verifies sign_ckb_secp256k1_witness on-chain.
fn cmd_spend_secp(seed_hex: String, to_address: String, amount_ckb: String) -> Result<(), String> {
    let seed_hex = seed_hex.trim_start_matches("0x").to_string();
    let amount: u64 = amount_ckb
        .parse::<u64>()
        .map_err(|_| "amount_ckb must be a whole number of CKB".to_string())?
        .checked_mul(1_0000_0000)
        .ok_or("amount overflow")?;

    let kp = generate_secp256k1_keypair(seed_hex.clone(), SECP_DERIVATION_PATH.to_string())
        .map_err(|e| format!("secp keygen: {e}"))?;
    let from_addr = public_key_to_ckb_address(kp.public_key_hex.clone(), "testnet".to_string())
        .map_err(|e| format!("classic address: {e}"))?;
    let from = decode_address(from_addr).map_err(|e| format!("decode from: {e}"))?;
    let to = decode_address(to_address).map_err(|e| format!("decode to: {e}"))?;

    let cells = get_cells(&from.lock_code_hash, &from.lock_hash_type, &from.lock_args)?;
    if cells.is_empty() {
        return Err("no spendable cells at the classic lock — fund it first".into());
    }

    let mut sorted = cells.clone();
    sorted.sort_by_key(|c| c.capacity);
    let mut selected: Vec<&Cell> = Vec::new();
    let mut selected_cap: u64 = 0;
    for c in &sorted {
        selected.push(c);
        selected_cap += c.capacity;
        if selected_cap >= amount + SECP_FEE_ESTIMATE + MIN_CELL_CAPACITY {
            break;
        }
    }
    if selected_cap < amount + SECP_FEE_ESTIMATE {
        return Err(format!("insufficient balance: have {selected_cap}, need {}", amount + SECP_FEE_ESTIMATE));
    }
    let needs_change = selected_cap >= amount + SECP_FEE_ESTIMATE + MIN_CELL_CAPACITY;

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
            capacity: selected_cap - amount - SECP_FEE_ESTIMATE,
            lock_code_hash: from.lock_code_hash.clone(),
            lock_hash_type: from.lock_hash_type.clone(),
            lock_args: from.lock_args.clone(),
            type_code_hash: String::new(),
            type_hash_type: String::new(),
            type_args: String::new(),
            data: String::new(),
        });
    }

    let request = TransactionRequest {
        inputs: selected
            .iter()
            .map(|c| TxInput { tx_hash: c.tx_hash.clone(), index: c.index, since: c.since, capacity: c.capacity })
            .collect(),
        outputs: outputs.clone(),
        cell_deps: vec![TxCellDep {
            tx_hash: SECP_DEP_TX_HASH.to_string(),
            index: SECP_DEP_INDEX,
            dep_type: 1, // dep_group
        }],
        header_deps: vec![],
        fee_rate: 1000,
    };
    let built = build_transaction(request).map_err(|e| format!("build_transaction: {e}"))?;
    println!("tx_hash (signed): 0x{}", built.tx_hash_hex);

    let witness0 = sign_ckb_secp256k1_witness(
        built.tx_hash_hex.clone(),
        selected.len() as u32,
        kp.private_key_hex.clone(),
    )
    .map_err(|e| format!("sign_ckb_secp256k1_witness: {e}"))?;

    let tx_json = build_tx_json(&selected, &outputs, &witness0, &[(SECP_DEP_TX_HASH, SECP_DEP_INDEX, "dep_group")], &[]);
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

/// Nervos DAO deposit — secp-signed tx that creates a DAO cell. Verifies the
/// secp signer on a type-script output + the corrected testnet DAO cell dep.
fn cmd_deposit_dao(seed_hex: String, amount_ckb: String) -> Result<(), String> {
    let seed_hex = seed_hex.trim_start_matches("0x").to_string();
    let amount: u64 = amount_ckb
        .parse::<u64>()
        .map_err(|_| "amount_ckb must be a whole number of CKB".to_string())?
        .checked_mul(1_0000_0000)
        .ok_or("amount overflow")?;
    if amount < DAO_MIN_CAPACITY {
        return Err(format!("DAO deposit must be ≥ 102 CKB, got {}", amount / 1_0000_0000));
    }

    let kp = generate_secp256k1_keypair(seed_hex.clone(), SECP_DERIVATION_PATH.to_string())
        .map_err(|e| format!("secp keygen: {e}"))?;
    let from_addr = public_key_to_ckb_address(kp.public_key_hex.clone(), "testnet".to_string())
        .map_err(|e| format!("classic address: {e}"))?;
    let from = decode_address(from_addr).map_err(|e| format!("decode from: {e}"))?;

    let cells = get_cells(&from.lock_code_hash, &from.lock_hash_type, &from.lock_args)?;
    if cells.is_empty() {
        return Err("no spendable cells at the classic lock".into());
    }
    let mut sorted = cells.clone();
    sorted.sort_by_key(|c| c.capacity);
    let mut selected: Vec<&Cell> = Vec::new();
    let mut selected_cap: u64 = 0;
    for c in &sorted {
        selected.push(c);
        selected_cap += c.capacity;
        if selected_cap >= amount + SECP_FEE_ESTIMATE + MIN_CELL_CAPACITY {
            break;
        }
    }
    if selected_cap < amount + SECP_FEE_ESTIMATE {
        return Err(format!("insufficient balance: have {selected_cap}, need {}", amount + SECP_FEE_ESTIMATE));
    }
    let needs_change = selected_cap >= amount + SECP_FEE_ESTIMATE + MIN_CELL_CAPACITY;

    // Output 0: the DAO cell — secp self-lock, DAO type, 8 zero bytes of data.
    let mut outputs = vec![TxOutput {
        capacity: amount,
        lock_code_hash: from.lock_code_hash.clone(),
        lock_hash_type: from.lock_hash_type.clone(),
        lock_args: from.lock_args.clone(),
        type_code_hash: DAO_TYPE_CODE_HASH.to_string(),
        type_hash_type: "type".to_string(),
        type_args: String::new(),
        data: "0000000000000000".to_string(), // 8-byte deposit number = 0
    }];
    if needs_change {
        outputs.push(TxOutput {
            capacity: selected_cap - amount - SECP_FEE_ESTIMATE,
            lock_code_hash: from.lock_code_hash.clone(),
            lock_hash_type: from.lock_hash_type.clone(),
            lock_args: from.lock_args.clone(),
            type_code_hash: String::new(),
            type_hash_type: String::new(),
            type_args: String::new(),
            data: String::new(),
        });
    }

    let request = TransactionRequest {
        inputs: selected
            .iter()
            .map(|c| TxInput { tx_hash: c.tx_hash.clone(), index: c.index, since: c.since, capacity: c.capacity })
            .collect(),
        outputs: outputs.clone(),
        cell_deps: vec![
            TxCellDep { tx_hash: SECP_DEP_TX_HASH.to_string(), index: SECP_DEP_INDEX, dep_type: 1 },
            TxCellDep { tx_hash: DAO_DEP_TX_HASH.to_string(), index: DAO_DEP_INDEX, dep_type: 0 },
        ],
        header_deps: vec![],
        fee_rate: 1000,
    };
    let built = build_transaction(request).map_err(|e| format!("build_transaction: {e}"))?;
    println!("tx_hash (signed): 0x{}", built.tx_hash_hex);

    let witness0 = sign_ckb_secp256k1_witness(built.tx_hash_hex.clone(), selected.len() as u32, kp.private_key_hex.clone())
        .map_err(|e| format!("sign_ckb_secp256k1_witness: {e}"))?;

    let tx_json = build_tx_json(
        &selected,
        &outputs,
        &witness0,
        &[(SECP_DEP_TX_HASH, SECP_DEP_INDEX, "dep_group"), (DAO_DEP_TX_HASH, DAO_DEP_INDEX, "code")],
        &[],
    );
    println!("broadcasting DAO deposit ({} CKB)…", amount / 1_0000_0000);
    match send_transaction(&tx_json) {
        Ok(hash) => {
            println!("\n✅ accepted by pool — tx_hash: {hash}");
            println!("   https://pudge.explorer.nervos.org/transaction/{hash}");
            Ok(())
        }
        Err(e) => Err(format!("send_transaction rejected: {e}")),
    }
}

/// Nervos DAO withdraw phase 1 (start withdraw) — consumes a deposit cell and
/// creates a withdrawing cell whose data = deposit block number (u64 LE). Adds
/// the deposit block header to header_deps (so build_transaction hashes it into
/// the tx_hash) and verifies on-chain. Exercises header_deps + a DAO transition.
fn cmd_withdraw_dao_phase1(seed_hex: String, deposit_tx_hash: String) -> Result<(), String> {
    let seed_hex = seed_hex.trim_start_matches("0x").to_string();
    let kp = generate_secp256k1_keypair(seed_hex.clone(), SECP_DERIVATION_PATH.to_string())
        .map_err(|e| format!("secp keygen: {e}"))?;
    let from_addr = public_key_to_ckb_address(kp.public_key_hex.clone(), "testnet".to_string())
        .map_err(|e| format!("classic address: {e}"))?;
    let from = decode_address(from_addr).map_err(|e| format!("decode from: {e}"))?;

    // Fetch the deposit tx: its commit block + the DAO cell at output 0.
    let result = rpc("get_transaction", json!([deposit_tx_hash]))?;
    let status = result.pointer("/tx_status/status").and_then(Value::as_str).unwrap_or("");
    if status != "committed" {
        return Err(format!("deposit tx not committed yet (status: {status})"));
    }
    let block_hash = result
        .pointer("/tx_status/block_hash")
        .and_then(Value::as_str)
        .ok_or("no block_hash")?
        .to_string();
    let block_number = parse_hex_u64(
        result.pointer("/tx_status/block_number").and_then(Value::as_str).ok_or("no block_number")?,
    )?;
    let deposit_cap = parse_hex_u64(
        result.pointer("/transaction/outputs/0/capacity").and_then(Value::as_str).ok_or("no deposit capacity")?,
    )?;

    // Fee cell(s) from the classic lock (pure-CKB; the DAO cell is filtered out).
    let fee_cells = get_cells(&from.lock_code_hash, &from.lock_hash_type, &from.lock_args)?;
    let fee_cell = fee_cells
        .iter()
        .filter(|c| c.capacity >= SECP_FEE_ESTIMATE + MIN_CELL_CAPACITY)
        .min_by_key(|c| c.capacity)
        .ok_or("no fee cell large enough at the classic lock")?
        .clone();

    // Inputs: deposit cell (index 0 — must positionally match the withdrawing
    // output) then the fee cell. Both share the classic secp lock (one group).
    let deposit_cell = Cell { tx_hash: deposit_tx_hash.clone(), index: 0, capacity: deposit_cap, since: 0 };
    let selected: Vec<&Cell> = vec![&deposit_cell, &fee_cell];

    // Withdrawing cell data = deposit block number, u64 little-endian (8 bytes).
    let data_hex = hex::encode(block_number.to_le_bytes());

    let outputs = vec![
        TxOutput {
            capacity: deposit_cap, // phase 1 preserves the deposit capacity exactly
            lock_code_hash: from.lock_code_hash.clone(),
            lock_hash_type: from.lock_hash_type.clone(),
            lock_args: from.lock_args.clone(),
            type_code_hash: DAO_TYPE_CODE_HASH.to_string(),
            type_hash_type: "type".to_string(),
            type_args: String::new(),
            data: data_hex,
        },
        TxOutput {
            capacity: fee_cell.capacity - SECP_FEE_ESTIMATE,
            lock_code_hash: from.lock_code_hash.clone(),
            lock_hash_type: from.lock_hash_type.clone(),
            lock_args: from.lock_args.clone(),
            type_code_hash: String::new(),
            type_hash_type: String::new(),
            type_args: String::new(),
            data: String::new(),
        },
    ];

    let request = TransactionRequest {
        inputs: selected
            .iter()
            .map(|c| TxInput { tx_hash: c.tx_hash.clone(), index: c.index, since: c.since, capacity: c.capacity })
            .collect(),
        outputs: outputs.clone(),
        cell_deps: vec![
            TxCellDep { tx_hash: SECP_DEP_TX_HASH.to_string(), index: SECP_DEP_INDEX, dep_type: 1 },
            TxCellDep { tx_hash: DAO_DEP_TX_HASH.to_string(), index: DAO_DEP_INDEX, dep_type: 0 },
        ],
        header_deps: vec![block_hash.clone()],
        fee_rate: 1000,
    };
    let built = build_transaction(request).map_err(|e| format!("build_transaction: {e}"))?;
    println!("tx_hash (signed): 0x{}", built.tx_hash_hex);

    let witness0 = sign_ckb_secp256k1_witness(built.tx_hash_hex.clone(), selected.len() as u32, kp.private_key_hex.clone())
        .map_err(|e| format!("sign_ckb_secp256k1_witness: {e}"))?;

    let tx_json = build_tx_json(
        &selected,
        &outputs,
        &witness0,
        &[(SECP_DEP_TX_HASH, SECP_DEP_INDEX, "dep_group"), (DAO_DEP_TX_HASH, DAO_DEP_INDEX, "code")],
        &[&block_hash],
    );
    println!("broadcasting DAO withdraw phase 1 (deposit block {block_number})…");
    match send_transaction(&tx_json) {
        Ok(hash) => {
            println!("\n✅ accepted by pool — tx_hash: {hash}");
            println!("   https://pudge.explorer.nervos.org/transaction/{hash}");
            Ok(())
        }
        Err(e) => Err(format!("send_transaction rejected: {e}")),
    }
}

/// NervosDAO phase-2 (unlock/claim). Consumes a withdrawing cell and releases
/// deposit + interest as plain CKB. Requires: both deposit & withdraw block
/// headers in header_deps; witnesses[0].input_type = deposit-header index;
/// the input `since` = the absolute-epoch unlock point (deposit_epoch + 180).
///
/// Pass "probe" as the 3rd arg to force since=0 — that makes the tx mature so
/// the node RUNS the scripts (instead of rejecting at the time-lock), exposing
/// any witness/header/capacity error vs. the expected DAO since-period failure.
fn cmd_claim_dao(seed_hex: String, withdraw_tx_hash: String, mode: Option<String>) -> Result<(), String> {
    let seed_hex = seed_hex.trim_start_matches("0x").to_string();
    let probe = mode.as_deref() == Some("probe");

    let kp = generate_secp256k1_keypair(seed_hex.clone(), SECP_DERIVATION_PATH.to_string())
        .map_err(|e| format!("secp keygen: {e}"))?;
    let from_addr = public_key_to_ckb_address(kp.public_key_hex.clone(), "testnet".to_string())
        .map_err(|e| format!("classic address: {e}"))?;
    let from = decode_address(from_addr).map_err(|e| format!("decode from: {e}"))?;

    // Fetch the phase-1 withdraw tx: its block, the withdrawing cell, the
    // original deposit out_point, and the deposit block number (cell data).
    let wtx = rpc("get_transaction", json!([withdraw_tx_hash]))?;
    if wtx.pointer("/tx_status/status").and_then(Value::as_str) != Some("committed") {
        return Err("withdraw tx not committed yet".into());
    }
    let withdraw_block_hash = wtx.pointer("/tx_status/block_hash").and_then(Value::as_str).ok_or("no withdraw block")?.to_string();
    let withdrawing_cap = parse_hex_u64(wtx.pointer("/transaction/outputs/0/capacity").and_then(Value::as_str).ok_or("no cap")?)?;
    let deposit_out_tx = wtx.pointer("/transaction/inputs/0/previous_output/tx_hash").and_then(Value::as_str).ok_or("no deposit outpoint")?.to_string();
    let deposit_out_idx = wtx.pointer("/transaction/inputs/0/previous_output/index").and_then(Value::as_str).unwrap_or("0x0").to_string();
    let data_hex = wtx.pointer("/transaction/outputs_data/0").and_then(Value::as_str).ok_or("no data")?.trim_start_matches("0x").to_string();
    let deposit_block_number = {
        let b = hex::decode(&data_hex).map_err(|e| format!("data hex: {e}"))?;
        let mut a = [0u8; 8];
        a.copy_from_slice(&b[..8]);
        u64::from_le_bytes(a)
    };

    // Deposit block header → hash + epoch.
    let dhdr = rpc("get_header_by_number", json!([format!("0x{:x}", deposit_block_number)]))?;
    let deposit_block_hash = dhdr.get("hash").and_then(Value::as_str).ok_or("no deposit hash")?.to_string();
    let deposit_epoch = parse_hex_u64(dhdr.get("epoch").and_then(Value::as_str).ok_or("no epoch")?)?;

    // Max withdraw (deposit + interest) from the node.
    let max_withdraw = parse_hex_u64(
        rpc("calculate_dao_maximum_withdraw", json!([
            { "tx_hash": deposit_out_tx, "index": deposit_out_idx },
            withdraw_block_hash
        ]))?
        .as_str()
        .ok_or("max_withdraw not a string")?,
    )?;

    // Unlock `since` = absolute epoch (deposit_epoch_number + 180), same fraction.
    let number = deposit_epoch & 0xff_ffff;
    let index = (deposit_epoch >> 24) & 0xffff;
    let length = (deposit_epoch >> 40) & 0xffff;
    let lock_until = (length << 40) | (index << 24) | (number + 180);
    let since = if probe { 0 } else { 0x2000_0000_0000_0000u64 | lock_until };

    println!("deposit block {deposit_block_number} epoch_number {number}; unlock epoch {}", number + 180);
    println!("withdrawing {} CKB → max withdraw {} shannons (interest {})",
        withdrawing_cap / 1_0000_0000, max_withdraw, max_withdraw - withdrawing_cap);
    if probe { println!("PROBE: since=0 (force scripts to run; expect a DAO since-period failure)"); }

    // Single input: the withdrawing cell, carrying the unlock `since`.
    let withdrawing = Cell { tx_hash: withdraw_tx_hash.clone(), index: 0, capacity: withdrawing_cap, since };
    let selected: Vec<&Cell> = vec![&withdrawing];

    let outputs = vec![TxOutput {
        capacity: max_withdraw - SECP_FEE_ESTIMATE,
        lock_code_hash: from.lock_code_hash.clone(),
        lock_hash_type: from.lock_hash_type.clone(),
        lock_args: from.lock_args.clone(),
        type_code_hash: String::new(),
        type_hash_type: String::new(),
        type_args: String::new(),
        data: String::new(),
    }];

    let request = TransactionRequest {
        inputs: vec![TxInput { tx_hash: withdraw_tx_hash.clone(), index: 0, since, capacity: withdrawing_cap }],
        outputs: outputs.clone(),
        cell_deps: vec![
            TxCellDep { tx_hash: SECP_DEP_TX_HASH.to_string(), index: SECP_DEP_INDEX, dep_type: 1 },
            TxCellDep { tx_hash: DAO_DEP_TX_HASH.to_string(), index: DAO_DEP_INDEX, dep_type: 0 },
        ],
        // Deposit header at index 0 (referenced by input_type), withdraw at 1.
        header_deps: vec![deposit_block_hash.clone(), withdraw_block_hash.clone()],
        fee_rate: 1000,
    };
    let built = build_transaction(request).map_err(|e| format!("build_transaction: {e}"))?;
    println!("tx_hash (signed): 0x{}", built.tx_hash_hex);

    // input_type = deposit header index (0) within header_deps.
    let witness0 = sign_ckb_secp256k1_dao_witness(built.tx_hash_hex.clone(), 1, 0, kp.private_key_hex.clone())
        .map_err(|e| format!("sign_ckb_secp256k1_dao_witness: {e}"))?;

    let tx_json = build_tx_json(
        &selected,
        &outputs,
        &witness0,
        &[(SECP_DEP_TX_HASH, SECP_DEP_INDEX, "dep_group"), (DAO_DEP_TX_HASH, DAO_DEP_INDEX, "code")],
        &[&deposit_block_hash, &withdraw_block_hash],
    );
    println!("broadcasting DAO claim…");
    match send_transaction(&tx_json) {
        Ok(hash) => {
            println!("\n✅ accepted by pool — tx_hash: {hash}");
            println!("   https://pudge.explorer.nervos.org/transaction/{hash}");
            Ok(())
        }
        Err(e) => Err(format!("send_transaction rejected: {e}")),
    }
}

// ── sUDT (simple UDT) mint + transfer — verifies WalletRepository.sendToken ──

fn cmd_sudt_balance(seed_hex: String) -> Result<(), String> {
    let seed_hex = seed_hex.trim_start_matches("0x").to_string();
    let kp = generate_secp256k1_keypair(seed_hex, SECP_DERIVATION_PATH.to_string())
        .map_err(|e| format!("secp keygen: {e}"))?;
    let from = decode_address(
        public_key_to_ckb_address(kp.public_key_hex, "testnet".to_string())
            .map_err(|e| format!("address: {e}"))?,
    )
    .map_err(|e| format!("decode: {e}"))?;
    let type_args = owner_lock_hash(&from.lock_code_hash, &from.lock_hash_type, &from.lock_args)?;
    let cells = get_sudt_cells(&from.lock_code_hash, &from.lock_hash_type, &from.lock_args, &type_args)?;
    let total: u128 = cells.iter().map(|c| c.amount).sum();
    println!("owner sUDT type args: {type_args}");
    println!("{} sUDT cell(s), total {total} tokens", cells.len());
    Ok(())
}

/// Mint sUDT to our own classic lock. Authorized because our owner lock (the
/// type args' preimage) is present as an input, so output amount may exceed 0.
fn cmd_mint_sudt(seed_hex: String, amount_str: String) -> Result<(), String> {
    let seed_hex = seed_hex.trim_start_matches("0x").to_string();
    let amount: u128 = amount_str.parse().map_err(|_| "amount must be a whole number of tokens".to_string())?;

    let kp = generate_secp256k1_keypair(seed_hex, SECP_DERIVATION_PATH.to_string())
        .map_err(|e| format!("secp keygen: {e}"))?;
    let from = decode_address(
        public_key_to_ckb_address(kp.public_key_hex.clone(), "testnet".to_string())
            .map_err(|e| format!("address: {e}"))?,
    )
    .map_err(|e| format!("decode: {e}"))?;
    let type_args = owner_lock_hash(&from.lock_code_hash, &from.lock_hash_type, &from.lock_args)?;

    // Fund the sUDT cell + fee from pure-CKB cells at the owner lock.
    let cells = get_cells(&from.lock_code_hash, &from.lock_hash_type, &from.lock_args)?;
    if cells.is_empty() {
        return Err("no spendable CKB cells — fund the classic lock first".into());
    }
    let mut sorted = cells.clone();
    sorted.sort_by_key(|c| c.capacity);
    let need = SUDT_CELL_CAPACITY + SUDT_FEE_ESTIMATE + MIN_CELL_CAPACITY;
    let mut selected: Vec<&Cell> = Vec::new();
    let mut cap: u64 = 0;
    for c in &sorted {
        selected.push(c);
        cap += c.capacity;
        if cap >= need {
            break;
        }
    }
    if cap < SUDT_CELL_CAPACITY + SUDT_FEE_ESTIMATE {
        return Err(format!("insufficient CKB: have {cap}, need ≥ {}", SUDT_CELL_CAPACITY + SUDT_FEE_ESTIMATE));
    }
    let needs_change = cap >= SUDT_CELL_CAPACITY + SUDT_FEE_ESTIMATE + MIN_CELL_CAPACITY;

    let mut outputs = vec![TxOutput {
        capacity: SUDT_CELL_CAPACITY,
        lock_code_hash: from.lock_code_hash.clone(),
        lock_hash_type: from.lock_hash_type.clone(),
        lock_args: from.lock_args.clone(),
        type_code_hash: SUDT_CODE_HASH.to_string(),
        type_hash_type: "type".to_string(),
        type_args: type_args.clone(),
        data: u128_le_hex(amount),
    }];
    if needs_change {
        outputs.push(TxOutput {
            capacity: cap - SUDT_CELL_CAPACITY - SUDT_FEE_ESTIMATE,
            lock_code_hash: from.lock_code_hash.clone(),
            lock_hash_type: from.lock_hash_type.clone(),
            lock_args: from.lock_args.clone(),
            type_code_hash: String::new(),
            type_hash_type: String::new(),
            type_args: String::new(),
            data: String::new(),
        });
    }

    let request = TransactionRequest {
        inputs: selected
            .iter()
            .map(|c| TxInput { tx_hash: c.tx_hash.clone(), index: c.index, since: c.since, capacity: c.capacity })
            .collect(),
        outputs: outputs.clone(),
        cell_deps: vec![
            TxCellDep { tx_hash: SECP_DEP_TX_HASH.to_string(), index: SECP_DEP_INDEX, dep_type: 1 },
            TxCellDep { tx_hash: SUDT_DEP_TX_HASH.to_string(), index: SUDT_DEP_INDEX, dep_type: 0 },
        ],
        header_deps: vec![],
        fee_rate: 1000,
    };
    let built = build_transaction(request).map_err(|e| format!("build_transaction: {e}"))?;
    println!("minting {amount} tokens; sUDT type args {type_args}");
    println!("tx_hash (signed): 0x{}", built.tx_hash_hex);

    let witness0 = sign_ckb_secp256k1_witness(built.tx_hash_hex.clone(), selected.len() as u32, kp.private_key_hex.clone())
        .map_err(|e| format!("sign_ckb_secp256k1_witness: {e}"))?;
    let tx_json = build_tx_json(
        &selected,
        &outputs,
        &witness0,
        &[(SECP_DEP_TX_HASH, SECP_DEP_INDEX, "dep_group"), (SUDT_DEP_TX_HASH, SUDT_DEP_INDEX, "code")],
        &[],
    );
    match send_transaction(&tx_json) {
        Ok(hash) => {
            println!("\n✅ minted — tx_hash: {hash}");
            println!("   https://pudge.explorer.nervos.org/transaction/{hash}");
            Ok(())
        }
        Err(e) => Err(format!("send_transaction rejected: {e}")),
    }
}

/// Transfer sUDT — faithful port of WalletRepository.sendToken (incl. the fee
/// reserved from CKB change). sUDT inputs first, then pure-CKB inputs for
/// capacity; outputs = recipient sUDT, [sUDT change], [CKB change].
fn cmd_send_sudt(seed_hex: String, to_address: String, amount_str: String) -> Result<(), String> {
    let seed_hex = seed_hex.trim_start_matches("0x").to_string();
    let amount: u128 = amount_str.parse().map_err(|_| "amount must be a whole number of tokens".to_string())?;

    let kp = generate_secp256k1_keypair(seed_hex, SECP_DERIVATION_PATH.to_string())
        .map_err(|e| format!("secp keygen: {e}"))?;
    let from = decode_address(
        public_key_to_ckb_address(kp.public_key_hex.clone(), "testnet".to_string())
            .map_err(|e| format!("address: {e}"))?,
    )
    .map_err(|e| format!("decode from: {e}"))?;
    let to = decode_address(to_address).map_err(|e| format!("decode to: {e}"))?;
    let type_args = owner_lock_hash(&from.lock_code_hash, &from.lock_hash_type, &from.lock_args)?;

    let sudt_cells = get_sudt_cells(&from.lock_code_hash, &from.lock_hash_type, &from.lock_args, &type_args)?;
    if sudt_cells.is_empty() {
        return Err("no sUDT cells at the classic lock — mint first".into());
    }
    let total: u128 = sudt_cells.iter().map(|c| c.amount).sum();
    if total < amount {
        return Err(format!("insufficient tokens: have {total}, need {amount}"));
    }

    let mut sudt_inputs: Vec<SudtCell> = Vec::new();
    let mut sel_amount: u128 = 0;
    for c in &sudt_cells {
        sudt_inputs.push(c.clone());
        sel_amount += c.amount;
        if sel_amount >= amount {
            break;
        }
    }
    let sudt_out_count: u64 = if sel_amount > amount { 2 } else { 1 };
    let sudt_input_cap: u64 = sudt_inputs.iter().map(|c| c.capacity).sum();

    // Pure-CKB cells to top up the sUDT output capacity + fee.
    let ckb_cells = get_cells(&from.lock_code_hash, &from.lock_hash_type, &from.lock_args)?;
    let needed_ckb = sudt_out_count * SUDT_CELL_CAPACITY + SUDT_FEE_ESTIMATE;
    let mut sorted = ckb_cells.clone();
    sorted.sort_by_key(|c| c.capacity);
    let mut selected_ckb: Vec<&Cell> = Vec::new();
    let mut sel_ckb_cap: u64 = 0;
    for c in &sorted {
        if sel_ckb_cap + sudt_input_cap >= needed_ckb + MIN_CELL_CAPACITY {
            break;
        }
        selected_ckb.push(c);
        sel_ckb_cap += c.capacity;
    }
    let total_in_cap = sel_ckb_cap + sudt_input_cap;
    if total_in_cap < needed_ckb {
        return Err(format!("insufficient CKB for transfer: have {total_in_cap}, need {needed_ckb}"));
    }

    let mut outputs = vec![TxOutput {
        capacity: SUDT_CELL_CAPACITY,
        lock_code_hash: to.lock_code_hash.clone(),
        lock_hash_type: to.lock_hash_type.clone(),
        lock_args: to.lock_args.clone(),
        type_code_hash: SUDT_CODE_HASH.to_string(),
        type_hash_type: "type".to_string(),
        type_args: type_args.clone(),
        data: u128_le_hex(amount),
    }];
    if sel_amount > amount {
        outputs.push(TxOutput {
            capacity: SUDT_CELL_CAPACITY,
            lock_code_hash: from.lock_code_hash.clone(),
            lock_hash_type: from.lock_hash_type.clone(),
            lock_args: from.lock_args.clone(),
            type_code_hash: SUDT_CODE_HASH.to_string(),
            type_hash_type: "type".to_string(),
            type_args: type_args.clone(),
            data: u128_le_hex(sel_amount - amount),
        });
    }
    let ckb_change = total_in_cap as i64
        - (sudt_out_count as i64) * (SUDT_CELL_CAPACITY as i64)
        - (SUDT_FEE_ESTIMATE as i64);
    if ckb_change >= MIN_CELL_CAPACITY as i64 {
        outputs.push(TxOutput {
            capacity: ckb_change as u64,
            lock_code_hash: from.lock_code_hash.clone(),
            lock_hash_type: from.lock_hash_type.clone(),
            lock_args: from.lock_args.clone(),
            type_code_hash: String::new(),
            type_hash_type: String::new(),
            type_args: String::new(),
            data: String::new(),
        });
    } else if ckb_change < 0 {
        return Err("insufficient CKB capacity for token transfer".into());
    }

    // Unified input list (sUDT first, then CKB) for build_transaction + build_tx_json.
    let mut all_cells: Vec<Cell> = sudt_inputs.iter().map(SudtCell::as_cell).collect();
    all_cells.extend(selected_ckb.iter().map(|c| (*c).clone()));
    let input_refs: Vec<&Cell> = all_cells.iter().collect();

    let request = TransactionRequest {
        inputs: all_cells
            .iter()
            .map(|c| TxInput { tx_hash: c.tx_hash.clone(), index: c.index, since: c.since, capacity: c.capacity })
            .collect(),
        outputs: outputs.clone(),
        cell_deps: vec![
            TxCellDep { tx_hash: SECP_DEP_TX_HASH.to_string(), index: SECP_DEP_INDEX, dep_type: 1 },
            TxCellDep { tx_hash: SUDT_DEP_TX_HASH.to_string(), index: SUDT_DEP_INDEX, dep_type: 0 },
        ],
        header_deps: vec![],
        fee_rate: 1000,
    };
    let built = build_transaction(request).map_err(|e| format!("build_transaction: {e}"))?;
    println!(
        "sending {amount} tokens to {} ({} sUDT in, {} out)",
        to.bech32m, sudt_inputs.len(), sudt_out_count
    );
    println!("tx_hash (signed): 0x{}", built.tx_hash_hex);

    let witness0 = sign_ckb_secp256k1_witness(built.tx_hash_hex.clone(), input_refs.len() as u32, kp.private_key_hex.clone())
        .map_err(|e| format!("sign_ckb_secp256k1_witness: {e}"))?;
    let tx_json = build_tx_json(
        &input_refs,
        &outputs,
        &witness0,
        &[(SECP_DEP_TX_HASH, SECP_DEP_INDEX, "dep_group"), (SUDT_DEP_TX_HASH, SUDT_DEP_INDEX, "code")],
        &[],
    );
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

/// A cell dep: (tx_hash, output index, dep_type "code"|"dep_group").
type Dep<'a> = (&'a str, u32, &'a str);

fn build_tx_json(
    selected: &[&Cell],
    outputs: &[TxOutput],
    witness0_hex: &str,
    deps: &[Dep],
    header_deps: &[&str],
) -> Value {
    let inputs: Vec<Value> = selected
        .iter()
        .map(|c| {
            json!({
                "previous_output": { "tx_hash": c.tx_hash, "index": hex_u32(c.index) },
                "since": hex_u64(c.since)
            })
        })
        .collect();

    let out_json: Vec<Value> = outputs
        .iter()
        .map(|o| {
            let mut cell = json!({
                "capacity": hex_u64(o.capacity),
                "lock": {
                    "code_hash": o.lock_code_hash,
                    "hash_type": o.lock_hash_type,
                    "args": o.lock_args
                },
                "type": null
            });
            if !o.type_code_hash.is_empty() {
                let type_args = if o.type_args.is_empty() {
                    "0x".to_string()
                } else {
                    format!("0x{}", o.type_args.trim_start_matches("0x"))
                };
                cell["type"] = json!({
                    "code_hash": o.type_code_hash,
                    "hash_type": o.type_hash_type,
                    "args": type_args
                });
            }
            cell
        })
        .collect();

    let outputs_data: Vec<Value> = outputs
        .iter()
        .map(|o| json!(if o.data.is_empty() { "0x".to_string() } else { format!("0x{}", o.data.trim_start_matches("0x")) }))
        .collect();

    let cell_deps: Vec<Value> = deps
        .iter()
        .map(|(tx, idx, dt)| {
            json!({
                "out_point": { "tx_hash": tx, "index": hex_u32(*idx) },
                "dep_type": dt
            })
        })
        .collect();

    let mut witnesses = vec![json!(format!("0x{witness0_hex}"))];
    for _ in 1..selected.len() {
        witnesses.push(json!("0x"));
    }

    json!({
        "version": "0x0",
        "cell_deps": cell_deps,
        "header_deps": header_deps,
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
    since: u64,
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
            since: 0,
        });
    }
    Ok(cells)
}

#[derive(Clone)]
struct SudtCell {
    tx_hash: String,
    index: u32,
    capacity: u64,
    amount: u128,
}

impl SudtCell {
    fn as_cell(&self) -> Cell {
        Cell { tx_hash: self.tx_hash.clone(), index: self.index, capacity: self.capacity, since: 0 }
    }
}

/// Indexer `get_cells` for a lock script, filtered to the given sUDT type
/// (args = owner lock hash), returning capacity + decoded u128 token amount.
fn get_sudt_cells(
    lock_code_hash: &str,
    lock_hash_type: &str,
    lock_args: &str,
    type_args: &str,
) -> Result<Vec<SudtCell>, String> {
    let search_key = json!({
        "script": { "code_hash": lock_code_hash, "hash_type": lock_hash_type, "args": lock_args },
        "script_type": "lock",
        "filter": { "script": { "code_hash": SUDT_CODE_HASH, "hash_type": "type", "args": type_args } },
        "with_data": true
    });
    let result = rpc("get_cells", json!([search_key, "asc", "0x3e8"]))?;
    let empty = vec![];
    let objects = result.get("objects").and_then(Value::as_array).unwrap_or(&empty);

    let mut cells = Vec::new();
    for o in objects {
        // Defensive: confirm the type script is the sUDT we asked for.
        let matches = o
            .pointer("/output/type/code_hash")
            .and_then(Value::as_str)
            .map(|c| c.eq_ignore_ascii_case(SUDT_CODE_HASH))
            .unwrap_or(false);
        if !matches {
            continue;
        }
        let cap_hex = o.pointer("/output/capacity").and_then(Value::as_str).unwrap_or("0x0");
        let tx_hash = o.pointer("/out_point/tx_hash").and_then(Value::as_str).unwrap_or("");
        let idx_hex = o.pointer("/out_point/index").and_then(Value::as_str).unwrap_or("0x0");
        let data = o.get("output_data").and_then(Value::as_str).unwrap_or("0x");
        cells.push(SudtCell {
            tx_hash: tx_hash.to_string(),
            index: parse_hex_u32(idx_hex)?,
            capacity: parse_hex_u64(cap_hex)?,
            amount: parse_u128_le(data),
        });
    }
    Ok(cells)
}

fn u128_le_hex(amount: u128) -> String {
    hex::encode(amount.to_le_bytes())
}

fn parse_u128_le(data_hex: &str) -> u128 {
    let b = hex::decode(data_hex.trim_start_matches("0x")).unwrap_or_default();
    let mut a = [0u8; 16];
    let n = b.len().min(16);
    a[..n].copy_from_slice(&b[..n]);
    u128::from_le_bytes(a)
}

/// sUDT type args = ckbhash(owner lock script molecule). The owner lock is the
/// authority that may mint; carrying it as an input authorizes output > input.
fn owner_lock_hash(code_hash: &str, hash_type: &str, args: &str) -> Result<String, String> {
    let script = ScriptSer {
        code_hash: h32(code_hash)?,
        hash_type: hash_type_to_byte(hash_type),
        args: hex::decode(args.trim_start_matches("0x")).map_err(|e| format!("lock args: {e}"))?,
    };
    let h = ckb_hash(hex::encode(script.to_bytes())).map_err(|e| format!("ckb_hash: {e}"))?;
    Ok(format!("0x{h}"))
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
