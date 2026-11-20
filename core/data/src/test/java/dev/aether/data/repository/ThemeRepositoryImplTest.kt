package dev.aether.data.repository

import dev.aether.data.dao.ThemeMetadataDao
import dev.aether.data.entity.ThemeDraftEntity
import dev.aether.data.entity.ThemeMetadataEntity
import dev.aether.domain.PreferencesRepository
import dev.aether.model.AetherSettings
import dev.aether.model.ThemeId
import dev.aether.model.theme.AetherColors
import dev.aether.model.theme.AetherDimensions
import dev.aether.model.theme.AetherShapes
import dev.aether.model.theme.AetherTypography
import dev.aether.model.theme.ComponentOverride
import dev.aether.model.theme.SurfaceStyle
import dev.aether.model.theme.SurfaceTreatment
import dev.aether.model.theme.ThemeDefinition
import dev.aether.model.theme.ThemeOrigin
import dev.aether.model.theme.ThemeTokens
import dev.aether.theme.AetherThemeCatalog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeRepositoryImplTest {
    private val dao = FakeThemeMetadataDao()
    private val preferences = FakePreferencesRepository()
    private val repository = ThemeRepositoryImpl(dao, preferences)

    @Test
    fun `round trips a complete definition through persistent storage`() = runTest {
        val theme = theme().copy(
            tokens = theme().tokens.copy(
                componentPresets = mapOf("button" to "filled"),
                componentOverrides = mapOf("button" to ComponentOverride()),
            ),
        )

        repository.saveDefinition(theme)

        assertEquals(theme, ThemeRepositoryImpl(dao, preferences).getDefinition(theme.id))
    }

    @Test
    fun `drafts survive repository recreation without changing active theme`() = runTest {
        val draft = theme().copy(id = ThemeId("draft.one"), origin = ThemeOrigin.DRAFT)

        repository.saveDraft(draft)

        assertNull(preferences.current.activeThemeId)
        assertEquals(draft, ThemeRepositoryImpl(dao, preferences).getDraft(draft.id))
    }

    @Test
    fun `a draft cannot hide a built in definition with the same id`() = runTest {
        val builtIn = AetherThemeCatalog.AETHER_BLACK
        repository.seedBuiltIns(listOf(builtIn), builtIn.id).getOrThrow()
        repository.saveDraft(builtIn.copy(name = "Black Draft"))

        assertEquals(builtIn, repository.getDefinition(builtIn.id))
        assertEquals("Black Draft", repository.getDraft(builtIn.id)?.name)
    }

    @Test
    fun `malformed stored json is not returned as a definition`() = runTest {
        val theme = theme()
        repository.saveDefinition(theme)
        val entity = dao.entities.getValue(theme.id.value)
        dao.entities[theme.id.value] = entity.copy(definitionJson = """{"unsafe":true}""")

        assertNull(repository.getDefinition(theme.id))
        assertTrue(repository.activate(theme.id).isFailure)
    }

    @Test
    fun `a failed seed leaves no partially written definitions`() = runTest {
        val first = theme().copy(id = ThemeId("seed.first"), name = "First")
        val second = theme().copy(id = ThemeId("seed.second"), name = "Second")
        dao.failSeedAt = 2

        val result = repository.seedBuiltIns(listOf(first, second), first.id)

        assertTrue(result.isFailure)
        assertTrue(dao.entities.isEmpty())
        assertNull(preferences.current.activeThemeId)
    }

    @Test
    fun `seeding is idempotent and preserves user managed definitions`() = runTest {
        val builtIn = AetherThemeCatalog.AETHER_BLACK
        val customized = builtIn.copy(id = ThemeId("user.black"), origin = ThemeOrigin.IMPORTED)
        repository.saveDefinition(customized)

        repository.seedBuiltIns(listOf(builtIn), builtIn.id).getOrThrow()
        repository.seedBuiltIns(listOf(builtIn), builtIn.id).getOrThrow()

        assertEquals(2, dao.entities.size)
        assertEquals(customized, repository.getDefinition(customized.id))
        assertEquals(builtIn, repository.getDefinition(builtIn.id))
    }

    @Test
    fun `seeding updates changed built ins without touching drafts`() = runTest {
        val builtIn = AetherThemeCatalog.AETHER_BLACK
        repository.seedBuiltIns(listOf(builtIn), builtIn.id).getOrThrow()
        val changed = builtIn.copy(name = "Aether Black 2")
        repository.saveDraft(builtIn.copy(name = "Black Draft"))

        repository.seedBuiltIns(listOf(changed), builtIn.id).getOrThrow()

        assertEquals(changed.name, repository.getDefinition(builtIn.id)?.name)
        assertEquals("Black Draft", repository.getDraft(builtIn.id)?.name)
    }

    @Test
    fun `initialization selects default and preserves a valid active theme`() = runTest {
        repository.seedBuiltIns(listOf(AetherThemeCatalog.AETHER_BLACK), AetherThemeCatalog.AETHER_BLACK.id)
            .getOrThrow()

        repository.initialize(AetherThemeCatalog.AETHER_BLACK.id).getOrThrow()
        assertEquals(AetherThemeCatalog.AETHER_BLACK.id, preferences.current.activeThemeId)

        val other = theme().copy(id = ThemeId("user.theme"), origin = ThemeOrigin.IMPORTED)
        repository.saveDefinition(other)
        repository.activate(other.id).getOrThrow()
        repository.initialize(AetherThemeCatalog.AETHER_BLACK.id).getOrThrow()
        assertEquals(other.id, preferences.current.activeThemeId)

        preferences.state.value = preferences.current.copy(activeThemeId = ThemeId("missing"))
        assertTrue(repository.initialize(AetherThemeCatalog.AETHER_BLACK.id).isFailure)
        assertEquals(ThemeId("missing"), preferences.current.activeThemeId)
    }

    @Test
    fun `activation requires a decodable persisted definition`() = runTest {
        assertTrue(repository.activate(ThemeId("missing")).isFailure)
        assertNull(preferences.current.activeThemeId)

        val theme = theme()
        dao.entities[theme.id.value] = ThemeMetadataEntity(
            id = theme.id.value,
            name = theme.name,
            parentId = null,
            origin = theme.origin.name,
        )
        assertTrue(repository.activate(theme.id).isFailure)
        assertNull(preferences.current.activeThemeId)

        repository.saveDefinition(theme)
        assertTrue(repository.activate(theme.id).isSuccess)
        assertEquals(theme.id, preferences.current.activeThemeId)
    }

    @Test
    fun `invalid definitions and drafts are not persisted`() = runTest {
        assertTrue(repository.saveDefinition(theme().copy(name = " ")).isFailure)
        assertTrue(repository.saveDraft(theme().copy(name = " ")).isFailure)
        assertTrue(dao.entities.isEmpty())
        assertTrue(dao.drafts.isEmpty())
    }

    @Test
    fun `a colliding draft never changes the persisted active definition`() = runTest {
        val builtIn = AetherThemeCatalog.AETHER_BLACK
        repository.seedBuiltIns(listOf(builtIn), builtIn.id).getOrThrow()
        repository.saveDraft(builtIn.copy(name = "Black Draft")).getOrThrow()

        assertEquals(builtIn, repository.getDefinition(builtIn.id))
        assertTrue(repository.activate(builtIn.id).isSuccess)
        assertEquals(builtIn.id, preferences.current.activeThemeId)
        assertEquals(builtIn.name, repository.getDefinition(builtIn.id)?.name)
    }

    @Test
    fun `an invalid persisted ancestor blocks activation of a valid child`() = runTest {
        val parent = theme().copy(id = ThemeId("parent.valid"))
        val child = theme().copy(id = ThemeId("child.valid"), parentId = parent.id)
        repository.saveDefinition(parent).getOrThrow()
        repository.saveDefinition(child).getOrThrow()
        val parentEntity = dao.entities.getValue(parent.id.value)
        dao.entities[parent.id.value] = parentEntity.copy(definitionJson = """{"unsafe":true}""")

        assertTrue(repository.activate(child.id).isFailure)
        assertNull(preferences.current.activeThemeId)
    }

    @Test
    fun `activation of a draft only theme is rejected`() = runTest {
        val draft = theme().copy(id = ThemeId("draft.only"), origin = ThemeOrigin.DRAFT)
        repository.saveDraft(draft).getOrThrow()

        assertTrue(repository.activate(draft.id).isFailure)
        assertNull(preferences.current.activeThemeId)
    }

    private fun theme() = ThemeDefinition(
        id = ThemeId("theme.one"),
        name = "Theme One",
        parentId = null,
        tokens = ThemeTokens(
            colors = AetherColors(background = 0xFF050505, foreground = null),
            typography = AetherTypography(40f, 18f, 14f, 0f),
            shapes = AetherShapes(8f, 16f, 24f),
            dimensions = AetherDimensions(16f, 12f),
            surface = SurfaceTreatment(SurfaceStyle.FLAT),
        ),
        origin = ThemeOrigin.IMPORTED,
    )
}

