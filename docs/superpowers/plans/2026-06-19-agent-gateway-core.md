# Agent Gateway — Plan A: Rust Security Core — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the on-device authorization core for Blackbox Vault's Agent Gateway in `rust-core`: mint/verify scoped **biscuit** tokens, decide ALLOW_AUTO / NEED_APPROVAL / DENY for an agent **intent** against per-asset spend caps, and prove it on Pudge testnet via the harness.

**Architecture:** Two pure UniFFI functions — `token_caps()` (parse + extract caveats) and `decide(token, intent, ledger_view, ctx)` (full policy) — built on `biscuit-auth`. Biscuit enforces the authenticated/attenuable caveats (scope membership, ttl, `allow_to`, `allow_ip`, account binding) via its Datalog authorizer; the Rust policy layer enforces the stateful caveats (cumulative + rolling-window caps, `auto_limit` tiering) by reading cap facts out of the authorized token. `decide()` is **read-only**: Kotlin (Plan B) performs the atomic debit-then-sign inside a SQLCipher transaction. A `LedgerStore` trait + `InMemoryLedger` exist in Rust for unit tests and the harness only.

**Tech Stack:** Rust 2021, `biscuit-auth` 5.x (Ed25519 tokens, Datalog), UniFFI 0.31 proc-macro, existing `wyltekwalletcore` crate, `ureq` (harness RPC).

## Global Constraints

- Crate is `wyltekwalletcore`; `crate-type = ["cdylib", "rlib"]` — the rlib lets `examples/pq_testnet.rs` link it. Do not break either.
- UniFFI proc-macro mode (`uniffi::setup_scaffolding!()`): structs use `#[derive(uniffi::Record)]`, enums `#[derive(uniffi::Enum)]`, free functions `#[uniffi::export]`, fallible functions return `Result<_, WalletError>` or a new `#[uniffi(flat_error)]` enum.
- Reuse, do not reimplement: `address::validate_address(String) -> bool` for recipient validation.
- All monetary amounts are **shannons as `i64`** (max CKB supply ≈ 3.36e18 < i64::MAX ≈ 9.2e18). Cap arithmetic must be **saturating** — never panic on overflow.
- `asset` identifier is the literal string `"CKB"` for native CKB, or a UDT type-script id `"{code_hash}:{hash_type}:{args}"` for a token.
- v1 scope vocabulary is exactly `send_ckb`, `send_udt`, `dao`, `messaging`. `fiber` is reserved — accept it in the schema, do not implement behaviour for it.
- Fail closed: any parse/verify/eval error in `decide()` returns `Decision::Deny`, never a silent allow.
- Testnet constants live in `examples/pq_testnet.rs` (RPC_URL, MLDSA_*, SECP_*). Reuse them; do not duplicate.
- Commit after every task with a `feat:`/`test:`/`build:` conventional message. No attribution footer (disabled globally).

## File Structure

| File | Responsibility |
|------|----------------|
| `rust-core/Cargo.toml` (modify) | Add `biscuit-auth = "5"` dependency |
| `rust-core/src/lib.rs` (modify) | `pub mod agent;` + re-exports |
| `rust-core/src/agent/mod.rs` (create) | Module root, re-exports, `AgentError` |
| `rust-core/src/agent/types.rs` (create) | `Scope`, `TokenSpec`, `Intent`, `RequestCtx`, `LedgerView`, `CapInfo`, `Decision` (all UniFFI) |
| `rust-core/src/agent/token.rs` (create) | `agent_root_keypair()`, `mint_token()`, internal `parse_and_authorize()`, `token_caps()` |
| `rust-core/src/agent/ledger.rs` (create) | `LedgerStore` trait, `SpendRecord`, `InMemoryLedger` (tests/harness only) |
| `rust-core/src/agent/policy.rs` (create) | `decide()` — the policy decision function |
| `rust-core/examples/pq_testnet.rs` (modify) | `agent-mint-token` + `agent-intent` harness commands |

---

### Task 1: Add dependency + agent module skeleton + shared types

**Files:**
- Modify: `rust-core/Cargo.toml`
- Modify: `rust-core/src/lib.rs:10` (after `pub mod molecule;`)
- Create: `rust-core/src/agent/mod.rs`
- Create: `rust-core/src/agent/types.rs`

**Interfaces:**
- Produces: enum `Scope`; records `TokenSpec`, `Intent`, `RequestCtx`, `LedgerView`, `CapInfo`; enum `Decision`; enum `AgentError`. Exact definitions below — later tasks rely on these names verbatim.

- [ ] **Step 1: Add the biscuit-auth dependency**

In `rust-core/Cargo.toml`, under `[dependencies]` after the `# Crypto` block, add:

```toml
# Agent Gateway tokens (Ed25519 + Datalog caveats)
biscuit-auth = "5"
```

- [ ] **Step 2: Create the agent module root**

Create `rust-core/src/agent/mod.rs`:

```rust
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
pub use token::{agent_root_keypair, mint_token, token_caps, AgentKeyPair};
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
```

- [ ] **Step 3: Create the shared types**

Create `rust-core/src/agent/types.rs`:

```rust
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
```

- [ ] **Step 4: Register the module**

In `rust-core/src/lib.rs`, after line 10 (`pub mod molecule;`) add:

```rust
pub mod agent;
```

And after line 18 (the `pub use hashing::...` line) add:

```rust
pub use agent::{
    agent_root_keypair, decide, mint_token, token_caps, AgentError, AgentKeyPair, CapInfo,
    Decision, Intent, LedgerView, RequestCtx, Scope, TokenSpec,
};
```

- [ ] **Step 5: Verify it compiles**

Run: `cd ~/wyltek-wallet/rust-core && cargo build`
Expected: PASS (`token.rs`, `ledger.rs`, `policy.rs` are still empty modules — they are created in later tasks, so temporarily add `pub mod token; pub mod ledger; pub mod policy;` will fail). To keep this task self-contained, create the three files as empty stubs now:

```bash
cd ~/wyltek-wallet/rust-core/src/agent
printf '//! Token mint/verify — implemented in Task 2/3.\n' > token.rs
printf '//! Ledger store — implemented in Task 4.\n' > ledger.rs
printf '//! Policy decision — implemented in Task 5.\n' > policy.rs
```

