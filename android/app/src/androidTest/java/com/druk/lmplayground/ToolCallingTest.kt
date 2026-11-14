package com.druk.lmplayground

import android.util.Log
import com.druk.llamacpp.LlamaGenerationCallback
import com.druk.llamacpp.LlamaProgressCallback
import com.druk.llamacpp.jni.NativeLlamaCpp
import com.druk.llamacpp.jni.NativeLlamaModel
import com.druk.llamacpp.jni.NativeLlamaSession
import com.druk.llamacpp.tools.ToolRegistry
import com.druk.lmplayground.tools.createDefault
import com.druk.lmplayground.tools.WebSearchTool
import com.druk.lmplayground.tools.JavaScriptTool
import com.druk.lmplayground.tools.WebFetchTool
import org.json.JSONArray
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File

/**
 * Instrumented tests for tool/function calling support.
 *
 * Tests the full pipeline: tool definition -> model generation -> tool call detection
 * -> tool execution -> result submission -> final response.
 *
 * Setup: copy a tool-capable model to /data/local/tmp/:
 *   adb shell "cp /sdcard/Models/Qwen3-0.6B-Q4_K_M.gguf /data/local/tmp/ && chmod 666 /data/local/tmp/Qwen3-0.6B-Q4_K_M.gguf"
 */
@RunWith(AndroidJUnit4::class)
class ToolCallingTest {

    companion object {
        private const val TAG = "ToolCallingTest"

        private val CANDIDATE_MODELS = listOf(
            "gemma-4-E2B-it-Q4_K_M.gguf",
            "gemma-4-E4B-it-Q4_K_M.gguf",
            "Qwen3-0.6B-Q4_K_M.gguf",
            "Qwen_Qwen3.5-0.8B-Q3_K_M.gguf",
            "NVIDIA-Nemotron3-Nano-4B-Q4_K_M.gguf"
        )
        private const val MODELS_PATH = "/data/local/tmp"
    }

    private lateinit var llamaCpp: NativeLlamaCpp
    private var llamaModel: NativeLlamaModel? = null
    private var session: NativeLlamaSession? = null

