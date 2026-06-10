# Wyltek Wallet — Swaps + Multi-Chain Plan

Companion plan to `PLAN.md`. Adds a Swaps page and BTC / ETH / SOL multi-chain support. **No custody, no spread, no hardcoded destination addresses.**

---

## 1. Guiding constraints (from product brief)

| # | Constraint | Implementation consequence |
|---|---|---|
| 1 | Zero custody, ever | All swaps initiated by signing locally and sending directly to a *provider-issued, per-quote ephemeral deposit address*. Wallet never receives transit funds. |
| 2 | No hardcoded destination addresses | Every deposit address is fetched fresh from a quote endpoint, displayed to user, and signed once. No static addresses anywhere in source. |
| 3 | No profit | No affiliate fee, no spread mark-up. Where providers offer referral codes, we either set zero fee or explicitly opt out and document the choice in code. |
| 4 | Liability sits with third parties | UX makes it clear: "Quote and execution provided by `<provider>`. By continuing you accept their terms." Each provider gets its own ToS / link in-app. |
| 5 | At least one fiat ramp | Recommend an *aggregator* of ramps rather than a single provider — better coverage, geo fallback, and the aggregator handles compliance with the underlying KYC'd providers. |
| 6 | At least one DEX aggregator | Pure on-chain swap routes (no KYC, no fiat). |
| 7 | Native Nervos DEX surface | First-class CKB swap path via a CKB-native DEX rather than a wrapped/bridged route. |
| 8 | BTC, ETH, SOL added now | Same BIP-39 mnemonic, derived per-chain via BIP-44 paths. Public-key + address only on the Kotlin side; signing in Rust. |

---

## 2. Current wallet snapshot

What exists today that swaps will sit on top of:

- `rust-core/` — BIP-39 mnemonic, secp256k1 keys, CKB address encode/decode, transaction builder, signing. ML-DSA-65 PQ keys.
- `wallet-core/` — `AccountManager`, `ChainProvider` (CKB RPC with failover, health checks), `SeedVault` (StrongBox/AES-256-GCM), `TransactionBuilder` (CKB only).
- `app/ui/screens/` — `HomeScreen`, `AssetsScreen`, `MarketplaceScreen`, `MessagesScreen`, `SettingsScreen` + nav.
- `AppNavigation.kt` bottom bar = **Home, Assets, Market, Messages, Settings**.

What's missing for swaps:

- No multi-chain key derivation (only CKB path used).
- No abstraction of "an account on a chain" — `WalletAccount` is implicitly CKB.
- No HTTP client wired for third-party APIs.
- No price/quote model, no swap session model, no quote-expiry handling.

---

## 3. Navigation restructure

### Bottom bar (after)

```
Home   |   Swaps   |   Market   |   Messages   |   Settings
```

`Swaps` replaces `Assets` in the bottom bar. Icon: `Icons.Default.SwapHoriz` (or `CurrencyExchange`).

### Assets relocated

Home screen gets a new card row below the balance card:

```
[ View Assets ]   [ Marketplace listings ]
```

`View Assets` deep-links to the existing `AssetsScreen` route. Route stays in `Screen` sealed class, just removed from `bottomBarScreens`. No code in `AssetsScreen` itself needs to change.

### Files touched (planning level — no code yet)

| File | Change |
|---|---|
| `AppNavigation.kt` | Replace `Screen.Assets` with `Screen.Swaps` in `bottomBarScreens`. Add `Screen.Swaps` sealed object. Register `composable(Screen.Swaps.route)`. |
| `HomeScreen.kt` | Add an "Assets" quick-tile that navigates to `Screen.Assets`. |
| `SwapsScreen.kt` | **new** — see §6 for layout. |
| `SwapsViewModel.kt` | **new** — orchestrates providers. |

---

## 4. Multi-chain key model (one mnemonic, four chains)

### Derivation paths (BIP-44 / SLIP-0010)

| Chain | Curve | Path | Address format |
|---|---|---|---|
| CKB | secp256k1 | `m/44'/309'/0'/0/0` | bech32m, secp256k1_blake160 (already done) |
| BTC | secp256k1 | `m/86'/0'/0'/0/0` (Taproot default) + `m/84'/0'/0'/0/0` (native segwit fallback) | bech32m / bech32 |
| ETH | secp256k1 | `m/44'/60'/0'/0/0` | keccak256(pubkey)[12..] + 0x prefix, EIP-55 checksum |
| SOL | ed25519 | `m/44'/501'/0'/0'` (SLIP-0010 hardened all the way) | base58 of 32-byte pubkey |

