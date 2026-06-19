# Agent Gateway — Design

**Date:** 2026-06-19
**Status:** Approved (brainstorming) — pending spec review
**Branch:** `feat/agent-gateway`

## Goal

Let an external agent (Claude, Argus, or any program holding a token) request **value-bearing actions** from Blackbox Vault — send CKB, send UDT, manage Nervos DAO positions, send CEMP-PQ messages — **without ever touching private keys**. Authority is carried by a **scoped, time-bounded, spend-capped, optionally IP-locked token** the user mints on-device and hands to the agent. The wallet remains the sole signer; keys never leave the device; the network only ever sees a locally-built, locally-signed transaction.

This is purely additive. It introduces one new subsystem — the **Agent Gateway** — layered over the existing local signer (`resolveSigningContext`, ML-DSA-65 / secp256k1) and asset/tx machinery. No existing flow changes behaviour.

## Why this is the right shape (context)

Blackbox Vault already ships the wallet itself: native Android (Kotlin + Compose), PQ (ML-DSA-65 `fips204`), local-only assembly, StrongBox/Biometric key storage, sUDT, Spore/CoTA/CKBFS, DAO, CEMP-PQ messaging, address validation. The only genuinely missing capability behind the "dedicated agent wallet" request is **agent-callable authorization**. So we extend, not rebuild.

Two protocol facts shaped the design:

- **RGB++ cannot be PQ-protected in its Bitcoin-bound state.** RGB++ uses isomorphic binding: the asset lives in a CKB (xUDT) cell whose RGB++ lock verifies an **SPV proof that the bound BTC UTXO was spent** — spending authority is a *Bitcoin* key (secp/Schnorr), which is classical. The ML-DSA lock only guards cells whose lock script *is* the ML-DSA lock. PQ safety for an RGB++ asset exists only after a "leap" to a CKB-native ML-DSA-locked cell. RGB++ is therefore **deferred entirely** from v1 (see Out of scope).
- **Biscuit is already the ecosystem's token primitive.** The Fiber node requires a biscuit bearer token (`fiber-lib/src/rpc/biscuit.rs`, `docs/biscuit-auth.md`) and uses **channel-scoped biscuit rules** for the watchtower. Building the Agent Gateway on biscuit means one verifier serves both the gateway and (later) the wallet's Fiber RPC client, and a future `fiber` agent scope is a caveat addition, not a new auth stack.

## Decisions locked in brainstorming

| Decision | Choice |
|---|---|
| Build vs rebuild | **Extend** Blackbox Vault |
| Token format | **Biscuit** (attenuable, offline-verifiable, caveat-based) |
| What the agent sends | An **intent**, never a pre-built or signed tx |
| Transport topology | **Hybrid** — Direct (Ktor on tailnet) + Relay (FCM-woken) over one shared on-device core; **Direct shipped first** |
| Approval model | **Tiered** — auto-sign under `auto_limit`, biometric tap above it |
| Spend-cap accounting | **Per-asset**, cumulative lifetime + optional rolling window |
| v1 scopes | `send_ckb`, `send_udt`, `dao`, `messaging` (`fiber` reserved, unimplemented) |
| RGB++ | Deferred entirely |
| Key custody | On-device only, always |

## Architecture

One security core, two transports. The **Policy Engine + Signer + Spend Ledger live on-device and are the single enforcement point.** Direct and Relay are thin adapters that both funnel into the same request handler.

```
                 ┌──────────────── ON DEVICE (phone) ────────────────┐
 Agent ──Direct──┤ Ktor API (tailnet) ─┐                              │
        ──Relay──┤ FCM pull ───────────┤─► Request Handler            │
                 │                      │      │                       │
 Relay           │                      │      ▼                       │
 (wyltek-10700): │              ┌───────────────────────┐             │
  token precheck │              │  POLICY ENGINE (Rust)  │  the only   │
  IP capture     │              │ scope·caps·ttl·IP·rev  │  gate       │
  spend pre-acct │              └───────────────────────┘             │
  FCM sender     │              ALLOW_AUTO │ NEED_APPROVAL │ DENY      │
  NO KEYS        │                  ▼            ▼                      │
                 │             Signer seam    Approval sheet            │
                 │          (ML-DSA/secp,     (FCM → Biometric tap)     │
                 │           StrongBox) ◄──────────┘                    │
                 │                  │                                   │
                 │           Spend Ledger (SQLCipher, atomic w/ sign)   │
                 └───────────────────────┬──────────────────────────── ┘
                                         ▼  signed tx only
                                    CKB testnet (RPC / light client)
```

