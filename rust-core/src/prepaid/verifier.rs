//! Settlement verifier for version-0x02 (terminal-attested) journal entries. D3 spec §6, demo-tier §6.3.
//! Verdict order per entry: length → MAC (covers decode + sign rule) → known & not revoked →
//! tmc above settled → tmc above this batch's running max for the card → cumulative net within balance.
//! Redeems: MAC → merchant → known voucher → settled counter (same hash = duplicate, else double_redeem; answered even for a void voucher) → not void → batch counter → uses left. Overrides: MAC → merchant.

use super::journal::{self, ENTRY_SIZE, KIND_PRICE_OVERRIDE, KIND_STAFF_LOCKOUT, KIND_VOUCHER_REDEEM};
use super::reserve::ckb_blake2b;
use std::collections::HashMap;

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct CardState { pub reserve_id: [u8; 32], pub balance_units: i32, pub settled_counter: u32, pub revoked: bool }

/// A voucher as the Vault index knows it. `settled` = every (counter, blake2b(entry)) already settled.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct VoucherState { pub voucher_id: [u8; 16], pub count: u8, pub void: bool, pub settled: Vec<(u32, [u8; 32])> }

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Verdict { Accepted, Duplicate, BadMac, UnknownCard, StaleCounter, OverBalance, DoubleRedeem, UnknownVoucher, VoucherVoid, UnsupportedKind }

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct CardNet { pub reserve_id: [u8; 32], pub net_units: i64, pub max_tmc: u32, pub accepted: u32 }
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct RedeemOut { pub index: u32, pub terminal_id: u16, pub voucher_id: [u8; 16], pub counter: u32, pub key_id: [u8; 8], pub entry_hash: [u8; 32] }
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct OverrideOut { pub index: u32, pub terminal_id: u16, pub product_hash: [u8; 8], pub old_price: i32, pub new_price: i32,
    pub currency: [u8; 3], pub key_id: [u8; 8], pub entry_hash: [u8; 32] }

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct LockoutOut { pub index: u32, pub terminal_id: u16, pub uid: [u8; 7], pub key_id: [u8; 8], pub pin_ver: u16,
    pub attempts: i32, pub entry_hash: [u8; 32] }

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct BatchResult { pub verdicts: Vec<Verdict>, pub nets: Vec<CardNet>, pub redeems: Vec<RedeemOut>, pub overrides: Vec<OverrideOut>,
    pub lockouts: Vec<LockoutOut> }

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum BatchError { BadLength }

struct Running { net: i64, max_tmc: Option<u32>, accepted: u32 }
#[derive(Default)]
struct VoucherRun { max_counter: Option<u32>, accepted: u32, seen: HashMap<u32, [u8; 32]> }

