package com.wyltek.wallet.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.wyltek.wallet.core.chain.NetworkConfig
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.wyltek.wallet.ui.components.QrCodeImage
import com.wyltek.wallet.ui.theme.*
import com.wyltek.wallet.data.WalletViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiveScreen(
    onBack: () -> Unit = {},
    viewModel: WalletViewModel
) {
    val uiState by viewModel.uiState.collectAsState()
    val account = uiState.currentAccount
    val subAddresses = account?.addresses.orEmpty()
    var selectedIndex by remember(account?.id) { mutableIntStateOf(0) }
    val selectedAddress = subAddresses.getOrNull(selectedIndex)
    val address = selectedAddress?.bech32m ?: viewModel.getCurrentAddress()
    val clipboardManager = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        TopAppBar(
            title = { Text("Receive CKB") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            }
        )

        if (subAddresses.size > 1 && account != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = CardBackground)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "Receive to",
                        style = MaterialTheme.typography.labelMedium,
                        color = TextSecondary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        subAddresses.forEachIndexed { idx, addr ->
                            val isPq = NetworkConfig.isPqLock(addr.lockScript.codeHash, account.network)
                            val label = if (isPq) "PQ (ML-DSA-65)" else "Classic (secp256k1)"
                            FilterChip(
                                selected = idx == selectedIndex,
                                onClick = { selectedIndex = idx; copied = false },
                                label = { Text(label) },
                                modifier = Modifier.weight(1f),
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = NeonCyan.copy(alpha = 0.2f),
                                    selectedLabelColor = NeonCyan
                                )
                            )
                        }
                    }
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = CardBackground)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (address.isNotBlank()) {
                    QrCodeImage(
                        content = address,
                        modifier = Modifier.size(200.dp)
                    )
                }

                Text(
                    text = "Your Address",
                    style = MaterialTheme.typography.labelLarge,
                    color = TextSecondary
                )
                Text(
                    text = if (address.isNotBlank()) address else "Create a wallet first",
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    color = if (address.isNotBlank()) TextPrimary else TextSecondary
                )
            }
        }

        if (copied) {
            Card(
                colors = CardDefaults.cardColors(containerColor = SuccessGreen.copy(alpha = 0.1f))
            ) {
                Text(
                    text = "Address copied to clipboard",
                    modifier = Modifier.padding(12.dp),
                    color = SuccessGreen,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        OutlinedButton(
            onClick = {
                if (address.isNotBlank()) {
                    clipboardManager.setText(AnnotatedString(address))
                    copied = true
                }
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = NeonCyan),
            enabled = address.isNotBlank()
        ) {
            Text("Copy Address")
        }
    }
}
