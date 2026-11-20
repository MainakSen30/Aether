package dev.aether.assets

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal fun pngBytes(width: Int = 1, height: Int = 1): ByteArray {
    val buffer = ByteBuffer.allocate(33).order(ByteOrder.BIG_ENDIAN)
    buffer.put(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
    buffer.putInt(13)
    buffer.put("IHDR".toByteArray(Charsets.US_ASCII))
    buffer.putInt(width)
    buffer.putInt(height)
    buffer.put(byteArrayOf(8, 2, 0, 0, 0))
    buffer.putInt(0)
    return buffer.array()
}

internal val PNG_BYTES = pngBytes()

internal fun jpegBytes(width: Int = 1, height: Int = 1): ByteArray {
    val buffer = ByteBuffer.allocate(17).order(ByteOrder.BIG_ENDIAN)
    buffer.put(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
    buffer.put(byteArrayOf(0xFF.toByte(), 0xC0.toByte(), 0x00, 0x0B, 0x08))
    buffer.putShort(height.toShort())
    buffer.putShort(width.toShort())
    buffer.put(byteArrayOf(0x01, 0x01, 0x11, 0x00, 0xFF.toByte(), 0xD9.toByte()))
    return buffer.array()
}

internal data class WebpChunk(val id: String, val size: Int, val payload: ByteArray = ByteArray(0))

internal fun webpBytes(vararg chunks: WebpChunk): ByteArray {
    val fileSize = 12 + chunks.sumOf { 8 + it.payload.size }
    val buffer = ByteBuffer.allocate(fileSize).order(ByteOrder.LITTLE_ENDIAN)
    buffer.put("RIFF".toByteArray(Charsets.US_ASCII))
    buffer.putInt(fileSize - 8)
    buffer.put("WEBP".toByteArray(Charsets.US_ASCII))
    chunks.forEach { chunk ->
        require(chunk.id.length == 4)
        buffer.put(chunk.id.toByteArray(Charsets.US_ASCII))
        buffer.putInt(chunk.size)
        buffer.put(chunk.payload)
    }
    return buffer.array()
}

internal fun vp8xPayload(width: Int, height: Int): ByteArray {
    val payload = ByteArray(10)
    val w = width - 1
    val h = height - 1
    payload[4] = w.toByte()
    payload[5] = (w shr 8).toByte()
    payload[6] = (w shr 16).toByte()
    payload[7] = h.toByte()
    payload[8] = (h shr 8).toByte()
    payload[9] = (h shr 16).toByte()
    return payload
}

internal fun fontBytes(extension: String, tableOffset: Long = 12L): ByteArray {
    val buffer = ByteBuffer.allocate(32).order(ByteOrder.BIG_ENDIAN)
    if (extension == "otf") {
        buffer.put("OTTO".toByteArray(Charsets.US_ASCII))
    } else {
        buffer.put(byteArrayOf(0x00, 0x01, 0x00, 0x00))
    }
    buffer.putShort(1)
    buffer.putShort(0)
    buffer.putShort(0)
    buffer.putShort(0)
    buffer.put("name".toByteArray(Charsets.US_ASCII))
    buffer.putInt(0)
    buffer.putInt(tableOffset.toInt())
    buffer.putInt(4)
    buffer.putInt(0)
    return buffer.array()
}
