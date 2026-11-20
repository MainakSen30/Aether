package dev.aether.assets

import dev.aether.domain.AssetRepository
import dev.aether.model.AssetCategory
import dev.aether.model.AssetId
import dev.aether.model.AssetImportRecord
import dev.aether.model.AssetMetadata
import dev.aether.model.AssetOwnership
import dev.aether.model.AssetReference
import dev.aether.model.AssetState
import dev.aether.model.ImportStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

@OptIn(ExperimentalCoroutinesApi::class)
class AssetEngineTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val tempRoot: File = createTempDirectory("aether-engine").toFile()
    private val storage = FailingAssetStorage(
        AppPrivateAssetStorage(tempRoot).also { it.initialize() },
    )
    private val repository = FakeAssetRepository()
    private val engine = AssetEngine(storage, repository, dispatcher)

    @After
    fun cleanUp() {
        tempRoot.deleteRecursively()
    }

    @Test
    fun `registers an asset with a stable relative path and checksum`() = runTest(dispatcher) {
        val result = register("wall.one")

        assertFalse(result.relativePath.startsWith("/"))
        assertEquals(originalPath("wall.one"), result.relativePath)
        assertEquals(
            "b56a089a96a0c108e038c6ee3b6d8895720cbfa226b28b86beb1583425138ee5",
            result.checksumSha256,
        )
        assertEquals(AssetState.REGISTERED, result.state)
        assertEquals(ImportStatus.REGISTERED, repository.imports.values.single().status)
    }

    @Test
    fun `trash preserves the restoration reference and repeated deletion is safe`() = runTest(dispatcher) {
        val assetId = AssetId("wall.two")
        register(assetId.value)

        engine.delete(assetId).getOrThrow()

        val trashed = repository.metadata.getValue(assetId.value)
        assertEquals(AssetState.TRASHED, trashed.state)
        assertEquals(trashPath(assetId.value), trashed.relativePath)
        assertFalse(storage.resolve(originalPath(assetId.value)).getOrThrow().exists())
        assertTrue(storage.resolve(trashPath(assetId.value)).getOrThrow().isFile)

        engine.delete(assetId).getOrThrow()
        assertEquals(AssetState.TRASHED, repository.metadata.getValue(assetId.value).state)
    }

    @Test
    fun `restore moves the asset back and repeated restoration is safe`() = runTest(dispatcher) {
        val assetId = AssetId("wall.restore")
        register(assetId.value)
        engine.delete(assetId).getOrThrow()

        engine.restore(assetId).getOrThrow()

        val restored = repository.metadata.getValue(assetId.value)
        assertEquals(AssetState.REGISTERED, restored.state)
        assertEquals(originalPath(assetId.value), restored.relativePath)
        assertTrue(
            PNG_BYTES.contentEquals(
                storage.resolve(originalPath(assetId.value)).getOrThrow().readBytes(),
            ),
        )
        assertFalse(storage.resolve(trashPath(assetId.value)).getOrThrow().exists())

        engine.restore(assetId).getOrThrow()
        assertEquals(AssetState.REGISTERED, repository.metadata.getValue(assetId.value).state)
    }

    @Test
    fun `restore fails without overwriting an occupied original location`() = runTest(dispatcher) {
        val assetId = AssetId("wall.occupied")
        register(assetId.value)
        engine.delete(assetId).getOrThrow()
        val original = storage.resolve(originalPath(assetId.value)).getOrThrow().also {
            it.writeText("occupant")
        }

        assertTrue(engine.restore(assetId).isFailure)

        assertEquals(AssetState.TRASHED, repository.metadata.getValue(assetId.value).state)
        assertEquals("occupant", original.readText())
        assertTrue(
            PNG_BYTES.contentEquals(
                storage.resolve(trashPath(assetId.value)).getOrThrow().readBytes(),
            ),
        )
    }

    @Test
    fun `permanent deletion from trash cleans file and metadata and is repeatable`() = runTest(dispatcher) {
        val assetId = AssetId("wall.permanent")
        register(assetId.value)
        engine.delete(assetId).getOrThrow()

        engine.permanentlyDelete(assetId).getOrThrow()

        assertNull(repository.metadata[assetId.value])
        assertFalse(storage.resolve(trashPath(assetId.value)).getOrThrow().exists())
        engine.permanentlyDelete(assetId).getOrThrow()
    }

    @Test
    fun `temporary assets are removed permanently`() = runTest(dispatcher) {
        val assetId = AssetId("wall.temp")
        register(assetId.value, AssetOwnership.TEMPORARY)

        engine.delete(assetId).getOrThrow()

        assertNull(repository.metadata[assetId.value])
        assertFalse(storage.resolve(originalPath(assetId.value)).getOrThrow().exists())
    }

    @Test
    fun `shared references protect an asset until every reference is removed`() = runTest(dispatcher) {
        val assetId = AssetId("wall.shared")
        register(assetId.value)
        val home = AssetReference(assetId, "HOME", "screen")
        val widget = AssetReference(assetId, "WIDGET", "screen")
        repository.addReference(home)
        repository.addReference(widget)

        assertTrue(engine.delete(assetId).isFailure)
        repository.removeReference(home)
        assertEquals(1, repository.referenceCount(assetId))
        assertTrue(engine.delete(assetId).isFailure)
        repository.removeReference(widget)
        assertEquals(0, repository.referenceCount(assetId))

        assertTrue(engine.delete(assetId).isSuccess)
    }

    @Test
    fun `filesystem move failure during trash leaves the active asset unchanged`() = runTest(dispatcher) {
        val assetId = AssetId("wall.move-fail")
        register(assetId.value)
        storage.failMoveTimes = 1

        val result = engine.delete(assetId)

        assertTrue(result.isFailure)
        assertEquals(AssetState.REGISTERED, repository.metadata.getValue(assetId.value).state)
        assertEquals(originalPath(assetId.value), repository.metadata.getValue(assetId.value).relativePath)
        assertTrue(storage.resolve(originalPath(assetId.value)).getOrThrow().isFile)
        assertFalse(storage.resolve(trashPath(assetId.value)).getOrThrow().exists())

        storage.failMoveTimes = 0
        engine.delete(assetId).getOrThrow()
    }

    @Test
    fun `database failure after moving to trash is recovered from the marker state`() = runTest(dispatcher) {
        val assetId = AssetId("wall.db-trash")
        register(assetId.value)
        repository.updateCalls = 0
        repository.failUpdateAt = 2

        val result = engine.delete(assetId)

        assertTrue(result.isFailure)
        assertEquals(AssetState.PENDING_TRASH, repository.metadata.getValue(assetId.value).state)
        assertFalse(storage.resolve(originalPath(assetId.value)).getOrThrow().exists())
        assertTrue(storage.resolve(trashPath(assetId.value)).getOrThrow().isFile)

        repository.failUpdateAt = null
        engine.recover().getOrThrow()
        val recovered = repository.metadata.getValue(assetId.value)
        assertEquals(AssetState.TRASHED, recovered.state)
        assertEquals(trashPath(assetId.value), recovered.relativePath)
    }

    @Test
    fun `interrupted restore is recovered from the marker state`() = runTest(dispatcher) {
        val assetId = AssetId("wall.interrupted")
        register(assetId.value)
        engine.delete(assetId).getOrThrow()
        val trashed = repository.metadata.getValue(assetId.value)
        repository.metadata[assetId.value] = trashed.copy(state = AssetState.PENDING_RESTORE)
        storage.move(trashed.relativePath, originalPath(assetId.value)).getOrThrow()

        engine.recover().getOrThrow()

        val recovered = repository.metadata.getValue(assetId.value)
        assertEquals(AssetState.REGISTERED, recovered.state)
        assertEquals(originalPath(assetId.value), recovered.relativePath)
        assertTrue(storage.resolve(originalPath(assetId.value)).getOrThrow().isFile)
    }

    @Test
    fun `database failure after promotion leaves registration recoverable`() = runTest(dispatcher) {
        val assetId = AssetId("wall.db-register")
        val staged = storage.stage("imp-db", AssetCategory.WALLPAPER, "wall.png", PNG_BYTES.size.toLong())
            .getOrThrow()
        staged.writeBytes(PNG_BYTES)
        repository.updateCalls = 0
        repository.failUpdateAt = 1

        val result = engine.register(
            AssetRegistration(
                importId = "imp-db",
                assetId = assetId,
                category = AssetCategory.WALLPAPER,
                displayName = "Wall",
                stagedRelativePath = "staging/imp-db/wall.png",
                extension = "png",
                declaredByteSize = PNG_BYTES.size.toLong(),
                ownership = AssetOwnership.USER_MANAGED,
            ),
        )

        assertTrue(result.isFailure)
        assertEquals(AssetState.STAGED, repository.metadata.getValue(assetId.value).state)
        assertTrue(storage.resolve(originalPath(assetId.value)).getOrThrow().isFile)

        repository.failUpdateAt = null
        engine.recover().getOrThrow()
        assertEquals(AssetState.REGISTERED, repository.metadata.getValue(assetId.value).state)
        assertEquals(
            ImportStatus.REGISTERED,
            repository.imports.values.single { it.assetId == assetId }.status,
        )
    }

    @Test
    fun `filesystem failure during registration cleans the metadata and marks import failed`() =
        runTest(dispatcher) {
            val assetId = AssetId("wall.fs-register")
            val staged = storage.stage("imp-fs", AssetCategory.WALLPAPER, "wall.png", PNG_BYTES.size.toLong())
                .getOrThrow()
            staged.writeBytes(PNG_BYTES)
            storage.failMoveTimes = 1

            val result = engine.register(
                AssetRegistration(
                    importId = "imp-fs",
                    assetId = assetId,
                    category = AssetCategory.WALLPAPER,
                    displayName = "Wall",
                    stagedRelativePath = "staging/imp-fs/wall.png",
                    extension = "png",
                    declaredByteSize = PNG_BYTES.size.toLong(),
                    ownership = AssetOwnership.USER_MANAGED,
                ),
            )

            assertTrue(result.isFailure)
            assertNull(repository.metadata[assetId.value])
            assertEquals(ImportStatus.FAILED, repository.imports.values.single().status)
            assertTrue(storage.resolve("staging/imp-fs/wall.png").getOrThrow().isFile)
        }

    @Test
    fun `invalid asset content is rejected before metadata is written`() = runTest(dispatcher) {
        val assetId = AssetId("wall.invalid")
        val staged = storage.stage("imp-invalid", AssetCategory.WALLPAPER, "wall.png", 5).getOrThrow()
        staged.writeText("hello")

        val result = engine.register(
            AssetRegistration(
                importId = "imp-invalid",
                assetId = assetId,
                category = AssetCategory.WALLPAPER,
                displayName = "Wall",
                stagedRelativePath = "staging/imp-invalid/wall.png",
                extension = "png",
                declaredByteSize = 5,
                ownership = AssetOwnership.USER_MANAGED,
            ),
        )

        val error = (result.exceptionOrNull() as? AssetValidationException)?.error
        assertTrue(error is AssetValidationError.InvalidFileSignature)
        assertNull(repository.metadata[assetId.value])
        assertEquals(ImportStatus.FAILED, repository.imports.values.single().status)
        assertTrue(staged.exists())
    }

    @Test
    fun `unexpected staged path is rejected before metadata is written`() = runTest(dispatcher) {
        val assetId = AssetId("wall.wrong-path")
        val staged = storage.stage("imp-path", AssetCategory.WALLPAPER, "wall.png", PNG_BYTES.size.toLong())
            .getOrThrow()
        staged.writeBytes(PNG_BYTES)

        val result = engine.register(
            AssetRegistration(
                importId = "imp-path",
                assetId = assetId,
                category = AssetCategory.WALLPAPER,
                displayName = "Wall",
                stagedRelativePath = "staging/other-import/wall.png",
                extension = "png",
                declaredByteSize = PNG_BYTES.size.toLong(),
                ownership = AssetOwnership.USER_MANAGED,
            ),
        )

        val error = (result.exceptionOrNull() as? AssetValidationException)?.error
        assertTrue(error is AssetValidationError.InvalidAssetPath)
        assertNull(repository.metadata[assetId.value])
        assertEquals(ImportStatus.FAILED, repository.imports.values.single().status)
    }

    @Test
    fun `database failure after file deletion is recovered from pending deletion`() = runTest(dispatcher) {
        val assetId = AssetId("wall.db-delete")
        register(assetId.value)
        engine.delete(assetId).getOrThrow()
        repository.deleteCalls = 0
        repository.failDeleteAt = 1

        val result = engine.permanentlyDelete(assetId)

        assertTrue(result.isFailure)
        assertEquals(AssetState.PENDING_DELETE, repository.metadata.getValue(assetId.value).state)
        assertFalse(storage.resolve(trashPath(assetId.value)).getOrThrow().exists())

        repository.failDeleteAt = null
        engine.recover().getOrThrow()
        assertNull(repository.metadata[assetId.value])
    }

    @Test
    fun `cleanupTemporary removes only unreferenced temporary assets`() = runTest(dispatcher) {
        val removable = register("wall.auto-temp", AssetOwnership.TEMPORARY)
        val retained = register("wall.kept-temp", AssetOwnership.TEMPORARY)
        repository.addReference(AssetReference(retained.id, "HOME", "screen"))

        val removed = engine.cleanupTemporary().getOrThrow()

        assertEquals(1, removed)
        assertNull(repository.metadata[removable.id.value])
        assertNotNull(repository.metadata[retained.id.value])
    }

    @Test
    fun `cleanup removes abandoned staging entries and stale failed imports`() = runTest(dispatcher) {
        val assetId = AssetId("wall.cleanup")
        val registered = register(assetId.value)
        repository.setImportStatus(assetId, ImportStatus.STAGED)
        repository.metadata[assetId.value] = registered.copy(
            state = AssetState.STAGED,
            relativePath = "originals/wallpaper/missing.png",
        )
        val abandoned = storage.resolve("staging/old-import").getOrThrow().also {
            it.mkdirs()
            File(it, "partial.tmp").writeText("x")
            it.setLastModified(System.currentTimeMillis() - 10_000_000L)
        }

        val removed = engine.cleanupAbandonedStaging(maxAgeMillis = 1_000).getOrThrow()

        assertTrue(removed >= 1)
        assertFalse(abandoned.exists())
        assertNull(repository.metadata[assetId.value])
        assertEquals(
            ImportStatus.FAILED,
            repository.imports.values.single { it.assetId == assetId }.status,
        )
    }

    private suspend fun register(
        idValue: String,
        ownership: AssetOwnership = AssetOwnership.USER_MANAGED,
    ): AssetMetadata {
        val assetId = AssetId(idValue)
        val importId = "imp-$idValue"
        val staged = storage.stage(importId, AssetCategory.WALLPAPER, "wall.png", PNG_BYTES.size.toLong())
            .getOrThrow()
        staged.writeBytes(PNG_BYTES)
        return engine.register(
            AssetRegistration(
                importId = importId,
                assetId = assetId,
                category = AssetCategory.WALLPAPER,
                displayName = "Wall",
                stagedRelativePath = "staging/$importId/wall.png",
                extension = "png",
                declaredByteSize = PNG_BYTES.size.toLong(),
                ownership = ownership,
            ),
        ).getOrThrow()
    }

    private fun originalPath(idValue: String) = "originals/wallpaper/$idValue.png"

    private fun trashPath(idValue: String) = "trash/$idValue.png"
}

