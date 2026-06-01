package com.wyltek.wallet.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wyltek.wallet.core.skin.ThemeConfig
import com.wyltek.wallet.core.skin.ThemePresets
import com.wyltek.wallet.data.WalletViewModel
import com.wyltek.wallet.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkinsScreen(viewModel: WalletViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    var selectedTab by remember { mutableStateOf(0) }
    var showExportDialog by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }
    var showCustomizeDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Skins") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = DarkSurface
                ),
                actions = {
                    IconButton(onClick = { showImportDialog = true }) {
                        Icon(
                            imageVector = Icons.Default.FileDownload,
                            contentDescription = "Import Theme"
                        )
                    }
                    IconButton(onClick = { showExportDialog = true }) {
                        Icon(
                            imageVector = Icons.Default.FileUpload,
                            contentDescription = "Export Theme"
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
                    text = { Text("Presets") }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Custom") }
                )
                Tab(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    text = { Text("Panels") }
                )
            }

            when (selectedTab) {
                0 -> PresetsTab(
                    viewModel = viewModel,
                    onCustomize = { showCustomizeDialog = true }
                )
                1 -> CustomTab(viewModel = viewModel)
                2 -> PanelsTab(viewModel = viewModel)
            }
        }
    }

    if (showExportDialog) {
        ExportThemeDialog(
            viewModel = viewModel,
            onDismiss = { showExportDialog = false }
        )
    }

    if (showImportDialog) {
        ImportThemeDialog(
            viewModel = viewModel,
            onDismiss = { showImportDialog = false }
        )
    }

    if (showCustomizeDialog) {
        CustomizeThemeDialog(
            viewModel = viewModel,
            onDismiss = { showCustomizeDialog = false }
        )
    }
}

@Composable
private fun PresetsTab(
    viewModel: WalletViewModel,
    onCustomize: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val themes = ThemePresets.all

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(themes) { theme ->
            ThemeCard(
                theme = theme,
                isSelected = uiState.currentTheme?.name == theme.name,
                onSelect = { viewModel.setTheme(theme) },
                onCustomize = onCustomize
            )
        }
    }
}

@Composable
private fun CustomTab(viewModel: WalletViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    val customThemes = uiState.customThemes

    if (customThemes.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Default.Palette,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = CyberGray
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "No custom themes yet",
                    style = MaterialTheme.typography.bodyLarge,
                    color = CyberGray
                )
                Text(
                    text = "Customize a preset theme to create your own",
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
            items(customThemes) { theme ->
                ThemeCard(
                    theme = theme,
                    isSelected = uiState.currentTheme?.name == theme.name,
                    onSelect = { viewModel.setTheme(theme) },
                    onDelete = { viewModel.removeCustomTheme(theme.name) }
                )
            }
        }
    }
}

@Composable
private fun PanelsTab(viewModel: WalletViewModel) {
    val panels = listOf("Home", "Assets", "Marketplace", "Messages", "Settings")

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(panels) { panelName ->
            PanelCard(
                panelName = panelName,
                viewModel = viewModel
            )
        }
    }
}

@Composable
private fun ThemeCard(
    theme: ThemeConfig,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onCustomize: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .then(
                if (isSelected) Modifier.border(2.dp, CyberBlue, RoundedCornerShape(12.dp))
                else Modifier
            ),
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
                    text = theme.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = CyberText,
                    fontWeight = FontWeight.Bold
                )

                if (isSelected) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = "Selected",
                        tint = CyberBlue
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Color preview
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(Color(theme.primaryColor))
                )
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(Color(theme.secondaryColor))
                )
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(Color(theme.backgroundColor))
                )
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(Color(theme.surfaceColor))
                )
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(Color(theme.textColor))
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Mini preview
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(80.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(theme.backgroundColor))
            ) {
                Column(
                    modifier = Modifier.padding(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(24.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(theme.surfaceColor))
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(32.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color(theme.primaryColor).copy(alpha = 0.3f))
                        )
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(32.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color(theme.secondaryColor).copy(alpha = 0.3f))
                        )
                    }
                }
            }

            if (onCustomize != null || onDelete != null) {
                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (onCustomize != null) {
                        OutlinedButton(
                            onClick = onCustomize,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = CyberBlue
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Customize")
                        }
                    }

                    if (onDelete != null) {
                        OutlinedButton(
                            onClick = onDelete,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = NeonPink
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Delete")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PanelCard(
    panelName: String,
    viewModel: WalletViewModel
) {
    var showImagePicker by remember { mutableStateOf(false) }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            viewModel.updatePanelBackground(panelName, it.toString())
        }
    }

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
                    text = panelName,
                    style = MaterialTheme.typography.titleMedium,
                    color = CyberText
                )
                Text(
                    text = "Background image",
                    style = MaterialTheme.typography.bodySmall,
                    color = CyberGray
                )
            }

            Button(
                onClick = { imagePickerLauncher.launch("image/*") },
                colors = ButtonDefaults.buttonColors(
                    containerColor = CyberBlue
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Image,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("Choose")
            }
        }
    }
}

