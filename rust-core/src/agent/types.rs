//! UniFFI-facing value types for the Agent Gateway.

/// The operations a token may authorize. `Fiber` is reserved (schema only).
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum Scope {
    SendCkb,
    SendUdt,
    Dao,
    Messaging,
    Fiber,
}

impl Scope {
    /// Stable Datalog/string token used inside biscuit facts and intents.
    pub fn as_tag(&self) -> &'static str {
        match self {
            Scope::SendCkb => "send_ckb",
            Scope::SendUdt => "send_udt",
            Scope::Dao => "dao",
            Scope::Messaging => "messaging",
            Scope::Fiber => "fiber",
        }
    }
}

/// One per-asset cap line carried by a token.
/// `window_seconds == 0` means "no rolling window" (only the cumulative cap applies).
#[derive(Debug, Clone, uniffi::Record)]
pub struct CapInfo {
    pub asset: String,
    pub cumulative: i64,
    pub window_seconds: i64,
    pub window_limit: i64,
    pub auto_limit: i64,
}

/// Parameters the wallet UI supplies when minting a token.
#[derive(Debug, Clone, uniffi::Record)]
pub struct TokenSpec {
    /// CKB address (testnet) the token may act for. Bound into the token.
    pub account: String,
    /// Granted operations.
    pub scopes: Vec<Scope>,
    /// Per-asset caps + auto-limits.
    pub caps: Vec<CapInfo>,
    /// Expiry unix-seconds; `None` = infinite validity.
    pub ttl_unix: Option<i64>,
    /// Recipient allowlist (CKB addresses); empty = any valid recipient.
    pub allow_to: Vec<String>,
    /// Source IP/host allowlist; empty = any source.
    pub allow_ip: Vec<String>,
}

/// What the agent asks the wallet to do. Built by the agent; the device builds the
/// actual transaction from this — the agent never supplies a pre-built/signed tx.
#[derive(Debug, Clone, uniffi::Record)]
pub struct Intent {
    /// One of `Scope::as_tag` values.
    pub op: String,
    pub asset: String,
    /// Recipient address ("" for ops without a recipient, e.g. some DAO ops).
    pub to: String,
    pub amount: i64,
    /// Client-chosen unique string; ledger rejects replays.
    pub nonce: String,
    /// Granular action within a scope (e.g. "deposit" | "withdraw" | "claim" for op="dao"). None for simple ops.
    pub action: Option<String>,
    /// Reference to an existing cell for actions that target one, as "txhash:index" (e.g. a DAO deposit outpoint). None otherwise.
    pub dao_ref: Option<String>,
}

/// Request-time context the device supplies to `decide`.
#[derive(Debug, Clone, uniffi::Record)]
pub struct RequestCtx {
    /// The account the request targets — must equal the token's bound account.
    pub account: String,
    pub source_ip: String,
    pub now_unix: i64,
}

/// Current ledger usage for the intent's asset, computed by the caller (Kotlin DB
/// or `InMemoryLedger`) BEFORE calling `decide`. `window_spent` is the sum of spends
/// for this asset within the token's window ending at `now_unix`.
#[derive(Debug, Clone, uniffi::Record)]
pub struct LedgerView {
    pub cumulative_spent: i64,
    pub window_spent: i64,
    /// True if this token_id+nonce pair has already been recorded (replay).
    pub nonce_seen: bool,
}

/// The policy outcome. Spend details are echoed back so the caller can debit.
#[derive(Debug, Clone, PartialEq, uniffi::Enum)]
pub enum Decision {
    Deny { reason: String },
    AllowAuto { token_id: String, asset: String, amount: i64 },
    NeedApproval { token_id: String, asset: String, amount: i64 },
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn scope_tags_are_stable() {
        assert_eq!(Scope::SendCkb.as_tag(), "send_ckb");
        assert_eq!(Scope::SendUdt.as_tag(), "send_udt");
        assert_eq!(Scope::Dao.as_tag(), "dao");
        assert_eq!(Scope::Messaging.as_tag(), "messaging");
        assert_eq!(Scope::Fiber.as_tag(), "fiber");
    }
}
