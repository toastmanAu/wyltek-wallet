package com.wyltek.wallet.core.skin

import kotlinx.serialization.Serializable

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

    val all = listOf(cyberpunk, neonNervos, lightClean)
}

class SkinManager {

    private var currentTheme: ThemeConfig = ThemePresets.cyberpunk
    private val customThemes = mutableListOf<ThemeConfig>()

    fun getCurrentTheme(): ThemeConfig = currentTheme

    fun setTheme(theme: ThemeConfig) {
        currentTheme = theme
    }

    fun saveCustomTheme(theme: ThemeConfig) {
        customThemes.add(theme)
    }

    fun getCustomThemes(): List<ThemeConfig> = customThemes.toList()

    fun getAllThemes(): List<ThemeConfig> = ThemePresets.all + customThemes

    fun exportThemeJson(theme: ThemeConfig): String {
        // TODO: kotlinx.serialization encoding
        return theme.toString()
    }

    fun importThemeJson(json: String): ThemeConfig? {
        // TODO: kotlinx.serialization decoding
        return null
    }
}
