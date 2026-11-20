package dev.aether.theme

import dev.aether.model.ThemeId
import dev.aether.model.theme.AetherColorOverrides
import dev.aether.model.theme.AetherColors
import dev.aether.model.theme.AetherDimensions
import dev.aether.model.theme.AetherShapes
import dev.aether.model.theme.AetherTypography
import dev.aether.model.theme.ComponentOverride
import dev.aether.model.theme.SurfaceStyle
import dev.aether.model.theme.SurfaceTreatment
import dev.aether.model.theme.ThemeDefinition
import dev.aether.model.theme.ThemeOrigin
import dev.aether.model.theme.ThemePackage
import dev.aether.model.theme.ThemeTokens

sealed interface ThemeValidationError {
    val message: String

    data class InvalidThemeId(val value: String) : ThemeValidationError {
        override val message = "Invalid theme id: $value"
    }

    data class InvalidThemeName(val reason: String) : ThemeValidationError {
        override val message = "Invalid theme name: $reason"
    }

    data class InvalidParentId(val value: String) : ThemeValidationError {
        override val message = "Invalid parent theme id: $value"
    }

    data class SelfInheritance(val themeId: String) : ThemeValidationError {
        override val message = "Theme cannot inherit from itself: $themeId"
    }

    data class InvalidOrigin(val value: String) : ThemeValidationError {
        override val message = "Invalid theme origin: $value"
    }

    data class UnsupportedVersion(val formatVersion: Int, val schemaVersion: Int) : ThemeValidationError {
        override val message = "Unsupported theme version: format=$formatVersion schema=$schemaVersion"
    }

    data class InvalidColor(val field: String, val value: Long) : ThemeValidationError {
        override val message = "Invalid ARGB color for $field: $value"
    }

    data class InvalidNumber(val field: String, val value: Float) : ThemeValidationError {
        override val message = "Invalid value for $field: $value"
    }

    data class InvalidSurface(val field: String, val value: String) : ThemeValidationError {
        override val message = "Invalid surface $field: $value"
    }

    data class InvalidComponent(val id: String, val reason: String) : ThemeValidationError {
        override val message = "Invalid component '$id': $reason"
    }

    data class MalformedJson(val reason: String) : ThemeValidationError {
        override val message = "Malformed theme JSON: $reason"
    }

    data class OversizedJson(val bytes: Int) : ThemeValidationError {
        override val message = "Theme JSON is too large: $bytes bytes"
    }

    data class Inheritance(val reason: String) : ThemeValidationError {
        override val message = "Invalid theme inheritance: $reason"
    }
}

class ThemeValidationException(val error: ThemeValidationError) : IllegalArgumentException(error.message)

/** Small explicit registry; extend these sets when the design system gains real components. */
object ThemeComponentRegistry {
    val COMPONENT_IDS = setOf("button", "text", "surface", "icon")
    val PRESET_IDS = setOf("filled", "outlined", "text", "glass")

    const val MAX_COMPONENT_ID_LENGTH = 64
}

object ThemePackageValidator {
    const val MAX_THEME_ID_LENGTH = 64
    const val MAX_THEME_NAME_LENGTH = 80
    const val MAX_THEME_JSON_BYTES = 256 * 1024