    @Before
    fun setUp() {
        llamaCpp = NativeLlamaCpp()
        llamaCpp.init(
            InstrumentationRegistry.getInstrumentation()
                .targetContext.applicationInfo.nativeLibraryDir
        )
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

    private fun findToolCapableModel(): Pair<File, NativeLlamaModel>? {
        for (name in CANDIDATE_MODELS) {
            val file = File(MODELS_PATH, name)
            if (!file.exists() || !file.canRead()) continue
            Log.d(TAG, "Probing model: ${file.name}")
            val model = llamaCpp.loadModel(
                file.absolutePath,
                object : LlamaProgressCallback {
                    override fun onProgress(progress: Float) {}
                },
                disableRepack = false,
            )
            if (model == null) {
                Log.d(TAG, "  -> load returned null, skipping")
                continue
            }
            if (model.supportsToolCalling()) {
                Log.d(TAG, "  -> supports tool calling!")
                return Pair(file, model)
            }
            Log.d(TAG, "  -> does NOT support tool calling")
            model.unloadModel()
        }
        return null
    }

    private fun loadModel(modelFile: File): NativeLlamaModel {
        Log.d(TAG, "Loading model: ${modelFile.name} (${modelFile.length() / 1024 / 1024}MB)")
        val model = llamaCpp.loadModel(
            modelFile.absolutePath,
            object : LlamaProgressCallback {
                override fun onProgress(progress: Float) {
                    Log.d(TAG, "Loading: ${(progress * 100).toInt()}%")
                }
            },
            disableRepack = false,
        ) ?: error("loadModel returned null for ${modelFile.absolutePath}")
        llamaModel = model
        return model
    }

    /**
     * Generate response, returning the final text and the generate() return code.
     * Return code: 1 = normal completion, 2 = tool calls detected.
     */
    private fun generateResponse(
        session: NativeLlamaSession,
        maxTokens: Int = 2048,
        timeoutMs: Long = 120_000
    ): Pair<String, Int> {
        var lastResponse = ""
        var tokenCount = 0
        val deadline = System.currentTimeMillis() + timeoutMs
        val callback = object : LlamaGenerationCallback {
            override fun onFullResponse(response: String) {
                lastResponse = response
            }
        }
        var result: Int
        while (true) {
            result = session.generate(callback)
            if (result != 0) break
            tokenCount++
            if (tokenCount >= maxTokens) {
                Log.d(TAG, "Reached max token limit ($maxTokens)")
                result = 1
                break
            }
            if (System.currentTimeMillis() > deadline) {
                Log.d(TAG, "Reached timeout after $tokenCount tokens")
                result = 1
                break
            }
        }
        Log.d(TAG, "Generation finished: $tokenCount tokens, result=$result")
        return Pair(lastResponse, result)
    }

    /**
     * Captured snapshot of a single tool-call cycle:
     *   the tool the model chose, the arguments it sent, the JSON the
     *   tool produced, and the model's reply after the result was
     *   submitted back. [runToolCycle] returns one of these per cycle.
     */
    private data class CycleOutcome(
        val toolName: String,
        val toolArguments: String,
        val toolContent: String,
        val finalResponse: String,
    )

    /**
     * Drive a full tool-calling cycle end to end:
     *   addMessage -> generate -> (if result==2) executeToolCalls ->
     *   submitToolResults -> generate -> final response.
     *
     * Returns the [CycleOutcome] when the model actually invoked a tool,
     * or null when it answered directly without one (small models can't
     * always be coaxed — every task test treats null as a soft skip).
     *
     * Mirrors production behavior: thinking is enabled for the response
     * phase iff [model] supports it. Gemma 4 emits an empty reply after
     * tool calls when thinking is off (see [testReproduceAppBehavior]),
     * and the app's tool pipeline always toggles thinking on for the
     * response phase based on [LlamaModel.supportsThinking]. Tests that
     * skipped this re-introduced the bug.
     *
     * Generic invariants are asserted inside (cycle progresses, final
     * response is non-empty); per-task assertions live in each test.
     */
    private fun runToolCycle(
        model: NativeLlamaModel,
        session: NativeLlamaSession,
        registry: ToolRegistry,
        prompt: String,
        enableThinking: Boolean = false,
        maxTokensPerStep: Int = 512,
        perStepTimeoutMs: Long = 180_000,
    ): CycleOutcome? {
        session.addMessage(prompt, enableThinking)
        val (response1, result1) = generateResponse(session, maxTokensPerStep, perStepTimeoutMs)
        Log.d(TAG, "Cycle: first-gen result=$result1, response='${response1.take(120)}'")
        if (result1 != 2) {
            Log.d(TAG, "Cycle: model did not invoke a tool — answered directly. Skipping cycle.")
            return null
        }

        val toolCallsJson = session.getToolCallsJson()
        Log.d(TAG, "Cycle: tool calls: $toolCallsJson")
        val callsArr = JSONArray(toolCallsJson)
        assertTrue("Expected at least one tool call", callsArr.length() > 0)
        val firstCall = callsArr.getJSONObject(0)

        val toolResults = registry.executeToolCalls(toolCallsJson)
        Log.d(TAG, "Cycle: tool results: ${toolResults.take(300)}")
        val resultsArr = JSONArray(toolResults)
        val firstResult = resultsArr.getJSONObject(0)

        // Match production: enable thinking for the response phase iff the
        // model supports it. Without this, Gemma-family models emit "" after
        // tool calls and downstream assertions fail with "response empty".
        val responseThinking = model.supportsThinking()
        val submitResult = session.submitToolResults(toolResults, responseThinking)
        assertEquals("submitToolResults should succeed", 0, submitResult)

        val (response2, result2) = generateResponse(session, maxTokensPerStep, perStepTimeoutMs)
        Log.d(TAG, "Cycle: final-gen result=$result2 (thinking=$responseThinking), " +
            "response='${response2.take(200)}'")
        assertTrue("Final response should not be empty", response2.isNotBlank())

        return CycleOutcome(
            toolName = firstCall.getString("name"),
            toolArguments = firstCall.getString("arguments"),
            toolContent = firstResult.getString("content"),
            finalResponse = response2,
        )
    }

    /** Standard tool-calling session with the same sampler params used elsewhere. */
    private fun newToolSession(model: NativeLlamaModel): NativeLlamaSession =
        model.createSession(
            /*ctx*/ 4096, /*temp*/ 0.6f, /*topP*/ 0.95f, /*repPen*/ 1.0f,
            /*topK*/ 40, /*minP*/ 0.05f, /*seed*/ -1, /*thinkBudget*/ -1,
            /*systemPrompt*/ ""
        ) ?: error("createSession returned null")

    // -- Kotlin-only tool tests (no model needed) --

    @Test
    fun testToolRegistryJsonFormat() {
        val registry = ToolRegistry.createDefault(InstrumentationRegistry.getInstrumentation().targetContext)
        // Tools are disabled by default (per-model toggles); the OpenAI JSON
        // covers enabled tools only, so enable them all for this format test.
        registry.getAllTools().forEach { registry.setToolEnabled(it.name, true) }
        val json = registry.toOpenAIToolsJson()
        Log.d(TAG, "Tools JSON:\n$json")

        val arr = JSONArray(json)
        assertTrue("Should have at least 2 tools", arr.length() >= 2)

        // Validate each tool has correct structure
        for (i in 0 until arr.length()) {
            val tool = arr.getJSONObject(i)
            assertEquals("type should be function", "function", tool.getString("type"))
            val func = tool.getJSONObject("function")
            assertTrue("Should have name", func.has("name"))
            assertTrue("Should have description", func.has("description"))
            assertTrue("Should have parameters", func.has("parameters"))
        }
    }

    @Test
    fun testToolRegistryExecuteToolCalls() {
        val registry = ToolRegistry.createDefault(InstrumentationRegistry.getInstrumentation().targetContext)
        // 42 * 37 = 1554
        val toolCallsJson = """[{"id":"call_0","name":"run_javascript","arguments":"{\"code\":\"42 * 37\"}"}]"""
        val results = registry.executeToolCalls(toolCallsJson)
        Log.d(TAG, "Execute result: $results")

        val arr = JSONArray(results)
        assertEquals("Should have 1 result", 1, arr.length())
        val result = arr.getJSONObject(0)
        assertEquals("call_0", result.getString("id"))
        assertEquals("run_javascript", result.getString("name"))
        val content = org.json.JSONObject(result.getString("content"))
        assertEquals("1554", content.getString("result"))
    }

    @Test
    fun testToolRegistryUnknownTool() {
        val registry = ToolRegistry.createDefault(InstrumentationRegistry.getInstrumentation().targetContext)
        val toolCallsJson = """[{"id":"call_0","name":"nonexistent","arguments":"{}"}]"""
        val results = registry.executeToolCalls(toolCallsJson)
        Log.d(TAG, "Unknown tool result: $results")

        val arr = JSONArray(results)
        val content = arr.getJSONObject(0).getString("content")
        assertTrue("Should report error", content.contains("error"))
    }

    // -- Model capability tests --

    @Test
    fun testSupportsToolCallingDetection() {
        val modelFile = findModel()
        assumeTrue(
            "No model file found in $MODELS_PATH. Copy a model first.",
            modelFile != null
        )
        val model = loadModel(modelFile!!)
        val supports = model.supportsToolCalling()
        Log.d(TAG, "Model ${modelFile.name} supportsToolCalling: $supports")
        // Log result — this is informational, not a hard assertion,
        // since small models may or may not support tools
    }

    @Test
    fun testSetToolsOnSession() {
        val modelFile = findModel()
        assumeTrue("No model found", modelFile != null)

        val model = loadModel(modelFile!!)
        assumeTrue(
            "Model ${modelFile.name} does not support tool calling. " +
            "Try: Qwen2.5-3B-Instruct, Qwen3-4B, or Mistral-Nemo",
            model.supportsToolCalling()
        )

        val session = model.createSession(4096, 0.6f, 0.95f, 1.0f, 40, 0.05f, -1, -1, "")!!
        this.session = session

        val registry = ToolRegistry.createDefault(InstrumentationRegistry.getInstrumentation().targetContext)
        session.setTools(registry.toOpenAIToolsJson())
        // No crash = success. Tools are accepted by the native layer.
        Log.d(TAG, "setTools completed without error")
    }

    // -- Full tool calling integration tests --

    @Test
    fun testGenerateWithToolsSet() {
        val modelFile = findModel()
        assumeTrue("No model found", modelFile != null)

        val model = loadModel(modelFile!!)
        assumeTrue(
            "Model ${modelFile.name} does not support tool calling",
            model.supportsToolCalling()
        )

        val session = model.createSession(4096, 0.6f, 0.95f, 1.0f, 40, 0.05f, -1, -1, "")!!
        this.session = session

        val registry = ToolRegistry.createDefault(InstrumentationRegistry.getInstrumentation().targetContext)
        session.setTools(registry.toOpenAIToolsJson())
        session.addMessage("What is 123 * 456? Use the run_javascript tool.", false)

        val (response, result) = generateResponse(session, maxTokens = 512, timeoutMs = 180_000)
        Log.d(TAG, "Response (tools set): result=$result\n$response")

        // The model either made a tool call (result=2) or responded directly (result=1)
        assertTrue("Generate should return 1 or 2", result == 1 || result == 2)

        if (result == 2) {
            val toolCallsJson = session.getToolCallsJson()
            Log.d(TAG, "Tool calls detected: $toolCallsJson")
            val arr = JSONArray(toolCallsJson)
            assertTrue("Should have at least one tool call", arr.length() > 0)

            val call = arr.getJSONObject(0)
            assertTrue("Tool call should have name", call.has("name"))
            assertTrue("Tool call should have arguments", call.has("arguments"))
            assertTrue("Tool call should have id", call.has("id"))
            Log.d(TAG, "Tool call: name=${call.getString("name")}, args=${call.getString("arguments")}")
        } else {
            Log.d(TAG, "Model did not make tool call (answered directly). This is expected for small models.")
        }
    }

    @Test
    fun testFullToolCallCycle() {
        val modelFile = findModel()
        assumeTrue("No model found", modelFile != null)

        val model = loadModel(modelFile!!)
        assumeTrue(
            "Model ${modelFile.name} does not support tool calling",
            model.supportsToolCalling()
        )

        val session = model.createSession(4096, 0.6f, 0.95f, 1.0f, 40, 0.05f, -1, -1, "")!!
        this.session = session

        val registry = ToolRegistry.createDefault(InstrumentationRegistry.getInstrumentation().targetContext)
        session.setTools(registry.toOpenAIToolsJson())

        // Use a very direct prompt to maximize chances of tool use
        session.addMessage("Use the run_javascript tool to tell me what day of the week it is.", false)

        val (response1, result1) = generateResponse(session, maxTokens = 512, timeoutMs = 180_000)
        Log.d(TAG, "First generation: result=$result1\n$response1")

        if (result1 == 2) {
            // Tool calls detected - execute the full cycle
            val toolCallsJson = session.getToolCallsJson()
            Log.d(TAG, "Tool calls: $toolCallsJson")

            // Execute tools
            val toolResults = registry.executeToolCalls(toolCallsJson)
            Log.d(TAG, "Tool results: $toolResults")

            // Submit results
            val submitResult = session.submitToolResults(toolResults, false)
            assertEquals("submitToolResults should succeed", 0, submitResult)

            // Generate final response
            val (response2, result2) = generateResponse(session, maxTokens = 512, timeoutMs = 180_000)
            Log.d(TAG, "Final response: result=$result2\n$response2")
            assertTrue("Final response should not be empty", response2.isNotBlank())
            // The response should mention the day of the week
            Log.d(TAG, "Full tool call cycle completed successfully!")
        } else {
            Log.d(TAG, "Model did not use tools - answered directly. Small models may not reliably call tools.")
        }
    }

    @Test
    fun testJavaScriptToolCallCycle() {
        val modelFile = findModel()
        assumeTrue("No model found", modelFile != null)

        val model = loadModel(modelFile!!)
        assumeTrue(
            "Model ${modelFile.name} does not support tool calling",
            model.supportsToolCalling()
        )

        val session = model.createSession(4096, 0.6f, 0.95f, 1.0f, 40, 0.05f, -1, -1, "")!!
        this.session = session

        val registry = ToolRegistry.createDefault(InstrumentationRegistry.getInstrumentation().targetContext)
        session.setTools(registry.toOpenAIToolsJson())

        session.addMessage("Use the run_javascript tool to compute 7823 * 4519", false)

        val (response1, result1) = generateResponse(session, maxTokens = 512, timeoutMs = 180_000)
        Log.d(TAG, "First generation: result=$result1\n$response1")

        if (result1 == 2) {
            val toolCallsJson = session.getToolCallsJson()
            Log.d(TAG, "Tool calls: $toolCallsJson")

            val arr = JSONArray(toolCallsJson)
            val call = arr.getJSONObject(0)
            assertEquals("Should call run_javascript", "run_javascript", call.getString("name"))

            val toolResults = registry.executeToolCalls(toolCallsJson)
            Log.d(TAG, "Tool results: $toolResults")

            val submitResult = session.submitToolResults(toolResults, false)
            assertEquals("submitToolResults should succeed", 0, submitResult)

            val (response2, result2) = generateResponse(session, maxTokens = 512, timeoutMs = 180_000)
            Log.d(TAG, "Final response: result=$result2\n$response2")
            assertTrue("Final response should not be empty", response2.isNotBlank())

            // The correct answer is 35,352,137
            Log.d(TAG, "JavaScript tool call cycle completed!")
        } else {
            Log.d(TAG, "Model did not use run_javascript tool.")
        }
    }

    @Test
    fun testGenerateWithoutToolsStillWorks() {
        val modelFile = findModel()
        assumeTrue("No model found", modelFile != null)

        val model = loadModel(modelFile!!)
        val session = model.createSession(4096, 0.8f, 0.95f, 1.0f, 40, 0.05f, -1, -1, "")!!
        this.session = session

        // Don't set tools - should work as before
        session.addMessage("Say hello in one word", false)
        val (response, result) = generateResponse(session, maxTokens = 64)
        Log.d(TAG, "No-tools response: result=$result\n$response")
        assertTrue("Response should not be empty", response.isNotBlank())
        assertEquals("Should complete normally (not tool call)", 1, result)
    }

    @Test
    fun testSetEmptyToolsDisablesTools() {
        val modelFile = findModel()
        assumeTrue("No model found", modelFile != null)

        val model = loadModel(modelFile!!)
        val session = model.createSession(4096, 0.8f, 0.95f, 1.0f, 40, 0.05f, -1, -1, "")!!
        this.session = session

        // Set tools then clear them
        val registry = ToolRegistry.createDefault(InstrumentationRegistry.getInstrumentation().targetContext)
        session.setTools(registry.toOpenAIToolsJson())
        session.setTools("[]")

        session.addMessage("Say hello in one word", false)
        val (response, result) = generateResponse(session, maxTokens = 64)
        Log.d(TAG, "Cleared-tools response: result=$result\n$response")
        assertTrue("Response should not be empty", response.isNotBlank())
        assertEquals("Should complete normally after clearing tools", 1, result)
    }

    @Test
    fun testToolCallCycleWithThinkingEnabled() {
        val modelFile = findModel()
        assumeTrue("No model found", modelFile != null)

        val model = loadModel(modelFile!!)
        assumeTrue(
            "Model ${modelFile.name} does not support tool calling",
            model.supportsToolCalling()
        )

        val session = model.createSession(4096, 0.6f, 0.95f, 1.0f, 40, 0.05f, -1, -1, "")!!
        this.session = session

        val registry = ToolRegistry.createDefault(InstrumentationRegistry.getInstrumentation().targetContext)
        session.setTools(registry.toOpenAIToolsJson())

        // KEY DIFFERENCE: thinking=true (matches app behavior)
        session.addMessage("Use the run_javascript tool to compute 15 times 37", true)

        val (response1, result1) = generateResponse(session, maxTokens = 512, timeoutMs = 180_000)
        Log.d(TAG, "THINKING-ENABLED: First gen: result=$result1, response='$response1'")

        if (result1 == 2) {
            val toolCallsJson = session.getToolCallsJson()
            Log.d(TAG, "THINKING-ENABLED: Tool calls: $toolCallsJson")

            val toolResults = registry.executeToolCalls(toolCallsJson)
            Log.d(TAG, "THINKING-ENABLED: Tool results: $toolResults")

            // Submit with thinking=true (matches app behavior)
            val submitResult = session.submitToolResults(toolResults, true)
            assertEquals("submitToolResults should succeed", 0, submitResult)

            val (response2, result2) = generateResponse(session, maxTokens = 512, timeoutMs = 180_000)
            Log.d(TAG, "THINKING-ENABLED: Final gen: result=$result2, response='$response2'")
            assertTrue(
                "Final response should not be empty after tool call with thinking enabled",
                response2.isNotBlank()
            )
        } else {
            Log.d(TAG, "THINKING-ENABLED: Model did not use tools")
        }
    }

    @Test
    fun testToolCallThinkingDisabledJavaScript() {
        // Test thinking=false with run_javascript (the other test used thinking=true)
        val modelFile = findModel()
        assumeTrue("No model found", modelFile != null)

        val model = loadModel(modelFile!!)
        assumeTrue("Needs tool calling", model.supportsToolCalling())

        val session = model.createSession(4096, 0.6f, 0.95f, 1.0f, 40, 0.05f, -1, -1, "")!!
        this.session = session

        val registry = ToolRegistry.createDefault(InstrumentationRegistry.getInstrumentation().targetContext)
        session.setTools(registry.toOpenAIToolsJson())

        session.addMessage("Use the run_javascript tool to compute 7823 * 4519", false)
        val (r1, res1) = generateResponse(session, maxTokens = 512, timeoutMs = 180_000)
        Log.d(TAG, "JS-NOTHINK: First gen result=$res1, response='${r1.take(100)}'")

        if (res1 == 2) {
            val tc = session.getToolCallsJson()
            val tr = registry.executeToolCalls(tc)
            Log.d(TAG, "JS-NOTHINK: Tool calls=$tc, results=$tr")
            session.submitToolResults(tr, false)
            val (r2, res2) = generateResponse(session, maxTokens = 512, timeoutMs = 180_000)
            Log.d(TAG, "JS-NOTHINK: Final gen result=$res2, response='$r2', length=${r2.length}")
        }
    }

    @Test
    fun testReproduceAppBehavior() {
        // Reproduce exact app behavior: user asks "what's current date?" with Gemma 4.
        // The fix: always enable thinking for the response phase after tool results,
        // as Gemma 4 generates empty responses without thinking mode.
        val modelFile = findModel()
        assumeTrue("No model found", modelFile != null)

        val model = loadModel(modelFile!!)
        assumeTrue("Needs tool calling", model.supportsToolCalling())

        val session = model.createSession(4096, 0.8f, 0.95f, 1.0f, 40, 0.05f, -1, -1, "")!!
        this.session = session

        val registry = ToolRegistry.createDefault(InstrumentationRegistry.getInstrumentation().targetContext)
        session.setTools(registry.toOpenAIToolsJson())

        // Initial message with thinking disabled (user toggle off)
        session.addMessage("what's current date?", false)

        val (response1, result1) = generateResponse(session, maxTokens = 512, timeoutMs = 180_000)
        Log.d(TAG, "APP-REPRO: First gen result=$result1")

        if (result1 == 2) {
            val toolCallsJson = session.getToolCallsJson()
            Log.d(TAG, "APP-REPRO: Tool calls: $toolCallsJson")
            val toolResults = registry.executeToolCalls(toolCallsJson)
            Log.d(TAG, "APP-REPRO: Tool results: $toolResults")

            // FIX: enable thinking for the response phase if model supports it
            val thinkingForResponse = model.supportsThinking()
            val submitResult = session.submitToolResults(toolResults, thinkingForResponse)
            Log.d(TAG, "APP-REPRO: submitToolResults returned $submitResult (thinking=$thinkingForResponse)")

            val (response2, result2) = generateResponse(session, maxTokens = 512, timeoutMs = 180_000)
            Log.d(TAG, "APP-REPRO: Final gen result=$result2")
            Log.d(TAG, "APP-REPRO: Final gen response='$response2'")
            assertTrue("Final response should not be empty", response2.isNotBlank())
        } else {
            Log.d(TAG, "APP-REPRO: No tool call, direct response")
        }
    }

    // -- Task-specific end-to-end tests --
    //
    // Each test gives the model a prompt that should naturally route through
    // exactly one tool, then hard-asserts the *content* of the tool result
    // and the final response. When the model declines to call a tool (small
    // models can't always be coaxed), we log and skip — same tolerance as
    // the lifecycle tests above.

    @Test
    fun testTask_currentDateTimeIncludesYear() {
        val modelFile = findModel()
        assumeTrue("No model found", modelFile != null)
        val model = loadModel(modelFile!!)
        assumeTrue("Needs tool calling", model.supportsToolCalling())

        val s = newToolSession(model); this.session = s
        val registry = ToolRegistry.createDefault(InstrumentationRegistry.getInstrumentation().targetContext)
        s.setTools(registry.toOpenAIToolsJson())

        val outcome = runToolCycle(
            model, s, registry,
            prompt = "Use the run_javascript tool to find the current year, then state the year in your reply."
        )
        if (outcome == null) {
            Log.d(TAG, "TASK-DATETIME: model didn't call a tool, skipping content asserts")
            return
        }
        assertEquals("Should call run_javascript", "run_javascript", outcome.toolName)
        val currentYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR).toString()
        assertTrue(
            "Tool content must include current year ($currentYear). content=${outcome.toolContent}",
            outcome.toolContent.contains(currentYear)
        )
        assertTrue(
            "Final response must mention current year ($currentYear). response=${outcome.finalResponse}",
            outcome.finalResponse.contains(currentYear)
        )
    }

