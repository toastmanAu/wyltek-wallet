# Prepaid D3 (Vault) device checkpoint

**Dates:** 2026-10-14 23:00 to 2026-10-15 17:40 ACDT
**Phone:** Samsung SM-A536E, Android 16 (SDK 36), build A536EXXSEEYD9
**Build:** wallet `main@57bd67a`, then branch `fix/duplicate-uniffi-bindings` (`2343ea6`, `019b696`, `e6e0d52`), installed with `adb install -r`
**Network:** CKB testnet (Pudge) via `https://testnet.ckbapp.dev`
**Merchant funding lock:** secp256k1_blake160, args `0x61120f9d8bbbe4bde1bdd8f24cc766c3f756d070`
**Tags:** three NTAG21x stickers (UIDs `04a28302…`, `04ebbb02…`, `040c5f82…`), accepted by the NTAG21x check. The app does not log the exact model (213/215/216). One non-NTAG21x card was used for the refusal.

## Result

The D3 plan Task 16 device steps pass, with the deviations listed below. The checkpoint found three bugs. All three are fixed on `fix/duplicate-uniffi-bindings`, and each fix was confirmed on the phone. The branch is not merged and not pushed.

## Transactions

Explorer: `https://pudge.explorer.nervos.org/transaction/<hash>`