/// `merchant` = this Vault's merchant_id: a voucher redeem (0x03), price override (0x04) or staff lockout (0x05) carrying any other merchant_id
/// answers `bad_mac` (vouchers spec §8.3). Card entries (0x01/0x02) are not merchant-checked here, as in D3.
pub fn verify_batch(key: &[u8; 16], merchant: &[u8; 8], entries: &[u8], cards: &[CardState], vouchers: &[VoucherState]) -> Result<BatchResult, BatchError> {
    if entries.len() % ENTRY_SIZE != 0 { return Err(BatchError::BadLength); }
    let index: HashMap<[u8; 32], &CardState> = cards.iter().map(|c| (c.reserve_id, c)).collect();
    let vindex: HashMap<[u8; 16], &VoucherState> = vouchers.iter().map(|v| (v.voucher_id, v)).collect();
    let mut running: HashMap<[u8; 32], Running> = HashMap::new();
    let mut vrun: HashMap<[u8; 16], VoucherRun> = HashMap::new();
    let mut order: Vec<[u8; 32]> = Vec::new();
    let mut verdicts = Vec::with_capacity(entries.len() / ENTRY_SIZE);
    let (mut redeems, mut overrides, mut lockouts) = (Vec::new(), Vec::new(), Vec::new());

    for (i, chunk) in entries.chunks_exact(ENTRY_SIZE).enumerate() {
        let raw: &[u8; ENTRY_SIZE] = chunk.try_into().unwrap();
        if !journal::verify(key, raw) {
            let unsupported = raw[0] == journal::VERSION_DEMO && !journal::is_known_kind(raw[1]) && journal::mac_ok(key, raw);
            verdicts.push(if unsupported { Verdict::UnsupportedKind } else { Verdict::BadMac });
            continue;
        }
        let e = journal::decode(raw).expect("verify implies decode");
        let hash = ckb_blake2b(raw);
        // Spec §8.3: a voucher, override or lockout entry must carry this Vault's merchant_id.
        if (e.kind == KIND_VOUCHER_REDEEM || e.kind == KIND_PRICE_OVERRIDE || e.kind == KIND_STAFF_LOCKOUT) && e.merchant_id != *merchant {
            verdicts.push(Verdict::BadMac); continue;
        }
        match e.kind {
            KIND_VOUCHER_REDEEM => {
                let v_id: [u8; 16] = e.reserve_id[..16].try_into().unwrap();
                let Some(v) = vindex.get(&v_id) else { verdicts.push(Verdict::UnknownVoucher); continue; };
                if let Some((_, h)) = v.settled.iter().find(|(c, _)| *c == e.tmc) {
                    verdicts.push(if *h == hash { Verdict::Duplicate } else { Verdict::DoubleRedeem }); continue;
                }
                if v.void { verdicts.push(Verdict::VoucherVoid); continue; }
                let run = vrun.entry(v_id).or_default();
                if let Some(h) = run.seen.get(&e.tmc) {
                    verdicts.push(if *h == hash { Verdict::Duplicate } else { Verdict::DoubleRedeem }); continue;
                }
                if run.max_counter.map_or(false, |m| e.tmc <= m) { verdicts.push(Verdict::StaleCounter); continue; }
                if run.accepted >= v.count as u32 { verdicts.push(Verdict::DoubleRedeem); continue; }
                run.max_counter = Some(e.tmc); run.accepted += 1; run.seen.insert(e.tmc, hash);
                redeems.push(RedeemOut { index: i as u32, terminal_id: e.terminal_id, voucher_id: v_id, counter: e.tmc,
                    key_id: e.pad[..8].try_into().unwrap(), entry_hash: hash });
                verdicts.push(Verdict::Accepted);
            }
            KIND_PRICE_OVERRIDE => {
                overrides.push(OverrideOut { index: i as u32, terminal_id: e.terminal_id, product_hash: e.reserve_id[..8].try_into().unwrap(),
                    old_price: e.amount_units, new_price: e.balance_after, currency: e.pad[8..11].try_into().unwrap(),
                    key_id: e.pad[..8].try_into().unwrap(), entry_hash: hash });
                verdicts.push(Verdict::Accepted);
            }
            KIND_STAFF_LOCKOUT => {
                lockouts.push(LockoutOut { index: i as u32, terminal_id: e.terminal_id, uid: e.uid, key_id: e.pad[..8].try_into().unwrap(),
                    pin_ver: u16::from_le_bytes([e.pad[8], e.pad[9]]), attempts: e.amount_units, entry_hash: hash });
                verdicts.push(Verdict::Accepted);
            }
            _ => {
                let card = match index.get(&e.reserve_id) {
                    Some(c) if !c.revoked => *c,
                    _ => { verdicts.push(Verdict::UnknownCard); continue; }
                };
                if e.tmc <= card.settled_counter { verdicts.push(Verdict::Duplicate); continue; }
                let run = running.entry(e.reserve_id).or_insert_with(|| {
                    order.push(e.reserve_id);
                    Running { net: 0, max_tmc: None, accepted: 0 }
                });
                if run.max_tmc.map_or(false, |m| e.tmc <= m) { verdicts.push(Verdict::StaleCounter); continue; }
                let proposed = run.net + e.amount_units as i64;
                if proposed > card.balance_units as i64 { verdicts.push(Verdict::OverBalance); continue; }
                run.net = proposed; run.max_tmc = Some(e.tmc); run.accepted += 1;
                verdicts.push(Verdict::Accepted);
            }
        }
    }

    let nets = order.into_iter().filter_map(|rid| {
        let r = &running[&rid];
        (r.accepted > 0).then(|| CardNet { reserve_id: rid, net_units: r.net, max_tmc: r.max_tmc.unwrap(), accepted: r.accepted })
    }).collect();
    Ok(BatchResult { verdicts, nets, redeems, overrides, lockouts })
}


