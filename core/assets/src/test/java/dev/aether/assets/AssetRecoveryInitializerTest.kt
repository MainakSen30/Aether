package dev.aether.assets

import dev.aether.model.AssetCategory
import dev.aether.model.AssetId
import dev.aether.model.AssetMetadata
import dev.aether.model.AssetOwnership
import dev.aether.model.AssetState
import dev.aether.model.ImportStatus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.io.path.createTempDirectory
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class AssetRecoveryInitializerTest {
    private val tempRoot: File = createTempDirectory("aether-startup").toFile()
    private val storage = AppPrivateAssetStorage(tempRoot).also { it.initialize() }
    private val repository = FakeAssetRepository()

    @After
    fun cleanUp() {
        tempRoot.deleteRecursively()
    }

    @Test
    fun `startup invokes the injected engine recovery automatically`() = runBlocking {
        val initializer = AssetRecoveryInitializer(engine(UnconfinedTestDispatcher()))

        initializer.start()

        assertTrue(awaitCompletion(initializer).isSuccess)
        assertEquals(1, repository.getAllCalls)
    }

    @Test
    fun `startup recovery does not run on the main thread`() = runBlocking {
        val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "aether-recovery") }
        val dispatcher = executor.asCoroutineDispatcher()
        try {
            val initializer = AssetRecoveryInitializer(engine(dispatcher), dispatcher)

            initializer.start()

            assertTrue(awaitCompletion(initializer).isSuccess)
            assertTrue(repository.getAllThread.orEmpty().startsWith("aether-recovery"))
            assertFalse(Thread.currentThread().name == repository.getAllThread)
        } finally {
            dispatcher.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun `repeated startup recovery is safe and runs once per process`() = runBlocking {
        val initializer = AssetRecoveryInitializer(engine(UnconfinedTestDispatcher()))

        initializer.start()
        initializer.start()
        initializer.start()

        assertTrue(awaitCompletion(initializer).isSuccess)
        assertEquals(1, repository.getAllCalls)
    }

    @Test
    fun `startup recovers interrupted trash into a consistent state`() = runBlocking {
        val engine = engine(UnconfinedTestDispatcher())
        val assetId = AssetId("wall.startup-trash")
        val registered = register(engine, assetId.value)
        repository.metadata[assetId.value] = registered.copy(state = AssetState.PENDING_TRASH)
        storage.move(originalPath(assetId.value), trashPath(assetId.value)).getOrThrow()

        val initializer = AssetRecoveryInitializer(engine)
        initializer.start()

        assertTrue(awaitCompletion(initializer).isSuccess)
        val recovered = repository.metadata.getValue(assetId.value)
        assertEquals(AssetState.TRASHED, recovered.state)
        assertEquals(trashPath(assetId.value), recovered.relativePath)
        assertFalse(storage.resolve(originalPath(assetId.value)).getOrThrow().exists())
        assertTrue(storage.resolve(trashPath(assetId.value)).getOrThrow().isFile)
    }

    @Test
    fun `startup recovers interrupted restore into a consistent state`() = runBlocking {
        val engine = engine(UnconfinedTestDispatcher())
        val assetId = AssetId("wall.startup-restore")
        register(engine, assetId.value)
        engine.delete(assetId).getOrThrow()
        repository.metadata[assetId.value] =
            repository.metadata.getValue(assetId.value).copy(state = AssetState.PENDING_RESTORE)
        storage.move(trashPath(assetId.value), originalPath(assetId.value)).getOrThrow()

        val initializer = AssetRecoveryInitializer(engine)
        initializer.start()

        assertTrue(awaitCompletion(initializer).isSuccess)
        val recovered = repository.metadata.getValue(assetId.value)
        assertEquals(AssetState.REGISTERED, recovered.state)
        assertEquals(originalPath(assetId.value), recovered.relativePath)
        assertEquals(ImportStatus.REGISTERED, repository.imports.values.single().status)
    }

    @Test
    fun `startup finishes interrupted permanent deletion into a consistent state`() = runBlocking {
        val engine = engine(UnconfinedTestDispatcher())
        val assetId = AssetId("wall.startup-delete")
        val registered = register(engine, assetId.value)
        repository.metadata[assetId.value] = registered.copy(state = AssetState.PENDING_DELETE)
        storage.delete(originalPath(assetId.value)).getOrThrow()

        val initializer = AssetRecoveryInitializer(engine)
        initializer.start()

        assertTrue(awaitCompletion(initializer).isSuccess)
        assertNull(repository.metadata[assetId.value])
        assertFalse(storage.resolve(originalPath(assetId.value)).getOrThrow().exists())
    }

    @Test
    fun `startup discards registered metadata whose file is missing`() = runBlocking {
        val engine = engine(UnconfinedTestDispatcher())
        val assetId = AssetId("wall.startup-missing")
        register(engine, assetId.value)
        storage.delete(originalPath(assetId.value)).getOrThrow()

        val initializer = AssetRecoveryInitializer(engine)
        initializer.start()

        assertTrue(awaitCompletion(initializer).isSuccess)
        assertNull(repository.metadata[assetId.value])
        assertEquals(ImportStatus.FAILED, repository.imports.values.single().status)
    }

    @Test
    fun `occupied recovery destination is reported without changing marker state`() = runBlocking {
        val engine = engine(UnconfinedTestDispatcher())
        val assetId = AssetId("wall.startup-occupied")
        register(engine, assetId.value)
        engine.delete(assetId).getOrThrow()
        repository.metadata[assetId.value] =
            repository.metadata.getValue(assetId.value).copy(state = AssetState.PENDING_RESTORE)
        storage.resolve(originalPath(assetId.value)).getOrThrow().writeText("occupant")

        val initializer = AssetRecoveryInitializer(engine)
        initializer.start()

        val result = awaitCompletion(initializer)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("occupied"))
        assertEquals(
            AssetState.PENDING_RESTORE,
            repository.metadata.getValue(assetId.value).state,
        )
        assertEquals("occupant", storage.resolve(originalPath(assetId.value)).getOrThrow().readText())
    }

    @Test
    fun `invalid marker metadata fails recovery without changing state`() = runBlocking {
        val engine = engine(UnconfinedTestDispatcher())
        val assetId = AssetId("wall.startup-invalid")
        val registered = register(engine, assetId.value)
        repository.metadata[assetId.value] =
            registered.copy(state = AssetState.PENDING_TRASH, relativePath = "trash/noextension")

        val initializer = AssetRecoveryInitializer(engine)
        initializer.start()

        val result = awaitCompletion(initializer)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("no extension"))
        assertEquals(
            AssetState.PENDING_TRASH,
            repository.metadata.getValue(assetId.value).state,
        )
    }

    @Test
    fun `database failure is logged and leaves the marker state intact`() = runBlocking {
        val engine = engine(UnconfinedTestDispatcher())
        val assetId = AssetId("wall.startup-db")
        val registered = register(engine, assetId.value)
        repository.metadata[assetId.value] = registered.copy(state = AssetState.PENDING_DELETE)
        storage.delete(originalPath(assetId.value)).getOrThrow()
        repository.failDeleteAt = 1
        val logged = CopyOnWriteArrayList<Throwable>()

        val initializer = AssetRecoveryInitializer(engine, UnconfinedTestDispatcher(), logged::add)
        initializer.start()

        val result = awaitCompletion(initializer)
        assertTrue(result.isFailure)
        assertSame(result.exceptionOrNull(), logged.single())
        assertEquals(AssetState.PENDING_DELETE, repository.metadata.getValue(assetId.value).state)
    }

    private fun engine(dispatcher: CoroutineDispatcher): AssetEngine =
        AssetEngine(storage, repository, dispatcher)

    private suspend fun register(engine: AssetEngine, idValue: String): AssetMetadata {
        val assetId = AssetId(idValue)
        val importId = "imp-$idValue"
        val staged = storage
            .stage(importId, AssetCategory.WALLPAPER, "wall.png", PNG_BYTES.size.toLong())
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
                ownership = AssetOwnership.USER_MANAGED,
            ),
        ).getOrThrow()
    }

    private suspend fun awaitCompletion(initializer: AssetRecoveryInitializer): Result<Unit> =
        withTimeout(5.seconds) { initializer.completion.first { it != null }!! }

    private fun originalPath(idValue: String) = "originals/wallpaper/$idValue.png"

    private fun trashPath(idValue: String) = "trash/$idValue.png"
}
