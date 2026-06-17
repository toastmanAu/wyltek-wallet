# Internal Transfer Screen Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the internal transfer screen so a user can move CKB (both directions) and sUDT (Classic→PQ only) between the two locks of one hybrid wallet.

**Architecture:** Pure Compose UI over the already-on-chain-verified `sendCkb` / `sendToken` ViewModel methods (both accept `fromCkbAddress`). All testable decision logic (asset eligibility, amount parsing, balance checks) is extracted into a pure, JVM-unit-tested `InternalTransferLogic` object so the view layer stays thin. No changes to `WalletRepository`, `wallet-core`, or `rust-core`.

**Tech Stack:** Kotlin, Jetbrains Compose (Material3), JUnit 4 (new `app/src/test` source set), existing `WalletViewModel` / `NetworkConfig` / biometric helpers.

---

## File Structure

- `app/src/main/java/com/wyltek/wallet/ui/screens/InternalTransferLogic.kt` — **new.** Pure logic: `Direction` enum, `tokensEnabled`, `parseCkbToShannons`, `parseTokenUnits`, balance checks. No Android/framework deps.
- `app/src/test/java/com/wyltek/wallet/ui/screens/InternalTransferLogicTest.kt` — **new.** JUnit tests for the above (first unit test in the app module).
- `app/src/main/java/com/wyltek/wallet/ui/screens/InternalTransferScreen.kt` — **modify.** Replace the stub body with the stateful screen consuming `InternalTransferLogic` + `uiState`.
- `app/src/main/java/com/wyltek/wallet/ui/navigation/AppNavigation.kt:226-227` — **modify.** Pass `viewModel` into `InternalTransferScreen`.

**Spec note / deliberate v1 refinement:** the spec says the Send button is disabled until `amount ≤ source balance`. Per-lock balance is not exposed in `uiState` (only the account-level `balanceCkb` and per-token `tokenBalances[].amount`), and adding it would mean ViewModel/Repository work that the spec excludes. So v1 uses those available figures as an **advisory** cap (powering Max + a soft check) and relies on the repository's existing insufficient-balance error as the **authoritative** guard. This is called out here so it is an explicit decision, not a silent gap.

---

## Task 1: Pure transfer logic + unit tests

**Files:**
- Create: `app/src/main/java/com/wyltek/wallet/ui/screens/InternalTransferLogic.kt`
- Test: `app/src/test/java/com/wyltek/wallet/ui/screens/InternalTransferLogicTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/wyltek/wallet/ui/screens/InternalTransferLogicTest.kt`:

```kotlin
package com.wyltek.wallet.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

class InternalTransferLogicTest {

    // --- tokensEnabled -------------------------------------------------------
    @Test fun tokens_enabled_from_classic_source() {
        assertTrue(InternalTransferLogic.tokensEnabled(sourceIsPq = false))
    }

    @Test fun tokens_disabled_from_pq_source() {
        assertFalse(InternalTransferLogic.tokensEnabled(sourceIsPq = true))
    }

    // --- parseCkbToShannons --------------------------------------------------
    @Test fun parses_whole_ckb() {
        assertEquals(100_000_000uL, InternalTransferLogic.parseCkbToShannons("1"))
    }

    @Test fun parses_fractional_ckb_to_shannons() {
        assertEquals(150_000_000uL, InternalTransferLogic.parseCkbToShannons("1.5"))
        assertEquals(1uL, InternalTransferLogic.parseCkbToShannons("0.00000001"))
    }

    @Test fun rejects_more_than_8_decimals() {
        assertNull(InternalTransferLogic.parseCkbToShannons("0.000000001")) // 9 dp = sub-shannon
    }

    @Test fun rejects_zero_negative_and_garbage_ckb() {
        assertNull(InternalTransferLogic.parseCkbToShannons("0"))
        assertNull(InternalTransferLogic.parseCkbToShannons("-5"))
        assertNull(InternalTransferLogic.parseCkbToShannons(""))
        assertNull(InternalTransferLogic.parseCkbToShannons("abc"))
    }

    // --- parseTokenUnits -----------------------------------------------------
    @Test fun parses_token_units() {
        assertEquals(BigInteger("30000"), InternalTransferLogic.parseTokenUnits("30000"))
    }

    @Test fun rejects_zero_negative_and_garbage_tokens() {
        assertNull(InternalTransferLogic.parseTokenUnits("0"))
        assertNull(InternalTransferLogic.parseTokenUnits("-1"))
        assertNull(InternalTransferLogic.parseTokenUnits("1.5")) // tokens are integer units
        assertNull(InternalTransferLogic.parseTokenUnits(""))
    }

    // --- balance checks ------------------------------------------------------
    @Test fun ckb_within_balance() {
        assertTrue(InternalTransferLogic.ckbAmountWithinBalance(100uL, 100uL))
        assertFalse(InternalTransferLogic.ckbAmountWithinBalance(101uL, 100uL))
    }

    @Test fun token_within_balance() {
        assertTrue(InternalTransferLogic.tokenAmountWithinBalance(BigInteger("5"), BigInteger("5")))
        assertFalse(InternalTransferLogic.tokenAmountWithinBalance(BigInteger("6"), BigInteger("5")))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.wyltek.wallet.ui.screens.InternalTransferLogicTest"`
