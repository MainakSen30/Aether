package dev.aether.model

data class AetherSettings(
    val activeThemeId: ThemeId? = null,
    val gridColumns: Int = 4,
    val gesturesEnabled: Boolean = true,
    val animationsEnabled: Boolean = true,
    val appearance: AppearanceMode = AppearanceMode.SYSTEM,
)

enum class AppearanceMode { SYSTEM, DARK, LIGHT }
