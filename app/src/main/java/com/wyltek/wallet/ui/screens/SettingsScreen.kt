package com.wyltek.wallet.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.wyltek.wallet.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
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
                    subtitle = "Public RPC"
                )
                SettingsItem(
                    icon = Icons.Default.Storage,
                    title = "Light Client",
                    subtitle = "Not running"
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
                    subtitle = "Cyberpunk"
                )
                SettingsItem(
                    icon = Icons.Default.Image,
                    title = "Custom Skin",
                    subtitle = "Default"
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
    subtitle: String
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
        colors = ListItemDefaults.colors(containerColor = DarkSurfaceVariant)
    )
}
