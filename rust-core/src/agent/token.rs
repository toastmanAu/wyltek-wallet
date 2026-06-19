//! Token mint/verify — Task 2.

use crate::agent::types::TokenSpec;
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