    @Test
    fun testTask_mathSimpleAddition() {
        val modelFile = findModel()
        assumeTrue("No model found", modelFile != null)
        val model = loadModel(modelFile!!)
        assumeTrue("Needs tool calling", model.supportsToolCalling())

        val s = newToolSession(model); this.session = s
        val registry = ToolRegistry.createDefault(InstrumentationRegistry.getInstrumentation().targetContext)
        s.setTools(registry.toOpenAIToolsJson())

        val outcome = runToolCycle(
            model, s, registry,
            prompt = "Use the run_javascript tool to compute 17 + 25, then tell me the answer."
        )
        if (outcome == null) {
            Log.d(TAG, "TASK-ADD: skipped, no tool call")
            return
        }
        assertEquals("Should call run_javascript", "run_javascript", outcome.toolName)
        // JS tool wraps the value as `{"result":"<stringified>"}`.
        assertTrue(
            "Tool content must contain '\"result\":\"42\"'. content=${outcome.toolContent}",
            outcome.toolContent.contains("\"result\":\"42\"")
        )
        assertTrue(
            "Final response must include '42'. response=${outcome.finalResponse}",
            outcome.finalResponse.contains("42")
        )
    }

    @Test
    fun testTask_mathPowerExponent() {
        // Validates the JS-tool description hint that `**` is the exponent
        // operator — small models historically write `2 ^ 10` (XOR=8) and
        // get the wrong answer. We accept any JS form that yields 1024.
        val modelFile = findModel()
        assumeTrue("No model found", modelFile != null)
        val model = loadModel(modelFile!!)
        assumeTrue("Needs tool calling", model.supportsToolCalling())

        val s = newToolSession(model); this.session = s
        val registry = ToolRegistry.createDefault(InstrumentationRegistry.getInstrumentation().targetContext)
        s.setTools(registry.toOpenAIToolsJson())

        val outcome = runToolCycle(
            model, s, registry,
            prompt = "Use the run_javascript tool to compute 2 to the power of 10, then tell me the result."
        )
        if (outcome == null) {
            Log.d(TAG, "TASK-POW: skipped, no tool call")
            return
        }
        assertEquals("Should call run_javascript", "run_javascript", outcome.toolName)
        assertTrue(
            "Tool content must contain '\"result\":\"1024\"'. content=${outcome.toolContent}",
            outcome.toolContent.contains("\"result\":\"1024\"")
        )
        assertTrue(
            "Final response must include '1024'. response=${outcome.finalResponse}",
            outcome.finalResponse.contains("1024")
        )
    }

