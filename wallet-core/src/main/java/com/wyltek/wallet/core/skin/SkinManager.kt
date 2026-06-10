package com.wyltek.wallet.core.skin

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class PanelBackground(
    val imageUri: String? = null,
    val backgroundColor: Long? = null,
    val blurRadius: Float = 0f,
    val dimOpacity: Float = 0.3f
)

@Serializable
data class PanelZone(
    val label: String,
    val xFraction: Float = 0.05f,
    val yFraction: Float = 0.05f,
    val widthFraction: Float = 0.9f,
    val heightFraction: Float = 0.12f,
    val alignment: String = "center",
    val textOpacity: Float = 0.95f,
    val panelOpacity: Float = 0.0f
)

@Serializable
data class ThemeConfig(
    val name: String,
    val primaryColor: Long,
    val secondaryColor: Long,
    val backgroundColor: Long,
    val surfaceColor: Long,
    val textColor: Long,
    val isDark: Boolean = true,
    val panelBackgrounds: Map<String, PanelBackground> = emptyMap(),
    val backgroundResId: String? = null,
    val panelZones: List<PanelZone> = emptyList(),
    val accentColor: Long = 0xFF00FFFF,
    val panelCornerRadius: Float = 12f,
    val useBackgroundImage: Boolean = false,
    val chainLabelYFraction: Float = 0.014f
)

object ThemePresets {

    val cyberpunk = ThemeConfig(
        name = "Cyberpunk",
        primaryColor = 0xFF00FFFF,
        secondaryColor = 0xFFFF00FF,
        backgroundColor = 0xFF0A0A0F,
        surfaceColor = 0xFF1A1A2E,
        textColor = 0xFFE0E0E0,
        isDark = true
    )

    val neonNervos = ThemeConfig(
        name = "Neon Nervos",
        primaryColor = 0xFF4CAF50,
        secondaryColor = 0xFF81C784,
        backgroundColor = 0xFF1B2A1B,
        surfaceColor = 0xFF2E3D2E,
        textColor = 0xFFE8F5E9,
        isDark = true
    )

    val lightClean = ThemeConfig(
        name = "Light Clean",
        primaryColor = 0xFF1976D2,
        secondaryColor = 0xFF42A5F5,
        backgroundColor = 0xFFFAFAFA,
        surfaceColor = 0xFFFFFFFF,
        textColor = 0xFF212121,
        isDark = false
    )

    val midnightBlue = ThemeConfig(
        name = "Midnight Blue",
        primaryColor = 0xFF3F51B5,
        secondaryColor = 0xFF7986CB,
        backgroundColor = 0xFF0D1B2A,
        surfaceColor = 0xFF1B2838,
        textColor = 0xFFE0E0E0,
        isDark = true
    )

    val sunsetOrange = ThemeConfig(
        name = "Sunset Orange",
        primaryColor = 0xFFFF6B35,
        secondaryColor = 0xFFFF9E80,
        backgroundColor = 0xFF1A0A00,
        surfaceColor = 0xFF2D1A0A,
        textColor = 0xFFFFE0B2,
        isDark = true
    )

    val forestGreen = ThemeConfig(
        name = "Forest Green",
        primaryColor = 0xFF2E7D32,
        secondaryColor = 0xFF66BB6A,
        backgroundColor = 0xFF0A1A0A,
        surfaceColor = 0xFF1A2D1A,
        textColor = 0xFFE8F5E9,
        isDark = true
    )

    val vaultBlackLeather = ThemeConfig(
        name = "Black Leather",
        primaryColor = 0xFFE0C068,
        secondaryColor = 0xFFB89040,
        backgroundColor = 0xFF0A0A0F,
        surfaceColor = 0xFF1A1A1E,
        textColor = 0xFFF0E0C0,
        accentColor = 0xFFE0C068,
        useBackgroundImage = true,
        backgroundResId = "bg_black_leather",
        chainLabelYFraction = 0.111f,
        panelZones = listOf(
            PanelZone(label = "balance", xFraction = 0.069f, yFraction = 0.155f, widthFraction = 0.847f, heightFraction = 0.125f, panelOpacity = 0.5f),
            PanelZone(label = "send", xFraction = 0.069f, yFraction = 0.327f, widthFraction = 0.847f, heightFraction = 0.125f, panelOpacity = 0.5f),
            PanelZone(label = "receive", xFraction = 0.069f, yFraction = 0.505f, widthFraction = 0.847f, heightFraction = 0.125f, panelOpacity = 0.5f),
            PanelZone(label = "transfer", xFraction = 0.069f, yFraction = 0.674f, widthFraction = 0.847f, heightFraction = 0.194f, panelOpacity = 0.5f)
        )
    )

