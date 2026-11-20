package dev.aether.assets

import dev.aether.model.AssetCategory
import dev.aether.model.AssetId
import java.io.File
import java.nio.file.Files

/** Root must come from Context.filesDir, never from a hardcoded or external path. */
class AppPrivateAssetStorage(private val root: File) : AssetStorage {
    override fun initialize() {
        AssetPaths.DIRECTORIES.forEach { File(root, it).mkdirs() }
    }

    override fun stage(
        importId: String,
        category: AssetCategory,
        fileName: String,
        byteSize: Long,
    ): Result<File> = runCatching {
        require(byteSize in 0..AssetPaths.MAX_ASSET_BYTES) { "Asset size out of bounds: $byteSize" }
        val safeImportId = AssetPaths.safeSegment(importId).getOrThrow()
        val safeName = AssetPaths.safeFileName(category, fileName).getOrThrow()
        resolve("staging/$safeImportId/$safeName").getOrThrow().also {
            it.parentFile?.mkdirs()
        }
    }

    override fun stagedRelativePath(importId: String, fileName: String): Result<String> = runCatching {
        val safeImportId = AssetPaths.safeSegment(importId).getOrThrow()
        val safeName = AssetPaths.safeSegment(fileName).getOrThrow()
        "staging/$safeImportId/$safeName"
    }

    override fun originalPath(
        assetId: AssetId,
        category: AssetCategory,
        extension: String,
    ): Result<File> = runCatching {
        resolve(relativeOriginalPath(assetId, category, extension).getOrThrow()).getOrThrow()
    }

    override fun relativeOriginalPath(
        assetId: AssetId,
        category: AssetCategory,
        extension: String,
    ): Result<String> = AssetPaths.originalRelativePath(assetId.value, category, extension)

    override fun thumbnailPath(assetId: AssetId): Result<File> = runCatching {
        val safeId = AssetPaths.safeSegment(assetId.value).getOrThrow()
        resolve("thumbnails/$safeId.webp").getOrThrow()
    }

    override fun resolve(relativePath: String): Result<File> = AssetPaths.resolve(root, relativePath)

    override fun promote(stagedRelativePath: String, originalRelativePath: String): Result<File> =
        move(stagedRelativePath, originalRelativePath)

    override fun moveToTrash(relativePath: String): Result<File> = runCatching {
        val (assetId, extension) = parseOriginalPath(relativePath)
        move(relativePath, AssetPaths.trashRelativePath(assetId, extension).getOrThrow()).getOrThrow()
    }

    override fun move(sourceRelativePath: String, targetRelativePath: String): Result<File> = runCatching {
        val source = resolve(sourceRelativePath).getOrThrow()
        require(source.isFile) { "Source file is missing: $sourceRelativePath" }
        val target = resolve(targetRelativePath).getOrThrow()
        require(!target.exists()) { "Target already exists: $targetRelativePath" }
        target.parentFile?.mkdirs()
        Files.move(source.toPath(), target.toPath()).toFile()
    }

    override fun delete(relativePath: String): Result<Unit> = runCatching {
        val file = resolve(relativePath).getOrThrow()
        if (file.exists()) {
            require(file.delete()) { "Could not delete: $relativePath" }
        }
    }

    override fun cleanupAbandonedStaging(maxAgeMillis: Long): Result<Int> = runCatching {
        val cutoff = System.currentTimeMillis() - maxAgeMillis
        val staging = resolve("staging").getOrThrow()
        var removed = 0
        staging.listFiles()?.forEach { entry ->
            if (entry.lastModified() < cutoff) {
                require(entry.deleteRecursively()) { "Could not clean staging entry: ${entry.name}" }
                removed++
            }
        }
        removed
    }

    override fun clearCache() {
        resolve("cache").getOrNull()?.listFiles()?.forEach { it.deleteRecursively() }
    }

    private fun parseOriginalPath(relativePath: String): Pair<String, String> {
        val parts = relativePath.split('/')
        require(parts.size == 3 && parts.first() == "originals") {
            "Not an original asset path: $relativePath"
        }
        val fileName = parts.last()
        require(fileName.contains('.')) { "Original path has no extension: $relativePath" }
        val assetId = AssetPaths.safeSegment(fileName.substringBeforeLast('.')).getOrThrow()
        val extension = AssetPaths.safeSegment(fileName.substringAfterLast('.')).getOrThrow()
        return assetId to extension
    }
}
