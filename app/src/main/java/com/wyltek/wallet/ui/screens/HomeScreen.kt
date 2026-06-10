package com.wyltek.wallet.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wyltek.wallet.R
import com.wyltek.wallet.core.model.WalletAccount
import com.wyltek.wallet.core.skin.PanelZone
import com.wyltek.wallet.core.skin.ThemeConfig
import com.wyltek.wallet.data.WalletViewModel
import com.wyltek.wallet.data.WalletUiState
import com.wyltek.wallet.ui.security.BiometricResult
import com.wyltek.wallet.ui.security.rememberBiometricAuth
import com.wyltek.wallet.ui.theme.*

private val backgroundResMap = mapOf(
    "bg_black_leather" to R.drawable.bg_black_leather,
    "bg_brown_leather_01" to R.drawable.bg_brown_leather_01,
    "bg_brown_leather_02" to R.drawable.bg_brown_leather_02,
    "bg_brown_leather_03" to R.drawable.bg_brown_leather_03,
    "bg_brown_leather_04" to R.drawable.bg_brown_leather_04,
    "bg_circuit_board" to R.drawable.bg_circuit_board,
    "bg_forest" to R.drawable.bg_forest,
    "bg_marble" to R.drawable.bg_marble,
    "bg_wood_dark_01" to R.drawable.bg_wood_dark_01,
    "bg_wood_dark_02" to R.drawable.bg_wood_dark_02,
)

@Composable
fun HomeScreen(
    onSend: () -> Unit = {},
    onReceive: () -> Unit = {},
    onInternalTransfer: () -> Unit = {},
    onTransactionHistory: () -> Unit = {},
    onCreateWallet: () -> Unit = {},
    onImportWallet: () -> Unit = {},
    onSettings: () -> Unit = {},
    onDao: () -> Unit = {},
    viewModel: WalletViewModel
) {
    val uiState by viewModel.uiState.collectAsState()
    val theme = uiState.currentTheme ?: ThemeConfig(
        name = "Default",
        primaryColor = 0xFF00FFFF,
        secondaryColor = 0xFFFF00FF,
        backgroundColor = 0xFF0A0A0F,
        surfaceColor = 0xFF1A1A2E,
        textColor = 0xFFE0E0E0
    )

    var accountMenuExpanded by remember { mutableStateOf(false) }
    var showSwitchDialog by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.currentAccount) {
        if (uiState.currentAccount != null) {
            viewModel.startAutoSync(30_000L)
        }
    }

    DisposableEffect(Unit) {
        onDispose { viewModel.stopAutoSync() }
    }

    val primaryColor = Color(theme.primaryColor)
    val textColorStyle = Color(theme.textColor)
    val accentColor = Color(theme.accentColor)

    Box(modifier = Modifier.fillMaxSize()) {
        if (theme.useBackgroundImage && theme.backgroundResId != null) {
            val resId = backgroundResMap[theme.backgroundResId]
            if (resId != null) {
                Image(
                    painter = painterResource(id = resId),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            Spacer(modifier = Modifier.height((theme.chainLabelYFraction * 1440f).coerceAtLeast(0f).dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = uiState.currentNetwork.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (uiState.currentNetwork == com.wyltek.wallet.core.model.NetworkType.MAINNET)
                        NervosGreen else textColorStyle.copy(alpha = 0.7f)
                )
                Row {
                    Box {
                        IconButton(onClick = { accountMenuExpanded = true }) {
                            Icon(
                                imageVector = Icons.Default.AccountCircle,
                                contentDescription = "Account",
                                tint = textColorStyle
                            )
                        }
                        AccountDropdownMenu(
                            expanded = accountMenuExpanded,
                            onDismiss = { accountMenuExpanded = false },
                            accounts = uiState.accounts,
                            currentAccount = uiState.currentAccount,
                            onSwitch = { showSwitchDialog = true },
                            onCreate = {
                                accountMenuExpanded = false
                                onCreateWallet()
                            },
                            onImport = {
                                accountMenuExpanded = false
                                onImportWallet()
                            },
                            onExport = {
                                accountMenuExpanded = false
                                showExportDialog = true
                            },
                            onPair = {
                                accountMenuExpanded = false
                                viewModel.pairAccount()
                            },
                            accentColor = accentColor,
                            textColor = textColorStyle
                        )
                    }
                    IconButton(onClick = onSettings) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Settings",
                            tint = textColorStyle
                        )
                    }
                }
            }

            if (theme.useBackgroundImage && theme.panelZones.isNotEmpty()) {
                ThemedPanelLayout(
                    theme = theme,
                    uiState = uiState,
                    onSend = onSend,
                    onReceive = onReceive,
                    onTransfer = onInternalTransfer,
                    onTransactionHistory = onTransactionHistory,
                    onDao = onDao,
                    onClearError = { viewModel.clearError() }
                )
            } else {
                DefaultPanelLayout(
                    uiState = uiState,
                    onSend = onSend,
                    onReceive = onReceive,
                    onTransfer = onInternalTransfer,
                    onTransactionHistory = onTransactionHistory,
                    onDao = onDao,
                    onClearError = { viewModel.clearError() },
                    primaryColor = primaryColor,
                    textColorStyle = textColorStyle,
                    accentColor = accentColor
                )
            }
        }
    }

    if (showSwitchDialog) {
        SwitchAccountDialog(
            accounts = uiState.accounts,
            currentAccount = uiState.currentAccount,
            onSelect = { account ->
                viewModel.selectAccount(account)
                showSwitchDialog = false
            },
            onDismiss = { showSwitchDialog = false },
            accentColor = accentColor,
            textColor = textColorStyle
        )
    }

    if (showExportDialog) {
        ExportAccountDialog(
            account = uiState.currentAccount,
            loadSeed = { viewModel.exportCurrentAccount() },
            onDismiss = { showExportDialog = false },
            textColor = textColorStyle
        )
    }
}

