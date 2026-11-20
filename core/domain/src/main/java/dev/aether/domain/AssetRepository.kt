package dev.aether.domain

import dev.aether.model.AssetId
import dev.aether.model.AssetImportRecord
import dev.aether.model.AssetMetadata
import dev.aether.model.AssetReference
import dev.aether.model.ImportStatus
import kotlinx.coroutines.flow.Flow

interface AssetRepository {
    fun observeAssets(): Flow<List<AssetMetadata>>
    suspend fun getAll(): List<AssetMetadata>
    suspend fun get(id: AssetId): AssetMetadata?
    suspend fun register(asset: AssetMetadata)
    suspend fun registerWithImport(asset: AssetMetadata, record: AssetImportRecord)
    suspend fun update(asset: AssetMetadata, importStatus: ImportStatus? = null)
    suspend fun addReference(reference: AssetReference)
    suspend fun removeReference(reference: AssetReference)
    suspend fun referenceCount(assetId: AssetId): Int
    suspend fun delete(id: AssetId)
    suspend fun recordImport(record: AssetImportRecord)
    suspend fun setImportStatus(assetId: AssetId, status: ImportStatus)
}
