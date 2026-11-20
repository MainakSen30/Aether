package dev.aether.domain

import dev.aether.model.AssetId
import dev.aether.model.AssetImportRecord
import dev.aether.model.AssetMetadata
import dev.aether.model.AssetReference
import kotlinx.coroutines.flow.Flow

interface AssetRepository {
    fun observeAssets(): Flow<List<AssetMetadata>>
    suspend fun get(id: AssetId): AssetMetadata?
    suspend fun register(asset: AssetMetadata)
    suspend fun addReference(reference: AssetReference)
    suspend fun removeReference(assetId: AssetId, consumerId: String)
    suspend fun referenceCount(assetId: AssetId): Int
    suspend fun markTrashed(id: AssetId)
    suspend fun delete(id: AssetId)
    suspend fun recordImport(record: AssetImportRecord)
}
