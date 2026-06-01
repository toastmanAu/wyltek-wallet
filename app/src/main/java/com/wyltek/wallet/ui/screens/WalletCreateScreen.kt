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
fun WalletCreateScreen(onBack: () -> Unit = {}) {
    var walletName by remember { mutableStateOf("") }
    var selectedType by remember { mutableIntStateOf(0) }
    val types = listOf("Classic CKB", "Post-Quantum CKB", "Hybrid")

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        TopAppBar(
            title = { Text("Create Wallet") },
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

        Spacer(modifier = Modifier.weight(1f))

        Button(
            onClick = { /* TODO */ },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = NeonCyan),
            enabled = walletName.isNotBlank()
        ) {
            Text("Create Wallet", color = DarkBackground)
        }
    }
}
