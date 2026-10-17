package com.druk.lmplayground

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
 * Instrumented tests for replayHistory — validates that a new native session
 * can reconstruct conversation history and continue generating coherently.
 *
 * This covers the model-switch and session-restore flows:
 *   1. Build a multi-turn conversation on session A
 *   2. Destroy session A, create session B on the same (or different) model
 *   3. Replay history into session B
 *   4. Continue the conversation and verify generation works
 *
 * Setup: adb shell "cp /sdcard/Models/Qwen3-0.6B-Q4_K_M.gguf /data/local/tmp/"
 */
@RunWith(AndroidJUnit4::class)
class ReplayHistoryTest {

    companion object {
        private const val TAG = "ReplayHistoryTest"
        private val CANDIDATE_MODELS = listOf(
            "Qwen3-0.6B-Q4_K_M.gguf",
            "Qwen_Qwen3.5-0.8B-Q3_K_M.gguf",
            "LFM2.5-1.2B-Thinking-Q4_K_M.gguf",
            "Qwen_Qwen3.5-2B-Q3_K_M.gguf",
            "DeepSeek-R1-Distill-Qwen-1.5B-Q4_K_M.gguf"
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
        Log.d(TAG, "Loading model: ${modelFile.name}")
        val model = llamaCpp.loadModel(
            modelFile.absolutePath,
            object : LlamaProgressCallback {
                override fun onProgress(progress: Float) {
                    Log.d(TAG, "Loading: ${(progress * 100).toInt()}%")
                }
            },
            disableRepack = false,
        ) ?: error("native loadModel returned null for ${modelFile.absolutePath}")
        llamaModel = model
        return model
    }

    private fun generateFullResponse(
        session: NativeLlamaSession,
        maxTokens: Int = 512,
        timeoutMs: Long = 120_000
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
        Log.d(TAG, "Generated $tokenCount tokens, ${lastResponse.length} chars")
        return lastResponse
    }

    /**
     * Core replay test: build 2-turn conversation, destroy session,
     * create new session, replay history, then continue with a 3rd turn.
     */
    @Test(timeout = 300_000)
    fun testReplayHistoryAndContinueGeneration() {
        val modelFile = findModel()
        assumeTrue("No model in $MODELS_PATH", modelFile != null)

        val model = loadModel(modelFile!!)

        // --- Session A: build a 2-turn conversation ---
        val sessionA = model.createSession(4096, 0.8f, 0.95f, 1.0f, 40, 0.05f, -1, -1, "")!!
        this.session = sessionA

        // Turn 1
        sessionA.addMessage("My name is Alice. Say hello to me.", false)
        val r1 = generateFullResponse(sessionA, maxTokens = 256, timeoutMs = 60_000)
        Log.d(TAG, "Session A turn 1:\n$r1")
        assertTrue("Turn 1 should not be empty", r1.isNotBlank())

        // Turn 2
        sessionA.addMessage("What is 2 + 3?", false)
        val r2 = generateFullResponse(sessionA, maxTokens = 256, timeoutMs = 60_000)
        Log.d(TAG, "Session A turn 2:\n$r2")
        assertTrue("Turn 2 should not be empty", r2.isNotBlank())

        // Destroy session A (simulates model switch / app restart)
        sessionA.destroy()
        this.session = null

        // --- Session B: new session, replay history, continue ---
        val sessionB = model.createSession(4096, 0.8f, 0.95f, 1.0f, 40, 0.05f, -1, -1, "")!!
        this.session = sessionB

        sessionB.replayHistory(
            userMessages = arrayOf("My name is Alice. Say hello to me.", "What is 2 + 3?"),
            assistantMessages = arrayOf(r1, r2)
        )

        // Turn 3: continue the conversation to verify the session is functional
        sessionB.addMessage("Now what is 10 + 20?", false)
        val r3 = generateFullResponse(sessionB, maxTokens = 256, timeoutMs = 60_000)
        Log.d(TAG, "Session B turn 3 (after replay):\n$r3")
        assertTrue("Turn 3 after replay should not be empty", r3.isNotBlank())

        // The model should produce a coherent math answer
        assertTrue(
            "After replaying history, model should answer the math question. Got: $r3",
            r3.contains("30")
        )
    }

    /**
     * Verify that replayHistory on an empty history is a no-op and
     * the session can still generate normally afterward.
     */
    @Test(timeout = 180_000)
    fun testReplayEmptyHistory() {
        val modelFile = findModel()
        assumeTrue("No model in $MODELS_PATH", modelFile != null)

        val model = loadModel(modelFile!!)
        val session = model.createSession(4096, 0.8f, 0.95f, 1.0f, 40, 0.05f, -1, -1, "")!!
        this.session = session

        // Replay with empty arrays
        session.replayHistory(
            userMessages = emptyArray(),
            assistantMessages = emptyArray()
        )

        // Should still be able to generate normally
        session.addMessage("Say hello in one sentence", false)
        val response = generateFullResponse(session, maxTokens = 256, timeoutMs = 60_000)
        Log.d(TAG, "After empty replay:\n$response")
        assertTrue("Should generate after empty replay", response.isNotBlank())
    }

    /**
     * Replay a single turn and verify the session can continue generating.
     */
    @Test(timeout = 180_000)
    fun testReplaySingleTurn() {
        val modelFile = findModel()
        assumeTrue("No model in $MODELS_PATH", modelFile != null)

        val model = loadModel(modelFile!!)

        // Session A: one turn
        val sessionA = model.createSession(4096, 0.8f, 0.95f, 1.0f, 40, 0.05f, -1, -1, "")!!
        this.session = sessionA

        sessionA.addMessage("What is 5 + 5?", false)
        val r1 = generateFullResponse(sessionA, maxTokens = 256, timeoutMs = 60_000)
        Log.d(TAG, "Session A turn 1:\n$r1")
        assertTrue("Turn 1 should not be empty", r1.isNotBlank())

        sessionA.destroy()
        this.session = null

        // Session B: replay + continue
        val sessionB = model.createSession(4096, 0.8f, 0.95f, 1.0f, 40, 0.05f, -1, -1, "")!!
        this.session = sessionB

        sessionB.replayHistory(
            userMessages = arrayOf("What is 5 + 5?"),
            assistantMessages = arrayOf(r1)
        )

        sessionB.addMessage("Now add 3 to that result", false)
        val r2 = generateFullResponse(sessionB, maxTokens = 256, timeoutMs = 60_000)
        Log.d(TAG, "Session B turn 2:\n$r2")
        assertTrue("Should generate after single-turn replay", r2.isNotBlank())
        // 10 + 3 = 13 — the model should produce "13" if it recalls the prior turn
        assertTrue(
            "Model should compute 13 (5+5+3) from replayed context. Got: $r2",
            r2.contains("13")
        )
    }

    /**
     * Simulate a full model reload: unload model, reload from disk,
     * create fresh session, replay history, and continue.
     */
    @Test(timeout = 300_000)
    fun testFullModelReloadWithReplay() {
        val modelFile = findModel()
        assumeTrue("No model in $MODELS_PATH", modelFile != null)

        // --- Load model and do one turn ---
        val model1 = loadModel(modelFile!!)
        val session1 = model1.createSession(4096, 0.8f, 0.95f, 1.0f, 40, 0.05f, -1, -1, "")!!
        this.session = session1

        session1.addMessage("The capital of France is Paris. Remember that.", false)
        val r1 = generateFullResponse(session1, maxTokens = 256, timeoutMs = 60_000)
        Log.d(TAG, "Pre-reload turn 1:\n$r1")
        assertTrue("Turn 1 should not be empty", r1.isNotBlank())

        // Save the history
        val userMessages = arrayOf("The capital of France is Paris. Remember that.")
        val assistantMessages = arrayOf(r1)

        // --- Tear down model completely ---
        session1.destroy()
        this.session = null
        model1.unloadModel()
        llamaModel = null

        // --- Reload model from scratch ---
        Log.d(TAG, "Reloading model from scratch")
        val model2 = loadModel(modelFile)
        val session2 = model2.createSession(4096, 0.8f, 0.95f, 1.0f, 40, 0.05f, -1, -1, "")!!
        this.session = session2

        // Replay the saved history
        session2.replayHistory(userMessages, assistantMessages)

        // Continue conversation
        session2.addMessage("What is the capital of France?", false)
        val r2 = generateFullResponse(session2, maxTokens = 256, timeoutMs = 60_000)
        Log.d(TAG, "Post-reload turn 2:\n$r2")
        assertTrue("Should generate after full model reload + replay", r2.isNotBlank())

        val r2Lower = r2.lowercase()
        assertTrue(
            "Model should know Paris is the capital after replay. Got: $r2",
            r2Lower.contains("paris")
        )
    }

    /**
     * Replay with thinking-enabled conversation history, then continue
     * with thinking enabled.
     */
    @Test(timeout = 300_000)
    fun testReplayWithThinkingHistory() {
        val modelFile = findModel()
        assumeTrue("No model in $MODELS_PATH", modelFile != null)

        val model = loadModel(modelFile!!)
        assumeTrue("Model doesn't support thinking", model.supportsThinking())

        // Session A: thinking enabled
        val sessionA = model.createSession(4096, 0.8f, 0.95f, 1.0f, 40, 0.05f, -1, -1, "")!!
        this.session = sessionA

        sessionA.addMessage("What is 7 * 8?", true)
        val r1 = generateFullResponse(sessionA, maxTokens = 512, timeoutMs = 60_000)
        Log.d(TAG, "Thinking turn 1:\n$r1")
        assertTrue("Turn 1 should not be empty", r1.isNotBlank())

        sessionA.destroy()
        this.session = null

        // Session B: replay and continue with thinking
        val sessionB = model.createSession(4096, 0.8f, 0.95f, 1.0f, 40, 0.05f, -1, -1, "")!!
        this.session = sessionB

        sessionB.replayHistory(
            userMessages = arrayOf("What is 7 * 8?"),
            assistantMessages = arrayOf(r1)
        )

        sessionB.addMessage("Now what is that result plus 10?", true)
        val r2 = generateFullResponse(sessionB, maxTokens = 512, timeoutMs = 60_000)
        Log.d(TAG, "Thinking turn 2 after replay:\n$r2")
        assertTrue("Should generate with thinking after replay", r2.isNotBlank())
    }
}
