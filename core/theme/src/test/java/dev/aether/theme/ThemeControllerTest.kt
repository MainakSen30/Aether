package dev.aether.theme

import dev.aether.domain.ThemeRepository
import dev.aether.model.ThemeId
import dev.aether.model.theme.ThemeDefinition
import dev.aether.model.theme.ThemeMetadata
import dev.aether.model.theme.ThemeOrigin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeControllerTest {
    private val repository = FakeThemeRepository()
    private val controller = ThemeController(
        repository,
        AetherThemeCatalog,
        ThemeResolver(AetherThemeCatalog.AETHER_BLACK.tokens),
    )

    @Test
    fun `initialization publishes the seeded default`() = runTest {
        assertTrue(controller.initialize().isSuccess)
        assertEquals(AetherThemeCatalog.AETHER_BLACK.id, controller.active.value?.id)
    }

    @Test
    fun `draft does not change active theme until applied`() = runTest {
        controller.initialize()
        val baseline = controller.active.value

        val draft = AetherThemeCatalog.AETHER_BLACK.copy(
            id = ThemeId("draft.black"),
            name = "Draft Black",
        )
        controller.saveDraft(draft)
        assertEquals(baseline, controller.active.value)
        assertEquals(draft.id, controller.preview(draft).getOrThrow().id)
        assertEquals(baseline, controller.active.value)

        repository.failActivation = true
        assertTrue(controller.activate(draft.id).isFailure)
        assertEquals(baseline, controller.active.value)

        repository.failActivation = false
        repository.saveDefinition(draft)
        assertTrue(controller.activate(draft.id).isSuccess)
        assertEquals(draft.id, controller.active.value?.id)
    }

    @Test
    fun `resolution failure never publishes a fallback theme`() = runTest {
        repository.failDefinitions = true
        assertTrue(controller.initialize().isFailure)
        assertNull(controller.active.value)
    }

    @Test
    fun `failed activation does not publish invalid runtime state or change stored id`() = runTest {
        controller.initialize()
        val baseline = controller.active.value

        val invalid = AetherThemeCatalog.AETHER_BLACK.copy(id = ThemeId("missing.theme"))
        assertTrue(controller.activate(invalid.id).isFailure)

        assertEquals(baseline, controller.active.value)
        assertEquals(AetherThemeCatalog.AETHER_BLACK.id, repository.activeId.value)
    }
}

private class FakeThemeRepository : ThemeRepository {
    val activeId = MutableStateFlow<ThemeId?>(null)
    val definitions = mutableMapOf<ThemeId, ThemeDefinition>(
        AetherThemeCatalog.AETHER_BLACK.id to AetherThemeCatalog.AETHER_BLACK,
    )
    var failActivation = false
    var failDefinitions = false

    override fun observeActiveThemeId(): Flow<ThemeId?> = activeId
    override fun observeMetadata(): Flow<List<ThemeMetadata>> = flowOf(definitions.values.map {
        ThemeMetadata(it.id, it.name, it.parentId, it.origin)
    })
    override suspend fun getMetadata(id: ThemeId) = definitions[id]?.let {
        ThemeMetadata(it.id, it.name, it.parentId, it.origin)
    }
    override suspend fun getDefinition(id: ThemeId) = if (failDefinitions) null else definitions[id]
    override suspend fun getDefinitions() = if (failDefinitions) emptyList() else definitions.values.toList()
    override suspend fun saveDefinition(theme: ThemeDefinition): Result<Unit> {
        definitions[theme.id] = theme
        return Result.success(Unit)
    }
    override suspend fun saveDraft(theme: ThemeDefinition): Result<Unit> = Result.success(Unit)
    override suspend fun getDraft(id: ThemeId): ThemeDefinition? = null
    override suspend fun seedBuiltIns(
        definitions: List<ThemeDefinition>,
        defaultId: ThemeId,
    ): Result<Unit> = Result.success(Unit)
    override suspend fun initialize(defaultId: ThemeId): Result<Unit> {
        if (activeId.value == null) activeId.value = defaultId
        return Result.success(Unit)
    }
    override suspend fun activate(id: ThemeId) = if (failActivation) {
        Result.failure(IllegalStateException("activation failed"))
    } else {
        activeId.value = id
        definitions[id]?.let { saveDefinition(it) }
        Result.success(Unit)
    }
}
