//! Builds one attested journal entry for a manual settlement test (device checkpoint, no POS needed).
//! It signs with the real merchant K_debit, so every argument is validated and a bad one exits 2 with no stdout.
//! Optional trailing flags: --uid <7B hex> (default 04000000000001) --merchant <8B hex> (default 0000000000000000)
//! For a live settlement --merchant MUST be the Vault's real merchant_id: the verifier answers bad_mac to a redeem or
//! override carrying any other merchant_id (vouchers spec §8.3), including the all-zero default.
//!                          --ts <u32> (default 0) --sale-ref <8B hex> (default 0000000000000000)
use wyltekwalletcore::prepaid::journal::{self, attest, JournalEntry, KIND_PRICE_OVERRIDE, KIND_STAFF_LOCKOUT, KIND_VOUCHER_REDEEM, VERSION_DEMO};

pub const USAGE: &str = "usage:
  prepaid_journal_entry <k_debit16> redeem <voucher_id16> <product_hash8> <counter u32> <count_after 0..254> <terminal_id u16> <key_id8 non-zero> [flags]
  prepaid_journal_entry <k_debit16> override <product_hash8> <old_minor >=0> <new_minor >=0> <currency AAA> <terminal_id u16> <key_id8 non-zero> [flags]
  prepaid_journal_entry <k_debit16> lockout <terminal_id u16> <key_id8 non-zero> <pin_ver u16> [flags]
flags: --uid <7B hex> (default 04000000000001)  --merchant <8B hex> (default 0000000000000000)
       --merchant MUST be the Vault's real merchant_id for a live settlement; any other value (the default too) answers bad_mac.
       --ts <u32> (default 0)  --sale-ref <8B hex> (default 0000000000000000)
hex arguments are exact-length (optional 0x prefix). Output: one 96-byte entry as hex.";

fn hexn<const N: usize>(name: &str, s: &str) -> Result<[u8; N], String> {
    let b = hex::decode(s.strip_prefix("0x").unwrap_or(s)).map_err(|e| format!("{name}: bad hex ({e})"))?;
    b.try_into().map_err(|v: Vec<u8>| format!("{name}: need exactly {N} bytes, got {}", v.len()))
}
fn num<T: std::str::FromStr>(name: &str, s: &str) -> Result<T, String> {
    s.parse::<T>().map_err(|_| format!("{name}: '{s}' is not a valid number in range"))
}
fn price(name: &str, s: &str) -> Result<i32, String> {
    let v: i32 = num(name, s)?;
    if v < 0 { Err(format!("{name}: must be >= 0")) } else { Ok(v) }
}
fn key_id(s: &str) -> Result<[u8; 8], String> {
    let k: [u8; 8] = hexn("key_id", s)?;
    if k == [0; 8] { Err("key_id: must be non-zero".into()) } else { Ok(k) }
}

/// Parses `args` (everything after the program name) and returns the attested entry as hex.
pub fn build(args: &[String]) -> Result<String, String> {
    if args.len() < 2 { return Err("missing arguments".into()); }
    let key: [u8; 16] = hexn("k_debit", &args[0])?;
    let (npos, sub) = match args[1].as_str() { "redeem" => (8, "redeem"), "override" => (8, "override"), "lockout" => (5, "lockout"), o => return Err(format!("unknown subcommand '{o}'")) };
    let mut pos: Vec<&String> = Vec::new();
    let mut flags: Vec<(&str, &String)> = Vec::new();
    let mut it = args[2..].iter();
    while let Some(a) = it.next() {
        if a.starts_with("--") {
            if !["--uid", "--merchant", "--ts", "--sale-ref"].contains(&a.as_str()) { return Err(format!("unknown flag {a}")); }
            if flags.iter().any(|(n, _)| n == a) { return Err(format!("duplicate flag {a}")); }
            flags.push((a.as_str(), it.next().ok_or(format!("{a}: missing value"))?));
        } else { pos.push(a); }
    }
    if pos.len() != npos - 2 { return Err(format!("{sub}: expected {} positional arguments after the subcommand, got {}", npos - 2, pos.len())); }
    let mut e = JournalEntry { version: VERSION_DEMO, kind: 0, terminal_id: 0, merchant_id: [0; 8], uid: [4, 0, 0, 0, 0, 0, 1],
        reserve_id: [0; 32], tmc: 0, amount_units: 0, balance_after: 0, ts: 0, tmv: [0; 8], sale_ref: [0; 8], pad: [0; 12] };
    for (n, v) in &flags {
        match *n {
            "--uid" => e.uid = hexn("--uid", v)?, "--merchant" => e.merchant_id = hexn("--merchant", v)?,
            "--ts" => e.ts = num("--ts", v)?, _ => e.sale_ref = hexn("--sale-ref", v)? }
    }
    if sub == "redeem" {
        e.kind = KIND_VOUCHER_REDEEM;
        e.reserve_id[..16].copy_from_slice(&hexn::<16>("voucher_id", pos[0])?);
        e.reserve_id[16..24].copy_from_slice(&hexn::<8>("product_hash", pos[1])?);
        e.tmc = num("counter", pos[2])?;
        let after: u8 = num("count_after", pos[3])?;
        if after > 254 { return Err("count_after: must be 0..=254".into()); }
        e.amount_units = 1; e.balance_after = after as i32;
        e.terminal_id = num("terminal_id", pos[4])?;
        e.pad[..8].copy_from_slice(&key_id(pos[5])?);
    } else if sub == "lockout" {
        e.kind = KIND_STAFF_LOCKOUT;
        e.terminal_id = num("terminal_id", pos[0])?;
        e.pad[..8].copy_from_slice(&key_id(pos[1])?);
        let ver: u16 = num("pin_ver", pos[2])?;
        e.pad[8..10].copy_from_slice(&ver.to_le_bytes());
        e.amount_units = 3;
    } else {
        e.kind = KIND_PRICE_OVERRIDE;
        e.reserve_id[..8].copy_from_slice(&hexn::<8>("product_hash", pos[0])?);
        e.amount_units = price("old_minor", pos[1])?; e.balance_after = price("new_minor", pos[2])?;
        let cur = pos[3].as_bytes();
        if cur.len() != 3 || !cur.iter().all(|b| b.is_ascii_uppercase()) { return Err("currency: must be exactly 3 letters A-Z".into()); }
        e.pad[8..11].copy_from_slice(cur);
        e.terminal_id = num("terminal_id", pos[4])?;
        e.pad[..8].copy_from_slice(&key_id(pos[5])?);
    }
    let raw = attest(&key, &mut e);
    if journal::decode(&raw).as_ref() != Some(&e) || !journal::verify(&key, &raw) {
        return Err("internal: built entry failed decode/verify round trip".into());
    }
    Ok(hex::encode(raw))
}

