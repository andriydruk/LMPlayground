package com.druk.lmplayground

import android.app.Application
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.druk.llamacpp.InferenceState
import com.druk.llamacpp.LlamaGenerationSession
import com.druk.llamacpp.LlamaModel
import com.druk.llamacpp.LlamaProgressCallback
import com.druk.lmplayground.conversation.ConversationViewModel
import com.druk.lmplayground.conversation.Message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Regression tests for ConversationViewModel's generation lifecycle around
 * tool calls.
 *
 * Bug: after a tool call, [ConversationViewModel.addMessage]'s cleanup
 * uses `Message.responseStartTimeMs` as the "is this still our
 * placeholder?" identity. But [ConversationUiState.addToolCallsToLastMessage]
 * resets `responseStartTimeMs` to start a fresh post-tool timer, so the
 * identity check always fails after the first tool call — the cleanup
 * bails out early, `_isGenerating` stays at true, and the Stop button
 * has nothing to cancel.
 *
 * These tests drive a real ConversationViewModel against a tool-capable
 * model copied to /data/local/tmp/, and assert that `isGenerating`
 * returns to false after a generation that involved a tool call.
 *
 * Setup:
 *   adb shell "cp /sdcard/Models/gemma-4-E2B-it-Q4_K_M.gguf /data/local/tmp/"
 */
@RunWith(AndroidJUnit4::class)
class ConversationViewModelToolCallTest {

    companion object {
        private const val TAG = "VMToolCallTest"

        private val CANDIDATE_MODELS = listOf(
            "gemma-4-E2B-it-Q4_K_M.gguf",
            "Qwen3-0.6B-Q4_K_M.gguf",
            "Qwen_Qwen3.5-0.8B-Q3_K_M.gguf"
        )
        private const val MODELS_PATH = "/data/local/tmp"

        // Tool-using prompt should fit in ~180s on a Pixel 7 Pro for
        // the smaller models, ~3 min for Gemma 4 E2B. We're testing
        // for a stuck `isGenerating` flag, not raw speed.
        private const val GEN_TIMEOUT_MS = 240_000L
    }

    private lateinit var app: App
    private var viewModel: ConversationViewModel? = null
    private var injectedModel: LlamaModel? = null
    private var injectedSession: LlamaGenerationSession? = null

    @After
    fun tearDown() {
        // Cancel any in-flight generation and tear down injected handles.
        runBlocking(Dispatchers.Main) {
            viewModel?.cancelGeneration()
        }
        try { injectedSession?.destroy() } catch (_: Throwable) {}
        try { injectedModel?.unloadModel() } catch (_: Throwable) {}
        injectedSession = null
        injectedModel = null
        viewModel = null
    }

    /**
     * Bind to the in-process inference service, find a tool-capable
     * model in /data/local/tmp/, load it via the AIDL proxy, and inject
     * the resulting [LlamaModel] / [LlamaGenerationSession] into a
     * brand-new [ConversationViewModel]. Returns the configured ViewModel
     * or null if no tool-capable model is on the device.
     */
    private fun buildViewModelWithToolCapableModel(): ConversationViewModel? {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        app = context.applicationContext as App
        Log.d(TAG, "Got App, waiting for inference service to bind...")

        runBlocking {
            withTimeout(15_000) {
                while (app.inferenceClient.state.value !is InferenceState.Connected) {
                    delay(50)
                }
            }
        }
        Log.d(TAG, "Inference client connected.")

        val modelFile = CANDIDATE_MODELS
            .map { File(MODELS_PATH, it) }
            .firstOrNull { it.exists() && it.canRead() }
            ?: return null
        Log.d(TAG, "Probing model: ${modelFile.name}")

        val model = app.llamaCpp.loadModel(
            modelFile.absolutePath,
            object : LlamaProgressCallback {
                override fun onProgress(progress: Float) {
                    if ((progress * 100).toInt() % 25 == 0) {
                        Log.d(TAG, "  load progress: ${(progress * 100).toInt()}%")
                    }
                }
            },
        )
        if (!model.supportsToolCalling()) {
            Log.d(TAG, "${modelFile.name} does not support tool calling — skipping")
            model.unloadModel()
            return null
        }
        injectedModel = model

        val session = model.createSession(
            /* contextSize = */ 4096,
            /* temperature = */ 0.6f,
            /* topP = */ 0.95f,
            /* repetitionPenalty = */ 1.0f,
            /* topK = */ 40,
            /* minP = */ 0.05f,
            /* seed = */ -1,
            /* thinkingBudget = */ -1,
            /* systemPrompt = */ "",
        ) ?: error("createSession returned null")
        injectedSession = session

        // Construct the ViewModel on the main thread (AndroidViewModel
        // touches LiveData in init).
        val vm = runBlocking(Dispatchers.Main) { ConversationViewModel(app) }
        injectFields(vm, model, session)
        viewModel = vm
        return vm
    }

