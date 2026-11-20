package dev.aether.assets

import dev.aether.model.AssetCategory
import dev.aether.model.AssetId
import dev.aether.model.AssetMetadata
import dev.aether.model.AssetOwnership
import dev.aether.model.AssetState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import kotlin.io.path.createTempDirectory

class AssetValidatorTest {
    private val root: File = createTempDirectory("aether-validate").toFile()
    private val storage = AppPrivateAssetStorage(root).also { it.initialize() }

    @After
    fun cleanUp() {
        root.deleteRecursively()
    }

    @Test
    fun `accepts a valid png and returns its actual size and checksum`() {
        val file = staged("wall.png", PNG_BYTES)

        val inspection = AssetValidator.validate(registration(file), file).getOrThrow()

        assertEquals(PNG_BYTES.size.toLong(), inspection.byteSize)
        assertEquals(AssetValidator.checksum(file), inspection.checksumSha256)
    }

    @Test
    fun `rejects empty oversized and mismatched declared sizes`() {
        val empty = staged("empty.png", ByteArray(0))
        assertTrue(error(AssetValidator.validate(registration(empty), empty)) is AssetValidationError.EmptyAsset)

        val file = staged("wall.png", PNG_BYTES)
        assertTrue(
            error(AssetValidator.validate(registration(file, declaredByteSize = file.length() + 1), file))
                is AssetValidationError.SizeMismatch,
        )

        val oversized = staged("huge.png", ByteArray(0))
        RandomAccessFile(oversized, "rw").use { it.setLength(AssetPaths.MAX_ASSET_BYTES + 1) }
        assertTrue(
            error(AssetValidator.validate(registration(oversized, declaredByteSize = 1), oversized))
                is AssetValidationError.AssetTooLarge,
        )
    }

    @Test
    fun `rejects unsupported category extension and svg`() {
        val executable = staged("wall.exe", PNG_BYTES)
        assertTrue(
            error(AssetValidator.validate(registration(executable, extension = "exe"), executable))
                is AssetValidationError.UnsupportedAssetType,
        )

        val mismatched = staged("wall.png", PNG_BYTES)
        assertTrue(
            error(AssetValidator.validate(registration(mismatched, category = AssetCategory.FONT), mismatched))
                is AssetValidationError.UnsupportedAssetType,
        )

        val svg = staged("icon.svg", "<svg onload=\"alert(1)\"/>".toByteArray())
        assertTrue(
            error(AssetValidator.validate(registration(svg, AssetCategory.ICON, "svg"), svg))
                is AssetValidationError.UnsafeSvg,
        )
    }

    @Test
    fun `validates image signatures and bounds without full decoding`() {
        val fake = staged("fake.png", "hello".toByteArray())
        assertTrue(
            error(AssetValidator.validate(registration(fake), fake)) is AssetValidationError.InvalidFileSignature,
        )

        val zeroWidth = staged("zero.png", pngBytes(width = 0))
        assertTrue(
            error(AssetValidator.validate(registration(zeroWidth), zeroWidth))
                is AssetValidationError.InvalidImageDimensions,
        )

        val huge = staged("huge-dim.png", pngBytes(width = AssetValidator.MAX_IMAGE_DIMENSION + 1))
        assertTrue(
            error(AssetValidator.validate(registration(huge), huge))
                is AssetValidationError.InvalidImageDimensions,
        )

        val jpeg = staged("photo.jpg", jpegBytes(width = 2, height = 3))
        assertTrue(AssetValidator.validate(registration(jpeg, extension = "jpg"), jpeg).isSuccess)
    }

    @Test
    fun `validates webp chunk lengths`() {
        val valid = staged("valid.webp", webpBytes(WebpChunk("VP8X", 10, vp8xPayload(2, 3))))
        assertTrue(AssetValidator.validate(registration(valid, extension = "webp"), valid).isSuccess)

        val negative = staged(
            "negative.webp",
            webpBytes(
                WebpChunk("JUNK", -1),
                WebpChunk("VP8X", 10, vp8xPayload(2, 3)),
            ),
        )
        assertTrue(
            error(AssetValidator.validate(registration(negative, extension = "webp"), negative))
                is AssetValidationError.InvalidFileSignature,
        )

        val oversized = staged(
            "oversized.webp",
            webpBytes(
                WebpChunk("JUNK", Int.MAX_VALUE),
                WebpChunk("VP8X", 10, vp8xPayload(2, 3)),
            ),
        )
        assertTrue(
            error(AssetValidator.validate(registration(oversized, extension = "webp"), oversized))
                is AssetValidationError.InvalidFileSignature,
        )

        val shortKnownChunk = staged(
            "short.webp",
            webpBytes(WebpChunk("VP8X", 4, vp8xPayload(2, 3))),
        )
        assertTrue(
            error(AssetValidator.validate(registration(shortKnownChunk, extension = "webp"), shortKnownChunk))
                is AssetValidationError.InvalidFileSignature,
        )
    }

    @Test
    fun `validates font signatures and table directory`() {
        val ttf = staged("font.ttf", fontBytes("ttf"))
        assertTrue(
            AssetValidator.validate(registration(ttf, AssetCategory.FONT, "ttf"), ttf).isSuccess,
        )

        val otf = staged("font.otf", fontBytes("otf"))
        assertTrue(
            AssetValidator.validate(registration(otf, AssetCategory.FONT, "otf"), otf).isSuccess,
        )

        val fake = staged("fake.ttf", PNG_BYTES)
        assertTrue(
            error(AssetValidator.validate(registration(fake, AssetCategory.FONT, "ttf"), fake))
                is AssetValidationError.InvalidFont,
        )

        val outside = staged("outside.ttf", fontBytes("ttf", tableOffset = 1_000L))
        assertTrue(
            error(AssetValidator.validate(registration(outside, AssetCategory.FONT, "ttf"), outside))
                is AssetValidationError.InvalidFont,
        )
    }