#[cfg(test)]
mod tests {
    use super::*;
    use crate::prepaid::journal::{attest, JournalEntry, KIND_DEBIT, KIND_REFUND, KIND_STAFF_LOCKOUT};

    fn key() -> [u8; 16] { core::array::from_fn(|i| i as u8) }
    /// The merchant every test entry carries.
    const M: [u8; 8] = [0x11; 8];
    fn rid(b: u8) -> [u8; 32] { [b; 32] }
    fn entry(rid_b: u8, tmc: u32, amount: i32) -> Vec<u8> {
        let mut e = JournalEntry { version: 0x02, kind: if amount < 0 { KIND_REFUND } else { KIND_DEBIT }, terminal_id: 7,
            merchant_id: [0x11; 8], uid: [4, 1, 2, 3, 4, 5, 6], reserve_id: rid(rid_b), tmc, amount_units: amount,
            balance_after: 0, ts: 0, tmv: [0; 8], sale_ref: [0; 8], pad: [0; 12] };
        attest(&key(), &mut e).to_vec()
    }
    fn card(b: u8, balance: i32, settled: u32, revoked: bool) -> CardState {
        CardState { reserve_id: rid(b), balance_units: balance, settled_counter: settled, revoked }
    }
    fn cat(v: &[Vec<u8>]) -> Vec<u8> { v.concat() }

    #[test]
    fn good_entries_net_per_card() {
        let batch = cat(&[entry(1, 3, 1500), entry(1, 4, -500), entry(2, 1, 700)]);
        let r = verify_batch(&key(), &M, &batch, &[card(1, 100_000, 2, false), card(2, 100_000, 0, false)], &[]).unwrap();
        assert_eq!(r.verdicts, vec![Verdict::Accepted; 3]);
        assert_eq!(r.nets.len(), 2);
        let n1 = r.nets.iter().find(|n| n.reserve_id == rid(1)).unwrap();
        assert_eq!((n1.net_units, n1.max_tmc, n1.accepted), (1000, 4, 2));
        let n2 = r.nets.iter().find(|n| n.reserve_id == rid(2)).unwrap();
        assert_eq!((n2.net_units, n2.max_tmc, n2.accepted), (700, 1, 1));
    }

    #[test]
    fn bad_mac_wrong_key_and_tamper() {
        let mut e = entry(1, 3, 1500);
        e[56] ^= 1;
        let r = verify_batch(&key(), &M, &e, &[card(1, 100_000, 0, false)], &[]).unwrap();
        assert_eq!(r.verdicts, vec![Verdict::BadMac]);
        assert!(r.nets.is_empty());
        let mut k2 = key(); k2[0] ^= 1;
        let r = verify_batch(&k2, &M, &entry(1, 3, 1500), &[card(1, 100_000, 0, false)], &[]).unwrap();
        assert_eq!(r.verdicts, vec![Verdict::BadMac]);
    }

    #[test]
    fn bad_mac_wins_over_unknown_card() {
        let mut e = entry(9, 3, 1500);
        e[56] ^= 1;
        let r = verify_batch(&key(), &M, &e, &[], &[]).unwrap();
        assert_eq!(r.verdicts, vec![Verdict::BadMac]);
    }

    #[test]
    fn unknown_and_revoked_cards() {
        let r = verify_batch(&key(), &M, &entry(9, 1, 100), &[card(1, 100_000, 0, false)], &[]).unwrap();
        assert_eq!(r.verdicts, vec![Verdict::UnknownCard]);
        let r = verify_batch(&key(), &M, &entry(1, 1, 100), &[card(1, 100_000, 0, true)], &[]).unwrap();
        assert_eq!(r.verdicts, vec![Verdict::UnknownCard], "revoked must read as unknown");
    }

