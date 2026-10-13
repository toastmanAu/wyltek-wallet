//! Settlement verifier for version-0x02 (terminal-attested) journal entries. D3 spec §6, demo-tier §6.3.
//! Verdict order per entry: length → MAC (covers decode + sign rule) → known & not revoked →
//! tmc above settled → tmc above this batch's running max for the card → cumulative net within balance.

use super::journal::{self, ENTRY_SIZE};
use std::collections::HashMap;

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct CardState { pub reserve_id: [u8; 32], pub balance_units: i32, pub settled_counter: u32, pub revoked: bool }

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Verdict { Accepted, Duplicate, BadMac, UnknownCard, StaleCounter, OverBalance }

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct CardNet { pub reserve_id: [u8; 32], pub net_units: i64, pub max_tmc: u32, pub accepted: u32 }

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct BatchResult { pub verdicts: Vec<Verdict>, pub nets: Vec<CardNet> }

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum BatchError { BadLength }

struct Running { net: i64, max_tmc: Option<u32>, accepted: u32 }

pub fn verify_batch(key: &[u8; 16], entries: &[u8], cards: &[CardState]) -> Result<BatchResult, BatchError> {
    if entries.len() % ENTRY_SIZE != 0 { return Err(BatchError::BadLength); }
    let index: HashMap<[u8; 32], &CardState> = cards.iter().map(|c| (c.reserve_id, c)).collect();
    let mut running: HashMap<[u8; 32], Running> = HashMap::new();
    let mut order: Vec<[u8; 32]> = Vec::new();
    let mut verdicts = Vec::with_capacity(entries.len() / ENTRY_SIZE);

    for chunk in entries.chunks_exact(ENTRY_SIZE) {
        let raw: &[u8; ENTRY_SIZE] = chunk.try_into().unwrap();
        if !journal::verify(key, raw) { verdicts.push(Verdict::BadMac); continue; }
        let e = journal::decode(raw).expect("verify implies decode");
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
        run.net = proposed;
        run.max_tmc = Some(e.tmc);
        run.accepted += 1;
        verdicts.push(Verdict::Accepted);
    }

    let nets = order.into_iter().filter_map(|rid| {
        let r = &running[&rid];
        (r.accepted > 0).then(|| CardNet { reserve_id: rid, net_units: r.net, max_tmc: r.max_tmc.unwrap(), accepted: r.accepted })
    }).collect();
    Ok(BatchResult { verdicts, nets })
}


#[cfg(test)]
mod tests {
    use super::*;
    use crate::prepaid::journal::{attest, JournalEntry, KIND_DEBIT, KIND_REFUND};

    fn key() -> [u8; 16] { core::array::from_fn(|i| i as u8) }
    fn rid(b: u8) -> [u8; 32] { [b; 32] }
    fn entry(rid_b: u8, tmc: u32, amount: i32) -> Vec<u8> {
        let mut e = JournalEntry { version: 0x02, kind: if amount < 0 { KIND_REFUND } else { KIND_DEBIT }, terminal_id: 7,
            merchant_id: [0x11; 8], uid: [4, 1, 2, 3, 4, 5, 6], reserve_id: rid(rid_b), tmc, amount_units: amount,
            balance_after: 0, ts: 0, tmv: [0; 8], sale_ref: [0; 8] };
        attest(&key(), &mut e).to_vec()
    }
    fn card(b: u8, balance: i32, settled: u32, revoked: bool) -> CardState {
        CardState { reserve_id: rid(b), balance_units: balance, settled_counter: settled, revoked }
    }
    fn cat(v: &[Vec<u8>]) -> Vec<u8> { v.concat() }

