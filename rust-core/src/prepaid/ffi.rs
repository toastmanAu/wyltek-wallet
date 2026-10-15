//! uniffi surface for the prepaid demo tier (D3 spec §4). Thin hex/record adapters over the pure modules.
use super::{record, reserve, verifier};

#[derive(Debug, thiserror::Error, uniffi::Error)]
#[uniffi(flat_error)]
pub enum PrepaidError {
    #[error("Invalid input: {0}")] InvalidInput(String),
    #[error("Plan refused: {0}")] Plan(String),
}

fn strip(h: &str) -> &str { h.strip_prefix("0x").or_else(|| h.strip_prefix("0X")).unwrap_or(h) }
fn hexv(h: &str) -> Result<Vec<u8>, PrepaidError> { hex::decode(strip(h)).map_err(|e| PrepaidError::InvalidInput(format!("hex: {e}"))) }
fn fixed<const N: usize>(h: &str, what: &str) -> Result<[u8; N], PrepaidError> {
    hexv(h)?.try_into().map_err(|_| PrepaidError::InvalidInput(format!("{what} must be {N} bytes")))
}
pub(crate) fn hex16(h: &str) -> Result<[u8; 16], PrepaidError> { fixed::<16>(h, "k_debit") }
pub(crate) fn hex7(h: &str) -> Result<[u8; 7], PrepaidError> { fixed::<7>(h, "uid") }

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Enum)]
pub enum SlotPickFfi { None, A, B, Ambiguous }
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Enum)]
pub enum SlotReason { NoValidSlot, BadVersion, MacFail }
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct CardRecordInfo { pub writer_id: u16, pub reserve_id_hex: String, pub balance_units: i32, pub counter: u32, pub merchant_id_hex: String }
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct SlotResult { pub pick: SlotPickFfi, pub record: Option<CardRecordInfo>, pub reason: SlotReason, pub merchant_hint_hex: Option<String> }
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum PrepaidAction { Issue, Load, Void, CashOut }
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct ReservePlan { pub successor_capacity: u64, pub customer_shannons: u64, pub balance_after: i32 }
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct CardStateFfi { pub reserve_id_hex: String, pub balance_units: i32, pub settled_counter: u32, pub revoked: bool }
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum JournalVerdict { Accepted, Duplicate, BadMac, UnknownCard, StaleCounter, OverBalance }
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct CardNetFfi { pub reserve_id_hex: String, pub net_units: i64, pub max_tmc: u32, pub accepted: u32 }
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct BatchResultFfi { pub verdicts: Vec<JournalVerdict>, pub nets: Vec<CardNetFfi> }

fn info_of(r: &record::DemoCardRecord) -> CardRecordInfo {
    CardRecordInfo { writer_id: r.writer_id, reserve_id_hex: hex::encode(r.reserve_id), balance_units: r.balance_units,
        counter: r.counter, merchant_id_hex: hex::encode(r.merchant_id) }
}

#[uniffi::export]
pub fn prepaid_select_slot(k_debit_hex: String, uid_hex: String, raw128_hex: String) -> Result<SlotResult, PrepaidError> {
    let key = hex16(&k_debit_hex)?; let uid = hex7(&uid_hex)?;
    let raw: [u8; 128] = fixed::<128>(&raw128_hex, "slots")?;
    let a: &[u8; 64] = raw[..64].try_into().unwrap(); let b: &[u8; 64] = raw[64..].try_into().unwrap();
    let (pick, rec, diag) = record::select_slot_diag(&key, &uid, a, b);
    Ok(SlotResult {
        pick: match pick { record::SlotPick::None => SlotPickFfi::None, record::SlotPick::A => SlotPickFfi::A, record::SlotPick::B => SlotPickFfi::B, record::SlotPick::Ambiguous => SlotPickFfi::Ambiguous },
        record: rec.as_ref().map(info_of),
        reason: match diag.reason { record::SlotNoneReason::NoValidSlot => SlotReason::NoValidSlot, record::SlotNoneReason::BadVersion => SlotReason::BadVersion, record::SlotNoneReason::MacFail => SlotReason::MacFail },
        merchant_hint_hex: diag.merchant_hint.map(hex::encode),
    })
}

#[uniffi::export]
pub fn prepaid_seal_record(k_debit_hex: String, uid_hex: String, record: CardRecordInfo) -> Result<String, PrepaidError> {
    let key = hex16(&k_debit_hex)?; let uid = hex7(&uid_hex)?;
    let mut r = record::DemoCardRecord { version: record::VERSION_DEMO, scheme: record::SCHEME_CKB_MILLI, writer_id: record.writer_id,
        reserve_id: fixed::<32>(&record.reserve_id_hex, "reserve_id")?, balance_units: record.balance_units, counter: record.counter,
        merchant_id: fixed::<8>(&record.merchant_id_hex, "merchant_id")?, mac: [0; 8] };
    Ok(hex::encode(record::seal(&key, &uid, &mut r)))
}

