//! Reserve identifiers and arithmetic for the demo-tier prepaid card (D3 spec §2.6, §2.9, §2.10, §4).
//! Pure; the uniffi surface is in `ffi.rs`.

use crate::molecule::ScriptSer;
use blake2b_ref::Blake2bBuilder;

/// 1 unit = 1 milli-CKB (demo-tier spec §2.8).
pub const UNIT_SHANNONS: u64 = 100_000;
/// secp256k1_blake160 minimum cell, funded by the merchant at issue and returned at close.
pub const FLOOR_SHANNONS: u64 = 61 * 100_000_000;
/// A cash-out output is a plain secp cell for the customer: same minimum.
pub const CUSTOMER_MIN_SHANNONS: u64 = 61 * 100_000_000;
/// 1,000 CKB per issue or load (D3 spec §2.6).
pub const LOAD_MAX_UNITS: i32 = 1_000 * 1_000;
/// Demo-tier spec §2.7.
pub const MAX_ACTIVE_CARDS: u32 = 64;

pub(crate) fn ckb_blake2b(data: &[u8]) -> [u8; 32] {
    let mut h = Blake2bBuilder::new(32).personal(b"ckb-default-hash").build();
    h.update(data);
    let mut out = [0u8; 32];
    h.finalize(&mut out);
    out
}

/// D3 spec §2.9: blake2b-256 over the 36-byte molecule OutPoint (tx_hash || index u32 LE).
pub fn reserve_id(tx_hash: &[u8; 32], index: u32) -> [u8; 32] {
    let mut m = [0u8; 36];
    m[..32].copy_from_slice(tx_hash);
    m[32..].copy_from_slice(&index.to_le_bytes());
    ckb_blake2b(&m)
}

