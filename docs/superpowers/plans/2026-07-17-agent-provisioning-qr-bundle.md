# Agent-Provisioning QR Bundle Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Change the Agent screen's "Minted Token" QR to encode a compact `{"device_id","token"}` JSON bundle the BlackBox POS can scan to provision itself, warning and hiding the QR when the device is not yet paired to the relay.

**Architecture:** A pure Kotlin builder owns the byte-exact POS wire format (manual compact string, no `org.json`). A read-only accessor surfaces the stored relay `device_id`; the Agent ViewModel exposes it in UI state; the "Minted Token" Composable renders the bundle QR when paired, or a warning with no QR when not.

**Tech Stack:** Kotlin, Jetbrains Compose, Android; JUnit 4 for JVM unit tests; ZXing (`QrCodeImage`, already EC-level L); Gradle (`:app` module).

## Global Constraints

- **QR payload format (from the POS, do not deviate):** compact JSON, no spaces — `{"device_id":"<uuid>","token":"<biscuit base64>"}`. The POS literal-substring-matches `"device_id":"` and `"token":"` with `strstr`; a space after any colon breaks it.
- **No `org.json` in the builder or its tests** — Android stubs `org.json` in JVM unit tests (`throw RuntimeException("Stub!")`); this repo has no Robolectric / `returnDefaultValues` / `org.json:json` test dependency. Build the string manually.
- **Values are quote-free by construction** (UUID + URL-safe base64 `[A-Za-z0-9_-=]`) — no escaping required; never wrap a value that could contain `"`.
- **Out of scope, do not touch:** the relay, biscuit mint/caps, intent contract, pairing flow, `QrCodeImage`/`QrCode.kt` (ZXing already defaults to EC-level L / max density).
- **Commits:** conventional (`feat: …`), no attribution footer (repo convention).
- **Test package convention:** `com.wyltek.wallet.<pkg>`, mirrored under `app/src/test/java/...`.
- **Unit test command:** `./gradlew :app:testDebugUnitTest`. **Compile check:** `./gradlew :app:compileDebugKotlin`.

---

## File Structure

- **Create** `app/src/main/java/com/wyltek/wallet/agent/provisioning/AgentProvisioning.kt` — pure bundle builder. One responsibility: the POS wire format.
- **Create** `app/src/test/java/com/wyltek/wallet/agent/provisioning/AgentProvisioningTest.kt` — JVM unit tests for the builder.
- **Modify** `app/src/main/java/com/wyltek/wallet/agent/relay/RelayPairing.kt` — add companion `deviceId(secure)` read-only accessor.
- **Modify** `app/src/main/java/com/wyltek/wallet/agent/ui/AgentViewModel.kt` — add `deviceId` to `AgentUiState`; populate it in `refresh()`.
- **Modify** `app/src/main/java/com/wyltek/wallet/ui/screens/AgentScreen.kt` — render bundle QR when paired; warning + no QR when not.

---

## Task 1: Pure provisioning-bundle builder

**Files:**
- Create: `app/src/main/java/com/wyltek/wallet/agent/provisioning/AgentProvisioning.kt`
- Test: `app/src/test/java/com/wyltek/wallet/agent/provisioning/AgentProvisioningTest.kt`

