package dev.aether.assets

import dev.aether.model.AssetId
import dev.aether.model.AssetMetadata
import dev.aether.model.AssetOwnership
import dev.aether.model.AssetState
import dev.aether.model.AssetCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AssetIdTest {
    @Test
    fun `ids are validated and stable`() {
        assertTrue(AssetId.of("wall.one").isSuccess)
        assertTrue(AssetId.of("../escape").isFailure)
        assertTrue(AssetId.of("").isFailure)
        assertEquals(AssetId("wall.one"), AssetId.of("wall.one").getOrThrow())
    }

    @Test
    fun `metadata carries a logical path not an absolute one`() {
        val metadata = AssetMetadata(
            id = AssetId("wall.one"),
            category = AssetCategory.WALLPAPER,
            displayName = "Wall",
            relativePath = "originals/wallpaper/wall.one.png",
            byteSize = 5,
            checksumSha256 = "abc",
            state = AssetState.REGISTERED,
            ownership = AssetOwnership.USER_MANAGED,
            createdAtMillis = 0,
        )
        assertEquals("wall.one", metadata.id.value)
        assertTrue(!metadata.relativePath.startsWith("/"))
        assertEquals(AssetState.REGISTERED, metadata.state)
    }
}
