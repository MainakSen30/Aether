package dev.aether.data.repository

import dev.aether.data.dao.ThemeMetadataDao
import dev.aether.data.entity.ThemeDraftEntity
import dev.aether.data.entity.ThemeMetadataEntity
import dev.aether.domain.PreferencesRepository
import dev.aether.domain.ThemeRepository
import dev.aether.model.ThemeId
import dev.aether.model.theme.ThemeDefinition
import dev.aether.model.theme.ThemeMetadata
import dev.aether.model.theme.ThemeOrigin
import dev.aether.model.theme.ThemePackage
import dev.aether.theme.ThemeInheritanceValidator
import dev.aether.theme.ThemePackageCodec
import dev.aether.theme.ThemeValidationError
import dev.aether.theme.ThemeValidationException
import dev.aether.theme.asResult
import dev.aether.theme.withTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ThemeRepositoryImpl(
    private val dao: ThemeMetadataDao,
    private val preferences: PreferencesRepository,
) : ThemeRepository {
    private val mutex = Mutex()
    private val inheritance = ThemeInheritanceValidator()

    override fun observeActiveThemeId(): Flow<ThemeId?> = preferences.settings.map { it.activeThemeId }

    override fun observeMetadata(): Flow<List<ThemeMetadata>> =
        dao.observeAll().map { entities -> entities.map(ThemeMetadataEntity::toDomain) }

    override suspend fun getMetadata(id: ThemeId): ThemeMetadata? = dao.get(id.value)?.toDomain()

    override suspend fun getDefinition(id: ThemeId): ThemeDefinition? = dao.get(id.value)?.let(::decode)

    /** Drafts are preview-only and never participate in active-theme inheritance. */
    override suspend fun getDefinitions(): List<ThemeDefinition> = dao.getAll().mapNotNull(::decode)

    override suspend fun saveDefinition(theme: ThemeDefinition): Result<Unit> = runCatching {
        validateGraph(theme, getDefinitions()).getOrThrow()
        dao.upsert(theme.toMetadataEntity())
    }

    override suspend fun saveDraft(theme: ThemeDefinition): Result<Unit> = runCatching {
        validateGraph(theme, getDefinitions()).getOrThrow()
        dao.upsertDraft(theme.toDraftEntity())
    }

    override suspend fun getDraft(id: ThemeId): ThemeDefinition? = dao.getDraft(id.value)?.let(::decode)

    override suspend fun seedBuiltIns(
        definitions: List<ThemeDefinition>,
        defaultId: ThemeId,
    ): Result<Unit> = mutex.withLock {
        runCatching {
            require(definitions.any { it.id == defaultId }) { "Default theme is not in catalog" }
            inheritance.validate(definitions).asResult().getOrThrow()
            dao.seedBuiltIns(
                definitions.map { it.copy(origin = ThemeOrigin.BUILT_IN).toMetadataEntity() },
                ThemeOrigin.BUILT_IN.name,
            )
        }
    }

    override suspend fun initialize(defaultId: ThemeId): Result<Unit> = mutex.withLock {
        runCatching {
            val configured = observeActiveThemeId().first()
            val activeId = configured ?: defaultId
            val active = getDefinition(activeId)
                ?: throw ThemeValidationException(
                    ThemeValidationError.Inheritance("configured theme is unavailable: ${activeId.value}"),
                )
            validateGraph(active, getDefinitions()).getOrThrow()
            if (configured == null) preferences.update { it.copy(activeThemeId = defaultId) }
        }
    }

    override suspend fun activate(id: ThemeId): Result<Unit> = runCatching {
        val theme = getDefinition(id)
            ?: throw ThemeValidationException(
                ThemeValidationError.Inheritance("theme is not available: ${id.value}"),
            )
        validateGraph(theme, getDefinitions()).getOrThrow()
        preferences.update { it.copy(activeThemeId = id) }
    }

    private fun validateGraph(theme: ThemeDefinition, definitions: List<ThemeDefinition>): Result<Unit> =
        inheritance.validateLineage(theme.id, definitions.withTheme(theme)).asResult()

    private fun decode(entity: ThemeMetadataEntity): ThemeDefinition? {
        val raw = entity.definitionJson ?: return null
        val definition = decode(raw) ?: return null
        if (definition.id.value != entity.id ||
            definition.name != entity.name ||
            definition.parentId?.value != entity.parentId ||
            definition.schemaVersion != entity.schemaVersion ||
            definition.origin.name != entity.origin
        ) {
            return null
        }
        return definition
    }

    private fun decode(entity: ThemeDraftEntity): ThemeDefinition? = decode(entity.definitionJson)

    private fun decode(raw: String): ThemeDefinition? =
        ThemePackageCodec.decode(raw).getOrNull()?.definition

    private fun ThemeDefinition.toMetadataEntity() = ThemeMetadataEntity(
        id = id.value,
        name = name,
        parentId = parentId?.value,
        origin = origin.name,
        schemaVersion = schemaVersion,
        definitionJson = encode(),
        updatedAtMillis = System.currentTimeMillis(),
    )

    private fun ThemeDefinition.toDraftEntity() = ThemeDraftEntity(
        id = id.value,
        schemaVersion = schemaVersion,
        definitionJson = encode(),
        updatedAtMillis = System.currentTimeMillis(),
    )

    private fun ThemeDefinition.encode(): String =
        ThemePackageCodec.encode(ThemePackage(schemaVersion, this))
}

private fun ThemeMetadataEntity.toDomain() = ThemeMetadata(
    id = ThemeId(id),
    name = name,
    parentId = parentId?.let { ThemeId(it) },
    origin = runCatching { ThemeOrigin.valueOf(origin) }.getOrDefault(ThemeOrigin.IMPORTED),
)
