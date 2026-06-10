package com.wyltek.wallet.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.wyltek.wallet.data.WalletViewModel
import com.wyltek.wallet.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MnemonicVerifyScreen(
    viewModel: WalletViewModel,
    onVerified: () -> Unit,
    onBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val mnemonic = uiState.pendingMnemonic
    if (mnemonic.isNullOrBlank()) {
        LaunchedEffect(Unit) { onBack() }
        return
    }

    val words = remember(mnemonic) { mnemonic.split(" ") }
    val blankedIndices = remember(mnemonic) {
        words.indices.shuffled().take(5).sorted()
    }
    val userAnswers = remember(mnemonic) { mutableStateMapOf<Int, String>() }
    var showError by remember { mutableStateOf(false) }
    var allFilled by remember { mutableStateOf(false) }

    LaunchedEffect(userAnswers.size) {
        allFilled = blankedIndices.all { userAnswers[it]?.isNotBlank() == true }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Verify Mnemonic") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = TextPrimary
                        )
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
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Fill in the missing words to confirm you saved your mnemonic.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )

            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.weight(1f)
            ) {
                itemsIndexed(words) { index, word ->
                    val isBlanked = index in blankedIndices
                    if (isBlanked) {
                        OutlinedTextField(
                            value = userAnswers[index] ?: "",
                            onValueChange = { value ->
                                userAnswers[index] = value
                                showError = false
                            },
                            label = { Text("#${index + 1}", style = MaterialTheme.typography.labelSmall) },
                            singleLine = true,
                            modifier = Modifier.height(60.dp),
                            textStyle = MaterialTheme.typography.bodySmall.copy(
                                textAlign = TextAlign.Center,
                                color = TextPrimary
                            ),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = NeonCyan,
                                unfocusedBorderColor = CardBorder,
                                focusedLabelColor = NeonCyan,
                                unfocusedLabelColor = TextSecondary
                            )
                        )
                    } else {
                        Surface(
                            modifier = Modifier.height(60.dp),
                            shape = RoundedCornerShape(4.dp),
                            color = DarkSurfaceVariant
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(4.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    text = "${index + 1}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextSecondary
                                )
                                Text(
                                    text = word,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextPrimary,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }

            if (showError) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = ErrorRed.copy(alpha = 0.1f))
                ) {
                    Text(
                        text = "Incorrect words. Check your mnemonic and try again.",
                        modifier = Modifier.padding(12.dp),
                        color = ErrorRed,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            Button(
                onClick = {
                    val wordsMap = userAnswers.mapValues { it.value.trim().lowercase() }
                    val correct = mnemonic.split(" ")
                    val isCorrect = blankedIndices.all { idx ->
                        wordsMap[idx] == correct[idx]
                    }
                    if (isCorrect) {
                        onVerified()
                    } else {
                        showError = true
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = NeonCyan),
                enabled = allFilled
            ) {
                Text("Verify & Create Wallet", color = DarkBackground)
            }
        }
    }
}
