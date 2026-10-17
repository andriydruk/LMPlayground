package com.druk.lmplayground

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.druk.llamacpp.LlamaGenerationCallback
import com.druk.llamacpp.jni.NativeLlamaCpp
import com.druk.llamacpp.jni.NativeLlamaModel
import com.druk.llamacpp.jni.NativeLlamaSession
import com.druk.lmplayground.models.ChatTemplateOverrides
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * On-device regression for the SmolLM3 chat-template override.
 *
 * SmolLM3's published template never renders `message.tool_calls`, so
 * llama.cpp derives no tool-call grammar and every tool call the model emits
 * leaks into the chat as raw `<tool_call>` XML. The app ships a corrected
 * template as an asset and passes it to the native loader.
 *
 * This exercises the whole seam on real hardware: asset -> Kotlin ->
 * JNI -> common_chat_templates_init. The desktop harness covers the parsing
 * itself; what can only break here is the plumbing.
 */
@RunWith(AndroidJUnit4::class)
class ChatTemplateOverrideTest {

    private companion object {
        const val TAG = "ChatTemplateOverrideTest"
        const val MODELS_PATH = "/data/local/tmp"
        const val SMOLLM3 = "HuggingFaceTB_SmolLM3-3B-Q4_K_M.gguf"

        /** A question the model cannot answer without calling the tool. */
        const val PROMPT =
            "What is the current weather in Kyiv right now? Use the web_search tool to find out."

        val TOOLS_JSON = """
            [{"type":"function","function":{"name":"web_search",
              "description":"Search the web and return results.",
              "parameters":{"type":"object","properties":{"query":{"type":"string"}},
              "required":["query"]}}}]
        """.trimIndent()

        /** Markup meaning "the model tried to call a tool". */
        val TOOL_MARKUP = Regex("""<tool_call\b|"name"\s*:\s*"web_search"""")
    }

    private var model: NativeLlamaModel? = null
    private var session: NativeLlamaSession? = null

    @After
    fun tearDown() {
        session?.destroy()
        model?.unloadModel()
    }

    @Test
    fun overrideAssetIsReadableForSmolLm3() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val override = ChatTemplateOverrides.forModel(context, SMOLLM3)
        assertNotNull("SmolLM3 override asset must ship in the APK", override)
        assertTrue(
            "override must render assistant tool_calls — that is the whole point",
            override!!.contains("message.tool_calls"),
        )
        assertTrue("override must emit the <tool_call> format", override.contains("<tool_call>"))
    }

    @Test
    fun modelsWithoutAnOverrideGetNull() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals(null, ChatTemplateOverrides.forModel(context, "Qwen3-0.6B-Q4_K_M.gguf"))
    }

    @Test(timeout = 600_000)
    fun overrideMakesSmolLm3ToolCallsParseable() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(MODELS_PATH, SMOLLM3)
        assumeTrue("$SMOLLM3 not present in $MODELS_PATH", file.exists() && file.canRead())

        val override = ChatTemplateOverrides.forModel(context, SMOLLM3)
        assertNotNull("override asset missing", override)

        val llamaCpp = NativeLlamaCpp()
        llamaCpp.init(context.applicationInfo.nativeLibraryDir, context.filesDir.absolutePath)

        val noProgress = object : com.druk.llamacpp.LlamaProgressCallback {
            override fun onProgress(progress: Float) {}
        }
        val loaded = llamaCpp.loadModel(file.absolutePath, noProgress, false, override!!)
        assertNotNull("loadModel with an override returned null", loaded)
        model = loaded

        // Greedy + fixed seed: the point is whether the call PARSES, and a
        // sampled run would make the result flaky for reasons unrelated to it.
        val s = loaded!!.createSession(4096, 0.0f, 1.0f, 1.0f, 1, 0.0f, 1234, -1, "")
        assertNotNull("createSession returned null", s)
        session = s

        s!!.setTools(TOOLS_JSON)
        assertEquals("addMessage should be accepted", 0, s.addMessage(PROMPT, false))

        var text = ""
        val cb = object : LlamaGenerationCallback {
            override fun onFullResponse(response: String) { text = response }
        }
        var rc = 0
        var tokens = 0
        while (rc == 0 && tokens < 512) {
            rc = s.generate(cb)
            tokens++
        }
        Log.d(TAG, "rc=$rc after $tokens tokens; text=${text.take(300)}")

        // rc 2 == the native layer recognised tool calls. Before the override
        // this returned 1 and the raw XML below ended up on screen.
        if (rc != 2) {
            val leaked = TOOL_MARKUP.containsMatchIn(text)
            assertTrue(
                "PARSER FAILURE: model emitted tool-call markup that was not parsed " +
                    "(rc=$rc). The override is not taking effect. Raw: ${text.take(200)}",
                !leaked,
            )
            // No markup at all: the model simply chose not to call a tool.
            Log.w(TAG, "model did not emit a tool call this run; parsing untested")
            return
        }

        val callsJson = s.getToolCallsJson()
        Log.d(TAG, "tool calls: $callsJson")
        assertTrue("tool calls JSON should name web_search", callsJson.contains("web_search"))
    }
}
