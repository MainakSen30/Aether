package dev.aether.theme

import dev.aether.domain.ThemeRepository
import dev.aether.model.ThemeId
import dev.aether.model.theme.ThemeDefinition
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

/**
 * Owns runtime theme state. Drafts can be previewed but are never published until
 * [activate] succeeds in both Room and DataStore.
 */
class ThemeController(
    private val repository: ThemeRepository,
    private val catalog: ThemeCatalog,
    private val resolver: ThemeResolver,
) {
    private val _active = MutableStateFlow<ResolvedTheme?>(null)
    val active: StateFlow<ResolvedTheme?> = _active.asStateFlow()

    suspend fun initialize(): Result<Unit> {
        val seeding = repository.seedBuiltIns(catalog.definitions(), catalog.default.id)
        if (seeding.isFailure) {
            _active.value = null
            return seeding
        }
        val initialization = repository.initialize(catalog.default.id)
        if (initialization.isFailure) {
            _active.value = null
            return initialization
        }
        return load()
    }

    suspend fun load(): Result<Unit> = runCatching {
        val id = repository.observeActiveThemeId().first() ?: error("No active theme configured")
        publish(id).getOrThrow()
    }

    suspend fun activate(id: ThemeId): Result<Unit> {
        val result = repository.activate(id)
        if (result.isSuccess) {
            val published = publish(id)
            if (published.isFailure) {
                _active.value = null
                return published
            }
        }
        return result
    }

    suspend fun saveDraft(theme: ThemeDefinition) = repository.saveDraft(theme)

    suspend fun preview(theme: ThemeDefinition): Result<ResolvedTheme> = runCatching {
        resolver.resolve(theme, repository.getDefinitions() + theme)
    }

    private suspend fun publish(id: ThemeId): Result<Unit> = runCatching {
        val theme = repository.getDraft(id) ?: repository.getDefinition(id)
            ?: error("Invalid persisted theme: ${id.value}")
        val definitions = repository.getDefinitions()
        _active.value = resolver.resolve(theme, definitions)
    }
}
