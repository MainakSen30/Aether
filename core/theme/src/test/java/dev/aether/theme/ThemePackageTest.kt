package dev.aether.theme

import dev.aether.model.ThemeId
import dev.aether.model.theme.AetherColors
import dev.aether.model.theme.ComponentOverride
import dev.aether.model.theme.SurfaceStyle
import dev.aether.model.theme.ThemeDefinition
import dev.aether.model.theme.ThemeOrigin
import dev.aether.model.theme.ThemePackage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val VALID_PACKAGE = """
{
  "formatVersion": 1,
  "definition": {
    "id": "compact.black",
    "name": "Compact Black",
    "tokens": {
      "colors": {"background": 436207616},
      "typography": {"displaySizeSp": 40, "titleSizeSp": 18, "bodySizeSp": 14, "letterSpacingEm": 0},
      "shapes": {"smallDp": 8, "mediumDp": 16, "largeDp": 24},
      "dimensions": {"spacingDp": 16, "gridGapDp": 12},
      "surface": {"style": "FLAT", "elevationDp": 0, "alpha": 1}
    }
  }
}
"""

class ThemePackageTest {
    @Test
    fun `decodes a versioned data only package`() {
        val themePackage = ThemePackageCodec.decode(VALID_PACKAGE).getOrThrow()
        assertEquals(ThemePackage.FORMAT_VERSION, themePackage.formatVersion)
        assertEquals("Compact Black", themePackage.definition.name)
        assertEquals(ThemePackage.FORMAT_VERSION, themePackage.definition.schemaVersion)
        assertEquals(Unit, ThemePackageValidator.validate(themePackage).getOrThrow())
        assertTrue(ThemePackageCodec.decode(ThemePackageCodec.encode(themePackage)).isSuccess)
    }

    @Test
    fun `rejects empty and malformed json`() {
        assertTrue(validationError(ThemePackageCodec.decode("")) is ThemeValidationError.MalformedJson)
        assertTrue(validationError(ThemePackageCodec.decode("{")) is ThemeValidationError.MalformedJson)
    }

    @Test
    fun `rejects oversized json`() {
        val oversized = VALID_PACKAGE + " ".repeat(ThemePackageValidator.MAX_THEME_JSON_BYTES)
        val failure = validationError(ThemePackageCodec.decode(oversized))
        assertTrue(failure is ThemeValidationError.OversizedJson)
    }

    @Test
    fun `rejects unsupported format and schema versions`() {
        val format = VALID_PACKAGE.replace("\"formatVersion\": 1", "\"formatVersion\": 2")
        assertTrue(validationError(ThemePackageCodec.decode(format)) is ThemeValidationError.UnsupportedVersion)

        val schema = VALID_PACKAGE.replace("\"id\": \"compact.black\"", "\"id\": \"compact.black\", \"schemaVersion\": 2")
        assertTrue(validationError(ThemePackageCodec.decode(schema)) is ThemeValidationError.UnsupportedVersion)
    }

    @Test
    fun `rejects unknown duplicate and nested unknown fields`() {
        assertTrue(
            validationError(
                ThemePackageCodec.decode(
                    VALID_PACKAGE.replace("\"name\":", "\"code\": \"exec()\", \"name\":"),
                ),
            ) is ThemeValidationError.MalformedJson,
        )
        assertTrue(
            validationError(
                ThemePackageCodec.decode(
                    VALID_PACKAGE.replace("\"name\": \"Compact Black\"", "\"name\": \"A\", \"name\": \"B\""),
                ),
            ) is ThemeValidationError.MalformedJson,
        )
        assertTrue(
            validationError(
                ThemePackageCodec.decode(VALID_PACKAGE.replace("\"style\": \"FLAT\"", "\"style\": \"FLAT\", \"unsafe\": true")),
            ) is ThemeValidationError.MalformedJson,
        )
    }

    @Test
    fun `rejects invalid enum values and special floats`() {
        assertTrue(
            validationError(ThemePackageCodec.decode(VALID_PACKAGE.replace("\"FLAT\"", "\"NEON\"")))
                is ThemeValidationError.MalformedJson,
        )
        assertTrue(
            validationError(ThemePackageCodec.decode(VALID_PACKAGE.replace("\"alpha\": 1", "\"alpha\": NaN")))
                is ThemeValidationError.MalformedJson,
        )
        assertTrue(
            validationError(ThemePackageCodec.decode(VALID_PACKAGE.replace("\"alpha\": 1", "\"alpha\": Infinity")))
                is ThemeValidationError.MalformedJson,
        )
    }

