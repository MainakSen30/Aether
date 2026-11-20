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
 * they are explicitly saved and activated.
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

    suspend fun load(): Result<Unit> {
        val prepared = prepareConfigured()
        if (prepared.isFailure) {
            _active.value = null
            return prepared.map { }
        }
        _active.value = prepared.getOrThrow()
        return Result.success(Unit)
    }

    /**
     * Resolves and validates before the ID is persisted, so an invalid theme never becomes active.
     * Room and DataStore are not atomic; a failed DataStore write gets a best-effort rollback to the
     * previously configured ID, and startup validation remains the final recovery path.
     */
    suspend fun activate(id: ThemeId): Result<Unit> {
        val prepared = prepare(id)
        if (prepared.isFailure) {
            return prepared.map { }
        }

        val previous = repository.observeActiveThemeId().first()
        val activation = repository.activate(id)
        if (activation.isFailure) {
            if (previous != null && previous != id) repository.activate(previous)
            return activation
        }
        _active.value = prepared.getOrThrow()
        return activation
    }

    suspend fun saveDraft(theme: ThemeDefinition): Result<Unit> = repository.saveDraft(theme)

    suspend fun preview(theme: ThemeDefinition): Result<ResolvedTheme> = runCatching {
        ThemePackageValidator.validateDefinition(theme).getOrThrow()
        resolver.resolve(theme, repository.getDefinitions().withTheme(theme))
    }

    private suspend fun prepareConfigured(): Result<ResolvedTheme> = runCatching {
        val id = repository.observeActiveThemeId().first()
            ?: throw ThemeValidationException(ThemeValidationError.Inheritance("no active theme configured"))
        prepare(id).getOrThrow()
    }

    /** Active themes are always persisted definitions; drafts are preview-only. */
    private suspend fun prepare(id: ThemeId): Result<ResolvedTheme> = runCatching {
        val theme = repository.getDefinition(id)
            ?: throw ThemeValidationException(ThemeValidationError.InvalidThemeId(id.value))
        resolver.resolve(theme, repository.getDefinitions().withTheme(theme))
    }
}