#[uniffi::export]
pub fn prepaid_next_slot(pick: SlotPickFfi) -> SlotPickFfi {
    match pick { SlotPickFfi::A => SlotPickFfi::B, _ => SlotPickFfi::A }
}

#[uniffi::export]
pub fn prepaid_reserve_id(tx_hash_hex: String, index: u32) -> Result<String, PrepaidError> {
    Ok(hex::encode(reserve::reserve_id(&fixed::<32>(&tx_hash_hex, "tx_hash")?, index)))
}

#[uniffi::export]
pub fn prepaid_merchant_id(code_hash_hex: String, hash_type: String, args_hex: String) -> Result<String, PrepaidError> {
    let ht = match hash_type.as_str() { "data" => 0u8, "type" => 1, "data1" => 2, "data2" => 4,
        other => return Err(PrepaidError::InvalidInput(format!("hash_type {other}"))) };
    Ok(hex::encode(reserve::merchant_id(&fixed::<32>(&code_hash_hex, "code_hash")?, ht, &hexv(&args_hex)?)))
}

#[uniffi::export]
pub fn prepaid_plan(action: PrepaidAction, balance_units: i32, capacity_shannons: u64, amount_units: i32) -> Result<ReservePlan, PrepaidError> {
    let a = match action { PrepaidAction::Issue => reserve::Action::Issue, PrepaidAction::Load => reserve::Action::Load,
        PrepaidAction::Void => reserve::Action::Void, PrepaidAction::CashOut => reserve::Action::CashOut };
    let p = reserve::plan(a, balance_units, capacity_shannons, amount_units).map_err(|e| PrepaidError::Plan(match e {
        reserve::PlanError::AmountNotPositive => "amount must be positive".into(),
        reserve::PlanError::OverCap => format!("amount above the demo cap of {} CKB", reserve::LOAD_MAX_UNITS / 1000),
        reserve::PlanError::Overflow => "balance overflow".into(),
        reserve::PlanError::BelowFloor => "reserve cell below the 61 CKB floor".into(),
        reserve::PlanError::CustomerBelowMinimum => "balance below the 61 CKB minimum cell; pay out in cash and void".into(),
        reserve::PlanError::BalanceMismatch => "reserve capacity does not match the index balance; use Inspect".into(),
    }))?;
    Ok(ReservePlan { successor_capacity: p.successor_capacity, customer_shannons: p.customer_shannons, balance_after: p.balance_after })
}

