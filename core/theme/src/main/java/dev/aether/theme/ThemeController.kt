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

    suspend fun load() {
        val id = repository.observeActiveThemeId().first() ?: catalog.default.id
        publish(id)
    }

    suspend fun activate(id: ThemeId): Result<Unit> {
        val result = repository.activate(id)
        if (result.isSuccess) publish(id)
        return result
    }

    suspend fun saveDraft(theme: ThemeDefinition) = repository.saveDraft(theme)

    fun preview(theme: ThemeDefinition): ResolvedTheme =
        resolver.resolve(theme, catalog.definitions())

    private suspend fun publish(id: ThemeId) {
        val draft = repository.getDraft(id)
        val theme = draft ?: catalog.find(id) ?: catalog.default
        val definitions = catalog.definitions() + listOfNotNull(draft, theme)
        _active.value = resolver.resolve(theme, definitions)
    }
}
