package com.wyltek.wallet.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wyltek.wallet.core.model.AccountType
import com.wyltek.wallet.ui.theme.*
import com.wyltek.wallet.data.WalletViewModel

@Composable
fun HomeScreen(
    onSend: () -> Unit = {},
    onReceive: () -> Unit = {},
    onInternalTransfer: () -> Unit = {},
    onCreateWallet: () -> Unit = {},
    onSettings: () -> Unit = {},
    viewModel: WalletViewModel
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(uiState.currentAccount) {
        if (uiState.currentAccount != null) {
            viewModel.startAutoSync(30_000L)
        }
    }

    DisposableEffect(Unit) {
        onDispose { viewModel.stopAutoSync() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Wyltek Wallet",
                style = MaterialTheme.typography.headlineLarge,
                color = NeonCyan
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    imageVector = if (uiState.isConnected) Icons.Default.CheckCircle else Icons.Default.CloudOff,
                    contentDescription = null,
                    tint = if (uiState.isConnected) SuccessGreen else ErrorRed,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = if (uiState.isConnected) "Block #${uiState.tipBlockNumber}" else "Disconnected",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (uiState.isConnected) SuccessGreen else ErrorRed
                )
                IconButton(onClick = onSettings) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Settings",
                        tint = TextSecondary
                    )
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = CardBackground)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Total Balance",
                        style = MaterialTheme.typography.labelLarge,
                        color = TextSecondary
                    )
                    if (uiState.isSyncing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = NeonCyan
                        )
                    }
                }
                Text(
                    text = uiState.balance,
                    style = MaterialTheme.typography.headlineMedium,
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold
                )
                uiState.currentAccount?.let { account ->
                    Text(
                        text = "${account.name} · ${account.type.name}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                } ?: run {
                    Text(
                        text = "No wallets yet",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                }
            }
        }

        if (uiState.currentAccount != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ActionButton(
                    modifier = Modifier.weight(1f),
                    icon = Icons.AutoMirrored.Filled.Send,
                    label = "Send",
                    onClick = onSend
                )
                ActionButton(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.QrCode,
                    label = "Receive",
                    onClick = onReceive
                )
                ActionButton(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.SwapHoriz,
                    label = "Transfer",
                    onClick = onInternalTransfer
                )
            }
        }

        if (uiState.error != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = ErrorRed.copy(alpha = 0.1f))
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = ErrorRed
                    )
                    Text(
                        text = uiState.error ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = ErrorRed,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { viewModel.clearError() }) {
                        Icon(Icons.Default.Close, contentDescription = "Dismiss", tint = ErrorRed)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        OutlinedButton(
            onClick = onCreateWallet,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = NeonCyan)
        ) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Create Wallet")
        }
    }
}

@Composable
private fun ActionButton(
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Card(
        modifier = modifier,
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = NeonCyan,
                modifier = Modifier.size(24.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = TextPrimary
            )
        }
    }
}
