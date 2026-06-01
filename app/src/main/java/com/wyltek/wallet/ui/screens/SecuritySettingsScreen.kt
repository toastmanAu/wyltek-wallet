package com.wyltek.wallet.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wyltek.wallet.core.security.SecurityInfo
import com.wyltek.wallet.data.WalletViewModel
import com.wyltek.wallet.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecuritySettingsScreen(viewModel: WalletViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    var showEnableDialog by remember { mutableStateOf(false) }
    var showDisableDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Security Settings") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = DarkSurface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(DarkBackground)
        ) {
            // StrongBox Status Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurface)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "StrongBox",
                            style = MaterialTheme.typography.titleMedium,
                            color = CyberText,
                            fontWeight = FontWeight.Bold
                        )

                        Surface(
                            color = if (uiState.securityInfo?.strongBoxEnabled == true) NeonGreen else CyberGray,
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = if (uiState.securityInfo?.strongBoxEnabled == true) "Enabled" else "Disabled",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = DarkBackground
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Security Info
                    SecurityInfoRow(
                        label = "Hardware Security",
                        value = if (uiState.securityInfo?.strongBoxAvailable == true) "Available" else "Not Available",
                        isPositive = uiState.securityInfo?.strongBoxAvailable == true
                    )
                    SecurityInfoRow(
                        label = "Algorithm",
                        value = uiState.securityInfo?.algorithm ?: "AES-256-GCM"
                    )
                    SecurityInfoRow(
                        label = "Key Store",
                        value = uiState.securityInfo?.keyStore ?: "AndroidKeyStore"
                    )
                    SecurityInfoRow(
                        label = "Key Created",
                        value = if (uiState.securityInfo?.keyCreated == true) "Yes" else "No",
                        isPositive = uiState.securityInfo?.keyCreated == true
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Action Buttons
                    if (uiState.securityInfo?.strongBoxEnabled != true) {
                        Button(
                            onClick = { showEnableDialog = true },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = NeonGreen
                            ),
                            shape = RoundedCornerShape(8.dp),
                            enabled = uiState.securityInfo?.strongBoxAvailable == true
                        ) {
                            Icon(
                                imageVector = Icons.Default.Security,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Enable StrongBox", color = DarkBackground)
                        }
                    } else {
                        OutlinedButton(
                            onClick = { showDisableDialog = true },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = NeonPink
                            ),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Security,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Disable StrongBox")
                        }
                    }
                }
            }

            // Security Tips Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurface)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "Security Tips",
                        style = MaterialTheme.typography.titleSmall,
                        color = CyberBlue,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    SecurityTip(
                        icon = Icons.Default.Lock,
                        text = "StrongBox uses hardware security to protect your keys"
                    )
                    SecurityTip(
                        icon = Icons.Default.Shield,
                        text = "Keys are encrypted and stored in the Secure Element"
                    )
                    SecurityTip(
                        icon = Icons.Default.Visibility,
                        text = "Even rooted devices cannot extract your keys"
                    )
                }
            }

            // Biometric Settings Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurface)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "Biometric Authentication",
                        style = MaterialTheme.typography.titleSmall,
                        color = CyberBlue,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Fingerprint",
                                style = MaterialTheme.typography.bodyMedium,
                                color = CyberText
                            )
                            Text(
                                text = "Use fingerprint to unlock wallet",
                                style = MaterialTheme.typography.bodySmall,
                                color = CyberGray
                            )
                        }
                        Switch(
                            checked = false,
                            onCheckedChange = { },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = DarkBackground,
                                checkedTrackColor = NeonGreen,
                                uncheckedThumbColor = CyberGray,
                                uncheckedTrackColor = DarkSurfaceVariant
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Face Recognition",
                                style = MaterialTheme.typography.bodyMedium,
                                color = CyberText
                            )
                            Text(
                                text = "Use face to unlock wallet",
                                style = MaterialTheme.typography.bodySmall,
                                color = CyberGray
                            )
                        }
                        Switch(
                            checked = false,
                            onCheckedChange = { },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = DarkBackground,
                                checkedTrackColor = NeonGreen,
                                uncheckedThumbColor = CyberGray,
                                uncheckedTrackColor = DarkSurfaceVariant
                            )
                        )
                    }
                }
            }
        }
    }

    if (showEnableDialog) {
        AlertDialog(
            onDismissRequest = { showEnableDialog = false },
            title = { Text("Enable StrongBox") },
            text = {
                Text(
                    text = "StrongBox provides hardware-backed security for your private keys. This will create a new encryption key in the Secure Element. Continue?",
                    style = MaterialTheme.typography.bodyMedium,
                    color = CyberText
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.enableStrongBox()
                        showEnableDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = NeonGreen
                    )
                ) {
                    Text("Enable", color = DarkBackground)
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showEnableDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showDisableDialog) {
        AlertDialog(
            onDismissRequest = { showDisableDialog = false },
            title = { Text("Disable StrongBox") },
            text = {
                Text(
                    text = "Disabling StrongBox will remove the hardware-backed encryption key. Your existing keys will remain encrypted with software fallback. Continue?",
                    style = MaterialTheme.typography.bodyMedium,
                    color = CyberText
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.disableStrongBox()
                        showDisableDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = NeonPink
                    )
                ) {
                    Text("Disable", color = DarkBackground)
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showDisableDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun SecurityInfoRow(
    label: String,
    value: String,
    isPositive: Boolean? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = CyberGray
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = when (isPositive) {
                true -> NeonGreen
                false -> NeonPink
                null -> CyberText
            }
        )
    }
}

@Composable
private fun SecurityTip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = CyberBlue,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = CyberGray
        )
    }
}