**Interfaces:**
- Consumes: nothing (leaf).
- Produces: `fun buildProvisioningBundle(deviceId: String?, token: String): String?` in package `com.wyltek.wallet.agent.provisioning` — returns the compact bundle string, or `null` when `deviceId` is null/blank.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/wyltek/wallet/agent/provisioning/AgentProvisioningTest.kt`:

```kotlin
package com.wyltek.wallet.agent.provisioning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentProvisioningTest {

    @Test
    fun `bundle is compact json carrying both keys`() {
        val out = buildProvisioningBundle("dev-123", "tok-abc")
        assertEquals("""{"device_id":"dev-123","token":"tok-abc"}""", out)
    }

    @Test
    fun `bundle contains the POS substring markers and no space after colons`() {
        val out = buildProvisioningBundle("id", "t")!!
        assertTrue(out.contains("\"device_id\":\""))
        assertTrue(out.contains("\"token\":\""))
        assertFalse("must be compact (no space after colon)", out.contains("\": \""))
    }

    @Test
    fun `long url-safe base64 token survives intact and untruncated`() {
        val token = "A".repeat(700) + "_-="
        val out = buildProvisioningBundle("11111111-2222-3333-4444-555555555555", token)!!
        assertTrue("token must appear whole", out.contains(token))
        assertTrue("must end on closing quote+brace", out.endsWith("\"}"))
    }

    @Test
    fun `null deviceId returns null (unpaired guard)`() {
        assertNull(buildProvisioningBundle(null, "tok"))
    }

    @Test
    fun `blank deviceId returns null (unpaired guard)`() {
        assertNull(buildProvisioningBundle("   ", "tok"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.wyltek.wallet.agent.provisioning.AgentProvisioningTest"`
Expected: FAIL — compilation error / unresolved reference `buildProvisioningBundle`.

- [ ] **Step 3: Write minimal implementation**

Create `app/src/main/java/com/wyltek/wallet/agent/provisioning/AgentProvisioning.kt`:

```kotlin
package com.wyltek.wallet.agent.provisioning

/**
 * Compact provisioning bundle for the BlackBox POS, or null if the device is not
 * yet paired to the relay (deviceId null/blank).
 *
 * Format (compact, no spaces — the POS matches the literal substrings
 * `"device_id":"` and `"token":"` with strstr, NOT a JSON parser):
 *   {"device_id":"<uuid>","token":"<biscuit base64>"}
 *
 * Built as a manual string, not org.json.JSONObject: org.json is stubbed in JVM
 * unit tests. Both values are quote-free by construction (UUID + URL-safe base64),
 * so no escaping is required.
 */
fun buildProvisioningBundle(deviceId: String?, token: String): String? {
    val id = deviceId?.ifBlank { null } ?: return null
    return """{"device_id":"$id","token":"$token"}"""
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.wyltek.wallet.agent.provisioning.AgentProvisioningTest"`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/wyltek/wallet/agent/provisioning/AgentProvisioning.kt \
        app/src/test/java/com/wyltek/wallet/agent/provisioning/AgentProvisioningTest.kt
git commit -m "feat: pure provisioning-bundle builder for POS QR"
```

---

## Task 2: Surface relay device_id into Agent UI state

**Files:**
- Modify: `app/src/main/java/com/wyltek/wallet/agent/relay/RelayPairing.kt` (companion object, after `loadConfig`, ~line 91)
- Modify: `app/src/main/java/com/wyltek/wallet/agent/ui/AgentViewModel.kt` (`AgentUiState` ~line 34-44; `refresh()` ~line 57-64)

**Interfaces:**
- Consumes: nothing from Task 1.
- Produces:
  - `RelayPairing.deviceId(secure: AgentSecureStore): String?` — stored device id, or null if never paired.
  - `AgentUiState.deviceId: String?` — nullable field, default `null`, read by Task 3.

**No automated test:** `AgentSecureStore` is a concrete `Context`-bound class and `AgentViewModel` is an `AndroidViewModel`; this is one-line plumbing with no JVM-testable seam. Verified by compile. (The behavior it feeds — the bundle format and unpaired guard — is fully covered by Task 1 and exercised in Task 3.)

- [ ] **Step 1: Add the read-only accessor to `RelayPairing`**

In `RelayPairing.kt`, inside the `companion object` (immediately after the `loadConfig(...)` function, before the closing brace of the companion), add:

```kotlin
        /** Stored relay device id, or null if this device never paired. */
        fun deviceId(secure: AgentSecureStore): String? =
            secure.loadBlob(KEY_RELAY_DEVICE_ID)
                ?.let { String(it, Charsets.UTF_8) }
                ?.ifBlank { null }
```

Do **not** call the private `loadOrGenerateDeviceId()` — this must read only, never generate (a generated id would have no relay-side pairing and would defeat the unpaired guard).

- [ ] **Step 2: Add `deviceId` to `AgentUiState`**

In `AgentViewModel.kt`, change the `AgentUiState` data class (~line 34) to add a field:

```kotlin
data class AgentUiState(
    val tokens: List<TokenRow> = emptyList(),
    val pending: List<PendingRow> = emptyList(),
    val serverRunning: Boolean = false,
    val bindAddress: String? = null,
    val pendingCount: Int = 0,
    val lastMintedToken: String? = null,
    val error: String? = null,
    val relayPaired: Boolean = false,
    val relayUrl: String? = null,
    val deviceId: String? = null
)
```

- [ ] **Step 3: Populate `deviceId` in `refresh()`**

In `AgentViewModel.kt`, in `refresh()` (~line 57), add the field to the `_uiState.value.copy(...)` that `refresh` performs. Locate the existing `copy(` block that sets `tokens = …, serverRunning = …, bindAddress = …` and add:

```kotlin
            deviceId = RelayPairing.deviceId(gateway.secure),
```

(Place it alongside the other relay-derived fields in that same `copy(...)` call. `gateway.secure` is the `AgentSecureStore` already used elsewhere in this file, e.g. the `relayUrl` load at line 58.)

- [ ] **Step 4: Compile check**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL. If `AgentSecureStore` is not already imported in `AgentViewModel.kt`, no new import is needed — the call is `RelayPairing.deviceId(gateway.secure)` and `RelayPairing` is already imported (line 12).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/wyltek/wallet/agent/relay/RelayPairing.kt \
        app/src/main/java/com/wyltek/wallet/agent/ui/AgentViewModel.kt
git commit -m "feat: surface relay device_id into Agent UI state"
```

---

## Task 3: Render bundle QR with unpaired guard

**Files:**
- Modify: `app/src/main/java/com/wyltek/wallet/ui/screens/AgentScreen.kt` (the `uiState.lastMintedToken?.let { token -> … }` "Minted Token" card, ~lines 225-268)

**Interfaces:**
- Consumes:
  - `buildProvisioningBundle(deviceId: String?, token: String): String?` (Task 1).
  - `uiState.deviceId: String?` (Task 2).
- Produces: no downstream consumers (UI leaf).

**No automated test:** Compose UI; verified by compile + the manual POS-scan checkpoint in the spec.

- [ ] **Step 1: Add the import**

In `AgentScreen.kt`, add to the import block (near the other `com.wyltek.wallet.*` imports, e.g. after line 26's `import com.wyltek.wallet.ui.components.QrCodeImage`):

```kotlin
import com.wyltek.wallet.agent.provisioning.buildProvisioningBundle
```

- [ ] **Step 2: Compute the bundle and branch the QR block**

In the "Minted Token" card, replace the QR emission (currently `AgentScreen.kt:248-251`):

```kotlin
                            QrCodeImage(
                                content = token,
                                modifier = Modifier.size(220.dp)
                            )
```

with a bundle-or-warning branch:

```kotlin
                            val bundle = buildProvisioningBundle(uiState.deviceId, token)
                            if (bundle != null) {
                                QrCodeImage(
                                    content = bundle,
                                    modifier = Modifier.size(220.dp)
                                )
                            } else {
                                Text(
                                    text = "Pair this device with the relay before provisioning a POS",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = WarningOrange,
                                    textAlign = TextAlign.Center
                                )
                            }
```

Leave the surrounding card, the "Shown once — copy or scan now" header, the selectable
`SelectionContainer { Text(token) }`, and the Dismiss button unchanged. The selectable raw
token stays visible in both branches (harmless; the POS only reads the QR).

- [ ] **Step 3: Ensure `TextAlign` is imported**

Check the top of `AgentScreen.kt` for `import androidx.compose.ui.text.style.TextAlign`. If it is absent, add it. (Run `grep -n "import androidx.compose.ui.text.style.TextAlign" app/src/main/java/com/wyltek/wallet/ui/screens/AgentScreen.kt`; if no output, add the import.)

- [ ] **Step 4: Compile check**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Run the full unit suite (regression)**

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL — Task 1's tests pass, nothing else broken.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/wyltek/wallet/ui/screens/AgentScreen.kt
git commit -m "feat: emit agent-provisioning bundle QR with unpaired guard"
```

---

## Manual verification (needs the POS — not automatable here)

Per the handoff doc's checkpoint (`~/blackbox-pos/docs/superpowers/WAVE9a-HW-CHECKPOINT.md`):

1. Wallet: pair the device to the relay (Agent screen), then mint a `send_ckb` token with a cap. The "Minted Token" card now shows the **bundle** QR. (Before pairing, confirm the card shows the warning and **no** QR.)
2. POS (flashed): **Settings → Merchant Wallet → Provision (scan token)** → scan the QR → should flip to **"Provisioned"** and survive a power-cycle (NVS-persisted). "Not a valid agent token" ⇒ malformed bundle (pretty-printed/spaces or QR too dense to scan fully).
3. POS **Refund** → scan a customer `ckt1…` address → amount under the auto-limit → **Send refund** → intent routes to the phone → auto-sign (under limit) or approve (over) → POS shows **Sent** + txHash.

---

## Self-Review

- **Spec coverage:** Change 1 (accessor) → Task 2 Step 1. Change 2 (pure builder) → Task 1. Change 3 (UI state field + refresh + screen render + guard) → Task 2 Steps 2-3 + Task 3. Not-changed items (`QrCodeImage`, relay/biscuit/intent/pairing) → untouched. Testing section → Task 1 Steps 1-4. ✅
- **Placeholder scan:** No TBD/TODO; all code steps show complete code; conditional import steps give the exact grep to decide. ✅
- **Type consistency:** `buildProvisioningBundle(deviceId: String?, token: String): String?` identical in Task 1 (produced), Task 3 (consumed). `AgentUiState.deviceId: String?` identical in Task 2 (produced) and Task 3 (`uiState.deviceId`). `RelayPairing.deviceId(secure)` signature identical in Task 2 Step 1 and Step 3. ✅