### Rust modules to add under `rust-core/src/`

```
chains/
 ├─ mod.rs              -- common ChainKey enum + dispatch
 ├─ ckb.rs              -- (existing logic relocated)
 ├─ btc.rs              -- bitcoin crate, derive_taproot_address, derive_segwit_address
 ├─ eth.rs              -- alloy or ethers-core, address from secp256k1 pubkey
 └─ sol.rs              -- ed25519-dalek + slip10 derivation, base58 encode
```

Public UniFFI functions to expose:

```
fn derive_address(mnemonic: String, chain: Chain, account_index: u32) -> Result<DerivedAddress>
fn sign_btc_psbt(seed_hex: String, path: String, psbt_b64: String) -> Result<String>
fn sign_eth_tx(seed_hex: String, path: String, rlp_hex: String, chain_id: u64) -> Result<String>
fn sign_sol_tx(seed_hex: String, path: String, message_b64: String) -> Result<String>
```

### Kotlin model changes (`Models.kt`)

```kotlin
enum class Chain { CKB, BTC, ETH, SOL }

data class ChainAccount(
    val chain: Chain,
    val derivationPath: String,
    val address: String,
    val publicKeyHex: String
)

// WalletAccount gets:
val chainAccounts: Map<Chain, ChainAccount>
```

For BTC/ETH/SOL we **only derive the public key + address up front** — signing happens lazily when a swap is confirmed, by unlocking the seed via biometrics each time. Same security model as CKB today.

### What we don't ship yet

- No BTC/ETH/SOL balance display from native RPCs in this phase (we surface balances only when needed inside a swap quote). Balance display can be a later phase once we pick light/full-node providers per chain — out of scope here to avoid creep.
- No native send/receive screens for BTC/ETH/SOL yet — swaps are the only outbound path initially. Receive addresses *are* shown so user can fund them externally.

`★ Insight ─────────────────────────────────────`
Deriving the address without immediately wiring full chain connectivity is the right scope. A wallet that only *swaps in* to BTC/ETH/SOL through third parties needs the address (to receive deposits) and a way to sign outgoing swaps (which is just hand-off bytes to providers). Full RPC for each chain is a much bigger compliance/infra question that doesn't have to be solved to ship swaps.
`─────────────────────────────────────────────────`

---

## 5. Third-party provider research and recommendations

### 5.1 Fiat on/off-ramps

Options surveyed (rank by mobile SDK quality + jurisdiction coverage + non-custodial integration story):

| Provider | Type | Coverage | KYC | Integration | Notes |
|---|---|---|---|---|---|
| **Onramper** | Aggregator (recommended) | 180+ countries via 30+ underlying providers | Inherited from underlying provider | Widget SDK, REST | One integration → many providers. Handles geo fallback. Affiliate fee defaults to 0 if you don't set one. |
| **Transak** | Direct | 160+ countries | Yes (Transak handles) | Mobile SDK + REST | Strong AU support, good docs. CKB not natively listed — confirm via API before relying on. |
| **MoonPay** | Direct | 160+ countries | Yes | Mobile SDK | Polished UX. Doesn't currently support CKB; would only be useful for BTC/ETH/SOL legs. |
| **Banxa** | Direct | 100+ countries, AU-headquartered | Yes | REST | Aussie company, strong PayID/AU bank rails. Worth pursuing for AU users specifically. |
| **Ramp Network** | Direct | 150+ countries | Yes | Mobile SDK | No CKB. Strong for ETH/SOL. |
| **Mercuryo** | Direct | 170+ countries | Yes | REST | Good off-ramp coverage. |

**Recommendation:** Use **Onramper as primary** (aggregator → all coverage from one integration), with **Banxa as a parallel option for AU** because they have local bank rails (PayID) that Onramper's underlying providers may not. UX = single "Buy with cash" button that calls Onramper; an AU-detected user gets a "Banxa direct (PayID)" alternative shown.

CKB coverage caveat: As of the latest checks, CKB fiat rails are thin. The fiat-ramp page should be honest — "Fiat → CKB may route via BTC/USDT and a follow-up DEX swap; we'll show the full route before you confirm."

### 5.2 Cross-chain swap aggregators (DEX/CEX hybrid)

