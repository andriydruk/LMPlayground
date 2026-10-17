package com.druk.lmplayground.harness.probes

import com.druk.llamacpp.LlamaGenerationCallback
import com.druk.lmplayground.harness.*
import com.druk.lmplayground.harness.Engine.generateToEnd
import com.druk.lmplayground.harness.Engine.newGreedySession

/**
 * The tool-call round trip, run through the app's own ToolCallLoop.
 *
 * Reports PARSER_FAILURE (model emitted markup, parser missed it) separately
 * from NO_EMISSION (model just answered) — those have completely different
 * fixes, and conflating them is what let SmolLM3 ship broken.
 */
object ToolRoundTripProbe : Probe {
    override val name = "tools"
    override val cap = Cap.TOOLS

    override fun run(ctx: ProbeContext): List<ProbeResult> {
        val attempts = ToolProbe.run(
            model = ctx.model,
            maxTokens = ctx.expectation.maxTokens,
            timeoutMs = ctx.expectation.timeoutMs,
        )
        return listOf(summarise(ctx, attempts, cap = Cap.TOOLS, probe = name, thinking = false))
    }

    internal fun summarise(
        ctx: ProbeContext,
        attempts: List<ToolProbe.Attempt>,
        cap: Cap,
        probe: String,
        thinking: Boolean,
    ): ProbeResult {
        val counts = attempts.groupingBy { it.outcome }.eachCount()
        val tally = counts.entries.joinToString(" ") { "${it.key}=${it.value}/${attempts.size}" }
        val ms = attempts.sumOf { it.ms }
        val parserFailures = attempts.filter { it.outcome == ToolProbe.Outcome.PARSER_FAILURE }
        val parsed = counts[ToolProbe.Outcome.PARSED] ?: 0

        // A parser failure on ANY attempt is red regardless of severity: an
        // intermittent parser is a broken parser, and the user sees raw markup.
        if (parserFailures.isNotEmpty()) {
            val a = parserFailures.first()
            val artifact = ctx.artifacts.write("$probe-parser-failure-seed${a.seed}", a.raw)
            return ProbeResult(
                probe = probe, cap = cap, status = Status.FAIL, code = "PARSER_FAILURE",
                reason = "model emitted tool-call markup that the parser did not recognise; " +
                    "the raw text reaches the UI as the assistant's answer",
                durationMs = ms,
                detail = mapOf("attempts" to tally, "markup" to (a.markupHit ?: "?"),
                               "head" to a.raw.take(160).replace("\n", "\\n")),
                rawArtifact = artifact,
                nextStep = "build/host/llamacpp-tools/bin/llama-debug-template-parser " +
                    ctx.modelFile.absolutePath,
            )
        }
        val errors = attempts.filter { it.outcome == ToolProbe.Outcome.ERROR }
        if (errors.isNotEmpty()) return ProbeResult(
            probe = probe, cap = cap, status = Status.ERROR, code = "ERROR",
            reason = errors.first().error ?: "unknown error", durationMs = ms,
            detail = mapOf("attempts" to tally),
        )
        if (parsed > 0) return ProbeResult(
            probe = probe, cap = cap, status = Status.PASS, code = "PARSED",
            reason = "tool call parsed and answered on $parsed/${attempts.size} attempts",
            durationMs = ms, detail = mapOf("attempts" to tally),
        )
        val emptyFinal = counts[ToolProbe.Outcome.EMPTY_FINAL] ?: 0
        if (emptyFinal > 0) return ProbeResult(
            probe = probe, cap = cap, status = Status.FAIL, code = "EMPTY_FINAL",
            reason = "tool call parsed but the model replied with nothing afterwards" +
                if (thinking) "" else " (thinking is forced on post-tool; see ToolCallLoop)",
            durationMs = ms, detail = mapOf("attempts" to tally),
        )
        val wrong = counts[ToolProbe.Outcome.WRONG_TOOL] ?: 0
        if (wrong > 0) return ProbeResult(
            probe = probe, cap = cap, status = Status.WARN, code = "WRONG_TOOL",
            reason = "model called a different tool than expected", durationMs = ms,
            detail = mapOf("attempts" to tally),
        )
        // Nothing emitted at all: a capability limit, not a defect, unless the
        // expectation says this model must manage it.
        val strict = ctx.expectation.strictEmission
        return ProbeResult(
            probe = probe, cap = cap,
            status = if (strict) ctx.grade(cap, true) else Status.WARN,
            code = "NO_EMISSION",
            reason = "model never emitted a tool call (answered in prose); " +
                "no parser problem detected",
            durationMs = ms, detail = mapOf("attempts" to tally),
        )
    }
}

/** Tools while thinking is on — where the <think> + tool-call envelopes interact. */
object ToolsWhileThinkingProbe : Probe {
    override val name = "tools+think"
    override val cap = Cap.TOOLS_WITH_THINKING

    override fun run(ctx: ProbeContext): List<ProbeResult> {
        val attempts = ToolProbe.run(
            model = ctx.model,
            enableThinking = true,
            maxTokens = ctx.expectation.maxTokens,
            timeoutMs = ctx.expectation.timeoutMs,
        )
        return listOf(
            ToolRoundTripProbe.summarise(ctx, attempts, cap = cap, probe = name, thinking = true)
        )
    }
}

/** <think> envelope present when asked for, absent when not. */
object ThinkingProbe : Probe {
    override val name = "think"
    // Deliberately null: this probe covers BOTH thinking and non-thinking. If
    // it declared THINKING, the runner would skip it entirely for models where
    // thinking is UNSUPPORTED — and never check that those models correctly
    // produce no <think> block, which is the half that matters for them.
    override val cap: Cap? = null

    private const val PROMPT = "A farmer has 17 sheep. All but 9 run away. How many are left? Explain briefly."

