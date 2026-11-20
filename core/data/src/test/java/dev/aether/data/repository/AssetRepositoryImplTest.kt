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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AssetRepositoryImplTest {
    private val dao = FakeAssetDao()
    private val repository: AssetRepository = AssetRepositoryImpl(dao)

    @Test
    fun `removing a reference targets the exact consumer type and id`() = runTest {
        val assetId = AssetId("asset.one")
        val home = AssetReference(assetId, "HOME", "screen")
        val widget = AssetReference(assetId, "WIDGET", "screen")
        repository.addReference(home)
        repository.addReference(widget)

        repository.removeReference(home)

        assertEquals(widget, dao.references.single().toDomain())
    }

    @Test
    fun `registration writes metadata and import row together`() = runTest {
        val metadata = metadata()
        val record = AssetImportRecord(
            importId = "imp.one",
            sourceName = "Wall",
            status = ImportStatus.STAGED,
            assetId = metadata.id,
            createdAtMillis = 10,
        )

        repository.registerWithImport(metadata, record)

        assertEquals(AssetState.STAGED, dao.assets.getValue(metadata.id.value).state.toState())
        assertEquals(ImportStatus.STAGED.name, dao.imports.getValue("imp.one").status)
    }

    @Test
    fun `update can change state and import status together`() = runTest {
        val metadata = metadata()
        val record = AssetImportRecord("imp.one", "Wall", ImportStatus.STAGED, metadata.id, 10)
        repository.registerWithImport(metadata, record)

        repository.update(metadata.copy(state = AssetState.REGISTERED), ImportStatus.REGISTERED)

        assertEquals(AssetState.REGISTERED, dao.assets.getValue(metadata.id.value).state.toState())
        assertEquals(ImportStatus.REGISTERED.name, dao.imports.getValue("imp.one").status)
    }

    @Test
    fun `getAll includes assets that are no longer active`() = runTest {
        val active = metadata()
        val trashed = metadata().copy(
            id = AssetId("asset.two"),
            state = AssetState.TRASHED,
            relativePath = "trash/asset.two.png",
        )
        dao.assets[active.id.value] = toEntity(active)
        dao.assets[trashed.id.value] = toEntity(trashed)

        assertEquals(setOf(active.id.value, trashed.id.value), repository.getAll().map { it.id.value }.toSet())

        repository.delete(active.id)
        assertNull(repository.get(active.id))
    }

    private fun metadata() = AssetMetadata(
        id = AssetId("asset.one"),
        category = AssetCategory.WALLPAPER,
        displayName = "Wall",
        relativePath = "originals/wallpaper/asset.one.png",
        byteSize = 5,
        checksumSha256 = "abc",
        state = AssetState.STAGED,
        ownership = AssetOwnership.USER_MANAGED,
        createdAtMillis = 10,
    )

    private fun toEntity(asset: AssetMetadata) = AssetMetadataEntity(
        id = asset.id.value,
        category = asset.category.name,
        displayName = asset.displayName,
        relativePath = asset.relativePath,
        byteSize = asset.byteSize,
        checksumSha256 = asset.checksumSha256,
        state = asset.state.name,
        ownership = asset.ownership.name,
        createdAtMillis = asset.createdAtMillis,
    )

    private fun String.toState(): AssetState = AssetState.valueOf(this)

    private fun AssetReferenceEntity.toDomain() = AssetReference(
        assetId = AssetId(assetId),
        consumerType = consumerType,
        consumerId = consumerId,
    )
}

private class FakeAssetDao : AssetDao {
    val assets = mutableMapOf<String, AssetMetadataEntity>()
    val imports = mutableMapOf<String, AssetImportRecordEntity>()
    val references = mutableListOf<AssetReferenceEntity>()

    override fun observeAssets(): Flow<List<AssetMetadataEntity>> =
        MutableStateFlow(assets.values.filter { it.state == AssetState.REGISTERED.name })

    override suspend fun getAll(): List<AssetMetadataEntity> = assets.values.toList()

    override suspend fun get(id: String): AssetMetadataEntity? = assets[id]

    override suspend fun upsert(entity: AssetMetadataEntity) {
        assets[entity.id] = entity
    }

    override suspend fun upsertWithImport(
        entity: AssetMetadataEntity,
        record: AssetImportRecordEntity,
    ) {
        upsert(entity)
        upsertImport(record)
    }

    override suspend fun updateWithImportStatus(entity: AssetMetadataEntity, status: String) {
        upsert(entity)
        setImportStatus(entity.id, status)
    }

    override suspend fun setImportStatus(assetId: String, status: String) {
        imports.values
            .filter { it.assetId == assetId }
            .forEach { imports[it.importId] = it.copy(status = status) }
    }

    override suspend fun delete(id: String) {
        assets.remove(id)
    }

    override suspend fun referenceCount(assetId: String): Int =
        references.count { it.assetId == assetId }

    override suspend fun addReference(reference: AssetReferenceEntity) {
        if (reference !in references) references.add(reference)
    }

    override suspend fun removeReference(assetId: String, consumerType: String, consumerId: String) {
        references.removeAll {
            it.assetId == assetId && it.consumerType == consumerType && it.consumerId == consumerId
        }
    }

    override suspend fun upsertImport(record: AssetImportRecordEntity) {
        imports[record.importId] = record
    }
}
