package com.druk.lmplayground.harness.probes

import com.druk.lmplayground.harness.*
import com.druk.lmplayground.harness.Engine.generateToEnd
import com.druk.llamacpp.jni.NativeLlamaSession

/**
 * What happens when a conversation outgrows the context window.
 *
 * The engine is supposed to compact rather than fail: strip reasoning from
 * older assistant turns first, then drop whole turns from the front, keeping
 * the system prompt. The failure modes are all silent from the app's side —
 * an unrenderable template after compaction, a turn that returns nothing, or
 * a decode that errors out — and the user just sees a long chat stop working.
 *
 * A deliberately small context is used rather than a huge conversation, so the
 * overflow path is reached in seconds instead of minutes. That is the same
 * code path a long real chat hits at 4096.
 */
object ContextOverflowProbe : Probe {
    override val name = "overflow"
    override val cap = null

    /** Small enough to overflow quickly, large enough for a real turn to fit. */
    private const val SMALL_CTX = 1024
    private const val TURNS = 12

    /** Per-turn token budget. Must clear a reasoning model's <think> block. */
    private const val TURN_TOKENS = 256

    private const val SYSTEM = "You are a helpful assistant. Keep every answer under 20 words."

    override fun run(ctx: ProbeContext): List<ProbeResult> {
        val started = System.currentTimeMillis()
        val session: NativeLlamaSession = ctx.model.createSession(
            SMALL_CTX, 0.0f, 1.0f, 1.0f, 1, 0.0f, 1234, -1, SYSTEM,
        ) ?: return listOf(
            ProbeResult(name, cap, Status.ERROR, "NO_SESSION",
                "createSession returned null at ctx=$SMALL_CTX", 0)
        )

        return try {
            // Padding makes each turn expensive so the window fills fast.
            val filler = "Please remember this reference text: " + "alpha bravo charlie delta echo ".repeat(12)
            var emptyTurn = -1
            var rejected = -1
            var completed = 0
            var emptyButGenerated = 0

            for (turn in 1..TURNS) {
                val rc = session.addMessage("$filler Question $turn: what is ${turn} + ${turn}?", false)
                if (rc != 0) { rejected = turn; break }
                // Reasoning models spend their first tokens inside <think>, so a
                // tight budget makes them look silent when they are only slow to
                // reach visible content. Generous enough for that, still bounded.
                val gen = session.generateToEnd(maxTokens = TURN_TOKENS, timeoutMs = ctx.expectation.timeoutMs)
                if (gen.text.isBlank()) {
                    emptyTurn = turn
                    emptyButGenerated = gen.tokens
                    break
                }
                completed = turn
            }

            val ms = System.currentTimeMillis() - started
            val results = mutableListOf<ProbeResult>()

            results += when {
                rejected > 0 -> ProbeResult(
                    name, cap, Status.FAIL, "TURN_REJECTED_ON_OVERFLOW",
                    "addMessage refused turn $rejected at ctx=$SMALL_CTX — compaction did not " +
                        "make room, so a long chat would stop accepting messages",
                    ms, mapOf("completedTurns" to completed.toString()),
                )
                // Tokens were produced but none surfaced: a reasoning model ran
                // out of window inside its <think> block, so the parser never had
                // visible content to emit. Real, and user-visible as a blank
                // reply, but a property of reasoning models in a tight window
                // rather than compaction failing — hence a warning.
                emptyTurn > 0 && emptyButGenerated > 0 -> ProbeResult(
                    name, cap, Status.WARN, "BLANK_WHILE_REASONING",
                    "turn $emptyTurn produced $emptyButGenerated tokens but no visible content " +
                        "at ctx=$SMALL_CTX — the window was exhausted inside <think>",
                    ms, mapOf("completedTurns" to completed.toString()),
                )
                emptyTurn > 0 -> ProbeResult(
                    name, cap, Status.FAIL, "EMPTY_TURN_ON_OVERFLOW",
                    "turn $emptyTurn generated nothing at all at ctx=$SMALL_CTX — the chat goes " +
                        "silent once the window fills",
                    ms, mapOf("completedTurns" to completed.toString()),
                )
                else -> ProbeResult(
                    name, cap, Status.PASS, "OK",
                    "survived $completed turns at ctx=$SMALL_CTX with compaction",
                    ms,
                )
            }

            // Compaction keeps the system prompt; if it were dropped the model
            // would stop obeying it, which is how a long chat silently changes
            // personality.
            if (rejected < 0 && emptyTurn < 0) {
                session.addMessage("In one word, what are you?", false)
                val gen = session.generateToEnd(maxTokens = TURN_TOKENS, timeoutMs = ctx.expectation.timeoutMs)
                results += ProbeResult(
                    probe = name, cap = cap,
                    status = if (gen.text.isNotBlank()) Status.PASS else Status.FAIL,
                    code = if (gen.text.isNotBlank()) "OK" else "DEAD_AFTER_COMPACTION",
                    reason = if (gen.text.isNotBlank()) "still answering after compaction"
                             else "no reply after the context was compacted",
                    durationMs = System.currentTimeMillis() - started,
                )
            }
            results
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
