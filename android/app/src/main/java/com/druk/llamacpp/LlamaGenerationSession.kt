package com.druk.llamacpp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * AIDL-proxy view of a generation session.
 *
 * The legacy `external fun generate(callback): Int` (which sampled exactly
 * one token per call) is gone. Generation now runs entirely service-side
 * in a single AIDL round trip — see [generateAll]. The historic
 * [LlamaGenerationCallback.onFullResponse] contract is preserved by
 * accumulating deltas in this class.
 */
class LlamaGenerationSession internal constructor(
    private val client: InferenceClient,
    internal val sessionId: Int,
) : GenerationSession {
    override fun addMessage(message: String, enableThinking: Boolean) {
        InferenceLimits.requireWithinBudget(message, "user message")
        client.withService { it.addMessage(sessionId, message, enableThinking) }
    }

    /**
     * Vision: stage image bytes (an already-resized JPEG/PNG) for the next
     * [addMessage] / [generateAll] turn. Must be called before [addMessage].
     * The bytes cross the binder boundary, so the caller must pre-resize the
     * image to stay well under the transaction cap.
     */
    override fun setImageData(imageData: ByteArray) {
        client.withService { it.setImageData(sessionId, imageData) }
    }

    /**
     * Replay a chat history into this session.
     *
     * The whole conversation may be much larger than a single binder
     * transaction can carry, so under the hood this does a `begin`,
     * one `appendHistoryPair` per turn, then `finalize` — each call
     * is its own transaction. Per-message validation still applies
     * (each individual message must fit inside [InferenceLimits.MAX_PAYLOAD_BYTES]).
     */
    fun replayHistory(userMessages: Array<String>, assistantMessages: Array<String>) {
        require(userMessages.size == assistantMessages.size) {
            "userMessages and assistantMessages must have the same length " +
                "(got ${userMessages.size} vs ${assistantMessages.size})"
        }
        if (userMessages.isEmpty()) return

        // Pre-validate every pair so we fail fast before sending any AIDL
        // calls — we don't want to half-replay a chat and then explode.
        for (i in userMessages.indices) {
            InferenceLimits.requireWithinBudget(userMessages[i], "user message #$i")
            InferenceLimits.requireWithinBudget(assistantMessages[i], "assistant message #$i")
        }

        client.withService { svc ->
            svc.beginReplayHistory(sessionId)
            for (i in userMessages.indices) {
                // One string per AIDL call — keeps every transaction safely
                // under the binder cap even when each message sits at the
                // 700 KB ceiling.
                svc.appendReplayUser(sessionId, userMessages[i])
                svc.appendReplayAssistant(sessionId, assistantMessages[i])
            }
            svc.finalizeReplayHistory(sessionId)
        }
    }

    fun printReport() {
        client.withService { it.printSessionReport(sessionId) }
    }

    fun getReport(): String = client.withService { it.getSessionReport(sessionId) }

    override fun destroy() {
        try { client.withService { it.destroySession(sessionId) } } catch (_: Throwable) {}
    }

    /**
     * Configure the available tools for function calling. Pass an
     * OpenAI-compatible JSON array of tool definitions, or `"[]"` to
     * disable tool calling for the next generation. Stateless on the
     * client side — every [generateAll] uses whatever was last set.
     */
    override fun setTools(toolsJson: String) {
        client.withService { it.setTools(sessionId, toolsJson) }
    }

    /**
     * After [generateAll] returns status code 2, fetch the pending tool
     * calls as a JSON array. Each element has `id`, `name`, and
     * `arguments` fields. Returns `"[]"` when no calls are pending.
     */
    override fun getToolCallsJson(): String =
        client.withService { it.getToolCallsJson(sessionId) }

    /**
     * Submit tool execution results back into the session's KV cache so
     * the next [generateAll] continues from where the model left off.
     * `resultsJson` is a JSON array of `{ id, name, content }` objects.
     * Returns 0 on success, non-zero on error.
     */
    fun submitToolResultsForStatus(resultsJson: String, enableThinking: Boolean): Int =
        client.withService { it.submitToolResults(sessionId, resultsJson, enableThinking) }

    override fun submitToolResults(resultsJson: String, enableThinking: Boolean) {
        submitToolResultsForStatus(resultsJson, enableThinking)
    }

    /**
     * Cancellation across the binder is driven by cancelling the coroutine
     * that called [generateAll] (which then issues cancelGeneration and waits
     * for the worker to acknowledge). This exists to satisfy
     * [GenerationSession]; direct callers should cancel the coroutine.
     */
    override fun requestAbort() {
        client.withService { it.cancelGeneration(sessionId) }
    }

    /**
     * Wire up the persistent preamble (system prompt + tools) KV cache.
     * Lazy: nothing happens on this call — the native side checks the
     * file on the first [addMessage] of the session. Path is the cache
     * prefix (the `.bin` and `.json` suffixes are appended natively).
     * `fingerprint` must change whenever the model, system prompt or tool
     * set changes; the cache is silently regenerated on mismatch. Pass
     * empty path/fingerprint to disable for this session.
     */
    fun setPreambleCachePath(path: String, fingerprint: String) {
        client.withService { it.setPreambleCachePath(sessionId, path, fingerprint) }
    }

    /**
     * Drives a full generation in a single AIDL call.
     *
     * Suspends until the service signals `onGenerationFinished`. Each
     * delta arriving from the service is appended to a buffer and forwarded
     * to [callback].onFullResponse, preserving the original (full
     * accumulated string) contract for existing call sites.
     *
     * Cancellation: if the calling coroutine is cancelled (Stop button),
     * the proxy issues `cancelGeneration` and then **waits** for the
     * service worker to actually emit `onGenerationFinished` before
     * re-throwing CancellationException. Without that wait, the worker
     * could keep firing `onResponseDelta` after the caller has already
     * finalized and persisted the assistant message — leaving the saved
     * text out of sync with the screen.
     *
     * Return value: 0 on natural stop, 2 when the model emitted tool
     * calls (the caller should drain via [getToolCallsJson] and resume
     * via [submitToolResults] + a fresh [generateAll]), other non-zero
     * status from the service on error / cancel.
     */
    override suspend fun generateAll(callback: LlamaGenerationCallback): Int {
        // Snapshot a service for the duration of this generation. If it
        // dies we'll surface InferenceUnavailableException via withService
        // semantics (the cancel/finalize calls below tolerate failure).
        val svc = client.requireConnected()
        val finished = CompletableDeferred<Int>()
        val buf = StringBuilder()
        val cb = object : ILlamaGenerationCallback.Stub() {
            override fun onResponseDelta(delta: String) {
                val full = synchronized(buf) {
                    buf.append(delta)
                    buf.toString()
                }
                callback.onFullResponse(full)
            }
            override fun onGenerationFinished(statusCode: Int) {
                finished.complete(statusCode)
            }
        }

        try {
            svc.startGeneration(sessionId, cb)
        } catch (e: android.os.DeadObjectException) {
            throw InferenceUnavailableException(
                "Inference service died before generation started: ${e.message}"
            )
        } catch (e: android.os.RemoteException) {
            throw InferenceUnavailableException(
                "Inference service rejected startGeneration: ${e.message}"
            )
        }
        return try {
            finished.await()
        } catch (e: CancellationException) {
            // Stop tapped (or job cancelled). Tell the service to abort
            // and synchronously wait for its worker to acknowledge —
            // otherwise tokens may still trickle in after we return.
            try { svc.cancelGeneration(sessionId) } catch (_: Throwable) {}
            withContext(NonCancellable) {
                withTimeoutOrNull(GEN_CANCEL_DRAIN_MS) { finished.await() }
            }
            throw e
        }
    }

    private companion object {
        // Worst-case wait for the service worker to exit after cancel.
        // Bounded by the same prompt-eval latency the service-side join
        // budget protects against (see GenerationWorker.join), plus
        // headroom for the oneway onGenerationFinished round trip.
        const val GEN_CANCEL_DRAIN_MS: Long = 30_000
    }
}
