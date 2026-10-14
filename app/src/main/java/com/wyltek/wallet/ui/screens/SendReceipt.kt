package com.wyltek.wallet.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wyltek.wallet.ui.theme.*
import kotlinx.coroutines.delay

/** What the user sent, captured at the moment they pressed Send. */
data class SentSummary(val amountCkb: String, val to: String, val fromLabel: String)

object SendReceipt {
    fun explorerTxUrl(explorerBase: String, txHash: String): String =
        explorerBase.trimEnd('/') + "/transaction/0x" + txHash.removePrefix("0x").lowercase()

    /** `status` is TxStatus.name lower-cased, as WalletViewModel.getTransactionStatus returns it. */
    fun statusLabel(status: String?): String = when (status) {
        null -> "Checking…"
        "pending" -> "Pending"
        "confirmed" -> "Confirmed"
        "rejected" -> "Rejected"
        else -> "Not yet seen by the node"
    }

    fun isFinal(status: String?): Boolean = status == "confirmed" || status == "rejected"

    fun shortAddress(address: String): String =
        if (address.length <= 24) address else address.take(14) + "…" + address.takeLast(9)
}

@Composable
fun SendReceiptView(
    summary: SentSummary,
    txHash: String,
    explorerBase: String,
    pollStatus: suspend (String) -> String?,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var status by remember(txHash) { mutableStateOf<String?>(null) }
    var copied by remember(txHash) { mutableStateOf(false) }

    LaunchedEffect(txHash) {
        while (true) {
            status = pollStatus(txHash) ?: status
            if (SendReceipt.isFinal(status)) break
            delay(4_000)
        }
    }

    val confirmed = status == "confirmed"
    val rejected = status == "rejected"
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(24.dp))
        Icon(
            imageVector = if (confirmed) Icons.Default.CheckCircle else Icons.Default.Schedule,
            contentDescription = null,
            tint = when { confirmed -> SuccessGreen; rejected -> ErrorRed; else -> NeonCyan },
            modifier = Modifier.size(64.dp),
        )
        Text(
            text = when { confirmed -> "Sent"; rejected -> "Transaction rejected"; else -> "Sending" },
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = TextPrimary,
        )
        Text("${summary.amountCkb} CKB", style = MaterialTheme.typography.headlineMedium, color = NeonCyan)

        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = CardBackground)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ReceiptRow("Status", SendReceipt.statusLabel(status))
                ReceiptRow("From", summary.fromLabel)
                ReceiptRow("To", SendReceipt.shortAddress(summary.to), mono = true)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Tx", color = TextSecondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(64.dp))
                    Text(
                        SendReceipt.shortAddress(txHash),
                        fontFamily = FontFamily.Monospace,
                        color = TextPrimary,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { clipboard.setText(AnnotatedString(txHash)); copied = true }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy transaction hash", tint = NeonCyan)
                    }
                }
                if (copied) Text("Transaction hash copied", color = SuccessGreen, style = MaterialTheme.typography.bodySmall)
            }
        }

        Spacer(Modifier.weight(1f))
        OutlinedButton(
            onClick = {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SendReceipt.explorerTxUrl(explorerBase, txHash))))
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = NeonCyan),
        ) { Text("View on explorer") }
        Button(
            onClick = onClose,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = NeonCyan),
        ) { Text("Close", color = DarkBackground) }
    }
}

@Composable
private fun ReceiptRow(label: String, value: String, mono: Boolean = false) {
    Row {
        Text(label, color = TextSecondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(64.dp))
        Text(
            value,
            color = TextPrimary,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
        )
    }
}
