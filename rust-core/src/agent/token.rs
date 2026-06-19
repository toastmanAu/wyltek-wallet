//! Token mint/verify and authorization — Tasks 2 & 3.

use crate::agent::types::{CapInfo, TokenSpec};
use crate::agent::AgentError;
use biscuit_auth::macros::*;
use biscuit_auth::{Authorizer, Biscuit, KeyPair, PrivateKey, PublicKey};

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
    let token_id = blake_id(&spec)?;

    let mut builder = Biscuit::builder();
    builder
        .add_fact(fact!("account({a})", a = spec.account.as_str()))
        .map_err(tok_err)?;
    builder
        .add_fact(fact!("token_id({id})", id = token_id.as_str()))
        .map_err(tok_err)?;
    for s in &spec.scopes {
        let tag = s.as_tag();
        builder
            .add_fact(fact!("scope({s})", s = tag))
            .map_err(tok_err)?;
    }
    for c in &spec.caps {
        let asset = c.asset.as_str();
        let cumulative = c.cumulative;
        let window_seconds = c.window_seconds;
        let window_limit = c.window_limit;
        let auto_limit = c.auto_limit;
        builder
            .add_fact(fact!("cap({a}, {v})", a = asset, v = cumulative))
            .map_err(tok_err)?;
        builder
            .add_fact(fact!(
                "window({a}, {s}, {l})",
                a = asset,
                s = window_seconds,
                l = window_limit
            ))
            .map_err(tok_err)?;
        builder
            .add_fact(fact!("auto_limit({a}, {v})", a = asset, v = auto_limit))
            .map_err(tok_err)?;
    }
    for addr in &spec.allow_to {
        let r = addr.as_str();
        builder
            .add_fact(fact!("allow_to({r})", r = r))
            .map_err(tok_err)?;
    }
    for ip in &spec.allow_ip {
        let i = ip.as_str();
        builder
            .add_fact(fact!("allow_ip({i})", i = i))
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

// ── Task 3: Authorization ──────────────────────────────────────────────────

/// Carries the verified outcome of `parse_and_authorize`.
pub(crate) struct Authorized {
    pub token_id: String,
    pub caps: Vec<CapInfo>,
}

fn root_public_from_hex(root_pub_hex: &str) -> Result<PublicKey, String> {
    let raw = hex::decode(root_pub_hex.trim_start_matches("0x"))
        .map_err(|e| format!("bad pubkey hex: {e}"))?;
    PublicKey::from_bytes(&raw).map_err(|e| format!("bad pubkey: {e}"))
}

fn extract_caps(authorizer: &mut Authorizer) -> Result<Vec<CapInfo>, String> {
    let caps: Vec<(String, i64)> = authorizer
        .query_all(rule!("out($a, $v) <- cap($a, $v)"))
        .map_err(|e| format!("cap query: {e}"))?;
    let windows: Vec<(String, i64, i64)> = authorizer
        .query_all(rule!("out($a, $s, $l) <- window($a, $s, $l)"))
        .map_err(|e| format!("window query: {e}"))?;
    let autos: Vec<(String, i64)> = authorizer
        .query_all(rule!("out($a, $v) <- auto_limit($a, $v)"))
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
            CapInfo { asset, cumulative, window_seconds: ws, window_limit: wl, auto_limit: auto }
        })
        .collect())
}

pub(crate) fn token_id_of_internal(authorizer: &mut Authorizer) -> Result<String, String> {
    let ids: Vec<(String,)> = authorizer
        .query_all(rule!("out($id) <- token_id($id)"))
        .map_err(|e| format!("token_id query: {e}"))?;
    ids.into_iter().next().map(|(id,)| id).ok_or_else(|| "token_id missing".into())
}

/// Parse a token, inject ambient facts, run checks + `allow if true`, then
/// extract the token_id and caps.  Returns `Err(reason)` on any caveat failure.
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

    // Build the authorizer with ambient facts and the allow policy.
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
    );
    // Load the token into the authorizer, then verify all checks and policies.
    authorizer.add_token(&biscuit).map_err(|e| format!("authorizer build: {e}"))?;
    authorizer.authorize().map_err(|e| format!("denied: {e}"))?;

    let token_id = token_id_of_internal(&mut authorizer)?;
    let caps = extract_caps(&mut authorizer)?;
    Ok(Authorized { token_id, caps })
}