    /**
     * Reach past the ViewModel's private fields so the test doesn't have
     * to drive the SAF-backed loadModel() path. We're testing the
     * generation lifecycle, not the storage layer.
     */
    private fun injectFields(
        vm: ConversationViewModel,
        model: LlamaModel,
        session: LlamaGenerationSession,
    ) {
        val cls = ConversationViewModel::class.java
        // The native handles live on the ViewModel's ModelRuntime.
        val runtime = cls.getDeclaredField("runtime").apply { isAccessible = true }.get(vm)
        runtime.javaClass.getDeclaredField("model").apply {
            isAccessible = true; set(runtime, model)
        }
        runtime.javaClass.getDeclaredField("session").apply {
            isAccessible = true; set(runtime, session)
        }
        @Suppress("UNCHECKED_CAST")
        fun setBool(name: String, value: Boolean) {
            val live = cls.getDeclaredField(name).apply { isAccessible = true }
                .get(vm) as MutableLiveData<Boolean>
            // Set on main thread — LiveData.setValue requires it.
            runBlocking(Dispatchers.Main) { live.value = value }
        }
        setBool("_isModelReady", true)
        setBool("_supportsThinking", model.supportsThinking())
        setBool("_supportsToolCalling", true)
    }

    /**
     * Observe a LiveData and suspend until it satisfies [predicate] or
     * [timeoutMs] elapses. Returns the matching value, or null on timeout.
     */
    private suspend fun <T> awaitLiveData(
        live: LiveData<T>,
        timeoutMs: Long,
        predicate: (T?) -> Boolean,
    ): T? = withContext(Dispatchers.Main) {
        if (predicate(live.value)) return@withContext live.value
        withTimeoutOrNull(timeoutMs) {
            kotlinx.coroutines.suspendCancellableCoroutine<T?> { cont ->
                val observer = object : Observer<T> {
                    override fun onChanged(value: T) {
                        if (predicate(value)) {
                            live.removeObserver(this)
                            if (cont.isActive) cont.resumeWith(Result.success(value))
                        }
                    }
                }
                live.observeForever(observer)
                cont.invokeOnCancellation { live.removeObserver(observer) }
            }
        }
    }

