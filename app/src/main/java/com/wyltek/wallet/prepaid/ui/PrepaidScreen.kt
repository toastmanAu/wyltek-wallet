package com.wyltek.wallet.prepaid.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wyltek.wallet.prepaid.PrepaidAmount
import com.wyltek.wallet.prepaid.VoucherMoney
import com.wyltek.wallet.prepaid.db.*
import com.wyltek.wallet.prepaid.nfc.NfcReaderMode
import com.wyltek.wallet.ui.theme.*

@Composable
fun DemoBadge() = AssistChip(onClick = {}, label = { Text("DEMO") }, colors = AssistChipDefaults.assistChipColors(containerColor = WarningOrange, labelColor = DarkBackground))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrepaidScreen(
    onBack: () -> Unit,
    onCard: (String) -> Unit,
    onAudit: () -> Unit,
    onVoucher: (String) -> Unit,
    onStaff: () -> Unit,
    onScanAddress: () -> Unit,
    scannedAddress: String? = null,
    onConsumeScannedAddress: () -> Unit = {},
    viewModel: PrepaidViewModel = viewModel(),
) {
    val ui by viewModel.ui.collectAsState()
    val vouchersFlow = remember(viewModel) { viewModel.vouchersFlow() }
    val vouchers by vouchersFlow.collectAsState(initial = emptyList())
    val readerOn = ui.nfcAvailable && ui.unavailableReason == null
    LaunchedEffect(scannedAddress) { if (scannedAddress != null) { viewModel.consumeScannedAddress(scannedAddress); onConsumeScannedAddress() } }
    NfcReaderMode(enabled = readerOn, onTag = viewModel::onTag)

    Scaffold(topBar = {
        TopAppBar(title = { Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) { Text("Prepaid cards"); Spacer(Modifier.width(8.dp)); DemoBadge() } },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } },
            actions = {
                IconButton(onClick = onStaff) { Icon(Icons.Default.Lock, "Staff keys") }
                IconButton(onClick = onAudit) { Icon(Icons.Default.History, "Audit log") }
            })
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val active = ui.cards.count { it.state == CARD_ACTIVE }; val revoked = ui.cards.count { it.state == CARD_REVOKED }; val closed = ui.cards.count { it.state == CARD_CLOSED }
            Text("Reserves ${ui.reserveTotalCkb} · $active active · $revoked revoked · $closed closed", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
            when {
                ui.unavailableReason != null -> Card(colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)) { Text(ui.unavailableReason!!, Modifier.padding(12.dp), color = WarningOrange) }
                !ui.nfcAvailable -> Card(colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)) { Text("This phone has no NFC. Cards can be viewed and audited but not issued.", Modifier.padding(12.dp), color = WarningOrange) }
                else -> Card(colors = CardDefaults.cardColors(containerColor = DarkSurface)) { Text("Hold a card to the back of the phone", Modifier.padding(12.dp), color = NeonCyan) }
            }
            ui.warning?.let { w ->
                Card(colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text(w, Modifier.weight(1f), color = ErrorRed)
                        TextButton(onClick = viewModel::dismissWarning) { Text("Dismiss") }
                    }
                }
            }
            if (ui.cards.isEmpty()) Text("No cards yet.", color = TextSecondary)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(ui.cards, key = { it.reserveId }) { c ->
                    ListItem(
                        headlineContent = { Text(c.label ?: c.uid) },
                        supportingContent = { Text("${PrepaidAmount.formatUnits(c.balanceUnits.toLong())} · counter ${c.counter}" + (c.pendingUnits?.takeIf { c.pendingKind != KIND_SETTLE }?.let { " · pending ${PrepaidAmount.formatUnits(it.toLong())}" } ?: ""), color = TextSecondary) },
                        trailingContent = { AssistChip(onClick = {}, label = { Text(c.state) }) },
                        modifier = Modifier.clickable { onCard(c.reserveId) },
                        colors = ListItemDefaults.colors(containerColor = DarkSurfaceVariant))
                }
                item { Text("Vouchers", color = NeonCyan, modifier = Modifier.padding(top = 8.dp)) }
                if (vouchers.isEmpty()) item { Text("No vouchers yet. Tap a blank tag and choose Voucher.", color = TextSecondary) }
                items(vouchers, key = { "v-" + it.voucherId }) { v ->
                    ListItem(
                        headlineContent = { Text(v.productName) },
                        supportingContent = { Text("${v.countLeft}/${v.countIssued} × ${v.productCode} · ${VoucherMoney.format(v.issuePriceMinor, v.currency)}" +
                            (v.pendingTxHash?.let { " · pending" } ?: ""), color = TextSecondary) },
                        trailingContent = { AssistChip(onClick = {}, label = { Text(v.state) }) },
                        modifier = Modifier.clickable { onVoucher(v.voucherId) },
                        colors = ListItemDefaults.colors(containerColor = DarkSurfaceVariant))
                }
            }
        }
    }
    if (ui.tap != null) PrepaidTapSheet(ui = ui, viewModel = viewModel, onDismiss = viewModel::dismissTap, onScanAddress = onScanAddress)
}
