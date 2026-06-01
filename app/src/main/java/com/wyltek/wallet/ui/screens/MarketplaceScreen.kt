package com.wyltek.wallet.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wyltek.wallet.core.assets.AssetInfo
import com.wyltek.wallet.core.assets.Listing
import com.wyltek.wallet.core.assets.ListingStatus
import com.wyltek.wallet.data.WalletViewModel
import com.wyltek.wallet.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarketplaceScreen(viewModel: WalletViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    var selectedTab by remember { mutableStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Marketplace") },
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
                    text = { Text("All Listings") }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("My Listings") }
                )
                Tab(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    text = { Text("List Asset") }
                )
            }

            when (selectedTab) {
                0 -> AllListingsTab(viewModel)
                1 -> MyListingsTab(viewModel)
                2 -> ListAssetTab(viewModel)
            }
        }
    }
}

@Composable
private fun AllListingsTab(viewModel: WalletViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    val activeListings = uiState.activeListings

    if (activeListings.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "No active listings",
                style = MaterialTheme.typography.bodyLarge,
                color = CyberGray
            )
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(activeListings) { listing ->
                ListingCard(
                    listing = listing,
                    onBuy = { viewModel.buyAsset(listing.id) },
                    showBuyButton = true
                )
            }
        }
    }
}

@Composable
private fun MyListingsTab(viewModel: WalletViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    val myListings = uiState.myListings

    if (myListings.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "You have no active listings",
                style = MaterialTheme.typography.bodyLarge,
                color = CyberGray
            )
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(myListings) { listing ->
                ListingCard(
                    listing = listing,
                    onCancel = { viewModel.cancelListing(listing.id) },
                    showCancelButton = true
                )
            }
        }
    }
}

@Composable
private fun ListAssetTab(viewModel: WalletViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    val assets = uiState.sporeAssets + uiState.cotaAssets + uiState.ckbfsAssets

    if (assets.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "No assets available to list",
                style = MaterialTheme.typography.bodyLarge,
                color = CyberGray
            )
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(assets) { asset ->
                AssetListCard(
                    asset = asset,
                    onList = { price, royalty, expiry ->
                        viewModel.listAssetForSale(asset, price, royalty, expiry)
                    }
                )
            }
        }
    }
}

@Composable
private fun ListingCard(
    listing: Listing,
    onBuy: (() -> Unit)? = null,
    onCancel: (() -> Unit)? = null,
    showBuyButton: Boolean = false,
    showCancelButton: Boolean = false
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DarkSurface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = listing.asset.name ?: "Unknown Asset",
                    style = MaterialTheme.typography.titleMedium,
                    color = CyberText
                )

                Surface(
                    color = when (listing.status) {
                        ListingStatus.ACTIVE -> CyberBlue
                        ListingStatus.SOLD -> NeonGreen
                        ListingStatus.CANCELLED -> NeonPink
                        ListingStatus.EXPIRED -> CyberYellow
                    },
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = listing.status.name,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = DarkBackground
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "Price",
                        style = MaterialTheme.typography.labelSmall,
                        color = CyberGray
                    )
                    Text(
                        text = "${listing.price} CKB",
                        style = MaterialTheme.typography.bodyLarge,
                        color = CyberBlue
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "Royalty",
                        style = MaterialTheme.typography.labelSmall,
                        color = CyberGray
                    )
                    Text(
                        text = "${listing.royaltyPercent}%",
                        style = MaterialTheme.typography.bodyLarge,
                        color = CyberYellow
                    )
                }
            }

            if (listing.expiryBlock != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Expires at block #${listing.expiryBlock}",
                    style = MaterialTheme.typography.labelSmall,
                    color = CyberGray
                )
            }

            if (showBuyButton || showCancelButton) {
                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (showBuyButton && onBuy != null) {
                        Button(
                            onClick = onBuy,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = NeonGreen
                            ),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = "Buy",
                                color = DarkBackground,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    if (showCancelButton && onCancel != null) {
                        OutlinedButton(
                            onClick = onCancel,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = NeonPink
                            ),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Cancel Listing")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AssetListCard(
    asset: AssetInfo,
    onList: (price: ULong, royalty: UInt, expiry: ULong?) -> Unit
) {
    var showListDialog by remember { mutableStateOf(false) }

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
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = asset.name ?: "Unknown Asset",
                    style = MaterialTheme.typography.titleMedium,
                    color = CyberText
                )

                Spacer(modifier = Modifier.height(4.dp))

                Surface(
                    color = when (asset.type) {
                        com.wyltek.wallet.core.assets.AssetType.SPORE -> NeonPink
                        com.wyltek.wallet.core.assets.AssetType.COTA -> CyberYellow
                        com.wyltek.wallet.core.assets.AssetType.CKBFS -> NeonGreen
                        else -> CyberGray
                    },
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = asset.type.name,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = DarkBackground
                    )
                }
            }

            Button(
                onClick = { showListDialog = true },
                colors = ButtonDefaults.buttonColors(
                    containerColor = CyberBlue
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = "List",
                    color = DarkBackground,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }

    if (showListDialog) {
        ListAssetDialog(
            asset = asset,
            onDismiss = { showListDialog = false },
            onConfirm = { price, royalty, expiry ->
                onList(price, royalty, expiry)
                showListDialog = false
            }
        )
    }
}

@Composable
private fun ListAssetDialog(
    asset: AssetInfo,
    onDismiss: () -> Unit,
    onConfirm: (price: ULong, royalty: UInt, expiry: ULong?) -> Unit
) {
    var price by remember { mutableStateOf("") }
    var royalty by remember { mutableStateOf("5") }
    var expiry by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("List Asset for Sale") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = asset.name ?: "Unknown Asset",
                    style = MaterialTheme.typography.titleMedium,
                    color = CyberBlue
                )

                OutlinedTextField(
                    value = price,
                    onValueChange = { price = it },
                    label = { Text("Price (CKB)") },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CyberBlue,
                        unfocusedBorderColor = CyberGray
                    )
                )

                OutlinedTextField(
                    value = royalty,
                    onValueChange = { royalty = it },
                    label = { Text("Royalty %") },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CyberBlue,
                        unfocusedBorderColor = CyberGray
                    )
                )

                OutlinedTextField(
                    value = expiry,
                    onValueChange = { expiry = it },
                    label = { Text("Expiry Block (optional)") },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                    ),
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
                    val priceUlong = price.toULongOrNull() ?: 0uL
                    val royaltyUint = royalty.toUIntOrNull() ?: 0u
                    val expiryUlong = expiry.toULongOrNull()
                    onConfirm(priceUlong, royaltyUint, expiryUlong)
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = NeonGreen
                )
            ) {
                Text("List", color = DarkBackground)
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