/// Main spec §4.1: first 8 bytes of blake2b-256 of the merchant lock script (molecule Script).
pub fn merchant_id(code_hash: &[u8; 32], hash_type: u8, args: &[u8]) -> [u8; 8] {
    let bytes = ScriptSer { code_hash: *code_hash, hash_type, args: args.to_vec() }.to_bytes();
    ckb_blake2b(&bytes)[..8].try_into().unwrap()
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Action { Issue, Load, Void, CashOut }

/// Fixed outputs of one reserve transaction. The merchant's change output is computed by the
/// caller (total inputs − these − fee); `successor_capacity == 0` means no successor cell.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Plan { pub successor_capacity: u64, pub customer_shannons: u64, pub balance_after: i32 }

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum PlanError { AmountNotPositive, OverCap, Overflow, BelowFloor, CustomerBelowMinimum, BalanceMismatch }

fn expected_capacity(balance_units: i32) -> Result<u64, PlanError> {
    if balance_units < 0 { return Err(PlanError::BalanceMismatch); }
    (balance_units as u64).checked_mul(UNIT_SHANNONS).and_then(|v| v.checked_add(FLOOR_SHANNONS)).ok_or(PlanError::Overflow)
}

/// The invariant every reserve transaction keeps: capacity == FLOOR + balance × UNIT.
fn check_invariant(balance_units: i32, capacity: u64) -> Result<(), PlanError> {
    if capacity < FLOOR_SHANNONS { return Err(PlanError::BelowFloor); }
    if capacity != expected_capacity(balance_units)? { return Err(PlanError::BalanceMismatch); }
    Ok(())
}

pub fn plan(action: Action, balance_units: i32, capacity_shannons: u64, amount_units: i32) -> Result<Plan, PlanError> {
    match action {
        Action::Issue => {
            if amount_units <= 0 { return Err(PlanError::AmountNotPositive); }
            if amount_units > LOAD_MAX_UNITS { return Err(PlanError::OverCap); }
            Ok(Plan { successor_capacity: expected_capacity(amount_units)?, customer_shannons: 0, balance_after: amount_units })
        }
        Action::Load => {
            if amount_units <= 0 { return Err(PlanError::AmountNotPositive); }
            if amount_units > LOAD_MAX_UNITS { return Err(PlanError::OverCap); }
            check_invariant(balance_units, capacity_shannons)?;
            let after = balance_units.checked_add(amount_units).ok_or(PlanError::Overflow)?;
            Ok(Plan { successor_capacity: expected_capacity(after)?, customer_shannons: 0, balance_after: after })
        }
        Action::Void => {
            if capacity_shannons < FLOOR_SHANNONS { return Err(PlanError::BelowFloor); }
            Ok(Plan { successor_capacity: 0, customer_shannons: 0, balance_after: 0 })
        }
        Action::CashOut => {
            if balance_units <= 0 { return Err(PlanError::AmountNotPositive); }
            check_invariant(balance_units, capacity_shannons)?;
            let customer = balance_units as u64 * UNIT_SHANNONS;
            if customer < CUSTOMER_MIN_SHANNONS { return Err(PlanError::CustomerBelowMinimum); }
            Ok(Plan { successor_capacity: 0, customer_shannons: customer, balance_after: 0 })
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn tx_hash() -> [u8; 32] { core::array::from_fn(|i| i as u8) }

    #[test]
    fn reserve_id_is_blake2b_over_outpoint_molecule() {
        // ckb-default-hash blake2b-256 over tx_hash || index LE, pinned once by hand (python below) and never changed:
        // python3 -c "import hashlib;print(hashlib.blake2b(bytes(range(32))+(1).to_bytes(4,'little'),digest_size=32,person=b'ckb-default-hash').hexdigest())"
        let id = reserve_id(&tx_hash(), 1);
        assert_eq!(hex::encode(id), "d0a4145cf09b6b16800d77952806fd37522e263271b8ac660edae69fade914d6");
        assert_ne!(reserve_id(&tx_hash(), 0), reserve_id(&tx_hash(), 1));
    }

    #[test]
    fn merchant_id_is_first_8_bytes_of_script_hash() {
        // secp256k1_blake160_sighash_all testnet lock with a 20-byte zero args:
        // script hash via ckb-cli / explorer for args 0x00*20 (same value on every network, code_hash is the system one)
        let code: [u8; 32] = hex::decode("9bd7e06f3ecf4be0f2fcd2188b23f1b9fcc88e5d4b65a8637b17723bbda3cce8").unwrap().try_into().unwrap();
        let m = merchant_id(&code, 1, &[0u8; 20]);
        let full = {
            let s = crate::molecule::ScriptSer { code_hash: code, hash_type: 1, args: vec![0u8; 20] }.to_bytes();
            ckb_blake2b(&s)
        };
        assert_eq!(&m[..], &full[..8]);
        assert_ne!(merchant_id(&code, 1, &[0u8; 20]), merchant_id(&code, 1, &[1u8; 20]));
    }

    #[test]
    fn issue_plan_is_floor_plus_amount() {
        let p = plan(Action::Issue, 0, 0, 250_000).unwrap(); // 250 CKB
        assert_eq!(p.successor_capacity, FLOOR_SHANNONS + 250_000 * UNIT_SHANNONS);
        assert_eq!(p.customer_shannons, 0);
        assert_eq!(p.balance_after, 250_000);
    }

    #[test]
    fn issue_and_load_refuse_bad_amounts() {
        assert_eq!(plan(Action::Issue, 0, 0, 0).unwrap_err(), PlanError::AmountNotPositive);
        assert_eq!(plan(Action::Issue, 0, 0, -5).unwrap_err(), PlanError::AmountNotPositive);
        assert_eq!(plan(Action::Issue, 0, 0, LOAD_MAX_UNITS + 1).unwrap_err(), PlanError::OverCap);
        assert!(plan(Action::Issue, 0, 0, LOAD_MAX_UNITS).is_ok());
        let cap = FLOOR_SHANNONS + (i32::MAX as u64 - 10) * UNIT_SHANNONS;
        assert_eq!(plan(Action::Load, i32::MAX - 10, cap, 100).unwrap_err(), PlanError::Overflow);
    }

    #[test]
    fn load_plan_requires_capacity_to_match_index_balance() {
        let cap = FLOOR_SHANNONS + 100_000 * UNIT_SHANNONS;
        let p = plan(Action::Load, 100_000, cap, 50_000).unwrap();
        assert_eq!(p.successor_capacity, cap + 50_000 * UNIT_SHANNONS);
        assert_eq!(p.balance_after, 150_000);
        assert_eq!(plan(Action::Load, 100_000, cap + 1, 50_000).unwrap_err(), PlanError::BalanceMismatch);
        assert_eq!(plan(Action::Load, 100_000, FLOOR_SHANNONS - 1, 50_000).unwrap_err(), PlanError::BelowFloor);
    }

    #[test]
    fn void_plan_has_no_outputs_and_zero_balance() {
        let cap = FLOOR_SHANNONS + 12_345 * UNIT_SHANNONS;
        let p = plan(Action::Void, 12_345, cap, 0).unwrap();
        assert_eq!(p, Plan { successor_capacity: 0, customer_shannons: 0, balance_after: 0 });
        assert_eq!(plan(Action::Void, 0, FLOOR_SHANNONS - 1, 0).unwrap_err(), PlanError::BelowFloor);
    }

    #[test]
    fn cash_out_pays_balance_to_customer_at_or_above_minimum() {
        let bal = 61_000; // exactly 61 CKB
        let cap = FLOOR_SHANNONS + bal as u64 * UNIT_SHANNONS;
        let p = plan(Action::CashOut, bal, cap, 0).unwrap();
        assert_eq!(p, Plan { successor_capacity: 0, customer_shannons: CUSTOMER_MIN_SHANNONS, balance_after: 0 });
        let bal2 = 60_999;
        let cap2 = FLOOR_SHANNONS + bal2 as u64 * UNIT_SHANNONS;
        assert_eq!(plan(Action::CashOut, bal2, cap2, 0).unwrap_err(), PlanError::CustomerBelowMinimum);
        assert_eq!(plan(Action::CashOut, 0, FLOOR_SHANNONS, 0).unwrap_err(), PlanError::AmountNotPositive);
        assert_eq!(plan(Action::CashOut, bal, cap + 5, 0).unwrap_err(), PlanError::BalanceMismatch);
    }
}
