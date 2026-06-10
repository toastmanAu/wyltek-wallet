package com.wyltek.wallet.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wyltek.wallet.core.chain.TransactionHistoryItem
import com.wyltek.wallet.data.WalletViewModel
import com.wyltek.wallet.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionDetailScreen(
    txHash: String,
    onBack: () -> Unit = {},
    viewModel: WalletViewModel
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    // Find the transaction in history, or create a placeholder
    val tx = uiState.transactionHistory.find { it.txHash == txHash }

    // Try to fetch status on first load
    var txStatus by remember { mutableStateOf<String?>(null) }
    var isLoadingStatus by remember { mutableStateOf(false) }

    LaunchedEffect(txHash) {
        isLoadingStatus = true
        txStatus = viewModel.getTransactionStatus(txHash)
        isLoadingStatus = false
    }

    val networkConfig = com.wyltek.wallet.core.chain.NetworkConfig.forNetwork(uiState.currentNetwork)
    val explorerUrl = "${networkConfig.explorerBaseUrl}/transaction/${txHash.removePrefix("0x")}"

    val typeLabel = when {
        tx == null -> "Unknown"
        tx.isInput && tx.isOutput -> "Sent (with change)"
        tx.isInput -> "Sent"
        tx.isOutput -> "Received"
        else -> "Unknown"
    }
    val typeColor = when {
        tx == null -> TextSecondary
        tx.isInput && !tx.isOutput -> NeonPink
        !tx.isInput && tx.isOutput -> SuccessGreen
        else -> TextSecondary
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        TopAppBar(
            title = { Text("Transaction Detail") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkSurface)
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Direction badge
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = typeLabel,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = typeColor
                    )
                    if (isLoadingStatus) {
                        Spacer(modifier = Modifier.width(12.dp))
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = NeonCyan,
                            strokeWidth = 2.dp
                        )
                    } else if (txStatus != null) {
                        Spacer(modifier = Modifier.width(12.dp))
                        val statusColor = when (txStatus) {
                            "committed", "confirmed" -> SuccessGreen
                            "pending", "proposed" -> CyberYellow
                            else -> TextSecondary
                        }
                        Text(
                            text = txStatus!!.replaceFirstChar { it.uppercase() },
                            style = MaterialTheme.typography.labelMedium,
                            color = statusColor
                        )
                    }
                }

                // Tx Hash
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "Transaction Hash",
                        style = MaterialTheme.typography.labelMedium,
                        color = TextSecondary
                    )
                    SelectionContainer {
                        Text(
                            text = txHash,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextPrimary
                        )
                    }
                }

                // Block number
                if (tx != null && tx.blockNumber > 0u) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "Block Number",
                            style = MaterialTheme.typography.labelMedium,
                            color = TextSecondary
                        )
                        Text(
                            text = "#${tx.blockNumber}",
                            style = MaterialTheme.typography.bodyLarge,
                            color = NeonCyan
                        )
                    }
                }

                // Explorer link
                Button(
                    onClick = {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(explorerUrl))
                        context.startActivity(intent)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = NeonCyan),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.OpenInBrowser, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("View in Explorer", color = DarkBackground)
                }

                // Network badge
                Text(
                    text = "Network: ${uiState.currentNetwork.name}",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
        }
    }
}
