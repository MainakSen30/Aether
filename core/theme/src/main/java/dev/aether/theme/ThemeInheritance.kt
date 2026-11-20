package dev.aether.theme

import dev.aether.model.ThemeId
import dev.aether.model.theme.ThemeDefinition

sealed interface InheritanceValidation {
    val description: String

    data object Valid : InheritanceValidation {
        override val description = "valid"
    }

    data class Cycle(val themeIds: List<ThemeId>) : InheritanceValidation {
        override val description = "inheritance cycle: ${themeIds.joinToString(" -> ") { it.value }}"
    }

    data class MissingParent(val themeId: ThemeId, val parentId: ThemeId) : InheritanceValidation {
        override val description = "missing parent $parentId for ${themeId.value}"
    }

    data class DuplicateThemeId(val themeId: ThemeId) : InheritanceValidation {
        override val description = "duplicate theme id: ${themeId.value}"
    }

    data class InvalidTheme(val themeId: ThemeId, val error: ThemeValidationError) : InheritanceValidation {
        override val description = "invalid theme ${themeId.value}: ${error.message}"
    }

    data class UnknownTheme(val themeId: ThemeId) : InheritanceValidation {
        override val description = "unknown theme: ${themeId.value}"
    }
}

fun InheritanceValidation.asResult(): Result<Unit> = when (this) {
    InheritanceValidation.Valid -> Result.success(Unit)
    is InheritanceValidation.InvalidTheme -> Result.failure(ThemeValidationException(error))
    else -> Result.failure(ThemeValidationException(ThemeValidationError.Inheritance(description)))
}

/** Single-parent inheritance with full-graph validation; a theme may never be its own ancestor. */
class ThemeInheritanceValidator {
    fun validate(definitions: List<ThemeDefinition>): InheritanceValidation {
        val seenIds = HashSet<ThemeId>()
        definitions.forEach { definition ->
            if (!seenIds.add(definition.id)) return InheritanceValidation.DuplicateThemeId(definition.id)
            val invalid = ThemePackageValidator.validateDefinition(definition).exceptionOrNull()
            if (invalid != null) {
                val error = (invalid as? ThemeValidationException)?.error
                    ?: ThemeValidationError.Inheritance(invalid.message ?: "invalid definition")
                return InheritanceValidation.InvalidTheme(definition.id, error)
            }
        }

        val byId = definitions.associateBy { it.id }
        definitions.forEach { definition ->
            val path = LinkedHashSet<ThemeId>()
            var current: ThemeDefinition? = definition
            while (current != null) {
                if (!path.add(current.id)) {
                    return InheritanceValidation.Cycle(path.toList() + current.id)
                }
                val parentId = current.parentId ?: break
                current = byId[parentId] ?: return InheritanceValidation.MissingParent(current.id, parentId)
            }
        }
        return InheritanceValidation.Valid
    }

    fun validateLineage(themeId: ThemeId, definitions: List<ThemeDefinition>): InheritanceValidation {
        val graph = validate(definitions)
        if (graph !is InheritanceValidation.Valid) return graph

        val byId = definitions.associateBy { it.id }
        val seen = HashSet<ThemeId>()
        var current = byId[themeId] ?: return InheritanceValidation.UnknownTheme(themeId)
        while (true) {
            if (!seen.add(current.id)) {
                return InheritanceValidation.Cycle(seen.toList() + current.id)
            }
            val parentId = current.parentId ?: break
            current = byId[parentId] ?: return InheritanceValidation.MissingParent(current.id, parentId)
        }
        return InheritanceValidation.Valid
    }

    /** Returns lineage from the root ancestor down to [themeId]. */
    fun lineage(themeId: ThemeId, definitions: List<ThemeDefinition>): Result<List<ThemeDefinition>> = runCatching {
        validateLineage(themeId, definitions).asResult().getOrThrow()
        val byId = definitions.associateBy { it.id }
        val chain = ArrayDeque<ThemeDefinition>()
        var current = byId.getValue(themeId)
        while (true) {
            chain.addFirst(current)
            val parentId = current.parentId ?: break
            current = byId.getValue(parentId)
        }
        chain.toList()
    }
}