private class FailingAssetStorage(private val delegate: AssetStorage) : AssetStorage {
    var failMoveTimes: Int = 0
    var failDeleteTimes: Int = 0

    override fun initialize() = delegate.initialize()

    override fun stage(
        importId: String,
        category: AssetCategory,
        fileName: String,
        byteSize: Long,
    ): Result<File> = delegate.stage(importId, category, fileName, byteSize)

    override fun stagedRelativePath(importId: String, fileName: String): Result<String> =
        delegate.stagedRelativePath(importId, fileName)

    override fun originalPath(
        assetId: AssetId,
        category: AssetCategory,
        extension: String,
    ): Result<File> = delegate.originalPath(assetId, category, extension)

    override fun relativeOriginalPath(
        assetId: AssetId,
        category: AssetCategory,
        extension: String,
    ): Result<String> = delegate.relativeOriginalPath(assetId, category, extension)

    override fun thumbnailPath(assetId: AssetId): Result<File> = delegate.thumbnailPath(assetId)

    override fun resolve(relativePath: String): Result<File> = delegate.resolve(relativePath)

    override fun promote(stagedRelativePath: String, originalRelativePath: String): Result<File> =
        move(stagedRelativePath, originalRelativePath)

    override fun moveToTrash(relativePath: String): Result<File> = delegate.moveToTrash(relativePath)

