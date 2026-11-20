package dev.aether.assets

import dev.aether.model.AssetMetadata
import kotlinx.serialization.json.Json
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

data class AssetInspection(val byteSize: Long, val checksumSha256: String)

/** Filesystem validation boundary for untrusted staged assets. No file is decoded in full. */
object AssetValidator {
    const val MAX_IMAGE_DIMENSION = 8192
    const val MAX_TEXT_BYTES = 1024 * 1024
    private const val MAX_HEADER_SCAN_BYTES = 1024 * 1024

    private val PNG_SIGNATURE = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    )
    private val CHECKSUM = Regex("[0-9a-f]{64}")
    private val SOF_MARKERS = setOf(
        0xC0, 0xC1, 0xC2, 0xC3, 0xC5, 0xC6, 0xC7, 0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF,
    )
    private val json = Json

    fun validate(registration: AssetRegistration, file: File): Result<AssetInspection> = runCatching {
        validateMetadata(registration)
        inspect(registration.category, registration.extension, registration.declaredByteSize, file)
    }

    fun verifyIntegrity(metadata: AssetMetadata, file: File): Result<Unit> = runCatching {
        if (!CHECKSUM.matches(metadata.checksumSha256)) {
            throw AssetValidationException(
                AssetValidationError.AssetIntegrityFailure(metadata.id.value, "invalid checksum format"),
            )
        }
        val extension = AssetPaths.extensionOf(metadata.relativePath).getOrThrow()
        val inspection = inspect(metadata.category, extension, metadata.byteSize, file)
        if (inspection.byteSize != metadata.byteSize) {
            throw AssetValidationException(
                AssetValidationError.AssetIntegrityFailure(metadata.id.value, "file size changed"),
            )
        }
        if (inspection.checksumSha256 != metadata.checksumSha256) {
            throw AssetValidationException(AssetValidationError.ChecksumMismatch(metadata.id.value))
        }
    }

    private fun inspect(
        category: dev.aether.model.AssetCategory,
        extension: String,
        declaredByteSize: Long,
        file: File,
    ): AssetInspection {
        val actualSize = file.length()
        if (actualSize <= 0L) throw AssetValidationException(AssetValidationError.EmptyAsset)
        if (actualSize > AssetPaths.MAX_ASSET_BYTES) {
            throw AssetValidationException(
                AssetValidationError.AssetTooLarge(actualSize, AssetPaths.MAX_ASSET_BYTES),
            )
        }
        if (declaredByteSize <= 0L) throw AssetValidationException(AssetValidationError.EmptyAsset)
        if (declaredByteSize > AssetPaths.MAX_ASSET_BYTES) {
            throw AssetValidationException(
                AssetValidationError.AssetTooLarge(declaredByteSize, AssetPaths.MAX_ASSET_BYTES),
            )
        }
        if (declaredByteSize != actualSize) {
            throw AssetValidationException(AssetValidationError.SizeMismatch(declaredByteSize, actualSize))
        }
        validateContent(category, extension, file)
        return AssetInspection(actualSize, checksum(file))
    }

    fun checksum(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun validateMetadata(registration: AssetRegistration) {
        val importId = AssetPaths.safeSegment(registration.importId).getOrThrow()
        AssetPaths.safeSegment(registration.assetId.value).getOrThrow()
        if (registration.displayName.isBlank() || registration.displayName.length > 128) {
            throw AssetValidationException(AssetValidationError.InvalidMetadata("display name is blank or too long"))
        }
        val path = registration.stagedRelativePath
        val prefix = "staging/$importId/"
        if (!path.startsWith(prefix)) {
            throw AssetValidationException(AssetValidationError.InvalidAssetPath(path))
        }
        val fileName = path.removePrefix(prefix)
        val safeName = AssetPaths.safeFileName(registration.category, fileName).getOrThrow()
        val extension = AssetPaths.safeSegment(registration.extension.lowercase()).getOrThrow()
        if (safeName.substringAfterLast('.') != extension) {
            throw AssetValidationException(AssetValidationError.InvalidAssetPath(path))
        }
    }

    private fun validateContent(category: dev.aether.model.AssetCategory, extension: String, file: File) {
        if (extension == "svg") throw AssetValidationException(AssetValidationError.UnsafeSvg)
        if (extension !in AssetPaths.allowedExtensions(category)) {
            throw AssetValidationException(AssetValidationError.UnsupportedAssetType(category.name, extension))
        }
        when (category) {
            dev.aether.model.AssetCategory.WALLPAPER,
            dev.aether.model.AssetCategory.ICON,
            dev.aether.model.AssetCategory.THEME_IMAGE,
            dev.aether.model.AssetCategory.THUMBNAIL,
            -> validateImage(extension, file)

            dev.aether.model.AssetCategory.FONT -> validateFont(extension, file)
            dev.aether.model.AssetCategory.OTHER -> validateText(extension, file)
        }
    }

    private fun validateImage(extension: String, file: File) {
        FileInputStream(file).use { stream ->
            val input = DataInputStream(stream)
            val dimensions = when (extension) {
                "png" -> pngDimensions(input)
                "jpg", "jpeg" -> jpegDimensions(input)
                "webp" -> webpDimensions(input, file.length())
                else -> throw AssetValidationException(
                    AssetValidationError.UnsupportedAssetType("IMAGE", extension),
                )
            }
            val (width, height) = dimensions
            if (width <= 0 || height <= 0 || width > MAX_IMAGE_DIMENSION || height > MAX_IMAGE_DIMENSION) {
                throw AssetValidationException(AssetValidationError.InvalidImageDimensions(width, height))
            }
        }
    }

    private fun pngDimensions(input: DataInputStream): Pair<Int, Int> {
        val signature = readExact(input, PNG_SIGNATURE.size, "PNG")
        if (!signature.contentEquals(PNG_SIGNATURE)) {
            throw AssetValidationException(AssetValidationError.InvalidFileSignature("png", "bad signature"))
        }
        val length = input.readInt()
        val type = readExact(input, 4, "PNG")
        if (length != 13 || String(type, StandardCharsets.US_ASCII) != "IHDR") {
            throw AssetValidationException(AssetValidationError.InvalidFileSignature("png", "missing IHDR"))
        }
        return input.readInt() to input.readInt()
    }

    private fun jpegDimensions(input: DataInputStream): Pair<Int, Int> {
        if (input.read() != 0xFF || input.read() != 0xD8) {
            throw AssetValidationException(AssetValidationError.InvalidFileSignature("jpg", "missing SOI"))
        }
        var scanned = 0
        while (scanned < MAX_HEADER_SCAN_BYTES) {
            val marker = nextMarker(input)
            scanned++
            if (marker == 0xD9) break
            if (marker == 0x01 || marker == 0xD8 || marker in 0xD0..0xD7) continue

            val length = input.readUnsignedShort()
            scanned += 2
            if (length < 2) break
            if (marker in SOF_MARKERS) {
                if (length < 7) break
                input.readUnsignedByte()
                val height = input.readUnsignedShort()
                val width = input.readUnsignedShort()
                return width to height
            }
            val skipped = length - 2
            skipFully(input, skipped)
            scanned += skipped
        }
        throw AssetValidationException(AssetValidationError.InvalidFileSignature("jpg", "no frame header"))
    }

    private fun webpDimensions(input: DataInputStream, fileLength: Long): Pair<Int, Int> {
        val riff = readExact(input, 4, "WebP")
        readExact(input, 4, "WebP") // RIFF size
        val webp = readExact(input, 4, "WebP")
        if (String(riff, StandardCharsets.US_ASCII) != "RIFF" ||
            String(webp, StandardCharsets.US_ASCII) != "WEBP"
        ) {
            throw AssetValidationException(AssetValidationError.InvalidFileSignature("webp", "bad RIFF header"))
        }
        var consumed = 12L
        while (consumed < MAX_HEADER_SCAN_BYTES && consumed + 8 <= fileLength) {
            val chunkId = String(readExact(input, 4, "WebP"), StandardCharsets.US_ASCII)
            val chunkSize = littleEndianInt(readExact(input, 4, "WebP")).toLong() and 0xffffffffL
            consumed += 8
            when (chunkId) {
                "VP8X" -> {
                    val trailing = chunkTail(chunkSize, 10, consumed, fileLength)
                    val data = readExact(input, 10, "WebP")
                    skipFully(input, trailing.toInt())
                    return littleEndian24(data, 4) + 1 to littleEndian24(data, 7) + 1
                }

                "VP8 " -> {
                    val trailing = chunkTail(chunkSize, 10, consumed, fileLength)
                    val data = readExact(input, 10, "WebP")
                    if (data[3] != 0x9D.toByte() || data[4] != 0x01.toByte() || data[5] != 0x2A.toByte()) {
                        throw AssetValidationException(
                            AssetValidationError.InvalidFileSignature("webp", "bad VP8 frame"),
                        )
                    }
                    skipFully(input, trailing.toInt())
                    return (bigEndian16(data, 6) and 0x3FFF) to (bigEndian16(data, 8) and 0x3FFF)
                }

                "VP8L" -> {
                    val trailing = chunkTail(chunkSize, 5, consumed, fileLength)
                    val data = readExact(input, 5, "WebP")
                    if (data[0] != 0x2F.toByte()) {
                        throw AssetValidationException(
                            AssetValidationError.InvalidFileSignature("webp", "bad VP8L frame"),
                        )
                    }
                    skipFully(input, trailing.toInt())
                    val bits = littleEndianInt(data, 1)
                    return (bits and 0x3FFF) + 1 to ((bits shr 14) and 0x3FFF) + 1
                }

                else -> {
                    val padded = chunkTail(chunkSize, 0, consumed, fileLength)
                    skipFully(input, padded.toInt())
                    consumed += padded
                }
            }
        }
        throw AssetValidationException(AssetValidationError.InvalidFileSignature("webp", "no image chunk"))
    }

    /** Returns the bytes after a known header; rejects signed/oversized or truncated WebP chunks. */
    private fun chunkTail(
        chunkSize: Long,
        headerBytes: Int,
        consumedHeaderBytes: Long,
        fileLength: Long,
    ): Long {
        val padded = chunkSize + (chunkSize and 1L)
        if (chunkSize < headerBytes ||
            padded > Int.MAX_VALUE ||
            consumedHeaderBytes + padded > fileLength
        ) {
            throw AssetValidationException(AssetValidationError.InvalidFileSignature("webp", "bad chunk length"))
        }
        return padded - headerBytes
    }

    private fun validateFont(extension: String, file: File) {
        FileInputStream(file).use { stream ->
            val input = DataInputStream(stream)
            val header = readExact(input, 12, "font")
            val tag = String(header, 0, 4, StandardCharsets.US_ASCII)
            val validTrueType = extension == "ttf" && (tag == "\u0000\u0001\u0000\u0000" || tag == "true")
            val validOpenType = extension == "otf" && tag == "OTTO"
            if (!validTrueType && !validOpenType) {
                throw AssetValidationException(AssetValidationError.InvalidFont("unsupported sfnt signature"))
            }
            val tableCount = bigEndian16(header, 4)
            if (tableCount !in 1..512 || file.length() < 12L + tableCount * 16L) {
                throw AssetValidationException(AssetValidationError.InvalidFont("bad table directory"))
            }
            repeat(tableCount) {
                val record = readExact(input, 16, "font")
                val offset = unsignedInt(record, 8)
                val length = unsignedInt(record, 12)
                if (offset < 12L || offset + length > file.length()) {
                    throw AssetValidationException(AssetValidationError.InvalidFont("table outside file"))
                }
            }
        }
    }

    private fun validateText(extension: String, file: File) {
        if (file.length() > MAX_TEXT_BYTES) {
            throw AssetValidationException(AssetValidationError.InvalidText("text exceeds $MAX_TEXT_BYTES bytes"))
        }
        val bytes = file.readBytes()
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val text = runCatching { decoder.decode(ByteBuffer.wrap(bytes)).toString() }
            .getOrElse { throw AssetValidationException(AssetValidationError.InvalidText("invalid UTF-8")) }
        if (text.contains('\u0000')) {
            throw AssetValidationException(AssetValidationError.InvalidText("contains NUL byte"))
        }
        if (extension == "json") {
            runCatching { json.parseToJsonElement(text) }
                .onFailure { throw AssetValidationException(AssetValidationError.InvalidText("invalid JSON")) }
        }
    }

    private fun nextMarker(input: DataInputStream): Int {
        while (true) {
            val value = input.read()
            if (value != 0xFF) {
                if (value < 0) throw AssetValidationException(
                    AssetValidationError.InvalidFileSignature("jpg", "unexpected end"),
                )
                continue
            }
            var marker = input.read()
            while (marker == 0xFF) marker = input.read()
            if (marker < 0) throw AssetValidationException(
                AssetValidationError.InvalidFileSignature("jpg", "unexpected end"),
            )
            return marker
        }
    }

    private fun skipFully(input: DataInputStream, count: Int) {
        var remaining = count
        val buffer = ByteArray(8192)
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(remaining, buffer.size))
            if (read < 0) throw AssetValidationException(
                AssetValidationError.InvalidFileSignature("binary", "unexpected end"),
            )
            remaining -= read
        }
    }

    private fun readExact(input: DataInputStream, count: Int, format: String): ByteArray {
        val bytes = ByteArray(count)
        try {
            input.readFully(bytes)
        } catch (failure: Exception) {
            throw AssetValidationException(AssetValidationError.InvalidFileSignature(format, "truncated header"))
        }
        return bytes
    }

    private fun littleEndianInt(bytes: ByteArray, offset: Int = 0): Int =
        (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16) or
            ((bytes[offset + 3].toInt() and 0xff) shl 24)

    private fun littleEndian24(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16)

    private fun bigEndian16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)

    private fun unsignedInt(bytes: ByteArray, offset: Int): Long =
        (((bytes[offset].toInt() and 0xff) shl 24) or
            ((bytes[offset + 1].toInt() and 0xff) shl 16) or
            ((bytes[offset + 2].toInt() and 0xff) shl 8) or
            (bytes[offset + 3].toInt() and 0xff)).toLong() and 0xffffffffL
}
