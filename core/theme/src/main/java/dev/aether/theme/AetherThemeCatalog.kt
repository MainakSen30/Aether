package dev.aether.theme

import dev.aether.model.ThemeId
import dev.aether.model.theme.AetherColors
import dev.aether.model.theme.AetherDimensions
import dev.aether.model.theme.AetherShapes
import dev.aether.model.theme.AetherTypography
import dev.aether.model.theme.SurfaceStyle
import dev.aether.model.theme.SurfaceTreatment
import dev.aether.model.theme.ThemeDefinition
import dev.aether.model.theme.ThemeTokens

interface ThemeCatalog {
    val default: ThemeDefinition
    fun definitions(): List<ThemeDefinition>
    fun find(id: ThemeId): ThemeDefinition?
}

/** Deliberately restrained: deep black, warm cream, Material 3 foundation, minimal glass. */
object AetherThemeCatalog : ThemeCatalog {
    val AETHER_BLACK = ThemeDefinition(
        id = ThemeId("aether.black"),
        name = "Aether Black",
        parentId = null,
        tokens = ThemeTokens(
            colors = AetherColors(
                background = 0xFF050505,
                surface = 0xFF141414,
                foreground = 0xFFF3EEE4,
                mutedForeground = 0xFFA9A299,
                primary = 0xFFD8CDBB,
                onPrimary = 0xFF111111,
                outline = 0xFF2E2E2E,
            ),
            typography = AetherTypography(
                displaySizeSp = 44f,
                titleSizeSp = 20f,
                bodySizeSp = 15f,
                letterSpacingEm = 0.01f,
            ),
            shapes = AetherShapes(smallDp = 8f, mediumDp = 16f, largeDp = 28f),
            dimensions = AetherDimensions(spacingDp = 16f, gridGapDp = 12f),
            surface = SurfaceTreatment(style = SurfaceStyle.LIQUID_GLASS, elevationDp = 2f, alpha = 0.92f),
        ),
    )

    override val default: ThemeDefinition = AETHER_BLACK

    private val builtIn = listOf(AETHER_BLACK)

    override fun definitions(): List<ThemeDefinition> = builtIn

    override fun find(id: ThemeId): ThemeDefinition? = builtIn.firstOrNull { it.id == id }
}
