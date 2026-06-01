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
    data object Send : Screen("send", "Send")
    data object Receive : Screen("receive", "Receive")
    data object InternalTransfer : Screen("internal-transfer", "Transfer")
    data object WalletCreate : Screen("wallet-create", "Create Wallet")
    data object WalletImport : Screen("wallet-import", "Import Wallet")
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
                    onCreateWallet = { navController.navigate(Screen.WalletCreate.route) },
                    onSettings = { navController.navigate(Screen.Settings.route) },
                    viewModel = viewModel
                )
            }
            composable(Screen.Assets.route) {
                AssetsScreen(viewModel = viewModel)
            }
            composable(Screen.Marketplace.route) {
                MarketplaceScreen(viewModel = viewModel)
            }
            composable(Screen.Messages.route) {
                MessagesScreen()
            }
            composable(Screen.Settings.route) {
                SettingsScreen(viewModel = viewModel)
            }
            composable(Screen.Send.route) {
                SendScreen(
                    onBack = { navController.popBackStack() },
                    viewModel = viewModel
                )
            }
            composable(Screen.Receive.route) {
                ReceiveScreen(
                    onBack = { navController.popBackStack() },
                    viewModel = viewModel
                )
            }
            composable(Screen.InternalTransfer.route) {
                InternalTransferScreen(onBack = { navController.popBackStack() })
            }
            composable(Screen.WalletCreate.route) {
                WalletCreateScreen(
                    onBack = { navController.popBackStack() },
                    viewModel = viewModel
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
