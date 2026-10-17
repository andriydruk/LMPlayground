package com.druk.lmplayground.harness.probes

import com.druk.lmplayground.harness.*
import com.druk.lmplayground.harness.Engine.generateToEnd
import com.druk.lmplayground.harness.Engine.newGreedySession
import java.io.File

/**
 * The persistent preamble KV cache.
 *
 * The system prompt and tool definitions are the same on every turn, so their
 * KV state is written to disk once and reloaded instead of re-evaluated. It is
 * pure optimisation: when it breaks, nothing fails — the app just quietly
 * re-evaluates the preamble on every single turn, and the only symptom is
 * being slower than it used to be. That makes it exactly the kind of thing
 * that rots unnoticed across a llama.cpp bump, since it depends on
 * llama_state_seq_load_file and on a manifest whose fields must still match.
 *
 * Measured through "Prompt tokens" in getReport(): on a hit the preamble
 * tokens come from the KV file rather than being evaluated, so the count
 * drops sharply. That is an objective signal, unlike wall-clock timing.
 */
object PreambleCacheProbe : Probe {
    override val name = "preamble-cache"
    override val cap = null

    private const val SYSTEM =
        "You are a meticulous assistant. Answer briefly and precisely, and never " +
            "speculate beyond what the user asked. Prefer concrete detail over generality."
    private const val PROMPT = "Say OK."

    override fun run(ctx: ProbeContext): List<ProbeResult> {
        val started = System.currentTimeMillis()
        val dir = File(System.getProperty("java.io.tmpdir"), "lmp-preamble-${ctx.modelFile.nameWithoutExtension}")
        dir.deleteRecursively()
        dir.mkdirs()
        val prefix = File(dir, "preamble").absolutePath
        val out = mutableListOf<ProbeResult>()

        try {
            // Pass 1 — cold. Populates the cache.
            val cold = runTurn(ctx, prefix, fingerprint = "fp-v1")
            val bin = File("$prefix.bin")
            val json = File("$prefix.json")

            if (!bin.isFile || !json.isFile) {
                return listOf(
                    ProbeResult(
                        name, cap, Status.FAIL, "CACHE_NOT_WRITTEN",
                        "no cache files after a turn with a cache path set — the preamble " +
                            "is being re-evaluated on every turn",
                        System.currentTimeMillis() - started,
                        mapOf("prefix" to prefix, "promptTokens" to cold.toString()),
                    )
                )
            }
            out += ProbeResult(
                name, cap, Status.PASS, "OK",
                "cache written (${bin.length() / 1024} KB) after the first turn",
                System.currentTimeMillis() - started,
            )

            // Pass 2 — same fingerprint. Should reload instead of re-evaluating.
            val warm = runTurn(ctx, prefix, fingerprint = "fp-v1")
            val hit = warm in 0 until cold
            out += ProbeResult(
                name, cap, if (hit) Status.PASS else Status.FAIL,
                if (hit) "CACHE_HIT" else "CACHE_MISS_ON_REUSE",
                if (hit) "reuse evaluated $warm prompt tokens vs $cold cold"
                else "reuse still evaluated $warm prompt tokens (cold was $cold) — the " +
                    "cache is written but never loaded",
                System.currentTimeMillis() - started,
                mapOf("coldPromptTokens" to cold.toString(), "warmPromptTokens" to warm.toString()),
            )

            // Pass 3 — changed fingerprint. Must NOT be reused: a stale preamble
            // would silently prepend the wrong system prompt or tool set.
            val rotated = runTurn(ctx, prefix, fingerprint = "fp-v2")
            val invalidated = rotated >= cold
            out += ProbeResult(
                name, cap, if (invalidated) Status.PASS else Status.FAIL,
                if (invalidated) "OK" else "STALE_CACHE_REUSED",
                if (invalidated) "a changed fingerprint re-evaluates the preamble ($rotated tokens)"
                else "a changed fingerprint still loaded the old cache ($rotated tokens vs " +
                    "$cold cold) — a stale system prompt or tool set can reach the model",
                System.currentTimeMillis() - started,
            )

            // Pass 4 — corrupt cache. Must degrade, not crash.
            bin.writeText("not a kv cache")
            val corrupt = runCatching { runTurn(ctx, prefix, fingerprint = "fp-v1") }
            out += ProbeResult(
                name, cap,
                if (corrupt.isSuccess) Status.PASS else Status.FAIL,
                if (corrupt.isSuccess) "OK" else "CORRUPT_CACHE_FATAL",
                if (corrupt.isSuccess) "a corrupt cache file falls back to evaluating the preamble"
                else "a corrupt cache file broke the turn: ${corrupt.exceptionOrNull()?.message}",
                System.currentTimeMillis() - started,
            )
        } catch (t: Throwable) {
            out += ProbeResult(
                name, cap, Status.ERROR, "ERROR", "${t::class.simpleName}: ${t.message}",
                System.currentTimeMillis() - started,
            )
        } finally {
            dir.deleteRecursively()
        }
        return out
    }

    /** Runs one short turn with the cache configured; returns prompt tokens evaluated. */
    private fun runTurn(ctx: ProbeContext, prefix: String, fingerprint: String): Int {
        val session = ctx.model.newGreedySession(systemPrompt = SYSTEM, nCtx = ctx.expectation.nCtx)
        return try {
            // Tools make the preamble comfortably larger than the 32-byte floor
            // below which the cache is deliberately skipped.
            session.setTools(HarnessTools.registry().toOpenAIToolsJson())
            session.setPreambleCachePath(prefix, fingerprint)
            session.addMessage(PROMPT, false)
            session.generateToEnd(maxTokens = 8, timeoutMs = ctx.expectation.timeoutMs)
            promptTokensOf(session.getReport())
        } finally {
            session.destroy()
        }
    }

    private val PROMPT_TOKENS = Regex("""Prompt tokens:\s*(\d+)""")

    private fun promptTokensOf(report: String): Int =
        PROMPT_TOKENS.find(report)?.groupValues?.get(1)?.toIntOrNull() ?: -1
}
