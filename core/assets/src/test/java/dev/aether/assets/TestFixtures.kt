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