    @Test
    fun testTask_mathParenthesizedExpression() {
        // (2 + 3) * 4 - 7 = 13. Tests precedence handling end-to-end.
        val modelFile = findModel()
        assumeTrue("No model found", modelFile != null)
        val model = loadModel(modelFile!!)
        assumeTrue("Needs tool calling", model.supportsToolCalling())

        val s = newToolSession(model); this.session = s
        val registry = ToolRegistry.createDefault(InstrumentationRegistry.getInstrumentation().targetContext)
        s.setTools(registry.toOpenAIToolsJson())

        val outcome = runToolCycle(
            model, s, registry,
            prompt = "Use the run_javascript tool to compute (2 + 3) * 4 - 7, then tell me the result."
        )
        if (outcome == null) {
            Log.d(TAG, "TASK-EXPR: skipped, no tool call")
            return
        }
        assertEquals("Should call run_javascript", "run_javascript", outcome.toolName)
        assertTrue(
            "Tool content must contain '\"result\":\"13\"'. content=${outcome.toolContent}",
            outcome.toolContent.contains("\"result\":\"13\"")
        )
        assertTrue(
            "Final response must include '13'. response=${outcome.finalResponse}",
            outcome.finalResponse.contains("13")
        )
    }

    @Test
    fun testTask_webSearchKotlinLanguage() {
        val modelFile = findModel()
        assumeTrue("No model found", modelFile != null)
        val model = loadModel(modelFile!!)
        assumeTrue("Needs tool calling", model.supportsToolCalling())

        val s = newToolSession(model); this.session = s
        val registry = ToolRegistry.createDefault(InstrumentationRegistry.getInstrumentation().targetContext)
        s.setTools(registry.toOpenAIToolsJson())

        val outcome = runToolCycle(
            model, s, registry,
            prompt = "Use the web_search tool to search for 'Kotlin programming language', " +
                "then briefly summarize what the search returned."
        )
        if (outcome == null) {
            Log.d(TAG, "TASK-SEARCH: skipped, no tool call")
            return
        }
        assertEquals("Should call web_search", "web_search", outcome.toolName)
        val toolJson = org.json.JSONObject(outcome.toolContent)
        assertFalse(
            "web_search must not return error: ${outcome.toolContent.take(400)}",
            toolJson.has("error")
        )
        val results = toolJson.getJSONArray("results")
        assertTrue(
            "web_search should return at least one result: ${outcome.toolContent.take(400)}",
            results.length() > 0
        )
        // Soft check on the model's reply — paraphrasing is fine, but it
        // should mention something topical (Kotlin / language / programming).
        val resp = outcome.finalResponse
        assertTrue(
            "Final response should mention Kotlin or 'programming'/'language'. response=$resp",
            resp.contains("Kotlin", ignoreCase = true) ||
                resp.contains("programming", ignoreCase = true) ||
                resp.contains("language", ignoreCase = true)
        )
    }

