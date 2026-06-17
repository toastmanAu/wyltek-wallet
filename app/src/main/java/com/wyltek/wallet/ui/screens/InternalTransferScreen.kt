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
