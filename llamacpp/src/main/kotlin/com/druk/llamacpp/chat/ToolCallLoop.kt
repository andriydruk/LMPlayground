package com.druk.llamacpp.chat

import com.druk.llamacpp.GenerationSession
import com.druk.llamacpp.LlamaGenerationCallback
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * One generation turn, including any tool rounds.
 *
 * Extracted from the app's GenerationCoordinator so the same loop runs in
 * production and in the test harness — the failure modes here (a round that
 * comes back empty, a model that never stops calling tools, thinking state
 * getting out of step across rounds) are exactly what a harness needs to
 * observe, and they can't be observed from a reimplementation.
 */
class ToolCallLoop(
    private val session: GenerationSession,
    private val tools: ToolExecutor,
    private val observer: Observer = Observer.NONE,
    private val maxToolRounds: Int = DEFAULT_MAX_TOOL_ROUNDS,
    /**
     * When the round budget runs out with calls still pending, don't run them:
     * answer each with a "budget spent" error and let the model write one last
     * reply from what it has. Without this the turn just stops, leaving the user
     * with whatever text preceded the last tool call (often nothing). Agent mode
     * turns this on, since its long runs are the ones likely to hit the limit.
     */
    private val wrapUpOnLimit: Boolean = false,
) {

    /** The tool side of the loop; [com.druk.llamacpp.tools.ToolRegistry] implements it. */
    interface ToolExecutor {
        fun executeToolCalls(toolCallsJson: String): String
        fun cancelInFlight()
    }

    interface Observer {
        /** A round's calls completed, paired with their results. */
        fun onToolCalls(calls: List<ToolCallRecord>) {}

        /**
         * A new round is starting. [responseThinking] is the thinking flag
         * handed to the model for it — the UI resets its per-round token
         * counters and thinking timer from this.
         */
        fun onRoundStarted(round: Int, responseThinking: Boolean) {}

        companion object { val NONE = object : Observer {} }
    }

    data class Result(
        /** Final return code from the last generateAll. */
        val rc: Int,
        val toolRounds: Int,
        /** True if the model still wanted tools after [maxToolRounds] rounds. */
        val hitRoundLimit: Boolean,
        val records: List<ToolCallRecord>,
    )

    /**
     * @param supportsThinking the model's own capability, not the user toggle.
     * @param enableThinking the user toggle for this turn.
     * @param isActive lets the caller abandon the loop between rounds
     *        (the app passes its coroutine's isActive).
     */
    suspend fun run(
        callback: LlamaGenerationCallback,
        supportsThinking: Boolean,
        enableThinking: Boolean,
        isActive: () -> Boolean = { true },
    ): Result {
        var toolRounds = 0
        val allRecords = mutableListOf<ToolCallRecord>()
        var rc: Int
        var hitLimit = false
        var wrappedUp = false
        // Same rule as after real tool results (see below).
        val responseThinking = supportsThinking || enableThinking

        while (true) {
            rc = session.generateAll(callback)
            if (rc != RC_TOOL_CALLS || !isActive()) break
            if (toolRounds >= maxToolRounds) {
                hitLimit = true
                if (!wrapUpOnLimit || wrappedUp) break
                wrappedUp = true
                session.submitToolResults(budgetSpentResults(session.getToolCallsJson()), responseThinking)
                observer.onRoundStarted(toolRounds, responseThinking)
                continue
            }
            toolRounds++

            val toolCallsJson = session.getToolCallsJson()
            val started = System.currentTimeMillis()
            // Tool execution blocks (network); run it off the calling
            // dispatcher and await it so cancellation can interrupt — on
            // cancel we abort in-flight requests, which unblocks promptly.
            val toolResults = withContext(Dispatchers.IO) {
                val exec = async { tools.executeToolCalls(toolCallsJson) }
                try {
                    exec.await()
                } catch (e: CancellationException) {
                    tools.cancelInFlight()
                    throw e
                }
            }
            val durationMs = System.currentTimeMillis() - started

            val records = ToolCallPairing.pair(toolCallsJson, toolResults, durationMs)
            allRecords += records
            observer.onToolCalls(records)

            // Force thinking on for the response phase when the model has a
            // thinking mode, regardless of the user toggle: Gemma 4 and
            // harmony-style models emit an empty content channel after tool
            // calls when thinking is off, which would show a blank assistant
            // bubble after every tool call. Reasoning still routes to the
            // collapsed thinking section via the always-on DEEPSEEK
            // extraction in the parser. A no-op for models without a
            // thinking mode (the flag is ignored).
            session.submitToolResults(toolResults, responseThinking)
            observer.onRoundStarted(toolRounds, responseThinking)
        }

        return Result(rc = rc, toolRounds = toolRounds, hitRoundLimit = hitLimit, records = allRecords)
    }

    /** One error result per pending call, telling the model to stop calling tools and answer. */
    private fun budgetSpentResults(toolCallsJson: String): String {
        val message = "Tool call limit reached for this turn, so this call was not run. " +
            "Do not call more tools. Answer the user now using what you have already found."
        val calls = JSONArray(toolCallsJson)
        val results = JSONArray()
        for (i in 0 until calls.length()) {
            val call = calls.getJSONObject(i)
            results.put(
                JSONObject()
                    .put("id", call.getString("id"))
                    .put("name", call.getString("name"))
                    .put("content", JSONObject().put("error", message).toString())
            )
        }
        return results.toString()
    }

    companion object {
        const val RC_TOOL_CALLS = 2
        const val DEFAULT_MAX_TOOL_ROUNDS = 5
    }
}
