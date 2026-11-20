package dev.aether.theme

import dev.aether.model.ThemeId
import dev.aether.model.theme.ThemeDefinition

sealed interface InheritanceValidation {
    data object Valid : InheritanceValidation
    data class Cycle(val themeIds: List<ThemeId>) : InheritanceValidation
    data class MissingParent(val themeId: ThemeId, val parentId: ThemeId) : InheritanceValidation
}

/** Single-parent inheritance with cycle detection; a theme may never be its own ancestor. */
class ThemeInheritanceValidator {
    fun validate(definitions: List<ThemeDefinition>): InheritanceValidation {
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

    /** Returns lineage from the root ancestor down to [themeId]. */
    fun lineage(themeId: ThemeId, definitions: List<ThemeDefinition>): Result<List<ThemeDefinition>> = runCatching {
        val byId = definitions.associateBy { it.id }
        val chain = ArrayDeque<ThemeDefinition>()
        val seen = HashSet<ThemeId>()
        var current = byId[themeId] ?: error("Unknown theme: ${themeId.value}")
        while (true) {
            require(seen.add(current.id)) { "Inheritance cycle at ${current.id.value}" }
            chain.addFirst(current)
            val parentId = current.parentId ?: break
            current = byId[parentId] ?: error("Missing parent: ${parentId.value}")
        }
        chain.toList()
    }
}