    #[test]
    fn tmc_at_or_below_settled_is_duplicate() {
        let batch = cat(&[entry(1, 5, 100), entry(1, 4, 100), entry(1, 6, 100)]);
        let r = verify_batch(&key(), &M, &batch, &[card(1, 100_000, 5, false)], &[]).unwrap();
        assert_eq!(r.verdicts, vec![Verdict::Duplicate, Verdict::Duplicate, Verdict::Accepted]);
        assert_eq!(r.nets[0].net_units, 100);
    }

    #[test]
    fn out_of_order_tmc_within_batch_is_stale() {
        let batch = cat(&[entry(1, 7, 100), entry(1, 6, 100), entry(1, 7, 100), entry(1, 8, 100)]);
        let r = verify_batch(&key(), &M, &batch, &[card(1, 100_000, 0, false)], &[]).unwrap();
        assert_eq!(r.verdicts, vec![Verdict::Accepted, Verdict::StaleCounter, Verdict::StaleCounter, Verdict::Accepted]);
        assert_eq!((r.nets[0].net_units, r.nets[0].max_tmc, r.nets[0].accepted), (200, 8, 2));
    }

    #[test]
    fn cumulative_net_above_balance_is_over_balance() {
        let batch = cat(&[entry(1, 1, 60_000), entry(1, 2, 50_000), entry(1, 3, -20_000), entry(1, 4, 50_000)]);
        let r = verify_batch(&key(), &M, &batch, &[card(1, 100_000, 0, false)], &[]).unwrap();
        // 60k ok (60k), +50k would be 110k > 100k → OverBalance and NOT counted, −20k → 40k, +50k → 90k ok
        assert_eq!(r.verdicts, vec![Verdict::Accepted, Verdict::OverBalance, Verdict::Accepted, Verdict::Accepted]);
        assert_eq!((r.nets[0].net_units, r.nets[0].max_tmc), (90_000, 4));
    }

    #[test]
    fn length_not_multiple_of_96_is_an_error() {
        assert_eq!(verify_batch(&key(), &M, &[0u8; 95], &[], &[]).unwrap_err(), BatchError::BadLength);
        let r = verify_batch(&key(), &M, &[], &[], &[]).unwrap();
        assert!(r.verdicts.is_empty() && r.nets.is_empty());
    }

    #[test]
    fn refund_only_card_has_negative_net() {
        let r = verify_batch(&key(), &M, &entry(1, 2, -300), &[card(1, 1_000, 1, false)], &[]).unwrap();
        assert_eq!(r.verdicts, vec![Verdict::Accepted]);
        assert_eq!(r.nets[0].net_units, -300);
    }

    use crate::prepaid::journal::{KIND_PRICE_OVERRIDE, KIND_VOUCHER_REDEEM};
    use crate::prepaid::reserve::ckb_blake2b;

    fn vid(b: u8) -> [u8; 16] { [b; 16] }
    fn redeem(v: u8, counter: u32, terminal: u16) -> Vec<u8> {
        let mut rid = [0u8; 32]; rid[..16].copy_from_slice(&vid(v)); rid[16..24].copy_from_slice(&[0x5A; 8]);
        let mut pad = [0u8; 12]; pad[..8].copy_from_slice(&[0x77; 8]);
        let mut e = JournalEntry { version: 0x02, kind: KIND_VOUCHER_REDEEM, terminal_id: terminal, merchant_id: [0x11; 8],
            uid: [4, 1, 2, 3, 4, 5, 6], reserve_id: rid, tmc: counter, amount_units: 1, balance_after: 0, ts: terminal as u32,
            tmv: [0; 8], sale_ref: [0; 8], pad };
        attest(&key(), &mut e).to_vec()
    }
    fn vstate(v: u8, count: u8, void: bool, settled: &[Vec<u8>]) -> VoucherState {
        VoucherState { voucher_id: vid(v), count, void,
            settled: settled.iter().map(|raw| (u32::from_le_bytes(raw[52..56].try_into().unwrap()), ckb_blake2b(raw))).collect() }
    }

