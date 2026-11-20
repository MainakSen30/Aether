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
        AssetCategory.ICON to setOf("png", "webp", "svg"),
        AssetCategory.THEME_IMAGE to setOf("jpg", "jpeg", "png", "webp"),
        AssetCategory.FONT to setOf("ttf", "otf"),
        AssetCategory.THUMBNAIL to setOf("jpg", "jpeg", "png", "webp"),
        AssetCategory.OTHER to setOf("txt", "json"),
    )

    fun safeFileName(category: AssetCategory, fileName: String): Result<String> = runCatching {
        require(fileName.length <= 128 && !fileName.contains('/') && !fileName.contains('\\')) {
            "Unsafe file name: $fileName"
        }
        val extension = fileName.substringAfterLast('.', "").lowercase()
        require(extension in ALLOWED_EXTENSIONS.getValue(category)) {
            "Unsupported file type for $category: $extension"
        }
        val stem = fileName.substringBeforeLast('.')
        require(SAFE_SEGMENT.matches(stem)) { "Unsafe file name: $fileName" }
        "$stem.$extension"
    }

    fun safeSegment(value: String): Result<String> = runCatching {
        require(SAFE_SEGMENT.matches(value)) { "Unsafe path segment: $value" }
        value
    }

    /** Resolves an app-relative path and proves it stays under [root], including through symlinks. */
    fun resolve(root: File, relativePath: String): Result<File> = runCatching {
        require(relativePath.isNotBlank() && !File(relativePath).isAbsolute) {
            "Path must be relative: $relativePath"
        }
        require(relativePath.split('/', '\\').none { it == ".." || it == "." }) {
            "Path traversal is not allowed: $relativePath"
        }
        val base = root.canonicalFile.toPath()
        val candidate = File(root, relativePath).canonicalFile.toPath()
        require(candidate.startsWith(base)) { "Path escapes asset root: $relativePath" }
        candidate.toFile()
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
        require(name.startsWith("$safeId.")) { "Trash path does not match asset: $trashRelativePath" }
        val extension = safeSegment(name.substringAfterLast('.')).getOrThrow()
        originalRelativePath(safeId, category, extension).getOrThrow()
    }
}
