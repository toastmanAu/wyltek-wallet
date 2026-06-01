package com.wyltek.wallet.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.wyltek.wallet.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WalletImportScreen(onBack: () -> Unit = {}) {
    var importInput by remember { mutableStateOf("") }
    var selectedMethod by remember { mutableIntStateOf(0) }
    val methods = listOf("Mnemonic Phrase", "Address", "Raw Lock Script", "Keystore File")

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
                    2 -> "Enter raw lock script (JSON)"
                    else -> "Select keystore file"
                }
            )},
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = NeonCyan,
                unfocusedBorderColor = CardBorder
            )
        )

        Spacer(modifier = Modifier.weight(1f))

        Button(
            onClick = { /* TODO */ },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = NeonCyan),
            enabled = importInput.isNotBlank()
        ) {
            Text("Import Wallet", color = DarkBackground)
        }
    }
}