#[uniffi::export]
pub fn prepaid_verify_batch(k_debit_hex: String, entries_hex: String, cards: Vec<CardStateFfi>) -> Result<BatchResultFfi, PrepaidError> {
    let key = hex16(&k_debit_hex)?;
    let entries = hexv(&entries_hex)?;
    let states = cards.iter().map(|c| Ok(verifier::CardState { reserve_id: fixed::<32>(&c.reserve_id_hex, "reserve_id")?,
        balance_units: c.balance_units, settled_counter: c.settled_counter, revoked: c.revoked })).collect::<Result<Vec<_>, PrepaidError>>()?;
    let r = verifier::verify_batch(&key, &entries, &states, &[]).map_err(|_| PrepaidError::InvalidInput("entries length is not a multiple of 96".into()))?;
    Ok(BatchResultFfi {
        verdicts: r.verdicts.into_iter().map(|v| match v { verifier::Verdict::Accepted => JournalVerdict::Accepted, verifier::Verdict::Duplicate => JournalVerdict::Duplicate,
            verifier::Verdict::BadMac => JournalVerdict::BadMac, verifier::Verdict::UnknownCard => JournalVerdict::UnknownCard,
            verifier::Verdict::StaleCounter => JournalVerdict::StaleCounter, verifier::Verdict::OverBalance => JournalVerdict::OverBalance,
            // TEMPORARY until Task 5 adds voucher verdicts to the FFI; no voucher states are passed yet.
            _ => JournalVerdict::BadMac }).collect(),
        nets: r.nets.into_iter().map(|n| CardNetFfi { reserve_id_hex: hex::encode(n.reserve_id), net_units: n.net_units, max_tmc: n.max_tmc, accepted: n.accepted }).collect(),
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::prepaid::record;

    const K: &str = "000102030405060708090a0b0c0d0e0f";
    const UID: &str = "04958caa5c5e80";

    fn sealed(counter: u32, balance: i32) -> String {
        let mut r = record::DemoCardRecord { version: record::VERSION_DEMO, scheme: record::SCHEME_CKB_MILLI, writer_id: 0,
            reserve_id: [0xA0; 32], balance_units: balance, counter, merchant_id: [0x11; 8], mac: [0; 8] };
        hex::encode(record::seal(&hex16(K).unwrap(), &hex7(UID).unwrap(), &mut r))
    }

    #[test]
    fn select_slot_round_trips_through_hex() {
        let raw = format!("0x{}{}", sealed(5, 1000), "00".repeat(64));
        let r = prepaid_select_slot(K.into(), UID.into(), raw).unwrap();
        assert_eq!(r.pick, SlotPickFfi::A);
        let rec = r.record.unwrap();
        assert_eq!((rec.counter, rec.balance_units, rec.writer_id), (5, 1000, 0));
        assert_eq!(rec.reserve_id_hex, "a0".repeat(32));
        assert_eq!(rec.merchant_id_hex, "11".repeat(8));
    }

    #[test]
    fn select_slot_reports_mac_fail_hint_and_bad_version() {
        let other_uid = "04ebbb02cd2a81";
        let raw = format!("{}{}", sealed(5, 1000), "00".repeat(64));
        let r = prepaid_select_slot(K.into(), other_uid.into(), raw).unwrap();
        assert_eq!((r.pick, r.reason), (SlotPickFfi::None, SlotReason::MacFail));
        assert_eq!(r.merchant_hint_hex.as_deref(), Some("1111111111111111"));
        let mut bad = sealed(5, 1000); bad.replace_range(8..10, "01");
        let r = prepaid_select_slot(K.into(), UID.into(), format!("{}{}", bad, "00".repeat(64))).unwrap();
        assert_eq!(r.reason, SlotReason::BadVersion);
    }

    #[test]
    fn seal_then_select_agrees() {
        let info = CardRecordInfo { writer_id: 0, reserve_id_hex: "a0".repeat(32), balance_units: 250_000, counter: 0, merchant_id_hex: "11".repeat(8) };
        let raw64 = prepaid_seal_record(K.into(), UID.into(), info).unwrap();
        assert_eq!(raw64.len(), 128);
        let r = prepaid_select_slot(K.into(), UID.into(), format!("{}{}", "00".repeat(64), raw64)).unwrap();
        assert_eq!(r.pick, SlotPickFfi::B);
        assert_eq!(r.record.unwrap().balance_units, 250_000);
    }

    #[test]
    fn next_slot_is_the_other_one_and_a_by_default() {
        assert_eq!(prepaid_next_slot(SlotPickFfi::A), SlotPickFfi::B);
        assert_eq!(prepaid_next_slot(SlotPickFfi::B), SlotPickFfi::A);
        assert_eq!(prepaid_next_slot(SlotPickFfi::None), SlotPickFfi::A);
        assert_eq!(prepaid_next_slot(SlotPickFfi::Ambiguous), SlotPickFfi::A);
    }

    #[test]
    fn bad_hex_and_lengths_are_errors() {
        assert!(prepaid_select_slot("zz".into(), UID.into(), "00".repeat(128)).is_err());
        assert!(prepaid_select_slot(K.into(), UID.into(), "00".repeat(127)).is_err());
        assert!(prepaid_select_slot(K.into(), "0001".into(), "00".repeat(128)).is_err());
        assert!(prepaid_reserve_id("00".repeat(31), 0).is_err());
        assert!(prepaid_merchant_id("00".repeat(32), "weird".into(), "".into()).is_err());
    }

    #[test]
    fn reserve_id_and_merchant_id_match_pure_functions() {
        let th = "0x".to_string() + &hex::encode([7u8; 32]);
        assert_eq!(prepaid_reserve_id(th, 2).unwrap(), hex::encode(crate::prepaid::reserve::reserve_id(&[7u8; 32], 2)));
        let code = "9bd7e06f3ecf4be0f2fcd2188b23f1b9fcc88e5d4b65a8637b17723bbda3cce8";
        let m = prepaid_merchant_id(code.into(), "type".into(), "0x".to_string() + &"00".repeat(20)).unwrap();
        assert_eq!(m.len(), 16);
        let code_b: [u8; 32] = hex::decode(code).unwrap().try_into().unwrap();
        assert_eq!(m, hex::encode(crate::prepaid::reserve::merchant_id(&code_b, 1, &[0u8; 20])));
        assert_ne!(m, prepaid_merchant_id(code.into(), "data".into(), "00".repeat(20)).unwrap());
    }

    #[test]
    fn plan_and_verify_batch_cross_the_boundary() {
        let p = prepaid_plan(PrepaidAction::Issue, 0, 0, 1000).unwrap();
        assert_eq!(p.successor_capacity, 6_100_000_000 + 1000 * 100_000);
        let e = prepaid_plan(PrepaidAction::Issue, 0, 0, 0).unwrap_err();
        assert!(matches!(e, PrepaidError::Plan(_)));
        let r = prepaid_verify_batch(K.into(), "".into(), vec![]).unwrap();
        assert!(r.verdicts.is_empty());
        assert!(prepaid_verify_batch(K.into(), "00".repeat(95), vec![]).is_err());
    }
}