    #[test]
    fn a_redeem_is_accepted_once_and_reported() {
        let r = verify_batch(&key(), &M, &redeem(1, 1, 3), &[], &[vstate(1, 1, false, &[])]).unwrap();
        assert_eq!(r.verdicts, vec![Verdict::Accepted]);
        assert_eq!(r.redeems.len(), 1);
        assert_eq!((r.redeems[0].index, r.redeems[0].terminal_id, r.redeems[0].voucher_id, r.redeems[0].counter), (0, 3, vid(1), 1));
        assert_eq!(r.redeems[0].entry_hash, ckb_blake2b(&redeem(1, 1, 3)));
        assert!(r.nets.is_empty(), "redeems never touch card nets");
    }

    #[test]
    fn identical_resend_is_duplicate_but_a_different_entry_on_a_settled_counter_is_double_redeem() {
        let first = redeem(1, 1, 3);
        let v = vstate(1, 0, false, &[first.clone()]);
        assert_eq!(verify_batch(&key(), &M, &first, &[], &[v.clone()]).unwrap().verdicts, vec![Verdict::Duplicate]);
        let copy_elsewhere = redeem(1, 1, 9);                     // same counter, other terminal → different bytes
        assert_eq!(verify_batch(&key(), &M, &copy_elsewhere, &[], &[v]).unwrap().verdicts, vec![Verdict::DoubleRedeem]);
    }

    #[test]
    fn within_one_batch_same_counter_twice_is_double_redeem_and_identical_is_duplicate() {
        let a = redeem(1, 1, 3); let b = redeem(1, 1, 9);
        let r = verify_batch(&key(), &M, &[a.clone(), b, a].concat(), &[], &[vstate(1, 5, false, &[])]).unwrap();
        assert_eq!(r.verdicts, vec![Verdict::Accepted, Verdict::DoubleRedeem, Verdict::Duplicate]);
        assert_eq!(r.redeems.len(), 1);
    }

    #[test]
    fn more_redeems_than_uses_left_is_double_redeem() {
        let r = verify_batch(&key(), &M, &[redeem(1, 1, 3), redeem(1, 2, 3)].concat(), &[], &[vstate(1, 1, false, &[])]).unwrap();
        assert_eq!(r.verdicts, vec![Verdict::Accepted, Verdict::DoubleRedeem]);
    }

    #[test]
    fn out_of_order_counter_in_a_batch_is_stale_and_unknown_and_void_vouchers_are_named() {
        let r = verify_batch(&key(), &M, &[redeem(1, 3, 3), redeem(1, 2, 3)].concat(), &[], &[vstate(1, 5, false, &[])]).unwrap();
        assert_eq!(r.verdicts, vec![Verdict::Accepted, Verdict::StaleCounter]);
        assert_eq!(verify_batch(&key(), &M, &redeem(2, 1, 3), &[], &[]).unwrap().verdicts, vec![Verdict::UnknownVoucher]);
        assert_eq!(verify_batch(&key(), &M, &redeem(1, 1, 3), &[], &[vstate(1, 1, true, &[])]).unwrap().verdicts, vec![Verdict::VoucherVoid]);
    }

    #[test]
    fn overrides_are_accepted_and_reported() {
        let mut rid = [0u8; 32]; rid[..8].copy_from_slice(&[0x5A; 8]);
        let mut pad = [0u8; 12]; pad[..8].copy_from_slice(&[0x77; 8]); pad[8..11].copy_from_slice(b"AUD");
        let mut e = JournalEntry { version: 0x02, kind: KIND_PRICE_OVERRIDE, terminal_id: 4, merchant_id: [0x11; 8], uid: [4, 9, 9, 9, 9, 9, 9],
            reserve_id: rid, tmc: 0, amount_units: 500, balance_after: 0, ts: 0, tmv: [0; 8], sale_ref: [0; 8], pad };
        let raw = attest(&key(), &mut e).to_vec();
        let r = verify_batch(&key(), &M, &raw, &[], &[]).unwrap();
        assert_eq!(r.verdicts, vec![Verdict::Accepted]);
        let o = &r.overrides[0];
        assert_eq!((o.index, o.terminal_id, o.product_hash, o.old_price, o.new_price, o.currency, o.key_id), (0, 4, [0x5A; 8], 500, 0, *b"AUD", [0x77; 8]));
        assert_eq!(o.entry_hash, ckb_blake2b(&raw));
    }

