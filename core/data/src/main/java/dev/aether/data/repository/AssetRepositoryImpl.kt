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
import dev.aether.model.ImportStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class AssetRepositoryImpl(private val dao: AssetDao) : AssetRepository {
    override fun observeAssets(): Flow<List<AssetMetadata>> =
        dao.observeAssets().map { entities -> entities.map(AssetMetadataEntity::toDomain) }

    override suspend fun getAll(): List<AssetMetadata> = dao.getAll().map(AssetMetadataEntity::toDomain)

    override suspend fun get(id: AssetId): AssetMetadata? = dao.get(id.value)?.toDomain()

    override suspend fun register(asset: AssetMetadata) = dao.upsert(asset.toEntity())

    override suspend fun registerWithImport(asset: AssetMetadata, record: AssetImportRecord) =
        dao.upsertWithImport(asset.toEntity(), record.toEntity())

    override suspend fun update(asset: AssetMetadata, importStatus: ImportStatus?) {
        if (importStatus == null) {
            dao.upsert(asset.toEntity())
        } else {
            dao.updateWithImportStatus(asset.toEntity(), importStatus.name)
        }
    }

    override suspend fun addReference(reference: AssetReference) = dao.addReference(
        AssetReferenceEntity(
            assetId = reference.assetId.value,
            consumerType = reference.consumerType,
            consumerId = reference.consumerId,
        ),
    )

    override suspend fun removeReference(reference: AssetReference) = dao.removeReference(
        reference.assetId.value,
        reference.consumerType,
        reference.consumerId,
    )

    override suspend fun referenceCount(assetId: AssetId): Int = dao.referenceCount(assetId.value)

    override suspend fun delete(id: AssetId) = dao.delete(id.value)

    override suspend fun recordImport(record: AssetImportRecord) = dao.upsertImport(record.toEntity())

    override suspend fun setImportStatus(assetId: AssetId, status: ImportStatus) =
        dao.setImportStatus(assetId.value, status.name)
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

private fun AssetImportRecord.toEntity() = AssetImportRecordEntity(
    importId = importId,
    sourceName = sourceName,
    status = status.name,
    assetId = assetId?.value,
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
