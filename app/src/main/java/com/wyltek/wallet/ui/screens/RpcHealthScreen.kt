package com.wyltek.wallet.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wyltek.wallet.core.chain.RpcHealthStatus
import com.wyltek.wallet.data.WalletViewModel
import com.wyltek.wallet.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RpcHealthScreen(onBack: () -> Unit = {}, viewModel: WalletViewModel) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("RPC Health") },
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
                    IconButton(onClick = { viewModel.refreshRpcHealth() }) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh",
                            tint = CyberBlue
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
            // Active Provider Card
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
                        text = "Active Provider",
                        style = MaterialTheme.typography.titleMedium,
                        color = CyberText,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = uiState.activeRpc ?: "None",
                        style = MaterialTheme.typography.bodyLarge,
                        color = NeonGreen
                    )
                }
            }

            // Provider Health List
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
                        text = "Provider Health",
                        style = MaterialTheme.typography.titleMedium,
                        color = CyberText,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    if (uiState.rpcHealthStatuses.isEmpty()) {
                        Text(
                            text = "No health data available",
                            style = MaterialTheme.typography.bodyMedium,
                            color = CyberGray
                        )
                    } else {
                        uiState.rpcHealthStatuses.forEach { status ->
                            RpcHealthCard(
                                status = status,
                                isActive = status.name == uiState.activeRpc,
                                onSetActive = { viewModel.setActiveRpc(status.name) }
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                    }
                }
            }

            // Failover Settings Card
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
                        text = "Failover Settings",
                        style = MaterialTheme.typography.titleMedium,
                        color = CyberText,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Auto Failover",
                                style = MaterialTheme.typography.bodyMedium,
                                color = CyberText
                            )
                            Text(
                                text = "Switch on 3+ consecutive failures",
                                style = MaterialTheme.typography.bodySmall,
                                color = CyberGray
                            )
                        }
                        Switch(
                            checked = true,
                            onCheckedChange = { },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = DarkBackground,
                                checkedTrackColor = NeonGreen,
                                uncheckedThumbColor = CyberGray,
                                uncheckedTrackColor = DarkSurfaceVariant
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Health Check Interval",
                                style = MaterialTheme.typography.bodyMedium,
                                color = CyberText
                            )
                            Text(
                                text = "Check every 30 seconds",
                                style = MaterialTheme.typography.bodySmall,
                                color = CyberGray
                            )
                        }
                        Text(
                            text = "30s",
                            style = MaterialTheme.typography.bodyMedium,
                            color = CyberBlue
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RpcHealthCard(
    status: RpcHealthStatus,
    isActive: Boolean,
    onSetActive: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isActive) NeonGreen.copy(alpha = 0.1f) else DarkSurfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(if (status.isHealthy) NeonGreen else NeonPink)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = status.name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = CyberText,
                        fontWeight = FontWeight.Bold
                    )
                }

                if (!isActive) {
                    TextButton(onClick = onSetActive) {
                        Text("Set Active", color = CyberBlue)
                    }
                } else {
                    Surface(
                        color = NeonGreen.copy(alpha = 0.2f),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = "Active",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = NeonGreen
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "Latency",
                        style = MaterialTheme.typography.labelSmall,
                        color = CyberGray
                    )
                    Text(
                        text = "${status.latencyMs}ms",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (status.latencyMs < 1000) NeonGreen else CyberYellow
                    )
                }

                Column {
                    Text(
                        text = "Tip Block",
                        style = MaterialTheme.typography.labelSmall,
                        color = CyberGray
                    )
                    Text(
                        text = status.tipBlockNumber?.toString() ?: "N/A",
                        style = MaterialTheme.typography.bodySmall,
                        color = CyberText
                    )
                }

                Column {
                    Text(
                        text = "Failures",
                        style = MaterialTheme.typography.labelSmall,
                        color = CyberGray
                    )
                    Text(
                        text = status.consecutiveFailures.toString(),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (status.consecutiveFailures > 0) NeonPink else NeonGreen
                    )
                }
            }
        }
    }
}
