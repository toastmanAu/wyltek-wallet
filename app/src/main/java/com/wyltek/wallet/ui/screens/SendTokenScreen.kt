package com.wyltek.wallet.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.wyltek.wallet.core.assets.TokenInfo
import com.wyltek.wallet.core.model.LockScript
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
    val uiState by viewModel.uiState.collectAsState()

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
                    viewModel.sendToken(
                        recipientAddress,
                        LockScript(
                            codeHash = token.typeScript.codeHash,
                            hashType = token.typeScript.hashType,
                            args = token.typeScript.args
                        ),
                        amountBig
                    )
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
