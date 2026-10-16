# Prepaid vouchers V1 (Vault) device checkpoint

**Date:** 2026-10-16, 12:57 to 14:25 ACDT
**Phone:** Samsung SM-A536E, Android 16, build `BP2A.250605.031.A3.A536EXXSOGZH2`
**Build:** branch `feat/prepaid-vouchers-v1`, `7c25fef`, then `d24126d` (fix found here), installed with `adb install -r`
**Network:** CKB testnet (Pudge) via `https://testnet.ckbapp.dev`
**Merchant:** funding lock secp256k1_blake160 args `0x61120f9d8bbbe4bde1bdd8f24cc766c3f756d070`, merchant id `72a2cacf11fe3cea`

## Result

The V1 plan Task 16 device steps pass, except staff key enrol, which could not be shown with a genuine tag (see below). The checkpoint found one bug, fixed in `d24126d` and confirmed on the phone. Nothing is merged or pushed.

## Transactions

Explorer: `https://pudge.explorer.nervos.org/transaction/<hash>`

| Step | Tx | Block | Shape |
|---|---|---|---|
| Voucher 1 issue (COF01, 1 use) | [`0xa0af97b0…7f67`](https://pudge.explorer.nervos.org/transaction/0xa0af97b048790ba66c3569f7585c029e2d98d3cfdef9324fe6329fe875d57f67) | 22756493 | out 0: `0x19b45a500` (69 CKB), funding lock, no type, data `0x4242563100000000`; out 1: change |
| Voucher 1 close (after its redeem settled) | [`0x70c411ab…6272`](https://pudge.explorer.nervos.org/transaction/0x70c411abd58f19be809d414a7ac8fa78b604ba9d1c2143ce0f3d227b01b06272) | 22756731 | spends voucher cell + change → one merchant output |
| Voucher 2 issue | [`0xfff5f013…c546`](https://pudge.explorer.nervos.org/transaction/0xfff5f01361a9a1afee33099e1efdd6795e8e1a48278eeeb6d279232cf0f7c546) | 22756898 | 69 CKB `BBV1` cell + change |
| Voucher 2 void | [`0xf751f98d…c68b`](https://pudge.explorer.nervos.org/transaction/0xf751f98de68815d03f708df529d56eccc90471b6262484a6acd11112dc64c68b) | 22756903 | spends voucher cell + change → one merchant output |

Voucher 1: tag UID `04b7580ecc2a81`, voucher id `9d6ada73da821df0780c9e68cba4c3c2`, product hash `f1a2f1f0b352a6bb` (matches the pinned fixture for `COF01`).

## Checks

- **Migration v5 → v6:** ran on the existing wallet. Both live cards still listed (`7adcde50…` 1502.000, `ca09acda…` 1000.000). No crash, Room or SQLite error in the app's log.
- **Issue:** the arm-and-retap flow worked. Re-tap read the voucher by name, 1 of 1 left, active, with Void.
- **Settlement** (laptop → `192.168.68.113:8443`, entries built with `prepaid_journal_entry --merchant 72a2cacf11fe3cea`):

| Entry | Verdict |
|---|---|
| Redeem, terminal 7, counter 1 | `accepted` |
| Same entry resent | `duplicate` |
| Same counter, terminal 9 | `double_redeem` |
| Price override 450 → 0 AUD | `accepted` |
| Override resent | `duplicate` |

- **Deferred close:** after the redeem, the close ran in the background. While it held the lock, the two follow-up entries were answered `retry` (about 25 s, `PrepaidService.kt:673`), then `duplicate` / `double_redeem` once it finished. This is the intended busy answer and nothing was applied.
- **Audit rows:** `voucher_redeem` (terminal 7, counter 1, staff key `1122334455667788 (unknown)`); `voucher_close` requested → broadcast → committed → done; `price_override` with staff key status `(unknown)`.
- **App:** voucher 1 shows closed.
- **Void:** voucher 2 voided with the tag present; the void tx has the expected shape.
- **`/v1/prepaid/staff`:** `{"staff":[]}` (nothing enrolled).

## Bug found and fixed

**Staff key enrol tap taken by Android's tag screen** (`d24126d`).
- Symptom: at "tap the tag", Android showed "New tag collected, empty tag" instead of the app reading it.
- Cause: NFC reader mode belongs to the activity. Navigating Prepaid → Staff keys, Compose disposed the Prepaid screen about 600 ms after Staff keys had enabled reader mode, and its dispose disabled it again (NfcService log: `setReaderMode flags 385`, `385`, then `0`).
- Fix: a `ReaderModeOwner` token. Only the latest screen to claim reader mode may disable or re-enable it; an old screen's camera re-check is ignored too.
- Tests: `ReaderModeOwnerTest` (3). Mutation probe: making `release` always succeed turned 2 of 3 red. App suite 332 / 0 failures.
- Confirmed on the phone: the enrol tap then reached the app.

## Staff keys: counterfeit tags

After the fix, every tag tried was refused as "not a genuine NXP tag". NXP TagInfo confirmed independently: "signature could not be verified" on originality. The NTAG215 stock used for all cards and vouchers is counterfeit. It answers GET_VERSION like an NTAG215 but cannot produce NXP's signature.

- The refusal path is therefore shown working on real counterfeit tags.
- Prepaid cards and vouchers are unaffected: their security is the `K_debit` MAC, not NXP's signature.
- The ring meant for this step was lost, so no genuine tag was available.

## Deviations

- **Staff key enrol success, `/staff` with one entry, revoke:** not run. No genuine NXP tag available.
- **Real-signature test (`real_ring_signature_verifies_against_nxp_key`):** not added. It needs a genuine tag's UID and signature. Until then the verifier is proven only against self-generated vectors.
- **Override key:** settled with an un-enrolled key id `1122334455667788`, so the audit shows `unknown` rather than `enrolled`.
- **Voucher price:** entered as 5.00 AUD (`500` minor units); the override test used 450 as the old price. The Vault records the terminal's figures and does not check them against the issue price.
- **Provisioning token:** the kept `d803d59f…` token's QR could not be re-shown (shown once, held in memory, lost on reinstall). A new `prepaid_settle` token was minted for this run.
- **Product name:** "Cofee Free" (as typed) was used instead of "Flat white".

## Follow-ups

- Find a genuine NXP NTAG21x (the ring, or bought stock), then: enrol, check `/staff`, revoke, and pin its UID and signature in `Secp128r1Test`.
- Staff key PIN (decided 2026-10-16): PIN set in the Vault at enrol, typed on the POS at override. Spec amendment and V1.1 change to plan.
- The voucher issue screen does not warn that a tag is not genuine NXP. That is fine for vouchers (MAC-secured), but worth a note in the UI copy for staff cards.
- Settles keep answering `retry` while a deferred close holds the lock. POS V2 needs a resend loop that tolerates about 30 to 90 s of `retry`.