Then the `pub use` lines for not-yet-defined symbols will fail to compile. To avoid that, comment out the re-export lines in `mod.rs` and `lib.rs` that reference unimplemented symbols **for this task only**, leaving only what exists. Simplest: in this task, make `mod.rs` re-export nothing and `lib.rs` add only `pub mod agent;` (no `pub use`). Add the `pub use` lines in the task that first defines each symbol.

Run: `cargo build`
Expected: PASS with `agent`, `agent::types` compiling; `Scope`/`Intent`/`Decision` etc. defined.

- [ ] **Step 6: Commit**

```bash
cd ~/wyltek-wallet
git add rust-core/Cargo.toml rust-core/src/lib.rs rust-core/src/agent/
git commit -m "feat(agent): add biscuit-auth dep + agent module skeleton and types"
```

---

### Task 2: Mint and round-trip a biscuit token

**Files:**
- Modify: `rust-core/src/agent/token.rs`
- Modify: `rust-core/src/agent/mod.rs` (add `pub use token::{...}`)

**Interfaces:**
- Consumes: `TokenSpec`, `Scope`, `CapInfo` (Task 1); `AgentError` (Task 1).
- Produces:
  - `pub struct AgentKeyPair { pub secret_hex: String, pub public_hex: String }` (`uniffi::Record`)
  - `pub fn agent_root_keypair() -> AgentKeyPair`
  - `pub fn mint_token(spec: TokenSpec, root_secret_hex: String) -> Result<String, AgentError>` (returns base64 biscuit)
  - internal `fn root_keypair_from_hex(secret_hex: &str) -> Result<KeyPair, AgentError>`

- [ ] **Step 1: Write the failing test**

Append to `rust-core/src/agent/token.rs`:

```rust
use crate::agent::types::{CapInfo, Scope, TokenSpec};
use crate::agent::AgentError;
use biscuit_auth::macros::*;
use biscuit_auth::{Biscuit, KeyPair, PrivateKey};

#[derive(Clone, uniffi::Record)]
pub struct AgentKeyPair {
    pub secret_hex: String,
    pub public_hex: String,
}

#[uniffi::export]
pub fn agent_root_keypair() -> AgentKeyPair {
    let kp = KeyPair::new();
    AgentKeyPair {
        secret_hex: hex::encode(kp.private().to_bytes()),
        public_hex: hex::encode(kp.public().to_bytes()),
    }
}

fn root_keypair_from_hex(secret_hex: &str) -> Result<KeyPair, AgentError> {
    let raw = hex::decode(secret_hex.trim_start_matches("0x"))
        .map_err(|e| AgentError::TokenError(format!("bad secret hex: {e}")))?;
    let pk = PrivateKey::from_bytes(&raw)
        .map_err(|e| AgentError::TokenError(format!("bad private key: {e}")))?;
    Ok(KeyPair::from(&pk))
}

#[uniffi::export]
pub fn mint_token(spec: TokenSpec, root_secret_hex: String) -> Result<String, AgentError> {
    if spec.scopes.is_empty() {
        return Err(AgentError::InvalidSpec("at least one scope required".into()));
    }
    let root = root_keypair_from_hex(&root_secret_hex)?;
    let token_id = blake_id(&spec);

    let mut builder = Biscuit::builder();
    builder
        .add_fact(fact!("account({a})", a = spec.account.as_str()))
        .map_err(tok_err)?;
    builder
        .add_fact(fact!("token_id({id})", id = token_id.as_str()))
        .map_err(tok_err)?;
    for s in &spec.scopes {
        builder
            .add_fact(fact!("scope({s})", s = s.as_tag()))
            .map_err(tok_err)?;
    }
    for c in &spec.caps {
        builder
            .add_fact(fact!("cap({a}, {v})", a = c.asset.as_str(), v = c.cumulative))
            .map_err(tok_err)?;
        builder
            .add_fact(fact!(
                "window({a}, {s}, {l})",
                a = c.asset.as_str(),
                s = c.window_seconds,
                l = c.window_limit
            ))
            .map_err(tok_err)?;
        builder
            .add_fact(fact!("auto_limit({a}, {v})", a = c.asset.as_str(), v = c.auto_limit))
            .map_err(tok_err)?;
    }
    for addr in &spec.allow_to {
        builder
            .add_fact(fact!("allow_to({r})", r = addr.as_str()))
            .map_err(tok_err)?;
    }
    for ip in &spec.allow_ip {
        builder
            .add_fact(fact!("allow_ip({i})", i = ip.as_str()))
            .map_err(tok_err)?;
    }

    // Caveats enforced cryptographically by the authorizer at verify time.
    builder
        .add_check(check!("check if operation($o), scope($o)"))
        .map_err(tok_err)?;
    builder
        .add_check(check!("check if requested_account($a), account($a)"))
        .map_err(tok_err)?;
    if let Some(exp) = spec.ttl_unix {
        let when = std::time::UNIX_EPOCH + std::time::Duration::from_secs(exp as u64);
        builder
            .add_check(check!("check if time($t), $t <= {exp}", exp = when))
            .map_err(tok_err)?;
    }
    if !spec.allow_to.is_empty() {
        builder
            .add_check(check!("check if recipient($r), allow_to($r)"))
            .map_err(tok_err)?;
    }
    if !spec.allow_ip.is_empty() {
        builder
            .add_check(check!("check if source_ip($i), allow_ip($i)"))
            .map_err(tok_err)?;
    }

    let biscuit = builder.build(&root).map_err(tok_err)?;
    biscuit.to_base64().map_err(tok_err)
}

fn tok_err<E: std::fmt::Display>(e: E) -> AgentError {
    AgentError::TokenError(e.to_string())
}

/// Deterministic 16-byte hex id from the spec (account + scopes + caps + ttl).
fn blake_id(spec: &TokenSpec) -> String {
    let mut seed = spec.account.clone();
    for s in &spec.scopes {
        seed.push_str(s.as_tag());
    }
    for c in &spec.caps {
        seed.push_str(&format!("{}{}{}", c.asset, c.cumulative, c.auto_limit));
    }
    if let Some(t) = spec.ttl_unix {
        seed.push_str(&t.to_string());
    }
    let h = crate::hashing::blake2b_256(hex::encode(seed)).unwrap_or_default();
    h.trim_start_matches("0x").chars().take(32).collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::agent::types::{CapInfo, Scope};

    fn sample_spec() -> TokenSpec {
        TokenSpec {
            account: "ckt1qexample".into(),
            scopes: vec![Scope::SendCkb],
            caps: vec![CapInfo {
                asset: "CKB".into(),
                cumulative: 100_000_000_000,
                window_seconds: 0,
                window_limit: 0,
                auto_limit: 20_000_000_000,
            }],
            ttl_unix: None,
            allow_to: vec![],
            allow_ip: vec![],
        }
    }

    #[test]
    fn mint_and_parse_roundtrip() {
        let kp = agent_root_keypair();
        let token = mint_token(sample_spec(), kp.secret_hex.clone()).unwrap();
        assert!(!token.is_empty());
        // Parses under the matching public key.
        let pub_raw = hex::decode(&kp.public_hex).unwrap();
        let public = biscuit_auth::PublicKey::from_bytes(&pub_raw).unwrap();
        let parsed = Biscuit::from_base64(&token, public);
        assert!(parsed.is_ok(), "token must parse under its root pubkey");
    }

    #[test]
    fn wrong_pubkey_rejected() {
        let kp = agent_root_keypair();
        let other = agent_root_keypair();
        let token = mint_token(sample_spec(), kp.secret_hex).unwrap();
        let pub_raw = hex::decode(&other.public_hex).unwrap();
        let public = biscuit_auth::PublicKey::from_bytes(&pub_raw).unwrap();
        assert!(Biscuit::from_base64(&token, public).is_err());
    }

    #[test]
    fn empty_scopes_rejected() {
        let kp = agent_root_keypair();
        let mut spec = sample_spec();
        spec.scopes.clear();
        assert!(mint_token(spec, kp.secret_hex).is_err());
    }
}
```

