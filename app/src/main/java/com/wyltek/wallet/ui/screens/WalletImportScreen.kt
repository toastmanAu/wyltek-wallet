package com.wyltek.wallet.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.wyltek.wallet.core.native.RustNative
import com.wyltek.wallet.data.WalletViewModel
import com.wyltek.wallet.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WalletImportScreen(
    onBack: () -> Unit = {},
    viewModel: WalletViewModel
) {
    var walletName by remember { mutableStateOf("") }
    val uiState by viewModel.uiState.collectAsState()

    val wordCount = 24
    val words = remember { mutableStateListOf<String>().apply { repeat(wordCount) { add("") } } }
    var currentInput by remember { mutableStateOf("") }
    var focusedIndex by remember { mutableIntStateOf(0) }
    var suggestions by remember { mutableStateOf<List<String>>(emptyList()) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(currentInput) {
        if (currentInput.length >= 2) {
            suggestions = try {
                RustNative.suggestBip39Words(currentInput.trim(), 8u)
            } catch (e: Exception) {
                emptyList()
            }
        } else {
            suggestions = emptyList()
        }
    }

    LaunchedEffect(uiState.currentAccount) {
        if (uiState.currentAccount != null && uiState.error == null) {
            onBack()
        }
    }

    fun acceptWord(word: String) {
        val cleaned = word.trim().lowercase()
        if (cleaned.isNotEmpty() && focusedIndex < wordCount) {
            words[focusedIndex] = cleaned
            currentInput = ""
            suggestions = emptyList()
            val nextEmpty = words.indexOfFirst { it.isEmpty() }
            focusedIndex = if (nextEmpty != -1) nextEmpty else focusedIndex
        }
    }

    val allWordsFilled = words.all { it.isNotEmpty() }
    val validMnemonic = allWordsFilled && try {
        RustNative.validateMnemonic(words.joinToString(" "))
        true
    } catch (_: Exception) {
        false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Import Wallet") },
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
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = walletName,
                onValueChange = { walletName = it },
                label = { Text("Wallet Name") },
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = NeonCyan,
                    unfocusedBorderColor = CardBorder
                ),
                singleLine = true
            )

            Text(
                text = "Enter your ${wordCount}-word mnemonic phrase",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )

            OutlinedTextField(
                value = currentInput,
                onValueChange = { newValue ->
                    if (newValue.contains(" ") || newValue.contains("\n")) {
                        val parts = newValue.trim().split(Regex("[\\s]+"))
                        parts.filter { it.isNotBlank() }.forEach { part ->
                            acceptWord(part)
                        }
                        focusRequester.requestFocus()
                    } else {
                        currentInput = newValue.lowercase()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
                label = {
                    Text(
                        if (focusedIndex < wordCount)
                            "Type word #${focusedIndex + 1} — space or tap suggestion to confirm"
                        else
                            "All words entered"
                    )
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = NeonCyan,
                    unfocusedBorderColor = CardBorder
                ),
                singleLine = true,
                enabled = focusedIndex < wordCount
            )

            if (suggestions.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(4.dp)) {
                        suggestions.forEach { suggestion ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        acceptWord(suggestion)
                                        focusRequester.requestFocus()
                                    }
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = suggestion,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = TextPrimary
                                )
                                val wordIndex = words.indexOfFirst { it.isEmpty() }
                                if (wordIndex != -1) {
                                    Text(
                                        text = "#${wordIndex + 1}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TextSecondary
                                    )
                                }
                            }
                        }
                    }
                }
            }

            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                userScrollEnabled = false,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 310.dp)
            ) {
                itemsIndexed(words) { index, word ->
                    val isFocused = index == focusedIndex
                    val isFilled = word.isNotEmpty()
                    val displayText = when {
                        isFocused && currentInput.isNotEmpty() -> currentInput
                        isFilled -> word
                        else -> ""
                    }

                    Surface(
                        modifier = Modifier
                            .height(48.dp)
                            .then(
                                if (isFocused) {
                                    Modifier.border(2.dp, NeonCyan, RoundedCornerShape(4.dp))
                                } else if (isFilled) {
                                    Modifier.border(1.dp, CardBorder, RoundedCornerShape(4.dp))
                                } else {
                                    Modifier.border(1.dp, CardBorder.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                                }
                            )
                            .clickable {
                                focusedIndex = index
                                currentInput = if (isFilled) word else ""
                                focusRequester.requestFocus()
                            },
                        shape = RoundedCornerShape(4.dp),
                        color = when {
                            isFocused -> NeonCyan.copy(alpha = 0.1f)
                            isFilled -> DarkSurfaceVariant
                            else -> DarkSurfaceVariant.copy(alpha = 0.5f)
                        }
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(2.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = "${index + 1}",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isFocused) NeonCyan else TextSecondary.copy(alpha = 0.6f),
                                textAlign = TextAlign.Center
                            )
                            if (displayText.isNotEmpty()) {
                                Text(
                                    text = displayText,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (isFocused && currentInput.isNotEmpty()) NeonCyan else TextPrimary,
                                    fontWeight = FontWeight.Medium,
                                    textAlign = TextAlign.Center,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }

            if (uiState.error != null) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = ErrorRed.copy(alpha = 0.1f))
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = uiState.error ?: "",
                            modifier = Modifier.weight(1f),
                            color = ErrorRed,
                            style = MaterialTheme.typography.bodySmall
                        )
                        IconButton(onClick = { viewModel.clearError() }) {
                            Icon(Icons.Default.Close, contentDescription = "Dismiss", tint = ErrorRed)
                        }
                    }
                }
            }

            Button(
                onClick = {
                    val mnemonic = words.joinToString(" ")
                    viewModel.importWallet(walletName, mnemonic)
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (validMnemonic) NeonCyan else DarkSurfaceVariant
                ),
                enabled = walletName.isNotBlank() && validMnemonic && !uiState.isLoading
            ) {
                if (uiState.isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = DarkBackground
                    )
                } else {
                    Icon(Icons.Default.Check, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "Import Wallet",
                        color = if (validMnemonic) DarkBackground else TextSecondary
                    )
                }
            }
        }
    }
}