    @Test
    fun testTask_webFetchLmPlaygroundIdentifiesApp() {
        // Target our own site (https://lmplayground.app) — the rendered
        // body text is ~2.6 KB after Jsoup strips nav/footer/script, with
        // "LM Playground" and "Android" prominent in both title and body.
        // Smaller, more stable, and more recognizable than the previous
        // httpbin.org/html target (5 KB of Moby-Dick excerpt) which a 2B
        // model couldn't reliably summarize in a 512-token budget.
        val modelFile = findModel()
        assumeTrue("No model found", modelFile != null)
        val model = loadModel(modelFile!!)
        assumeTrue("Needs tool calling", model.supportsToolCalling())

        val s = newToolSession(model); this.session = s
        val registry = ToolRegistry.createDefault(InstrumentationRegistry.getInstrumentation().targetContext)
        s.setTools(registry.toOpenAIToolsJson())

        val outcome = runToolCycle(
            model, s, registry,
            // Three things tighten this prompt to stay inside Gemma 4's
            // thinking budget: (1) explicit max_length=200 keeps the tool
            // payload tiny so there's little for the model to chew on
            // before answering; (2) "in one short sentence" caps the
            // visible output; (3) we ask for the page title, which
            // web_fetch returns as a top-level field — always present.
            prompt = "Use the web_fetch tool with max_length=200 to retrieve " +
                "https://lmplayground.app, then in one short sentence give me the page title.",
            // Even with max_length=200 the model may ignore the hint and
            // pull the full 2.6 KB body. With thinking auto-enabled for
            // Gemma 4 (see runToolCycle), generation can spend a lot of
            // wall clock filtering through reasoning tokens before the
            // visible answer surfaces. Bumping ceiling tokens *and* the
            // wall-clock so we don't truncate the visible answer phase.
            maxTokensPerStep = 2048,
            perStepTimeoutMs = 360_000,
        )
        if (outcome == null) {
            Log.d(TAG, "TASK-FETCH: skipped, no tool call")
            return
        }
        assertEquals("Should call web_fetch", "web_fetch", outcome.toolName)
        // Tool result is deterministic — both literals appear in the
        // fetched body text, and "LM Playground" sits in the title field
        // even when the model truncates content via max_length.
        assertTrue(
            "Tool content must include 'LM Playground'. content head=${outcome.toolContent.take(400)}",
            outcome.toolContent.contains("LM Playground")
        )
        assertTrue(
            "Tool content must include 'Android'. content head=${outcome.toolContent.take(400)}",
            outcome.toolContent.contains("Android")
        )
        // Model interpretation: as long as the reply mentions either the
        // app name or the platform, fetched content reached the response
        // phase. Either alone proves the cycle worked end to end.
        val resp = outcome.finalResponse
        assertTrue(
            "Final response should surface fetched content " +
                "(LM Playground / Android). response=$resp",
            resp.contains("LM Playground", ignoreCase = true) ||
                resp.contains("Playground", ignoreCase = true) ||
                resp.contains("Android", ignoreCase = true)
        )
    }