Built smallest-blast-radius first. Parts 1–2 are the security core (Rust, testable in isolation). Parts 3–5 are device integration. Part 6 is the off-device relay. Part 7 is verification.

### Part 1 — Token model & verifier (Rust, `rust-core/src/agent/token.rs`)

A token is a **biscuit**. A biscuit root Ed25519 keypair is generated on-device at first gateway use; the private half is sealed in StrongBox (separate from the wallet seed — compromise of one is not compromise of the other). Tokens are signed by the root key; verification is offline (no network, no shared secret).

Caveats encode the full authorization. Conceptual schema (the canonical form is biscuit Datalog facts/checks; this table is the surface the UI and Policy Engine reason over):

| Caveat | Meaning |
|---|---|
| `id` | unique token id — revocation handle |
| `account` | which wallet/account (lock script) the token may act for |
| `scope` | subset of `{send_ckb, send_udt, dao, messaging}` (`fiber` reserved) |
| `cap{asset}` | per-asset: `cumulative` lifetime limit + optional `window{seconds, limit}` |
| `auto_limit{asset}` | per-tx and/or cumulative threshold; at/under → auto, over → approval |
| `ttl` | expiry unix-seconds; **absent = infinite validity** |
| `allow_to` | optional recipient address allowlist (empty = any valid address) |
| `allow_ip` | optional source-IP/CIDR allowlist (empty = any) |

`asset` is `"CKB"` or a UDT type-script id (`code_hash:hash_type:args`). Caps and `auto_limit` are tracked independently per asset.

**Verifier responsibilities:** parse, verify root signature, evaluate all caveats against the request context (now, source IP, requested op/asset/amount/recipient), and check the token `id` against the on-device revocation list. Any failure → reject. Verification is pure and deterministic — no I/O — so it is exhaustively unit-testable.

**Attenuation:** because biscuits attenuate, the agent can derive a *further-restricted* token for a sub-agent (e.g. "10 CKB, 1 hour, one address") but can never broaden one. Delegation without re-minting.

### Part 2 — Policy Engine & Spend Ledger (Rust, `rust-core/src/agent/policy.rs`, `ledger.rs`)

The Policy Engine is the decision function:

```
decide(token, intent, ledger, ctx) -> Decision
  Decision = Deny{reason} | AllowAuto | NeedApproval
```

Steps (fail-closed — any ambiguity or error → `Deny`):

1. Verify token (Part 1) against `ctx` (now, source IP).
2. Check `intent.op` ∈ token `scope`; `intent.to` ∈ `allow_to` (if set); address valid (reuse existing validator).
3. Compute the spend this intent represents per asset.
4. Read the ledger; check `cumulative + spend ≤ cap.cumulative` and rolling-window usage `+ spend ≤ window.limit`. Over → `Deny{cap_exceeded}`. Cap math is **saturating** (no overflow).
5. Compare `spend` to `auto_limit`: at/under → `AllowAuto`; over (but within cap) → `NeedApproval`.

The **Spend Ledger** (SQLCipher table) is durable per-token accounting: `cumulative_spent{asset}`, rolling-window buckets, `last_used`, and a `nonce` set for replay defense. The cap **debit and the signature must be one atomic transaction** — debit-then-sign with rollback on sign/broadcast failure — so a cap cannot be double-spent under concurrent requests and a failed broadcast does not burn cap.

**The intent-not-tx invariant lives here.** The agent submits `Intent { op, asset, to, amount, nonce }`. The device builds the actual CKB transaction from its **own** live-cell view, runs `decide`, then signs. A leaked token is bounded strictly by its caveats and caps; the agent can never inject hidden tx mutations (the failure class the `ckb-transactions` feedback log catalogues).