@Composable
private fun ThemedPanelLayout(
    theme: ThemeConfig,
    uiState: WalletUiState,
    onSend: () -> Unit,
    onReceive: () -> Unit,
    onTransfer: () -> Unit,
    onTransactionHistory: () -> Unit,
    onDao: () -> Unit,
    onClearError: () -> Unit
) {
    val textColor = Color(theme.textColor)
    val accentColor = Color(theme.accentColor)
    val surfaceColor = Color(theme.surfaceColor)
    val cornerRadius = theme.panelCornerRadius.dp

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize()
    ) {
        val screenW = maxWidth.value
        val screenH = maxHeight.value

        val imgW = 720f
        val imgH = 1440f
        val imgAspect = imgW / imgH
        val screenAspect = screenW / screenH

        // ContentScale.Crop: image fills screen, excess is cropped from center
        // Calculate what fraction of the image is visible and the offset
        val visibleFracX: Float
        val visibleFracY: Float
        val offsetFracX: Float
        val offsetFracY: Float

        if (screenAspect > imgAspect) {
            // Screen wider than image -> crop top/bottom, full width visible
            visibleFracX = 1f
            visibleFracY = imgAspect / screenAspect
            offsetFracX = 0f
            offsetFracY = (1f - visibleFracY) / 2f
        } else {
            // Screen taller than image -> crop left/right, full height visible
            visibleFracX = screenAspect / imgAspect
            visibleFracY = 1f
            offsetFracX = (1f - visibleFracX) / 2f
            offsetFracY = 0f
        }

        // Convert image-fraction to screen-fraction accounting for crop
        fun imgToScreenXFrac(f: Float) = (f - offsetFracX) / visibleFracX
        fun imgToScreenYFrac(f: Float) = (f - offsetFracY) / visibleFracY
        fun imgToScreenWFrac(f: Float) = f / visibleFracX
        fun imgToScreenHFrac(f: Float) = f / visibleFracY

        theme.panelZones.forEach { zone ->
            val panelSurface = surfaceColor.copy(alpha = zone.panelOpacity)
            val zoneX = (imgToScreenXFrac(zone.xFraction) * screenW).dp
            val zoneY = (imgToScreenYFrac(zone.yFraction) * screenH).dp
            val zoneW = (imgToScreenWFrac(zone.widthFraction) * screenW).dp
            val zoneH = (imgToScreenHFrac(zone.heightFraction) * screenH).dp

            when (zone.label) {
                "balance" -> {
                    Box(
                        modifier = Modifier
                            .offset(x = zoneX, y = zoneY)
                            .width(zoneW)
                            .height(zoneH)
                    ) {
                        Surface(
                            modifier = Modifier.fillMaxSize(),
                            color = panelSurface,
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(cornerRadius)
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.Center
                            ) {
Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "Total Balance",
                                            style = MaterialTheme.typography.labelLarge,
                                            color = textColor.copy(alpha = zone.textOpacity)
                                        )
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                            IconButton(onClick = onDao, modifier = Modifier.size(28.dp)) {
                                                Icon(
                                                    imageVector = Icons.Default.AccountBalance,
                                                    contentDescription = "DAO",
                                                    tint = accentColor,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                            if (uiState.isSyncing) {
                                                CircularProgressIndicator(
                                                    modifier = Modifier.size(16.dp),
                                                    strokeWidth = 2.dp,
                                                    color = accentColor
                                                )
                                            }
                                        }
                                    }
                                Text(
                                    text = uiState.balance,
                                    style = MaterialTheme.typography.headlineMedium,
                                    color = textColor.copy(alpha = zone.textOpacity),
                                    fontWeight = FontWeight.Bold
                                )
                                uiState.currentAccount?.let { account ->
                                    Text(
                                        text = "${account.name} · ${account.type.name}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = textColor.copy(alpha = 0.7f)
                                    )
                                }
                            }
                        }
                    }
                }
                "send" -> {
                    Box(
                        modifier = Modifier
                            .offset(x = zoneX, y = zoneY)
                            .width(zoneW)
                            .height(zoneH)
                    ) {
                        Surface(
                            modifier = Modifier.fillMaxSize(),
                            color = panelSurface,
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(cornerRadius),
                            onClick = onSend
                        ) {
                            Column(
                                modifier = Modifier.padding(8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Send,
                                    contentDescription = "Send",
                                    tint = accentColor,
                                    modifier = Modifier.size(20.dp)
                                )
                                Text(
                                    text = "Send",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = textColor
                                )
                            }
                        }
                    }
                }
                "receive" -> {
                    Box(
                        modifier = Modifier
                            .offset(x = zoneX, y = zoneY)
                            .width(zoneW)
                            .height(zoneH)
                    ) {
                        Surface(
                            modifier = Modifier.fillMaxSize(),
                            color = panelSurface,
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(cornerRadius),
                            onClick = onReceive
                        ) {
                            Column(
                                modifier = Modifier.padding(8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.QrCode,
                                    contentDescription = "Receive",
                                    tint = accentColor,
                                    modifier = Modifier.size(20.dp)
                                )
                                Text(
                                    text = "Receive",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = textColor
                                )
                            }
                        }
                    }
                }
                "transfer" -> {
                    Box(
                        modifier = Modifier
                            .offset(x = zoneX, y = zoneY)
                            .width(zoneW)
                            .height(zoneH)
                    ) {
                        Surface(
                            modifier = Modifier.fillMaxSize(),
                            color = panelSurface,
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(cornerRadius),
                            onClick = onTransfer
                        ) {
                            Column(
                                modifier = Modifier.padding(8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.SwapHoriz,
                                    contentDescription = "Transfer",
                                    tint = accentColor,
                                    modifier = Modifier.size(20.dp)
                                )
                                Text(
                                    text = "Transfer",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = textColor
                                )
                            }
                        }
                    }
                }
                "history" -> {
                    Box(
                        modifier = Modifier
                            .offset(x = zoneX, y = zoneY)
                            .width(zoneW)
                            .height(zoneH)
                    ) {
                        if (uiState.currentAccount != null) {
                            OutlinedButton(
                                onClick = onTransactionHistory,
                                modifier = Modifier.fillMaxSize(),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = accentColor)
                            ) {
                                Icon(Icons.Default.History, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Transaction History", color = textColor)
                            }
                        }
                    }
                }
            }
        }

        if (uiState.error != null) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .align(Alignment.BottomCenter),
                color = ErrorRed.copy(alpha = 0.15f),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = ErrorRed
                    )
                    Text(
                        text = uiState.error ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = ErrorRed,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onClearError) {
                        Icon(Icons.Default.Close, contentDescription = "Dismiss", tint = ErrorRed)
                    }
                }
            }
        }
    }
}