    // -- Web tool tests (no model needed) --

    @Test
    fun testWebSearchReturnsResults() {
        val tool = WebSearchTool()
        val result = tool.execute("""{"query":"OpenAI"}""")
        Log.d(TAG, "WebSearch result: ${result.take(500)}")
        val json = org.json.JSONObject(result)
        assertFalse("Should not have error", json.has("error"))
        val results = json.getJSONArray("results")
        assertTrue("Should have at least 1 result", results.length() > 0)
        val first = results.getJSONObject(0)
        assertTrue("Result should have title", first.has("title"))
        assertTrue("Result should have url", first.has("url"))
        assertTrue("URL should start with http", first.getString("url").startsWith("http"))
    }

    @Test
    fun testWebSearchMaxResults() {
        val tool = WebSearchTool()
        val result = tool.execute("""{"query":"Android development","max_results":2}""")
        Log.d(TAG, "WebSearch max_results result: ${result.take(500)}")
        val json = org.json.JSONObject(result)
        if (!json.has("error")) {
            val results = json.getJSONArray("results")
            assertTrue("Should have at most 2 results", results.length() <= 2)
        }
    }

    @Test
    fun testWebFetchReturnsContent() {
        val tool = WebFetchTool()
        val result = tool.execute("""{"url":"https://httpbin.org/html"}""")
        Log.d(TAG, "WebFetch result: ${result.take(500)}")
        val json = org.json.JSONObject(result)
        assertFalse("Should not have error: $result", json.has("error"))
        assertTrue("Should have content", json.has("content"))
        val content = json.getString("content")
        assertTrue("Content should not be empty", content.isNotEmpty())
    }

