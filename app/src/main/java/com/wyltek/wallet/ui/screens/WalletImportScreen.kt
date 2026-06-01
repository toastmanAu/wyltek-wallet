package com.wyltek.wallet.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.wyltek.wallet.ui.theme.*
import com.wyltek.wallet.data.WalletViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WalletImportScreen(
    onBack: () -> Unit = {},
    viewModel: WalletViewModel
) {
    var walletName by remember { mutableStateOf("") }
    var importInput by remember { mutableStateOf("") }
    var selectedMethod by remember { mutableIntStateOf(0) }
    val methods = listOf("Mnemonic Phrase", "Address", "Raw Lock Script")
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(uiState.currentAccount) {
        if (uiState.currentAccount != null && uiState.error == null) {
            onBack()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        TopAppBar(
            title = { Text("Import Wallet") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            }
        )

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
            text = "Import Method",
            style = MaterialTheme.typography.labelLarge,
            color = TextSecondary
        )

        methods.forEachIndexed { index, method ->
            Card(
                onClick = { selectedMethod = index },
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (selectedMethod == index) DarkSurfaceVariant else CardBackground
                )
            ) {
                Text(
                    text = method,
                    modifier = Modifier.padding(16.dp),
                    color = if (selectedMethod == index) NeonCyan else TextPrimary
                )
            }
        }

        OutlinedTextField(
            value = importInput,
            onValueChange = { importInput = it },
            label = { Text(
                when (selectedMethod) {
                    0 -> "Enter 12/18/24 word mnemonic"
                    1 -> "Enter CKB address"
                    else -> "Enter raw lock script (JSON)"
                }
            )},
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = NeonCyan,
                unfocusedBorderColor = CardBorder
            )
        )

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

        Spacer(modifier = Modifier.weight(1f))

        Button(
            onClick = {
                if (selectedMethod == 0 && walletName.isNotBlank() && importInput.isNotBlank()) {
                    viewModel.importWallet(walletName, importInput)
                }
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = NeonCyan),
            enabled = walletName.isNotBlank() && importInput.isNotBlank() && !uiState.isLoading
        ) {
            if (uiState.isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    color = DarkBackground
                )
            } else {
                Text("Import Wallet", color = DarkBackground)
            }
        }
    }
}
