package com.wyltek.wallet.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wyltek.wallet.core.passkey.JoyIDAccount
import com.wyltek.wallet.core.passkey.PasskeyCredential
import com.wyltek.wallet.data.WalletViewModel
import com.wyltek.wallet.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PasskeySettingsScreen(viewModel: WalletViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    var selectedTab by remember { mutableStateOf(0) }
    var showLinkJoyIDDialog by remember { mutableStateOf(false) }
    var showCreatePasskeyDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Passkeys & JoyID") },
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
                .background(DarkBackground)
        ) {
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = DarkSurface,
                contentColor = CyberBlue
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Passkeys") }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("JoyID") }
                )
            }

            when (selectedTab) {
                0 -> PasskeysTab(
                    viewModel = viewModel,
                    onCreatePasskey = { showCreatePasskeyDialog = true }
                )
                1 -> JoyIDTab(
                    viewModel = viewModel,
                    onLinkAccount = { showLinkJoyIDDialog = true }
                )
            }
        }
    }

    if (showLinkJoyIDDialog) {
        LinkJoyIDDialog(
            viewModel = viewModel,
            onDismiss = { showLinkJoyIDDialog = false }
        )
    }

    if (showCreatePasskeyDialog) {
        CreatePasskeyDialog(
            viewModel = viewModel,
            onDismiss = { showCreatePasskeyDialog = false }
        )
    }
}

@Composable
private fun PasskeysTab(
    viewModel: WalletViewModel,
    onCreatePasskey: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val passkeys = uiState.passkeyCredentials

    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Passkey Credentials",
                style = MaterialTheme.typography.titleMedium,
                color = CyberText
            )

            Button(
                onClick = onCreatePasskey,
                colors = ButtonDefaults.buttonColors(
                    containerColor = CyberBlue
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("Create")
            }
        }

        if (passkeys.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.Key,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = CyberGray
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "No passkeys created",
                        style = MaterialTheme.typography.bodyLarge,
                        color = CyberGray
                    )
                    Text(
                        text = "Create a passkey for secure authentication",
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
                items(passkeys) { passkey ->
                    PasskeyCard(
                        passkey = passkey,
                        onDelete = { viewModel.deletePasskey(passkey.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun JoyIDTab(
    viewModel: WalletViewModel,
    onLinkAccount: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val joyIdAccounts = uiState.joyIdAccounts

    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "JoyID Accounts",
                style = MaterialTheme.typography.titleMedium,
                color = CyberText
            )

            Button(
                onClick = onLinkAccount,
                colors = ButtonDefaults.buttonColors(
                    containerColor = NeonGreen
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Link,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("Link")
            }
        }

        if (joyIdAccounts.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.AccountCircle,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = CyberGray
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "No JoyID accounts linked",
                        style = MaterialTheme.typography.bodyLarge,
                        color = CyberGray
                    )
                    Text(
                        text = "Link your JoyID account for WebAuthn signing",
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
                items(joyIdAccounts) { account ->
                    JoyIDAccountCard(
                        account = account,
                        onUnlink = { viewModel.unlinkJoyIDAccount(account.address) }
                    )
                }
            }
        }

        // JoyID Info Section
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
                    text = "About JoyID",
                    style = MaterialTheme.typography.titleSmall,
                    color = CyberBlue
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "JoyID is a WebAuthn-based wallet that allows you to sign transactions using biometrics or security keys. Link your JoyID account to sign CKB transactions seamlessly.",
                    style = MaterialTheme.typography.bodySmall,
                    color = CyberGray
                )
            }
        }
    }
}

@Composable
private fun PasskeyCard(
    passkey: PasskeyCredential,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DarkSurface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Key,
                contentDescription = null,
                tint = CyberBlue,
                modifier = Modifier.size(32.dp)
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = passkey.name ?: "Passkey",
                    style = MaterialTheme.typography.titleSmall,
                    color = CyberText
                )
                Text(
                    text = passkey.address.take(20) + "...",
                    style = MaterialTheme.typography.bodySmall,
                    color = CyberGray
                )
                Text(
                    text = "Created: ${java.text.SimpleDateFormat("MMM dd, yyyy").format(java.util.Date(passkey.createdAt))}",
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
        }
    }
}

@Composable
private fun JoyIDAccountCard(
    account: JoyIDAccount,
    onUnlink: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DarkSurface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.AccountCircle,
                contentDescription = null,
                tint = NeonGreen,
                modifier = Modifier.size(32.dp)
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = account.name ?: "JoyID Account",
                    style = MaterialTheme.typography.titleSmall,
                    color = CyberText
                )
                Text(
                    text = account.address.take(20) + "...",
                    style = MaterialTheme.typography.bodySmall,
                    color = CyberGray
                )
                Text(
                    text = "Pubkey: ${account.pubkeyHash.take(16)}...",
                    style = MaterialTheme.typography.labelSmall,
                    color = CyberGray
                )
            }

            Surface(
                color = NeonGreen,
                shape = RoundedCornerShape(4.dp)
            ) {
                Text(
                    text = "Linked",
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = DarkBackground
                )
            }

            IconButton(onClick = onUnlink) {
                Icon(
                    imageVector = Icons.Default.LinkOff,
                    contentDescription = "Unlink",
                    tint = NeonPink
                )
            }
        }
    }
}

@Composable
private fun CreatePasskeyDialog(
    viewModel: WalletViewModel,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create Passkey") },
        text = {
            Column {
                Text(
                    text = "Create a new passkey for secure authentication. This will generate a new key pair stored securely on your device.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = CyberText
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Passkey Name") },
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
                    if (name.isNotBlank()) {
                        viewModel.createPasskey(name)
                        onDismiss()
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = NeonGreen
                )
            ) {
                Text("Create", color = DarkBackground)
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
private fun LinkJoyIDDialog(
    viewModel: WalletViewModel,
    onDismiss: () -> Unit
) {
    var address by remember { mutableStateOf("") }
    var pubkeyHash by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Link JoyID Account") },
        text = {
            Column {
                Text(
                    text = "Link your JoyID account to sign transactions using WebAuthn. You'll need to provide your JoyID address and public key hash.",
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
                    value = address,
                    onValueChange = { address = it },
                    label = { Text("JoyID Address") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CyberBlue,
                        unfocusedBorderColor = CyberGray
                    )
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = pubkeyHash,
                    onValueChange = { pubkeyHash = it },
                    label = { Text("Public Key Hash") },
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
                    if (address.isNotBlank() && pubkeyHash.isNotBlank()) {
                        viewModel.linkJoyIDAccount(address, pubkeyHash, name.ifBlank { null })
                        onDismiss()
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = NeonGreen
                )
            ) {
                Text("Link", color = DarkBackground)
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