    private val SAFE_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,${MAX_THEME_ID_LENGTH - 1}}")

    private const val MIN_TEXT_SIZE_SP = 8f
    private const val MAX_DISPLAY_SIZE_SP = 96f
    private const val MAX_TITLE_SIZE_SP = 48f
    private const val MAX_BODY_SIZE_SP = 32f
    private const val MIN_LETTER_SPACING_EM = -0.2f
    private const val MAX_LETTER_SPACING_EM = 0.5f
    private const val MAX_DIMENSION_DP = 512f
    private const val MAX_ALPHA = 1f

    fun validate(themePackage: ThemePackage): Result<Unit> = runCatching {
        if (themePackage.formatVersion != ThemePackage.FORMAT_VERSION ||
            themePackage.definition.schemaVersion != themePackage.formatVersion
        ) {
            throw ThemeValidationException(
                ThemeValidationError.UnsupportedVersion(
                    themePackage.formatVersion,
                    themePackage.definition.schemaVersion,
                ),
            )
        }
        validateDefinition(themePackage.definition).getOrThrow()
    }

    fun validateDefinition(definition: ThemeDefinition): Result<Unit> = runCatching {
        requireValidId(definition.id.value) { ThemeValidationError.InvalidThemeId(it) }
        if (definition.name.isBlank() || definition.name.length > MAX_THEME_NAME_LENGTH) {
            throw ThemeValidationException(ThemeValidationError.InvalidThemeName("blank or over $MAX_THEME_NAME_LENGTH characters"))
        }
        definition.parentId?.value?.let { parentId ->
            requireValidId(parentId) { ThemeValidationError.InvalidParentId(it) }
            if (parentId == definition.id.value) {
                throw ThemeValidationException(ThemeValidationError.SelfInheritance(definition.id.value))
            }
        }
        runCatching { ThemeOrigin.valueOf(definition.origin.name) }
            .onFailure { throw ThemeValidationException(ThemeValidationError.InvalidOrigin(definition.origin.name)) }
        if (definition.schemaVersion != ThemePackage.FORMAT_VERSION) {
            throw ThemeValidationException(
                ThemeValidationError.UnsupportedVersion(ThemePackage.FORMAT_VERSION, definition.schemaVersion),
            )
        }
        validateTokens(definition.tokens)
    }

    private fun validateTokens(tokens: ThemeTokens) {
        validateColors(tokens.colors)
        validateTypography(tokens.typography)
        validateShapes(tokens.shapes)
        validateDimensions(tokens.dimensions)
        validateSurface(tokens.surface)
        validateComponents(tokens.componentPresets, tokens.componentOverrides)
    }

    private fun validateColors(colors: AetherColors) {
        listOf(
            "background" to colors.background,
            "surface" to colors.surface,
            "foreground" to colors.foreground,
            "mutedForeground" to colors.mutedForeground,
            "primary" to colors.primary,
            "onPrimary" to colors.onPrimary,
            "outline" to colors.outline,
        ).forEach { (field, value) ->
            if (value != null && value !in 0L..0xFFFFFFFFL) {
                throw ThemeValidationException(ThemeValidationError.InvalidColor(field, value))
            }
        }
    }

    private fun validateTypography(typography: AetherTypography) {
        bounded("displaySizeSp", typography.displaySizeSp, MIN_TEXT_SIZE_SP, MAX_DISPLAY_SIZE_SP)
        bounded("titleSizeSp", typography.titleSizeSp, MIN_TEXT_SIZE_SP, MAX_TITLE_SIZE_SP)
        bounded("bodySizeSp", typography.bodySizeSp, MIN_TEXT_SIZE_SP, MAX_BODY_SIZE_SP)
        bounded("letterSpacingEm", typography.letterSpacingEm, MIN_LETTER_SPACING_EM, MAX_LETTER_SPACING_EM)
    }

    private fun validateShapes(shapes: AetherShapes) {
        bounded("smallDp", shapes.smallDp, 0f, MAX_DIMENSION_DP)
        bounded("mediumDp", shapes.mediumDp, 0f, MAX_DIMENSION_DP)
        bounded("largeDp", shapes.largeDp, 0f, MAX_DIMENSION_DP)
    }

    private fun validateDimensions(dimensions: AetherDimensions) {
        bounded("spacingDp", dimensions.spacingDp, 0f, MAX_DIMENSION_DP)
        bounded("gridGapDp", dimensions.gridGapDp, 0f, MAX_DIMENSION_DP)
    }

    private fun validateSurface(surface: SurfaceTreatment) {
        if (runCatching { SurfaceStyle.valueOf(surface.style.name) }.isFailure) {
            throw ThemeValidationException(ThemeValidationError.InvalidSurface("style", surface.style.name))
        }
        bounded("elevationDp", surface.elevationDp, 0f, MAX_DIMENSION_DP)
        bounded("alpha", surface.alpha, 0f, MAX_ALPHA)
    }

    private fun validateComponents(
        presets: Map<String, String>,
        overrides: Map<String, ComponentOverride>,
    ) {
        (presets.keys + overrides.keys).forEach { id ->
            if (id.isBlank() || id.length > ThemeComponentRegistry.MAX_COMPONENT_ID_LENGTH ||
                id !in ThemeComponentRegistry.COMPONENT_IDS
            ) {
                throw ThemeValidationException(ThemeValidationError.InvalidComponent(id, "unsupported component"))
            }
        }
        if (presets.keys.distinct().size != presets.size || overrides.keys.distinct().size != overrides.size) {
            throw ThemeValidationException(ThemeValidationError.Inheritance("duplicate component identifier"))
        }
        presets.forEach { (id, preset) ->
            if (preset !in ThemeComponentRegistry.PRESET_IDS) {
                throw ThemeValidationException(ThemeValidationError.InvalidComponent(id, "unsupported preset: $preset"))
            }
        }
        overrides.forEach { (id, override) -> validateOverride(id, override.colors) }
    }

    private fun validateOverride(id: String, colors: AetherColorOverrides?) {
        if (colors == null) return
        listOf(
            "background" to colors.background,
            "surface" to colors.surface,
            "foreground" to colors.foreground,
        ).forEach { (field, value) ->
            if (value != null && value !in 0L..0xFFFFFFFFL) {
                throw ThemeValidationException(ThemeValidationError.InvalidColor("override.$id.$field", value))
            }
        }
    }

    private fun bounded(field: String, value: Float, min: Float, max: Float) {
        if (!value.isFinite() || value !in min..max) {
            throw ThemeValidationException(ThemeValidationError.InvalidNumber(field, value))
        }
    }

    private fun requireValidId(value: String, error: (String) -> ThemeValidationError) {
        if (!SAFE_ID.matches(value)) throw ThemeValidationException(error(value))
    }
}

/** Replaces any definition with the same ID so previews and saves stay duplicate-free. */
fun List<ThemeDefinition>.withTheme(theme: ThemeDefinition): List<ThemeDefinition> {
    val result = LinkedHashMap<String, ThemeDefinition>()
    forEach { result[it.id.value] = it }
    result[theme.id.value] = theme
    return result.values.toList()
}