### Part 3 — Signer seam & tx builders (Kotlin, `wallet-core/`)

The gateway calls the **existing** signing/build paths — it adds no new signing code:

- `send_ckb` → existing `sendCkb` path.
- `send_udt` → existing `sendToken` / `resolveSigningContext` path (the just-shipped sUDT-from-PQ work).
- `dao` → existing deposit / withdraw / unlock builders.
- `messaging` → existing CEMP-PQ send path.

A thin `AgentActionDispatcher` maps a validated `Intent` to the right builder, executes it under the atomic ledger transaction, broadcasts, and returns `{ status, txhash | reason, intent_id }`.

### Part 4 — On-device transport: Direct adapter (Kotlin, `app/`)

A Ktor HTTPS server runs inside a **foreground service** (the same host planned for the light-client runtime — one service, two responsibilities). It binds to the **tailnet interface only**. Endpoints:

- `POST /v1/intent` `{ token, intent }` → `200 {txhash}` (auto) · `202 {intent_id}` (queued for approval) · `403 {reason}` (deny)
- `GET  /v1/intent/{id}` → status (for the async approval path)
- `GET  /v1/account` → read-only balances/scopes the token may see (read implied in all scopes)

The adapter does **no policy of its own** — it parses, captures the peer IP into `ctx`, and calls the shared handler. IP-lock on this path = tailnet ACL **and** the `allow_ip` caveat check in the Policy Engine.

### Part 5 — Approval flow & token management UI (Kotlin + Compose, `app/`)

- **Approval:** a `NeedApproval` decision enqueues the intent and fires an FCM (or local) push. Tapping it opens a Compose approval sheet showing op/asset/amount/recipient/which-token; **BiometricPrompt** releases the signature, which then runs the same atomic sign+debit. No tap, no signature.
- **Token management:** mint (biometric-gated) / list / revoke; set scope, per-asset caps + window, `auto_limit`, ttl (or infinite), `allow_to`, `allow_ip`. Live usage bars (spent vs cap, window remaining). Minting renders the token as a copyable string + QR to hand to the agent. Revoke writes the `id` to the revocation list (and pushes it to the relay) — effective on the next request.

### Part 6 — Relay adapter (off-device, new keyless service on wyltek-10700)

An always-on service so the agent can reach the wallet when the phone is behind carrier NAT or in Doze. It holds **no keys and no signing ability**. Responsibilities: accept agent requests, **pre-validate** the token (public verification + revocation list + advisory spend pre-accounting), capture source IP for the `allow_ip` check, queue the intent, and **FCM-wake** the phone, which pulls the intent and runs the *real* Policy Engine + signer. The device **re-validates everything** the relay pre-checked (defense in depth; the relay is an optimisation and an outer fence, never the authority). Built **after** the Direct adapter; both sit on the identical on-device handler, so adding it is new transport code, not a rebuild.

### Part 7 — Verification (Rust unit/property + on-chain harness)

- **Rust unit/property tests** (`agent/` modules), the regression guards:
  - scope matrix (each op allowed only with its scope),
  - cap exhaustion (cumulative and rolling-window edges; saturating math property test),
  - `auto_limit` boundary (at-limit auto, over-limit approval),
  - ttl (expired rejected; absent = infinite accepted),
  - `allow_to` / `allow_ip` allow & deny,
  - revocation (revoked `id` rejected),
  - replay (reused nonce rejected),
  - attenuation (attenuated token cannot exceed parent).
- **Harness commands** (`rust-core/examples/`, following the existing testnet-harness pattern) for on-chain proof:
  - `agent-mint-token <seed> <scope...> <caps...>` → prints a token,
  - `agent-intent <token> <op> <asset> <to> <amount>` → replicates the device path: policy → build → sign → broadcast.
