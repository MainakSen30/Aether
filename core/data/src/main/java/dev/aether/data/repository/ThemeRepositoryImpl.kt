package dev.aether.data.repository

import dev.aether.data.dao.ThemeMetadataDao
import dev.aether.data.entity.ThemeMetadataEntity
import dev.aether.domain.PreferencesRepository
import dev.aether.domain.ThemeRepository
import dev.aether.model.ThemeId
import dev.aether.model.theme.ThemeDefinition
import dev.aether.model.theme.ThemeMetadata
import dev.aether.model.theme.ThemeOrigin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.concurrent.ConcurrentHashMap

class ThemeRepositoryImpl(
    private val dao: ThemeMetadataDao,
    private val preferences: PreferencesRepository,
) : ThemeRepository {
    // Full token blobs are not persisted yet; drafts are process-local until the format is finalized.
    private val drafts = ConcurrentHashMap<String, ThemeDefinition>()

    override fun observeActiveThemeId(): Flow<ThemeId?> = preferences.settings.map { it.activeThemeId }

    override fun observeMetadata(): Flow<List<ThemeMetadata>> =
        dao.observeAll().map { entities -> entities.map(ThemeMetadataEntity::toDomain) }

    override suspend fun getMetadata(id: ThemeId): ThemeMetadata? = dao.get(id.value)?.toDomain()

    override suspend fun saveDraft(theme: ThemeDefinition) {
        drafts[theme.id.value] = theme
        dao.upsert(
            ThemeMetadataEntity(
                id = theme.id.value,
                name = theme.name,
                parentId = theme.parentId?.value,
                origin = ThemeOrigin.DRAFT.name,
            ),
        )
    }

    override suspend fun getDraft(id: ThemeId): ThemeDefinition? = drafts[id.value]

    override suspend fun activate(id: ThemeId): Result<Unit> = runCatching {
        require(getMetadata(id) != null) { "Unknown theme: ${id.value}" }
        preferences.update { it.copy(activeThemeId = id) }
    }
}

private fun ThemeMetadataEntity.toDomain() = ThemeMetadata(
    id = ThemeId(id),
    name = name,
    parentId = parentId?.let { ThemeId(it) },
    origin = runCatching { ThemeOrigin.valueOf(origin) }.getOrDefault(ThemeOrigin.IMPORTED),
)