    @Test
    fun testWebFetchTruncation() {
        val tool = WebFetchTool()
        val result = tool.execute("""{"url":"https://httpbin.org/html","max_length":50}""")
        Log.d(TAG, "WebFetch truncated: ${result.take(200)}")
        val json = org.json.JSONObject(result)
        assertFalse("Should not have error: $result", json.has("error"))
        val content = json.getString("content")
        assertTrue("Content should be truncated to ~50 chars, got ${content.length}", content.length <= 54)
    }

    @Test
    fun testWebFetchInvalidUrl() {
        val tool = WebFetchTool()
        val result = tool.execute("""{"url":"https://thisdoesnotexist.invalid"}""")
        Log.d(TAG, "WebFetch invalid: $result")
        val json = org.json.JSONObject(result)
        assertTrue("Should have error for invalid URL", json.has("error"))
    }

    @Test
    fun testToolRegistryIncludesWebTools() {
        val registry = ToolRegistry.createDefault(InstrumentationRegistry.getInstrumentation().targetContext)
        // The catalog itself must carry the three built-in tools; enablement
        // (default off) is a separate per-model concern.
        val names = registry.getAllTools().map { it.name }
        assertTrue("Should include web_search", names.contains("web_search"))
        assertTrue("Should include web_fetch", names.contains("web_fetch"))
        assertTrue("Should include run_javascript", names.contains("run_javascript"))
    }

    // -- JavaScript tool tests --