@Composable
private fun DefaultPanelLayout(
    uiState: WalletUiState,
    onSend: () -> Unit,
    onReceive: () -> Unit,
    onTransfer: () -> Unit,
    onTransactionHistory: () -> Unit,
    onDao: () -> Unit,
    onClearError: () -> Unit,
    primaryColor: Color,
    textColorStyle: Color,
    accentColor: Color
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = CardBackground)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Total Balance",
                        style = MaterialTheme.typography.labelLarge,
                        color = TextSecondary
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onDao, modifier = Modifier.size(28.dp)) {
                            Icon(
                                imageVector = Icons.Default.AccountBalance,
                                contentDescription = "DAO",
                                tint = NervosGreen,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        if (uiState.isSyncing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = primaryColor
                            )
                        }
                    }
                }
                Text(
                    text = uiState.balance,
                    style = MaterialTheme.typography.headlineMedium,
                    color = textColorStyle,
                    fontWeight = FontWeight.Bold
                )
                uiState.currentAccount?.let { account ->
                    Text(
                        text = "${account.name} · ${account.type.name}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                } ?: run {
                    Text(
                        text = "No wallets yet",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                }
            }
        }

        if (uiState.currentAccount != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ActionButton(
                    modifier = Modifier.weight(1f),
                    icon = Icons.AutoMirrored.Filled.Send,
                    label = "Send",
                    onClick = onSend
                )
                ActionButton(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.QrCode,
                    label = "Receive",
                    onClick = onReceive
                )
                ActionButton(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.SwapHoriz,
                    label = "Transfer",
                    onClick = onTransfer
                )
            }

            OutlinedButton(
                onClick = onTransactionHistory,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = primaryColor)
            ) {
                Icon(Icons.Default.History, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Transaction History")
            }
        }

        if (uiState.error != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = ErrorRed.copy(alpha = 0.1f))
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = ErrorRed
                    )
                    Text(
                        text = uiState.error ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = ErrorRed,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onClearError) {
                        Icon(Icons.Default.Close, contentDescription = "Dismiss", tint = ErrorRed)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))
    }
}

