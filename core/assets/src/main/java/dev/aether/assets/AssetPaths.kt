package dev.aether.assets

import dev.aether.model.AssetCategory
import java.io.File

object AssetPaths {
    const val ROOT_NAME = "aether"
    const val MAX_ASSET_BYTES = 128L * 1024 * 1024

    val DIRECTORIES = listOf("staging", "originals", "thumbnails", "cache", "trash")

    private val SAFE_SEGMENT = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")

    private val ALLOWED_EXTENSIONS = mapOf(
        AssetCategory.WALLPAPER to setOf("jpg", "jpeg", "png", "webp"),
        AssetCategory.ICON to setOf("png", "webp"),
        AssetCategory.THEME_IMAGE to setOf("jpg", "jpeg", "png", "webp"),
        AssetCategory.FONT to setOf("ttf", "otf"),
        AssetCategory.THUMBNAIL to setOf("jpg", "jpeg", "png", "webp"),
        AssetCategory.OTHER to setOf("txt", "json"),
    )

    fun allowedExtensions(category: AssetCategory): Set<String> = ALLOWED_EXTENSIONS.getValue(category)

    fun requiresContent(category: AssetCategory): Boolean = true

    fun safeFileName(category: AssetCategory, fileName: String): Result<String> = runCatching {
        if (fileName.length > 128 || fileName.contains('/') || fileName.contains('\\')) {
            throw AssetValidationException(AssetValidationError.InvalidAssetPath(fileName))
        }
        val extension = fileName.substringAfterLast('.', "").lowercase()
        if (extension == "svg") throw AssetValidationException(AssetValidationError.UnsafeSvg)
        if (extension !in allowedExtensions(category)) {
            throw AssetValidationException(AssetValidationError.UnsupportedAssetType(category.name, extension))
        }
        val stem = fileName.substringBeforeLast('.')
        if (!SAFE_SEGMENT.matches(stem)) {
            throw AssetValidationException(AssetValidationError.InvalidAssetPath(fileName))
        }
        "$stem.$extension"
    }

    fun safeSegment(value: String): Result<String> = runCatching {
        if (!SAFE_SEGMENT.matches(value)) {
            throw AssetValidationException(AssetValidationError.InvalidAssetPath(value))
        }
        value
    }

    /** Resolves an app-relative path and proves it stays under [root], including through symlinks. */
    fun resolve(root: File, relativePath: String): Result<File> = runCatching {
        if (relativePath.isBlank() || File(relativePath).isAbsolute || relativePath.contains('\\')) {
            throw AssetValidationException(AssetValidationError.InvalidAssetPath(relativePath))
        }
        val segments = relativePath.split('/')
        if (segments.any { it.isEmpty() || it == "." || it == ".." || !SAFE_SEGMENT.matches(it) }) {
            throw AssetValidationException(AssetValidationError.InvalidAssetPath(relativePath))
        }
        val base = root.canonicalFile.toPath()
        val candidate = File(root, relativePath).canonicalFile.toPath()
        if (!candidate.startsWith(base)) {
            throw AssetValidationException(AssetValidationError.InvalidAssetPath(relativePath))
        }
        candidate.toFile()
    }

    fun extensionOf(relativePath: String): Result<String> = runCatching {
        val name = relativePath.substringAfterLast('/')
        if (!name.contains('.') || name.endsWith('.')) {
            throw AssetValidationException(AssetValidationError.InvalidAssetPath(relativePath))
        }
        safeSegment(name.substringAfterLast('.').lowercase()).getOrThrow()
    }

    fun originalRelativePath(assetId: String, category: AssetCategory, extension: String): Result<String> =
        runCatching {
            val safeId = safeSegment(assetId).getOrThrow()
            val safeExtension = safeSegment(extension).getOrThrow()
            "originals/${category.name.lowercase()}/$safeId.$safeExtension"
        }

    /** Stable trash name derived from the asset ID, so restoration never needs an absolute path. */
    fun trashRelativePath(assetId: String, extension: String): Result<String> = runCatching {
        "trash/${safeSegment(assetId).getOrThrow()}.${safeSegment(extension).getOrThrow()}"
    }

    /** Reconstructs the canonical original path from metadata that is already in the trash. */
    fun originalPathFromTrash(
        assetId: String,
        category: AssetCategory,
        trashRelativePath: String,
    ): Result<String> = runCatching {
        val name = trashRelativePath.substringAfterLast('/')
        val safeId = safeSegment(assetId).getOrThrow()
        if (!name.startsWith("$safeId.")) {
            throw AssetValidationException(AssetValidationError.InvalidAssetPath(trashRelativePath))
        }
        val extension = safeSegment(name.substringAfterLast('.')).getOrThrow()
        originalRelativePath(safeId, category, extension).getOrThrow()
    }
}
