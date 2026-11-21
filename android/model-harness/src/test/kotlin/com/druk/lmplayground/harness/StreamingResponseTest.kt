package com.druk.lmplayground.harness

import com.druk.llamacpp.LlamaGenerationCallback
import com.druk.lmplayground.harness.EngineFixture.session
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File

/**
 * The streamed response must only ever grow.
 *
 * Native hands back the whole normalized response on every token, and the
 * Android service forwards it to the app as deltas
 * (`response.substring(sentLength)`). If one call's string is not a prefix of
 * the next — the parser reclassifies earlier text, say, from reasoning to
 * content — the deltas no longer add up and the bubble shows text that was
 * never generated.
 *
 * Text the model itself emits is not checked here: LFM3 1.7B, for one,
 * sometimes closes a think block it was told not to open.
 */
@RunWith(Parameterized::class)
class StreamingResponseTest(private val modelFile: File) {

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun models(): List<Array<Any>> {
            val models = EngineFixture.chatModels()
            return if (models.isEmpty()) listOf(arrayOf(File("<no chat GGUF in ${EngineFixture.modelsDir()}>")))
            else models.map { arrayOf<Any>(it) }
        }

        private const val QUESTION = "What is the capital of France? Answer with one word."
    }

    /** [tools] runs it the way the app does: production sampler, tools set. */
    private fun stream(thinking: Boolean, tools: Boolean = false): List<String> {
        val model = EngineFixture.model(modelFile)
        // The app's defaults: production sampler and a thinking budget of a
        // quarter of the context (GenerationParams), with tools set.
        val session = if (tools) {
            model.createSession(4096, 0.6f, 0.95f, 1.0f, 40, 0.05f, 1, 1024, "")
                ?: error("createSession returned null")
        } else model.session()
        try {
            if (tools) session.setTools(HarnessTools.registry().toOpenAIToolsJson())
            check(session.addMessage(QUESTION, thinking) == 0)
            val seen = mutableListOf<String>()
            val cb = object : LlamaGenerationCallback {
                override fun onFullResponse(response: String) { seen += response }
            }
            var tokens = 0
            while (session.generate(cb) == Engine.RC_MORE_TOKENS && ++tokens < 512) { /* keep going */ }
            return seen
        } finally {
            session.destroy()
        }
    }

    private fun assertAppendOnly(seen: List<String>) {
        seen.zipWithNext().forEachIndexed { i, (before, after) ->
            assertEquals(
                "call ${i + 1} -> ${i + 2} rewrote earlier text: \"$before\" -> \"$after\"",
                before, after.take(before.length),
            )
        }
    }

    @Test
    fun thinkingOffStreamsAppendOnly() {
        assumeTrue("no chat model on disk", modelFile.isFile)
        assertAppendOnly(stream(thinking = false))
    }

    @Test
    fun thinkingOffWithToolsStreamsAppendOnly() {
        assumeTrue("no chat model on disk", modelFile.isFile)
        assertAppendOnly(stream(thinking = false, tools = true))
    }

    @Test
    fun thinkingOnStreamsAppendOnly() {
        assumeTrue("no chat model on disk", modelFile.isFile)
        assertAppendOnly(stream(thinking = true))
    }
}
