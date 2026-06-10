package com.wyltek.wallet.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.wyltek.wallet.core.model.AccountType
import com.wyltek.wallet.ui.theme.*
import com.wyltek.wallet.data.WalletViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WalletCreateScreen(
    onBack: () -> Unit = {},
    onVerify: () -> Unit = {},
    viewModel: WalletViewModel
) {
    var walletName by remember { mutableStateOf("") }
    var selectedType by remember { mutableIntStateOf(0) }
    val types = listOf("Classic CKB", "Post-Quantum CKB", "Hybrid")
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.clearStaleCreationState()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Create Wallet") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            OutlinedTextField(
                value = walletName,
                onValueChange = { walletName = it },
                label = { Text("Wallet Name") },
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = NeonCyan,
                    unfocusedBorderColor = CardBorder
                )
            )

            Text(
                text = "Account Type",
                style = MaterialTheme.typography.labelLarge,
                color = TextSecondary
            )

            types.forEachIndexed { index, type ->
                Card(
                    onClick = { selectedType = index },
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (selectedType == index) DarkSurfaceVariant else CardBackground
                    )
                ) {
                    Text(
                        text = type,
                        modifier = Modifier.padding(16.dp),
                        color = if (selectedType == index) NeonCyan else TextPrimary
                    )
                }
            }

            uiState.error?.let { error ->
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

            uiState.pendingMnemonic?.let { mnemonic ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = WarningOrange.copy(alpha = 0.1f))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Backup your mnemonic",
                            style = MaterialTheme.typography.titleMedium,
                            color = WarningOrange
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = mnemonic,
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextPrimary
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Write these 24 words down and store them safely. This is the ONLY way to recover your wallet.",
                            style = MaterialTheme.typography.bodySmall,
                            color = ErrorRed
                        )
                    }
                }
            }

            uiState.createdMnemonic?.let { mnemonic ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = SuccessGreen.copy(alpha = 0.1f))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Wallet Created!",
                            style = MaterialTheme.typography.titleMedium,
                            color = SuccessGreen
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Your wallet is ready. You can now receive CKB.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            when {
                uiState.createdMnemonic != null -> {
                    Button(
                        onClick = {
                            viewModel.clearCreatedMnemonic()
                            onBack()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen)
                    ) {
                        Text("Done", color = DarkBackground)
                    }
                }
                uiState.pendingMnemonic != null -> {
                    Button(
                        onClick = { onVerify() },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = NeonCyan)
                    ) {
                        Text("I've Saved My Mnemonic — Verify", color = DarkBackground)
                    }
                }
                else -> {
                    Button(
                        onClick = {
                            val accountType = when (selectedType) {
                                0 -> AccountType.CLASSIC
                                1 -> AccountType.POST_QUANTUM
                                else -> AccountType.HYBRID
                            }
                            viewModel.createWallet(walletName, accountType)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = NeonCyan),
                        enabled = walletName.isNotBlank() && !uiState.isLoading
                    ) {
                        if (uiState.isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = DarkBackground
                            )
                        } else {
                            Text("Create Wallet", color = DarkBackground)
                        }
                    }
                }
            }
        }
    }
}