    #[test]
    fn unknown_kind_with_good_mac_is_unsupported_and_with_bad_mac_is_bad_mac() {
        let mut e = JournalEntry { version: 0x02, kind: 0x07, terminal_id: 1, merchant_id: [0x11; 8], uid: [4, 1, 2, 3, 4, 5, 6],
            reserve_id: [0; 32], tmc: 1, amount_units: 1, balance_after: 0, ts: 0, tmv: [0; 8], sale_ref: [0; 8], pad: [0; 12] };
        let raw = attest(&key(), &mut e).to_vec();
        assert_eq!(verify_batch(&key(), &M, &raw, &[], &[]).unwrap().verdicts, vec![Verdict::UnsupportedKind]);
        let mut t = raw; t[30] ^= 1;
        assert_eq!(verify_batch(&key(), &M, &t, &[], &[]).unwrap().verdicts, vec![Verdict::BadMac]);
    }

    #[test]
    fn voucher_and_override_entries_never_reach_card_accounting() {
        // A card whose reserve_id equals the redeem's 32-byte reserve_id field: a kind-3 entry must not debit it.
        let redeem_raw = redeem(1, 1, 3);
        let mut rid32 = [0u8; 32]; rid32.copy_from_slice(&redeem_raw[20..52]);
        let c = CardState { reserve_id: rid32, balance_units: 100_000, settled_counter: 0, revoked: false };
        let r = verify_batch(&key(), &M, &redeem_raw, &[c.clone()], &[]).unwrap();
        assert_eq!(r.verdicts, vec![Verdict::UnknownVoucher]);
        assert!(r.nets.is_empty());
        let r = verify_batch(&key(), &M, &redeem_raw, &[c], &[vstate(1, 1, false, &[])]).unwrap();
        assert_eq!(r.verdicts, vec![Verdict::Accepted]);
        assert!(r.nets.is_empty(), "accepted redeem must not appear in card nets");
    }

    #[test]
    fn identical_resend_of_a_settled_redeem_stays_duplicate_after_the_voucher_is_voided() {
        let first = redeem(1, 1, 3);
        let v = vstate(1, 0, true, &[first.clone()]);
        assert_eq!(verify_batch(&key(), &M, &first, &[], &[v.clone()]).unwrap().verdicts, vec![Verdict::Duplicate]);
        assert_eq!(verify_batch(&key(), &M, &redeem(1, 2, 3), &[], &[v.clone()]).unwrap().verdicts, vec![Verdict::VoucherVoid]);
        assert_eq!(verify_batch(&key(), &M, &redeem(1, 1, 9), &[], &[v]).unwrap().verdicts, vec![Verdict::DoubleRedeem]);
    }

    #[test]
    fn a_new_redeem_after_the_uses_are_spent_in_earlier_batches_is_double_redeem() {
        // `count` is the uses left; a voucher whose only use is already settled has count 0.
        let v = vstate(1, 0, false, &[redeem(1, 1, 3)]);
        let r = verify_batch(&key(), &M, &redeem(1, 2, 3), &[], &[v]).unwrap();
        assert_eq!(r.verdicts, vec![Verdict::DoubleRedeem]);
        assert!(r.redeems.is_empty());
    }

    #[test]
    fn a_redeem_reports_its_key_id() {
        let r = verify_batch(&key(), &M, &redeem(1, 1, 3), &[], &[vstate(1, 1, false, &[])]).unwrap();
        assert_eq!(r.redeems[0].key_id, [0x77; 8]);
    }

