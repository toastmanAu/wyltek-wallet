//! Builds one attested journal entry for a manual settlement test (device checkpoint, no POS needed).
//! cargo run --example prepaid_journal_entry -- <k_debit> redeem <voucher_id16> <product_hash8> <counter> <count_after> <terminal_id> <key_id8>
//! cargo run --example prepaid_journal_entry -- <k_debit> override <product_hash8> <old_minor> <new_minor> <currency> <terminal_id> <key_id8>
use wyltekwalletcore::prepaid::journal::{attest, JournalEntry, KIND_PRICE_OVERRIDE, KIND_VOUCHER_REDEEM, VERSION_DEMO};

fn h<const N: usize>(s: &str) -> [u8; N] { hex::decode(s.trim_start_matches("0x")).unwrap().try_into().expect("length") }

fn main() {
    let a: Vec<String> = std::env::args().skip(1).collect();
    let key: [u8; 16] = h(&a[0]);
    let mut e = JournalEntry { version: VERSION_DEMO, kind: 0, terminal_id: 0, merchant_id: [0; 8], uid: [4, 0, 0, 0, 0, 0, 1],
        reserve_id: [0; 32], tmc: 0, amount_units: 0, balance_after: 0, ts: 0, tmv: [0; 8], sale_ref: [0; 8], pad: [0; 12] };
    match a[1].as_str() {
        "redeem" => {
            e.kind = KIND_VOUCHER_REDEEM;
            e.reserve_id[..16].copy_from_slice(&h::<16>(&a[2])); e.reserve_id[16..24].copy_from_slice(&h::<8>(&a[3]));
            e.tmc = a[4].parse().unwrap(); e.amount_units = 1; e.balance_after = a[5].parse().unwrap();
            e.terminal_id = a[6].parse().unwrap(); e.pad[..8].copy_from_slice(&h::<8>(&a[7]));
        }
        "override" => {
            e.kind = KIND_PRICE_OVERRIDE;
            e.reserve_id[..8].copy_from_slice(&h::<8>(&a[2]));
            e.amount_units = a[3].parse().unwrap(); e.balance_after = a[4].parse().unwrap();
            e.pad[8..11].copy_from_slice(a[5].as_bytes()); e.terminal_id = a[6].parse().unwrap(); e.pad[..8].copy_from_slice(&h::<8>(&a[7]));
        }
        other => panic!("kind {other}"),
    }
    println!("{}", hex::encode(attest(&key, &mut e)));
}
