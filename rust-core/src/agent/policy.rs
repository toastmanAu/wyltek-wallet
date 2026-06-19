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

    #[test]
    fn deny_wrong_account() {
        let to = real_addr();
        let (t, p, _a) = setup(20, 100, 0, 0);
        // Generate a different address using a distinct seed
        let other_kp = crate::keys::generate_secp256k1_keypair("22".repeat(16), "m/44'/309'/0'/0/0".into()).unwrap();
        let other_account = crate::keys::public_key_to_ckb_address(other_kp.public_key_hex, "testnet".into()).unwrap();
        let d = decide(t, p, intent(5, "n1", &to), fresh_view(), ctx(&other_account, 1));
        assert!(matches!(d, Decision::Deny { .. }), "token bound to a different account must be denied");
    }

    #[test]
    fn deny_wrong_scope_op() {
        let to = real_addr();
        let (t, p, a) = setup(20, 100, 0, 0);
        let bad = Intent { op: "send_udt".into(), asset: "CKB".into(), to: to.clone(), amount: 5, nonce: "n1".into() };
        let d = decide(t, p, bad, fresh_view(), ctx(&a, 1));
        assert!(matches!(d, Decision::Deny { .. }), "op outside the token's granted scope must be denied");
    }

    #[test]
    fn deny_negative_amount() {
        let to = real_addr();
        let (t, p, a) = setup(20, 100, 0, 0);
        let d = decide(t, p, intent(-1, "n1", &to), fresh_view(), ctx(&a, 1));
        assert!(matches!(d, Decision::Deny { ref reason } if reason.contains("negative")));
    }

    #[test]
    fn deny_no_cap_for_asset() {
        let to = real_addr();
        let (t, p, a) = setup(20, 100, 0, 0);
        // Token has cap only for CKB; request for a different asset should be denied.
        let bad = Intent { op: "send_ckb".into(), asset: "sUDT".into(), to: to.clone(), amount: 5, nonce: "n1".into() };
        let d = decide(t, p, bad, fresh_view(), ctx(&a, 1));
        assert!(matches!(d, Decision::Deny { ref reason } if reason.contains("no cap for asset")));
    }

    #[test]
    fn window_within_limit_allows() {
        // window_seconds > 0 and window_limit > 0 but amount is below the limit — should allow.
        let to = real_addr();
        let (t, p, a) = setup(1000, 100_000, 86400, 50);
        let view = LedgerView { cumulative_spent: 10, window_spent: 10, nonce_seen: false };
        let d = decide(t, p, intent(5, "n1", &to), view, ctx(&a, 1));
        // 10 + 5 = 15 <= 50 window_limit -> allowed (auto, since 5 <= 1000 auto_limit)
        assert!(matches!(d, Decision::AllowAuto { amount: 5, .. }));
    }
}
