//! Agent Gateway authorization core.
//!
//! `token` mints/verifies scoped biscuit tokens; `policy::decide` turns a token +
//! an agent intent + current ledger usage into an ALLOW_AUTO / NEED_APPROVAL / DENY
//! decision. `decide` is pure and read-only — atomic debit-then-sign happens in the
//! Kotlin (SQLCipher) layer. `ledger` provides an in-memory store for tests/harness.

pub mod types;
pub mod token;
pub mod ledger;
pub mod policy;

pub use types::{CapInfo, Decision, Intent, LedgerView, RequestCtx, Scope, TokenSpec};
pub use token::{AgentKeyPair, agent_root_keypair, mint_token, token_caps, token_id_of};
pub use ledger::{InMemoryLedger, LedgerStore, SpendRecord};
pub use policy::decide;

/// Errors surfaced across the UniFFI boundary for token minting/inspection.
/// Policy *denials* are NOT errors — they are returned as `Decision::Deny`.
#[derive(Debug, thiserror::Error, uniffi::Error)]
#[uniffi(flat_error)]
pub enum AgentError {
    #[error("Token error: {0}")]
    TokenError(String),
    #[error("Invalid spec: {0}")]
    InvalidSpec(String),
}
