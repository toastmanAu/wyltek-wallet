package com.wyltek.wallet.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.wyltek.wallet.core.chain.NetworkConfig
import com.wyltek.wallet.core.model.AccountType
import com.wyltek.wallet.core.model.CkbAddress
import com.wyltek.wallet.ui.security.BiometricResult
import com.wyltek.wallet.ui.security.rememberBiometricAuth
import com.wyltek.wallet.ui.theme.*
import com.wyltek.wallet.data.WalletViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SendScreen(
    onBack: () -> Unit = {},
    onScanQr: () -> Unit = {},
    scannedAddress: String? = null,
    viewModel: WalletViewModel
) {
    var recipientAddress by remember { mutableStateOf(scannedAddress ?: "") }
    var amount by remember { mutableStateOf("") }
    var authError by remember { mutableStateOf<String?>(null) }
    val uiState by viewModel.uiState.collectAsState()
    val biometric = rememberBiometricAuth()

    // Update address if scanned result arrives after initial composition
    LaunchedEffect(scannedAddress) {
        scannedAddress?.let { recipientAddress = it }
    }

    val account = uiState.currentAccount
    val subAddresses = account?.addresses.orEmpty()
    var selectedIndex by remember(account?.id) { mutableIntStateOf(0) }
    val selectedAddress = subAddresses.getOrNull(selectedIndex)
    val isPqSelection = selectedAddress != null && account != null &&
        NetworkConfig.isPqLock(selectedAddress.lockScript.codeHash, account.network)
    // Auth gate fires for either: explicit PQ sub-account selection, or PQ/Hybrid
    // account where the (auto-)selected key cannot be verified as classic.
    val accountType = account?.type
    val requiresAuth = isPqSelection ||
        accountType == AccountType.POST_QUANTUM ||
        (accountType == AccountType.HYBRID && subAddresses.size <= 1)

    val performSend: (ULong) -> Unit = { amountShannons ->
        if (requiresAuth) {
            authError = null
            biometric.authenticate(
                title = "Confirm send",
                subtitle = "Authenticate to sign this post-quantum transaction",
                description = "ML-DSA-65 signatures from this account require re-authentication."
            ) { result ->
                when (result) {
                    is BiometricResult.Success -> viewModel.sendCkb(recipientAddress, amountShannons, selectedAddress)
                    is BiometricResult.Cancelled -> { /* silent */ }
                    is BiometricResult.Error -> { authError = result.message }
                    is BiometricResult.Unavailable -> {
                        authError = "Set up a device PIN, password, or biometric to send from a PQ account."
                    }
                }
            }
        } else {
            viewModel.sendCkb(recipientAddress, amountShannons, selectedAddress)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        TopAppBar(
            title = { Text("Send CKB") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            }
        )

        OutlinedTextField(
            value = recipientAddress,
            onValueChange = { recipientAddress = it },
            label = { Text("Recipient Address") },
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = NeonCyan,
                unfocusedBorderColor = CardBorder
            ),
            trailingIcon = {
                IconButton(onClick = onScanQr) {
                    Icon(
                        Icons.Default.QrCodeScanner,
                        contentDescription = "Scan QR Code",
                        tint = NeonCyan
                    )
                }
            }
        )

        OutlinedTextField(
            value = amount,
            onValueChange = { amount = it },
            label = { Text("Amount (CKB)") },
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = NeonCyan,
                unfocusedBorderColor = CardBorder
            )
        )

        if (subAddresses.size > 1 && account != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = CardBackground)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "Sign with",
                        style = MaterialTheme.typography.labelMedium,
                        color = TextSecondary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        subAddresses.forEachIndexed { idx, addr ->
                            val isPq = NetworkConfig.isPqLock(addr.lockScript.codeHash, account.network)
                            val label = if (isPq) "PQ (ML-DSA-65)" else "Classic (secp256k1)"
                            val selected = idx == selectedIndex
                            FilterChip(
                                selected = selected,
                                onClick = { selectedIndex = idx },
                                label = { Text(label) },
                                modifier = Modifier.weight(1f),
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = NeonCyan.copy(alpha = 0.2f),
                                    selectedLabelColor = NeonCyan
                                )
                            )
                        }
                    }
                    selectedAddress?.let {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = it.bech32m.take(20) + "…" + it.bech32m.takeLast(8),
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                    }
                }
            }
        }

        (authError ?: uiState.error)?.let { error ->
            Card(
                colors = CardDefaults.cardColors(containerColor = ErrorRed.copy(alpha = 0.1f))
            ) {
                Text(
                    text = error,
                    modifier = Modifier.padding(12.dp),
                    color = ErrorRed,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        uiState.lastTxHash?.let { txHash ->
            Card(
                colors = CardDefaults.cardColors(containerColor = SuccessGreen.copy(alpha = 0.1f))
            ) {
                Text(
                    text = "Transaction sent: $txHash",
                    modifier = Modifier.padding(12.dp),
                    color = SuccessGreen,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        Button(
            onClick = {
                val amountCkb = amount.toDoubleOrNull()
                if (amountCkb != null && amountCkb > 0 && recipientAddress.isNotBlank()) {
                    val amountShannons = (amountCkb * 100_000_000.0).toULong()
                    performSend(amountShannons)
                }
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = NeonCyan),
            enabled = amount.isNotBlank() && recipientAddress.isNotBlank() && !uiState.isLoading
        ) {
            if (uiState.isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    color = DarkBackground
                )
            } else {
                Text("Send CKB", color = DarkBackground)
            }
        }
    }
}
