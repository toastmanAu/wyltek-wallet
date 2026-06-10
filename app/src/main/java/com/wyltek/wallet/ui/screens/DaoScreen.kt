package com.wyltek.wallet.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wyltek.wallet.core.model.*
import com.wyltek.wallet.data.WalletViewModel
import com.wyltek.wallet.ui.theme.*

private fun formatCkb(shannons: ULong): String {
    val ckb = shannons / 100_000_000u
    val remainder = shannons % 100_000_000u
    val fraction = remainder.toString().padStart(8, '0').trimEnd('0')
    return if (fraction.isNotEmpty()) "$ckb.$fraction" else "$ckb"
}

private fun statusLabel(status: DaoCellStatus): String = when (status) {
    DaoCellStatus.DEPOSITING -> "Depositing"
    DaoCellStatus.DEPOSITED -> "Deposited"
    DaoCellStatus.WITHDRAWING -> "Withdrawing"
    DaoCellStatus.LOCKED -> "Locked"
    DaoCellStatus.UNLOCKABLE -> "Unlockable"
    DaoCellStatus.UNLOCKING -> "Unlocking"
    DaoCellStatus.COMPLETED -> "Completed"
}

private fun statusColor(status: DaoCellStatus) = when (status) {
    DaoCellStatus.DEPOSITING -> WarningOrange
    DaoCellStatus.DEPOSITED -> NervosGreen
    DaoCellStatus.WITHDRAWING -> InfoBlue
    DaoCellStatus.LOCKED -> TextSecondary
    DaoCellStatus.UNLOCKABLE -> NeonCyan
    DaoCellStatus.UNLOCKING -> InfoBlue
    DaoCellStatus.COMPLETED -> SuccessGreen
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DaoScreen(
    onBack: () -> Unit = {},
    viewModel: WalletViewModel
) {
    val uiState by viewModel.uiState.collectAsState()
    val deposits = uiState.daoDeposits
    val overview = uiState.daoOverview
    val selectedTab = uiState.daoSelectedTab
    val isLoading = uiState.isLoadingDao
    val error = uiState.daoError
    val pendingDaoTxHash = uiState.pendingDaoTxHash
    val pendingDaoAction = uiState.pendingDaoAction
    val isSendingTx = uiState.isLoading

    var showDepositDialog by remember { mutableStateOf(false) }
    var depositAmount by remember { mutableStateOf("") }

    LaunchedEffect(uiState.currentAccount) {
        if (uiState.currentAccount != null) {
            viewModel.refreshDaoDeposits()
        }
    }

    LaunchedEffect(pendingDaoTxHash) {
        if (pendingDaoTxHash == null && uiState.currentAccount != null) {
            viewModel.refreshDaoDeposits()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        TopAppBar(
            title = { Text("Nervos DAO", color = TextPrimary) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = TextPrimary
                    )
                }
            },
            actions = {
                IconButton(onClick = { viewModel.refreshDaoDeposits() }) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = "Refresh",
                        tint = TextPrimary
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = DarkBackground
            )
        )

        if (isSendingTx) {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = NervosGreen.copy(alpha = 0.15f))
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = NervosGreen
                        )
                        Text("Broadcasting transaction...", color = NervosGreen, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }

        OverviewCard(overview)

        Spacer(modifier = Modifier.height(12.dp))

        TabRow(selectedTabIndex = if (selectedTab == DaoTab.ACTIVE) 0 else 1,
            containerColor = DarkSurface,
            contentColor = NeonCyan
        ) {
            Tab(
                selected = selectedTab == DaoTab.ACTIVE,
                onClick = { viewModel.selectDaoTab(DaoTab.ACTIVE) },
                text = { Text("Active (${overview.activeCount})") }
            )
            Tab(
                selected = selectedTab == DaoTab.COMPLETED,
                onClick = { viewModel.selectDaoTab(DaoTab.COMPLETED) },
                text = { Text("Completed (${overview.completedCount})") }
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (error != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = ErrorRed.copy(alpha = 0.1f))
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = ErrorRed)
                    Text(error, color = ErrorRed, style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        Button(
            onClick = { showDepositDialog = true },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = NervosGreen)
        ) {
            Icon(Icons.Default.Savings, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Deposit to DAO", color = DarkBackground, fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (isLoading) {
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = NeonCyan)
            }
        } else {
            val filteredDeposits = if (selectedTab == DaoTab.ACTIVE) {
                val onChain = deposits.filter { it.status != DaoCellStatus.COMPLETED }
                val hasPendingDeposit = pendingDaoTxHash != null && pendingDaoAction == "deposit"
                val hasPendingWithdraw = pendingDaoTxHash != null && pendingDaoAction == "withdraw"
                val pendingItems = mutableListOf<DaoDeposit>()
                if (hasPendingDeposit) {
                    pendingItems.add(DaoDeposit(
                        outPoint = OutPoint(txHash = pendingDaoTxHash!!, index = 0u),
                        capacity = 0u,
                        status = DaoCellStatus.DEPOSITING,
                        depositBlockNumber = 0u
                    ))
                }
                if (hasPendingWithdraw) {
                    pendingItems.add(DaoDeposit(
                        outPoint = OutPoint(txHash = pendingDaoTxHash!!, index = 0u),
                        capacity = 0u,
                        status = DaoCellStatus.WITHDRAWING,
                        depositBlockNumber = 0u
                    ))
                }
                pendingItems + onChain
            } else {
                deposits.filter { it.status == DaoCellStatus.COMPLETED }
            }

            if (filteredDeposits.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.AccountBalance,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = TextSecondary.copy(alpha = 0.5f)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            if (selectedTab == DaoTab.ACTIVE) "No active deposits" else "No completed deposits",
                            color = TextSecondary,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filteredDeposits, key = { "${it.outPoint.txHash}:${it.outPoint.index}" }) { deposit ->
                        DepositCard(
                            deposit = deposit,
                            onWithdraw = { viewModel.withdrawDaoPhase1(deposit) },
                            onUnlock = { viewModel.unlockDao(deposit) }
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        DaoInfoBanner()
    }

    if (showDepositDialog) {
        DepositDialog(
            currentBalance = uiState.balanceCkb,
            onConfirm = { amountShannons ->
                viewModel.depositDao(amountShannons)
                showDepositDialog = false
            },
            onDismiss = { showDepositDialog = false }
        )
    }
}

@Composable
private fun OverviewCard(overview: DaoOverview) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CardBackground)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "DAO Overview",
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
                fontWeight = FontWeight.Bold
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("Total Deposited", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                    Text(
                        "${formatCkb(overview.totalDeposited)} CKB",
                        style = MaterialTheme.typography.titleLarge,
                        color = NeonCyan,
                        fontWeight = FontWeight.Bold
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("Compensation", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                    Text(
                        "+${formatCkb(overview.totalCompensation)} CKB",
                        style = MaterialTheme.typography.titleLarge,
                        color = NervosGreen,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            if (overview.currentApc > 0) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Current APC", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                    Text(
                        "~${String.format("%.2f", overview.currentApc)}%",
                        style = MaterialTheme.typography.labelLarge,
                        color = NervosGreenLight,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

@Composable
private fun DepositCard(deposit: DaoDeposit, onWithdraw: (DaoDeposit) -> Unit = {}, onUnlock: (DaoDeposit) -> Unit = {}) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (deposit.status == DaoCellStatus.DEPOSITING || deposit.status == DaoCellStatus.WITHDRAWING) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = when (deposit.status) {
                                DaoCellStatus.DEPOSITING -> NervosGreen
                                DaoCellStatus.WITHDRAWING -> InfoBlue
                                else -> TextSecondary
                            }
                        )
                        Text(
                            when (deposit.status) {
                                DaoCellStatus.DEPOSITING -> "Depositing..."
                                DaoCellStatus.WITHDRAWING -> "Withdrawing (Phase 1)..."
                                else -> "Pending..."
                            },
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                } else {
                    Text(
                        "${formatCkb(deposit.capacity)} CKB",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                }
                Surface(
                    color = statusColor(deposit.status).copy(alpha = 0.15f),
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(
                        statusLabel(deposit.status),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = statusColor(deposit.status),
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            if (deposit.compensation > 0u) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Compensation", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                    Text(
                        "+${formatCkb(deposit.compensation)} CKB",
                        style = MaterialTheme.typography.bodySmall,
                        color = NervosGreen,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            if (deposit.apc > 0) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("APC", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                    Text(
                        "~${String.format("%.2f", deposit.apc)}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = NervosGreenLight
                    )
                }
            }

            if (deposit.status == DaoCellStatus.LOCKED && deposit.unlockEpoch != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Unlocks in", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                    Text(
                        "${deposit.lockRemainingEpochs} epochs (~${deposit.lockRemainingEpochs * 4}h)",
                        style = MaterialTheme.typography.bodySmall,
                        color = WarningOrange
                    )
                }
                if (deposit.compensationCycleProgress > 0f) {
                    LinearProgressIndicator(
                        progress = { deposit.compensationCycleProgress },
                        modifier = Modifier.fillMaxWidth(),
                        color = when (deposit.cyclePhase) {
                            CyclePhase.NORMAL -> NervosGreen
                            CyclePhase.SUGGESTED -> WarningOrange
                            CyclePhase.ENDING -> ErrorRed
                        },
                        trackColor = CardBorder,
                    )
                }
            }

            if (deposit.status == DaoCellStatus.UNLOCKABLE) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Button(
                        onClick = { onUnlock(deposit) },
                        colors = ButtonDefaults.buttonColors(containerColor = NeonCyan)
                    ) {
                        Icon(Icons.Default.LockOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Unlock", color = DarkBackground)
                    }
                }
            }

            if (deposit.status == DaoCellStatus.DEPOSITED) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Button(
                        onClick = { onWithdraw(deposit) },
                        colors = ButtonDefaults.buttonColors(containerColor = WarningOrange)
                    ) {
                        Icon(Icons.Default.Outbox, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Withdraw (Phase 1)", color = DarkBackground)
                    }
                }
            }

            Text(
                "Block #${deposit.depositBlockNumber}",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
private fun DepositDialog(
    currentBalance: ULong,
    onConfirm: (ULong) -> Unit,
    onDismiss: () -> Unit
) {
    var amount by remember { mutableStateOf("") }
    val balanceCkb = (currentBalance / 100_000_000u).toLong()
    val minDeposit = 102

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DarkSurface,
        title = { Text("Deposit to Nervos DAO", color = TextPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Deposit CKB into the Nervos DAO to earn ~2.1% annual compensation. " +
                    "Minimum deposit is 102 CKB (61 CKB occupied + 1 CKB minimum deposit).",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
                Text(
                    "Available: $balanceCkb CKB",
                    style = MaterialTheme.typography.labelLarge,
                    color = NervosGreen
                )
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("Amount (CKB)") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = NeonCyan,
                        unfocusedBorderColor = CardBorder
                    )
                )
                Text(
                    "Phase 1 withdrawal locks funds for ~180 epochs (~30 days) before you can unlock.",
                    style = MaterialTheme.typography.bodySmall,
                    color = WarningOrange
                )
            }
        },
        confirmButton = {
            val amountDouble = amount.toDoubleOrNull() ?: 0.0
            val amountShannons = (amountDouble * 100_000_000).toULong()
            val isValid = amountDouble >= minDeposit && amountShannons <= currentBalance
            TextButton(
                onClick = { if (isValid) onConfirm(amountShannons) },
                enabled = isValid
            ) {
                Text("Deposit", color = if (isValid) NervosGreen else TextSecondary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextSecondary)
            }
        }
    )
}

@Composable
private fun DaoInfoBanner() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = InfoBlue.copy(alpha = 0.1f))
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Info, contentDescription = null, tint = InfoBlue, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("2-Phase Withdrawal", style = MaterialTheme.typography.labelLarge, color = InfoBlue, fontWeight = FontWeight.Bold)
            }
            Text(
                "Phase 1: Request withdrawal locks your deposit for ~180 epochs (~30 days). " +
                "Phase 2: After lock period expires, unlock to reclaim CKB + compensation.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
        }
    }
}