Expected: FAIL — compilation error, `InternalTransferLogic` is unresolved.

- [ ] **Step 3: Write the minimal implementation**

Create `app/src/main/java/com/wyltek/wallet/ui/screens/InternalTransferLogic.kt`:

```kotlin
package com.wyltek.wallet.ui.screens

import java.math.BigDecimal
import java.math.BigInteger

/**
 * Pure decision logic for the internal transfer screen — no Android / Compose
 * dependencies so it is unit-testable on the JVM. The screen is a thin shell
 * over these functions plus the verified sendCkb / sendToken ViewModel methods.
 */
object InternalTransferLogic {

    enum class Direction { CLASSIC_TO_PQ, PQ_TO_CLASSIC }

    /** sUDT sends from a PQ source lock are not yet supported (PQ witness +
     *  dual cell-dep path is a separate refactor), so tokens are CKB-only when
     *  spending from the PQ lock. */
    fun tokensEnabled(sourceIsPq: Boolean): Boolean = !sourceIsPq

    private val SHANNONS_PER_CKB = BigDecimal("100000000")
    private val ULONG_MAX = BigInteger("18446744073709551615")

    /**
     * Parse a CKB decimal string to shannons. Returns null if blank, not a
     * number, non-positive, finer than 8 decimal places (sub-shannon), or
     * beyond ULong range.
     */
    fun parseCkbToShannons(input: String): ULong? {
        val ckb = input.trim().toBigDecimalOrNull() ?: return null
        if (ckb <= BigDecimal.ZERO) return null
        if (ckb.scale() > 8) return null
        val shannons = ckb.multiply(SHANNONS_PER_CKB).toBigIntegerExact()
        if (shannons > ULONG_MAX) return null
        return shannons.toString().toULong()
    }

    /** Parse a raw integer token-units string. Null if blank, not an integer,
     *  or non-positive. */
    fun parseTokenUnits(input: String): BigInteger? {
        val units = input.trim().toBigIntegerOrNull() ?: return null
        if (units <= BigInteger.ZERO) return null
        return units
    }

    fun ckbAmountWithinBalance(shannons: ULong, balanceShannons: ULong): Boolean =
        shannons <= balanceShannons

    fun tokenAmountWithinBalance(units: BigInteger, balanceUnits: BigInteger): Boolean =
        units <= balanceUnits
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.wyltek.wallet.ui.screens.InternalTransferLogicTest"`
Expected: PASS — all assertions green (BUILD SUCCESSFUL).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/wyltek/wallet/ui/screens/InternalTransferLogic.kt \
        app/src/test/java/com/wyltek/wallet/ui/screens/InternalTransferLogicTest.kt
git commit -m "feat(transfer): pure internal-transfer logic + unit tests"
```

---

## Task 2: Internal transfer screen

**Files:**
- Modify: `app/src/main/java/com/wyltek/wallet/ui/screens/InternalTransferScreen.kt` (replace entire file)

- [ ] **Step 1: Replace the stub with the stateful screen**

Overwrite `app/src/main/java/com/wyltek/wallet/ui/screens/InternalTransferScreen.kt` with:

```kotlin
package com.wyltek.wallet.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wyltek.wallet.core.assets.TokenInfo
import com.wyltek.wallet.core.chain.NetworkConfig
import com.wyltek.wallet.core.model.CkbAddress
import com.wyltek.wallet.data.WalletViewModel
import com.wyltek.wallet.ui.security.BiometricResult
import com.wyltek.wallet.ui.security.rememberBiometricAuth
import com.wyltek.wallet.ui.theme.*

