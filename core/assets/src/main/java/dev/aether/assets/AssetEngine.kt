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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class AssetRegistration(
    val importId: String,
    val assetId: AssetId,
    val category: AssetCategory,
    val displayName: String,
    val stagedRelativePath: String,
    val extension: String,
    val declaredByteSize: Long,
    val ownership: AssetOwnership,
)

/**
 * Coordinates staging, registration, trash, restore, deletion and recovery.
 * Filesystem and Room cannot share a transaction, so every cross-boundary write has a marker
 * state and a compensating recovery step.
 */
class AssetEngine(
    private val storage: AssetStorage,
    private val repository: AssetRepository,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun register(registration: AssetRegistration): Result<AssetMetadata> = withContext(io) {
        runCatching { registerAsset(registration) }
            .onFailure { failure ->
                if (failure is AssetValidationException) {
                    runCatching {
                        repository.recordImport(
                            AssetImportRecord(
                                importId = registration.importId,
                                sourceName = registration.displayName,
                                status = ImportStatus.FAILED,
                                assetId = registration.assetId,
                                createdAtMillis = System.currentTimeMillis(),
                            ),
                        )
                    }
                }
            }
    }

    /** User-facing delete: temporary assets are erased, user assets become recoverable trash. */
    suspend fun delete(assetId: AssetId): Result<Unit> = withContext(io) {
        runCatching { deleteAsset(assetId) }
    }

    suspend fun restore(assetId: AssetId): Result<Unit> = withContext(io) {
        runCatching { restoreAsset(assetId) }
    }

    suspend fun permanentlyDelete(assetId: AssetId): Result<Unit> = withContext(io) {
        runCatching { permanentlyDeleteAsset(assetId) }
    }

    /** Replays interrupted operations from their marker states. Safe to repeat. */
    suspend fun recover(): Result<Unit> = withContext(io) {
        runCatching {
            val failures = mutableListOf<Throwable>()
            repository.getAll().forEach { metadata ->
                runCatching { recoverAsset(metadata.id) }.exceptionOrNull()?.let(failures::add)
            }
            if (failures.isNotEmpty()) {
                throw failures.first().apply { failures.drop(1).forEach(::addSuppressed) }
            }
        }
    }

    suspend fun cleanupAbandonedStaging(
        maxAgeMillis: Long = DEFAULT_STAGING_RETENTION_MILLIS,
    ): Result<Int> = withContext(io) {
        runCatching {
            repository.getAll()
                .filter { it.state == AssetState.STAGED }
                .forEach { metadata ->
                    val file = storage.resolve(metadata.relativePath).getOrNull()
                    if (file == null || !file.isFile) {
                        repository.delete(metadata.id)
                        repository.setImportStatus(metadata.id, ImportStatus.FAILED)
                    }
                }
            storage.cleanupAbandonedStaging(maxAgeMillis).getOrThrow()
        }
    }

    suspend fun cleanupTemporary(): Result<Int> = withContext(io) {
        runCatching {
            var removed = 0
            repository.getAll()
                .filter { it.ownership == AssetOwnership.TEMPORARY && it.state == AssetState.REGISTERED }
                .forEach { metadata ->
                    if (repository.referenceCount(metadata.id) == 0) {
                        deletePermanently(metadata)
                        removed++
                    }
                }
            removed
        }
    }

    private suspend fun registerAsset(registration: AssetRegistration): AssetMetadata {
        recoverAsset(registration.assetId)
        repository.get(registration.assetId)?.let { existing ->
            val existingFile = storage.resolve(existing.relativePath).getOrThrow()
            if (existing.state == AssetState.REGISTERED && existingFile.isFile) {
                AssetValidator.verifyIntegrity(existing, existingFile).getOrThrow()
                return existing
            }
            error("Asset id already exists in state ${existing.state}")
        }

        val stagedFile = storage.resolve(registration.stagedRelativePath).getOrThrow()
        if (!stagedFile.isFile) {
            throw AssetValidationException(
                AssetValidationError.InvalidAssetPath(registration.stagedRelativePath),
            )
        }
        val inspection = AssetValidator.validate(registration, stagedFile).getOrThrow()
        val originalPath = storage.relativeOriginalPath(
            registration.assetId,
            registration.category,
            registration.extension,
        ).getOrThrow()
        require(!storage.resolve(originalPath).getOrThrow().exists()) {
            "Original location is occupied: $originalPath"
        }

        val now = System.currentTimeMillis()
        val pending = AssetMetadata(
            id = registration.assetId,
            category = registration.category,
            displayName = registration.displayName,
            relativePath = originalPath,
            byteSize = inspection.byteSize,
            checksumSha256 = inspection.checksumSha256,
            state = AssetState.STAGED,
            ownership = registration.ownership,
            createdAtMillis = now,
        )
        val record = AssetImportRecord(
            importId = registration.importId,
            sourceName = registration.displayName,
            status = ImportStatus.STAGED,
            assetId = registration.assetId,
            createdAtMillis = now,
        )
        repository.registerWithImport(pending, record)

        try {
            storage.promote(registration.stagedRelativePath, originalPath).getOrThrow()
        } catch (failure: Throwable) {
            runCatching { repository.delete(registration.assetId) }.addSuppressed(failure)
            runCatching { repository.setImportStatus(registration.assetId, ImportStatus.FAILED) }
                .addSuppressed(failure)
            throw failure
        }

        val registered = pending.copy(state = AssetState.REGISTERED)
        // A failure here leaves STAGED metadata pointing at an existing file; recover() finishes it.
        repository.update(registered, ImportStatus.REGISTERED)
        return registered
    }

    private suspend fun deleteAsset(assetId: AssetId) {
        recoverAsset(assetId)
        val metadata = repository.get(assetId) ?: return
        require(repository.referenceCount(assetId) == 0) { "Asset is still referenced: ${assetId.value}" }
        when (metadata.ownership) {
            AssetOwnership.TEMPORARY -> deletePermanently(metadata)
            AssetOwnership.USER_MANAGED -> when (metadata.state) {
                AssetState.REGISTERED -> moveToTrash(metadata)
                AssetState.TRASHED -> Unit
                else -> error("Unexpected asset state: ${metadata.state}")
            }
        }
    }

    private suspend fun restoreAsset(assetId: AssetId) {
        recoverAsset(assetId)
        val metadata = repository.get(assetId) ?: error("Unknown asset: ${assetId.value}")
        if (metadata.state == AssetState.REGISTERED &&
            storage.resolve(metadata.relativePath).getOrThrow().isFile
        ) {
            return
        }
        require(metadata.state == AssetState.TRASHED) { "Asset is not in trash: ${metadata.state}" }
        val originalPath = AssetPaths.originalPathFromTrash(
            assetId.value,
            metadata.category,
            metadata.relativePath,
        ).getOrThrow()
        require(!storage.resolve(originalPath).getOrThrow().exists()) {
            "Original location is occupied: $originalPath"
        }

        repository.update(metadata.copy(state = AssetState.PENDING_RESTORE))
        try {
            storage.move(metadata.relativePath, originalPath).getOrThrow()
        } catch (failure: Throwable) {
            runCatching { repository.update(metadata) }.addSuppressed(failure)
            throw failure
        }
        // A failure here leaves PENDING_RESTORE metadata; recover() finishes or reverts it.
        repository.update(
            metadata.copy(state = AssetState.REGISTERED, relativePath = originalPath),
            ImportStatus.REGISTERED,
        )
    }

    private suspend fun permanentlyDeleteAsset(assetId: AssetId) {
        recoverAsset(assetId)
        val metadata = repository.get(assetId) ?: return
        require(repository.referenceCount(assetId) == 0) { "Asset is still referenced: ${assetId.value}" }
        deletePermanently(metadata)
    }

    private suspend fun deletePermanently(metadata: AssetMetadata) {
        require(
            metadata.state == AssetState.REGISTERED ||
                metadata.state == AssetState.TRASHED ||
                metadata.state == AssetState.PENDING_DELETE,
        ) { "Unexpected asset state: ${metadata.state}" }
        repository.update(metadata.copy(state = AssetState.PENDING_DELETE))
        val failure = runCatching { storage.delete(metadata.relativePath).getOrThrow() }.exceptionOrNull()
        if (failure != null) {
            runCatching { repository.update(metadata) }.addSuppressed(failure)
            throw failure
        }
        // If this fails, PENDING_DELETE remains and recover() finishes the database deletion.
        repository.delete(metadata.id)
    }

    private suspend fun moveToTrash(metadata: AssetMetadata) {
        val trashPath = AssetPaths.trashRelativePath(
            metadata.id.value,
            extensionOf(metadata.relativePath),
        ).getOrThrow()
        require(!storage.resolve(trashPath).getOrThrow().exists()) {
            "Trash target is occupied: $trashPath"
        }

        repository.update(metadata.copy(state = AssetState.PENDING_TRASH))
        try {
            storage.move(metadata.relativePath, trashPath).getOrThrow()
        } catch (failure: Throwable) {
            runCatching { repository.update(metadata) }.addSuppressed(failure)
            throw failure
        }
        // A failure here leaves PENDING_TRASH metadata; recover() finishes or reverts it.
        repository.update(metadata.copy(state = AssetState.TRASHED, relativePath = trashPath))
    }

    private suspend fun recoverAsset(assetId: AssetId) {
        val metadata = repository.get(assetId) ?: return
        val currentFile = storage.resolve(metadata.relativePath).getOrNull()
        when (metadata.state) {
            AssetState.STAGED -> {
                if (currentFile?.isFile == true) {
                    AssetValidator.verifyIntegrity(metadata, currentFile).getOrThrow()
                    repository.update(metadata.copy(state = AssetState.REGISTERED), ImportStatus.REGISTERED)
                } else {
                    discardMissingAsset(metadata)
                }
            }
            AssetState.REGISTERED -> {
                if (currentFile?.isFile != true) {
                    discardMissingAsset(metadata)
                } else {
                    AssetValidator.verifyIntegrity(metadata, currentFile).getOrThrow()
                }
            }
            AssetState.PENDING_TRASH -> recoverPendingTrash(metadata, currentFile)
            AssetState.TRASHED -> {
                if (currentFile?.isFile != true) repository.delete(assetId)
            }
            AssetState.PENDING_RESTORE -> recoverPendingRestore(metadata, currentFile)
            AssetState.PENDING_DELETE -> {
                if (currentFile?.isFile == true) storage.delete(metadata.relativePath).getOrThrow()
                repository.delete(assetId)
            }
        }
    }

    private suspend fun recoverPendingTrash(metadata: AssetMetadata, currentFile: File?) {
        val trashPath = AssetPaths.trashRelativePath(
            metadata.id.value,
            extensionOf(metadata.relativePath),
        ).getOrThrow()
        val trashedFile = storage.resolve(trashPath).getOrNull()
        when {
            currentFile?.isFile == true && trashedFile?.exists() != true ->
                repository.update(metadata.copy(state = AssetState.REGISTERED))
            currentFile?.exists() != true && trashedFile?.isFile == true ->
                repository.update(metadata.copy(state = AssetState.TRASHED, relativePath = trashPath))
            currentFile?.isFile == true && trashedFile?.isFile == true ->
                error("Trash target is occupied during recovery: $trashPath")
            else -> error("Cannot recover pending trash for ${metadata.id.value}")
        }
    }

    private suspend fun recoverPendingRestore(metadata: AssetMetadata, currentFile: File?) {
        val originalPath = AssetPaths.originalPathFromTrash(
            metadata.id.value,
            metadata.category,
            metadata.relativePath,
        ).getOrThrow()
        val originalFile = storage.resolve(originalPath).getOrNull()
        when {
            currentFile?.isFile == true && originalFile?.exists() != true ->
                repository.update(metadata.copy(state = AssetState.TRASHED))
            currentFile?.exists() != true && originalFile?.isFile == true ->
                repository.update(
                    metadata.copy(state = AssetState.REGISTERED, relativePath = originalPath),
                    ImportStatus.REGISTERED,
                )
            currentFile?.isFile == true && originalFile?.isFile == true ->
                error("Original location is occupied during recovery: $originalPath")
            else -> error("Cannot recover pending restore for ${metadata.id.value}")
        }
    }

    private suspend fun discardMissingAsset(metadata: AssetMetadata) {
        repository.delete(metadata.id)
        runCatching { repository.setImportStatus(metadata.id, ImportStatus.FAILED) }
    }

    private fun extensionOf(relativePath: String): String {
        val name = relativePath.substringAfterLast('/')
        require(name.contains('.') && !name.endsWith('.')) {
            "Asset path has no extension: $relativePath"
        }
        return name.substringAfterLast('.')
    }

    private fun Result<*>.addSuppressed(failure: Throwable) {
        exceptionOrNull()?.let { if (it !== failure) failure.addSuppressed(it) }
    }

    companion object {
        const val DEFAULT_STAGING_RETENTION_MILLIS = 24L * 60 * 60 * 1000
    }
}