    @Test
    fun `validates text encoding and json syntax`() {
        val text = staged("note.txt", "Aether".toByteArray())
        assertTrue(
            AssetValidator.validate(registration(text, AssetCategory.OTHER, "txt"), text).isSuccess,
        )

        val invalidUtf8 = staged("bad.txt", byteArrayOf(0xFF.toByte()))
        assertTrue(
            error(AssetValidator.validate(registration(invalidUtf8, AssetCategory.OTHER, "txt"), invalidUtf8))
                is AssetValidationError.InvalidText,
        )

        val nul = staged("nul.txt", "a\u0000b".toByteArray())
        assertTrue(
            error(AssetValidator.validate(registration(nul, AssetCategory.OTHER, "txt"), nul))
                is AssetValidationError.InvalidText,
        )

        val json = staged("data.json", "{\"ok\":true}".toByteArray())
        assertTrue(
            AssetValidator.validate(registration(json, AssetCategory.OTHER, "json"), json).isSuccess,
        )

        val badJson = staged("bad.json", "{\"ok\":".toByteArray())
        assertTrue(
            error(AssetValidator.validate(registration(badJson, AssetCategory.OTHER, "json"), badJson))
                is AssetValidationError.InvalidText,
        )

        val oversized = staged("long.txt", ByteArray(AssetValidator.MAX_TEXT_BYTES + 1))
        assertTrue(
            error(AssetValidator.validate(registration(oversized, AssetCategory.OTHER, "txt"), oversized))
                is AssetValidationError.InvalidText,
        )
    }

    @Test
    fun `rejects unsafe registration metadata`() {
        val file = staged("wall.png", PNG_BYTES)

        assertTrue(
            error(
                AssetValidator.validate(
                    registration(file, stagedRelativePath = "../staging/import.one/wall.png"),
                    file,
                ),
            ) is AssetValidationError.InvalidAssetPath,
        )
        assertTrue(
            error(AssetValidator.validate(registration(file, displayName = "  "), file))
                is AssetValidationError.InvalidMetadata,
        )
        assertTrue(
            error(AssetValidator.validate(registration(file, importId = "bad/id"), file))
                is AssetValidationError.InvalidAssetPath,
        )
        assertTrue(
            error(
                AssetValidator.validate(
                    registration(file, stagedRelativePath = "staging/other.one/wall.png"),
                    file,
                ),
            ) is AssetValidationError.InvalidAssetPath,
        )
        assertTrue(
            error(
                AssetValidator.validate(
                    registration(file, stagedRelativePath = "originals/wallpaper/asset.one.png"),
                    file,
                ),
            ) is AssetValidationError.InvalidAssetPath,
        )
    }

    @Test
    fun `integrity checks detect checksum format changes and file tampering`() {
        val file = staged("wall.png", PNG_BYTES)
        val inspection = AssetValidator.validate(registration(file), file).getOrThrow()
        val metadata = metadata(file, inspection.checksumSha256)

        assertTrue(AssetValidator.verifyIntegrity(metadata, file).isSuccess)
        assertTrue(
            error(AssetValidator.verifyIntegrity(metadata.copy(checksumSha256 = "bad"), file))
                is AssetValidationError.AssetIntegrityFailure,
        )

        val tampered = PNG_BYTES.copyOf().also { it[it.lastIndex] = (it[it.lastIndex] + 1).toByte() }
        file.writeBytes(tampered)
        assertTrue(error(AssetValidator.verifyIntegrity(metadata, file)) is AssetValidationError.ChecksumMismatch)

        file.writeBytes(PNG_BYTES + byteArrayOf(0))
        assertTrue(error(AssetValidator.verifyIntegrity(metadata, file)) is AssetValidationError.SizeMismatch)
    }

    private fun staged(name: String, bytes: ByteArray): File =
        storage.resolve("staging/import.one/$name").getOrThrow().also {
            it.parentFile?.mkdirs()
            it.writeBytes(bytes)
        }

    private fun registration(
        file: File,
        category: AssetCategory = AssetCategory.WALLPAPER,
        extension: String = "png",
        declaredByteSize: Long = file.length(),
        importId: String = "import.one",
        stagedRelativePath: String = "staging/$importId/${file.name}",
        displayName: String = "Asset",
    ) = AssetRegistration(
        importId = importId,
        assetId = AssetId("asset.one"),
        category = category,
        displayName = displayName,
        stagedRelativePath = stagedRelativePath,
        extension = extension,
        declaredByteSize = declaredByteSize,
        ownership = AssetOwnership.USER_MANAGED,
    )

    private fun metadata(file: File, checksum: String) = AssetMetadata(
        id = AssetId("asset.one"),
        category = AssetCategory.WALLPAPER,
        displayName = "Asset",
        relativePath = "staging/import.one/${file.name}",
        byteSize = file.length(),
        checksumSha256 = checksum,
        state = AssetState.REGISTERED,
        ownership = AssetOwnership.USER_MANAGED,
        createdAtMillis = 0L,
    )

    private fun error(result: Result<*>) = (result.exceptionOrNull() as? AssetValidationException)?.error
}