private fun shortAddr(a: String): String =
    if (a.length <= 16) a else "${a.take(10)}…${a.takeLast(6)}"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InternalTransferScreen(
    onBack: () -> Unit = {},
    viewModel: WalletViewModel
) {
    val uiState by viewModel.uiState.collectAsState()
    val biometric = rememberBiometricAuth()

    val account = uiState.currentAccount
    val network = account?.network

    val classicAddr: CkbAddress? = remember(account?.id) {
        if (network == null) null else account?.addresses?.firstOrNull {
            !NetworkConfig.isPqLock(it.lockScript.codeHash, network)
        }
    }
    val pqAddr: CkbAddress? = remember(account?.id) {
        if (network == null) null else account?.addresses?.firstOrNull {
            NetworkConfig.isPqLock(it.lockScript.codeHash, network)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        TopAppBar(
            title = { Text("Internal Transfer") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            }
        )

        if (classicAddr == null || pqAddr == null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
            ) {
                Text(
                    text = "Internal transfer needs both a Classic and a PQ lock " +
                        "on this wallet. This wallet has only one lock type.",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = WarningOrange
                )
            }
            return@Column
        }

        var direction by remember { mutableStateOf(InternalTransferLogic.Direction.CLASSIC_TO_PQ) }
        val toPq = direction == InternalTransferLogic.Direction.CLASSIC_TO_PQ
        val source = if (toPq) classicAddr else pqAddr
        val dest = if (toPq) pqAddr else classicAddr
        val sourceIsPq = !toPq
        val tokensEnabled = InternalTransferLogic.tokensEnabled(sourceIsPq)

        var selectedToken by remember { mutableStateOf<TokenInfo?>(null) }
        // If tokens become disabled (swapped to PQ source), fall back to CKB.
        LaunchedEffect(tokensEnabled) { if (!tokensEnabled) selectedToken = null }

        var amount by remember { mutableStateOf("") }
        var authError by remember { mutableStateOf<String?>(null) }

        // Direction card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = CardBackground)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Move funds between your wallet's locks",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("From", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                        Text(if (toPq) "Classic" else "PQ", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            shortAddr(source.bech32m),
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(onClick = {
                        direction = if (toPq) {
                            InternalTransferLogic.Direction.PQ_TO_CLASSIC
                        } else {
                            InternalTransferLogic.Direction.CLASSIC_TO_PQ
                        }
                    }) {
                        Icon(Icons.Default.SwapHoriz, contentDescription = "Swap direction", tint = NeonCyan)
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.End
                    ) {
                        Text("To", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                        Text(if (toPq) "PQ" else "Classic", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            shortAddr(dest.bech32m),
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        // Asset selector
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = selectedToken == null,
                onClick = { selectedToken = null },
                label = { Text("CKB") }
            )
            uiState.tokenBalances.forEach { token ->
                FilterChip(
                    selected = selectedToken?.typeScript == token.typeScript,
                    onClick = { if (tokensEnabled) selectedToken = token },
                    enabled = tokensEnabled,
                    label = { Text(token.symbol) }
                )
            }
        }

        if (!tokensEnabled) {
            Text(
                text = "PQ token sends coming soon — only CKB can be moved from the PQ lock.",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary
            )
        }

        // Amount
        OutlinedTextField(
            value = amount,
            onValueChange = { amount = it },
            label = { Text(if (selectedToken == null) "Amount (CKB)" else "Amount (${selectedToken!!.symbol})") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            trailingIcon = {
                TextButton(onClick = {
                    amount = selectedToken?.amount?.toString()
                        ?: java.math.BigDecimal(uiState.balanceCkb.toString())
                            .movePointLeft(8).stripTrailingZeros().toPlainString()
                }) { Text("Max", color = NeonCyan) }
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = NeonCyan,
                unfocusedBorderColor = CardBorder
            )
        )

        if (sourceIsPq) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
            ) {
                Text(
                    text = "Spending from your post-quantum lock requires device authentication.",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = WarningOrange
                )
            }
        }

        authError?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = WarningOrange)
        }
        uiState.error?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = WarningOrange)
        }
        uiState.lastTxHash?.let { hash ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Transfer submitted", style = MaterialTheme.typography.bodyMedium, color = NeonCyan)
                    Text(shortAddr(hash), style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                    TextButton(onClick = { viewModel.clearLastTxHash(); amount = "" }) {
                        Text("Done", color = NeonCyan)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        // Validity: amount parses & positive (balance is repository-enforced).
        val ckbShannons = if (selectedToken == null) InternalTransferLogic.parseCkbToShannons(amount) else null
        val tokenUnits = if (selectedToken != null) InternalTransferLogic.parseTokenUnits(amount) else null
        val amountValid = ckbShannons != null || tokenUnits != null

        val doSend: () -> Unit = {
            val token = selectedToken
            if (token == null) {
                ckbShannons?.let { viewModel.sendCkb(dest.bech32m, it, source) }
            } else {
                tokenUnits?.let { viewModel.sendToken(dest.bech32m, token.typeScript, it, source) }
            }
        }

        Button(
            onClick = {
                authError = null
                if (sourceIsPq) {
                    biometric.authenticate(
                        title = "Confirm transfer",
                        subtitle = "Authenticate to sign this post-quantum transaction",
                        description = "ML-DSA-65 signatures from the PQ lock require re-authentication."
                    ) { result ->
                        when (result) {
                            is BiometricResult.Success -> doSend()
                            is BiometricResult.Cancelled -> { /* silent */ }
                            is BiometricResult.Error -> { authError = result.message }
                            is BiometricResult.Unavailable ->
                                authError = "Set up a device PIN, password, or biometric to spend from the PQ lock."
                        }
                    }
                } else {
                    doSend()
                }
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = NeonCyan),
            enabled = amountValid && !uiState.isLoading
        ) {
            if (uiState.isLoading) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), color = DarkBackground)
            } else {
                Text("Send Transfer", color = DarkBackground)
            }
        }
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL. (The screen now requires a `viewModel` argument — the call site is fixed in Task 3, so a full `:app:assembleDebug` will fail until then; `compileDebugKotlin` of this file's module still type-checks the file. If the nav call site error blocks compilation, do Task 3 Step 1 first, then re-run.)

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/wyltek/wallet/ui/screens/InternalTransferScreen.kt
git commit -m "feat(transfer): internal transfer screen UI over verified send paths"
```

---

## Task 3: Wire navigation

**Files:**
- Modify: `app/src/main/java/com/wyltek/wallet/ui/navigation/AppNavigation.kt:226-227`

- [ ] **Step 1: Pass the viewModel into the screen**

In `AppNavigation.kt`, find the composable route (around line 226):

```kotlin
            composable(Screen.InternalTransfer.route) {
                InternalTransferScreen(onBack = { navController.popBackStack() })
            }
```

Replace with (the surrounding `composable` blocks already have a `viewModel` in scope — match the exact identifier used by sibling routes such as `Screen.Send`; it is `viewModel` in this file):

```kotlin
            composable(Screen.InternalTransfer.route) {
                InternalTransferScreen(
                    onBack = { navController.popBackStack() },
                    viewModel = viewModel
                )
            }
```

- [ ] **Step 2: Verify the whole app builds**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/wyltek/wallet/ui/navigation/AppNavigation.kt
git commit -m "feat(transfer): wire internal transfer screen into navigation"
```

- [ ] **Step 4: Manual verification (testnet, hybrid wallet)**

Build/install the debug app on a device with a funded hybrid wallet, open Internal Transfer, and confirm:

1. **Classic → PQ, CKB:** enter an amount, Send, tx submits (hash card appears). Verify on https://pudge.explorer.nervos.org.
2. **Swap → PQ → Classic, CKB:** tap the swap icon; From/To flip; biometric prompt fires on Send; tx submits.
3. **Classic → PQ, sUDT:** select a token chip, enter units, Send, tx submits.
4. **Token chips disable from PQ source:** in PQ→Classic direction, token chips are greyed and the "PQ token sends coming soon" hint shows; CKB stays selectable.
5. **Over-balance:** enter more than the source holds; the transfer fails with the repository's insufficient-balance error shown inline (the authoritative guard).
6. **Non-hybrid wallet:** switch to a classic-only (or PQ-only) wallet; the disabled-state card renders and no send is possible.

If verifying against the existing throwaway seed, the harness `secp-balance` / `sudt-balance` commands confirm post-transfer balances.

---

## Self-Review Notes

- **Spec coverage:** direction toggle (Task 2 direction card), asset selector + PQ-source disable (Task 2 chips + `tokensEnabled`), amount parse CKB/token (Task 1 + Task 2 validity), biometric gate on PQ source (Task 2 button), non-hybrid disabled state (Task 2 early return), result/error surfaces (Task 2), reuse of verified `sendCkb`/`sendToken` (Task 2 `doSend`). All covered.
- **Balance guard:** spec's "≤ source balance" is implemented as advisory (Max + repository-enforced error) per the File Structure note, because per-lock balance is not in `uiState`. Explicit, not silent.
- **Type consistency:** `InternalTransferLogic.Direction`, `tokensEnabled(Boolean)`, `parseCkbToShannons(String): ULong?`, `parseTokenUnits(String): BigInteger?` are used identically in Tasks 1 and 2. `TokenInfo.{typeScript, amount, symbol}`, `CkbAddress.{bech32m, lockScript.codeHash}`, `viewModel.{sendCkb,sendToken,clearLastTxHash}`, and `uiState.{currentAccount, tokenBalances, balanceCkb, isLoading, error, lastTxHash}` all match the existing definitions verified during design.
