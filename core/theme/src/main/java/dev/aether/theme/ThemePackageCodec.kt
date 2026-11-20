package dev.aether.theme

import dev.aether.model.theme.ThemePackage
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** Portable themes are data-only JSON; unknown keys are rejected so code-like fields cannot hide. */
object ThemePackageCodec {
    private val json = Json {
        ignoreUnknownKeys = false
        explicitNulls = false
    }

    fun decode(raw: String): Result<ThemePackage> = runCatching {
        json.decodeFromString<ThemePackage>(raw)
    }

    fun encode(themePackage: ThemePackage): String = json.encodeToString(themePackage)
}

object ThemePackageValidator {
    fun validate(themePackage: ThemePackage): Result<Unit> = runCatching {
        require(themePackage.formatVersion == ThemePackage.FORMAT_VERSION) {
            "Unsupported theme format: ${themePackage.formatVersion}"
        }
        require(themePackage.definition.name.isNotBlank()) { "Theme name is blank" }
        require(themePackage.definition.parentId != themePackage.definition.id) {
            "A theme cannot inherit from itself"
        }
    }
}
