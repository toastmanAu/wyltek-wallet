package com.wyltek.wallet.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wyltek.wallet.agent.ui.AgentViewModel
import com.wyltek.wallet.agent.ui.PendingRow
import com.wyltek.wallet.ui.security.BiometricResult
import com.wyltek.wallet.ui.security.rememberBiometricAuth
import com.wyltek.wallet.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentApprovalScreen(
    onBack: () -> Unit = {},
    viewModel: AgentViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val biometric = rememberBiometricAuth()

    LaunchedEffect(Unit) {
        viewModel.loadPending()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Pending Approvals") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkSurface)
        )

        if (uiState.pending.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No pending approvals",
                    style = MaterialTheme.typography.bodyLarge,
                    color = TextSecondary
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                uiState.error?.let { error ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = ErrorRed.copy(alpha = 0.1f))
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = error,
                                modifier = Modifier.weight(1f),
                                color = ErrorRed,
                                style = MaterialTheme.typography.bodySmall
                            )
                            TextButton(onClick = { viewModel.clearError() }) {
                                Text("Dismiss", color = TextSecondary)
                            }
                        }
                    }
                }

                uiState.pending.forEach { row ->
                    PendingApprovalCard(
                        row = row,
                        onApprove = {
                            biometric.authenticate(
                                title = "Approve agent transaction",
                                subtitle = "${row.op} ${row.amount} ${row.asset} to ${row.to}",
                                description = "An agent requested a spend above its auto-limit."
                            ) { result ->
                                when (result) {
                                    is BiometricResult.Success -> viewModel.approve(row.id)
                                    is BiometricResult.Cancelled -> { /* silent */ }
                                    is BiometricResult.Error -> {
                                        // error surfaced through uiState.error via viewModel
                                    }
                                    is BiometricResult.Unavailable -> {
                                        // prompt user to set up device credential
                                    }
                                }
                            }
                        },
                        onReject = { viewModel.reject(row.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun PendingApprovalCard(
    row: PendingRow,
    onApprove: () -> Unit,
    onReject: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = row.op.uppercase(),
                    style = MaterialTheme.typography.labelLarge,
                    color = NeonCyan
                )
                row.action?.let { action ->
                    Surface(
                        color = NeonCyan.copy(alpha = 0.1f),
                        shape = MaterialTheme.shapes.small
                    ) {
                        Text(
                            text = action,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = NeonCyan
                        )
                    }
                }
            }

            HorizontalDivider(color = CardBorder)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Amount",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
                Text(
                    text = "${row.amount} ${row.asset}",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextPrimary
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "To",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
                Text(
                    text = if (row.to.length > 20) row.to.take(10) + "…" + row.to.takeLast(8) else row.to,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextPrimary
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onReject,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = ErrorRed)
                ) {
                    Text("Reject")
                }
                Button(
                    onClick = onApprove,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = NeonCyan)
                ) {
                    Text("Approve", color = DarkBackground)
                }
            }
        }
    }
}