    val vaultBrownLeather1 = ThemeConfig(
        name = "Brown Leather I",
        primaryColor = 0xFFD4A050,
        secondaryColor = 0xFFC08830,
        backgroundColor = 0xFF1A0E04,
        surfaceColor = 0xFF2A1A08,
        textColor = 0xFFF0D8A8,
        accentColor = 0xFFD4A050,
        useBackgroundImage = true,
        backgroundResId = "bg_brown_leather_01",
        chainLabelYFraction = 0.007f,
        panelZones = listOf(
            PanelZone(label = "balance", xFraction = 0.139f, yFraction = 0.257f, widthFraction = 0.806f, heightFraction = 0.0625f, panelOpacity = 0.5f),
            PanelZone(label = "send", xFraction = 0.139f, yFraction = 0.357f, widthFraction = 0.806f, heightFraction = 0.0625f, panelOpacity = 0.5f),
            PanelZone(label = "receive", xFraction = 0.139f, yFraction = 0.45f, widthFraction = 0.806f, heightFraction = 0.0625f, panelOpacity = 0.5f),
            PanelZone(label = "transfer", xFraction = 0.139f, yFraction = 0.542f, widthFraction = 0.806f, heightFraction = 0.0625f, panelOpacity = 0.5f),
            PanelZone(label = "history", xFraction = 0.139f, yFraction = 0.639f, widthFraction = 0.806f, heightFraction = 0.257f, panelOpacity = 0.5f)
        )
    )

    val vaultBrownLeather2 = ThemeConfig(
        name = "Brown Leather II",
        primaryColor = 0xFFCCA060,
        secondaryColor = 0xFFB08840,
        backgroundColor = 0xFF1C0E04,
        surfaceColor = 0xFF2C1808,
        textColor = 0xFFF0D8A8,
        accentColor = 0xFFCCA060,
        useBackgroundImage = true,
        backgroundResId = "bg_brown_leather_02",
        chainLabelYFraction = 0.007f,
        panelZones = listOf(
            PanelZone(label = "balance", xFraction = 0.064f, yFraction = 0.139f, widthFraction = 0.875f, heightFraction = 0.121f, panelOpacity = 0.5f),
            PanelZone(label = "send", xFraction = 0.064f, yFraction = 0.306f, widthFraction = 0.875f, heightFraction = 0.121f, panelOpacity = 0.5f),
            PanelZone(label = "receive", xFraction = 0.064f, yFraction = 0.47f, widthFraction = 0.875f, heightFraction = 0.121f, panelOpacity = 0.5f),
            PanelZone(label = "transfer", xFraction = 0.064f, yFraction = 0.632f, widthFraction = 0.875f, heightFraction = 0.264f, panelOpacity = 0.5f)
        )
    )

    val vaultBrownLeather3 = ThemeConfig(
        name = "Brown Leather III",
        primaryColor = 0xFFD0A858,
        secondaryColor = 0xFFAA8838,
        backgroundColor = 0xFF1A0E04,
        surfaceColor = 0xFF281A08,
        textColor = 0xFFF0D8B0,
        accentColor = 0xFFD0A858,
        useBackgroundImage = true,
        backgroundResId = "bg_brown_leather_03",
        chainLabelYFraction = 0.007f,
        panelZones = listOf(
            PanelZone(label = "balance", xFraction = 0.069f, yFraction = 0.205f, widthFraction = 0.861f, heightFraction = 0.667f, panelOpacity = 0.4f)
        )
    )

    val vaultBrownLeather4 = ThemeConfig(
        name = "Brown Leather IV",
        primaryColor = 0xFFC89848,
        secondaryColor = 0xFFAA7830,
        backgroundColor = 0xFF180C04,
        surfaceColor = 0xFF241608,
        textColor = 0xFFF0D0A0,
        accentColor = 0xFFC89848,
        useBackgroundImage = true,
        backgroundResId = "bg_brown_leather_04",
        chainLabelYFraction = 0.042f,
        panelZones = listOf(
            PanelZone(label = "balance", xFraction = 0.107f, yFraction = 0.264f, widthFraction = 0.778f, heightFraction = 0.111f, panelOpacity = 0.5f),
            PanelZone(label = "send", xFraction = 0.107f, yFraction = 0.421f, widthFraction = 0.778f, heightFraction = 0.111f, panelOpacity = 0.5f),
            PanelZone(label = "receive", xFraction = 0.107f, yFraction = 0.578f, widthFraction = 0.778f, heightFraction = 0.111f, panelOpacity = 0.5f),
            PanelZone(label = "transfer", xFraction = 0.107f, yFraction = 0.741f, widthFraction = 0.778f, heightFraction = 0.111f, panelOpacity = 0.5f)
        )
    )