fn main() {
    let a: Vec<String> = std::env::args().skip(1).collect();
    match build(&a) {
        Ok(h) => println!("{h}"),
        Err(m) => { eprintln!("error: {m}\n{USAGE}"); std::process::exit(2); }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn v(s: &str) -> Vec<String> { s.split_whitespace().map(String::from).collect() }
    const K: &str = "000102030405060708090a0b0c0d0e0f";
    const VID: &str = "c0c1c2c3c4c5c6c7c8c9cacbcccdcecf";
    #[test] fn good_redeem_is_192_hex() {
        assert_eq!(build(&v(&format!("{K} redeem {VID} 5a5a5a5a5a5a5a5a 1 0 3 7777777777777777"))).unwrap().len(), 192);
    }
    #[test] fn good_override_with_flags() {
        assert!(build(&v(&format!("{K} override 5a5a5a5a5a5a5a5a 500 450 AUD 3 7777777777777777 --ts 9 --uid 04958caa5c5e80"))).is_ok());
    }
    #[test] fn malformed_inputs_are_refused() {
        for bad in [
            format!("{K} redeem {VID} 5a5a5a5a5a5a5a5a 1 255 3 7777777777777777"),      // count_after > 254
            format!("{K} redeem {VID} 5a5a5a5a5a5a5a5a 1 0 3 0000000000000000"),         // zero key_id
            format!("{K} redeem {VID} 5a5a5a5a5a5a5a5a 1 0 70000 7777777777777777"),     // terminal > u16
            format!("{K} redeem {VID} 5a5a5a5a5a5a5a 1 0 3 7777777777777777"),           // short product_hash
            format!("{K} redeem {VID} 5a5a5a5a5a5a5a5a 1 0 3 7777777777777777 extra"),   // extra positional
            format!("{K} redeem {VID} 5a5a5a5a5a5a5a5a 1 0 3"),                          // missing positional
            format!("00 redeem {VID} 5a5a5a5a5a5a5a5a 1 0 3 7777777777777777"),          // short key
            format!("{K} override 5a5a5a5a5a5a5a5a -1 450 AUD 3 7777777777777777"),      // negative price
            format!("{K} override 5a5a5a5a5a5a5a5a 1 99999999999 AUD 3 7777777777777777"), // > i32
            format!("{K} override 5a5a5a5a5a5a5a5a 1 450 aud 3 7777777777777777"),       // lowercase currency
            format!("{K} override 5a5a5a5a5a5a5a5a 1 450 AUDD 3 7777777777777777"),      // 4-letter currency
            format!("{K} redeem {VID} 5a5a5a5a5a5a5a5a 1 0 3 7777777777777777 --sale-ref 00"), // short sale-ref
            format!("{K} redeem {VID} 5a5a5a5a5a5a5a5a 1 0 3 7777777777777777 --bogus 1"),
            format!("{K} nope"),
        ] { assert!(build(&v(&bad)).is_err(), "should refuse: {bad}"); }
    }
    #[test] fn lockout_builds_a_verifying_0x05_entry() {
        let h = build(&v(&format!("{K} lockout 3 7777777777777777 258"))).unwrap();
        let raw: [u8; 96] = hex::decode(&h).unwrap().try_into().unwrap();
        let key: [u8; 16] = hex::decode(K).unwrap().try_into().unwrap();
        assert!(journal::verify(&key, &raw));
        let e = journal::decode(&raw).unwrap();
        assert_eq!(e.kind, 5);
        assert_eq!(e.amount_units, 3);
        assert_eq!(e.terminal_id, 3);
        assert_eq!(&e.pad[..8], &[0x77u8; 8]);
        assert_eq!(u16::from_le_bytes([e.pad[8], e.pad[9]]), 258);
        assert_eq!(&e.pad[10..], &[0, 0]);
    }
    #[test] fn lockout_refuses_a_zero_key_id() {
        assert!(build(&v(&format!("{K} lockout 3 0000000000000000 1"))).is_err());
    }
    #[test] fn lockout_refuses_malformed_inputs() {
        for bad in [format!("{K} lockout 3 7777777777777777"), format!("{K} lockout 3 7777777777777777 70000"),
            format!("{K} lockout 70000 7777777777777777 1"), format!("{K} lockout 3 7777777777777777 1 extra")] {
            assert!(build(&v(&bad)).is_err(), "should refuse: {bad}");
        }
    }
}