    @Test
    fun `rejects invalid identity fields`() {
        assertTrue(validationError(validate(theme().copy(id = ThemeId("a".repeat(65))))) is ThemeValidationError.InvalidThemeId)
        assertTrue(
            validationError(ThemePackageCodec.decode(VALID_PACKAGE.replace("compact.black", "bad id")))
                is ThemeValidationError.MalformedJson,
        )
        assertTrue(validationError(validate(theme().copy(name = "  "))) is ThemeValidationError.InvalidThemeName)
        assertTrue(validationError(validate(theme().copy(name = "x".repeat(81)))) is ThemeValidationError.InvalidThemeName)
        assertTrue(
            validationError(validate(theme().copy(parentId = ThemeId("p".repeat(65)))))
                is ThemeValidationError.InvalidParentId,
        )
        assertTrue(validationError(validate(theme().copy(parentId = theme().id))) is ThemeValidationError.SelfInheritance)
    }

    @Test
    fun `accepts transparent colors but rejects out of range argb`() {
        assertTrue(validate(theme(0x00FF0000)).isSuccess)
        assertTrue(validationError(validate(theme(0x1_0000_0000))) is ThemeValidationError.InvalidColor)
        assertTrue(validationError(validate(theme(-1))) is ThemeValidationError.InvalidColor)
    }

    @Test
    fun `rejects nonfinite and out of bounds numeric tokens`() {
        assertTrue(validationError(validate(theme(typography = { it.copy(displaySizeSp = Float.NaN) })))
            is ThemeValidationError.InvalidNumber)
        assertTrue(validationError(validate(theme(typography = { it.copy(titleSizeSp = Float.POSITIVE_INFINITY) })))
            is ThemeValidationError.InvalidNumber)
        assertTrue(validationError(validate(theme(typography = { it.copy(bodySizeSp = -1f) })))
            is ThemeValidationError.InvalidNumber)
        assertTrue(validationError(validate(theme(shapes = { it.copy(smallDp = -1f) })))
            is ThemeValidationError.InvalidNumber)
        assertTrue(validationError(validate(theme(dimensions = { it.copy(gridGapDp = 1_000f) })))
            is ThemeValidationError.InvalidNumber)
    }

    @Test
    fun `rejects invalid surface and component values`() {
        assertTrue(validationError(validate(theme(surface = { it.copy(alpha = 1.1f) })))
            is ThemeValidationError.InvalidNumber)
        assertTrue(validationError(validate(theme(surface = { it.copy(elevationDp = -1f) })))
            is ThemeValidationError.InvalidNumber)
        assertTrue(validate(theme(surface = { it.copy(style = SurfaceStyle.LIQUID_GLASS) })).isSuccess)
        assertTrue(
            validationError(
                validate(
                    theme().copy(
                        tokens = theme().tokens.copy(
                            componentPresets = mapOf("button" to "glow"),
                        ),
                    ),
                ),
            ) is ThemeValidationError.InvalidComponent,
        )
        assertTrue(
            validationError(
                validate(
                    theme().copy(
                        tokens = theme().tokens.copy(
                            componentOverrides = mapOf(
                                "button" to ComponentOverride(
                                    colors = dev.aether.model.theme.AetherColorOverrides(background = -1),
                                ),
                            ),
                        ),
                    ),
                ),
            ) is ThemeValidationError.InvalidColor,
        )
        assertTrue(
            validationError(
                validate(
                    theme().copy(
                        tokens = theme().tokens.copy(
                            componentOverrides = mapOf("unknown" to ComponentOverride()),
                        ),
                    ),
                ),
            ) is ThemeValidationError.InvalidComponent,
        )
    }

    private fun validate(definition: ThemeDefinition) =
        ThemePackageValidator.validate(ThemePackage(ThemePackage.FORMAT_VERSION, definition))

    private fun validationError(result: Result<*>): ThemeValidationError? =
        (result.exceptionOrNull() as? ThemeValidationException)?.error

    private fun theme(
        background: Long = 0xFF050505,
        typography: (dev.aether.model.theme.AetherTypography) -> dev.aether.model.theme.AetherTypography = { it },
        shapes: (dev.aether.model.theme.AetherShapes) -> dev.aether.model.theme.AetherShapes = { it },
        dimensions: (dev.aether.model.theme.AetherDimensions) -> dev.aether.model.theme.AetherDimensions = { it },
        surface: (dev.aether.model.theme.SurfaceTreatment) -> dev.aether.model.theme.SurfaceTreatment = { it },
    ) = ThemeDefinition(
        id = ThemeId("theme.valid"),
        name = "Theme Valid",
        tokens = ThemeTokensFixture.tokens.copy(
            colors = AetherColors(background = background),
            typography = typography(ThemeTokensFixture.tokens.typography),
            shapes = shapes(ThemeTokensFixture.tokens.shapes),
            dimensions = dimensions(ThemeTokensFixture.tokens.dimensions),
            surface = surface(ThemeTokensFixture.tokens.surface),
        ),
        origin = ThemeOrigin.IMPORTED,
    )

    private object ThemeTokensFixture {
        val tokens = ThemePackageCodec.decode(VALID_PACKAGE).getOrThrow().definition.tokens
    }
}
