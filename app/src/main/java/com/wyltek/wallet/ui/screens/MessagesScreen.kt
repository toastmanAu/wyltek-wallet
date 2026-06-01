package com.wyltek.wallet.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wyltek.wallet.core.messaging.ContactProfile
import com.wyltek.wallet.core.messaging.Conversation
import com.wyltek.wallet.core.messaging.Message
import com.wyltek.wallet.data.WalletViewModel
import com.wyltek.wallet.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessagesScreen(viewModel: WalletViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    var selectedTab by remember { mutableStateOf(0) }
    var showAddContactDialog by remember { mutableStateOf(false) }
    var selectedConversation by remember { mutableStateOf<Conversation?>(null) }

    if (selectedConversation != null) {
        ConversationScreen(
            conversation = selectedConversation!!,
            onBack = { selectedConversation = null },
            onSendMessage = { content ->
                viewModel.sendMessage(selectedConversation!!.contactAddress, content)
            },
            viewModel = viewModel
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Messages") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = DarkSurface
                ),
                actions = {
                    IconButton(onClick = { showAddContactDialog = true }) {
                        Icon(
                            imageVector = Icons.Default.PersonAdd,
                            contentDescription = "Add Contact"
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
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = DarkSurface,
                contentColor = CyberBlue
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Conversations") }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Contacts") }
                )
            }

            when (selectedTab) {
                0 -> ConversationsTab(
                    viewModel = viewModel,
                    onConversationClick = { selectedConversation = it }
                )
                1 -> ContactsTab(
                    viewModel = viewModel,
                    onContactClick = { contact ->
                        viewModel.createConversation(contact.address)
                    }
                )
            }
        }
    }

    if (showAddContactDialog) {
        AddContactDialog(
            onDismiss = { showAddContactDialog = false },
            onConfirm = { address, name ->
                viewModel.addContact(address, name)
                showAddContactDialog = false
            }
        )
    }
}

@Composable
private fun ConversationsTab(
    viewModel: WalletViewModel,
    onConversationClick: (Conversation) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val conversations = uiState.conversations

    if (conversations.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Default.Mail,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = CyberGray
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "No conversations yet",
                    style = MaterialTheme.typography.bodyLarge,
                    color = CyberGray
                )
                Text(
                    text = "Add a contact to start messaging",
                    style = MaterialTheme.typography.bodySmall,
                    color = CyberGray
                )
            }
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(8.dp)
        ) {
            items(conversations) { conversation ->
                ConversationCard(
                    conversation = conversation,
                    onClick = { onConversationClick(conversation) }
                )
            }
        }
    }
}

@Composable
private fun ContactsTab(
    viewModel: WalletViewModel,
    onContactClick: (ContactProfile) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val contacts = uiState.contacts

    if (contacts.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Default.People,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = CyberGray
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "No contacts yet",
                    style = MaterialTheme.typography.bodyLarge,
                    color = CyberGray
                )
                Text(
                    text = "Tap + to add a contact",
                    style = MaterialTheme.typography.bodySmall,
                    color = CyberGray
                )
            }
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(8.dp)
        ) {
            items(contacts) { contact ->
                ContactCard(
                    contact = contact,
                    onClick = { onContactClick(contact) }
                )
            }
        }
    }
}

@Composable
private fun ConversationCard(
    conversation: Conversation,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = DarkSurface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(CyberBlue),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = (conversation.contactName ?: conversation.contactAddress.take(2)).uppercase(),
                    style = MaterialTheme.typography.titleMedium,
                    color = DarkBackground,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = conversation.contactName ?: conversation.contactAddress.take(10) + "...",
                        style = MaterialTheme.typography.titleSmall,
                        color = CyberText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )

                    if (conversation.lastMessage != null) {
                        val lastMsg = conversation.lastMessage
                        if (lastMsg != null) {
                            Text(
                                text = formatTimestamp(lastMsg.timestamp),
                                style = MaterialTheme.typography.labelSmall,
                                color = CyberGray
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = conversation.lastMessage?.content?.let { String(it) } ?: "No messages",
                        style = MaterialTheme.typography.bodySmall,
                        color = CyberGray,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )

                    if (conversation.unreadCount > 0) {
                        Surface(
                            color = NeonPink,
                            shape = CircleShape
                        ) {
                            Text(
                                text = conversation.unreadCount.toString(),
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = DarkBackground,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ContactCard(
    contact: ContactProfile,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = DarkSurface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(if (contact.discovered) NeonGreen else CyberYellow),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = (contact.name ?: contact.address.take(2)).uppercase(),
                    style = MaterialTheme.typography.titleMedium,
                    color = DarkBackground,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = contact.name ?: "Unknown Contact",
                    style = MaterialTheme.typography.titleSmall,
                    color = CyberText
                )

                Text(
                    text = contact.address.take(20) + "...",
                    style = MaterialTheme.typography.bodySmall,
                    color = CyberGray
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
fun ConversationScreen(
    conversation: Conversation,
    onBack: () -> Unit,
    onSendMessage: (ByteArray) -> Unit,
    viewModel: WalletViewModel
) {
    var messageText by remember { mutableStateOf("") }
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = conversation.contactName ?: conversation.contactAddress.take(10) + "...",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = conversation.contactAddress.take(20) + "...",
                            style = MaterialTheme.typography.bodySmall,
                            color = CyberGray
                        )
                    }
                },
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
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(conversation.messages) { message ->
                    MessageBubble(
                        message = message,
                        isOwn = message.from == uiState.currentAccount?.addresses?.firstOrNull()?.bech32m
                    )
                }
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = DarkSurface
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = messageText,
                        onValueChange = { messageText = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Type a message...", color = CyberGray) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = CyberBlue,
                            unfocusedBorderColor = CyberGray
                        )
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    IconButton(
                        onClick = {
                            if (messageText.isNotBlank()) {
                                onSendMessage(messageText.toByteArray())
                                messageText = ""
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Send,
                            contentDescription = "Send",
                            tint = CyberBlue
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(
    message: Message,
    isOwn: Boolean
) {
    val alignment = if (isOwn) Alignment.CenterEnd else Alignment.CenterStart
    val backgroundColor = if (isOwn) CyberBlue else DarkSurface
    val textColor = if (isOwn) DarkBackground else CyberText

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = alignment
    ) {
        Surface(
            modifier = Modifier.widthIn(max = 280.dp),
            color = backgroundColor,
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(
                modifier = Modifier.padding(12.dp)
            ) {
                Text(
                    text = String(message.content),
                    style = MaterialTheme.typography.bodyMedium,
                    color = textColor
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = formatTimestamp(message.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isOwn) DarkSurfaceVariant else CyberGray
                )
            }
        }
    }
}

@Composable
private fun AddContactDialog(
    onDismiss: () -> Unit,
    onConfirm: (address: String, name: String) -> Unit
) {
    var address by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Contact") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CyberBlue,
                        unfocusedBorderColor = CyberGray
                    )
                )

                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    label = { Text("CKB Address") },
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
                    if (address.isNotBlank() && name.isNotBlank()) {
                        onConfirm(address, name)
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = NeonGreen
                )
            ) {
                Text("Add", color = DarkBackground)
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

private fun formatTimestamp(timestamp: ULong): String {
    val millis = timestamp.toLong()
    val seconds = millis / 1000
    val minutes = seconds / 60
    val hours = minutes / 60
    val days = hours / 24

    return when {
        days > 0 -> "${days}d ago"
        hours > 0 -> "${hours}h ago"
        minutes > 0 -> "${minutes}m ago"
        else -> "Just now"
    }
}
