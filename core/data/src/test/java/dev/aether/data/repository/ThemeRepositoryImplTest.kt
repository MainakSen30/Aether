package dev.aether.data.repository

import dev.aether.data.dao.ThemeMetadataDao
import dev.aether.data.entity.ThemeMetadataEntity
import dev.aether.domain.PreferencesRepository
import dev.aether.model.AetherSettings
import dev.aether.model.ThemeId
import dev.aether.model.theme.AetherColors
import dev.aether.model.theme.AetherDimensions
import dev.aether.model.theme.AetherShapes
import dev.aether.model.theme.AetherTypography
import dev.aether.model.theme.SurfaceStyle
import dev.aether.model.theme.SurfaceTreatment
import dev.aether.model.theme.ThemeDefinition
import dev.aether.model.theme.ThemeOrigin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeRepositoryImplTest {
    private val dao = FakeThemeMetadataDao()
    private val preferences = FakePreferencesRepository()
    private val repository = ThemeRepositoryImpl(dao, preferences)

    @Test
    fun `activation requires persisted metadata and only then writes preferences`() = runTest {
        assertTrue(repository.activate(ThemeId("missing")).isFailure)
        assertEquals(null, preferences.current.activeThemeId)

        dao.entities[ThemeId("aether.black")] = ThemeMetadataEntity(
            "aether.black", "Aether Black", null, ThemeOrigin.BUILT_IN.name,
        )
        assertTrue(repository.activate(ThemeId("aether.black")).isSuccess)
        assertEquals(ThemeId("aether.black"), preferences.current.activeThemeId)
    }

    @Test
    fun `draft metadata is persisted while full tokens stay in memory`() = runTest {
        repository.saveDraft(draft())
        assertEquals(ThemeOrigin.DRAFT.name, dao.entities.getValue(draft().id).origin)
        assertEquals(draft().name, repository.getDraft(draft().id)?.name)
    }

    private fun draft() = ThemeDefinition(
        id = ThemeId("draft.one"),
        name = "Draft One",
        tokens = dev.aether.model.theme.ThemeTokens(
            colors = AetherColors(background = 0x050505),
            typography = AetherTypography(40f, 18f, 14f, 0f),
            shapes = AetherShapes(8f, 16f, 24f),
            dimensions = AetherDimensions(16f, 12f),
            surface = SurfaceTreatment(SurfaceStyle.FLAT),
        ),
        origin = ThemeOrigin.DRAFT,
    )
}

private class FakeThemeMetadataDao : ThemeMetadataDao {
    val entities = mutableMapOf<ThemeId, ThemeMetadataEntity>()
    override fun observeAll(): Flow<List<ThemeMetadataEntity>> = MutableStateFlow(entities.values.toList())
    override suspend fun get(id: String) = entities[ThemeId(id)]
    override suspend fun upsert(entity: ThemeMetadataEntity) = entities.set(ThemeId(entity.id), entity).let { Unit }
}

private class FakePreferencesRepository : PreferencesRepository {
    val state = MutableStateFlow(AetherSettings())
    val current get() = state.value
    override val settings: Flow<AetherSettings> = state
    override suspend fun update(transform: (AetherSettings) -> AetherSettings) {
        state.value = transform(state.value)
    }
}
