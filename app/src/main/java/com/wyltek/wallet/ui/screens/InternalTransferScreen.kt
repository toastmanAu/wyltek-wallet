package com.wyltek.wallet.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wyltek.wallet.core.chain.NetworkConfig
import com.wyltek.wallet.core.model.CkbAddress
import com.wyltek.wallet.ui.security.BiometricResult
import com.wyltek.wallet.ui.security.rememberBiometricAuth
import com.wyltek.wallet.ui.theme.*
import com.wyltek.wallet.data.WalletViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InternalTransferScreen(
    onBack: () -> Unit = {},
    viewModel: WalletViewModel
) {
    var amount by remember { mutableStateOf("") }
    var authError by remember { mutableStateOf<String?>(null) }
    // Default direction: Classic → PQ (migrating funds into a post-quantum lock).
    var classicToPq by remember { mutableStateOf(true) }

    val uiState by viewModel.uiState.collectAsState()
    val biometric = rememberBiometricAuth()

    val account = uiState.currentAccount
    val subAddresses = account?.addresses.orEmpty()

    // Internal transfer moves CKB between this wallet's own classic and PQ
    // sub-accounts, so it requires both to exist (a hybrid wallet).
    val classicAddress: CkbAddress? = remember(subAddresses, account?.network) {
        account?.let { acc ->
            subAddresses.firstOrNull { !NetworkConfig.isPqLock(it.lockScript.codeHash, acc.network) }
        }
    }
    val pqAddress: CkbAddress? = remember(subAddresses, account?.network) {
        account?.let { acc ->
            subAddresses.firstOrNull { NetworkConfig.isPqLock(it.lockScript.codeHash, acc.network) }
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

        if (classicAddress == null || pqAddress == null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
            ) {
                Text(
                    text = "Internal transfer requires a hybrid wallet with both a " +
                        "classic (secp256k1) and a post-quantum (ML-DSA-65) sub-account. " +
                        "The current wallet only has one of these.",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
            }
            return@Column
        }

        val from = if (classicToPq) classicAddress else pqAddress
        val to = if (classicToPq) pqAddress else classicAddress
        val fromIsPq = NetworkConfig.isPqLock(from.lockScript.codeHash, account!!.network)

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = CardBackground)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Move funds between your own wallets",
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
                        Text(
                            if (fromIsPq) "PQ Wallet" else "Classic Wallet",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            text = from.bech32m.take(14) + "…" + from.bech32m.takeLast(6),
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(onClick = { classicToPq = !classicToPq }) {
                        Icon(
                            Icons.Default.SwapVert,
                            contentDescription = "Swap transfer direction",
                            tint = NeonCyan
                        )
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.End
                    ) {
                        Text("To", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                        Text(
                            if (fromIsPq) "Classic Wallet" else "PQ Wallet",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            text = to.bech32m.take(14) + "…" + to.bech32m.takeLast(6),
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        OutlinedTextField(
            value = amount,
            onValueChange = { amount = it },
            label = { Text("Amount (CKB)") },
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = NeonCyan,
                unfocusedBorderColor = CardBorder
            )
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
        ) {
            Text(
                text = if (classicToPq)
                    "Security note: You are migrating funds into a post-quantum lock."
                else
                    "Security note: You are moving funds out of your post-quantum lock into a classic lock.",
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodySmall,
                color = WarningOrange
            )
        }

        (authError ?: uiState.error)?.let { error ->
            Card(
                colors = CardDefaults.cardColors(containerColor = ErrorRed.copy(alpha = 0.1f))
            ) {
                Text(
                    text = error,
                    modifier = Modifier.padding(12.dp),
                    color = ErrorRed,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        uiState.lastTxHash?.let { txHash ->
            Card(
                colors = CardDefaults.cardColors(containerColor = SuccessGreen.copy(alpha = 0.1f))
            ) {
                Text(
                    text = "Transfer sent: $txHash",
                    modifier = Modifier.padding(12.dp),
                    color = SuccessGreen,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        // Signing from a PQ sub-account requires biometric re-auth, mirroring SendScreen.
        val performTransfer: (ULong) -> Unit = { amountShannons ->
            if (fromIsPq) {
                authError = null
                biometric.authenticate(
                    title = "Confirm transfer",
                    subtitle = "Authenticate to sign this post-quantum transaction",
                    description = "ML-DSA-65 signatures from this account require re-authentication."
                ) { result ->
                    when (result) {
                        is BiometricResult.Success -> viewModel.sendCkb(to.bech32m, amountShannons, from)
                        is BiometricResult.Cancelled -> { /* silent */ }
                        is BiometricResult.Error -> { authError = result.message }
                        is BiometricResult.Unavailable -> {
                            authError = "Set up a device PIN, password, or biometric to send from a PQ account."
                        }
                    }
                }
            } else {
                viewModel.sendCkb(to.bech32m, amountShannons, from)
            }
        }

        Button(
            onClick = {
                val amountCkb = amount.toDoubleOrNull()
                if (amountCkb != null && amountCkb > 0) {
                    val amountShannons = (amountCkb * 100_000_000.0).toULong()
                    performTransfer(amountShannons)
                }
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = NeonCyan),
            enabled = amount.isNotBlank() && !uiState.isLoading
        ) {
            if (uiState.isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    color = DarkBackground
                )
            } else {
                Text("Transfer", color = DarkBackground)
            }
        }
    }
}
