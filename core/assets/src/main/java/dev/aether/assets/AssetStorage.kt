package dev.aether.assets

import dev.aether.model.AssetCategory
import dev.aether.model.AssetId
import java.io.File

/** Filesystem-only abstraction; metadata stays in Room and never holds absolute paths. */
interface AssetStorage {
    fun initialize()
    fun stage(importId: String, category: AssetCategory, fileName: String, byteSize: Long): Result<File>
    fun stagedRelativePath(importId: String, fileName: String): Result<String>
    fun originalPath(assetId: AssetId, category: AssetCategory, extension: String): Result<File>
    fun relativeOriginalPath(assetId: AssetId, category: AssetCategory, extension: String): Result<String>
    fun thumbnailPath(assetId: AssetId): Result<File>
    fun resolve(relativePath: String): Result<File>
    fun promote(stagedRelativePath: String, originalRelativePath: String): Result<File>
    fun moveToTrash(relativePath: String): Result<File>
    fun delete(relativePath: String): Result<Unit>
    fun clearCache()
}
