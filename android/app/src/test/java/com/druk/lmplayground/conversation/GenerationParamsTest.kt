package com.druk.lmplayground.conversation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GenerationParamsTest {

    @Test
    fun `default thinkingBudget is 25 percent of contextSize`() {
        val params = GenerationParams()
        assertEquals(1024, params.thinkingBudget) // 4096 / 4
    }

    @Test
    fun `custom contextSize adjusts default thinkingBudget`() {
        val params = GenerationParams(contextSize = 8192)
        assertEquals(2048, params.thinkingBudget)
    }

    @Test
    fun `toMap contains all fields`() {
        val params = GenerationParams(
            contextSize = 2048,
            temperature = 0.5f,
            topP = 0.9f,
            repetitionPenalty = 1.2f,
            topK = 50,
            minP = 0.1f,
            seed = 42,
            thinkingBudget = 512
        )
        val map = params.toMap()
        assertEquals(2048f, map["contextSize"])
        assertEquals(0.5f, map["temperature"])
        assertEquals(0.9f, map["topP"])
        assertEquals(1.2f, map["repetitionPenalty"])
        assertEquals(50f, map["topK"])
        assertEquals(0.1f, map["minP"])
        assertEquals(42f, map["seed"])
        assertEquals(512f, map["thinkingBudget"])
    }

    @Test
    fun `fromMap restores all fields`() {
        val original = GenerationParams(
            contextSize = 2048,
            temperature = 0.5f,
            topP = 0.9f,
            repetitionPenalty = 1.2f,
            topK = 50,
            minP = 0.1f,
            seed = 42,
            thinkingBudget = 512
        )
        val restored = GenerationParams.fromMap(original.toMap())
        assertEquals(original, restored)
    }

    @Test
    fun `roundtrip preserves defaults`() {
        val original = GenerationParams()
        val restored = GenerationParams.fromMap(original.toMap())
        assertEquals(original, restored)
    }

    @Test
    fun `roundtrip preserves negative seed`() {
        val original = GenerationParams(seed = -1)
        val restored = GenerationParams.fromMap(original.toMap())
        assertEquals(-1, restored.seed)
    }

    @Test
    fun `fromMap with empty map returns defaults`() {
        val params = GenerationParams.fromMap(emptyMap())
        assertEquals(GenerationParams(), params)
    }

    @Test
    fun `fromMap with partial map fills missing with defaults`() {
        val map = mapOf("temperature" to 1.5f, "contextSize" to 8192f)
        val params = GenerationParams.fromMap(map)
        assertEquals(8192, params.contextSize)
        assertEquals(1.5f, params.temperature)
        assertEquals(0.95f, params.topP) // default
        assertEquals(40, params.topK)    // default
        assertEquals(2048, params.thinkingBudget) // 8192 / 4
    }

    @Test
    fun `fromMap thinkingBudget uses stored value not computed default`() {
        val map = mapOf("contextSize" to 8192f, "thinkingBudget" to 100f)
        val params = GenerationParams.fromMap(map)
        assertEquals(100, params.thinkingBudget)
    }
}