    #[test]
    fn good_entries_net_per_card() {
        let batch = cat(&[entry(1, 3, 1500), entry(1, 4, -500), entry(2, 1, 700)]);
        let r = verify_batch(&key(), &batch, &[card(1, 100_000, 2, false), card(2, 100_000, 0, false)]).unwrap();
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
        let r = verify_batch(&key(), &e, &[card(1, 100_000, 0, false)]).unwrap();
        assert_eq!(r.verdicts, vec![Verdict::BadMac]);
        assert!(r.nets.is_empty());
        let mut k2 = key(); k2[0] ^= 1;
        let r = verify_batch(&k2, &entry(1, 3, 1500), &[card(1, 100_000, 0, false)]).unwrap();
        assert_eq!(r.verdicts, vec![Verdict::BadMac]);
    }

    #[test]
    fn bad_mac_wins_over_unknown_card() {
        let mut e = entry(9, 3, 1500);
        e[56] ^= 1;
        let r = verify_batch(&key(), &e, &[]).unwrap();
        assert_eq!(r.verdicts, vec![Verdict::BadMac]);
    }

    #[test]
    fn unknown_and_revoked_cards() {
        let r = verify_batch(&key(), &entry(9, 1, 100), &[card(1, 100_000, 0, false)]).unwrap();
        assert_eq!(r.verdicts, vec![Verdict::UnknownCard]);
        let r = verify_batch(&key(), &entry(1, 1, 100), &[card(1, 100_000, 0, true)]).unwrap();
        assert_eq!(r.verdicts, vec![Verdict::UnknownCard], "revoked must read as unknown");
    }

    #[test]
    fn tmc_at_or_below_settled_is_duplicate() {
        let batch = cat(&[entry(1, 5, 100), entry(1, 4, 100), entry(1, 6, 100)]);
        let r = verify_batch(&key(), &batch, &[card(1, 100_000, 5, false)]).unwrap();
        assert_eq!(r.verdicts, vec![Verdict::Duplicate, Verdict::Duplicate, Verdict::Accepted]);
        assert_eq!(r.nets[0].net_units, 100);
    }

    #[test]
    fn out_of_order_tmc_within_batch_is_stale() {
        let batch = cat(&[entry(1, 7, 100), entry(1, 6, 100), entry(1, 7, 100), entry(1, 8, 100)]);
        let r = verify_batch(&key(), &batch, &[card(1, 100_000, 0, false)]).unwrap();
        assert_eq!(r.verdicts, vec![Verdict::Accepted, Verdict::StaleCounter, Verdict::StaleCounter, Verdict::Accepted]);
        assert_eq!((r.nets[0].net_units, r.nets[0].max_tmc, r.nets[0].accepted), (200, 8, 2));
    }

    #[test]
    fn cumulative_net_above_balance_is_over_balance() {
        let batch = cat(&[entry(1, 1, 60_000), entry(1, 2, 50_000), entry(1, 3, -20_000), entry(1, 4, 50_000)]);
        let r = verify_batch(&key(), &batch, &[card(1, 100_000, 0, false)]).unwrap();
        // 60k ok (60k), +50k would be 110k > 100k → OverBalance and NOT counted, −20k → 40k, +50k → 90k ok
        assert_eq!(r.verdicts, vec![Verdict::Accepted, Verdict::OverBalance, Verdict::Accepted, Verdict::Accepted]);
        assert_eq!((r.nets[0].net_units, r.nets[0].max_tmc), (90_000, 4));
    }

    #[test]
    fn length_not_multiple_of_96_is_an_error() {
        assert_eq!(verify_batch(&key(), &[0u8; 95], &[]).unwrap_err(), BatchError::BadLength);
        let r = verify_batch(&key(), &[], &[]).unwrap();
        assert!(r.verdicts.is_empty() && r.nets.is_empty());
    }

    #[test]
    fn refund_only_card_has_negative_net() {
        let r = verify_batch(&key(), &entry(1, 2, -300), &[card(1, 1_000, 1, false)]).unwrap();
        assert_eq!(r.verdicts, vec![Verdict::Accepted]);
        assert_eq!(r.nets[0].net_units, -300);
    }
}
