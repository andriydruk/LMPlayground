package com.druk.lmplayground.harness.probes

import com.druk.lmplayground.harness.*
import com.druk.lmplayground.harness.Engine.generateToEnd
import com.druk.lmplayground.harness.Engine.newGreedySession

/**
 * History replay: rebuilding a past conversation into a fresh session.
 *
 * This runs whenever a chat is reopened, the model is reloaded, or the
 * inference process is restarted after a crash — so a break here silently
 * costs the user their conversation context, with no error anywhere. It is
 * also the one path where the app hands the engine a whole conversation at
 * once rather than a turn at a time.
 *
 * Two things are checked, in increasing strength:
 *
 *  - the replayed session still generates at all (a malformed history can
 *    leave the template unable to render, which surfaces as an empty reply);
 *  - the model can answer a question that is only answerable from the
 *    replayed turns, i.e. the history actually reached the context rather
 *    than being silently dropped.
 *
 * The recall half is content-based, so it warns rather than fails — a small
 * model can hold the context and still phrase the answer unrecognisably.
 */
object ReplayProbe : Probe {
    override val name = "replay"
    override val cap = Cap.MULTI_TURN

    private val USER_TURNS = arrayOf(
        "My favourite colour is vermilion and I keep a tortoise named Ada.",
        "I also work as a lighthouse keeper in Porto.",
    )
    private val ASSISTANT_TURNS = arrayOf(
        "Noted — vermilion, and a tortoise called Ada.",
        "A lighthouse keeper in Porto. Got it.",
    )
    private const val QUESTION = "What is my tortoise called? Reply with just the name."

    override fun run(ctx: ProbeContext): List<ProbeResult> {
        val started = System.currentTimeMillis()
        val session = ctx.model.newGreedySession(nCtx = ctx.expectation.nCtx)
        return try {
            session.replayHistory(USER_TURNS, ASSISTANT_TURNS)
            session.addMessage(QUESTION, false)
            val gen = session.generateToEnd(ctx.expectation.maxTokens, ctx.expectation.timeoutMs)
            val ms = System.currentTimeMillis() - started

            when {
                gen.text.isBlank() -> listOf(
                    ProbeResult(
                        name, cap, ctx.grade(cap, true), "EMPTY_AFTER_REPLAY",
                        "session produced nothing after replaying ${USER_TURNS.size} turns — " +
                            "the replayed history likely left the template unrenderable",
                        ms,
                    )
                )
                !gen.text.contains("Ada", ignoreCase = true) -> listOf(
                    ProbeResult(
                        name, cap, Status.WARN, "REPLAY_CONTEXT_LOST",
                        "could not recall a detail from the replayed history", ms,
                        mapOf("reply" to gen.text.take(120).replace("\n", "\\n")),
                        rawArtifact = ctx.artifacts.write("replay", gen.text),
                    )
                )
                else -> listOf(
                    ProbeResult(
                        name, cap, Status.PASS, "OK",
                        "recalled a detail from ${USER_TURNS.size} replayed turns", ms,
                    )
                )
            }
        } catch (t: Throwable) {
            listOf(
                ProbeResult(
                    name, cap, Status.ERROR, "REPLAY_THREW",
                    "${t::class.simpleName}: ${t.message}",
                    System.currentTimeMillis() - started,
                )
            )
        } finally {
            session.destroy()
        }
    }
}
