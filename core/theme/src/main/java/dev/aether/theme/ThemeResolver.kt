package dev.aether.theme

import dev.aether.model.ThemeId
import dev.aether.model.theme.AetherColors
import dev.aether.model.theme.ComponentOverride
import dev.aether.model.theme.ThemeDefinition
import dev.aether.model.theme.ThemeTokens

data class ResolvedTheme(
    val id: ThemeId,
    val tokens: ThemeTokens,
    val lineage: List<ThemeId>,
)

/** Platform fallbacks are the last resort when no theme or design-system token exists. */
enum class ColorKey(val fallback: Long) {
    BACKGROUND(0xFF000000),
    SURFACE(0xFF1E1E1E),
    FOREGROUND(0xFFFFFFFF),
    MUTED_FOREGROUND(0xFFB3B3B3),
    PRIMARY(0xFFBB86FC),
    ON_PRIMARY(0xFF000000),
    OUTLINE(0xFF3C3C3C),
}

/**
 * Resolution order: component override, active theme token, design-system default, platform fallback.
 * Presets and overrides merge along the single-parent lineage (child wins).
 */
class ThemeResolver(private val defaults: ThemeTokens) {
    private val inheritance = ThemeInheritanceValidator()

    fun resolve(theme: ThemeDefinition, definitions: List<ThemeDefinition>): ResolvedTheme {
        val lineage = inheritance.lineage(theme.id, definitions + theme).getOrThrow()
        val presets = linkedMapOf<String, String>()
        val overrides = linkedMapOf<String, ComponentOverride>()
        lineage.forEach {
            presets.putAll(it.tokens.componentPresets)
            overrides.putAll(it.tokens.componentOverrides)
        }
        val tokens = lineage.last().tokens.copy(
            colors = AetherColors(
                background = lineage.mapNotNull { it.tokens.colors.background }.lastOrNull(),
                surface = lineage.mapNotNull { it.tokens.colors.surface }.lastOrNull(),
                foreground = lineage.mapNotNull { it.tokens.colors.foreground }.lastOrNull(),
                mutedForeground = lineage.mapNotNull { it.tokens.colors.mutedForeground }.lastOrNull(),
                primary = lineage.mapNotNull { it.tokens.colors.primary }.lastOrNull(),
                onPrimary = lineage.mapNotNull { it.tokens.colors.onPrimary }.lastOrNull(),
                outline = lineage.mapNotNull { it.tokens.colors.outline }.lastOrNull(),
            ),
            componentPresets = presets.toMap(),
            componentOverrides = overrides.toMap(),
        )
        return ResolvedTheme(theme.id, tokens, lineage.map { it.id })
    }

    fun color(resolved: ResolvedTheme, key: ColorKey, componentId: String? = null): Long {
        val component = componentId?.let { resolved.tokens.componentOverrides[it]?.colors }
        val overridden = when (key) {
            ColorKey.BACKGROUND -> component?.background
            ColorKey.SURFACE -> component?.surface
            ColorKey.FOREGROUND -> component?.foreground
            else -> null
        }
        if (overridden != null) return overridden

        val themed = value(resolved.tokens, key)
        if (themed != null) return themed

        return value(defaults, key) ?: key.fallback
    }

    private fun value(tokens: ThemeTokens, key: ColorKey): Long? = when (key) {
        ColorKey.BACKGROUND -> tokens.colors.background
        ColorKey.SURFACE -> tokens.colors.surface
        ColorKey.FOREGROUND -> tokens.colors.foreground
        ColorKey.MUTED_FOREGROUND -> tokens.colors.mutedForeground
        ColorKey.PRIMARY -> tokens.colors.primary
        ColorKey.ON_PRIMARY -> tokens.colors.onPrimary
        ColorKey.OUTLINE -> tokens.colors.outline
    }
}