/// UniFFI export: parse a token and return its caps without running authorization
/// (no request context needed — for the UI to display a token's permissions).
#[uniffi::export]
pub fn token_caps(token: String, root_pub_hex: String) -> Result<Vec<CapInfo>, AgentError> {
    let public = root_public_from_hex(&root_pub_hex).map_err(AgentError::TokenError)?;
    let biscuit = Biscuit::from_base64(&token, public).map_err(tok_err)?;
    let mut authorizer = biscuit.authorizer().map_err(tok_err)?;
    extract_caps(&mut authorizer).map_err(AgentError::TokenError)
}

/// UniFFI export: return the stable token_id for a biscuit without running authorization
/// (no request context needed — for correlating a token to ledger records).
#[uniffi::export]
pub fn token_id_of(token: String, root_pub_hex: String) -> Result<String, AgentError> {
    let public = root_public_from_hex(&root_pub_hex).map_err(AgentError::TokenError)?;
    let biscuit = Biscuit::from_base64(&token, public).map_err(tok_err)?;
    let mut authorizer = biscuit.authorizer().map_err(tok_err)?;
    super::token::token_id_of_internal(&mut authorizer).map_err(AgentError::TokenError)
}

/// Deterministic 16-byte hex id from the spec (account + scopes + caps + ttl).
fn blake_id(spec: &TokenSpec) -> Result<String, AgentError> {
    let mut seed = spec.account.clone();
    for s in &spec.scopes {
        seed.push_str(s.as_tag());
    }
    for c in &spec.caps {
        seed.push_str(&format!("{}{}{}{}{}", c.asset, c.cumulative, c.auto_limit, c.window_seconds, c.window_limit));
    }
    if let Some(t) = spec.ttl_unix {
        seed.push_str(&t.to_string());
    }
    let h = crate::hashing::blake2b_256(hex::encode(seed))
        .map_err(|e| AgentError::TokenError(format!("blake2b failed: {e}")))?;
    Ok(h.trim_start_matches("0x").chars().take(32).collect())
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

    #[test]
    fn mint_token_bad_secret_hex_returns_token_error() {
        let err = mint_token(sample_spec(), "not-valid-hex!!".into()).unwrap_err();
        assert!(matches!(err, AgentError::TokenError(_)));
    }

    #[test]
    fn token_caps_bad_pubkey_returns_token_error() {
        let (token, _) = keypair_and_token(sample_spec());
        let err = token_caps(token, "bad-pubkey-hex".into()).unwrap_err();
        assert!(matches!(err, AgentError::TokenError(_)));
    }

    #[test]
    fn parse_and_authorize_bad_pubkey_returns_err() {
        let (token, _) = keypair_and_token(sample_spec());
        let r = parse_and_authorize(&token, "bad-hex", "send_ckb", "ckt1qexample", "ckt1qto", "10.0.0.1", 1);
        assert!(r.is_err());
    }

    #[test]
    fn parse_and_authorize_malformed_token_returns_err() {
        let kp = agent_root_keypair();
        let r = parse_and_authorize("not-a-valid-biscuit-base64", &kp.public_hex, "send_ckb", "ckt1qexample", "ckt1qto", "10.0.0.1", 1);
        assert!(r.is_err());
    }

    #[test]
    fn token_id_of_matches_decide_token_id() {
        let (token, pubhex) = keypair_and_token(sample_spec());
        let id = token_id_of(token.clone(), pubhex.clone()).unwrap();
        assert!(!id.is_empty());
        // Same token id surfaced by parse_and_authorize.
        let a = parse_and_authorize(&token, &pubhex, "send_ckb", "ckt1qexample", "ckt1qto", "10.0.0.1", 1_700_000_000).unwrap();
        assert_eq!(id, a.token_id);
    }
}
