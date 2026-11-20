package dev.aether.model

enum class AssetCategory { WALLPAPER, ICON, THEME_IMAGE, FONT, THUMBNAIL, OTHER }

enum class AssetState { STAGED, REGISTERED, TRASHED }

enum class AssetOwnership { TEMPORARY, USER_MANAGED }

data class AssetMetadata(
    val id: AssetId,
    val category: AssetCategory,
    val displayName: String,
    val relativePath: String,
    val byteSize: Long,
    val checksumSha256: String,
    val state: AssetState,
    val ownership: AssetOwnership,
    val createdAtMillis: Long,
)

data class AssetReference(
    val assetId: AssetId,
    val consumerType: String,
    val consumerId: String,
)

data class AssetImportRecord(
    val importId: String,
    val sourceName: String,
    val status: ImportStatus,
    val assetId: AssetId? = null,
    val createdAtMillis: Long = 0L,
)

enum class ImportStatus { RECEIVED, STAGED, REGISTERED, FAILED }
