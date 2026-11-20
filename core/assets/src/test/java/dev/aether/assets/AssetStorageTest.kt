package dev.aether.assets

import dev.aether.model.AssetCategory
import dev.aether.model.AssetId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.io.path.createTempDirectory

class AssetStorageTest {
    private val root: File = createTempDirectory("aether-assets").toFile().also {
        File(it, AssetPaths.ROOT_NAME).mkdirs()
    }
    private val storage = AppPrivateAssetStorage(File(root, AssetPaths.ROOT_NAME))

    @After
    fun cleanUp() {
        root.deleteRecursively()
    }

    @Test
    fun `rejects unsafe names, traversal and absolute paths`() {
        assertTrue(AssetPaths.safeFileName(AssetCategory.WALLPAPER, "../escape.png").isFailure)
        assertTrue(AssetPaths.safeFileName(AssetCategory.WALLPAPER, "wall.exe").isFailure)
        assertTrue(AssetPaths.safeFileName(AssetCategory.FONT, "font.ttf").isSuccess)
        assertTrue(storage.resolve("/etc/passwd").isFailure)
        assertTrue(storage.resolve("originals/../../outside.txt").isFailure)
    }

    @Test
    fun `rejects symlink escape from the asset root`() {
        storage.initialize()
        val outside = File(root, "outside.txt").also { it.writeText("secret") }
        Files.createSymbolicLink(File(storagePath(), "link").toPath(), outside.toPath())
        assertTrue(storage.resolve("link/outside.txt").isFailure)
    }

    @Test
    fun `stages promotes and trashes files under the app private root`() {
        storage.initialize()
        val staged = storage.stage("import-1", AssetCategory.WALLPAPER, "wall.png", 5).getOrThrow()
        staged.writeText("hello")
        assertTrue(staged.canonicalPath.startsWith(storagePath().canonicalPath))

        val assetId = AssetId("asset.one")
        val relative = storage.relativeOriginalPath(assetId, AssetCategory.WALLPAPER, "png").getOrThrow()
        val original = storage.promote(
            storage.stagedRelativePath("import-1", "wall.png").getOrThrow(),
            relative,
        ).getOrThrow()
        assertEquals("hello", original.readText())
        assertFalse(staged.exists())

        val trashRelative = AssetPaths.trashRelativePath(assetId.value, "png").getOrThrow()
        val trashed = storage.moveToTrash(relative).getOrThrow()
        assertEquals(storage.resolve(trashRelative).getOrThrow().canonicalPath, trashed.canonicalPath)
        assertTrue(trashed.canonicalPath.startsWith(File(storagePath(), "trash").canonicalPath))
        assertFalse(original.exists())
    }

    @Test
    fun `move does not overwrite an occupied target`() {
        storage.initialize()
        val source = storage.resolve("cache/source.txt").getOrThrow().also { it.writeText("new") }
        val target = storage.resolve("originals/wallpaper/occupied.txt").getOrThrow().also {
            it.parentFile?.mkdirs()
            it.writeText("old")
        }

        assertTrue(storage.move("cache/source.txt", "originals/wallpaper/occupied.txt").isFailure)
        assertEquals("new", source.readText())
        assertEquals("old", target.readText())
    }

    @Test
    fun `enforces size limits and clears only cache`() {
        storage.initialize()
        assertTrue(
            storage.stage("import-2", AssetCategory.WALLPAPER, "big.png", AssetPaths.MAX_ASSET_BYTES + 1).isFailure,
        )
        val cached = storage.resolve("cache/temp.txt").getOrThrow().also { it.writeText("x") }
        storage.clearCache()
        assertFalse(cached.exists())
    }

    private fun storagePath() = File(root, AssetPaths.ROOT_NAME)
}
