package dev.aether.assets

import dev.aether.domain.AssetRepository
import dev.aether.model.AssetCategory
import dev.aether.model.AssetId
import dev.aether.model.AssetImportRecord
import dev.aether.model.AssetMetadata
import dev.aether.model.AssetOwnership
import dev.aether.model.AssetReference
import dev.aether.model.AssetState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class AssetEngineTest {
    private val tempRoot: File = createTempDirectory("aether-engine").toFile()
    private val storage = AppPrivateAssetStorage(tempRoot).also { it.initialize() }
    private val repository = FakeAssetRepository()
    private val engine = AssetEngine(storage, repository)

    @After
    fun cleanUp() {
        tempRoot.deleteRecursively()
    }

    @Test
    fun `registers an asset with a stable relative path and checksum`() = runTest {
        val staged = storage.stage("imp", AssetCategory.WALLPAPER, "wall.png", 5).getOrThrow()
        staged.writeText("hello")
        val result = engine.register(
            AssetRegistration(
                importId = "imp",
                assetId = AssetId("wall.one"),
                category = AssetCategory.WALLPAPER,
                displayName = "Wall",
                stagedRelativePath = "staging/imp/wall.png",
                extension = "png",
                ownership = AssetOwnership.USER_MANAGED,
            ),
        ).getOrThrow()

        assertFalse(result.relativePath.startsWith("/"))
        assertTrue(result.relativePath.startsWith("originals/wallpaper/"))
        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824", result.checksumSha256)
        assertEquals(AssetState.REGISTERED, result.state)
        assertEquals(1, repository.imports.size)
    }

    @Test
    fun `user managed deletion is recoverable while referenced assets are kept`() = runTest {
        val assetId = AssetId("wall.two")
        staged(assetId, AssetOwnership.USER_MANAGED)
        repository.references[assetId.value] = 1
        assertTrue(engine.delete(assetId).isFailure)

        repository.references.clear()
        assertTrue(engine.delete(assetId).isSuccess)
        assertEquals(AssetState.TRASHED, repository.metadata.getValue(assetId.value).state)
        assertTrue(storage.resolve("trash").getOrThrow().listFiles()?.isNotEmpty() == true)
    }

    @Test
    fun `temporary assets are removed permanently`() = runTest {
        val assetId = AssetId("wall.three")
        staged(assetId, AssetOwnership.TEMPORARY)
        assertTrue(engine.delete(assetId).isSuccess)
        assertFalse(repository.metadata.containsKey(assetId.value))
    }

    private suspend fun staged(assetId: AssetId, ownership: AssetOwnership) {
        val staged = storage.stage("imp-${assetId.value}", AssetCategory.WALLPAPER, "wall.png", 5).getOrThrow()
        staged.writeText("hello")
        engine.register(
            AssetRegistration(
                importId = "imp-${assetId.value}",
                assetId = assetId,
                category = AssetCategory.WALLPAPER,
                displayName = "Wall",
                stagedRelativePath = "staging/imp-${assetId.value}/wall.png",
                extension = "png",
                ownership = ownership,
            ),
        ).getOrThrow()
    }
}

private class FakeAssetRepository : AssetRepository {
    val metadata = mutableMapOf<String, AssetMetadata>()
    val imports = mutableListOf<AssetImportRecord>()
    val references = mutableMapOf<String, Int>()

    override fun observeAssets(): Flow<List<AssetMetadata>> = flowOf(metadata.values.toList())
    override suspend fun get(id: AssetId) = metadata[id.value]
    override suspend fun register(asset: AssetMetadata) = metadata.set(asset.id.value, asset).let { Unit }
    override suspend fun addReference(reference: AssetReference) = Unit
    override suspend fun removeReference(assetId: AssetId, consumerId: String) = Unit
    override suspend fun referenceCount(assetId: AssetId) = references[assetId.value] ?: 0
    override suspend fun markTrashed(id: AssetId) {
        metadata[id.value] = metadata.getValue(id.value).copy(state = AssetState.TRASHED)
    }
    override suspend fun delete(id: AssetId) = metadata.remove(id.value).let { Unit }
    override suspend fun recordImport(record: AssetImportRecord) = imports.add(record).let { Unit }
}
