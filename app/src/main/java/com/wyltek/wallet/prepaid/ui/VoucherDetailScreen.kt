package com.wyltek.wallet.prepaid.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wyltek.wallet.prepaid.ActionOutcome
import com.wyltek.wallet.prepaid.VoucherMoney
import com.wyltek.wallet.prepaid.db.*
import com.wyltek.wallet.ui.security.BiometricResult
import com.wyltek.wallet.ui.security.rememberBiometricAuth
import com.wyltek.wallet.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoucherDetailScreen(voucherId: String, onBack: () -> Unit, viewModel: PrepaidViewModel = viewModel()) {
    val biometric = rememberBiometricAuth()
    val ui by viewModel.ui.collectAsState()
    var v by remember { mutableStateOf<VoucherEntity?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var events by remember { mutableStateOf<List<PrepaidEventEntity>>(emptyList()) }
    var confirmVoid by remember { mutableStateOf(false) }
    var authError by remember { mutableStateOf<String?>(null) }
    val fmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }
    LaunchedEffect(voucherId, ui.outcome) {
        v = viewModel.voucher(voucherId); events = viewModel.eventsFor(voucherId).sortedByDescending { it.id }; loaded = true
    }
    Scaffold(topBar = { TopAppBar(title = { Row { Text(v?.productName ?: "Voucher"); Spacer(Modifier.width(8.dp)); DemoBadge() } },
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } }) }) { pad ->
        val x = v
        if (x == null) { if (loaded) Text("Voucher not found", Modifier.padding(pad).padding(16.dp), color = TextSecondary); return@Scaffold }
        // Mirrors VoucherService.void: active, and nothing pending except an issue that is on chain but not yet on the tag.
        val voidable = x.state == VOUCHER_ACTIVE && (x.pendingTxHash == null || (x.pendingKind == KIND_VOUCHER_ISSUE && x.pendingCommitted))
        LazyColumn(Modifier.padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Text("${x.countLeft} of ${x.countIssued} × ${x.productCode}", color = TextPrimary, style = MaterialTheme.typography.titleMedium) }
            item { Text("Issued at ${VoucherMoney.format(x.issuePriceMinor, x.currency)} · ${x.state}", color = TextSecondary) }
            item { Text("Cell ${x.outpoint.take(14)}…", color = TextSecondary, style = MaterialTheme.typography.bodySmall) }
            x.pendingTxHash?.let { item {
                Text("Pending ${x.pendingKind ?: "action"}: ${if (x.pendingCommitted) "committed, tap the voucher to write it" else "confirming"}", color = WarningOrange) } }
            if (voidable) item {
                OutlinedButton(colors = ButtonDefaults.outlinedButtonColors(contentColor = ErrorRed), enabled = !ui.busy, onClick = { confirmVoid = true }) { Text("Void") }
            }
            authError?.let { item { Text(it, color = ErrorRed) } }
            if (ui.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            (ui.outcome as? ActionOutcome.Failed)?.let { item { Text(it.reason, color = ErrorRed) } }
            if (ui.outcome == ActionOutcome.Done) item { Text("Done", color = SuccessGreen) }
            item { Text("Events", color = NeonCyan) }
            items(events, key = { it.id }) { e ->
                ListItem(headlineContent = { Text("${e.kind} · ${e.phase}", color = if (e.phase == PHASE_FAILED) ErrorRed else TextPrimary) },
                    supportingContent = { Text(fmt.format(Date(e.atMs)) + (e.txHash?.let { "\n$it" } ?: "") + (e.detail.takeIf { it.isNotBlank() }?.let { "\n$it" } ?: ""),
                        color = TextSecondary, style = MaterialTheme.typography.bodySmall) },
                    colors = ListItemDefaults.colors(containerColor = DarkSurfaceVariant))
            }
        }
        if (confirmVoid) AlertDialog(onDismissRequest = { confirmVoid = false }, title = { Text("Void ${x.productName}?") },
            text = { Text("Cancels ${x.countLeft} of ${x.countIssued} uses; the voucher cell returns to you. The tag is not needed; a redemption a terminal takes later is refused at settlement.", color = ErrorRed) },
            confirmButton = { TextButton(onClick = { confirmVoid = false; authError = null
                biometric.authenticate(title = "Void voucher", subtitle = "Spends the voucher cell back to you") { r ->
                    when (r) {
                        is BiometricResult.Success, is BiometricResult.Unavailable -> viewModel.voidVoucher(x, null)
                        is BiometricResult.Cancelled -> {}
                        is BiometricResult.Error -> authError = r.message
                    } } }) { Text("Void", color = ErrorRed) } },
            dismissButton = { TextButton(onClick = { confirmVoid = false }) { Text("Cancel") } })
    }
}
