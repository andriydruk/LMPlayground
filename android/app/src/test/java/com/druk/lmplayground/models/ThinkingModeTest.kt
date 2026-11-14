package com.druk.lmplayground.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the measured thinking behaviour that the chat toggle is gated on.
 *
 * These values come from running the model — a template's own flag disagrees
 * with the weights often enough that trusting it gave the user a switch that
 * did nothing in one direction or the other. Changing an entry here changes
 * what the UI offers, so it should only follow a fresh measurement, not a
 * guess.
 */
class ThinkingModeTest {

    private fun byFile(filename: String): ModelInfo =
        requireNotNull(ModelInfoProvider.allModels.find { it.filename == filename }) {
            "Model not found: $filename"
        }

    @Test
    fun modelsThatNeverThinkAreNotOfferedTheToggle() {
        listOf(
            "Ministral-3-3B-Instruct-2512-Q4_K_M.gguf",
            "LFM2.5-1.2B-Instruct-Q4_K_M.gguf",
            "LFM2.5-350M-Q4_K_M.gguf",
        ).forEach {
            assertEquals("$it never emits a think block", ThinkingMode.NONE, byFile(it).thinkingMode)
        }
    }

    @Test
    fun reasoningModelsAreMarkedAlwaysThinking() {
        listOf(
            "DeepSeek-R1-Distill-Qwen-1.5B-Q4_K_M.gguf",
            "LFM2.5-1.2B-Thinking-Q4_K_M.gguf",
            "LFM2.5-2.6B-Q4_K_M.gguf",
            "Ministral-3-3B-Reasoning-2512-Q4_K_M.gguf",
        ).forEach {
            assertEquals(
                "$it keeps reasoning with thinking disabled",
                ThinkingMode.ALWAYS, byFile(it).thinkingMode,
            )
        }
    }

    @Test
    fun modelsHonouringTheToggleAreMarkedOptional() {
        listOf(
            "Qwen_Qwen3.5-2B-IQ4_XS.gguf",
            "gemma-4-E2B_q4_0-it.gguf",
            "HuggingFaceTB_SmolLM3-3B-Q4_K_M.gguf",
        ).forEach {
            assertEquals(
                "$it honours the toggle in both directions",
                ThinkingMode.OPTIONAL, byFile(it).thinkingMode,
            )
        }
    }

    /**
     * A measured NONE contradicting the catalog's own THINKING_CAPABLE badge is
     * the case that started this: the badge and the template both said yes, the
     * model said no.
     */
    @Test
    fun measuredNoneOverridesTheThinkingBadge() {
        val ministral = byFile("Ministral-3-3B-Instruct-2512-Q4_K_M.gguf")
        assertEquals(ThinkingMode.NONE, ministral.thinkingMode)
        assertTrue(
            "the badge is allowed to disagree — the measurement is what the UI follows",
            !ministral.supportsThinking || ministral.thinkingMode == ThinkingMode.NONE,
        )
    }

    @Test
    fun unmeasuredModelsFallBackToTheTemplateFlag() {
        // Nothing was measured for the legacy Qwen 3 entries, so they must stay
        // UNKNOWN rather than be guessed into a mode.
        assertEquals(ThinkingMode.UNKNOWN, byFile("Qwen3-0.6B-Q4_K_M.gguf").thinkingMode)
    }

    @Test
    fun everyDeclaredModeNamesARealCatalogFile() {
        val known = ModelInfoProvider.allModels.map { it.filename }.toSet()
        ModelInfoProvider.allModels
            .filter { it.thinkingMode != ThinkingMode.UNKNOWN }
            .forEach {
                assertTrue("${it.filename} declared a mode but is not in the catalog",
                    it.filename in known)
            }
        assertNotEquals(
            "no thinking modes are declared at all — the table was lost",
            0, ModelInfoProvider.allModels.count { it.thinkingMode != ThinkingMode.UNKNOWN },
        )
    }
}