@Composable
private fun AccountDropdownMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    accounts: List<WalletAccount>,
    currentAccount: WalletAccount?,
    onSwitch: () -> Unit,
    onCreate: () -> Unit,
    onImport: () -> Unit,
    onExport: () -> Unit,
    onPair: () -> Unit,
    accentColor: Color,
    textColor: Color
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        containerColor = DarkSurfaceVariant
    ) {
        if (accounts.size > 1) {
            DropdownMenuItem(
                text = { Text("Switch Account", color = textColor) },
                leadingIcon = {
                    Icon(Icons.Default.SwitchAccount, contentDescription = null, tint = accentColor)
                },
                onClick = onSwitch
            )
        }
        DropdownMenuItem(
            text = { Text("Create Account", color = textColor) },
            leadingIcon = {
                Icon(Icons.Default.AddCircle, contentDescription = null, tint = accentColor)
            },
            onClick = onCreate
        )
        DropdownMenuItem(
            text = { Text("Import Account", color = textColor) },
            leadingIcon = {
                Icon(Icons.Default.FileDownload, contentDescription = null, tint = accentColor)
            },
            onClick = onImport
        )
        DropdownMenuItem(
            text = { Text("Export Account", color = textColor) },
            leadingIcon = {
                Icon(Icons.Default.Upload, contentDescription = null, tint = accentColor)
            },
            onClick = onExport
        )
        DropdownMenuItem(
            text = { Text("Pair Account", color = textColor) },
            leadingIcon = {
                Icon(Icons.Default.Phonelink, contentDescription = null, tint = accentColor)
            },
            onClick = onPair
        )
    }
}

