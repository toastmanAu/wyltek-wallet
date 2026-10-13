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
import com.wyltek.wallet.core.model.Utxo
import com.wyltek.wallet.prepaid.PrepaidAmount
import com.wyltek.wallet.prepaid.db.*
import com.wyltek.wallet.ui.security.BiometricResult
import com.wyltek.wallet.ui.security.rememberBiometricAuth
import com.wyltek.wallet.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrepaidCardDetailScreen(reserveId: String, onBack: () -> Unit, viewModel: PrepaidViewModel = viewModel()) {
    val ui by viewModel.ui.collectAsState()
    val card = ui.cards.firstOrNull { it.reserveId == reserveId }
    var live by remember { mutableStateOf<Utxo?>(null) }; var liveErr by remember { mutableStateOf<String?>(null) }
    var events by remember { mutableStateOf<List<PrepaidEventEntity>>(emptyList()) }
    var confirmVoid by remember { mutableStateOf(false) }
    val biometric = rememberBiometricAuth()
    LaunchedEffect(card?.currentOutpoint) { card?.let { live = try { liveErr = null; viewModel.liveCell(it) } catch (e: Exception) { liveErr = e.message; null } } }
    LaunchedEffect(reserveId, ui.cards) { events = viewModel.eventsFor(reserveId) }
    Scaffold(topBar = { TopAppBar(title = { Row { Text(card?.label ?: card?.uid ?: "Card"); Spacer(Modifier.width(8.dp)); DemoBadge() } },
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } }) }) { pad ->
        if (card == null) { Text("Card not found", Modifier.padding(pad).padding(16.dp)); return@Scaffold }
        val tagRec = viewModel.tagRecordFor(card)?.record
        val expected = 6_100_000_000uL + card.balanceUnits.toULong() * 100_000uL
        val liveCell = live
        LazyColumn(Modifier.padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) { Text("Tag", color = NeonCyan); Text(tagRec?.let { "${PrepaidAmount.formatUnits(it.balanceUnits.toLong())}\ncounter ${it.counter}" } ?: "tap to read", color = TextSecondary) }
                Column(Modifier.weight(1f)) { Text("Index", color = NeonCyan); Text("${PrepaidAmount.formatUnits(card.balanceUnits.toLong())}\ncounter ${card.counter}\nsettled ${card.settledCounter}\n${card.state}", color = TextSecondary) }
                Column(Modifier.weight(1f)) { Text("Live cell", color = NeonCyan)
                    Text(liveCell?.let { "${it.capacity / 100_000_000uL} CKB\n${card.currentOutpoint.take(12)}…" } ?: (liveErr ?: "not found on chain"), color = if (liveCell != null && liveCell.capacity == expected) TextSecondary else ErrorRed) }
            } }
            if (tagRec != null && tagRec.counter.toLong() != card.counter) item { Text("Tag counter ${tagRec.counter} vs index ${card.counter}: ${tagRec.counter.toLong() - card.counter} unsettled taps", color = WarningOrange) }
            if (liveCell != null && liveCell.capacity != expected) item { Text("Live capacity does not match the index balance (expected ${expected / 100_000_000uL} CKB)", color = ErrorRed) }
            card.pendingTxHash?.let { item {
                val what = card.pendingKind ?: "action"
                val detail = card.pendingUnits?.let { u -> if (card.pendingKind == KIND_SETTLE) " (successor output #$u)" else " (${PrepaidAmount.formatUnits(u.toLong())})" } ?: ""
                Text("Pending $what$detail: ${if (card.pendingCommitted) "committed, tap the card to write it" else "confirming"}", color = WarningOrange) } }
            item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (card.state == CARD_ACTIVE) OutlinedButton(onClick = { viewModel.revoke(card) }) { Text("Revoke") }
                if (card.state == CARD_REVOKED) OutlinedButton(onClick = { viewModel.reinstate(card) }) { Text("Reinstate") }
                if (card.state == CARD_ACTIVE || card.state == CARD_REVOKED) OutlinedButton(colors = ButtonDefaults.outlinedButtonColors(contentColor = ErrorRed), onClick = { confirmVoid = true }) { Text("Void") }
            } }
            item { Text("Load, cash-out and repair need the card: tap it on the Cards page.", color = TextSecondary, style = MaterialTheme.typography.bodySmall) }
            item { Text("Events", color = NeonCyan) }
            items(events, key = { it.id }) { e -> EventRow(e) }
        }
    }
    if (confirmVoid && card != null) AlertDialog(onDismissRequest = { confirmVoid = false }, title = { Text("Void ${card.label ?: card.uid}?") },
        text = { Text("Forfeits ${PrepaidAmount.formatUnits(card.balanceUnits.toLong())} to the merchant. The tag is not needed.", color = ErrorRed) },
        confirmButton = { TextButton(onClick = { confirmVoid = false; biometric.authenticate("Void card", "Confirm") { r -> if (r is BiometricResult.Success || r is BiometricResult.Unavailable) viewModel.voidCard(card) } }) { Text("Void", color = ErrorRed) } },
        dismissButton = { TextButton(onClick = { confirmVoid = false }) { Text("Cancel") } })
}

@Composable
fun EventRow(e: PrepaidEventEntity) {
    val fmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }
    ListItem(headlineContent = { Text("${e.kind} · ${e.phase}", color = if (e.phase == PHASE_FAILED) ErrorRed else TextPrimary) },
        supportingContent = { Text("${fmt.format(Date(e.atMs))} · ${PrepaidAmount.formatUnits(e.amountUnits)} · ${e.balanceBefore}→${e.balanceAfter} · ctr ${e.counterBefore}→${e.counterAfter}" +
            (e.txHash?.let { "\n$it" } ?: "") + (e.detail.takeIf { it.isNotBlank() }?.let { "\n$it" } ?: ""), color = TextSecondary, style = MaterialTheme.typography.bodySmall) },
        colors = ListItemDefaults.colors(containerColor = DarkSurfaceVariant))
}
