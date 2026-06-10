package com.wyltek.wallet.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.wyltek.wallet.core.assets.TokenInfo
import com.wyltek.wallet.core.chain.NetworkConfig
import com.wyltek.wallet.core.model.AccountType
import com.wyltek.wallet.core.model.LockScript
import com.wyltek.wallet.ui.security.BiometricResult
import com.wyltek.wallet.ui.security.rememberBiometricAuth
import com.wyltek.wallet.ui.theme.*
import com.wyltek.wallet.data.WalletViewModel
import java.math.BigInteger

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SendTokenScreen(
    token: TokenInfo,
    onBack: () -> Unit = {},
    viewModel: WalletViewModel
) {
    var recipientAddress by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var authError by remember { mutableStateOf<String?>(null) }
    val uiState by viewModel.uiState.collectAsState()
    val biometric = rememberBiometricAuth()

    val account = uiState.currentAccount
    val subAddresses = account?.addresses.orEmpty()
    // For tokens, default to the first non-PQ sub-account if one exists —
    // sUDT sends from PQ are not yet wired (Repository.sendToken refuses).
    val defaultIndex = remember(account?.id) {
        if (account != null) {
            subAddresses.indexOfFirst { !NetworkConfig.isPqLock(it.lockScript.codeHash, account.network) }
                .takeIf { it >= 0 } ?: 0
        } else 0
    }
    var selectedIndex by remember(account?.id) { mutableIntStateOf(defaultIndex) }
    val selectedAddress = subAddresses.getOrNull(selectedIndex)
    val isPqSelection = selectedAddress != null && account != null &&
        NetworkConfig.isPqLock(selectedAddress.lockScript.codeHash, account.network)
    val accountType = account?.type
    val requiresAuth = isPqSelection ||
        accountType == AccountType.POST_QUANTUM ||
        (accountType == AccountType.HYBRID && subAddresses.size <= 1)

    val performSend: (BigInteger) -> Unit = { amountBig ->
        val tokenLock = LockScript(
            codeHash = token.typeScript.codeHash,
            hashType = token.typeScript.hashType,
            args = token.typeScript.args
        )
        if (requiresAuth) {
            authError = null
            biometric.authenticate(
                title = "Confirm send",
                subtitle = "Authenticate to sign this post-quantum transaction",
                description = "Sending ${token.symbol} from a PQ account requires re-authentication."
            ) { result ->
                when (result) {
                    is BiometricResult.Success -> viewModel.sendToken(recipientAddress, tokenLock, amountBig, selectedAddress)
                    is BiometricResult.Cancelled -> { /* silent */ }
                    is BiometricResult.Error -> { authError = result.message }
                    is BiometricResult.Unavailable -> {
                        authError = "Set up a device PIN, password, or biometric to send from a PQ account."
                    }
                }
            }
        } else {
            viewModel.sendToken(recipientAddress, tokenLock, amountBig, selectedAddress)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        TopAppBar(
            title = { Text("Send ${token.symbol}") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            }
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = CardBackground)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Token: ${token.symbol}",
                    style = MaterialTheme.typography.bodyLarge,
                    color = NeonCyan
                )
                Text(
                    text = "Balance: ${token.amount}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextPrimary
                )
                Text(
                    text = "Type Args: ${token.typeScript.args.take(24)}...",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
        }

        OutlinedTextField(
            value = recipientAddress,
            onValueChange = { recipientAddress = it },
            label = { Text("Recipient Address") },
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = NeonCyan,
                unfocusedBorderColor = CardBorder
            )
        )

        OutlinedTextField(
            value = amount,
            onValueChange = { amount = it },
            label = { Text("Amount") },
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
                            FilterChip(
                                selected = idx == selectedIndex,
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
                    if (isPqSelection) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Note: ${token.symbol} sends from a PQ sub-account are not yet supported. Switch to the Classic sub-account.",
                            style = MaterialTheme.typography.labelSmall,
                            color = WarningOrange
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
                val amountBig = amount.toBigIntegerOrNull()
                if (amountBig != null && amountBig > BigInteger.ZERO && recipientAddress.isNotBlank()) {
                    performSend(amountBig)
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
                Text("Send ${token.symbol}", color = DarkBackground)
            }
        }
    }
}
