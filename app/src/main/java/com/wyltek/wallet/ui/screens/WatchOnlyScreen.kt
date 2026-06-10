package com.wyltek.wallet.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wyltek.wallet.core.watchonly.WatchOnlyAccount
import com.wyltek.wallet.data.WalletViewModel
import com.wyltek.wallet.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WatchOnlyScreen(onBack: () -> Unit = {}, viewModel: WalletViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    var showImportDialog by remember { mutableStateOf(false) }
    var selectedAccount by remember { mutableStateOf<WatchOnlyAccount?>(null) }

    if (selectedAccount != null) {
        WatchOnlyDetailScreen(
            account = selectedAccount!!,
            onBack = { selectedAccount = null },
            viewModel = viewModel
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Watch-Only Wallets") },
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
                ),
                actions = {
                    IconButton(onClick = { showImportDialog = true }) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Import xpub"
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(DarkBackground)
        ) {
            if (uiState.watchOnlyAccounts.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.Visibility,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = CyberGray
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "No watch-only wallets",
                            style = MaterialTheme.typography.bodyLarge,
                            color = CyberGray
                        )
                        Text(
                            text = "Import an xpub to monitor addresses",
                            style = MaterialTheme.typography.bodySmall,
                            color = CyberGray
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(uiState.watchOnlyAccounts) { account ->
                        WatchOnlyAccountCard(
                            account = account,
                            onClick = { selectedAccount = account },
                            onDelete = { viewModel.deleteWatchOnlyAccount(account.id) }
                        )
                    }
                }
            }
        }
    }

    if (showImportDialog) {
        ImportXpubDialog(
            onDismiss = { showImportDialog = false },
            onConfirm = { name, xpub, path ->
                viewModel.importWatchOnlyAccount(name, xpub, path)
                showImportDialog = false
            }
        )
    }
}

@Composable
private fun WatchOnlyAccountCard(
    account: WatchOnlyAccount,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = DarkSurface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Visibility,
                contentDescription = null,
                tint = CyberYellow,
                modifier = Modifier.size(32.dp)
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = account.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = CyberText,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Path: ${account.derivationPath}",
                    style = MaterialTheme.typography.bodySmall,
                    color = CyberGray
                )
                Text(
                    text = "${account.addresses.size} addresses",
                    style = MaterialTheme.typography.labelSmall,
                    color = CyberGray
                )
            }

            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Delete",
                    tint = NeonPink
                )
            }

            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = CyberGray
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WatchOnlyDetailScreen(
    account: WatchOnlyAccount,
    onBack: () -> Unit,
    viewModel: WalletViewModel
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(account.name) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = DarkSurface
                ),
                actions = {
                    IconButton(onClick = { viewModel.refreshWatchOnlyAddresses(account.id) }) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh"
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(DarkBackground)
        ) {
            // Account Info Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurface)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "xpub",
                        style = MaterialTheme.typography.labelLarge,
                        color = CyberBlue
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = account.xpub.take(20) + "..." + account.xpub.takeLast(10),
                        style = MaterialTheme.typography.bodySmall,
                        color = CyberGray
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                text = "Derivation Path",
                                style = MaterialTheme.typography.labelSmall,
                                color = CyberGray
                            )
                            Text(
                                text = account.derivationPath,
                                style = MaterialTheme.typography.bodySmall,
                                color = CyberText
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = "Addresses",
                                style = MaterialTheme.typography.labelSmall,
                                color = CyberGray
                            )
                            Text(
                                text = account.addresses.size.toString(),
                                style = MaterialTheme.typography.bodySmall,
                                color = CyberText
                            )
                        }
                    }
                }
            }

            // Addresses List
            Text(
                text = "Derived Addresses",
                style = MaterialTheme.typography.titleSmall,
                color = CyberBlue,
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            Spacer(modifier = Modifier.height(8.dp))

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(account.addresses) { address ->
                    AddressCard(address = address)
                }
            }
        }
    }
}

@Composable
private fun AddressCard(address: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DarkSurface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.AccountBalanceWallet,
                contentDescription = null,
                tint = CyberBlue,
                modifier = Modifier.size(24.dp)
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = address.take(20) + "...",
                    style = MaterialTheme.typography.bodySmall,
                    color = CyberText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Icon(
                imageVector = Icons.Default.ContentCopy,
                contentDescription = "Copy",
                tint = CyberGray
            )
        }
    }
}

@Composable
private fun ImportXpubDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String, xpub: String, path: String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var xpub by remember { mutableStateOf("") }
    var path by remember { mutableStateOf("m/44'/302'/0'") }
    var isValid by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import xpub") },
        text = {
            Column {
                Text(
                    text = "Import an extended public key to monitor addresses without exposing private keys.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = CyberText
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Account Name") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CyberBlue,
                        unfocusedBorderColor = CyberGray
                    )
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = xpub,
                    onValueChange = {
                        xpub = it
                        isValid = it.isBlank() || it.startsWith("xpub") || it.startsWith("ypub") || it.startsWith("zpub")
                    },
                    label = { Text("xpub / ypub / zpub") },
                    modifier = Modifier.fillMaxWidth(),
                    isError = !isValid,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CyberBlue,
                        unfocusedBorderColor = CyberGray,
                        errorBorderColor = NeonPink
                    )
                )
                if (!isValid) {
                    Text(
                        text = "Invalid xpub format",
                        style = MaterialTheme.typography.labelSmall,
                        color = NeonPink
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = path,
                    onValueChange = { path = it },
                    label = { Text("Derivation Path") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CyberBlue,
                        unfocusedBorderColor = CyberGray
                    )
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (name.isNotBlank() && xpub.isNotBlank() && isValid) {
                        onConfirm(name, xpub, path)
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = NeonGreen
                )
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
