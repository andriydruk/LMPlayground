package com.druk.lmplayground.harness.probes

import com.druk.llamacpp.LlamaGenerationCallback
import com.druk.lmplayground.harness.*
import com.druk.lmplayground.harness.Engine.generateToEnd
import com.druk.lmplayground.harness.Engine.newProductionSession

/**
 * Tools and an image in the same turn.
 *
 * Each half is already covered on its own, but they meet in the prompt: the
 * template has to carry tool definitions *and* image placeholders, and the
 * parser has to find a tool call in output that also came from a vision
 * decode. This combination is where a model most plausibly emits a tool call
 * the parser then misses — the SmolLM3 failure mode, on the path nothing else
 * exercises.
 *
 * Passing means either outcome is acceptable: calling a tool, or answering
 * about the picture. What is not acceptable is emitting tool-call markup that
 * never becomes a parsed call, because that lands in the chat as raw XML.
 */
object ToolsWithVisionProbe : Probe {
    override val name = "tools+vision"
    override val cap = Cap.TOOLS_WITH_VISION

    /** Answerable only with the tool, while an image occupies the turn. */
    private const val PROMPT =
        "Look at this picture, then use the web_search tool to find today's weather in Kyiv."

    override fun run(ctx: ProbeContext): List<ProbeResult> {
        val started = System.currentTimeMillis()
        val mmproj = ctx.mmprojFile ?: return listOf(
            ProbeResult(name, cap, Status.SKIP, "NO_MMPROJ", "vision projector not downloaded", 0)
        )
        val image = Fixtures.image(System.getenv("LMP_VISION_IMAGE") ?: "test_cat.jpg")
            ?: return listOf(
                ProbeResult(name, cap, Status.SKIP, "NO_FIXTURE", "test image not found", 0)
            )

        try {
            // Called unconditionally even though VisionProbe may already have
            // attached this projector: loadMmprojModel is idempotent, and
            // exercising the repeat call here is what would catch that
            // regressing back into a leak-and-abort at teardown.
            ctx.model.loadMmprojModel(mmproj.absolutePath)
        } catch (t: Throwable) {
            return listOf(
                ProbeResult(name, cap, Status.ERROR, "MMPROJ_LOAD_FAILED",
                    "${t::class.simpleName}: ${t.message}", System.currentTimeMillis() - started)
            )
        }

        val registry = HarnessTools.registry()
        val session = ctx.model.newProductionSession(seed = 1, systemPrompt = ToolProbe.SYSTEM_PROMPT)
        return try {
            session.setTools(registry.toOpenAIToolsJson())
            session.setImageData(image)
            val rc = session.addMessage(PROMPT, false)
            if (rc != 0) {
                return listOf(
                    ProbeResult(name, cap, ctx.grade(cap, true), "TEMPLATE_REJECTED",
                        "addMessage returned $rc with both tools and an image set — the template " +
                            "cannot carry tool definitions and an image in one turn",
                        System.currentTimeMillis() - started)
                )
            }

            var text = ""
            val cb = object : LlamaGenerationCallback {
                override fun onFullResponse(response: String) { text = response }
            }
            var code = 0
            var tokens = 0
            while (code == 0 && tokens < ctx.expectation.maxTokens) {
                code = session.generate(cb)
                tokens++
            }
            val ms = System.currentTimeMillis() - started

            // 2 == tool calls detected by the native parser.
            if (code == 2) {
                val calls = session.getToolCallsJson()
                return listOf(
                    ProbeResult(name, cap, Status.PASS, "PARSED",
                        "called a tool with an image attached", ms,
                        mapOf("calls" to calls.take(90)))
                )
            }

            val hit = ToolProbe.TOOL_MARKUP.find(text)
            listOf(
                when {
                    hit != null -> ProbeResult(
                        name, cap, Status.FAIL, "PARSER_FAILURE",
                        "emitted tool-call markup on the vision path that was not parsed — it " +
                            "reaches the chat as raw text",
                        ms,
                        mapOf("markup" to hit.value, "head" to text.take(140).replace("\n", "\\n")),
                        rawArtifact = ctx.artifacts.write("tools-vision", text),
                        nextStep = "build/host/llamacpp-tools/bin/llama-debug-template-parser " +
                            ctx.modelFile.absolutePath,
                    )
                    text.isBlank() -> ProbeResult(
                        name, cap, ctx.grade(cap, true), "EMPTY_RESPONSE",
                        "no reply at all with tools and an image in the same turn", ms,
                    )
                    // Describing the picture instead of calling the tool is a
                    // choice, not a defect — the parser was never challenged.
                    else -> ProbeResult(
                        name, cap, Status.PASS, "NO_EMISSION",
                        "answered about the image without calling a tool; no parser problem", ms,
                    )
                }
            )
        } catch (t: Throwable) {
            listOf(
                ProbeResult(name, cap, Status.ERROR, "ERROR",
                    "${t::class.simpleName}: ${t.message}", System.currentTimeMillis() - started)
            )
        } finally {
            session.destroy()
        }
    }
}
