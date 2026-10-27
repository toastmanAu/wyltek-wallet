# Staff PIN V1.1 (Vault) device checkpoint

**Date:** 2026-10-27 (to be filled in when run)
**Branch:** `feat/staff-pin-v1_1`, commits `3f48962..ffcc0f0`
**Phone:** Samsung SM-A536E, serial `R5CTC07MPYD`
**Verifying:** the Vault half of the staff PIN: Room migration v6 → v7, Set PIN on an enrolled key, enrol with a PIN, the `pin_ver` / `pin_check` fields on `GET /v1/prepaid/staff`, and settlement of a `lockout` journal entry (verdicts, audit row, "Locked on terminal" line, unlock by setting a new PIN). Also that the PIN never reaches logcat.

## Prerequisites

- The build from this branch, installed with `adb install -r` (step 1). Never run `connectedAndroidTest` on this phone: it uninstalls the app and wipes the wallet.
- The two live cards from the D3/V1 checkpoints (`7adcde50…`, `ca09acda…`) and the keys enrolled on 2026-10-16 are still on the phone (the install must preserve data).
- A genuine NTAG21x for step 4. The counterfeit NTAG215 stock is refused at `READ_SIG` ("not a genuine NXP tag", confirmed by NXP TagInfo on 2026-10-16). If that is still the only stock, record `REFUSED (counterfeit)` for step 4 and move on.
- The gateway running on the phone, a token minted with `prepaid_settle`, and the laptop on the LAN. Host and port are on the Agent screen's Server card (the previous checkpoints used `192.168.68.113:8443`). The token is on the "Minted Token" card.
- `K_debit` and `merchant_id` for steps 6 and 7: take them from the provisioning QR, as in `PREPAID-VOUCHERS-V1-CHECKPOINT.md` (Task 16 Step 6 in its plan: "`k_debit` and `merchant_id` come from the same QR"; the merchant id last time was `72a2cacf11fe3cea`). `--merchant` must be the Vault's real merchant_id or the verifier answers `bad_mac`.
- Laptop shell variables:

```bash
export TOKEN=...        # prepaid_settle token, scratch file only, never committed
export K_DEBIT=...      # 32 hex chars
export MERCHANT=...     # 16 hex chars (merchant_id)
```

Use a PIN unlikely to appear elsewhere in logcat: `73105` (used below in place of `1234`).

## Steps

### 1. Build and install, preserving data

```bash
cd ~/wyltek-wallet/.claude/worktrees/staff-pin-v1_1
./gradlew :app:assembleDebug -q && adb -s R5CTC07MPYD install -r app/build/outputs/apk/debug/app-debug.apk
```

Expected: `Success`; the app opens with the wallet intact (no re-onboarding).

Result:

### 2. Migration v6 → v7 on the real DB

Open Prepaid Cards → Staff keys.

Expected: the keys enrolled on 2026-10-16 are listed, each with `No PIN: not sent to terminals` and a **Set PIN** button. The two live cards are still listed under Prepaid Cards. No crash:

```bash
adb -s R5CTC07MPYD logcat -d | grep -E "FATAL|Room|IllegalState"    # expect empty
```

Result:

### 3. Set PIN on an existing key (twice)

Tap **Set PIN** on a listed key, enter `73105`, confirm. Then **Set PIN** again with `73105`. Start `adb -s R5CTC07MPYD logcat -c` just before, and run step 9's grep afterwards.

Expected: first `PIN set (v1)`, then `PIN set (v2)`. The audit screen / CSV shows two `staff_pin_set` rows, with `pin_ver 1` and `pin_ver 2`.

Result:

### 4. Enrol a new key with a PIN

Staff keys → label → PIN → Enrol → tap a genuine NTAG21x.

Expected: the key is listed with `PIN set (v1)`. With counterfeit stock: the enrol is refused at `READ_SIG` ("not a genuine NXP tag"); record `REFUSED (counterfeit)` and continue.

Result:

### 5. `/staff` from the laptop

```bash
curl -sk -H "x-device-token: $TOKEN" https://192.168.68.113:8443/v1/prepaid/staff
```

Expected: every served entry carries `pin_ver` and a 32-hex-character `pin_check`. A key with no PIN set is absent. Nothing else secret appears (only `key_id`, `pin_ver`, `pin_check` are PIN-related). Note the `key_id` and current `pin_ver` of the key used in steps 6 to 8.

Result:

### 6. Lockout report

Build an entry for terminal 7 and POST it as a one-entry journal. `<pin_ver>` is the key's current `pin_ver` from step 5.

```bash
cd ~/wyltek-wallet/rust-core
KEY_ID=...      # from /staff
PIN_VER=...     # from /staff
E=$(cargo run -q --example prepaid_journal_entry -- $K_DEBIT lockout 7 $KEY_ID $PIN_VER --merchant $MERCHANT)
curl -sk -H "x-device-token: $TOKEN" -H 'content-type: application/json' -d "{\"entries_hex\":\"$E\"}" https://192.168.68.113:8443/v1/prepaid/journal   # accepted
```

Expected: `{"results":[{"index":0,"verdict":"accepted"}]}`; a `staff_lockout` audit row; the Staff keys screen shows `Locked on terminal 7 at <time>; set a new PIN to unlock`.

Result:

### 7. Resend the same entry

```bash
curl -sk -H "x-device-token: $TOKEN" -H 'content-type: application/json' -d "{\"entries_hex\":\"$E\"}" https://192.168.68.113:8443/v1/prepaid/journal   # duplicate
```

Expected: verdict `duplicate`; no second `staff_lockout` audit row.

Result:

### 8. Set PIN on the locked key

On the Staff keys screen, **Set PIN** on that key with `73105`.

Expected: `PIN set (v<n+1>)` and the `Locked on terminal 7 …` line disappears. Run `logcat -c` before, step 9's grep after.

Result:

### 9. PIN never logged

Run after steps 3 and again after step 8 (with `logcat -c` before each):

```bash
adb -s R5CTC07MPYD logcat -d | grep -c 73105
```

Expected: `0` both times.

Result:

## What this does not prove

- No POS consumer yet: the POS side that checks the PIN and sends `lockout` is V2. The lockout entry here is hand-built with `prepaid_journal_entry`, so the checkpoint proves the Vault verifies and settles it, not that a terminal produces it.
- The Compose Set PIN / enrol screens have no UI test; steps 2, 3, 4 and 8 are the only coverage of what is on screen.
- The lockout time shown is the Vault's settle time, not the time the terminal locked (the entry's `--ts` is not used for display).
- Step 4 may be refused for lack of genuine NTAG21x stock, in which case enrol-with-PIN on a real tag remains unshown.