    override fun run(ctx: ProbeContext): List<ProbeResult> {
        val out = mutableListOf<ProbeResult>()
        if (ctx.expect(Cap.THINKING) != Expect.UNSUPPORTED) {
            out += envelope(ctx, enableThinking = true, cap = Cap.THINKING)
        }
        if (ctx.expect(Cap.NO_THINKING) != Expect.UNSUPPORTED) {
            out += envelope(ctx, enableThinking = false, cap = Cap.NO_THINKING)
        }
        return out
    }

    private fun envelope(ctx: ProbeContext, enableThinking: Boolean, cap: Cap): ProbeResult {
        val started = System.currentTimeMillis()
        val session = ctx.model.newGreedySession(nCtx = ctx.expectation.nCtx)
        return try {
            session.addMessage(PROMPT, enableThinking)
            val gen = session.generateToEnd(ctx.expectation.maxTokens, ctx.expectation.timeoutMs)
            val ms = System.currentTimeMillis() - started
            val hasOpen = gen.text.contains("<think>")
            val hasClose = gen.text.contains("</think>")
            val label = if (enableThinking) "think" else "no-think"

            // Raw channel markup reaching the caller is the thinking-side
            // analogue of the tool-call leak.
            val leak = Regex("""<\|channel\|>|<\|start\|>|<\|message\|>""").find(gen.text)
            when {
                leak != null -> ProbeResult(name, cap, Status.FAIL, "CHANNEL_LEAK",
                    "raw channel markup '${leak.value}' reached the caller instead of being parsed",
                    ms, mapOf("mode" to label),
                    rawArtifact = ctx.artifacts.write("think-$label-leak", gen.text))
                gen.text.isBlank() -> ProbeResult(name, cap, ctx.grade(cap, true), "EMPTY_RESPONSE",
                    "model returned nothing with enableThinking=$enableThinking", ms,
                    mapOf("mode" to label))
                // Opened a think block but never closed it before the budget
                // ran out: a probe limit, not a model or parser defect.
                enableThinking && hasOpen && !hasClose && (gen.hitTokenCap || gen.hitTimeout) ->
                    ProbeResult(name, cap, Status.WARN, "THINK_TRUNCATED",
                        "still reasoning when the ${if (gen.hitTokenCap) "token budget" else "timeout"} " +
                            "ran out (${gen.tokens} tokens); raise maxTokens for this model", ms,
                        mapOf("mode" to label, "tokens" to gen.tokens.toString()),
                        rawArtifact = ctx.artifacts.write("think-$label", gen.text))
                enableThinking && !(hasOpen && hasClose) -> ProbeResult(
                    name, cap, ctx.grade(cap, true), "NO_THINK_BLOCK",
                    "no <think>...</think> block with thinking enabled", ms,
                    mapOf("mode" to label, "open" to hasOpen.toString(), "close" to hasClose.toString()),
                    rawArtifact = ctx.artifacts.write("think-$label", gen.text))
                !enableThinking && hasOpen -> ProbeResult(
                    name, cap, ctx.grade(cap, true), "THINK_NOT_SUPPRESSED",
                    "<think> block present even though thinking was disabled", ms,
                    mapOf("mode" to label),
                    rawArtifact = ctx.artifacts.write("think-$label", gen.text))
                else -> ProbeResult(name, cap, Status.PASS, "OK",
                    if (enableThinking) "emits a <think> block" else "no <think> block, non-empty answer",
                    ms, mapOf("mode" to label, "tokens" to gen.tokens.toString()))
            }
        } catch (t: Throwable) {
            ProbeResult(name, cap, Status.ERROR, "ERROR", "${t::class.simpleName}: ${t.message}",
                System.currentTimeMillis() - started)
        } finally {
            session.destroy()
        }
    }
}

/** Three turns, each answered, with the third referring back to the first. */
object MultiTurnProbe : Probe {
    override val name = "multiturn"
    override val cap = Cap.MULTI_TURN

    override fun run(ctx: ProbeContext): List<ProbeResult> {
        val started = System.currentTimeMillis()
        val session = ctx.model.newGreedySession(nCtx = ctx.expectation.nCtx)
        return try {
            val turns = listOf(
                "My name is Zoltan and I live in Prague. Reply in one short sentence.",
                "What is 2 + 2? Reply with just the number.",
                "What is my name? Reply with just the name.",
            )
            val replies = turns.map { t ->
                session.addMessage(t, false)
                session.generateToEnd(ctx.expectation.maxTokens, ctx.expectation.timeoutMs).text
            }
            val ms = System.currentTimeMillis() - started
            val blank = replies.indexOfFirst { it.isBlank() }
            listOf(when {
                blank >= 0 -> ProbeResult(name, cap, ctx.grade(cap, true), "EMPTY_TURN",
                    "turn ${blank + 1} of ${turns.size} returned nothing", ms)
                // Content-based, so warn-only: a model can be coherent and
                // still phrase this in a way a substring check misses.
                !replies.last().contains("Zoltan", ignoreCase = true) -> ProbeResult(
                    name, cap, Status.WARN, "CONTEXT_LOST",
                    "did not recall a name given two turns earlier", ms,
                    mapOf("reply" to replies.last().take(120).replace("\n", "\\n")),
                    rawArtifact = ctx.artifacts.write("multiturn", replies.joinToString("\n---\n")))
                else -> ProbeResult(name, cap, Status.PASS, "OK",
                    "3 turns answered, context retained", ms)
            })
        } catch (t: Throwable) {
            listOf(ProbeResult(name, cap, Status.ERROR, "ERROR", "${t::class.simpleName}: ${t.message}",
                System.currentTimeMillis() - started))
        } finally {
            session.destroy()
        }
    }
}