@Composable
private fun SwitchAccountDialog(
    accounts: List<WalletAccount>,
    currentAccount: WalletAccount?,
    onSelect: (WalletAccount) -> Unit,
    onDismiss: () -> Unit,
    accentColor: Color,
    textColor: Color
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DarkSurface,
        title = { Text("Switch Account", color = textColor) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                accounts.forEach { account ->
                    val isSelected = account.id == currentAccount?.id
                    Card(
                        onClick = { onSelect(account) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected) accentColor.copy(alpha = 0.1f) else CardBackground
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = account.name,
                                    color = if (isSelected) accentColor else textColor,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = account.type.name,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary
                                )
                            }
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = "Selected",
                                    tint = accentColor
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextSecondary)
            }
        }
    )
}

@Composable
private fun ExportAccountDialog(
    account: WalletAccount?,
    loadSeed: () -> String?,
    onDismiss: () -> Unit,
    textColor: Color
) {
    // Seed is materialized only after BiometricPrompt success, and dropped
    // when the dialog leaves composition.
    var seedHex by remember { mutableStateOf<String?>(null) }
    var authError by remember { mutableStateOf<String?>(null) }
    DisposableEffect(Unit) {
        onDispose {
            seedHex = null
            authError = null
        }
    }

    val biometric = rememberBiometricAuth()

    val revealAfterAuth: () -> Unit = {
        authError = null
        biometric.authenticate(
            title = "Confirm export",
            subtitle = "Authenticate to reveal your seed phrase",
            description = "Anyone with this phrase can drain your account."
        ) { result ->
            when (result) {
                is BiometricResult.Success -> { seedHex = loadSeed() }
                is BiometricResult.Cancelled -> { /* silent: user backed out */ }
                is BiometricResult.Error -> { authError = result.message }
                is BiometricResult.Unavailable -> {
                    authError = "Set up a device PIN, password, or biometric to enable export."
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DarkSurface,
        title = { Text("Export Account", color = textColor) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (account == null) {
                    Text("No account selected.", color = TextSecondary)
                } else {
                    Text(
                        text = "Account: ${account.name}",
                        color = textColor,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "Address: ${account.addresses.firstOrNull()?.bech32m ?: "N/A"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    val revealed = seedHex
                    if (revealed == null) {
                        Text(
                            text = "Revealing your seed phrase exposes full control of this account. Anyone with the phrase can drain it.",
                            color = ErrorRed,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Only proceed somewhere private and never share or screenshot the result.",
                            color = TextSecondary,
                            style = MaterialTheme.typography.bodySmall
                        )
                        authError?.let {
                            Text(
                                text = it,
                                color = ErrorRed,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Button(
                            onClick = revealAfterAuth,
                            colors = ButtonDefaults.buttonColors(containerColor = ErrorRed),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Reveal Seed Phrase", color = DarkBackground, fontWeight = FontWeight.Bold)
                        }
                    } else {
                        Text(
                            text = "Seed Hex (keep secret!):",
                            color = ErrorRed,
                            fontWeight = FontWeight.Bold
                        )
                        Card(
                            colors = CardDefaults.cardColors(containerColor = DarkBackground),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = revealed,
                                modifier = Modifier.padding(12.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = textColor
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = TextSecondary)
            }
        }
    )
}

@Composable
private fun ActionButton(
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Card(
        modifier = modifier,
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = NeonCyan,
                modifier = Modifier.size(24.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = TextPrimary
            )
        }
    }
}