    val vaultCircuitBoard = ThemeConfig(
        name = "Circuit Board",
        primaryColor = 0xFF00E080,
        secondaryColor = 0xFF00C060,
        backgroundColor = 0xFF0A1A10,
        surfaceColor = 0xFF14281A,
        textColor = 0xFFC0F0D0,
        accentColor = 0xFF00E080,
        useBackgroundImage = true,
        backgroundResId = "bg_circuit_board",
        chainLabelYFraction = 0.007f,
        panelZones = listOf(
            PanelZone(label = "balance", xFraction = 0.107f, yFraction = 0.264f, widthFraction = 0.778f, heightFraction = 0.111f, panelOpacity = 0.6f),
            PanelZone(label = "send", xFraction = 0.107f, yFraction = 0.421f, widthFraction = 0.778f, heightFraction = 0.111f, panelOpacity = 0.6f),
            PanelZone(label = "receive", xFraction = 0.107f, yFraction = 0.578f, widthFraction = 0.778f, heightFraction = 0.111f, panelOpacity = 0.6f),
            PanelZone(label = "transfer", xFraction = 0.107f, yFraction = 0.741f, widthFraction = 0.778f, heightFraction = 0.111f, panelOpacity = 0.6f)
        )
    )

    val vaultForest = ThemeConfig(
        name = "Forest",
        primaryColor = 0xFF60C060,
        secondaryColor = 0xFF40A040,
        backgroundColor = 0xFF0A1A0A,
        surfaceColor = 0xFF183018,
        textColor = 0xFFD0F0D0,
        accentColor = 0xFF60C060,
        useBackgroundImage = true,
        backgroundResId = "bg_forest",
        chainLabelYFraction = 0.007f,
        panelZones = listOf(
            PanelZone(label = "balance", xFraction = 0.099f, yFraction = 0.28f, widthFraction = 0.806f, heightFraction = 0.09f, panelOpacity = 0.5f),
            PanelZone(label = "send", xFraction = 0.099f, yFraction = 0.415f, widthFraction = 0.806f, heightFraction = 0.09f, panelOpacity = 0.5f),
            PanelZone(label = "receive", xFraction = 0.099f, yFraction = 0.548f, widthFraction = 0.806f, heightFraction = 0.09f, panelOpacity = 0.5f),
            PanelZone(label = "transfer", xFraction = 0.099f, yFraction = 0.678f, widthFraction = 0.806f, heightFraction = 0.09f, panelOpacity = 0.5f)
        )
    )

    val vaultMarble = ThemeConfig(
        name = "Marble",
        primaryColor = 0xFFD0C8B8,
        secondaryColor = 0xFFB0A898,
        backgroundColor = 0xFFF0E8E0,
        surfaceColor = 0xFFE0D8D0,
        textColor = 0xFF2A2018,
        accentColor = 0xFF8B7355,
        useBackgroundImage = true,
        backgroundResId = "bg_marble",
        chainLabelYFraction = 0.007f,
        panelZones = listOf(
            PanelZone(label = "balance", xFraction = 0.049f, yFraction = 0.24f, widthFraction = 0.903f, heightFraction = 0.104f, panelOpacity = 0.45f),
            PanelZone(label = "send", xFraction = 0.049f, yFraction = 0.382f, widthFraction = 0.903f, heightFraction = 0.104f, panelOpacity = 0.45f),
            PanelZone(label = "receive", xFraction = 0.049f, yFraction = 0.525f, widthFraction = 0.903f, heightFraction = 0.104f, panelOpacity = 0.45f),
            PanelZone(label = "transfer", xFraction = 0.049f, yFraction = 0.664f, widthFraction = 0.903f, heightFraction = 0.104f, panelOpacity = 0.45f)
        )
    )

    val vaultWoodDark1 = ThemeConfig(
        name = "Dark Wood I",
        primaryColor = 0xFFC8A868,
        secondaryColor = 0xFFA08848,
        backgroundColor = 0xFF0C0804,
        surfaceColor = 0xFF18100A,
        textColor = 0xFFF0D8B0,
        accentColor = 0xFFC8A868,
        useBackgroundImage = true,
        backgroundResId = "bg_wood_dark_01",
        chainLabelYFraction = 0.007f,
        panelZones = listOf(
            PanelZone(label = "balance", xFraction = 0.061f, yFraction = 0.233f, widthFraction = 0.875f, heightFraction = 0.104f, panelOpacity = 0.55f),
            PanelZone(label = "send", xFraction = 0.061f, yFraction = 0.382f, widthFraction = 0.875f, heightFraction = 0.104f, panelOpacity = 0.55f),
            PanelZone(label = "receive", xFraction = 0.061f, yFraction = 0.528f, widthFraction = 0.875f, heightFraction = 0.104f, panelOpacity = 0.55f),
            PanelZone(label = "transfer", xFraction = 0.061f, yFraction = 0.678f, widthFraction = 0.875f, heightFraction = 0.104f, panelOpacity = 0.55f)
        )
    )

