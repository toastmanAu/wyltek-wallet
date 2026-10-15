package com.wyltek.wallet.prepaid.ui

import android.app.Application
import android.nfc.Tag
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wyltek.wallet.WyltekWalletApp
import com.wyltek.wallet.prepaid.ActionOutcome
import com.wyltek.wallet.prepaid.db.StaffKeyEntity
import com.wyltek.wallet.prepaid.nfc.MifareTagPort
import com.wyltek.wallet.prepaid.nfc.NfcReaderMode
import com.wyltek.wallet.ui.security.BiometricResult
import com.wyltek.wallet.ui.security.rememberBiometricAuth
import com.wyltek.wallet.ui.theme.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class StaffUi(val armedLabel: String? = null, val busy: Boolean = false, val outcome: ActionOutcome? = null)

class StaffKeysViewModel(app: Application) : AndroidViewModel(app) {
    private val gateway = (app as WyltekWalletApp).agentGateway
    private val svc = gateway.staffKeys
    val keys: StateFlow<List<StaffKeyEntity>> = gateway.voucherStore().staffKeysFlow().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private val _ui = MutableStateFlow(StaffUi()); val ui: StateFlow<StaffUi> = _ui.asStateFlow()
    val available = svc != null
    val unavailableReason: String? = if (svc == null) gateway.prepaidUnavailableReason ?: "Prepaid is not available" else null
    fun arm(label: String) { _ui.update { it.copy(armedLabel = label, outcome = null) } }
    fun disarm() { _ui.update { it.copy(armedLabel = null) } }
    fun onTag(raw: Tag) {
        val label = _ui.value.armedLabel ?: return
        val s = svc ?: return
        if (_ui.value.busy) return
        if (!MifareTagPort.accepts(raw)) { _ui.update { it.copy(outcome = ActionOutcome.Failed("not an NTAG21x tag")) }; return }
        _ui.update { it.copy(busy = true) }
        viewModelScope.launch {
            val out = try { s.enrol(MifareTagPort(raw), label) } catch (e: CancellationException) { _ui.update { it.copy(busy = false) }; throw e }
                catch (e: Exception) { ActionOutcome.Failed("enrol failed: ${e.message}") }
            _ui.update { it.copy(busy = false, outcome = out, armedLabel = if (out is ActionOutcome.NeedsTag) label else null) }
        }
    }
    fun revoke(k: StaffKeyEntity) = viewModelScope.launch {
        val s = svc ?: return@launch
        val out = try { s.revoke(k.keyId) } catch (e: CancellationException) { throw e } catch (e: Exception) { ActionOutcome.Failed("revoke failed: ${e.message}") }
        _ui.update { it.copy(outcome = out) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StaffKeysScreen(onBack: () -> Unit, vm: StaffKeysViewModel = viewModel()) {
    val keys by vm.keys.collectAsState(); val ui by vm.ui.collectAsState()
    val biometric = rememberBiometricAuth()
    var label by remember { mutableStateOf("") }
    var confirmRevoke by remember { mutableStateOf<StaffKeyEntity?>(null) }
    var authError by remember { mutableStateOf<String?>(null) }
    fun gated(title: String, subtitle: String, block: () -> Unit) {
        authError = null
        biometric.authenticate(title = title, subtitle = subtitle) { r ->
            when (r) {
                is BiometricResult.Success, is BiometricResult.Unavailable -> block()
                is BiometricResult.Cancelled -> {}
                is BiometricResult.Error -> authError = r.message
            }
        }
    }
    NfcReaderMode(enabled = vm.available, onTag = vm::onTag)
    Scaffold(topBar = { TopAppBar(title = { Row { Text("Staff keys"); Spacer(Modifier.width(8.dp)); DemoBadge() } },
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } }) }) { pad ->
        Column(Modifier.padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("A staff key approves voucher redemptions and price overrides at the POS. Any genuine NXP NTAG21x tag works; nothing is written to it.", color = TextSecondary)
            vm.unavailableReason?.let { r -> Card(colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)) { Text(r, Modifier.padding(12.dp), color = WarningOrange) } }
            if (vm.available) {
                if (ui.armedLabel == null) {
                    OutlinedTextField(value = label, onValueChange = { label = it }, label = { Text("Label, e.g. Phill's ring") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Button(enabled = label.isNotBlank() && !ui.busy, onClick = { val l = label; gated("Enrol staff key", "Lets this tag approve overrides") { vm.arm(l) } }) { Text("Enrol") }
                } else {
                    Text("Hold the staff tag for \"${ui.armedLabel}\" to the phone…", color = NeonCyan)
                    if (!ui.busy) OutlinedButton(onClick = vm::disarm) { Text("Cancel") }
                }
            }
            authError?.let { Text(it, color = ErrorRed) }
            when (val o = ui.outcome) {
                ActionOutcome.Done -> Text("Done", color = SuccessGreen)
                is ActionOutcome.Failed -> Text(o.reason, color = ErrorRed)
                is ActionOutcome.NeedsTag -> Text("Could not read the tag. Hold it still.", color = WarningOrange)
                else -> {}
            }
            if (ui.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (keys.isEmpty()) Text("No staff keys yet.", color = TextSecondary)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(keys, key = { it.keyId }) { k ->
                    ListItem(headlineContent = { Text(k.label) },
                        supportingContent = { Text("${k.uid} · key ${k.keyId}" + (k.revokedAt?.let { " · revoked" } ?: ""), color = TextSecondary) },
                        trailingContent = { if (k.revokedAt == null) TextButton(onClick = { confirmRevoke = k }) { Text("Revoke", color = ErrorRed) } },
                        colors = ListItemDefaults.colors(containerColor = DarkSurfaceVariant))
                }
            }
        }
    }
    confirmRevoke?.let { k ->
        AlertDialog(onDismissRequest = { confirmRevoke = null }, title = { Text("Revoke ${k.label}?") },
            text = { Text("This tag stops approving redemptions and overrides. Terminals drop it at their next sync; until then they may still accept it.", color = ErrorRed) },
            confirmButton = { TextButton(onClick = { confirmRevoke = null; gated("Revoke ${k.label}", "Terminals drop it at their next sync") { vm.revoke(k) } }) { Text("Revoke", color = ErrorRed) } },
            dismissButton = { TextButton(onClick = { confirmRevoke = null }) { Text("Cancel") } })
    }
}
