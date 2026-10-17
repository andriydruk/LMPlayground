package com.druk.lmplayground.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gating rule matters more than the deletion: these files live in the
 * user's own model folder, and removing one prematurely costs a multi-GB
 * re-download — or leaves a vision model with no projector at all.
 */
class SupersededFileCleanupTest {

    private val bf16 = "mmproj-gemma-4-E2B-it-BF16.gguf"
    private val q8 = "mmproj-gemma-4-E2B-it-Q8_0.gguf"

    @Test
    fun deletesRetiredFileOnceReplacementIsPresent() {
        assertEquals(listOf(bf16), SupersededFileCleanup.selectDeletable(setOf(bf16, q8)))
    }

    @Test
    fun keepsRetiredFileWhileReplacementIsMissing() {
        assertTrue(
            "must not delete the only projector the user has",
            SupersededFileCleanup.selectDeletable(setOf(bf16)).isEmpty(),
        )
    }

    @Test
    fun ignoresFoldersWithNeitherFile() {
        assertTrue(SupersededFileCleanup.selectDeletable(setOf("Qwen3-0.6B-Q4_K_M.gguf")).isEmpty())
    }

    @Test
    fun replacementAloneIsNotDeleted() {
        assertTrue(SupersededFileCleanup.selectDeletable(setOf(q8)).isEmpty())
    }

    @Test
    fun qwen35Q3IsRetiredOnceIq4xsArrives() {
        val q3 = "Qwen_Qwen3.5-2B-Q3_K_M.gguf"
        val iq4 = "Qwen_Qwen3.5-2B-IQ4_XS.gguf"
        assertEquals(listOf(q3), SupersededFileCleanup.selectDeletable(setOf(q3, iq4)))
        assertTrue(SupersededFileCleanup.selectDeletable(setOf(q3)).isEmpty())
    }

    @Test
    fun everySupersededEntryPointsAtACatalogFile() {
        val known = com.druk.lmplayground.models.ModelInfoProvider.knownFilenames
        SupersededFileCleanup.supersededFilenames().forEach { retired ->
            assertTrue(
                "$retired is retired but still referenced by the catalog",
                retired !in known,
            )
        }
    }
}
