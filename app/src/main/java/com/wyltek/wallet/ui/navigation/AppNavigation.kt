package com.wyltek.wallet.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Mail
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Store
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.wyltek.wallet.ui.screens.*
import com.wyltek.wallet.data.WalletViewModel

sealed class Screen(val route: String, val label: String) {
    data object Home : Screen("home", "Home")
    data object Assets : Screen("assets", "Assets")
    data object Marketplace : Screen("marketplace", "Market")
    data object Messages : Screen("messages", "Messages")
    data object Settings : Screen("settings", "Settings")
    data object Skins : Screen("skins", "Skins")
    data object PasskeySettings : Screen("passkey-settings", "Passkeys")
    data object WatchOnly : Screen("watch-only", "Watch-Only")
    data object Security : Screen("security", "Security")
    data object RpcHealth : Screen("rpc-health", "RPC Health")
    data object Send : Screen("send", "Send")
    data object Receive : Screen("receive", "Receive")
    data object QrScanner : Screen("qr-scanner", "Scan QR")
    data object TransactionHistory : Screen("transaction-history", "History")
    data object TransactionDetail : Screen("transaction-detail/{txHash}", "Detail")
    data object SendToken : Screen("send-token", "Send Token")
    data object InternalTransfer : Screen("internal-transfer", "Transfer")
    data object WalletCreate : Screen("wallet-create", "Create Wallet")
    data object WalletImport : Screen("wallet-import", "Import Wallet")
    data object MnemonicVerify : Screen("mnemonic-verify", "Verify Mnemonic")
    data object Dao : Screen("dao", "DAO")
}

