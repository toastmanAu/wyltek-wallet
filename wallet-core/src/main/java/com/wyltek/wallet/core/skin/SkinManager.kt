package com.wyltek.wallet.core.skin

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class ThemeConfig(
    val name: String,
    val primaryColor: Long,
    val secondaryColor: Long,
    val backgroundColor: Long,
    val surfaceColor: Long,
    val textColor: Long,
    val isDark: Boolean = true,
    val panelBackgrounds: Map<String, PanelBackground> = emptyMap()
)

@Serializable
data class PanelBackground(
    val imageUri: String? = null,
    val backgroundColor: Long? = null,
    val blurRadius: Float = 0f,
    val dimOpacity: Float = 0.3f
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

    val all = listOf(cyberpunk, neonNervos, lightClean, midnightBlue, sunsetOrange, forestGreen)
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