| Provider | Type | Custody model | Coverage | Notes |
|---|---|---|---|---|
| **Rango Exchange** | Aggregator (recommended) | Non-custodial, peer-to-pool via underlying DEXes/bridges | 70+ chains incl. BTC, ETH, SOL | Single REST API, returns step-by-step route. Each step is a signed tx in the source chain. CKB not native — would not be the CKB route. |
| **LI.FI** | Aggregator | Non-custodial | EVM + SOL focused | Best for ETH↔SOL↔EVM. No BTC, no CKB. |
| **Squid (Axelar)** | Aggregator | Non-custodial | EVM + SOL | EVM-first. |
| **ChangeNOW** | Instant-swap broker | **Custodial during swap** (provider holds 1–2 mins) | 1000+ assets incl. BTC/ETH/SOL/CKB | Fastest path to BTC↔CKB and similar exotic legs. Liability sits with ChangeNOW. |
| **SimpleSwap** | Instant-swap broker | Custodial during swap | Wide incl. CKB | Same model as ChangeNOW, alternative provider. |
| **THORChain** | Native cross-chain L1 | Non-custodial (vault-based, audited) | BTC/ETH/SOL native + others | Best non-custodial BTC↔ETH path. No CKB. |

**Recommendation:** Two providers to start:

1. **Rango** for non-custodial routes where they exist (BTC↔ETH↔SOL combos).
2. **ChangeNOW** for any leg involving CKB and for users who prefer simplicity over a multi-step on-chain route.

The Swaps page presents both, labels them honestly (`Non-custodial route via Rango` vs `Custodial swap via ChangeNOW (1–2 min)`), and lets the user pick.

### 5.3 Native CKB DEX

Options as of mid-2026:

| DEX | Type | API status | Notes |
|---|---|---|---|
| **UTXOSwap** | AMM on CKB | REST quote API | The most mature CKB native DEX. SUDT and xUDT pairs. |
| **CKBDEX / Tuxapp** | Order book | Varying | Smaller liquidity. |
| **Stable++** | Stable-asset focused | Limited | Useful for stablecoin↔CKB later. |

**Recommendation:** Integrate **UTXOSwap** as the native CKB DEX surface. Quote → user signs locally (we already have full CKB tx-building) → broadcast via existing `ChainProvider`. **This is the only swap route where we construct the on-chain tx ourselves.** All other routes hand off via deposit addresses.

`★ Insight ─────────────────────────────────────`
UTXOSwap is qualitatively different from the other providers because it's the only one where we *construct and sign the swap transaction ourselves*. That's a feature, not a bug — it's the lowest-trust path. But it also means the swap page has two code paths internally: "on-chain swap we build" (CKB DEX) vs "send to deposit address" (everything else). The provider abstraction (§7) makes this difference invisible to the UI.
`─────────────────────────────────────────────────`

---

## 6. Swaps page UX

### Top of page — pair selector

```
┌─────────────────────────────────────────┐
│ You pay                                 │
│ ┌────────────┬──────────────────┐       │
│ │  CKB  ▼   │  100.00          │       │
│ └────────────┴──────────────────┘       │
│  Balance: 482.31 CKB                    │
│                                         │
│       ⇅  (flip)                         │
│                                         │
│ You receive                             │
│ ┌────────────┬──────────────────┐       │
│ │  BTC  ▼   │  0.00041234     │       │
│ └────────────┴──────────────────┘       │
│  → bc1p...your taproot address          │
└─────────────────────────────────────────┘
```

### Below — provider routes

```
ROUTES                                Refresh in 14s
─────────────────────────────────────────────────────
○ Rango (non-custodial)         0.00041234 BTC
  CKB → USDT(ETH) → BTC, 3 steps, ~6 min
  Liability: Rango Exchange · ToS

● ChangeNOW (custodial 1–2 min)  0.00041012 BTC
  Direct CKB → BTC swap
  Liability: ChangeNOW · ToS

○ Native CKB DEX                  N/A
  No BTC pair on UTXOSwap
─────────────────────────────────────────────────────

[ Get quote ]  →  [ Sign and send ]
```

### Fiat ramp entry point

Top-right tab toggle:

```
[ Swap ] [ Buy ] [ Sell ]
```

`Buy` and `Sell` route to the fiat-ramp flow (Onramper widget by default, Banxa shown if AU).

### Critical UI rules

