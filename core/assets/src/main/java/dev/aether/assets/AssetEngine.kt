package dev.aether.assets

import dev.aether.domain.AssetRepository
import dev.aether.model.AssetCategory
import dev.aether.model.AssetId
import dev.aether.model.AssetImportRecord
import dev.aether.model.AssetMetadata
import dev.aether.model.AssetOwnership
import dev.aether.model.AssetState
import dev.aether.model.ImportStatus
import java.io.File
import java.security.MessageDigest

data class AssetRegistration(
    val importId: String,
    val assetId: AssetId,
    val category: AssetCategory,
    val displayName: String,
    val stagedRelativePath: String,
    val extension: String,
    val ownership: AssetOwnership,
)

/** Coordinates the storage -> register -> publish lifecycle. Import UI and processing stay out of scope. */
class AssetEngine(
    private val storage: AssetStorage,
    private val repository: AssetRepository,
) {
    suspend fun register(registration: AssetRegistration): Result<AssetMetadata> = runCatching {
        val relativePath = storage.relativeOriginalPath(
            registration.assetId,
            registration.category,
            registration.extension,
        ).getOrThrow()
        val original = storage.promote(registration.stagedRelativePath, relativePath).getOrThrow()
        val metadata = AssetMetadata(
            id = registration.assetId,
            category = registration.category,
            displayName = registration.displayName,
            relativePath = relativePath,
            byteSize = original.length(),
            checksumSha256 = checksum(original),
            state = AssetState.REGISTERED,
            ownership = registration.ownership,
            createdAtMillis = System.currentTimeMillis(),
        )
        repository.recordImport(
            AssetImportRecord(
                importId = registration.importId,
                sourceName = registration.displayName,
                status = ImportStatus.REGISTERED,
                assetId = registration.assetId,
                createdAtMillis = metadata.createdAtMillis,
            ),
        )
        repository.register(metadata)
        metadata
    }

    suspend fun delete(assetId: AssetId): Result<Unit> = runCatching {
        val metadata = repository.get(assetId) ?: error("Unknown asset: ${assetId.value}")
        require(repository.referenceCount(assetId) == 0) { "Asset is still referenced: ${assetId.value}" }
        if (metadata.ownership == AssetOwnership.TEMPORARY) {
            storage.delete(metadata.relativePath).getOrThrow()
            repository.delete(assetId)
        } else {
            storage.moveToTrash(metadata.relativePath).getOrThrow()
            repository.markTrashed(assetId)
        }
    }

    private fun checksum(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
