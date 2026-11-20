package dev.aether.model.theme

import dev.aether.model.ThemeId
import kotlinx.serialization.Serializable

@Serializable
data class ThemeDefinition(
    val id: ThemeId,
    val name: String,
    val schemaVersion: Int = ThemePackage.FORMAT_VERSION,
    val parentId: ThemeId? = null,
    val tokens: ThemeTokens,
    val origin: ThemeOrigin = ThemeOrigin.BUILT_IN,
)

data class ThemeMetadata(
    val id: ThemeId,
    val name: String,
    val parentId: ThemeId?,
    val origin: ThemeOrigin,
)

/** A draft is isolated from the active theme until it is explicitly applied. */
data class ThemeDraft(
    val baseThemeId: ThemeId,
    val definition: ThemeDefinition,
    val isDirty: Boolean = false,
)

@Serializable
data class ThemeTokens(
    val colors: AetherColors,
    val typography: AetherTypography,
    val shapes: AetherShapes,
    val dimensions: AetherDimensions,
    val surface: SurfaceTreatment,
    val componentPresets: Map<String, String> = emptyMap(),
    val componentOverrides: Map<String, ComponentOverride> = emptyMap(),
)

/** Nullable values allow a child theme to override only part of its parent's palette. */
@Serializable
data class AetherColors(
    val background: Long? = null,
    val surface: Long? = null,
    val foreground: Long? = null,
    val mutedForeground: Long? = null,
    val primary: Long? = null,
    val onPrimary: Long? = null,
    val outline: Long? = null,
)

@Serializable
data class AetherColorOverrides(
    val background: Long? = null,
    val surface: Long? = null,
    val foreground: Long? = null,
)

@Serializable
data class AetherTypography(
    val displaySizeSp: Float,
    val titleSizeSp: Float,
    val bodySizeSp: Float,
    val letterSpacingEm: Float,
)

@Serializable
data class AetherShapes(
    val smallDp: Float,
    val mediumDp: Float,
    val largeDp: Float,
)

@Serializable
data class AetherDimensions(
    val spacingDp: Float,
    val gridGapDp: Float,
)

@Serializable
data class SurfaceTreatment(
    val style: SurfaceStyle,
    val elevationDp: Float = 0f,
    val alpha: Float = 1f,
)

@Serializable
data class ComponentOverride(val colors: AetherColorOverrides? = null)

enum class SurfaceStyle { FLAT, SUBTLE_ELEVATION, LIQUID_GLASS }

enum class ThemeOrigin { BUILT_IN, IMPORTED, DRAFT }

/** Portable, versioned JSON envelope. It carries data only, never executable code. */
@Serializable
data class ThemePackage(val formatVersion: Int, val definition: ThemeDefinition) {
    companion object {
        const val FORMAT_VERSION = 1
    }
}
