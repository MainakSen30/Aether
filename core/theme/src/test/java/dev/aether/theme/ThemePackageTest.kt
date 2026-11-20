package dev.aether.theme

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
    fun `rejects unsupported format versions`() {
        val themePackage = ThemePackageCodec.decode(VALID_PACKAGE.replace("\"formatVersion\": 1", "\"formatVersion\": 2"))
            .getOrThrow()
        assertTrue(ThemePackageValidator.validate(themePackage).isFailure)
    }

    @Test
    fun `rejects unknown fields so packages cannot carry code`() {
        val result = ThemePackageCodec.decode(VALID_PACKAGE.replace("\"name\":", "\"code\": \"exec()\", \"name\":"))
        assertTrue(result.isFailure)
    }
}

