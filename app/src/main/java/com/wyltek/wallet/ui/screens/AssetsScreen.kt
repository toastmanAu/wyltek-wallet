package com.wyltek.wallet.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wyltek.wallet.core.assets.AssetType
import com.wyltek.wallet.core.assets.TokenInfo
import com.wyltek.wallet.data.WalletViewModel
import com.wyltek.wallet.ui.theme.*

@Composable
fun AssetsScreen(
    onSendToken: () -> Unit = {},
    viewModel: WalletViewModel
) {
    val uiState by viewModel.uiState.collectAsState()
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("All", "Spore", "CoTA", "CKBFS", "Tokens")
    var showImportTokenDialog by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.currentAccount) {
        if (uiState.currentAccount != null) {
            viewModel.refreshAssets()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = DarkSurface,
            contentColor = NeonCyan
        ) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = { Text(title) }
                )
            }
        }

        when (selectedTab) {
            4 -> TokenList(
                tokens = uiState.tokenBalances,
                onSend = { token ->
                    viewModel.selectToken(token)
                    onSendToken()
                },
                onImportToken = { showImportTokenDialog = true }
            )
            else -> {
                val filteredAssets = when (selectedTab) {
                    1 -> uiState.sporeAssets
                    2 -> uiState.cotaAssets
                    3 -> uiState.ckbfsAssets
                    else -> uiState.allAssets
                }

                if (filteredAssets.isEmpty()) {
                    EmptyAssetsView(
                        tabName = tabs[selectedTab],
                        hasAccount = uiState.currentAccount != null,
                        isScanning = uiState.isScanningAssets
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(filteredAssets) { asset ->
                            AssetCard(
                                asset = asset,
                                onClick = { viewModel.selectAsset(asset) }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showImportTokenDialog) {
        ImportTokenDialog(
            onDismiss = { showImportTokenDialog = false },
            onImport = { codeHash, hashType, args, symbol ->
                val added = viewModel.importCustomToken(codeHash, hashType, args, symbol)
                showImportTokenDialog = false
                added
            }
        )
    }
}

@Composable
private fun TokenList(
    tokens: List<TokenInfo>,
    onSend: (TokenInfo) -> Unit,
    onImportToken: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.End
        ) {
            OutlinedButton(
                onClick = onImportToken,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = NeonCyan)
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Import Token")
            }
        }

        if (tokens.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Token,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = TextSecondary
                    )
                    Text(
                        text = "No tokens found",
                        style = MaterialTheme.typography.bodyLarge,
                        color = TextSecondary
                    )
                    Text(
                        text = "Import a custom token to track its balance",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(tokens) { token ->
                    TokenCard(
                        token = token,
                        onSend = { onSend(token) }
                    )
                }
            }
        }
    }
}

@Composable
private fun TokenCard(
    token: TokenInfo,
    onSend: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Token,
                contentDescription = null,
                tint = CyberYellow,
                modifier = Modifier.size(32.dp)
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = token.symbol,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = TextPrimary
                )
                Text(
                    text = token.typeScript.args.take(20) + "...",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = token.amount.toString(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = NeonCyan
                )
            }

            OutlinedButton(
                onClick = onSend,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = NeonCyan)
            ) {
                Text("Send")
            }
        }
    }
}

@Composable
private fun EmptyAssetsView(
    tabName: String,
    hasAccount: Boolean,
    isScanning: Boolean
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (isScanning) {
                CircularProgressIndicator(color = NeonCyan)
                Text(
                    text = "Scanning...",
                    style = MaterialTheme.typography.bodyLarge,
                    color = TextSecondary
                )
            } else {
                Icon(
                    imageVector = Icons.Default.Image,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = TextSecondary
                )
                Text(
                    text = "No assets found",
                    style = MaterialTheme.typography.bodyLarge,
                    color = TextSecondary
                )
                Text(
                    text = if (hasAccount) {
                        "No ${tabName.lowercase()} assets in this wallet"
                    } else {
                        "Create a wallet to view your assets"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
        }
    }
}

@Composable
private fun ImportTokenDialog(
    onDismiss: () -> Unit,
    onImport: (codeHash: String, hashType: String, args: String, symbol: String) -> Boolean
) {
    var codeHash by remember { mutableStateOf("") }
    var hashType by remember { mutableStateOf("type") }
    var args by remember { mutableStateOf("") }
    var symbol by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DarkSurface,
        titleContentColor = TextPrimary,
        textContentColor = TextSecondary,
        title = { Text("Import Custom Token") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = codeHash,
                    onValueChange = { codeHash = it; error = null },
                    label = { Text("Type Script Code Hash") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedBorderColor = NeonCyan,
                        unfocusedBorderColor = TextSecondary
                    )
                )
                OutlinedTextField(
                    value = hashType,
                    onValueChange = { hashType = it; error = null },
                    label = { Text("Hash Type (type or data)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedBorderColor = NeonCyan,
                        unfocusedBorderColor = TextSecondary
                    )
                )
                OutlinedTextField(
                    value = args,
                    onValueChange = { args = it; error = null },
                    label = { Text("Type Script Args") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedBorderColor = NeonCyan,
                        unfocusedBorderColor = TextSecondary
                    )
                )
                OutlinedTextField(
                    value = symbol,
                    onValueChange = { symbol = it; error = null },
                    label = { Text("Token Symbol") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedBorderColor = NeonCyan,
                        unfocusedBorderColor = TextSecondary
                    )
                )
                if (error != null) {
                    Text(
                        text = error!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (codeHash.isBlank() || args.isBlank() || symbol.isBlank()) {
                        error = "Code hash, args, and symbol are required"
                        return@Button
                    }
                    val added = onImport(codeHash, hashType, args, symbol)
                    if (!added) {
                        error = "Token already imported"
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = NeonCyan)
            ) {
                Text("Import", color = DarkBackground)
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun AssetCard(
    asset: com.wyltek.wallet.core.assets.AssetInfo,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = when (asset.type) {
                    AssetType.SPORE -> Icons.Default.Diamond
                    AssetType.COTA -> Icons.Default.Token
                    AssetType.CKBFS -> Icons.Default.Description
                    AssetType.SUDT -> Icons.Default.Token
                    AssetType.UNKNOWN -> Icons.Default.HelpOutline
                },
                contentDescription = null,
                tint = when (asset.type) {
                    AssetType.SPORE -> NeonMagenta
                    AssetType.COTA -> NeonCyan
                    AssetType.CKBFS -> NervosGreen
                    AssetType.SUDT -> CyberYellow
                    AssetType.UNKNOWN -> TextSecondary
                },
                modifier = Modifier.size(32.dp)
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = asset.name ?: "${asset.type.name} Asset",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (asset.contentType != null) {
                    Text(
                        text = asset.contentType ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = "${asset.capacity / 100_000_000u} CKB",
                    style = MaterialTheme.typography.bodySmall,
                    color = NeonCyan
                )
            }

            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = TextSecondary
            )
        }
    }
}