    override fun move(sourceRelativePath: String, targetRelativePath: String): Result<File> {
        if (failMoveTimes > 0) {
            failMoveTimes--
            return Result.failure(IllegalStateException("move failed"))
        }
        return delegate.move(sourceRelativePath, targetRelativePath)
    }

    override fun delete(relativePath: String): Result<Unit> {
        if (failDeleteTimes > 0) {
            failDeleteTimes--
            return Result.failure(IllegalStateException("delete failed"))
        }
        return delegate.delete(relativePath)
    }

    override fun cleanupAbandonedStaging(maxAgeMillis: Long): Result<Int> =
        delegate.cleanupAbandonedStaging(maxAgeMillis)

    override fun clearCache() = delegate.clearCache()
}

private class FakeAssetRepository : AssetRepository {
    val metadata = mutableMapOf<String, AssetMetadata>()
    val imports = mutableMapOf<String, AssetImportRecord>()
    val references = mutableListOf<AssetReference>()

    var failUpdateAt: Int? = null
    var updateCalls: Int = 0
    var failDeleteAt: Int? = null
    var deleteCalls: Int = 0

    override fun observeAssets(): Flow<List<AssetMetadata>> = MutableStateFlow(metadata.values.toList())

    override suspend fun getAll(): List<AssetMetadata> = metadata.values.toList()

