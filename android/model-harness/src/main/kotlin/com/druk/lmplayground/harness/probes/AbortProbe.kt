package com.druk.lmplayground.harness.probes

import com.druk.llamacpp.LlamaGenerationCallback
import com.druk.lmplayground.harness.*
import com.druk.lmplayground.harness.Engine.newGreedySession
import kotlin.concurrent.thread

/**
 * Stopping a generation that is already running.
 *
 * This is the Stop button. It has to interrupt a decode loop from another
 * thread, and the two ways it goes wrong are both bad: the loop ignores the
 * request and the UI is stuck until the model runs out of tokens, or the abort
 * races the decode and takes the process down — which on device kills the
 * whole inference service, not just the turn.
 *
 * The model is asked for something long, aborted shortly after the first
 * tokens arrive, and then the session is used again: an abort that leaves the
 * session unusable is only marginally better than one that crashes, since the
 * app reuses the session for the next message.
 */
object AbortProbe : Probe {
    override val name = "abort"
    override val cap = null

    private const val LONG_PROMPT =
        "Count slowly from 1 to 200, writing each number as a word on its own line."

    /** Generous: we are testing that abort works at all, not how fast it is. */
    private const val ABORT_DEADLINE_MS = 15_000L

    override fun run(ctx: ProbeContext): List<ProbeResult> {
        val started = System.currentTimeMillis()
        val session = ctx.model.newGreedySession(nCtx = ctx.expectation.nCtx)
        val out = mutableListOf<ProbeResult>()
        try {
            session.addMessage(LONG_PROMPT, false)

            // AtomicInteger rather than @Volatile: the counter is written on
            // the decode thread and read by the aborter thread.
            val tokensSeen = java.util.concurrent.atomic.AtomicInteger(0)
            val cb = object : LlamaGenerationCallback {
                override fun onFullResponse(response: String) { tokensSeen.incrementAndGet() }
            }

            // Abort once generation is demonstrably under way, so we are
            // interrupting a live decode rather than racing the setup.
            val aborter = thread(start = true, isDaemon = true, name = "abort-probe") {
                val deadline = System.currentTimeMillis() + 10_000
                while (tokensSeen.get() < 5 && System.currentTimeMillis() < deadline) {
                    Thread.sleep(20)
                }
                session.requestAbort()
            }

            val loopStart = System.currentTimeMillis()
            var rc = 0
            var generated = 0
            while (rc == 0 && generated < 4096) {
                rc = session.generate(cb)
                generated++
                if (System.currentTimeMillis() - loopStart > ABORT_DEADLINE_MS + 30_000) break
            }
            val stoppedAfter = System.currentTimeMillis() - loopStart
            aborter.join(2_000)

            out += when {
                tokensSeen.get() < 5 -> ProbeResult(
                    name, cap, Status.SKIP, "NEVER_STARTED",
                    "generation never produced enough tokens to abort mid-flight",
                    stoppedAfter,
                )
                stoppedAfter > ABORT_DEADLINE_MS -> ProbeResult(
                    name, cap, Status.FAIL, "ABORT_IGNORED",
                    "generation ran ${stoppedAfter}ms after abort was requested — " +
                        "Stop would leave the UI generating",
                    stoppedAfter,
                    mapOf("tokens" to generated.toString()),
                )
                else -> ProbeResult(
                    name, cap, Status.PASS, "OK",
                    "aborted after ${stoppedAfter}ms and $generated tokens",
                    stoppedAfter,
                )
            }

            // The app keeps using the same session after Stop.
            val reusable = runCatching {
                session.addMessage("What is 2 + 2? Reply with just the number.", false)
                var r = 0
                var n = 0
                var text = ""
                val cb2 = object : LlamaGenerationCallback {
                    override fun onFullResponse(response: String) { text = response }
                }
                while (r == 0 && n < 64) { r = session.generate(cb2); n++ }
                text
            }
            out += when {
                reusable.isFailure -> ProbeResult(
                    name, cap, Status.FAIL, "SESSION_DEAD_AFTER_ABORT",
                    "the session could not be reused after abort: " +
                        "${reusable.exceptionOrNull()?.message}",
                    System.currentTimeMillis() - started,
                )
                reusable.getOrNull().isNullOrBlank() -> ProbeResult(
                    name, cap, Status.FAIL, "EMPTY_AFTER_ABORT",
                    "the next turn after an abort produced nothing",
                    System.currentTimeMillis() - started,
                )
                else -> ProbeResult(
                    name, cap, Status.PASS, "OK",
                    "session still answers after an abort",
                    System.currentTimeMillis() - started,
                )
            }
        } catch (t: Throwable) {
            out += ProbeResult(
                name, cap, Status.ERROR, "ERROR", "${t::class.simpleName}: ${t.message}",
                System.currentTimeMillis() - started,
            )
        } finally {
            session.destroy()
        }
        return out
    }
}
