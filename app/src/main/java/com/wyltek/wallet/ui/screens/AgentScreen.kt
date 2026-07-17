package com.wyltek.wallet.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wyltek.wallet.agent.provisioning.buildProvisioningBundle
import com.wyltek.wallet.agent.ui.AgentViewModel
import com.wyltek.wallet.core.native.CapInfo
import com.wyltek.wallet.core.native.Scope
import com.wyltek.wallet.core.native.TokenSpec
import com.wyltek.wallet.data.WalletViewModel
import com.wyltek.wallet.ui.components.QrCodeImage
import com.wyltek.wallet.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3Api::class)
@Composable
fun AgentScreen(
    onBack: () -> Unit = {},
    onApprovals: () -> Unit = {},
    agentViewModel: AgentViewModel = viewModel(),
    walletViewModel: WalletViewModel = viewModel()
) {
    val uiState by agentViewModel.uiState.collectAsState()
    val walletState by walletViewModel.uiState.collectAsState()

    // Notification permission launcher (Android 13+)
    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // After permission response, start the server regardless
        agentViewModel.startServer()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Agent Gateway") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
            actions = {
                IconButton(onClick = { agentViewModel.refresh() }) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = NeonCyan)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkSurface)
        )

        Column(
            modifier = Modifier
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // ── Server card ──────────────────────────────────────────────────
            AgentSettingsSection(title = "Server") {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (uiState.serverRunning) "Running" else "Stopped",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = if (uiState.serverRunning) SuccessGreen else TextSecondary
                                )
                                Text(
                                    text = uiState.bindAddress ?: "Tailnet unavailable",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (uiState.bindAddress != null) TextSecondary else WarningOrange
                                )
                            }
                            if (uiState.serverRunning) {
                                Button(
                                    onClick = { agentViewModel.stopServer() },
                                    colors = ButtonDefaults.buttonColors(containerColor = ErrorRed)
                                ) {
                                    Text("Stop", color = TextPrimary)
                                }
                            } else {
                                Button(
                                    onClick = {
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                        } else {
                                            agentViewModel.startServer()
                                        }
                                    },
                                    enabled = uiState.bindAddress != null,
                                    colors = ButtonDefaults.buttonColors(containerColor = NeonCyan)
                                ) {
                                    Text("Start", color = DarkBackground)
                                }
                            }
                        }
                    }
                }
            }

            // ── Pending approvals row ────────────────────────────────────────
            AgentSettingsSection(title = "Approvals") {
                ListItem(
                    headlineContent = { Text("Pending approvals (${uiState.pendingCount})") },
                    supportingContent = { Text("Tap to review", color = TextSecondary) },
                    leadingContent = {
                        Icon(
                            imageVector = Icons.Default.Notifications,
                            contentDescription = null,
                            tint = if (uiState.pendingCount > 0) WarningOrange else NeonCyan
                        )
                    },
                    trailingContent = {
                        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = TextSecondary)
                    },
                    modifier = Modifier.clickableListItem(onApprovals),
                    colors = ListItemDefaults.colors(containerColor = DarkSurfaceVariant)
                )
            }

            // ── Relay card ───────────────────────────────────────────────────
            AgentSettingsSection(title = "Relay") {
                RelayCard(
                    paired = uiState.relayPaired,
                    pairedUrl = uiState.relayUrl,
                    onPair = { agentViewModel.pairRelay(it) },
                    onUnpair = { agentViewModel.unpairRelay() }
                )
            }

            // ── Token list ───────────────────────────────────────────────────
            AgentSettingsSection(title = "Tokens (${uiState.tokens.size})") {
                if (uiState.tokens.isEmpty()) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
                    ) {
                        Text(
                            text = "No tokens minted yet",
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                } else {
                    uiState.tokens.forEach { token ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = token.account.take(12) + "…" + token.account.takeLast(6),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = TextPrimary
                                        )
                                        if (token.revoked) {
                                            Surface(
                                                color = ErrorRed.copy(alpha = 0.2f),
                                                shape = MaterialTheme.shapes.small
                                            ) {
                                                Text(
                                                    text = "REVOKED",
                                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = ErrorRed
                                                )
                                            }
                                        }
                                    }
                                    Text(
                                        text = token.tokenId.take(16) + "…",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TextSecondary
                                    )
                                }
                                if (!token.revoked) {
                                    TextButton(
                                        onClick = { agentViewModel.revoke(token.tokenId) },
                                        colors = ButtonDefaults.textButtonColors(contentColor = ErrorRed)
                                    ) {
                                        Text("Revoke")
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ── Last minted token QR ─────────────────────────────────────────
            uiState.lastMintedToken?.let { token ->
                AgentSettingsSection(title = "Minted Token") {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = CardBackground)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = null,
                                tint = WarningOrange,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Shown once — copy or scan now",
                                style = MaterialTheme.typography.labelMedium,
                                color = WarningOrange
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            // NOTE: buildProvisioningBundle assumes quote-free inputs (UUID device_id, URL-safe base64 token) and does not enforce it.
                            val bundle = buildProvisioningBundle(uiState.deviceId, token)
                            if (bundle != null) {
                                QrCodeImage(
                                    content = bundle,
                                    modifier = Modifier.size(220.dp)
                                )
                            } else {
                                Text(
                                    text = "Pair this device with the relay before provisioning a POS",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = WarningOrange,
                                    textAlign = TextAlign.Center
                                )
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            SelectionContainer {
                                Text(
                                    text = token,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary
                                )
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            OutlinedButton(
                                onClick = { agentViewModel.clearLastMintedToken() },
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary)
                            ) {
                                Text("Dismiss")
                            }
                        }
                    }
                }
            }

            // ── Error card ───────────────────────────────────────────────────
            uiState.error?.let { error ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = ErrorRed.copy(alpha = 0.1f))
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = error,
                            modifier = Modifier.weight(1f),
                            color = ErrorRed,
                            style = MaterialTheme.typography.bodySmall
                        )
                        TextButton(onClick = { agentViewModel.clearError() }) {
                            Text("Dismiss", color = TextSecondary)
                        }
                    }
                }
            }

            // ── Mint token form ──────────────────────────────────────────────
            AgentSettingsSection(title = "Mint Token") {
                MintTokenForm(
                    accounts = walletState.accounts.mapNotNull { it.addresses.firstOrNull()?.bech32m },
                    onMint = { spec -> agentViewModel.mint(spec) }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MintTokenForm(
    accounts: List<String>,
    onMint: (TokenSpec) -> Unit
) {
    // Scope checkboxes
    var scopeSendCkb by remember { mutableStateOf(true) }
    var scopeSendUdt by remember { mutableStateOf(false) }
    var scopeDao by remember { mutableStateOf(false) }
    var scopeMessaging by remember { mutableStateOf(false) }

    // Account dropdown
    var selectedAccount by remember(accounts) { mutableStateOf(accounts.firstOrNull() ?: "") }
    var accountMenuExpanded by remember { mutableStateOf(false) }

    // CKB cap fields
    var cumulativeCkb by remember { mutableStateOf("") }
    var autoLimitCkb by remember { mutableStateOf("") }

    // Optional fields
    var ttlHours by remember { mutableStateOf("") }
    var allowTo by remember { mutableStateOf("") }
    var allowIp by remember { mutableStateOf("") }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Account dropdown
            Text("Account", style = MaterialTheme.typography.labelMedium, color = NeonCyan)
            if (accounts.isEmpty()) {
                Text(
                    "No accounts available",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            } else {
                ExposedDropdownMenuBox(
                    expanded = accountMenuExpanded,
                    onExpandedChange = { accountMenuExpanded = !accountMenuExpanded }
                ) {
                    OutlinedTextField(
                        value = if (selectedAccount.length > 24)
                            selectedAccount.take(12) + "…" + selectedAccount.takeLast(8)
                        else selectedAccount,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Bound account") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = accountMenuExpanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = NeonCyan,
                            unfocusedBorderColor = CardBorder
                        )
                    )
                    ExposedDropdownMenu(
                        expanded = accountMenuExpanded,
                        onDismissRequest = { accountMenuExpanded = false }
                    ) {
                        accounts.forEach { addr ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = if (addr.length > 24) addr.take(12) + "…" + addr.takeLast(8) else addr,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                },
                                onClick = {
                                    selectedAccount = addr
                                    accountMenuExpanded = false
                                }
                            )
                        }
                    }
                }
            }

            // Scope checkboxes
            Text("Scopes", style = MaterialTheme.typography.labelMedium, color = NeonCyan)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ScopeCheckbox(label = "send_ckb", checked = scopeSendCkb, onCheckedChange = { scopeSendCkb = it }, modifier = Modifier.weight(1f))
                ScopeCheckbox(label = "send_udt", checked = scopeSendUdt, onCheckedChange = { scopeSendUdt = it }, modifier = Modifier.weight(1f))
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ScopeCheckbox(label = "dao", checked = scopeDao, onCheckedChange = { scopeDao = it }, modifier = Modifier.weight(1f))
                ScopeCheckbox(label = "messaging", checked = scopeMessaging, onCheckedChange = { scopeMessaging = it }, modifier = Modifier.weight(1f))
            }

            // CKB caps
            Text("CKB Caps", style = MaterialTheme.typography.labelMedium, color = NeonCyan)
            OutlinedTextField(
                value = cumulativeCkb,
                onValueChange = { cumulativeCkb = it },
                label = { Text("Cumulative cap (CKB)") },
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = NeonCyan,
                    unfocusedBorderColor = CardBorder
                )
            )
            OutlinedTextField(
                value = autoLimitCkb,
                onValueChange = { autoLimitCkb = it },
                label = { Text("Auto-approve limit per tx (CKB)") },
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = NeonCyan,
                    unfocusedBorderColor = CardBorder
                )
            )

            // Optional fields
            Text("Optional", style = MaterialTheme.typography.labelMedium, color = NeonCyan)
            OutlinedTextField(
                value = ttlHours,
                onValueChange = { ttlHours = it },
                label = { Text("Expiry (hours, blank = infinite)") },
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = NeonCyan,
                    unfocusedBorderColor = CardBorder
                )
            )
            OutlinedTextField(
                value = allowTo,
                onValueChange = { allowTo = it },
                label = { Text("Allow-to addresses (comma-separated)") },
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = NeonCyan,
                    unfocusedBorderColor = CardBorder
                )
            )
            OutlinedTextField(
                value = allowIp,
                onValueChange = { allowIp = it },
                label = { Text("Allow-IP list (comma-separated)") },
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = NeonCyan,
                    unfocusedBorderColor = CardBorder
                )
            )

            // Mint button
            Button(
                onClick = {
                    val scopes = buildList {
                        if (scopeSendCkb) add(Scope.SEND_CKB)
                        if (scopeSendUdt) add(Scope.SEND_UDT)
                        if (scopeDao) add(Scope.DAO)
                        if (scopeMessaging) add(Scope.MESSAGING)
                    }
                    val cumulative = (cumulativeCkb.toLongOrNull() ?: 0L) * 100_000_000L
                    val autoLimit = (autoLimitCkb.toLongOrNull() ?: 0L) * 100_000_000L
                    val caps = if (scopeSendCkb) {
                        listOf(
                            CapInfo(
                                asset = "CKB",
                                cumulative = cumulative,
                                windowSeconds = 0L,
                                windowLimit = 0L,
                                autoLimit = autoLimit
                            )
                        )
                    } else emptyList()
                    val ttlUnix = ttlHours.toLongOrNull()?.let {
                        System.currentTimeMillis() / 1000 + it * 3600L
                    }
                    val allowToList = allowTo.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                    val allowIpList = allowIp.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                    onMint(
                        TokenSpec(
                            account = selectedAccount,
                            scopes = scopes,
                            caps = caps,
                            ttlUnix = ttlUnix,
                            allowTo = allowToList,
                            allowIp = allowIpList
                        )
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = selectedAccount.isNotBlank() && (scopeSendCkb || scopeSendUdt || scopeDao || scopeMessaging),
                colors = ButtonDefaults.buttonColors(containerColor = NeonCyan)
            ) {
                Text("Mint Token", color = DarkBackground)
            }
        }
    }
}