    val vaultWoodDark2 = ThemeConfig(
        name = "Dark Wood II",
        primaryColor = 0xFFB89858,
        secondaryColor = 0xFF987838,
        backgroundColor = 0xFF0C0804,
        surfaceColor = 0xFF180E06,
        textColor = 0xFFF0D0A0,
        accentColor = 0xFFB89858,
        useBackgroundImage = true,
        backgroundResId = "bg_wood_dark_02",
        chainLabelYFraction = 0.007f,
        panelZones = listOf(
            PanelZone(label = "balance", xFraction = 0.031f, yFraction = 0.243f, widthFraction = 0.944f, heightFraction = 0.16f, panelOpacity = 0.55f),
            PanelZone(label = "send", xFraction = 0.061f, yFraction = 0.438f, widthFraction = 0.875f, heightFraction = 0.069f, panelOpacity = 0.55f),
            PanelZone(label = "receive", xFraction = 0.061f, yFraction = 0.539f, widthFraction = 0.875f, heightFraction = 0.069f, panelOpacity = 0.55f),
            PanelZone(label = "transfer", xFraction = 0.061f, yFraction = 0.643f, widthFraction = 0.875f, heightFraction = 0.069f, panelOpacity = 0.55f),
            PanelZone(label = "history", xFraction = 0.061f, yFraction = 0.746f, widthFraction = 0.875f, heightFraction = 0.069f, panelOpacity = 0.55f)
        )
    )

    val all = listOf(
        cyberpunk, neonNervos, lightClean, midnightBlue, sunsetOrange, forestGreen,
        vaultBlackLeather, vaultBrownLeather1, vaultBrownLeather2, vaultBrownLeather3,
        vaultBrownLeather4, vaultCircuitBoard, vaultForest, vaultMarble,
        vaultWoodDark1, vaultWoodDark2
    )

    val backgroundImageThemes = listOf(
        vaultBlackLeather, vaultBrownLeather1, vaultBrownLeather2, vaultBrownLeather3,
        vaultBrownLeather4, vaultCircuitBoard, vaultForest, vaultMarble,
        vaultWoodDark1, vaultWoodDark2
    )
}

class SkinManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("skin_manager", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private var currentTheme: ThemeConfig = ThemePresets.cyberpunk
    private val customThemes = mutableListOf<ThemeConfig>()

    init {
        loadTheme()
        loadCustomThemes()
    }

    fun getCurrentTheme(): ThemeConfig = currentTheme

    fun setTheme(theme: ThemeConfig) {
        currentTheme = theme
        saveTheme()
    }

    fun saveCustomTheme(theme: ThemeConfig) {
        customThemes.add(theme)
        saveCustomThemes()
    }

    fun removeCustomTheme(themeName: String) {
        customThemes.removeAll { it.name == themeName }
        saveCustomThemes()
    }

    fun getCustomThemes(): List<ThemeConfig> = customThemes.toList()

    fun getAllThemes(): List<ThemeConfig> = ThemePresets.all + customThemes

    fun exportThemeJson(theme: ThemeConfig): String {
        return try {
            json.encodeToString(theme)
        } catch (e: Exception) {
            "{}"
        }
    }

    fun importThemeJson(jsonString: String): ThemeConfig? {
        return try {
            json.decodeFromString<ThemeConfig>(jsonString)
        } catch (e: Exception) {
            null
        }
    }

    fun updatePanelBackground(panelName: String, background: PanelBackground) {
        val updatedPanels = currentTheme.panelBackgrounds.toMutableMap()
        updatedPanels[panelName] = background
        currentTheme = currentTheme.copy(panelBackgrounds = updatedPanels)
        saveTheme()
    }

    private fun saveTheme() {
        try {
            val jsonString = json.encodeToString(currentTheme)
            prefs.edit().putString("current_theme", jsonString).apply()
        } catch (e: Exception) {
            // Handle serialization error
        }
    }

    private fun loadTheme() {
        val jsonString = prefs.getString("current_theme", null)
        if (jsonString != null) {
            try {
                currentTheme = json.decodeFromString(jsonString)
            } catch (e: Exception) {
                currentTheme = ThemePresets.cyberpunk
            }
        }
    }

    private fun saveCustomThemes() {
        try {
            val jsonString = json.encodeToString(customThemes)
            prefs.edit().putString("custom_themes", jsonString).apply()
        } catch (e: Exception) {
            // Handle serialization error
        }
    }

    private fun loadCustomThemes() {
        val jsonString = prefs.getString("custom_themes", null)
        if (jsonString != null) {
            try {
                val loaded = json.decodeFromString<List<ThemeConfig>>(jsonString)
                customThemes.addAll(loaded)
            } catch (e: Exception) {
                customThemes.clear()
            }
        }
    }
}