private class FakeThemeMetadataDao : ThemeMetadataDao {
    val entities = mutableMapOf<String, ThemeMetadataEntity>()
    val drafts = mutableMapOf<String, ThemeDraftEntity>()
    var failSeedAt: Int? = null

    override fun observeAll(): Flow<List<ThemeMetadataEntity>> = MutableStateFlow(entities.values.toList())
    override suspend fun getAll(): List<ThemeMetadataEntity> = entities.values.toList()
    override suspend fun get(id: String) = entities[id]
    override suspend fun getDrafts(): List<ThemeDraftEntity> = drafts.values.toList()
    override suspend fun getDraft(id: String) = drafts[id]
    override suspend fun count(id: String): Int = if (id in entities) 1 else 0
    override suspend fun insert(entity: ThemeMetadataEntity) {
        entities.putIfAbsent(entity.id, entity)
    }
    override suspend fun upsert(entity: ThemeMetadataEntity) {
        entities[entity.id] = entity
    }
    override suspend fun upsertDraft(entity: ThemeDraftEntity) {
        drafts[entity.id] = entity
    }
    override suspend fun seedBuiltIns(
        definitions: List<ThemeMetadataEntity>,
        builtInOrigin: String,
    ) {
        val committed = LinkedHashMap(entities)
        definitions.forEachIndexed { index, entity ->
            if (index + 1 == failSeedAt) throw IllegalStateException("seed interrupted")
            when {
                entity.id !in committed -> committed[entity.id] = entity
                committed[entity.id]?.origin == builtInOrigin -> committed[entity.id] = entity
            }
        }
        entities.clear()
        entities.putAll(committed)
    }
}

private class FakePreferencesRepository : PreferencesRepository {
    val state = MutableStateFlow(AetherSettings())
    val current get() = state.value
    override val settings: Flow<AetherSettings> = state
    override suspend fun update(transform: (AetherSettings) -> AetherSettings) {
        state.value = transform(state.value)
    }
}
