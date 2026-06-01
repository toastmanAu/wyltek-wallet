package com.wyltek.wallet.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.wyltek.wallet.ui.theme.*
import com.wyltek.wallet.data.WalletViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onSkins: () -> Unit = {},
    viewModel: WalletViewModel
) {
    val uiState by viewModel.uiState.collectAsState()
    var showRpcDialog by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Settings") },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkSurface)
        )

        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            SettingsSection(title = "Network") {
                SettingsItem(
                    icon = Icons.Default.Cloud,
                    title = "RPC Provider",
                    subtitle = uiState.activeRpc ?: "Not configured",
                    onClick = { showRpcDialog = true }
                )
                SettingsItem(
                    icon = Icons.Default.Storage,
                    title = "Light Client",
                    subtitle = "Not running"
                )
                SettingsItem(
                    icon = Icons.Default.Sync,
                    title = "Tip Block",
                    subtitle = if (uiState.isConnected) "#${uiState.tipBlockNumber}" else "Disconnected"
                )
            }

            SettingsSection(title = "Security") {
                SettingsItem(
                    icon = Icons.Default.Lock,
                    title = "Biometric Lock",
                    subtitle = "Disabled"
                )
                SettingsItem(
                    icon = Icons.Default.Key,
                    title = "Passkey",
                    subtitle = "Not configured"
                )
            }

            SettingsSection(title = "Appearance") {
                SettingsItem(
                    icon = Icons.Default.Palette,
                    title = "Theme",
                    subtitle = uiState.currentTheme?.name ?: "Cyberpunk",
                    onClick = onSkins
                )
                SettingsItem(
                    icon = Icons.Default.Image,
                    title = "Custom Skin",
                    subtitle = "Default",
                    onClick = onSkins
                )
            }

            SettingsSection(title = "Advanced") {
                SettingsItem(
                    icon = Icons.Default.Code,
                    title = "Network",
                    subtitle = "Testnet"
                )
                SettingsItem(
                    icon = Icons.Default.Info,
                    title = "About",
                    subtitle = "v0.1.0-dev"
                )
            }
        }
    }

    if (showRpcDialog) {
        RpcSelectionDialog(
            currentRpc = uiState.activeRpc,
            onDismiss = { showRpcDialog = false },
            onSelect = { rpc ->
                viewModel.setActiveRpc(rpc)
                showRpcDialog = false
            }
        )
    }
}

@Composable
private fun RpcSelectionDialog(
    currentRpc: String?,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select RPC Provider") },
        text = {
            Column {
                RpcOption(
                    name = "CKB Testnet (public)",
                    url = "https://testnet.ckbapp.dev",
                    isSelected = currentRpc == "CKB Testnet (public)",
                    onClick = onSelect
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Private RPC endpoints can be added in a future update.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextSecondary)
            }
        }
    )
}

@Composable
private fun RpcOption(
    name: String,
    url: String,
    isSelected: Boolean,
    onClick: (String) -> Unit
) {
    Card(
        onClick = { onClick(name) },
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) NeonCyan.copy(alpha = 0.1f) else DarkSurfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyLarge,
                color = if (isSelected) NeonCyan else TextPrimary
            )
            Text(
                text = url,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
        }
    }
}

@Composable
private fun SettingsSection(
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

@Composable
private fun SettingsItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit = {}
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle, color = TextSecondary) },
        leadingContent = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = NeonCyan
            )
        },
        modifier = Modifier.clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = DarkSurfaceVariant)
    )
}
