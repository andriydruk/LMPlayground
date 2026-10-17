package com.druk.lmplayground

import android.util.Log
import com.druk.llamacpp.LlamaProgressCallback
import com.druk.llamacpp.jni.NativeLlamaCpp
import com.druk.llamacpp.jni.NativeLlamaModel
import com.druk.llamacpp.jni.NativeLlamaSession
import com.druk.llamacpp.tools.ToolRegistry
import com.druk.lmplayground.tools.WebFetchTool
import com.druk.lmplayground.tools.WebSearchTool
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/**
 * Probe: verify that the chat template can be rendered with messages
 * reduced to [system] alone and add_generation_prompt=false.
 *
 * For each model present in /data/local/tmp/, the test asserts:
 *   - renderPreambleString returns non-empty
 *   - the preamble contains the system prompt
 *   - if the model supports tools, the preamble grows when tools are
 *     attached vs. detached (proves tool descriptions render into it)
 *
 * Setup:
 *   adb shell "cp /sdcard/Models/gemma-4-E2B-it-Q4_K_M.gguf /data/local/tmp/ && chmod 666 /data/local/tmp/gemma-4-E2B-it-Q4_K_M.gguf"
 */
@RunWith(AndroidJUnit4::class)
class PreambleProbeTest {

    companion object {
        private const val TAG = "PreambleProbe"
        private const val MODELS_PATH = "/data/local/tmp"

        private val CANDIDATE_MODELS = listOf(
            "gemma-4-E2B-it-Q4_K_M.gguf",
            "Qwen3-0.6B-Q4_K_M.gguf",
            "Qwen_Qwen3.5-0.8B-Q3_K_M.gguf",
            "LFM2.5-350M-Q4_K_M.gguf",
        )
    }

    private lateinit var llamaCpp: NativeLlamaCpp

    @Before
    fun setUp() {
        llamaCpp = NativeLlamaCpp()
        llamaCpp.init(
            InstrumentationRegistry.getInstrumentation()
                .targetContext.applicationInfo.nativeLibraryDir
        )
    }

    private fun loadModel(file: File): NativeLlamaModel? = llamaCpp.loadModel(
        file.absolutePath,
        object : LlamaProgressCallback {
            override fun onProgress(progress: Float) {}
        },
        disableRepack = false,
    )

    private fun toolsJson(): String =
        ToolRegistry().apply {
            register(WebSearchTool())
            register(WebFetchTool())
        }.toOpenAIToolsJson()

    private fun availableModels(): List<File> = CANDIDATE_MODELS
        .map { File(MODELS_PATH, it) }
        .filter { it.exists() && it.canRead() }

    private data class ProbeResult(
        val name: String,
        val supportsTools: Boolean,
        val preambleBytesWithoutTools: Int,
        val preambleBytesWithTools: Int,
        val containsSystemPrompt: Boolean,
        val toolsAddedToPreamble: Boolean
    )

    private fun probeModel(file: File): ProbeResult {
        val model = loadModel(file) ?: error("loadModel null for ${file.name}")
        try {
            val supportsTools = model.supportsToolCalling()
            val session: NativeLlamaSession = model.createSession(
                4096, 0f, 1f, 1f, 0, 0f, 1234, -1,
                "You are a helpful assistant."
            ) ?: error("createSession null for ${file.name}")
            try {
                // Without tools first
                session.setTools("[]")
                val plain = session.renderPreambleString(false)
                Log.i(TAG, "  ${file.name} preamble (no tools) = ${plain.length}B")
                Log.i(TAG, "    head: ${plain.take(160).replace("\n", "\\n")}")

                var withTools = plain
                if (supportsTools) {
                    session.setTools(toolsJson())
                    withTools = session.renderPreambleString(false)
                    Log.i(TAG, "  ${file.name} preamble (with tools) = ${withTools.length}B")
                    Log.i(TAG, "    head: ${withTools.take(200).replace("\n", "\\n")}")
                }

                return ProbeResult(
                    name = file.name,
                    supportsTools = supportsTools,
                    preambleBytesWithoutTools = plain.length,
                    preambleBytesWithTools = withTools.length,
                    containsSystemPrompt = plain.contains("helpful assistant", ignoreCase = true),
                    toolsAddedToPreamble = withTools.length > plain.length
                )
            } finally {
                session.destroy()
            }
        } finally {
            model.unloadModel()
        }
    }

    @Test
    fun renderPreambleNonEmpty_forEveryAvailableModel() {
        val models = availableModels()
        assumeTrue("No models in $MODELS_PATH", models.isNotEmpty())

        val results = mutableListOf<ProbeResult>()
        for (file in models) {
            Log.i(TAG, "── ${file.name} ───────────────────────────────")
            results.add(probeModel(file))
        }

        val summary = buildString {
            appendLine("── Probe summary ──")
            results.forEach { r ->
                appendLine(
                    "  ${r.name}: bytes(no tools)=${r.preambleBytesWithoutTools}, " +
                        "bytes(tools)=${r.preambleBytesWithTools}, " +
                        "supportsTools=${r.supportsTools}, " +
                        "containsSysPrompt=${r.containsSystemPrompt}, " +
                        "toolsBoostedPreamble=${r.toolsAddedToPreamble}"
                )
            }
        }
        Log.i(TAG, summary)

        // Every model must produce a non-empty preamble that includes the system prompt.
        for (r in results) {
            assertTrue("${r.name}: preamble too short (${r.preambleBytesWithoutTools}B)\n$summary",
                r.preambleBytesWithoutTools >= 20)
            assertTrue("${r.name}: preamble missing system prompt\n$summary",
                r.containsSystemPrompt)
            if (r.supportsTools) {
                assertTrue("${r.name}: tools didn't grow preamble\n$summary",
                    r.toolsAddedToPreamble)
            }
        }
    }
}
