package com.druk.lmplayground

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.druk.lmplayground.conversation.GenerationParams
import com.druk.lmplayground.storage.StoragePreferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StoragePreferencesTest {

    private lateinit var prefs: StoragePreferences

    @Before
    fun setup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        prefs = StoragePreferences(context)
    }

    @After
    fun cleanup() {
        prefs.clear()
    }

    @Test
    fun noSavedParamsReturnsNull() {
        assertNull(prefs.getModelGenerationParams("nonexistent-model.gguf"))
    }

    @Test
    fun saveAndLoadParamsRoundtrip() {
        val params = GenerationParams(
            contextSize = 2048,
            temperature = 0.5f,
            topP = 0.9f,
            repetitionPenalty = 1.3f,
            topK = 100,
            minP = 0.02f,
            seed = 123,
            thinkingBudget = 256
        )
        prefs.setModelGenerationParams("model-a.gguf", params.toMap())

        val loaded = prefs.getModelGenerationParams("model-a.gguf")
        assertNotNull(loaded)
        val restored = GenerationParams.fromMap(loaded!!)
        assertEquals(params, restored)
    }

    @Test
    fun differentModelsHaveSeparateParams() {
        val paramsA = GenerationParams(contextSize = 2048, temperature = 0.3f)
        val paramsB = GenerationParams(contextSize = 8192, temperature = 1.5f)

        prefs.setModelGenerationParams("model-a.gguf", paramsA.toMap())
        prefs.setModelGenerationParams("model-b.gguf", paramsB.toMap())

        val loadedA = GenerationParams.fromMap(prefs.getModelGenerationParams("model-a.gguf")!!)
        val loadedB = GenerationParams.fromMap(prefs.getModelGenerationParams("model-b.gguf")!!)

        assertEquals(2048, loadedA.contextSize)
        assertEquals(0.3f, loadedA.temperature)
        assertEquals(8192, loadedB.contextSize)
        assertEquals(1.5f, loadedB.temperature)
    }

    @Test
    fun overwriteParamsForSameModel() {
        val initial = GenerationParams(temperature = 0.5f)
        prefs.setModelGenerationParams("model.gguf", initial.toMap())

        val updated = GenerationParams(temperature = 1.0f)
        prefs.setModelGenerationParams("model.gguf", updated.toMap())

        val loaded = GenerationParams.fromMap(prefs.getModelGenerationParams("model.gguf")!!)
        assertEquals(1.0f, loaded.temperature)
    }

}