    fn with_merchant(raw: &[u8], merchant: [u8; 8]) -> Vec<u8> {
        let mut e = crate::prepaid::journal::decode(raw.try_into().unwrap()).unwrap();
        e.merchant_id = merchant;
        attest(&key(), &mut e).to_vec()
    }

    #[test]
    fn a_redeem_or_override_from_another_merchant_is_bad_mac() {
        let mut rid = [0u8; 32]; rid[..8].copy_from_slice(&[0x5A; 8]);
        let mut pad = [0u8; 12]; pad[..8].copy_from_slice(&[0x77; 8]); pad[8..11].copy_from_slice(b"AUD");
        let mut o = JournalEntry { version: 0x02, kind: KIND_PRICE_OVERRIDE, terminal_id: 4, merchant_id: [0x11; 8], uid: [4, 9, 9, 9, 9, 9, 9],
            reserve_id: rid, tmc: 0, amount_units: 500, balance_after: 0, ts: 0, tmv: [0; 8], sale_ref: [0; 8], pad };
        let ovr = attest(&key(), &mut o).to_vec();
        let other = [0x22; 8];
        let batch = [with_merchant(&redeem(1, 1, 3), other), with_merchant(&ovr, other), redeem(1, 2, 3), ovr].concat();
        let r = verify_batch(&key(), &M, &batch, &[], &[vstate(1, 5, false, &[])]).unwrap();
        assert_eq!(r.verdicts, vec![Verdict::BadMac, Verdict::BadMac, Verdict::Accepted, Verdict::Accepted]);
        assert_eq!((r.redeems.len(), r.redeems[0].index), (1, 2));
        assert_eq!((r.overrides.len(), r.overrides[0].index), (1, 3));
    }

    fn lockout_raw(merchant: [u8; 8], pin_ver: u16) -> Vec<u8> {
        let mut pad = [0u8; 12]; pad[..8].copy_from_slice(&[0xC0, 0xFF, 0xEE, 1, 2, 3, 4, 5]); pad[8..10].copy_from_slice(&pin_ver.to_le_bytes());
        let mut e = JournalEntry { version: 0x02, kind: KIND_STAFF_LOCKOUT, terminal_id: 9, merchant_id: merchant, uid: [4, 1, 2, 3, 4, 5, 6],
            reserve_id: [0; 32], tmc: 0, amount_units: 3, balance_after: 0, ts: 0, tmv: [0; 8], sale_ref: [0; 8], pad };
        attest(&key(), &mut e).to_vec()
    }
    #[test] fn lockout_is_accepted_and_reported_with_key_id_pin_ver_and_terminal() {
        let raw = lockout_raw(M, 7);
        let r = verify_batch(&key(), &M, &raw, &[], &[]).unwrap();
        assert_eq!(r.verdicts, vec![Verdict::Accepted]); assert!(r.nets.is_empty() && r.redeems.is_empty() && r.overrides.is_empty());
        let l = &r.lockouts[0];
        assert_eq!((l.index, l.terminal_id, l.pin_ver, l.attempts), (0, 9, 7, 3));
        assert_eq!(l.key_id, [0xC0, 0xFF, 0xEE, 1, 2, 3, 4, 5]); assert_eq!(l.uid, [4, 1, 2, 3, 4, 5, 6]);
        assert_eq!(l.entry_hash, ckb_blake2b(&raw));
    }
    #[test] fn lockout_from_another_merchant_is_bad_mac() {
        assert_eq!(verify_batch(&key(), &M, &lockout_raw([0x22; 8], 1), &[], &[]).unwrap().verdicts, vec![Verdict::BadMac]);
    }
    #[test] fn two_identical_lockouts_in_one_batch_are_both_accepted_here() {
        // dedupe by entry hash is the Vault's job (prepaid_journal_seen), as for 0x04
        let mut two = lockout_raw(M, 1); two.extend(lockout_raw(M, 1));
        assert_eq!(verify_batch(&key(), &M, &two, &[], &[]).unwrap().verdicts, vec![Verdict::Accepted, Verdict::Accepted]);
    }
}
