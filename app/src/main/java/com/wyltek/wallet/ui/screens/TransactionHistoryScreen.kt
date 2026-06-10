package com.wyltek.wallet.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wyltek.wallet.core.chain.TransactionHistoryItem
import com.wyltek.wallet.ui.theme.*
import com.wyltek.wallet.data.WalletViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionHistoryScreen(
    onBack: () -> Unit = {},
    onDetail: (String) -> Unit = {},
    viewModel: WalletViewModel
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.refreshTransactionHistory()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TopAppBar(
            title = { Text("Transaction History") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            }
        )

        if (uiState.isLoadingHistory) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = NeonCyan)
            }
            return
        }

        if (uiState.transactionHistory.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No transactions found",
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodyLarge
                )
            }
            return
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(uiState.transactionHistory) { tx ->
                TransactionCard(
                    tx = tx,
                    onClick = { onDetail(tx.txHash) }
                )
            }
        }
    }
}

@Composable
private fun TransactionCard(
    tx: TransactionHistoryItem,
    onClick: () -> Unit = {}
) {
    val typeLabel = when {
        tx.isInput && tx.isOutput -> "Sent (with change)"
        tx.isInput -> "Sent"
        tx.isOutput -> "Received"
        else -> "Unknown"
    }
    val typeColor = when {
        tx.isInput && !tx.isOutput -> NeonPink
        !tx.isInput && tx.isOutput -> SuccessGreen
        else -> TextSecondary
    }

    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CardBackground)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = typeLabel,
                    color = typeColor,
                    style = MaterialTheme.typography.labelLarge
                )
                Text(
                    text = "Block ${tx.blockNumber}",
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Text(
                text = tx.txHash,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