@Composable
private fun ExportThemeDialog(
    viewModel: WalletViewModel,
    onDismiss: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val themeJson = uiState.currentTheme?.let { viewModel.exportThemeJson(it) } ?: "{}"

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Export Theme") },
        text = {
            Column {
                Text(
                    text = "Copy this JSON to share your theme:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = CyberText
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = themeJson,
                    onValueChange = {},
                    modifier = Modifier.fillMaxWidth(),
                    readOnly = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CyberBlue,
                        unfocusedBorderColor = CyberGray
                    )
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(
                    containerColor = NeonGreen
                )
            ) {
                Text("Done", color = DarkBackground)
            }
        }
    )
}

@Composable
private fun ImportThemeDialog(
    viewModel: WalletViewModel,
    onDismiss: () -> Unit
) {
    var jsonInput by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import Theme") },
        text = {
            Column {
                Text(
                    text = "Paste a theme JSON to import:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = CyberText
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = jsonInput,
                    onValueChange = { jsonInput = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Paste JSON here...", color = CyberGray) },
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
                    if (jsonInput.isNotBlank()) {
                        viewModel.importTheme(jsonInput)
                        onDismiss()
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = NeonGreen
                )
            ) {
                Text("Import", color = DarkBackground)
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
private fun CustomizeThemeDialog(
    viewModel: WalletViewModel,
    onDismiss: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val currentTheme = uiState.currentTheme ?: ThemePresets.cyberpunk

    var primaryColor by remember { mutableStateOf(Color(currentTheme.primaryColor)) }
    var secondaryColor by remember { mutableStateOf(Color(currentTheme.secondaryColor)) }
    var backgroundColor by remember { mutableStateOf(Color(currentTheme.backgroundColor)) }
    var surfaceColor by remember { mutableStateOf(Color(currentTheme.surfaceColor)) }
    var textColor by remember { mutableStateOf(Color(currentTheme.textColor)) }
    var themeName by remember { mutableStateOf(currentTheme.name + " Custom") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Customize Theme") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = themeName,
                    onValueChange = { themeName = it },
                    label = { Text("Theme Name") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CyberBlue,
                        unfocusedBorderColor = CyberGray
                    )
                )

                ColorPickerRow("Primary", primaryColor) { primaryColor = it }
                ColorPickerRow("Secondary", secondaryColor) { secondaryColor = it }
                ColorPickerRow("Background", backgroundColor) { backgroundColor = it }
                ColorPickerRow("Surface", surfaceColor) { surfaceColor = it }
                ColorPickerRow("Text", textColor) { textColor = it }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val customTheme = ThemeConfig(
                        name = themeName,
                        primaryColor = primaryColor.toArgb().toLong(),
                        secondaryColor = secondaryColor.toArgb().toLong(),
                        backgroundColor = backgroundColor.toArgb().toLong(),
                        surfaceColor = surfaceColor.toArgb().toLong(),
                        textColor = textColor.toArgb().toLong(),
                        isDark = true
                    )
                    viewModel.saveCustomTheme(customTheme)
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = NeonGreen
                )
            ) {
                Text("Save", color = DarkBackground)
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
private fun ColorPickerRow(
    label: String,
    color: Color,
    onColorChange: (Color) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = CyberText
        )

        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(color)
                .border(2.dp, CyberGray, CircleShape)
                .clickable {
                    // Simple color cycling for demo
                    val colors = listOf(
                        Color.Red, Color.Green, Color.Blue, Color.Yellow,
                        Color.Cyan, Color.Magenta, CyberBlue, NeonPink
                    )
                    val nextIndex = (colors.indexOf(color) + 1) % colors.size
                    onColorChange(colors[nextIndex])
                }
        )
    }
}
