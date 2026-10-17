package com.druk.lmplayground.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MmprojPairing is pure string logic (no Android deps), so it runs as a plain
 * JUnit test. Cases cover the real catalog naming conventions plus custom /
 * sideloaded patterns and the false-positive guards.
 */
class MmprojPairingTest {

    @Test
    fun isMmproj_detectsMarkerAnywhere() {
        assertTrue(MmprojPairing.isMmproj("mmproj-gemma-4-E2B-it-BF16.gguf"))
        assertTrue(MmprojPairing.isMmproj("gemma-3-4b-it-mmproj-f16.gguf"))
        assertFalse(MmprojPairing.isMmproj("gemma-3-4b-it-Q4_K_M.gguf"))
    }

    @Test
    fun pairs_prefixMarkerConvention() {
        // mmproj-<core>-<fmt>
        assertEquals(
            "mmproj-Qwen_Qwen3.5-0.8B-f16.gguf",
            MmprojPairing.findMmprojFor(
                "Qwen_Qwen3.5-0.8B-Q3_K_M.gguf",
                listOf("Qwen_Qwen3.5-0.8B-Q3_K_M.gguf", "mmproj-Qwen_Qwen3.5-0.8B-f16.gguf"),
            ),
        )
    }

    @Test
    fun pairs_infixMarkerConvention() {
        // <core>-mmproj-<fmt>
        assertEquals(
            "gemma-3-4b-it-mmproj-f16.gguf",
            MmprojPairing.findMmprojFor(
                "gemma-3-4b-it-Q4_K_M.gguf",
                listOf("gemma-3-4b-it-Q4_K_M.gguf", "gemma-3-4b-it-mmproj-f16.gguf"),
            ),
        )
    }

    @Test
    fun pairs_whenQuantTagsDifferBetweenModelAndProjector() {
        // Base carries the quant inline (q4_0), projector carries BF16.
        assertEquals(
            "mmproj-gemma-4-E2B-it-BF16.gguf",
            MmprojPairing.findMmprojFor(
                "gemma-4-E2B_q4_0-it.gguf",
                listOf("gemma-4-E2B_q4_0-it.gguf", "mmproj-gemma-4-E2B-it-BF16.gguf"),
            ),
        )
    }

    @Test
    fun pairs_customSideloadedNaming() {
        assertEquals(
            "my-llava-7b-mmproj-f16.gguf",
            MmprojPairing.findMmprojFor(
                "my-llava-7b-Q5_K_M.gguf",
                listOf("my-llava-7b-Q5_K_M.gguf", "my-llava-7b-mmproj-f16.gguf"),
            ),
        )
    }

    @Test
    fun disambiguates_betweenSiblingSizesAndVariants() {
        val files = listOf(
            "gemma-4-E2B_q4_0-it.gguf",
            "gemma-4-E4B_q4_0-it.gguf",
            "mmproj-gemma-4-E2B-it-BF16.gguf",
            "mmproj-gemma-4-E4B-it-BF16.gguf",
            "Ministral-3-3B-Instruct-2512-Q4_K_M.gguf",
            "mmproj-Ministral-3-3B-Instruct-2512-F16.gguf",
            "mmproj-Ministral-3-3B-Reasoning-2512-F16.gguf",
        )
        assertEquals("mmproj-gemma-4-E2B-it-BF16.gguf", MmprojPairing.findMmprojFor("gemma-4-E2B_q4_0-it.gguf", files))
        assertEquals("mmproj-gemma-4-E4B-it-BF16.gguf", MmprojPairing.findMmprojFor("gemma-4-E4B_q4_0-it.gguf", files))
        assertEquals(
            "mmproj-Ministral-3-3B-Instruct-2512-F16.gguf",
            MmprojPairing.findMmprojFor("Ministral-3-3B-Instruct-2512-Q4_K_M.gguf", files),
        )
    }

    @Test
    fun returnsNull_whenNoProjectorPresent() {
        assertNull(
            MmprojPairing.findMmprojFor(
                "Llama-3.2-3B-Instruct-Q4_K_M.gguf",
                listOf("Llama-3.2-3B-Instruct-Q4_K_M.gguf", "Qwen3-4B-Q4_K_M.gguf"),
            ),
        )
    }

    @Test
    fun returnsNull_whenNoProjectorMatchesByName() {
        // A projector is present, but for an unrelated model.
        assertNull(
            MmprojPairing.findMmprojFor(
                "Llama-3.2-3B-Instruct-Q4_K_M.gguf",
                listOf("Llama-3.2-3B-Instruct-Q4_K_M.gguf", "mmproj-gemma-4-E2B-it-BF16.gguf"),
            ),
        )
    }

    @Test
    fun returnsNull_forAProjectorAskingForItsOwnProjector() {
        assertNull(
            MmprojPairing.findMmprojFor(
                "mmproj-gemma-4-E2B-it-BF16.gguf",
                listOf("mmproj-gemma-4-E2B-it-BF16.gguf"),
            ),
        )
    }

    @Test
    fun everyCatalogVisionModelPairsToItsDeclaredProjector() {
        // Guards the heuristic against the real catalog: each declared
        // (model, mmproj) pair must resolve when both files are on disk.
        val all = ModelInfoProvider.allModels.filter { it.isVision }
        assertTrue("catalog should have vision models", all.isNotEmpty())
        for (m in all) {
            val onDisk = listOf(m.filename, m.mmprojFilename!!)
            assertEquals(
                "pairing for ${m.filename}",
                m.mmprojFilename,
                MmprojPairing.findMmprojFor(m.filename, onDisk),
            )
        }
    }
}
