package dev.aether.data.repository

import dev.aether.data.dao.AssetDao
import dev.aether.data.entity.AssetImportRecordEntity
import dev.aether.data.entity.AssetMetadataEntity
import dev.aether.data.entity.AssetReferenceEntity
import dev.aether.domain.AssetRepository
import dev.aether.model.AssetCategory
import dev.aether.model.AssetId
import dev.aether.model.AssetImportRecord
import dev.aether.model.AssetMetadata
import dev.aether.model.AssetOwnership
import dev.aether.model.AssetReference
import dev.aether.model.AssetState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class AssetRepositoryImpl(private val dao: AssetDao) : AssetRepository {
    override fun observeAssets(): Flow<List<AssetMetadata>> =
        dao.observeAssets().map { entities -> entities.map(AssetMetadataEntity::toDomain) }

    override suspend fun get(id: AssetId): AssetMetadata? = dao.get(id.value)?.toDomain()

    override suspend fun register(asset: AssetMetadata) = dao.upsert(asset.toEntity())

    override suspend fun addReference(reference: AssetReference) = dao.addReference(
        AssetReferenceEntity(
            assetId = reference.assetId.value,
            consumerType = reference.consumerType,
            consumerId = reference.consumerId,
        ),
    )

    override suspend fun removeReference(assetId: AssetId, consumerId: String) =
        dao.removeReference(assetId.value, consumerId)

    override suspend fun referenceCount(assetId: AssetId): Int = dao.referenceCount(assetId.value)

    override suspend fun markTrashed(id: AssetId) = dao.markTrashed(id.value)

    override suspend fun delete(id: AssetId) = dao.delete(id.value)

    override suspend fun recordImport(record: AssetImportRecord) =
        dao.recordImport(
            AssetImportRecordEntity(
                importId = record.importId,
                sourceName = record.sourceName,
                status = record.status.name,
                assetId = record.assetId?.value,
                createdAtMillis = record.createdAtMillis,
            ),
        )
}

private fun AssetMetadata.toEntity() = AssetMetadataEntity(
    id = id.value,
    category = category.name,
    displayName = displayName,
    relativePath = relativePath,
    byteSize = byteSize,
    checksumSha256 = checksumSha256,
    state = state.name,
    ownership = ownership.name,
    createdAtMillis = createdAtMillis,
)

private fun AssetMetadataEntity.toDomain() = AssetMetadata(
    id = AssetId(id),
    category = runCatching { AssetCategory.valueOf(category) }.getOrDefault(AssetCategory.OTHER),
    displayName = displayName,
    relativePath = relativePath,
    byteSize = byteSize,
    checksumSha256 = checksumSha256,
    state = runCatching { AssetState.valueOf(state) }.getOrDefault(AssetState.STAGED),
    ownership = runCatching { AssetOwnership.valueOf(ownership) }.getOrDefault(AssetOwnership.TEMPORARY),
    createdAtMillis = createdAtMillis,
)

