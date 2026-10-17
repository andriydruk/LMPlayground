package com.druk.lmplayground.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class EmbeddingCodecTest {

    @Test
    fun `roundtrip preserves values exactly`() {
        val vector = FloatArray(768) { i -> (i - 384) * 0.0037f }
        val decoded = EmbeddingCodec.decode(EmbeddingCodec.encode(vector))
        assertArrayEquals(vector, decoded, 0f)
    }

    @Test
    fun `encoding is 4 bytes per float little-endian`() {
        val bytes = EmbeddingCodec.encode(floatArrayOf(1.0f))
        assertEquals(4, bytes.size)
        // 1.0f = 0x3F800000 → little-endian byte order
        assertArrayEquals(byteArrayOf(0x00, 0x00, 0x80.toByte(), 0x3F), bytes)
    }

    @Test
    fun `empty vector roundtrips`() {
        assertEquals(0, EmbeddingCodec.decode(EmbeddingCodec.encode(FloatArray(0))).size)
    }

    @Test
    fun `special values survive`() {
        val vector = floatArrayOf(Float.MAX_VALUE, Float.MIN_VALUE, -0f, Float.NaN)
        val decoded = EmbeddingCodec.decode(EmbeddingCodec.encode(vector))
        assertEquals(vector.toList().toString(), decoded.toList().toString())
    }
}
