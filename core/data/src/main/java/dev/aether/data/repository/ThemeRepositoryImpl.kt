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
import dev.aether.theme.ThemePackageCodec
import dev.aether.theme.ThemePackageValidator
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

    override fun observeActiveThemeId(): Flow<ThemeId?> = preferences.settings.map { it.activeThemeId }

    override fun observeMetadata(): Flow<List<ThemeMetadata>> =
        dao.observeAll().map { entities -> entities.map(ThemeMetadataEntity::toDomain) }

    override suspend fun getMetadata(id: ThemeId): ThemeMetadata? = dao.get(id.value)?.toDomain()

    override suspend fun getDefinition(id: ThemeId): ThemeDefinition? =
        dao.get(id.value)?.let(::decode)

    override suspend fun getDefinitions(): List<ThemeDefinition> =
        dao.getAll().mapNotNull(::decode) + dao.getDrafts().mapNotNull(::decode)

    override suspend fun saveDefinition(theme: ThemeDefinition) {
        dao.upsert(theme.toMetadataEntity())
    }

    override suspend fun saveDraft(theme: ThemeDefinition) {
        dao.upsertDraft(theme.toDraftEntity())
    }

    override suspend fun getDraft(id: ThemeId): ThemeDefinition? =
        dao.getDraft(id.value)?.let(::decode)

    override suspend fun seedBuiltIns(
        definitions: List<ThemeDefinition>,
        defaultId: ThemeId,
    ): Result<Unit> = mutex.withLock {
        runCatching {
            require(definitions.any { it.id == defaultId }) { "Default theme is not in catalog" }
            definitions.forEach { definition ->
                ThemePackageValidator.validate(ThemePackage(ThemePackage.FORMAT_VERSION, definition)).getOrThrow()
                val entity = definition.copy(origin = ThemeOrigin.BUILT_IN).toMetadataEntity()
                if (!dao.insertIfAbsent(entity)) {
                    val existing = dao.get(definition.id.value)
                    if (existing?.origin == ThemeOrigin.BUILT_IN.name) dao.upsert(entity)
                }
            }
        }
    }

    override suspend fun initialize(defaultId: ThemeId): Result<Unit> = mutex.withLock {
        runCatching {
            val configured = observeActiveThemeId().first()
            val valid = configured?.let { getDefinition(it) != null || getDraft(it) != null } == true
            if (!valid) {
                require(getDefinition(defaultId) != null) { "Default theme is unavailable" }
                preferences.update { it.copy(activeThemeId = defaultId) }
            }
        }
    }

    override suspend fun activate(id: ThemeId): Result<Unit> = runCatching {
        require(getDefinition(id) != null || getDraft(id) != null) { "Unknown or invalid theme: ${id.value}" }
        preferences.update { it.copy(activeThemeId = id) }
    }

    private fun decode(entity: ThemeMetadataEntity): ThemeDefinition? {
        val raw = entity.definitionJson ?: return null
        val definition = decode(raw) ?: return null
        if (definition.id.value != entity.id ||
            definition.name != entity.name ||
            definition.parentId?.value != entity.parentId ||
            definition.origin.name != entity.origin
        ) {
            return null
        }
        return definition
    }

    private fun decode(entity: ThemeDraftEntity): ThemeDefinition? = decode(entity.definitionJson)

    private fun decode(raw: String): ThemeDefinition? {
        val decoded = ThemePackageCodec.decode(raw).getOrNull() ?: return null
        if (ThemePackageValidator.validate(decoded).isFailure) return null
        return decoded.definition
    }

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
