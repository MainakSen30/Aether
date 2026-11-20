package dev.aether.model

import kotlinx.serialization.Serializable

private val SAFE_ID = Regex("[A-Za-z0-9._-]{1,128}")

@Serializable
@JvmInline
value class ThemeId(val value: String) {
    init {
        require(SAFE_ID.matches(value)) { "Invalid theme id: $value" }
    }

    companion object {
        fun of(value: String): Result<ThemeId> = runCatching { ThemeId(value) }
    }
}

@Serializable
@JvmInline
value class AssetId(val value: String) {
    init {
        require(SAFE_ID.matches(value)) { "Invalid asset id: $value" }
    }

    companion object {
        fun of(value: String): Result<AssetId> = runCatching { AssetId(value) }
    }
}
