package com.druk.lmplayground.harness

import com.druk.llamacpp.DirectGenerationSession
import com.druk.llamacpp.LlamaGenerationCallback
import com.druk.llamacpp.chat.ToolCallLoop
import com.druk.llamacpp.chat.ToolCallRecord
import com.druk.lmplayground.harness.Engine.newProductionSession
import com.druk.llamacpp.jni.NativeLlamaModel
import kotlinx.coroutines.runBlocking

/**
 * The probe this harness exists for.
 *
 * A model can fail to produce a usable tool call in two completely different
 * ways, and the app cannot tell them apart today:
 *
 *  - NO_EMISSION    the model just answered in prose. Nothing is broken; the
 *                   model is small, or the prompt didn't force a call.
 *  - PARSER_FAILURE the model DID emit tool-call markup and our parser didn't
 *                   recognise it — generation reported "done" instead of
 *                   "tool calls pending", so the raw markup leaks into the
 *                   chat bubble as the assistant's answer.
 *
 * The second is a shipped bug (SmolLM3 3B did exactly this). Separating them
 * is what makes the report actionable instead of a wall of red.
 *
 * Runs the production [ToolCallLoop], not a copy of it, so a regression in the
 * app's own tool handling shows up here too.
 */
object ToolProbe {

    /** Shared with the vision variant so both classify emission identically. */
    val TOOL_MARKUP = Regex(
        """<tool_call\b|</tool_call>|<\|tool_call|<\|tool▁call|<tool▁call|""" +
            """\[TOOL_CALLS]|<function[ =>]|<\|python_tag\|>|functools\[|""" +
            """<\|channel\|>commentary|""" +
            """"name"\s*:\s*"[\w.\-]+"\s*,\s*"arguments"\s*:|""" +
            """"function"\s*:\s*\{\s*"name"""",
        RegexOption.IGNORE_CASE,
    )

    enum class Outcome { PARSED, WRONG_TOOL, PARSER_FAILURE, NO_EMISSION, EMPTY_FINAL, ROUND_LIMIT, ERROR }

    data class Attempt(
        val seed: Int,
        val outcome: Outcome,
        val rc: Int,
        val toolName: String? = null,
        val markupHit: String? = null,
        val raw: String = "",
        val finalAnswer: String = "",
        val records: List<ToolCallRecord> = emptyList(),
        val ms: Long = 0,
        val error: String? = null,
    )

    fun run(
        model: NativeLlamaModel,
        prompt: String = FORCED_TOOL_PROMPT,
        expectedTool: String = "web_search",
        seeds: List<Int> = listOf(1, 2, 3),
        enableThinking: Boolean = false,
        maxTokens: Int = 1024,
        timeoutMs: Long = 180_000,
    ): List<Attempt> = seeds.map { seed ->
        val registry = HarnessTools.registry()
        val native = model.newProductionSession(seed, systemPrompt = SYSTEM_PROMPT)
        val session = DirectGenerationSession(native, maxTokens = maxTokens, timeoutMs = timeoutMs)
        val started = System.currentTimeMillis()
        try {
            session.setTools(registry.toOpenAIToolsJson())
            session.addMessage(prompt, enableThinking)

            var streamed = ""
            val callback = object : LlamaGenerationCallback {
                override fun onFullResponse(response: String) { streamed = response }
            }

            val result = runBlocking {
                ToolCallLoop(session, HarnessTools.executor(registry)).run(
                    callback = callback,
                    supportsThinking = model.supportsThinking(),
                    enableThinking = enableThinking,
                )
            }
            val ms = System.currentTimeMillis() - started

            when {
                result.hitRoundLimit ->
                    Attempt(seed, Outcome.ROUND_LIMIT, result.rc, records = result.records, ms = ms)

                result.records.isEmpty() -> {
                    // No tool round happened. Did the model try anyway?
                    val hit = TOOL_MARKUP.find(streamed)
                    Attempt(
                        seed = seed,
                        outcome = if (hit != null) Outcome.PARSER_FAILURE else Outcome.NO_EMISSION,
                        rc = result.rc,
                        markupHit = hit?.value,
                        raw = streamed,
                        ms = ms,
                    )
                }

                else -> {
                    val name = result.records.first().name
                    Attempt(
                        seed = seed,
                        outcome = when {
                            streamed.isBlank() -> Outcome.EMPTY_FINAL
                            name != expectedTool -> Outcome.WRONG_TOOL
                            else -> Outcome.PARSED
                        },
                        rc = result.rc,
                        toolName = name,
                        finalAnswer = streamed,
                        records = result.records,
                        ms = ms,
                    )
                }
            }
        } catch (t: Throwable) {
            Attempt(seed, Outcome.ERROR, -1, ms = System.currentTimeMillis() - started,
                    error = "${t::class.simpleName}: ${t.message}")
        } finally {
            session.destroy()
        }
    }

    const val SYSTEM_PROMPT = "You are a helpful assistant."

    /** A question that cannot be answered from training data alone. */
    const val FORCED_TOOL_PROMPT =
        "What is the current weather in Kyiv right now? Use the web_search tool to find out."
}
