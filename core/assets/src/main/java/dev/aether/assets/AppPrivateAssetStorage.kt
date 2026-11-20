package dev.aether.assets

import dev.aether.model.AssetCategory
import dev.aether.model.AssetId
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

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
        runCatching {
            val source = resolve(stagedRelativePath).getOrThrow()
            require(source.isFile) { "Staged file is missing: $stagedRelativePath" }
            val target = resolve(originalRelativePath).getOrThrow()
            target.parentFile?.mkdirs()
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING).toFile()
        }

    override fun moveToTrash(relativePath: String): Result<File> = runCatching {
        val source = resolve(relativePath).getOrThrow()
        require(source.isFile) { "File is missing: $relativePath" }
        val name = "${UUID.randomUUID()}-${source.name}"
        val target = resolve("trash/$name").getOrThrow()
        Files.move(source.toPath(), target.toPath()).toFile()
    }

    override fun delete(relativePath: String): Result<Unit> = runCatching {
        val file = resolve(relativePath).getOrThrow()
        if (file.exists()) {
            require(file.delete()) { "Could not delete: $relativePath" }
        }
    }

    override fun clearCache() {
        resolve("cache").getOrNull()?.listFiles()?.forEach { it.deleteRecursively() }
    }
}
