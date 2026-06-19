//! Spend accounting. The in-memory store is for Rust unit tests and the testnet
//! harness; production persistence is the Kotlin SQLCipher layer (Plan B), which
//! implements the same view/apply contract inside an atomic DB transaction.

use crate::agent::types::LedgerView;
use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SpendRecord {
    pub token_id: String,
    pub asset: String,
    pub amount: i64,
    pub unix: i64,
    pub nonce: String,
}

pub trait LedgerStore {
    /// Current usage for (token, asset). `window_seconds == 0` -> `window_spent` is 0.
    fn view(&self, token_id: &str, asset: &str, window_seconds: i64, now_unix: i64) -> LedgerView;
    /// Record a spend. Rejects a duplicate (token_id, nonce).
    fn apply(&mut self, rec: SpendRecord) -> Result<(), String>;
}

#[derive(Debug, Default, Serialize, Deserialize)]
pub struct InMemoryLedger {
    records: Vec<SpendRecord>,
}

impl InMemoryLedger {
    pub fn new() -> Self {
        Self { records: Vec::new() }
    }
    pub fn to_json(&self) -> String {
        serde_json::to_string(self).unwrap_or_else(|_| "{\"records\":[]}".into())
    }
    pub fn from_json(s: &str) -> Self {
        serde_json::from_str(s).unwrap_or_default()
    }
}

impl LedgerStore for InMemoryLedger {
    fn view(&self, token_id: &str, asset: &str, window_seconds: i64, now_unix: i64) -> LedgerView {
        let mut cumulative: i64 = 0;
        let mut window: i64 = 0;
        let win_start = now_unix.saturating_sub(window_seconds.max(0));
        let mut nonce_seen = false;
        for r in &self.records {
            if r.token_id == token_id && r.asset == asset {
                cumulative = cumulative.saturating_add(r.amount);
                if window_seconds > 0 && r.unix >= win_start && r.unix <= now_unix {
                    window = window.saturating_add(r.amount);
                }
            }
        }
        // nonce uniqueness is per token across assets
        for r in &self.records {
            if r.token_id == token_id {
                // caller fills the real nonce check via `apply`; view reports false here
            }
        }
        let _ = &mut nonce_seen;
        LedgerView { cumulative_spent: cumulative, window_spent: window, nonce_seen }
    }

    fn apply(&mut self, rec: SpendRecord) -> Result<(), String> {
        if self
            .records
            .iter()
            .any(|r| r.token_id == rec.token_id && r.nonce == rec.nonce)
        {
            return Err(format!("replay: nonce {} already used", rec.nonce));
        }
        self.records.push(rec);
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn rec(amount: i64, unix: i64, nonce: &str) -> SpendRecord {
        SpendRecord { token_id: "t1".into(), asset: "CKB".into(), amount, unix, nonce: nonce.into() }
    }

    #[test]
    fn cumulative_sums() {
        let mut l = InMemoryLedger::new();
        l.apply(rec(10, 100, "a")).unwrap();
        l.apply(rec(5, 200, "b")).unwrap();
        let v = l.view("t1", "CKB", 0, 300);
        assert_eq!(v.cumulative_spent, 15);
        assert_eq!(v.window_spent, 0); // window_seconds==0
    }

    #[test]
    fn window_only_counts_recent() {
        let mut l = InMemoryLedger::new();
        l.apply(rec(10, 100, "a")).unwrap();
        l.apply(rec(7, 250, "b")).unwrap();
        // window 100s ending at 300 -> only the unix=250 record (>=200) counts
        let v = l.view("t1", "CKB", 100, 300);
        assert_eq!(v.window_spent, 7);
        assert_eq!(v.cumulative_spent, 17);
    }

    #[test]
    fn rejects_replayed_nonce() {
        let mut l = InMemoryLedger::new();
        l.apply(rec(10, 100, "dup")).unwrap();
        assert!(l.apply(rec(1, 110, "dup")).is_err());
    }

    #[test]
    fn json_roundtrip() {
        let mut l = InMemoryLedger::new();
        l.apply(rec(10, 100, "a")).unwrap();
        let j = l.to_json();
        let l2 = InMemoryLedger::from_json(&j);
        assert_eq!(l2.view("t1", "CKB", 0, 300).cumulative_spent, 10);
    }
}