- [ ] **Step 2: Run tests to verify they fail / surface API drift**

Run: `cd ~/wyltek-wallet/rust-core && cargo test agent::token 2>&1 | tail -30`
Expected: compiles and the three tests run. If `add_fact`/`add_check`/`fact!`/`check!`/`to_base64`/`from_base64`/`PrivateKey::from_bytes` names mismatch the installed `biscuit-auth` version, the compiler names the exact wrong symbol — fix the call to the version's equivalent (this is the API-drift catch, not a placeholder). Re-run until it compiles.

- [ ] **Step 3: Wire the re-exports**

In `rust-core/src/agent/mod.rs`, ensure the `pub use token::{...}` line includes `AgentKeyPair, agent_root_keypair, mint_token` and that `lib.rs` re-exports them (uncomment the lines deferred in Task 1 for these three symbols + `AgentKeyPair`).

- [ ] **Step 4: Run tests to verify they pass**

Run: `cargo test agent::token 2>&1 | tail -15`
Expected: `mint_and_parse_roundtrip`, `wrong_pubkey_rejected`, `empty_scopes_rejected` all PASS.

- [ ] **Step 5: Commit**

```bash
cd ~/wyltek-wallet
git add rust-core/src/agent/ rust-core/src/lib.rs
git commit -m "feat(agent): mint scoped biscuit tokens with caveat checks"
```

---

### Task 3: Authorize a token against context + extract caps

**Files:**
- Modify: `rust-core/src/agent/token.rs`