| Step | Tx | Block | Reserve cell |
|---|---|---|---|
| Card 1 issue 1000 | [`0x08b03ba7…2a93`](https://pudge.explorer.nervos.org/transaction/0x08b03ba796db4272cd428d8cbd87ab54c39d445ce4c07c1ba3a3ba9695e12a93) | 22739580 | 1061 CKB |
| Card 2 issue 1000 | [`0xc79db4c0…0e38`](https://pudge.explorer.nervos.org/transaction/0xc79db4c055d9b8d65508c9295da6634d043867657613a2e0c01986d0cfff0e38) | 22739599 | 1061 CKB |
| Card 1 load 50 | [`0x4d0298fe…5384`](https://pudge.explorer.nervos.org/transaction/0x4d0298fe9d7d627a44538f3ec9a24c506df6a21b8829da566e7eaa6f6ba15384) | 22739692 | 1061 → 1111 CKB |
| Card 3 issue 20 (torn write) | [`0x14a08135…5d7e`](https://pudge.explorer.nervos.org/transaction/0x14a081353e032f7c2129870fedda861980aea1025d6469064234d6e5ba205d7e) | 22739784 | 81 CKB |
| Card 1 cash-out 1050 | [`0x4a1eb078…aeed`](https://pudge.explorer.nervos.org/transaction/0x4a1eb0786f52566ec42e26e51f4864a427d8beed398864a578186181bd3aaeed) | 22744869 | spent: 1050 CKB to the scanned address, the rest back to the merchant |
| Card 1 re-issue 1000 (wiped tag) | [`0xec83073f…c5`](https://pudge.explorer.nervos.org/transaction/0xec83073fa93d0e62c0ee0569cd25befe7828c797d82ce68fe13b4293b1725bc5) | 22747240 | 1061 CKB |
| Card 1 load 502 | [`0xbc66449c…c3`](https://pudge.explorer.nervos.org/transaction/0xbc66449cdcc23886f9488c67340122cada0d156af3de12b573e53fcd0e96c2c3) | 22747247 | 1061 → 1563 CKB |
| Card 3 void | [`0xb5b46202…41`](https://pudge.explorer.nervos.org/transaction/0xb5b46202482e9a624dac9ecc5a44216ec9de96399af55e6092488a8856d0c441) | 22747407 | spent: reserve (81) + one fee input → one merchant output |

**Cards left live:**
- Card 1: reserve `7adcde50…`, balance 1502.000.
- Card 2: reserve `ca09acda…`, balance 1000.000. Kept for D2/D4.

## Timings (from the audit CSV `at_ms`)

| Action | requested → broadcast | → committed | → written |
|---|---|---|---|
| Cash-out (card 1) | 165 ms | 38.4 s | n/a (the wipe is a separate step; done +9 ms after commit) |
| Void (card 3) | 171 ms | 22.5 s | n/a |
| Load 502 (card 1) | 156 ms | 28.9 s | +15.6 s (waits for the card tap) |

Almost all of the wait is the testnet block. The app's own work is under 200 ms.

## Checks

- **One-time K_debit banner:** shown.
- **Torn write (card 3):** the card was lifted mid-write. The audit shows `failed` "write interrupted: Tag was lost." between `committed` and `written`. The next tap finished the write.
- **Cash-out:**
  - The flow is now: scan address → Pay out (biometric) → tap card.
  - It paid 1050 CKB to the scanned address. The card closed.
  - The next tap showed the card as closed, and **Wipe card** wiped the tag (audit `repair` "closed card: tag wiped"). The wiped tag then classified as Blank and was issued again.
- **Void:** the transaction has the shape the plan expected (one reserve input, one fee input, one merchant output).
- **Refusals:**
  - A non-NTAG21x card showed the refusal banner.
  - The Blank path was covered by the wiped tag above. A separate never-used NTAG was not tapped.
- **Audit CSV:** exported, 44 rows, one row per phase, columns as `AuditCsv.HEADER`.
- **Settlement endpoints:** run from the laptop on the LAN against `192.168.68.113:8443`, with a token minted with `prepaid_settle`.
  - `GET /v1/prepaid/cards` → 200, two cards (active only; the closed ones are excluded).
  - `POST /v1/prepaid/journal {"entries_hex":"00"}` → 400 "entries must be hex and a multiple of 96 bytes".
  - 96 zero bytes → 200 `{"results":[{"index":0,"verdict":"bad_mac"}]}`.
  - No token → 401.

## Bugs found and fixed

1. **Crash on re-tap** (`2343ea6`).
   - Symptom: `NoSuchFieldError: No field $stable … SlotResult` in `TapClass$Resume.<clinit>`.
   - Cause: the uniffi bindings were checked into both `:app` and `:wallet-core`. The `:app` copy was compiled by Compose, which adds a `$stable` field, but the runtime loaded the `:wallet-core` copy, which has no such field.
   - Fix: deleted the `:app` copy. `BindingsSingleSourceTest` now fails if a copy reappears.
2. **Cash-out unusable** (`019b696`).
   - Symptom: the card had to stay on the phone through the camera address scan, and a failed read cancelled the flow.
   - Fix:
     - The address is scanned first, then the cash-out is armed for that card's UID.
     - The tag is read once, so the card can be lifted after the read.
     - New outcomes: `NeedsTag` (nothing done, stays armed) and `WipePending` (closed on chain, tag wipe outstanding).
     - New **Wipe card** action for closed tags.
   - A code-reviewer pass found one HIGH (a dismissed sheet could leave a hidden re-armed cash-out) and two MEDIUMs. All three are fixed in the same commit. Mutation probes confirmed the tests catch a removed wipe guard and a reverted re-arm rule.
3. **NFC dead after the QR scan** (`e6e0d52`).
   - Cause: the app turned NFC reading back on while the camera was still closing. The system then released its own camera-time NFC hold, and that release left reading off.
   - Fix: turn NFC reading on again 500 ms after the camera becomes available.
   - Verified in NfcService logs: system release at 11:24:19.621, app re-enable at 11:24:20.363, card detected.

## Deviations

- **RPC:** the Settings RPC picker only offers the public testnet endpoint ("Private RPC endpoints can be added in a future update"), so `testnet.ckbapp.dev` was used. `192.168.68.134:8114` was at the same tip.
- **Amounts:** cards were issued at 1000 CKB, not 100. Card 1's load was 50 CKB, as planned.
- **Room 4→5 migration:** not exercised. The database was created fresh at v5 after the 2026-10-13 wipe.
- **`PrepaidDaoTest` (connected test):** not run. `connectedAndroidTest` uninstalls the app and wipes the wallet on this phone.
- **Cash-out flow** differs from plan Step 4: address first, then tap the card, and wiping the tag is a separate step.

## Follow-ups

- Add a custom RPC endpoint entry in Settings.
- Load `written`/`done` audit rows record `balance_before` as the new balance (e.g. 1502000/1502000). The audit row is built after the commit has already updated the card. The `requested`/`broadcast`/`committed` rows are correct.
- Older plan docs say to copy regenerated bindings into both modules. They need correcting to `:wallet-core` only.
- Review LOW still open: a half-torn slot is left in place when the wipe hits `NO_VALID_SLOT`.
- The Room 4→5 migration needs a device run against a v4 database.