    override suspend fun get(id: AssetId): AssetMetadata? = metadata[id.value]

    override suspend fun register(asset: AssetMetadata) {
        metadata[asset.id.value] = asset
    }

    override suspend fun registerWithImport(asset: AssetMetadata, record: AssetImportRecord) {
        metadata[asset.id.value] = asset
        imports[record.importId] = record
    }

    override suspend fun update(asset: AssetMetadata, importStatus: ImportStatus?) {
        updateCalls++
        if (updateCalls == failUpdateAt) throw IllegalStateException("database update failed")
        metadata[asset.id.value] = asset
        if (importStatus != null) {
            imports.values
                .filter { it.assetId == asset.id }
                .forEach { imports[it.importId] = it.copy(status = importStatus) }
        }
    }

    override suspend fun addReference(reference: AssetReference) {
        if (reference !in references) references.add(reference)
    }

    override suspend fun removeReference(reference: AssetReference) {
        references.removeAll {
            it.assetId == reference.assetId &&
                it.consumerType == reference.consumerType &&
                it.consumerId == reference.consumerId
        }
    }

    override suspend fun referenceCount(assetId: AssetId): Int =
        references.count { it.assetId == assetId }

    override suspend fun delete(id: AssetId) {
        deleteCalls++
        if (deleteCalls == failDeleteAt) throw IllegalStateException("database delete failed")
        metadata.remove(id.value)
    }

    override suspend fun recordImport(record: AssetImportRecord) {
        imports[record.importId] = record
    }

    override suspend fun setImportStatus(assetId: AssetId, status: ImportStatus) {
        imports.values
            .filter { it.assetId == assetId }
            .forEach { imports[it.importId] = it.copy(status = status) }
    }
}
