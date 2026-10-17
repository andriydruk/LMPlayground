package com.druk.lmplayground

import com.druk.llamacpp.chat.ResponseProcessor
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import com.druk.llamacpp.LlamaGenerationCallback
import com.druk.llamacpp.LlamaProgressCallback
import com.druk.llamacpp.jni.NativeLlamaCpp
import com.druk.llamacpp.jni.NativeLlamaModel
import com.druk.llamacpp.jni.NativeLlamaSession
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File

/**
 * Instrumented test that loads a real GGUF model and validates that the
 * system prompt surfaced through createSession() actually reaches the model
 * and influences its generation.
 *
 * Setup: copy a small instruction-tuned GGUF model to /data/local/tmp/ on the device:
 *   adb shell "cp /sdcard/Models/Qwen3-0.6B-Q4_K_M.gguf /data/local/tmp/ && chmod 666 /data/local/tmp/Qwen3-0.6B-Q4_K_M.gguf"
 */
@RunWith(AndroidJUnit4::class)
class SystemPromptTest {

    companion object {
        private const val TAG = "SystemPromptTest"
        private val CANDIDATE_MODELS = listOf(
            "gemma-4-E2B-it-Q4_K_M.gguf",
            "Qwen3-0.6B-Q4_K_M.gguf",
            "Qwen_Qwen3.5-0.8B-Q3_K_M.gguf",
            "LFM2.5-1.2B-Thinking-Q4_K_M.gguf",
            "Qwen_Qwen3.5-2B-Q3_K_M.gguf"
        )
        private const val MODELS_PATH = "/data/local/tmp"
    }

    private lateinit var llamaCpp: NativeLlamaCpp
    private var llamaModel: NativeLlamaModel? = null
    private var session: NativeLlamaSession? = null

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        llamaCpp = NativeLlamaCpp()
        llamaCpp.init(context.applicationInfo.nativeLibraryDir)
    }

    @After
    fun tearDown() {
        session?.destroy()
        session = null
        llamaModel?.unloadModel()
        llamaModel = null
    }

    private fun findModel(): File? {
        for (name in CANDIDATE_MODELS) {
            val file = File(MODELS_PATH, name)
            if (file.exists() && file.canRead()) return file
        }
        return null
    }

    private fun loadModel(modelFile: File): NativeLlamaModel {
        Log.d(TAG, "Loading model: ${modelFile.name} (${modelFile.length() / 1024 / 1024}MB)")
        val model = llamaCpp.loadModel(
            modelFile.absolutePath,
            object : LlamaProgressCallback {
                override fun onProgress(progress: Float) {}
            },
            disableRepack = false,
        ) ?: error("native loadModel returned null for ${modelFile.absolutePath}")
        llamaModel = model
        return model
    }

    private fun generateFullResponse(
        session: NativeLlamaSession,
        maxTokens: Int = 512,
        timeoutMs: Long = 90_000
    ): String {
        var lastResponse = ""
        var tokenCount = 0
        val deadline = System.currentTimeMillis() + timeoutMs
        val callback = object : LlamaGenerationCallback {
            override fun onFullResponse(response: String) {
                lastResponse = response
            }
        }
        while (session.generate(callback) == 0) {
            tokenCount++
            if (tokenCount >= maxTokens) break
            if (System.currentTimeMillis() > deadline) break
        }
        return lastResponse
    }

    @Test(timeout = 180_000)
    fun testSystemPromptOverridesLanguage() {
        val modelFile = findModel()
        assumeTrue("No model in $MODELS_PATH", modelFile != null)

        val model = loadModel(modelFile!!)
        val systemPrompt = "You must answer every question in French. Never use English."
        val session = model.createSession(
            4096, 0.3f, 0.95f, 1.0f, 40, 0.05f, 42, -1, systemPrompt
        )!!
        this.session = session

        session.addMessage("What is the capital of Germany?", false)
        val raw = generateFullResponse(session)
        val clean = ResponseProcessor.process(raw).trim()
        Log.d(TAG, "Response (French system prompt):\n$clean")

        assertTrue("Response should not be empty", clean.isNotBlank())

        val lower = clean.lowercase()
        val frenchMarkers = listOf("berlin", "capitale", "allemagne", "la ", "est ")
        val matched = frenchMarkers.count { lower.contains(it) }
        assertTrue(
            "Response should look French (matched $matched of ${frenchMarkers.size} markers): $clean",
            matched >= 2
        )
        assertFalse(
            "Response should not contain the English phrase 'The capital of': $clean",
            lower.contains("the capital of")
        )
    }

    @Test(timeout = 180_000)
    fun testSystemPromptConstrainsFormat() {
        val modelFile = findModel()
        assumeTrue("No model in $MODELS_PATH", modelFile != null)

        val model = loadModel(modelFile!!)
        val systemPrompt = "Respond with exactly one word. No punctuation, no explanation, no preamble."
        val session = model.createSession(
            4096, 0.3f, 0.95f, 1.0f, 40, 0.05f, 42, -1, systemPrompt
        )!!
        this.session = session

        session.addMessage("Name a color.", false)
        val raw = generateFullResponse(session, maxTokens = 64)
        val clean = ResponseProcessor.process(raw).trim()
        Log.d(TAG, "Response (one-word system prompt):\n$clean")

        assertTrue("Response should not be empty", clean.isNotBlank())
        val wordCount = clean.split(Regex("\\s+")).filter { it.isNotBlank() }.size
        assertTrue(
            "Response should be at most 3 tokens (got $wordCount): $clean",
            wordCount <= 3
        )
    }

    @Test(timeout = 180_000)
    fun testEmptySystemPromptIsNoOp() {
        val modelFile = findModel()
        assumeTrue("No model in $MODELS_PATH", modelFile != null)

        val model = loadModel(modelFile!!)
        val session = model.createSession(
            4096, 0.8f, 0.95f, 1.0f, 40, 0.05f, -1, -1, ""
        )!!
        this.session = session

        session.addMessage("Say hello in one short sentence.", false)
        val raw = generateFullResponse(session, maxTokens = 128)
        val clean = ResponseProcessor.process(raw).trim()
        Log.d(TAG, "Response (empty system prompt):\n$clean")

        assertTrue("Empty prompt should still generate non-empty output", clean.isNotBlank())
    }
}
