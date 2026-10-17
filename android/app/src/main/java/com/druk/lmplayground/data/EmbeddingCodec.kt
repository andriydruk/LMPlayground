package com.druk.lmplayground.data

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * FloatArray ↔ BLOB codec for embedding vectors. Fixed little-endian
 * float32 layout so stored vectors are stable across devices.
 */
object EmbeddingCodec {

    fun encode(vector: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(vector.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        buffer.asFloatBuffer().put(vector)
        return buffer.array()
    }

    fun decode(bytes: ByteArray): FloatArray {
        val floats = FloatArray(bytes.size / 4)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(floats)
        return floats
    }
}