1. **Deposit address always shown before sign.** "You will send 100 CKB to `ckb1q...` (provided by ChangeNOW for swap #abc)". User must explicitly confirm.
2. **Per-quote address; never reused.** Each quote returns a fresh deposit address. We display it and store it bound to the quote ID. No caching of "deposit addresses for provider X".
3. **No success without provider acknowledgement.** After broadcast, the swap page polls the provider's status endpoint and shows the chain-of-custody: "Sent → Provider received → Conversion → Sent to your BTC address".
4. **Provider terms link in every quote card.** Clicking the provider's name opens their ToS in browser.
5. **Slippage and timing warnings.** Show quote expiry countdown; refuse to send after expiry.
6. **No referral codes by default.** Track in code with a top-level constant `WYLTEK_AFFILIATE_FEE_BPS = 0` so any future review confirms it.

---

## 7. Module / file architecture

```
wallet-core/src/main/java/com/wyltek/wallet/core/
├─ chains/                     # NEW — multi-chain key + address surface
│   ├─ ChainRegistry.kt
│   ├─ BtcAccount.kt
│   ├─ EthAccount.kt
│   └─ SolAccount.kt
│
├─ swap/                       # NEW
│   ├─ SwapProvider.kt         # interface: quote, deposit address, status
│   ├─ SwapQuote.kt            # data classes
│   ├─ SwapSession.kt          # in-flight swap state, persisted
│   ├─ providers/
│   │   ├─ UtxoSwapProvider.kt        # CKB-native, we build the tx
│   │   ├─ RangoProvider.kt
│   │   ├─ ChangeNowProvider.kt
│   │   └─ OnramperFiatProvider.kt    # fiat ramp aggregator
│   └─ SwapOrchestrator.kt     # parallel quote, route ranking
│
└─ http/                       # NEW
    └─ HttpClient.kt           # OkHttp wrapper + retry + tor-friendly proxy hook

app/src/main/java/com/wyltek/wallet/ui/screens/
├─ SwapsScreen.kt              # NEW
└─ swap/                       # NEW
    ├─ SwapPairSelector.kt
    ├─ QuoteCard.kt
    └─ DepositConfirmDialog.kt

app/src/main/java/com/wyltek/wallet/data/
└─ SwapsViewModel.kt           # NEW
```

### `SwapProvider` interface sketch (planning, not committed code)

```
interface SwapProvider {
    val id: String                // "rango", "changenow", "utxoswap", "onramper"
    val displayName: String
    val custodyModel: CustodyModel  // NON_CUSTODIAL | TRANSIT_CUSTODIAL
    val termsUrl: String

    suspend fun supports(from: Asset, to: Asset): Boolean
    suspend fun quote(req: QuoteRequest): Quote
    suspend fun confirm(quote: Quote): SwapSession   // returns deposit address + payload
    suspend fun status(session: SwapSession): SwapStatus
}
```

For non-custodial CKB-native (UTXOSwap), `confirm()` returns a *transaction we need to sign and broadcast ourselves*. For everything else, it returns a *deposit address we need to fund*. The UI sees one type — `SwapSession` — with a discriminator.

---

## 8. Security and privacy

| Concern | Mitigation |
|---|---|
| Phishing provider, swapped destination address | Pin provider API hostnames; certificate-pin the high-risk ones (Rango, ChangeNOW, Onramper). Reject downgrades. |
| Quote tampering between display and sign | Sign over the *exact* deposit address the user saw. Store quote payload locally with the SwapSession; re-display before signing. |
| Provider outage during a swap | `SwapSession` is persisted in encrypted local DB. Status poll continues across app restarts. User can recover/cancel from a "Pending swaps" panel. |
| User leaks privacy via shared address reuse | Each quote draws a fresh receiving address for the destination chain when our derivation supports it (BTC gap-limit, ETH same address by convention, SOL same key, CKB fresh). |
| Compliance pull-in from provider integration | Document in repo: "We are an integrator. KYC/AML responsibility sits with the provider. We perform no identity collection." |
| Provider-side referral fee accidentally enabled | Constant `WYLTEK_AFFILIATE_FEE_BPS = 0` + integration tests asserting outgoing requests don't carry a `ref=` / `affiliate=` parameter. |

---

## 9. Legal posture recap (from prior discussion, condensed)

Position the wallet maintains across all routes:

> Wyltek Wallet is non-custodial software. Quotes, custody during conversion (if any), KYC, and execution are performed by the named third-party provider. By initiating a swap you accept that provider's terms.

This posture is consistent with how MetaMask Swaps, Phantom, Rabby and Trust Wallet operate, all of which have survived regulatory scrutiny across US/EU/UK/AU. Recommended action items separate from code:

