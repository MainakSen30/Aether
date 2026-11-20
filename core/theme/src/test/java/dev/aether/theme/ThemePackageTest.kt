package dev.aether.theme

import dev.aether.model.theme.ThemePackage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
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

class ThemeControllerTest {
    private val repository = FakeThemeRepository()
    private val controller = ThemeController(
        repository,
        AetherThemeCatalog,
        ThemeResolver(AetherThemeCatalog.AETHER_BLACK.tokens),
    )

    @Test
    fun `draft does not change active theme until applied`() = kotlinx.coroutines.test.runTest {
        controller.load()
        val baseline = controller.active.value
        assertEquals(AetherThemeCatalog.AETHER_BLACK.id, baseline?.id)

        val draft = AetherThemeCatalog.AETHER_BLACK.copy(
            id = dev.aether.model.ThemeId("draft.black"),
            name = "Draft Black",
        )
        controller.saveDraft(draft)
        assertEquals(baseline, controller.active.value)

        repository.failActivation = true
        assertTrue(controller.activate(draft.id).isFailure)
        assertEquals(baseline, controller.active.value)

        repository.failActivation = false
        assertTrue(controller.activate(draft.id).isSuccess)
        assertEquals("draft.black", controller.active.value?.id?.value)
    }
}

private class FakeThemeRepository : dev.aether.domain.ThemeRepository {
    val activeId = MutableStateFlow<dev.aether.model.ThemeId?>(null)
    val drafts = mutableMapOf<dev.aether.model.ThemeId, dev.aether.model.theme.ThemeDefinition>()
    val metadata = mutableMapOf<dev.aether.model.ThemeId, dev.aether.model.theme.ThemeMetadata>()
    var failActivation = false

    override fun observeActiveThemeId() = activeId
    override fun observeMetadata() = flowOf(metadata.values.toList())
    override suspend fun getMetadata(id: dev.aether.model.ThemeId) = metadata[id]
    override suspend fun saveDraft(theme: dev.aether.model.theme.ThemeDefinition) {
        drafts[theme.id] = theme
        metadata[theme.id] = dev.aether.model.theme.ThemeMetadata(
            theme.id, theme.name, theme.parentId, dev.aether.model.theme.ThemeOrigin.DRAFT,
        )
    }
    override suspend fun getDraft(id: dev.aether.model.ThemeId) = drafts[id]
    override suspend fun activate(id: dev.aether.model.ThemeId) = if (failActivation) {
        Result.failure(IllegalStateException("activation failed"))
    } else {
        activeId.value = id
        Result.success(Unit)
    }
}
