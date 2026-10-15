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
    // Voucher issue form. Saveable: the product-code scan navigates away and back.
    var issueKind by rememberSaveable { mutableStateOf("card") }
    var vCode by rememberSaveable { mutableStateOf("") }
    var vName by rememberSaveable { mutableStateOf("") }
    var vPrice by rememberSaveable { mutableStateOf("") }
    var vCurrency by rememberSaveable { mutableStateOf("AUD") }
    var vCount by rememberSaveable { mutableStateOf("1") }
    var confirmVoidVoucher by remember { mutableStateOf<VoucherEntity?>(null) }
    var confirmWipeVoucher by remember { mutableStateOf(false) }
    LaunchedEffect(ui.scannedAddress) {
        val scanned = ui.scannedAddress
        if (issueKind == "voucher" && scanned != null) { vCode = scanned; viewModel.clearScannedAddress() }
    }
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

    // Not dismissable mid-action: a running cash-out must finish where the user can see its result.
    val sheetState = rememberModalBottomSheetState(confirmValueChange = { it != SheetValue.Hidden || !ui.busy })
    ModalBottomSheet(onDismissRequest = { if (!ui.busy) onDismiss() }, sheetState = sheetState, containerColor = DarkSurface) {
        Column(Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row { Text(headline(ui.tap), style = MaterialTheme.typography.titleMedium, color = TextPrimary); Spacer(Modifier.width(8.dp)); DemoBadge() }
            detail(ui.tap)?.let { Text(it, color = TextSecondary) }
            val voucherForm = TapAction.ISSUE_VOUCHER in actions && issueKind == "voucher"
            if (TapAction.ISSUE_VOUCHER in actions) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = issueKind == "card", onClick = { issueKind = "card" }, label = { Text("Card") })
                FilterChip(selected = issueKind == "voucher", onClick = { issueKind = "voucher" }, label = { Text("Voucher") })
            }
            if (voucherForm) {
                OutlinedTextField(value = vCode, onValueChange = { vCode = it }, label = { Text("Product code") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    trailingIcon = { TextButton(onClick = onScanAddress) { Text("Scan") } })
                OutlinedTextField(value = vName, onValueChange = { vName = it.take(VOUCHER_NAME_MAX) }, label = { Text("Product name") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    supportingText = { Text("${vName.length}/$VOUCHER_NAME_MAX") })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = vPrice, onValueChange = { vPrice = it }, label = { Text("Price") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(value = vCurrency, onValueChange = { vCurrency = it.uppercase().take(3) }, label = { Text("Currency") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(value = vCount, onValueChange = { vCount = it }, label = { Text("Uses") }, singleLine = true, modifier = Modifier.weight(1f))
                }
            } else if (TapAction.ISSUE in actions || TapAction.LOAD in actions) {
                OutlinedTextField(value = amount, onValueChange = { amount = it }, label = { Text("Amount (CKB, max 1000)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (TapAction.ISSUE in actions) OutlinedTextField(value = label, onValueChange = { label = it }, label = { Text("Label (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
            ui.armed?.let { armed ->
                Text(armedPrompt(armed, ui.cards.firstOrNull { it.reserveId == armed.reserveId }), color = WarningOrange)
                if (!ui.busy) OutlinedButton(onClick = viewModel::disarm) { Text(if (armed is Armed.Wipe) "Not now" else "Cancel cash-out") }
            }
            if (ui.armed == null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (a in actions) when (a) {
                    TapAction.ISSUE -> if (issueKind == "card") Button(enabled = !ui.busy, onClick = { val c = viewModel.currentContext(); val a = amount; val l = label; gated("Issue card") { viewModel.issue(c, a, l) } }) { Text("Issue") }
                    TapAction.LOAD -> Button(enabled = !ui.busy, onClick = { val c = viewModel.currentContext(); val a = amount; gated("Load card") { viewModel.load(c, a) } }) { Text("Load") }
                    TapAction.RESUME -> Button(enabled = !ui.busy, onClick = { viewModel.resume(viewModel.currentContext()) }) { Text("Finish pending action") }
                    TapAction.ADOPT -> Button(enabled = !ui.busy, onClick = { val c = viewModel.currentContext(); gated("Adopt card") { viewModel.adopt(c) } }) { Text("Adopt") }
                    TapAction.REPAIR -> OutlinedButton(enabled = !ui.busy, onClick = { viewModel.repair(viewModel.currentContext()) }) { Text("Repair") }
                    TapAction.REVOKE -> OutlinedButton(enabled = !ui.busy, onClick = { viewModel.tappedCard()?.let(viewModel::revoke) }) { Text("Revoke") }
                    TapAction.REINSTATE -> OutlinedButton(enabled = !ui.busy, onClick = { viewModel.tappedCard()?.let(viewModel::reinstate) }) { Text("Reinstate") }
                    TapAction.VOID -> OutlinedButton(enabled = !ui.busy, colors = ButtonDefaults.outlinedButtonColors(contentColor = ErrorRed), onClick = { previewVoid = null; confirmVoid = viewModel.tappedCard() }) { Text("Void") }
                    TapAction.WIPE -> OutlinedButton(enabled = !ui.busy, onClick = { val c = viewModel.currentContext(); gated("Wipe card") { viewModel.wipeClosed(c) } }) { Text("Wipe card") }
                    TapAction.CASH_OUT -> OutlinedButton(enabled = !ui.busy, onClick = { previewCashOut = null; cashOutOpen = true }) { Text("Cash out") }
                    TapAction.ISSUE_VOUCHER -> if (issueKind == "voucher") Button(enabled = !ui.busy, onClick = {
                        val c = viewModel.currentContext(); val f = listOf(vCode, vName, vPrice, vCurrency, vCount)
                        gated("Issue voucher") { viewModel.issueVoucher(c, f[0], f[1], f[2], f[3], f[4]) } }) { Text("Issue voucher") }
                    TapAction.RESUME_VOUCHER -> Button(enabled = !ui.busy, onClick = { viewModel.resumeVoucher(viewModel.currentContext()) }) { Text("Finish pending action") }
                    TapAction.VOID_VOUCHER -> OutlinedButton(enabled = !ui.busy, colors = ButtonDefaults.outlinedButtonColors(contentColor = ErrorRed),
                        onClick = { confirmVoidVoucher = viewModel.tappedVoucher() }) { Text("Void") }
                    TapAction.WIPE_VOUCHER -> OutlinedButton(enabled = !ui.busy, onClick = { confirmWipeVoucher = true }) { Text("Wipe") }
                }
            }
            if (PrepaidUiReducer.offersAcceptAsIs(ui.outcome, ui.tap)) {
                Text("The pending credit stays on chain (in the index balance) but is not written to the card.", color = TextSecondary)
                OutlinedButton(enabled = !ui.busy, onClick = { val c = viewModel.currentContext(); gated("Accept card as-is") { viewModel.acceptTagAsIs(c) } }) { Text("Accept card as-is") }
            }
            if (ui.armed == null && !ui.tagPresent && ui.tap != null && ui.tap !is TapClass.Refused) Text("Tap the card again to continue.", color = WarningOrange)
            authError?.let { Text(it, color = ErrorRed) }
            if (ui.phases.isNotEmpty()) Column { for ((p, t) in ui.phases) Text("${fmt.format(Date(t))}  ${p.name.lowercase()}", color = if (p == Phase.FAILED) ErrorRed else NeonCyan, style = MaterialTheme.typography.bodySmall) }
            when (val o = ui.outcome) {
                ActionOutcome.Done -> Text("Done", color = SuccessGreen)
                is ActionOutcome.AwaitingTag -> Text("Committed on chain. Tap the card again to write it.", color = WarningOrange)
                is ActionOutcome.StillConfirming -> Text("Still confirming ${o.txHash.take(10)}…; tap the card again later.", color = WarningOrange)
                is ActionOutcome.Failed -> Text(o.reason, color = ErrorRed)
                is ActionOutcome.NeedsTag -> Text("Could not read the card. Hold it still against the phone.", color = WarningOrange)
                is ActionOutcome.WipePending -> Text("Paid out and closed on chain.", color = SuccessGreen)
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
    confirmVoidVoucher?.let { v ->
        AlertDialog(onDismissRequest = { confirmVoidVoucher = null }, title = { Text("Void ${v.productName}?") },
            text = { Text("Cancels ${v.countLeft} of ${v.countIssued} uses; the voucher cell returns to you. Its tag is wiped if it is still on the phone.", color = ErrorRed) },
            confirmButton = { TextButton(onClick = { val c = viewModel.currentContext(); confirmVoidVoucher = null; gated("Void voucher") { viewModel.voidVoucher(v, c) } }) { Text("Void", color = ErrorRed) } },
            dismissButton = { TextButton(onClick = { confirmVoidVoucher = null }) { Text("Cancel") } })
    }
    if (confirmWipeVoucher) {
        val v = (ui.tap as? TapClass.Voucher)?.voucher
        AlertDialog(onDismissRequest = { confirmWipeVoucher = false }, title = { Text("Wipe ${v?.productName ?: "voucher"}?") },
            text = { Text("Erases this ${v?.state ?: ""} voucher's record from the tag so it can be reused.") },
            confirmButton = { TextButton(onClick = { val c = viewModel.currentContext(); confirmWipeVoucher = false; gated("Wipe voucher") { viewModel.wipeVoucher(c) } }) { Text("Wipe", color = ErrorRed) } },
            dismissButton = { TextButton(onClick = { confirmWipeVoucher = false }) { Text("Cancel") } })
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
                if (taps != null && taps > 0) Text("$taps unsettled taps: sync the terminal first.", color = ErrorRed)
                else Text("After Pay out, hold the card to the phone: it is checked for unsettled terminal taps, then paid.", color = TextSecondary)
                Text(scannedAddress?.let { "To: $it" } ?: "Scan the customer's address", color = TextSecondary)
                TextButton(onClick = onScanAddress) { Text("Scan address") } } },
            confirmButton = { TextButton(enabled = scannedAddress != null && pv != null && !pv.belowMinimum && (taps == null || taps == 0L),
                onClick = { val addr = scannedAddress!!; cashOutOpen = false; viewModel.clearScannedAddress(); gated("Cash out") { viewModel.cashOut(coCard, addr) } }) { Text("Pay out") } },
            dismissButton = { TextButton(onClick = { cashOutOpen = false; viewModel.clearScannedAddress() }) { Text("Cancel") } })
    }
}

private fun armedPrompt(armed: Armed, card: PrepaidCardEntity?): String {
    val name = card?.label ?: armed.uid
    return when (armed) {
        is Armed.CashOut -> "Hold $name to the phone to pay out to ${armed.address.take(14)}…${armed.address.takeLast(6)}. You can lift it once Done shows."
        is Armed.Wipe -> "Hold $name to the phone to wipe it."
    }
}

private fun headline(t: TapClass?) = when (t) {
    is TapClass.Blank -> "Blank card"; is TapClass.Resume -> "Pending action"; is TapClass.Damaged -> "Damaged card"
    is TapClass.Known -> t.card.label ?: t.card.uid; is TapClass.Bench -> "Terminal-formatted card"; is TapClass.Refused -> "Refused"
    is TapClass.Voucher -> "Voucher: ${t.voucher.productName}"; is TapClass.VoucherResume -> "Pending voucher"
    is TapClass.VoucherDamaged -> "Damaged voucher"; null -> ""
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
    is TapClass.Voucher -> "${t.record.count} of ${t.voucher.countIssued} × ${t.voucher.productCode} · issued at ${VoucherMoney.format(t.voucher.issuePriceMinor, t.voucher.currency)} · index ${t.voucher.countLeft} left · ${t.voucher.state}"
    is TapClass.VoucherResume -> if (t.voucher.pendingCommitted) "Committed on chain, not yet on the tag. Tap Finish." else "Waiting for the chain. Tap Finish to check."
    is TapClass.VoucherDamaged -> "${t.reason}. Index: ${t.voucher.countLeft} of ${t.voucher.countIssued} uses left."
    null -> null
}
