package com.wyltek.wallet.prepaid.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.wyltek.wallet.prepaid.*
import com.wyltek.wallet.prepaid.db.*
import com.wyltek.wallet.ui.security.BiometricResult
import com.wyltek.wallet.ui.security.rememberBiometricAuth
import com.wyltek.wallet.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrepaidTapSheet(ui: PrepaidUiState, viewModel: PrepaidViewModel, onDismiss: () -> Unit, onScanAddress: () -> Unit) {
    val biometric = rememberBiometricAuth()
    var amount by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }
    var confirmVoid by remember { mutableStateOf<PrepaidCardEntity?>(null) }
    // Saveable: the address scan navigates away and back, which would otherwise drop the open dialog.
    var cashOutOpen by rememberSaveable { mutableStateOf(false) }
    var previewVoid by remember { mutableStateOf<CashOutPreview?>(null) }
    var previewCashOut by remember { mutableStateOf<CashOutPreview?>(null) }
    val scannedAddress = ui.scannedAddress
    var authError by remember { mutableStateOf<String?>(null) }
    val actions = PrepaidUiReducer.tapActions(ui.tap, ui.tagPresent)
    val fmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    fun gated(title: String, block: () -> Unit) {
        authError = null
        biometric.authenticate(title = title, subtitle = "Confirm this prepaid transaction") { r ->
            when (r) {
                is BiometricResult.Success -> block()
                is BiometricResult.Unavailable -> block()      // no device credential set up: the demo tier does not block on it
                is BiometricResult.Cancelled -> {}
                is BiometricResult.Error -> authError = r.message
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = DarkSurface) {
        Column(Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row { Text(headline(ui.tap), style = MaterialTheme.typography.titleMedium, color = TextPrimary); Spacer(Modifier.width(8.dp)); DemoBadge() }
            detail(ui.tap)?.let { Text(it, color = TextSecondary) }
            if (TapAction.ISSUE in actions || TapAction.LOAD in actions) {
                OutlinedTextField(value = amount, onValueChange = { amount = it }, label = { Text("Amount (CKB, max 1000)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (TapAction.ISSUE in actions) OutlinedTextField(value = label, onValueChange = { label = it }, label = { Text("Label (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (a in actions) when (a) {
                    TapAction.ISSUE -> Button(enabled = !ui.busy, onClick = { val c = viewModel.currentContext(); val a = amount; val l = label; gated("Issue card") { viewModel.issue(c, a, l) } }) { Text("Issue") }
                    TapAction.LOAD -> Button(enabled = !ui.busy, onClick = { val c = viewModel.currentContext(); val a = amount; gated("Load card") { viewModel.load(c, a) } }) { Text("Load") }
                    TapAction.RESUME -> Button(enabled = !ui.busy, onClick = { viewModel.resume(viewModel.currentContext()) }) { Text("Finish pending action") }
                    TapAction.ADOPT -> Button(enabled = !ui.busy, onClick = { val c = viewModel.currentContext(); gated("Adopt card") { viewModel.adopt(c) } }) { Text("Adopt") }
                    TapAction.REPAIR -> OutlinedButton(enabled = !ui.busy, onClick = { viewModel.repair(viewModel.currentContext()) }) { Text("Repair") }
                    TapAction.REVOKE -> OutlinedButton(enabled = !ui.busy, onClick = { viewModel.tappedCard()?.let(viewModel::revoke) }) { Text("Revoke") }
                    TapAction.REINSTATE -> OutlinedButton(enabled = !ui.busy, onClick = { viewModel.tappedCard()?.let(viewModel::reinstate) }) { Text("Reinstate") }
                    TapAction.VOID -> OutlinedButton(enabled = !ui.busy, colors = ButtonDefaults.outlinedButtonColors(contentColor = ErrorRed), onClick = { previewVoid = null; confirmVoid = viewModel.tappedCard() }) { Text("Void") }
                    TapAction.CASH_OUT -> OutlinedButton(enabled = !ui.busy, onClick = { previewCashOut = null; cashOutOpen = true }) { Text("Cash out") }
                }
            }
            if (PrepaidUiReducer.offersAcceptAsIs(ui.outcome, ui.tap)) {
                Text("The pending credit stays on chain (in the index balance) but is not written to the card.", color = TextSecondary)
                OutlinedButton(enabled = !ui.busy, onClick = { val c = viewModel.currentContext(); gated("Accept card as-is") { viewModel.acceptTagAsIs(c) } }) { Text("Accept card as-is") }
            }
            if (!ui.tagPresent && ui.tap != null && ui.tap !is TapClass.Refused) Text("Tap the card again to continue.", color = WarningOrange)
            authError?.let { Text(it, color = ErrorRed) }
            if (ui.phases.isNotEmpty()) Column { for ((p, t) in ui.phases) Text("${fmt.format(Date(t))}  ${p.name.lowercase()}", color = if (p == Phase.FAILED) ErrorRed else NeonCyan, style = MaterialTheme.typography.bodySmall) }
            when (val o = ui.outcome) {
                ActionOutcome.Done -> Text("Done", color = SuccessGreen)
                is ActionOutcome.AwaitingTag -> Text("Committed on chain. Tap the card again to write it.", color = WarningOrange)
                is ActionOutcome.StillConfirming -> Text("Still confirming ${o.txHash.take(10)}…; tap the card again later.", color = WarningOrange)
                is ActionOutcome.Failed -> Text(o.reason, color = ErrorRed)
                null -> {}
            }
            if (ui.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }

    confirmVoid?.let { card ->
        LaunchedEffect(ui.tapEpoch, card.reserveId) { previewVoid = viewModel.preview(card) }
        AlertDialog(onDismissRequest = { confirmVoid = null }, title = { Text("Void ${card.label ?: card.uid}?") },
            text = { Column { Text("Forfeits ${PrepaidAmount.formatUnits(card.balanceUnits.toLong())} to the merchant.", color = ErrorRed)
                previewVoid?.unsettledTaps?.takeIf { it > 0 }?.let { Text("$it unsettled terminal taps will be swept too.", color = WarningOrange) }
                if (previewVoid != null && previewVoid?.unsettledTaps == null && ui.tagPresent) Text("Could not read the card: unsettled terminal taps unknown", color = WarningOrange) } },
            confirmButton = { TextButton(onClick = { val c = viewModel.currentContext(); confirmVoid = null; gated("Void card") { viewModel.voidCard(card, c) } }) { Text("Void", color = ErrorRed) } },
            dismissButton = { TextButton(onClick = { confirmVoid = null }) { Text("Cancel") } })
    }
    val coCard = if (cashOutOpen) viewModel.tappedCard() else null
    if (coCard != null) {
        LaunchedEffect(ui.tapEpoch, coCard.reserveId) { previewCashOut = viewModel.preview(coCard) }
        val pv = previewCashOut
        val taps = pv?.unsettledTaps
        AlertDialog(onDismissRequest = { cashOutOpen = false; viewModel.clearScannedAddress() }, title = { Text("Cash out ${coCard.label ?: coCard.uid}") },
            text = { Column {
                Text("Pays ${PrepaidAmount.formatUnits(coCard.balanceUnits.toLong())} to the customer's address; the 61 CKB floor returns to the merchant.")
                if (pv?.belowMinimum == true) Text("Below the 61 CKB minimum cell: pay this out in cash and void the card instead.", color = ErrorRed)
                if (pv != null && taps == null) Text("Could not read the card. Hold it to the phone and reopen this dialog.", color = ErrorRed)
                if (taps != null && taps > 0) Text("$taps unsettled taps: sync the terminal first.", color = ErrorRed)
                Text(scannedAddress?.let { "To: $it" } ?: "Scan the customer's address", color = TextSecondary)
                TextButton(onClick = onScanAddress) { Text("Scan address") } } },
            confirmButton = { TextButton(enabled = scannedAddress != null && pv != null && !pv.belowMinimum && taps == 0L,
                onClick = { val c = viewModel.currentContext(); val addr = scannedAddress!!; cashOutOpen = false; viewModel.clearScannedAddress(); gated("Cash out") { viewModel.cashOut(c, addr) } }) { Text("Pay out") } },
            dismissButton = { TextButton(onClick = { cashOutOpen = false; viewModel.clearScannedAddress() }) { Text("Cancel") } })
    }
}

private fun headline(t: TapClass?) = when (t) {
    is TapClass.Blank -> "Blank card"; is TapClass.Resume -> "Pending action"; is TapClass.Damaged -> "Damaged card"
    is TapClass.Known -> t.card.label ?: t.card.uid; is TapClass.Bench -> "Terminal-formatted card"; is TapClass.Refused -> "Refused"; null -> ""
}
private fun pendingText(c: PrepaidCardEntity): String = when (c.pendingKind) {
    KIND_VOID -> "A void is pending"
    KIND_CASH_OUT -> "A cash-out is pending"
    KIND_SETTLE -> "A settlement is pending"
    KIND_ADOPT -> "An adoption is pending"
    else -> "${PrepaidAmount.formatUnits((c.pendingUnits ?: 0).toLong())} committed on chain, not yet on the card"
}
private fun detail(t: TapClass?) = when (t) {
    is TapClass.Blank -> "UID ${t.uidHex}. Issue it with an opening balance."
    is TapClass.Resume -> "${pendingText(t.card)}. Tap Finish to complete it."
    is TapClass.Damaged -> "${t.reason}. Index balance ${PrepaidAmount.formatUnits(t.card.balanceUnits.toLong())}."
    is TapClass.Known -> "Card ${PrepaidAmount.formatUnits(t.record.balanceUnits.toLong())} (counter ${t.record.counter}) · index ${PrepaidAmount.formatUnits(t.card.balanceUnits.toLong())} (counter ${t.card.counter}) · ${t.card.state}"
    is TapClass.Bench -> "Terminal ${t.record.writerId} wrote ${PrepaidAmount.formatUnits(t.record.balanceUnits.toLong())}. Adopting funds a reserve for it."
    is TapClass.Refused -> t.reason
    null -> null
}