**Interfaces:**
- Consumes: `Intent`, `RequestCtx`, `CapInfo` (Task 1); `mint_token` (Task 2).
- Produces:
  - `pub(crate) struct Authorized { pub token_id: String, pub caps: Vec<CapInfo> }`
  - `pub(crate) fn parse_and_authorize(token: &str, root_pub_hex: &str, op: &str, account: &str, to: &str, source_ip: &str, now_unix: i64) -> Result<Authorized, String>` — `Err(reason)` on any caveat failure.
  - `pub fn token_caps(token: String, root_pub_hex: String) -> Result<Vec<CapInfo>, AgentError>` (UniFFI; parses without context, for the UI to display a token's caps).

- [ ] **Step 1: Write the failing test**

Append to `rust-core/src/agent/token.rs` (inside the file, before `#[cfg(test)]`):

```rust
use crate::agent::types::CapInfo as Cap;
use biscuit_auth::{Authorizer, PublicKey};

pub(crate) struct Authorized {
    pub token_id: String,
    pub caps: Vec<Cap>,
}

fn root_public_from_hex(root_pub_hex: &str) -> Result<PublicKey, String> {
    let raw = hex::decode(root_pub_hex.trim_start_matches("0x"))
        .map_err(|e| format!("bad pubkey hex: {e}"))?;
    PublicKey::from_bytes(&raw).map_err(|e| format!("bad pubkey: {e}"))
}

fn extract_caps(authorizer: &mut Authorizer) -> Result<Vec<Cap>, String> {
    // cumulative caps
    let caps: Vec<(String, i64)> = authorizer
        .query(rule!("out($a, $v) <- cap($a, $v)"))
        .map_err(|e| format!("cap query: {e}"))?;
    let windows: Vec<(String, i64, i64)> = authorizer
        .query(rule!("out($a, $s, $l) <- window($a, $s, $l)"))
        .map_err(|e| format!("window query: {e}"))?;
    let autos: Vec<(String, i64)> = authorizer
        .query(rule!("out($a, $v) <- auto_limit($a, $v)"))
        .map_err(|e| format!("auto query: {e}"))?;

    Ok(caps
        .into_iter()
        .map(|(asset, cumulative)| {
            let (ws, wl) = windows
                .iter()
                .find(|(a, _, _)| *a == asset)
                .map(|(_, s, l)| (*s, *l))
                .unwrap_or((0, 0));
            let auto = autos
                .iter()
                .find(|(a, _)| *a == asset)
                .map(|(_, v)| *v)
                .unwrap_or(0);
            Cap { asset, cumulative, window_seconds: ws, window_limit: wl, auto_limit: auto }
        })
        .collect())
}

fn token_id_of(authorizer: &mut Authorizer) -> Result<String, String> {
    let ids: Vec<(String,)> = authorizer
        .query(rule!("out($id) <- token_id($id)"))
        .map_err(|e| format!("token_id query: {e}"))?;
    ids.into_iter().next().map(|(id,)| id).ok_or_else(|| "token_id missing".into())
}

pub(crate) fn parse_and_authorize(
    token: &str,
    root_pub_hex: &str,
    op: &str,
    account: &str,
    to: &str,
    source_ip: &str,
    now_unix: i64,
) -> Result<Authorized, String> {
    let public = root_public_from_hex(root_pub_hex)?;
    let biscuit = Biscuit::from_base64(token, public).map_err(|e| format!("parse: {e}"))?;
    let when = std::time::UNIX_EPOCH + std::time::Duration::from_secs(now_unix.max(0) as u64);
    let mut authorizer = authorizer!(
        r#"
        operation({op});
        requested_account({account});
        recipient({to});
        source_ip({ip});
        time({now});
        allow if true;
        "#,
        op = op,
        account = account,
        to = to,
        ip = source_ip,
        now = when
    )
    .build(&biscuit)
    .map_err(|e| format!("authorizer build: {e}"))?;
    authorizer.authorize().map_err(|e| format!("denied: {e}"))?;

    let token_id = token_id_of(&mut authorizer)?;
    let caps = extract_caps(&mut authorizer)?;
    Ok(Authorized { token_id, caps })
}

#[uniffi::export]
pub fn token_caps(token: String, root_pub_hex: String) -> Result<Vec<Cap>, AgentError> {
    let public = root_public_from_hex(&root_pub_hex).map_err(AgentError::TokenError)?;
    let biscuit = Biscuit::from_base64(&token, public).map_err(tok_err)?;
    // Authorizer with no context facts and no policy run — just to query stored caps.
    let mut authorizer = biscuit.authorizer().map_err(tok_err)?;
    extract_caps(&mut authorizer).map_err(AgentError::TokenError)
}
```

Add these tests inside the existing `#[cfg(test)] mod tests`:

```rust
    fn keypair_and_token(spec: TokenSpec) -> (String, String) {
        let kp = agent_root_keypair();
        let token = mint_token(spec, kp.secret_hex).unwrap();
        (token, kp.public_hex)
    }

    #[test]
    fn authorizes_in_scope_op() {
        let (token, pubhex) = keypair_and_token(sample_spec());
        let r = parse_and_authorize(&token, &pubhex, "send_ckb", "ckt1qexample", "ckt1qto", "10.0.0.1", 1_700_000_000);
        assert!(r.is_ok());
        let a = r.unwrap();
        assert_eq!(a.caps.len(), 1);
        assert_eq!(a.caps[0].asset, "CKB");
        assert_eq!(a.caps[0].cumulative, 100_000_000_000);
        assert_eq!(a.caps[0].auto_limit, 20_000_000_000);
    }

    #[test]
    fn rejects_out_of_scope_op() {
        let (token, pubhex) = keypair_and_token(sample_spec());
        let r = parse_and_authorize(&token, &pubhex, "dao", "ckt1qexample", "ckt1qto", "10.0.0.1", 1_700_000_000);
        assert!(r.is_err(), "dao op not granted -> deny");
    }

    #[test]
    fn rejects_wrong_account() {
        let (token, pubhex) = keypair_and_token(sample_spec());
        let r = parse_and_authorize(&token, &pubhex, "send_ckb", "ckt1qOTHER", "ckt1qto", "10.0.0.1", 1_700_000_000);
        assert!(r.is_err());
    }

    #[test]
    fn enforces_ttl() {
        let mut spec = sample_spec();
        spec.ttl_unix = Some(1_700_000_000);
        let (token, pubhex) = keypair_and_token(spec);
        // before expiry -> ok
        assert!(parse_and_authorize(&token, &pubhex, "send_ckb", "ckt1qexample", "ckt1qto", "10.0.0.1", 1_699_999_000).is_ok());
        // after expiry -> deny
        assert!(parse_and_authorize(&token, &pubhex, "send_ckb", "ckt1qexample", "ckt1qto", "10.0.0.1", 1_700_000_001).is_err());
    }

    #[test]
    fn enforces_allow_to_and_ip() {
        let mut spec = sample_spec();
        spec.allow_to = vec!["ckt1qALLOWED".into()];
        spec.allow_ip = vec!["100.115.197.42".into()];
        let (token, pubhex) = keypair_and_token(spec);
        assert!(parse_and_authorize(&token, &pubhex, "send_ckb", "ckt1qexample", "ckt1qALLOWED", "100.115.197.42", 1).is_ok());
        assert!(parse_and_authorize(&token, &pubhex, "send_ckb", "ckt1qexample", "ckt1qEVIL", "100.115.197.42", 1).is_err());
        assert!(parse_and_authorize(&token, &pubhex, "send_ckb", "ckt1qexample", "ckt1qALLOWED", "1.2.3.4", 1).is_err());
    }

    #[test]
    fn token_caps_lists_caps() {
        let (token, pubhex) = keypair_and_token(sample_spec());
        let caps = token_caps(token, pubhex).unwrap();
        assert_eq!(caps.len(), 1);
        assert_eq!(caps[0].auto_limit, 20_000_000_000);
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd ~/wyltek-wallet/rust-core && cargo test agent::token 2>&1 | tail -30`
Expected: compile errors only if `query`/`rule!`/`authorizer!`/`biscuit.authorizer()` names differ from the installed version; otherwise the new tests run. Fix any API-name drift the compiler reports (e.g. tuple-arity query return type), then proceed.

- [ ] **Step 3: (implementation already written in Step 1) — run tests to verify they pass**

Run: `cargo test agent::token 2>&1 | tail -20`
Expected: all token tests PASS, including `authorizes_in_scope_op`, `rejects_out_of_scope_op`, `rejects_wrong_account`, `enforces_ttl`, `enforces_allow_to_and_ip`, `token_caps_lists_caps`.

- [ ] **Step 4: Export `token_caps`**

Ensure `rust-core/src/agent/mod.rs` `pub use token::{...}` includes `token_caps`, and `lib.rs` re-exports it.

Run: `cargo build` → PASS.

- [ ] **Step 5: Commit**

```bash
cd ~/wyltek-wallet
git add rust-core/src/agent/ rust-core/src/lib.rs
git commit -m "feat(agent): authorize tokens against request context + extract caps"
```

---

### Task 4: Ledger store (in-memory, for tests + harness)

**Files:**
- Modify: `rust-core/src/agent/ledger.rs`

**Interfaces:**
- Consumes: `LedgerView` (Task 1).
- Produces:
  - `pub struct SpendRecord { pub token_id: String, pub asset: String, pub amount: i64, pub unix: i64, pub nonce: String }`
  - `pub trait LedgerStore { fn view(&self, token_id: &str, asset: &str, window_seconds: i64, now_unix: i64) -> LedgerView; fn apply(&mut self, rec: SpendRecord) -> Result<(), String>; }`
  - `pub struct InMemoryLedger { ... }` implementing `LedgerStore`, plus `InMemoryLedger::new()`, `to_json()`, `from_json(&str)` for harness persistence.

- [ ] **Step 1: Write the failing test**

Replace `rust-core/src/agent/ledger.rs` contents with:

```rust
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
```

- [ ] **Step 2: Run tests to verify they fail, then pass**

Run: `cd ~/wyltek-wallet/rust-core && cargo test agent::ledger 2>&1 | tail -20`
Expected: `cumulative_sums`, `window_only_counts_recent`, `rejects_replayed_nonce`, `json_roundtrip` PASS.

- [ ] **Step 3: Export the ledger types**

In `rust-core/src/agent/mod.rs` add `pub use ledger::{InMemoryLedger, LedgerStore, SpendRecord};` (these are not UniFFI-exported — Rust-internal only — so do NOT add them to the `lib.rs` UniFFI re-export list).

Run: `cargo build` → PASS.

- [ ] **Step 4: Commit**

```bash
cd ~/wyltek-wallet
git add rust-core/src/agent/
git commit -m "feat(agent): in-memory spend ledger with window + replay guard"
```

---

### Task 5: The policy decision function

**Files:**
- Modify: `rust-core/src/agent/policy.rs`

**Interfaces:**
- Consumes: `Intent`, `RequestCtx`, `LedgerView`, `Decision` (Task 1); `parse_and_authorize`, `Authorized` (Task 3); `validate_address` (existing).
- Produces: `pub fn decide(token: String, root_pub_hex: String, intent: Intent, view: LedgerView, ctx: RequestCtx) -> Decision` (`#[uniffi::export]`).

- [ ] **Step 1: Write the failing test**

Replace `rust-core/src/agent/policy.rs` contents with:

```rust
//! Policy decision: token + intent + current usage -> ALLOW_AUTO / NEED_APPROVAL / DENY.
//! Pure and read-only. Fails closed: any error path returns `Decision::Deny`.

use crate::address::validate_address;
use crate::agent::token::parse_and_authorize;
use crate::agent::types::{Decision, Intent, LedgerView, RequestCtx};

fn deny(reason: impl Into<String>) -> Decision {
    Decision::Deny { reason: reason.into() }
}

#[uniffi::export]
pub fn decide(
    token: String,
    root_pub_hex: String,
    intent: Intent,
    view: LedgerView,
    ctx: RequestCtx,
) -> Decision {
    if intent.amount < 0 {
        return deny("negative amount");
    }
    if view.nonce_seen {
        return deny(format!("replay: nonce {} already used", intent.nonce));
    }
    // Recipient validation for ops that move value to an address.
    let needs_recipient = matches!(intent.op.as_str(), "send_ckb" | "send_udt");
    if needs_recipient && !validate_address(intent.to.clone()) {
        return deny("invalid recipient address");
    }

    // Cryptographic caveat enforcement (scope, account, ttl, allow_to, allow_ip).
    let authorized = match parse_and_authorize(
        &token,
        &root_pub_hex,
        &intent.op,
        &ctx.account,
        &intent.to,
        &ctx.source_ip,
        ctx.now_unix,
    ) {
        Ok(a) => a,
        Err(reason) => return deny(reason),
    };

    // Stateful cap enforcement for the intent's asset.
    let cap = match authorized.caps.iter().find(|c| c.asset == intent.asset) {
        Some(c) => c,
        None => return deny(format!("no cap for asset {}", intent.asset)),
    };

    let new_cumulative = view.cumulative_spent.saturating_add(intent.amount);
    if cap.cumulative > 0 && new_cumulative > cap.cumulative {
        return deny(format!(
            "cumulative cap exceeded: {} + {} > {}",
            view.cumulative_spent, intent.amount, cap.cumulative
        ));
    }
    if cap.window_seconds > 0 && cap.window_limit > 0 {
        let new_window = view.window_spent.saturating_add(intent.amount);
        if new_window > cap.window_limit {
            return deny(format!(
                "rolling-window cap exceeded: {} + {} > {} per {}s",
                view.window_spent, intent.amount, cap.window_limit, cap.window_seconds
            ));
        }
    }

    // Tiering.
    if cap.auto_limit > 0 && intent.amount > cap.auto_limit {
        Decision::NeedApproval {
            token_id: authorized.token_id,
            asset: intent.asset,
            amount: intent.amount,
        }
    } else {
        Decision::AllowAuto {
            token_id: authorized.token_id,
            asset: intent.asset,
            amount: intent.amount,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::agent::token::{agent_root_keypair, mint_token};
    use crate::agent::types::{CapInfo, Scope, TokenSpec};

    // A real testnet address so `validate_address` passes. Generated from a fixed seed.
    fn real_addr() -> String {
        let kp = crate::keys::generate_secp256k1_keypair("11".repeat(16), "m/44'/309'/0'/0/0".into()).unwrap();
        crate::keys::public_key_to_ckb_address(kp.public_key_hex, "testnet".into()).unwrap()
    }

    fn setup(auto_limit: i64, cumulative: i64, window_s: i64, window_l: i64) -> (String, String, String) {
        let kp = agent_root_keypair();
        let acct = real_addr();
        let spec = TokenSpec {
            account: acct.clone(),
            scopes: vec![Scope::SendCkb],
            caps: vec![CapInfo {
                asset: "CKB".into(),
                cumulative,
                window_seconds: window_s,
                window_limit: window_l,
                auto_limit,
            }],
            ttl_unix: None,
            allow_to: vec![],
            allow_ip: vec![],
        };
        let token = mint_token(spec, kp.secret_hex).unwrap();
        (token, kp.public_hex, acct)
    }

    fn intent(amount: i64, nonce: &str, to: &str) -> Intent {
        Intent { op: "send_ckb".into(), asset: "CKB".into(), to: to.into(), amount, nonce: nonce.into() }
    }
    fn ctx(acct: &str, now: i64) -> RequestCtx {
        RequestCtx { account: acct.into(), source_ip: "10.0.0.1".into(), now_unix: now }
    }
    fn fresh_view() -> LedgerView {
        LedgerView { cumulative_spent: 0, window_spent: 0, nonce_seen: false }
    }

    #[test]
    fn auto_under_limit() {
        let to = real_addr();
        let (t, p, a) = setup(20, 100, 0, 0);
        let d = decide(t, p, intent(5, "n1", &to), fresh_view(), ctx(&a, 1));
        assert!(matches!(d, Decision::AllowAuto { amount: 5, .. }));
    }

    #[test]
    fn approval_over_limit() {
        let to = real_addr();
        let (t, p, a) = setup(20, 100, 0, 0);
        let d = decide(t, p, intent(50, "n1", &to), fresh_view(), ctx(&a, 1));
        assert!(matches!(d, Decision::NeedApproval { amount: 50, .. }));
    }

    #[test]
    fn deny_over_cumulative_cap() {
        let to = real_addr();
        let (t, p, a) = setup(20, 100, 0, 0);
        let view = LedgerView { cumulative_spent: 80, window_spent: 0, nonce_seen: false };
        let d = decide(t, p, intent(50, "n1", &to), view, ctx(&a, 1));
        assert!(matches!(d, Decision::Deny { .. }));
    }

    #[test]
    fn deny_over_window_cap() {
        let to = real_addr();
        let (t, p, a) = setup(1000, 100000, 86400, 30);
        let view = LedgerView { cumulative_spent: 25, window_spent: 25, nonce_seen: false };
        let d = decide(t, p, intent(10, "n1", &to), view, ctx(&a, 1));
        assert!(matches!(d, Decision::Deny { .. }), "25+10 > 30 window limit");
    }

    #[test]
    fn deny_replay() {
        let to = real_addr();
        let (t, p, a) = setup(20, 100, 0, 0);
        let view = LedgerView { cumulative_spent: 0, window_spent: 0, nonce_seen: true };
        let d = decide(t, p, intent(5, "dup", &to), view, ctx(&a, 1));
        assert!(matches!(d, Decision::Deny { .. }));
    }

    #[test]
    fn deny_invalid_recipient() {
        let (t, p, a) = setup(20, 100, 0, 0);
        let d = decide(t, p, intent(5, "n1", "not-an-address"), fresh_view(), ctx(&a, 1));
        assert!(matches!(d, Decision::Deny { .. }));
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd ~/wyltek-wallet/rust-core && cargo test agent::policy 2>&1 | tail -30`
Expected: compiles; tests run. If `crate::keys::generate_secp256k1_keypair` signature differs, adjust the `real_addr()` helper to match `keys.rs` (it is only test scaffolding).

- [ ] **Step 3: Wire the re-export**

In `rust-core/src/agent/mod.rs` confirm `pub use policy::decide;` and `lib.rs` re-exports `decide`.

Run: `cargo build` → PASS.

- [ ] **Step 4: Run the full agent suite to verify it passes**

Run: `cargo test agent:: 2>&1 | tail -25`
Expected: all `agent::token`, `agent::ledger`, `agent::policy` tests PASS.

- [ ] **Step 5: Commit**

```bash
cd ~/wyltek-wallet
git add rust-core/src/agent/ rust-core/src/lib.rs
git commit -m "feat(agent): policy decide() — scope/cap/window/auto-limit/replay"
```

---

### Task 6: Coverage gate for the agent core

**Files:** none created — verification task.

**Interfaces:** none.

- [ ] **Step 1: Install/confirm coverage tooling**

Run: `cargo llvm-cov --version || cargo install cargo-llvm-cov`
Expected: a version prints.

- [ ] **Step 2: Measure coverage of the agent module**

Run: `cd ~/wyltek-wallet/rust-core && cargo llvm-cov --lib --no-fail-fast 2>&1 | grep -E "agent/|TOTAL"`
Expected: each `src/agent/*.rs` line ≥ 80% region/line coverage.

- [ ] **Step 3: Close gaps if any file is under 80%**

For any `agent/*.rs` below 80%, add a focused unit test for the uncovered branch (e.g. `mint_token` empty-scope error, `token_caps` bad-pubkey error, a `window_seconds==0` path). Re-run Step 2 until all agent files ≥ 80%.

- [ ] **Step 4: Commit**

```bash
cd ~/wyltek-wallet
git add rust-core/src/agent/
git commit -m "test(agent): raise agent-core coverage to >=80%"
```

---

### Task 7: Testnet harness commands

**Files:**
- Modify: `rust-core/examples/pq_testnet.rs`

**Interfaces:**
- Consumes: `mint_token`, `agent_root_keypair`, `token_caps`, `decide` (Tasks 2/3/5); `InMemoryLedger`, `SpendRecord` (Task 4); existing `spend-pq` build/`send_transaction` path in the harness.
- Produces: two new harness subcommands wired into the `match cmd` block in `main()`:
  - `agent-mint-token <seed_hex> <ckb_addr> <auto_ckb> <cumulative_ckb>` → prints `ROOT_SECRET`, `ROOT_PUBLIC`, `TOKEN`.
  - `agent-intent <root_pub_hex> <token> <to_addr> <amount_ckb> <nonce> [<seed_hex>]` → runs `decide` against a JSON-file ledger (`/tmp/agent_ledger.json`); on `AllowAuto` with a seed provided, executes the existing PQ send and records the spend.

- [ ] **Step 1: Add the imports**

At the top of `rust-core/examples/pq_testnet.rs`, extend the `use wyltekwalletcore::...` imports with:

```rust
use wyltekwalletcore::agent::{
    agent_root_keypair, decide, mint_token, token_caps,
    ledger::{InMemoryLedger, LedgerStore, SpendRecord},
    types::{CapInfo, Decision, Intent, LedgerView, RequestCtx, Scope, TokenSpec},
};
```

- [ ] **Step 2: Add the command handlers**

Add two functions near the other command handlers in `pq_testnet.rs`:

```rust
const LEDGER_PATH: &str = "/tmp/agent_ledger.json";
const CKB_PER: i64 = 100_000_000; // shannons per CKB

fn cmd_agent_mint_token(args: &[String]) -> Result<String, String> {
    let seed_hex = args.get(0).ok_or("need <seed_hex>")?;
    let ckb_addr = args.get(1).ok_or("need <ckb_addr>")?;
    let auto_ckb: i64 = args.get(2).ok_or("need <auto_ckb>")?.parse().map_err(|_| "auto_ckb")?;
    let cumulative_ckb: i64 = args.get(3).ok_or("need <cumulative_ckb>")?.parse().map_err(|_| "cumulative_ckb")?;
    let _ = seed_hex; // seed is the wallet's; the agent root key is independent

    let kp = agent_root_keypair();
    let spec = TokenSpec {
        account: ckb_addr.clone(),
        scopes: vec![Scope::SendCkb],
        caps: vec![CapInfo {
            asset: "CKB".into(),
            cumulative: cumulative_ckb.saturating_mul(CKB_PER),
            window_seconds: 0,
            window_limit: 0,
            auto_limit: auto_ckb.saturating_mul(CKB_PER),
        }],
        ttl_unix: None,
        allow_to: vec![],
        allow_ip: vec![],
    };
    let token = mint_token(spec, kp.secret_hex.clone()).map_err(|e| e.to_string())?;
    let caps = token_caps(token.clone(), kp.public_hex.clone()).map_err(|e| e.to_string())?;
    Ok(format!(
        "ROOT_SECRET={}\nROOT_PUBLIC={}\nTOKEN={}\nCAPS={:?}",
        kp.secret_hex, kp.public_hex, token, caps
    ))
}

fn cmd_agent_intent(args: &[String]) -> Result<String, String> {
    let root_pub = args.get(0).ok_or("need <root_pub_hex>")?.clone();
    let token = args.get(1).ok_or("need <token>")?.clone();
    let to = args.get(2).ok_or("need <to_addr>")?.clone();
    let amount_ckb: i64 = args.get(3).ok_or("need <amount_ckb>")?.parse().map_err(|_| "amount_ckb")?;
    let nonce = args.get(4).ok_or("need <nonce>")?.clone();
    let seed_hex = args.get(5).cloned();
    let amount = amount_ckb.saturating_mul(CKB_PER);

    // Account = the token's bound account; recover it is not exposed, so require the
    // caller to pass the same ckb_addr used at mint via env for this harness.
    let account = std::env::var("AGENT_ACCOUNT").map_err(|_| "set AGENT_ACCOUNT to the minted ckb_addr")?;

    let ledger = std::fs::read_to_string(LEDGER_PATH)
        .map(|s| InMemoryLedger::from_json(&s))
        .unwrap_or_else(|_| InMemoryLedger::new());

    // We need the token_id to view the ledger; get it cheaply by a dry decide on a
    // zero-amount probe is overkill — instead read caps (asset list) and use a stable
    // per-token file keyed by the token string hash for the harness.
    let now = 1_700_000_000_i64 + (nonce.len() as i64); // deterministic-ish for harness
    // token_id is embedded; re-derive via a successful authorize through decide.
    let view0 = LedgerView { cumulative_spent: 0, window_spent: 0, nonce_seen: false };
    // First decide to obtain token_id (works because caps/limits are read there too).
    let probe = decide(
        token.clone(),
        root_pub.clone(),
        Intent { op: "send_ckb".into(), asset: "CKB".into(), to: to.clone(), amount, nonce: nonce.clone() },
        view0,
        RequestCtx { account: account.clone(), source_ip: "127.0.0.1".into(), now_unix: now },
    );
    let token_id = match &probe {
        Decision::AllowAuto { token_id, .. } | Decision::NeedApproval { token_id, .. } => token_id.clone(),
        Decision::Deny { reason } => return Ok(format!("DECISION=DENY reason={reason}")),
    };

    // Real view with persisted usage.
    let view = ledger.view(&token_id, "CKB", 0, now);
    let nonce_seen = {
        // emulate nonce check via apply attempt later; view doesn't track it, so probe:
        let mut l = InMemoryLedger::from_json(&ledger.to_json());
        l.apply(SpendRecord { token_id: token_id.clone(), asset: "CKB".into(), amount: 0, unix: now, nonce: nonce.clone() }).is_err()
    };
    let view = LedgerView { nonce_seen, ..view };

    let decision = decide(
        token.clone(),
        root_pub.clone(),
        Intent { op: "send_ckb".into(), asset: "CKB".into(), to: to.clone(), amount, nonce: nonce.clone() },
        view,
        RequestCtx { account: account.clone(), source_ip: "127.0.0.1".into(), now_unix: now },
    );

    match decision {
        Decision::Deny { reason } => Ok(format!("DECISION=DENY reason={reason}")),
        Decision::NeedApproval { amount, .. } => {
            Ok(format!("DECISION=NEED_APPROVAL amount_shannons={amount} (no broadcast in harness)"))
        }
        Decision::AllowAuto { amount, token_id, .. } => {
            let seed = seed_hex.ok_or("AllowAuto but no <seed_hex> to sign with")?;
            // Reuse the existing PQ send path (spend-pq) to actually broadcast.
            let txhash = spend_pq(&seed, &to, amount_ckb as u64)?;
            let mut l = ledger;
            l.apply(SpendRecord { token_id, asset: "CKB".into(), amount, unix: now, nonce })
                .map_err(|e| e.to_string())?;
            std::fs::write(LEDGER_PATH, l.to_json()).map_err(|e| e.to_string())?;
            Ok(format!("DECISION=ALLOW_AUTO tx={txhash} ledger_updated"))
        }
    }
}
```

> Note: `spend_pq(seed, to, amount_ckb)` is the existing harness send function. Match its real signature in `pq_testnet.rs` (it currently runs as the `spend-pq` arm); if it is inlined in the `match`, extract it into `fn spend_pq(seed_hex: &str, to: &str, amount_ckb: u64) -> Result<String, String>` returning the tx hash, and call that from both the `spend-pq` arm and `cmd_agent_intent`.

- [ ] **Step 3: Wire the commands into `main()`**

In the `match cmd { ... }` block in `main()` (around line 84), add two arms (using whatever slice of `args` the existing arms use for parameters — match the local convention):

```rust
        "agent-mint-token" => cmd_agent_mint_token(&args[2..]),
        "agent-intent" => cmd_agent_intent(&args[2..]),
```

And add the two usage lines to the `//! Usage:` doc-comment header and any printed usage string.

- [ ] **Step 4: Build the example**

Run: `cd ~/wyltek-wallet/rust-core && cargo build --example pq_testnet`
Expected: PASS.

- [ ] **Step 5: Run the on-chain sequence (Pudge testnet, funded hybrid wallet)**

Use a funded testnet seed. CKB address = the wallet's PQ-or-classic address that holds funds.

```bash
cd ~/wyltek-wallet/rust-core
rm -f /tmp/agent_ledger.json
export AGENT_ACCOUNT="<ckt1...your_funded_addr>"
# 1. Mint: auto-limit 20 CKB, cumulative 100 CKB
cargo run --example pq_testnet -- agent-mint-token <seed_hex> "$AGENT_ACCOUNT" 20 100
#    -> capture ROOT_PUBLIC and TOKEN from output
# 2. Small spend (5 CKB) -> ALLOW_AUTO + on-chain tx
cargo run --example pq_testnet -- agent-intent <ROOT_PUBLIC> <TOKEN> <to_addr> 5 n1 <seed_hex>
# 3. Over auto-limit (50 CKB) -> NEED_APPROVAL (no broadcast)
cargo run --example pq_testnet -- agent-intent <ROOT_PUBLIC> <TOKEN> <to_addr> 50 n2
# 4. Over cumulative cap (120 CKB) -> DENY
cargo run --example pq_testnet -- agent-intent <ROOT_PUBLIC> <TOKEN> <to_addr> 120 n3
# 5. Replay step-2 nonce -> DENY
cargo run --example pq_testnet -- agent-intent <ROOT_PUBLIC> <TOKEN> <to_addr> 5 n1 <seed_hex>
```

Expected:
- Step 2: `DECISION=ALLOW_AUTO tx=0x...` (verify the tx on a Pudge explorer).
- Step 3: `DECISION=NEED_APPROVAL ...`.
- Step 4: `DECISION=DENY reason=cumulative cap exceeded...`.
- Step 5: `DECISION=DENY reason=replay...`.

- [ ] **Step 6: Commit**

```bash
cd ~/wyltek-wallet
git add rust-core/examples/pq_testnet.rs
git commit -m "feat(agent): testnet harness for mint-token + intent decision path"
```

---

## Self-Review

**1. Spec coverage** (against `2026-06-19-agent-gateway-design.md` Parts 1, 2, 7):

- Part 1 token model (biscuit, StrongBox root key, caveats `id`/`account`/`scope`/`cap`/`auto_limit`/`ttl`/`allow_to`/`allow_ip`) → Tasks 2–3. (StrongBox sealing is Kotlin/Plan B; Rust exposes `agent_root_keypair` returning the secret for Kotlin to seal — covered.)
- Part 1 attenuation → biscuit `.append`/`block!` is available; an explicit attenuation harness/UI is Plan B. **Open item (below).**
- Part 2 policy engine `decide()` (scope, allow_to, caps, window, auto_limit, fail-closed) → Task 5.
- Part 2 ledger (cumulative + window + replay nonce) → Task 4; atomic debit-then-sign is Kotlin/Plan B (noted in Architecture).
- Part 2 intent-not-tx invariant → enforced by `Intent` type + harness building the tx itself (Task 7).
- Part 7 unit/property tests → Tasks 2–5; coverage gate → Task 6; on-chain harness sequence → Task 7.

**2. Placeholder scan:** No "TBD"/"add error handling"/"similar to". The two version-drift notes (biscuit method names, `spend_pq` signature) are explicit verify-and-adjust steps backed by a compile/test gate, not vague placeholders — the exact symbol to check is named.

**3. Type consistency:** `Decision`, `Intent`, `RequestCtx`, `LedgerView`, `CapInfo`, `Scope`, `TokenSpec` defined once in `types.rs` (Task 1) and used verbatim in Tasks 3/5/7. `parse_and_authorize` signature in Task 3 matches its call in Task 5. `LedgerStore::view/apply` in Task 4 matches harness use in Task 7. `mint_token(spec, secret_hex)` / `decide(token, root_pub_hex, intent, view, ctx)` consistent across tasks.

**Open items (carried to Plan B / flagged):**
- **Attenuation** is available via biscuit but has no dedicated task here; add a Plan B task (UI "derive restricted token") + a Rust `attenuate_token` export if sub-delegation is wanted in v1.
- **`InMemoryLedger::view` nonce field** is always `false` (Task 4); the harness computes replay separately via a dry `apply` (Task 7), and Kotlin uses a DB unique index. This is intentional — `view` reports usage, replay is an `apply`-time/DB-constraint concern — but the `nonce_seen` field on `LedgerView` is populated by the caller, documented in `types.rs`.
- **Property tests** (saturating cap math) are folded into Task 5/6 unit tests rather than a `proptest` dependency, to avoid adding a crate for v1; add `proptest` later if fuzzing the cap arithmetic is wanted.