    /**
     * Repro: send a tool-using prompt through ConversationViewModel and
     * verify `isGenerating` returns to false after generation completes
     * naturally.
     *
     * Before the fix this hangs forever — the cleanup bails out because
     * `addToolCallsToLastMessage` reset `responseStartTimeMs` and the
     * "is this still our placeholder?" identity check now fails.
     */
    @Test
    fun isGeneratingResetsToFalseAfterToolCall() {
        val vm = buildViewModelWithToolCapableModel()
        assumeTrue("No tool-capable model found in $MODELS_PATH", vm != null)
        val viewModel = vm!!

        // Make sure a tool the model can actually call is enabled.
        // setToolEnabled persists to prefs; harmless cross-test.
        runBlocking(Dispatchers.Main) {
            viewModel.setToolEnabled("run_javascript", true)
        }

        // Pick a prompt that's very likely to trigger run_javascript.
        val userMessage = Message(
            author = "User",
            content = "Use the run_javascript tool to compute 17 * 23, then give me the answer.",
        )

        runBlocking(Dispatchers.Main) { viewModel.addMessage(userMessage) }

        // Wait for isGenerating to become false. Before the fix this
        // never happens — the test fails on timeout.
        val result = runBlocking {
            awaitLiveData(viewModel.isGenerating, GEN_TIMEOUT_MS) { it == false }
        }
        Log.d(TAG, "Final isGenerating=${viewModel.isGenerating.value}, " +
            "messages=${viewModel.uiState.messages.size}")
        for ((i, m) in viewModel.uiState.messages.withIndex()) {
            val firstPreThinking = m.toolCalls?.firstOrNull()?.precedingThinking.orEmpty()
            Log.d(TAG, "  [$i] ${m.author}: tools=${m.toolCalls?.size ?: 0}, " +
                "preThinking='${firstPreThinking.take(80)}', " +
                "content='${m.content.take(80)}'")
        }

        assertTrue(
            "isGenerating should return to false after generation finishes",
            result == false
        )
        // Sanity: the assistant placeholder should have been finalized
        // (responseStartTimeMs back to 0).
        val last = viewModel.uiState.messages.lastOrNull()
        assertTrue("Should have at least 2 messages (user + assistant)",
            viewModel.uiState.messages.size >= 2)
        assertEquals("Last message must be Assistant", "Assistant", last?.author)
        assertEquals(
            "responseStartTimeMs must be reset to 0 by finalizeLastMessage",
            0L, last?.responseStartTimeMs ?: -1L
        )
        // Regression: the assistant reply after a tool call must NOT be
        // empty. Gemma 4 and other harmony-channel models emit "" on the
        // content channel when thinking is off in the response phase;
        // ConversationViewModel must force thinking on (when supported)
        // for that phase so the user never sees a blank bubble. Skip the
        // assertion only if the model didn't end up calling a tool at all
        // (small models can't always be coaxed).
        val toolCallCount = last?.toolCalls?.size ?: 0
        if (toolCallCount > 0) {
            assertTrue(
                "Assistant content must not be empty after a tool call " +
                    "(content='${last?.content}'). " +
                    "ConversationViewModel needs to enable thinking on the " +
                    "tool-result response phase for harmony-channel models.",
                !last?.content.isNullOrBlank()
            )
        } else {
            Log.d(TAG, "Model didn't invoke a tool — skipping content assertion")
        }
    }

    /**
     * Repro: cancel after a tool call has already happened. Before the
     * fix the cleanup bailed out the same way, so even cancel() couldn't
     * re-enable Send.
     */
    @Test
    fun cancelAfterToolCallResetsIsGenerating() {
        val vm = buildViewModelWithToolCapableModel()
        assumeTrue("No tool-capable model found in $MODELS_PATH", vm != null)
        val viewModel = vm!!

        runBlocking(Dispatchers.Main) {
            viewModel.setToolEnabled("run_javascript", true)
        }

        runBlocking(Dispatchers.Main) {
            viewModel.addMessage(
                Message(
                    author = "User",
                    content = "Use the run_javascript tool to compute 7823 * 4519, then write a 500-word " +
                        "essay about the history of multiplication.",
                )
            )
        }

        // Wait until the message has at least one tool call recorded —
        // i.e. we got past `addToolCallsToLastMessage`. Then cancel.
        val sawTool = runBlocking {
            withTimeoutOrNull(GEN_TIMEOUT_MS) {
                while (true) {
                    val msgs = viewModel.uiState.messages
                    if (msgs.lastOrNull()?.toolCalls?.isNotEmpty() == true) return@withTimeoutOrNull true
                    if (viewModel.isGenerating.value == false) return@withTimeoutOrNull false
                    delay(200)
                }
                @Suppress("UNREACHABLE_CODE") false
            }
        }

        if (sawTool != true) {
            Log.d(TAG, "Model did not invoke a tool (sawTool=$sawTool); skipping cancel test")
            assumeTrue("Model didn't trigger tool call in this run", sawTool == true)
            return
        }

        Log.d(TAG, "Tool call recorded; tapping Stop now.")
        runBlocking(Dispatchers.Main) { viewModel.cancelGeneration() }

        val result = runBlocking {
            awaitLiveData(viewModel.isGenerating, 60_000L) { it == false }
        }
        assertEquals(
            "isGenerating must return to false after cancel post-tool-call",
            false, result,
        )
    }
}
