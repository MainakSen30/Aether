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
import dev.aether.theme.ThemeValidationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private fun tokens(colors: AetherColors) = ThemeTokens(
    colors = colors,
    typography = AetherTypography(40f, 18f, 14f, 0f),
    shapes = AetherShapes(8f, 16f, 24f),
    dimensions = AetherDimensions(16f, 12f),
    surface = SurfaceTreatment(SurfaceStyle.FLAT),
)

private fun theme(id: String, parent: String? = null, colors: AetherColors = AetherColors()) =
    ThemeDefinition(ThemeId(id), id, parentId = parent?.let(::ThemeId), tokens = tokens(colors))

class ThemeInheritanceTest {
    private val validator = ThemeInheritanceValidator()

    @Test
    fun `detects a two theme cycle`() {
        val result = validator.validate(listOf(theme("a", "b"), theme("b", "a")))
        assertTrue(result is InheritanceValidation.Cycle)
    }

    @Test
    fun `detects a missing parent`() {
        val result = validator.validate(listOf(theme("child", "missing")))
        assertTrue(result is InheritanceValidation.MissingParent)
    }

    @Test
    fun `accepts single parent lineage`() {
        val definitions = listOf(theme("a"), theme("b", "a"), theme("c", "b"))
        assertEquals(InheritanceValidation.Valid, validator.validate(definitions))
        assertEquals(
            listOf("a", "b", "c"),
            validator.lineage(ThemeId("c"), definitions).getOrThrow().map { it.id.value },
        )
    }

    @Test
    fun `inherits parent tokens and applies design system defaults`() {
        val parent = theme("parent", colors = AetherColors(background = 0x111111))
        val child = theme("child", "parent", AetherColors(foreground = 0x222222))
        val resolver = ThemeResolver(tokens(AetherColors(outline = 0x333333)))
        val resolved = resolver.resolve(child, listOf(parent))

        assertEquals(0x111111L, resolver.color(resolved, ColorKey.BACKGROUND))
        assertEquals(0x222222L, resolver.color(resolved, ColorKey.FOREGROUND))
        assertEquals(0x333333L, resolver.color(resolved, ColorKey.OUTLINE))
        assertEquals(0xFF1E1E1EL, resolver.color(resolved, ColorKey.SURFACE))
    }

    @Test
    fun `component override wins over active token`() {
        val base = theme("base", colors = AetherColors(background = 0x111111))
        val override = base.copy(
            tokens = base.tokens.copy(
                componentOverrides = mapOf(
                    "button" to dev.aether.model.theme.ComponentOverride(
                        colors = dev.aether.model.theme.AetherColorOverrides(background = 0x999999),
                    ),
                ),
            ),
        )
        val resolver = ThemeResolver(tokens(AetherColors()))
        val resolved = resolver.resolve(override, listOf(base))
        assertEquals(0x999999L, resolver.color(resolved, ColorKey.BACKGROUND, "button"))
        assertEquals(0x111111L, resolver.color(resolved, ColorKey.BACKGROUND))
    }

    @Test
    fun `rejects self inheritance and longer cycles`() {
        assertTrue(validator.validate(listOf(theme("self", "self"))) is InheritanceValidation.InvalidTheme)
        val cycle = listOf(theme("a", "b"), theme("b", "c"), theme("c", "a"))
        assertTrue(validator.validate(cycle) is InheritanceValidation.Cycle)
    }

    @Test
    fun `rejects duplicate theme ids`() {
        val result = validator.validate(listOf(theme("same"), theme("same")))
        assertTrue(result is InheritanceValidation.DuplicateThemeId)
        assertEquals("same", (result as InheritanceValidation.DuplicateThemeId).themeId.value)
    }

    @Test
    fun `rejects an invalid ancestor in an otherwise valid chain`() {
        val invalidParent = theme("parent").copy(
            tokens = theme("parent").tokens.copy(
                typography = theme("parent").tokens.typography.copy(displaySizeSp = Float.NaN),
            ),
        )
        val result = validator.validate(listOf(invalidParent, theme("child", "parent")))
        assertTrue(result is InheritanceValidation.InvalidTheme)
        assertEquals("parent", (result as InheritanceValidation.InvalidTheme).themeId.value)
    }

    @Test
    fun `resolution refuses an invalid inheritance graph`() {
        val resolver = ThemeResolver(tokens(AetherColors()))
        val result = runCatching { resolver.resolve(theme("child", "missing"), emptyList()) }
        assertTrue(result.exceptionOrNull() is ThemeValidationException)
    }
}
