package com.wyltek.wallet.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wyltek.wallet.core.assets.AssetType
import com.wyltek.wallet.data.WalletViewModel
import com.wyltek.wallet.ui.theme.*

@Composable
fun AssetsScreen(viewModel: WalletViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("All", "Spore", "CoTA", "CKBFS")

    LaunchedEffect(uiState.currentAccount) {
        if (uiState.currentAccount != null) {
            viewModel.refreshAssets()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = DarkSurface,
            contentColor = NeonCyan
        ) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = { Text(title) }
                )
            }
        }

        val filteredAssets = when (selectedTab) {
            1 -> uiState.sporeAssets
            2 -> uiState.cotaAssets
            3 -> uiState.ckbfsAssets
            else -> uiState.allAssets
        }

        if (filteredAssets.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Image,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = TextSecondary
                    )
                    Text(
                        text = "No assets found",
                        style = MaterialTheme.typography.bodyLarge,
                        color = TextSecondary
                    )
                    Text(
                        text = if (uiState.currentAccount != null) {
                            "No ${tabs[selectedTab].lowercase()} assets in this wallet"
                        } else {
                            "Create a wallet to view your assets"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filteredAssets) { asset ->
                    AssetCard(
                        asset = asset,
                        onClick = { viewModel.selectAsset(asset) }
                    )
                }
            }
        }
    }
}

@Composable
private fun AssetCard(
    asset: com.wyltek.wallet.core.assets.AssetInfo,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = when (asset.type) {
                    AssetType.SPORE -> Icons.Default.Diamond
                    AssetType.COTA -> Icons.Default.Token
                    AssetType.CKBFS -> Icons.Default.Description
                    AssetType.UNKNOWN -> Icons.Default.HelpOutline
                },
                contentDescription = null,
                tint = when (asset.type) {
                    AssetType.SPORE -> NeonMagenta
                    AssetType.COTA -> NeonCyan
                    AssetType.CKBFS -> NervosGreen
                    AssetType.UNKNOWN -> TextSecondary
                },
                modifier = Modifier.size(32.dp)
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = asset.name ?: "${asset.type.name} Asset",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (asset.contentType != null) {
                    Text(
                        text = asset.contentType ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = "${asset.capacity / 100_000_000u} CKB",
                    style = MaterialTheme.typography.bodySmall,
                    color = NeonCyan
                )
            }

            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = TextSecondary
            )
        }
    }
}