- Add provider names + ToS links to wallet `Terms` settings screen.
- Get a fintech-specialist lawyer review of combined BlackBox POS + Wyltek Wallet posture before public release (~$3–5K AUD).
- Watch AGD's 2026 digital-asset reforms for any "control" wording that could pull non-custodial wallets in.

---

## 10. Build phases

### Phase S1 — Nav restructure (smallest atomic change)

- Add `Screen.Swaps` to nav, swap it into `bottomBarScreens` in place of `Assets`.
- Add "View Assets" tile on `HomeScreen`.
- New `SwapsScreen.kt` shows a "Coming soon" placeholder.

### Phase S2 — Multi-chain derivation (Rust + Kotlin model)

- Add `bitcoin`, `alloy-primitives` (or `ethers-core`), `ed25519-dalek`, `slip10` crates to `rust-core`.
- Add `chains/` module exposing `derive_address(chain, ...)`.
- Extend `WalletAccount` with `Map<Chain, ChainAccount>`.
- `WalletViewModel` derives + persists BTC/ETH/SOL addresses on wallet creation/import.
- New "Receive" UI lets user see each chain's address and copy it.

### Phase S3 — Provider abstraction + UTXOSwap

- `SwapProvider` interface + `SwapOrchestrator` + `SwapSession` persistence.
- First provider: `UtxoSwapProvider` (CKB↔SUDT). Reuses existing CKB tx builder.
- Swap page renders one route, signs locally, broadcasts.

### Phase S4 — ChangeNOW (CKB ↔ BTC/ETH/SOL)

- `ChangeNowProvider`. Deposit-address flow for outbound CKB.
- Status polling with foreground notification.
- "Pending swaps" recovery panel.

### Phase S5 — Rango (non-custodial multi-chain)

- `RangoProvider` returning multi-step routes.
- Sign step-by-step in the source chain; UI shows progress per step.
- BTC PSBT signing + ETH RLP signing + SOL message signing (already in Rust from Phase S2).

### Phase S6 — Fiat on-ramp (Onramper + Banxa)

- `OnramperFiatProvider` (widget-launching).
- Detect AU and show Banxa direct as alternative.
- Off-ramp tab uses the same providers' off-ramp flows.

### Phase S7 — Polish

- Quote refresh + expiry countdown.
- Provider health indicator (re-uses RPC health UI pattern).
- Slippage settings.
- Address-pin / cert-pin enforcement.

---

## 11. Resolved decisions

1. **Mnemonic default = 24 words** for multi-chain accounts. 12/18 still selectable for users who request them, but the create-wallet flow defaults to 24 to leave entropy headroom for chains added later.
2. **BTC default = Taproot (`m/86'/0'/0'/0/0`); Segwit (`m/84'`) exposed on request** via a per-account toggle in Receive UI ("Show legacy segwit address"). Both addresses derive from the same seed; user copies whichever the counterparty needs.
3. **ETH chain set = Mainnet + Base + Arbitrum + Optimism + BNB Smart Chain.** All five use the same `m/44'/60'/0'/0/0` derivation (same address, different chain IDs). Chain registry:

   | Chain | chainId | Notes |
   |---|---|---|
   | Ethereum Mainnet | 1 | Primary L1 |
   | Base | 8453 | Coinbase L2, common DEX-aggregator route |
   | Arbitrum One | 42161 | Largest L2 by TVL |
   | Optimism | 10 | OP Stack L2 |
   | BNB Smart Chain | 56 | Major non-EVM-aligned chain with deep swap liquidity |

   Each chain is a row in a `ChainRegistry` map; swap providers (Rango, LI.FI) can route through any of them. UI shows them under one "ETH-family" group on the receive screen so the wallet doesn't look like five separate accounts.

4. **Zero affiliate fee for now; right reserved to introduce a fee later.** Constant `WYLTEK_AFFILIATE_FEE_BPS = 0` in code as the source of truth. **No CI gate** — keeping it as a single constant is sufficient. ToS line to add to in-app Terms screen:

   > Wyltek Wallet currently charges no swap fee. We reserve the right to introduce a transparent service fee on third-party swap routes as the business scales. Any such fee will be displayed in the quote breakdown before you confirm.

5. **UTXOSwap traffic** — proceed under the assumption their public quote API can absorb our traffic at current usage levels. Revisit if/when we see rate-limit responses or before any public marketing push that would spike volume. Track as a pre-launch checklist item, not a phase-S3 blocker.

---

End of plan. Nothing has been integrated; everything above is a recommendation set ready for your review and trim.