@Composable
private fun ScopeCheckbox(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = CheckboxDefaults.colors(checkedColor = NeonCyan)
        )
        Text(label, style = MaterialTheme.typography.bodySmall, color = TextPrimary)
    }
}

@Composable
private fun RelayCard(
    paired: Boolean,
    pairedUrl: String?,
    onPair: (String) -> Unit,
    onUnpair: () -> Unit
) {
    var relayInput by remember { mutableStateOf("") }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (paired && pairedUrl != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Paired",
                            style = MaterialTheme.typography.bodyLarge,
                            color = SuccessGreen
                        )
                        Text(
                            text = pairedUrl,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                    Button(
                        onClick = onUnpair,
                        colors = ButtonDefaults.buttonColors(containerColor = ErrorRed)
                    ) {
                        Text("Unpair", color = TextPrimary)
                    }
                }
            } else {
                OutlinedTextField(
                    value = relayInput,
                    onValueChange = { relayInput = it },
                    label = { Text("Relay base URL") },
                    placeholder = { Text("https://wyltek-10700.tail6db685.ts.net:9992", color = TextSecondary) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = NeonCyan,
                        unfocusedBorderColor = CardBorder
                    )
                )
                Button(
                    onClick = { onPair(relayInput.trim()) },
                    enabled = relayInput.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = NeonCyan)
                ) {
                    Text("Pair", color = DarkBackground)
                }
            }
        }
    }
}

@Composable
private fun AgentSettingsSection(
    title: String,
    content: @Composable () -> Unit
) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = NeonCyan,
        modifier = Modifier.padding(vertical = 8.dp)
    )
    content()
}

private fun Modifier.clickableListItem(onClick: () -> Unit): Modifier =
    this.clickable(onClick = onClick)

