package com.druk.lmplayground.dictation

import com.druk.lmplayground.models.ModelInfoProvider
import com.druk.lmplayground.models.supportsLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The dictation model is downloaded and stored like a chat model but must never
 * behave like one — these are the properties the rest of the app relies on.
 */
class DictationModelTest {

    private val dictationModel = ModelInfoProvider.dictationModel

    @Test
    fun `dictation model is hidden from the chat model picker`() {
        assertFalse(
            "Parakeet can't chat — it must not appear in the model list",
            ModelInfoProvider.allModels.any { it.filename == dictationModel.filename },
        )
    }

    @Test
    fun `dictation model is a known filename so storage does not call it a custom model`() {
        assertTrue(dictationModel.filename in ModelInfoProvider.knownFilenames)
    }

    @Test
    fun `dictation model resolves by filename`() {
        assertEquals(dictationModel, ModelInfoProvider.getByFilename(dictationModel.filename))
        assertEquals(dictationModel.name, ModelInfoProvider.getDisplayName(dictationModel.filename))
    }

    @Test
    fun `dictation model is downloadable and not a vision model`() {
        assertFalse("A remote URI is required for the download flow", dictationModel.isCustom)
        assertFalse(dictationModel.isVision)
    }

    @Test
    fun `dictation model declares its European language coverage`() {
        // Parakeet v3 covers 25 European languages; CJK is explicitly absent.
        assertEquals(25, dictationModel.supportedLanguages.size)
        listOf("en", "de", "uk", "ru", "pl").forEach {
            assertTrue("expected $it", dictationModel.supportsLanguage(it))
        }
        listOf("zh", "ja", "ko").forEach {
            assertFalse("unexpected $it", dictationModel.supportsLanguage(it))
        }
    }
}