    @Test
    fun testJavaScriptBasicMath() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val tool = JavaScriptTool(ctx)
        val result = tool.execute("""{"code":"2 + 2"}""")
        Log.d(TAG, "JS basic math: $result")
        val json = org.json.JSONObject(result)
        assertFalse("Should not have error: $result", json.has("error"))
        assertEquals("4", json.getString("result"))
    }

    @Test
    fun testJavaScriptStringResult() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val tool = JavaScriptTool(ctx)
        val result = tool.execute("""{"code":"'hello ' + 'world'"}""")
        Log.d(TAG, "JS string: $result")
        val json = org.json.JSONObject(result)
        assertFalse("Should not have error: $result", json.has("error"))
        assertEquals("hello world", json.getString("result"))
    }

    @Test
    fun testJavaScriptComplexCode() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val tool = JavaScriptTool(ctx)
        val code = "const fib = (n) => { const a = [0,1]; for(let i=2;i<n;i++) a.push(a[i-1]+a[i-2]); return a; }; JSON.stringify(fib(10))"
        val result = tool.execute("""{"code":${org.json.JSONObject.quote(code)}}""")
        Log.d(TAG, "JS complex: $result")
        val json = org.json.JSONObject(result)
        assertFalse("Should not have error: $result", json.has("error"))
        assertTrue("Should contain fibonacci", json.getString("result").contains("[0,1,1,2,3,5,8,13,21,34]"))
    }

    @Test
    fun testJavaScriptSyntaxError() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val tool = JavaScriptTool(ctx)
        val result = tool.execute("""{"code":"function("}""")
        Log.d(TAG, "JS syntax error: $result")
        val json = org.json.JSONObject(result)
        assertTrue("Should have error for syntax error", json.has("error"))
    }

    // -- Regression tests for the template-literal eval bug --
    //
    // The tool used to wrap user code in a JS *template literal*:
    //   String(eval(`<user code>`))
    // That made the engine run `${...}` interpolation (and collide with
    // backticks) against the wrong scope, so any code using template
    // strings — which models emit constantly — either threw a
    // ReferenceError or produced wrong values. The fix passes the code to
    // eval() as a JSON-quoted plain string literal instead. These tests
    // would fail on the old wrapper and pass on the fix.

    @Test
    fun testJavaScriptTemplateLiteralInterpolation() {
        // Model-style code that builds a string with `${...}`. Under the old
        // wrapper `${name}` interpolated at the outer (empty) scope ->
        // ReferenceError: name is not defined.
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val tool = JavaScriptTool(ctx)
        val code = "const name = \"World\"; `Hello, \${name}!`"
        val result = tool.execute("""{"code":${org.json.JSONObject.quote(code)}}""")
        Log.d(TAG, "JS template literal: $result")
        val json = org.json.JSONObject(result)
        assertFalse("Should not have error: $result", json.has("error"))
        assertEquals("Hello, World!", json.getString("result"))
    }

    @Test
    fun testJavaScriptTemplateLiteralArithmetic() {
        // `${x * 2}` must be evaluated *inside* eval, against the user's
        // own `x`, not the outer wrapper scope.
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val tool = JavaScriptTool(ctx)
        val code = "const x = 5; `value=\${x * 2}`"
        val result = tool.execute("""{"code":${org.json.JSONObject.quote(code)}}""")
        Log.d(TAG, "JS template literal arithmetic: $result")
        val json = org.json.JSONObject(result)
        assertFalse("Should not have error: $result", json.has("error"))
        assertEquals("value=10", json.getString("result"))
    }

    @Test
    fun testJavaScriptLiteralDollarBraceIsNotInterpolated() {
        // A literal `${...}` inside an ordinary (single-quoted) string must
        // survive verbatim — it is data, not interpolation. The old wrapper
        // treated it as interpolation against the outer scope (ReferenceError).
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val tool = JavaScriptTool(ctx)
        val code = "'cost is \${total}'"
        val result = tool.execute("""{"code":${org.json.JSONObject.quote(code)}}""")
        Log.d(TAG, "JS literal dollar-brace: $result")
        val json = org.json.JSONObject(result)
        assertFalse("Should not have error: $result", json.has("error"))
        assertEquals("cost is \${total}", json.getString("result"))
    }

    @Test
    fun testJavaScriptBacktickInUserCode() {
        // User code that itself contains backticks must not break out of the
        // wrapper. Build and return a tagged-template-style string.
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val tool = JavaScriptTool(ctx)
        val code = "const items = ['a', 'b']; `[\${items.join('-')}]`"
        val result = tool.execute("""{"code":${org.json.JSONObject.quote(code)}}""")
        Log.d(TAG, "JS backtick in code: $result")
        val json = org.json.JSONObject(result)
        assertFalse("Should not have error: $result", json.has("error"))
        assertEquals("[a-b]", json.getString("result"))
    }

    @Test
    fun testJavaScriptMultiStatementLastExpression() {
        // Multi-statement code whose final line is a bare expression returns
        // that expression's value (eval completion-value semantics).
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val tool = JavaScriptTool(ctx)
        val code = "let a = 6;\nlet b = 7;\na * b"
        val result = tool.execute("""{"code":${org.json.JSONObject.quote(code)}}""")
        Log.d(TAG, "JS multi-statement: $result")
        val json = org.json.JSONObject(result)
        assertFalse("Should not have error: $result", json.has("error"))
        assertEquals("42", json.getString("result"))
    }

    // -- Diagnostic: does the model REALLY emit tool calls? --
    //
    // supportsToolCalling() only reports whether the chat template injects tool
    // definitions (template "supports_tools"). It does not prove the model was
    // trained to emit parseable tool calls. This probe gives a direct, tool-
    // forcing prompt N times to each model and logs how often the model
    // actually produced a tool call (generate() rc==2) vs answered directly.
    // Compares a suspect small model (LFM2.5 350M) against a known-good control
    // (Qwen3 0.6B). Pure diagnostic — logs results, only fails if neither model
    // is on the device.
    @Test(timeout = 600_000)
    fun testToolEmissionRate_LFMvsControl() {
        val probes = listOf(
            "LFM2.5-350M-Q4_K_M.gguf" to "LFM2.5 350M (user-reported failing)",
            "Qwen3-0.6B-Q4_K_M.gguf" to "Qwen3 0.6B (confirmed-good control)",
        )
        val registry = ToolRegistry.createDefault(
            InstrumentationRegistry.getInstrumentation().targetContext
        )
        // Tools now default to DISABLED, so enable them before serializing —
        // otherwise toOpenAIToolsJson() returns "[]" and no tools are sent.
        registry.getAllTools().forEach { registry.setToolEnabled(it.name, true) }
        val toolsJson = registry.toOpenAIToolsJson()
        // The current-date task can't be answered from training data, so a real
        // tool-user must call run_javascript (new Date()). This is the prompt
        // that fired a tool call in ~1s in the live app, so it's a good positive
        // control for a known-good model.
        val prompt = "What is today's date? Use the run_javascript tool to find out."
        val attempts = 3
        var anyRan = false

        for ((fileName, label) in probes) {
            val file = File(MODELS_PATH, fileName)
            if (!file.exists() || !file.canRead()) {
                Log.d(TAG, "TOOL-PROBE [$label]: not on device, skipping")
                continue
            }
            anyRan = true
            val model = loadModel(file)
            val templateFlag = model.supportsToolCalling()
            var toolCallCount = 0
            val toolNames = mutableListOf<String>()
            repeat(attempts) { i ->
                val s = model.createSession(4096, 0.6f, 0.95f, 1.0f, 40, 0.05f, -1, -1, "")
                    ?: error("createSession returned null")
                try {
                    s.setTools(toolsJson)
                    s.addMessage(prompt, false)
                    val (resp, rc) = generateResponse(s, maxTokens = 1024, timeoutMs = 120_000)
                    if (rc == 2) {
                        toolCallCount++
                        val arr = JSONArray(s.getToolCallsJson())
                        if (arr.length() > 0) toolNames.add(arr.getJSONObject(0).getString("name"))
                        Log.d(TAG, "TOOL-PROBE [$label] attempt $i: PARSED TOOL CALL -> ${toolNames.lastOrNull()}")
                    } else {
                        // Did the model emit a tool-call STRUCTURE the parser missed
                        // (=> parser bug), or just prose (=> capability)? Log the full
                        // raw output and flag any tool-call-ish markup.
                        val looksLikeToolCall = Regex("tool_call|<\\|tool|\"name\"\\s*:|function").containsMatchIn(resp)
                        Log.d(TAG, "TOOL-PROBE [$label] attempt $i: NO PARSED CALL (rc=$rc), " +
                            "looksLikeUnparsedToolCall=$looksLikeToolCall")
                        Log.d(TAG, "TOOL-PROBE [$label] attempt $i RAW >>>\n$resp\n<<< END RAW")
                    }
                } finally {
                    s.destroy()
                }
            }
            Log.d(
                TAG,
                "TOOL-PROBE RESULT [$label]: template supports_tools=$templateFlag, " +
                    "actually emitted tool call in $toolCallCount/$attempts attempts " +
                    "(tools=$toolNames)"
            )
            model.unloadModel()
            if (this.llamaModel === model) this.llamaModel = null
        }
        assumeTrue("Neither probe model present on device", anyRan)
    }

    // -- Diagnostic test: reports all model capabilities --

    @Test
    fun testReportAllModelCapabilities() {
        Log.d(TAG, "=== Model Capability Report ===")
        for (name in CANDIDATE_MODELS) {
            val file = File(MODELS_PATH, name)
            if (!file.exists() || !file.canRead()) {
                Log.d(TAG, "$name: NOT FOUND on device")
                continue
            }
            val model = llamaCpp.loadModel(
                file.absolutePath,
                object : LlamaProgressCallback {
                    override fun onProgress(progress: Float) {}
                },
                disableRepack = false,
            )
            if (model == null) {
                Log.d(TAG, "$name: load returned null (corrupt or unsupported)")
                continue
            }
            val thinking = model.supportsThinking()
            val tools = model.supportsToolCalling()
            Log.d(TAG, "$name: thinking=$thinking, tools=$tools")
            model.unloadModel()
        }
        Log.d(TAG, "=== End Report ===")
        Log.d(TAG, "Recommended models for tool calling: Qwen2.5-3B-Instruct, Qwen3-4B, Mistral-Nemo-7B")
    }
}