private val bottomBarScreens = listOf(
    Screen.Home,
    Screen.Assets,
    Screen.Marketplace,
    Screen.Messages,
    Screen.Settings
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    val viewModel: WalletViewModel = viewModel()

    val showBottomBar = bottomBarScreens.any { screen ->
        currentDestination?.hierarchy?.any { it.route == screen.route } == true
    }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    bottomBarScreens.forEach { screen ->
                        NavigationBarItem(
                            icon = {
                                Icon(
                                    imageVector = when (screen) {
                                        Screen.Home -> Icons.Default.Home
                                        Screen.Assets -> Icons.Default.Image
                                        Screen.Marketplace -> Icons.Default.Store
                                        Screen.Messages -> Icons.Default.Mail
                                        Screen.Settings -> Icons.Default.Settings
                                        else -> Icons.Default.Home
                                    },
                                    contentDescription = screen.label
                                )
                            },
                            label = { Text(screen.label) },
                            selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true,
                            onClick = {
                                navController.navigate(screen.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Home.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Home.route) {
                HomeScreen(
                    onSend = { navController.navigate(Screen.Send.route) },
                    onReceive = { navController.navigate(Screen.Receive.route) },
                    onInternalTransfer = { navController.navigate(Screen.InternalTransfer.route) },
                    onTransactionHistory = { navController.navigate(Screen.TransactionHistory.route) },
                    onCreateWallet = { navController.navigate(Screen.WalletCreate.route) },
                    onImportWallet = { navController.navigate(Screen.WalletImport.route) },
                    onSettings = {
                        navController.navigate(Screen.Settings.route) {
                            popUpTo(Screen.Home.route) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    onDao = { navController.navigate(Screen.Dao.route) },
                    viewModel = viewModel
                )
            }
            composable(Screen.Assets.route) {
                AssetsScreen(
                    onSendToken = { navController.navigate(Screen.SendToken.route) },
                    viewModel = viewModel
                )
            }
            composable(Screen.Marketplace.route) {
                MarketplaceScreen(viewModel = viewModel)
            }
            composable(Screen.Messages.route) {
                MessagesScreen(viewModel = viewModel)
            }
            composable(Screen.Settings.route) {
                SettingsScreen(
                    onSkins = { navController.navigate(Screen.Skins.route) },
                    onPasskeys = { navController.navigate(Screen.PasskeySettings.route) },
                    onWatchOnly = { navController.navigate(Screen.WatchOnly.route) },
                    onSecurity = { navController.navigate(Screen.Security.route) },
                    onRpcHealth = { navController.navigate(Screen.RpcHealth.route) },
                    viewModel = viewModel
                )
            }
            composable(Screen.Skins.route) {
                SkinsScreen(onBack = { navController.popBackStack() }, viewModel = viewModel)
            }
            composable(Screen.PasskeySettings.route) {
                PasskeySettingsScreen(onBack = { navController.popBackStack() }, viewModel = viewModel)
            }
            composable(Screen.WatchOnly.route) {
                WatchOnlyScreen(onBack = { navController.popBackStack() }, viewModel = viewModel)
            }
            composable(Screen.Security.route) {
                SecuritySettingsScreen(onBack = { navController.popBackStack() }, viewModel = viewModel)
            }
            composable(Screen.RpcHealth.route) {
                RpcHealthScreen(onBack = { navController.popBackStack() }, viewModel = viewModel)
            }
            composable(Screen.Send.route) { backStackEntry ->
                val scannedAddress = backStackEntry.savedStateHandle.get<String>("scannedAddress")
                backStackEntry.savedStateHandle.remove<String>("scannedAddress")
                SendScreen(
                    onBack = { navController.popBackStack() },
                    onScanQr = { navController.navigate(Screen.QrScanner.route) },
                    scannedAddress = scannedAddress,
                    viewModel = viewModel
                )
            }
            composable(Screen.Receive.route) {
                ReceiveScreen(
                    onBack = { navController.popBackStack() },
                    viewModel = viewModel
                )
            }
            composable(Screen.QrScanner.route) {
                QrScannerScreen(
                    onBack = { navController.popBackStack() },
                    onScanned = { address ->
                        navController.previousBackStackEntry
                            ?.savedStateHandle
                            ?.set("scannedAddress", address)
                        navController.popBackStack()
                    }
                )
            }
            composable(Screen.TransactionHistory.route) {
                TransactionHistoryScreen(
                    onBack = { navController.popBackStack() },
                    onDetail = { txHash ->
                        navController.navigate("transaction-detail/$txHash")
                    },
                    viewModel = viewModel
                )
            }
            composable(Screen.TransactionDetail.route) { backStackEntry ->
                val txHash = backStackEntry.arguments?.getString("txHash") ?: ""
                TransactionDetailScreen(
                    txHash = txHash,
                    onBack = { navController.popBackStack() },
                    viewModel = viewModel
                )
            }
            composable(Screen.SendToken.route) {
                val token = viewModel.uiState.value.selectedToken
                if (token != null) {
                    SendTokenScreen(
                        token = token,
                        onBack = {
                            viewModel.clearSelectedToken()
                            navController.popBackStack()
                        },
                        viewModel = viewModel
                    )
                } else {
                    navController.popBackStack()
                }
            }
            composable(Screen.InternalTransfer.route) {
                InternalTransferScreen(
                    onBack = { navController.popBackStack() },
                    viewModel = viewModel
                )
            }
            composable(Screen.Dao.route) {
                DaoScreen(
                    onBack = { navController.popBackStack() },
                    viewModel = viewModel
                )
            }
            composable(Screen.WalletCreate.route) {
                WalletCreateScreen(
                    onBack = { navController.popBackStack() },
                    onVerify = {
                        navController.navigate(Screen.MnemonicVerify.route)
                    },
                    viewModel = viewModel
                )
            }
            composable(Screen.MnemonicVerify.route) {
                MnemonicVerifyScreen(
                    viewModel = viewModel,
                    onVerified = {
                        viewModel.confirmWalletCreation()
                        navController.popBackStack(Screen.Home.route, false)
                    },
                    onBack = { navController.popBackStack() }
                )
            }
            composable(Screen.WalletImport.route) {
                WalletImportScreen(
                    onBack = { navController.popBackStack() },
                    viewModel = viewModel
                )
            }
        }
    }
}