- **On-chain sequence (Pudge testnet, funded hybrid wallet):**
  1. Mint a token: `send_ckb`, cap 100 CKB cumulative, `auto_limit` 20 CKB.
  2. `agent-intent` 5 CKB → expect **auto** → pool-accepted tx hash; ledger shows 5 spent.
  3. `agent-intent` 50 CKB → expect **NeedApproval** (no auto-broadcast in harness; assert decision).
  4. `agent-intent` 120 CKB → expect **Deny{cap_exceeded}**.
  5. Replay step-2 nonce → expect **Deny{replay}**.

## Components & boundaries

| Unit | Responsibility | Depends on |
|------|----------------|------------|
| `agent::token` (Rust) | Biscuit mint/verify, caveat schema, attenuation | biscuit-auth crate, StrongBox-sealed root key |
| `agent::policy` (Rust) | `decide(token, intent, ledger, ctx)` | `agent::token`, address validator |
| `agent::ledger` (Rust) | Atomic per-asset/per-token cap accounting, nonce replay set | SQLCipher |
| `AgentActionDispatcher` (Kotlin) | Intent → existing builder, under atomic sign+debit | `resolveSigningContext`, existing builders, `chainManager` |
| Direct adapter / Ktor service (Kotlin) | Tailnet HTTPS transport, IP capture | foreground service, request handler |
| Approval + token-mgmt UI (Compose) | Mint/revoke/limits, biometric approval | BiometricPrompt, FCM |
| Relay service (off-device) | NAT-traversal queue, pre-check, FCM wake — **no keys** | FCM, public biscuit verify |
| Harness `agent-*` (Rust) | On-chain replica of the device path | signing FFI, policy/ledger |

## Error handling

- Every boundary validated: token parse/verify, caveat eval, intent schema, address (reuse validator), cap math (saturating), ledger atomicity (sign+debit in one tx with rollback), nonce replay.
- **Fail closed:** any error or ambiguity → `Deny`.
- Network/broadcast failure → ledger rolled back (cap not consumed); response is `pending` and idempotent by nonce so the agent can safely retry.
- Relay unreachable → Direct path still works on tailnet; phone unreachable on Direct → relay/FCM path. Neither transport can authorize on its own.

## Testing

- Rust: the Part 7 unit/property suite green; **80%+ coverage** on the `agent::` modules (these hold all the money logic).
- Kotlin: `:app:compileDebugKotlin` green; instrumented test for the approval sheet → biometric → sign path; existing suites unaffected.
- On-chain: the Part 7 Pudge sequence — auto-accepted small spend, approval gate, cap deny, replay deny — is the definition of done for the core.

## Out of scope (YAGNI)

- **RGB++** (any of display / transfer / leap) — separate later track; BTC-bound RGB++ is not PQ-safe by construction.
- **`fiber` scope** — reserved in the caveat schema, not implemented; lands once the wallet has a Fiber RPC client.
- **Multi-scheme PQ** (SPHINCS+/Falcon) — ML-DSA-65 only.
- **Embedded light client** — independent roadmap item; the gateway uses it opportunistically when present but does not block on it (RPC works today).
- **Cloud key custody / server-side signing** — never.

## Definition of done (v1)

1. `agent::token` / `policy` / `ledger` implemented in `rust-core` with the Part 7 unit/property suite green at 80%+ coverage.
2. `AgentActionDispatcher` + Direct (Ktor/tailnet) adapter + token-management & approval UI wired; `:app:compileDebugKotlin` green.
3. Pudge testnet sequence proven via harness: auto small spend pool-accepted, over-`auto_limit` → approval, over-cap → deny, replay → deny.
4. README + PLAN.md updated with the Agent Gateway subsystem and its testnet status.

(Relay adapter, Part 6, is a fast follow on the same core — tracked but not required for v1 done.)

## Fallback

If the on-device Ktor/foreground-service integration cannot be completed this session, fall back to Parts 1–3 + Part 7 (the Rust core + dispatcher + harness): a fully tested, on-chain-proven agent authorization core driven by the harness, with the Android transport/UI as the next slice. The security-critical logic is all in the Rust core, so this fallback still delivers the verifiable heart of the subsystem.
