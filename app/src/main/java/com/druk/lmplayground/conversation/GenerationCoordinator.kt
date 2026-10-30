package com.druk.lmplayground.conversation

import com.druk.llamacpp.chat.ResponseProcessor
import com.druk.llamacpp.chat.ToolCallRecord
import com.druk.llamacpp.chat.ToolCallLoop
import android.app.Application
import android.util.Log
import com.druk.llamacpp.InferenceUnavailableException
import com.druk.llamacpp.LlamaGenerationCallback
import com.druk.lmplayground.App
import com.druk.lmplayground.R
import com.druk.lmplayground.inference.ModelRuntime
import com.druk.lmplayground.storage.StoragePreferences
import com.druk.llamacpp.tools.ToolRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File

// Minimum gap between per-token haptic ticks. ~60 ms (≈16/s) reads as
// distinct typewriter taps rather than a continuous buzz on fast streams.
private const val HAPTIC_MIN_INTERVAL_MS = 60L

/**
 * Drives the engine side of a single generation turn: image staging, tool
 * hydration, preamble cache setup, the addMessage → generateAll loop with
 * tool-call rounds, and the guaranteed (NonCancellable) turn cleanup.
 *
 * Chat UI state stays out: streamed deltas, tool-call results and the
 * final cleanup are reported through [Listener] (implemented by the
 * ViewModel, which owns uiState and persistence). Listener methods are
 * invoked from background dispatchers.
 */
class GenerationCoordinator(
    private val app: Application,
    private val runtime: ModelRuntime,
    private val toolRegistry: ToolRegistry,
    private val storagePreferences: StoragePreferences,
    private val notifications: InferenceNotificationUpdater,
    private val imageStore: ChatImageStore,
    private val preambleCache: PreambleCacheManager,
    private val listener: Listener,
) {

    interface Listener {
        /** Per-tool enablement re-hydrated from prefs at turn start. */
        fun onToolStatesHydrated(states: Map<String, Boolean>)

        /**
         * Streamed assistant progress: [text] is the full processed response
         * so far; token counts split thinking vs. visible output.
         */
        fun onAssistantDelta(
            text: String,
            thinkingTokens: Int,
            responseTokens: Int,
            thinkingJustStarted: Boolean,
        )

        /** A tool round completed; attach [infos] to the assistant message. */
        fun onToolCalls(infos: List<ToolCallInfo>)

        /** Thinking (re)started for the post-tool response phase. */
        fun onThinkingRestarted()

        /**
         * The turn failed before generation started — the assistant
         * placeholder identified by [messageId] should be dropped.
         */
        fun onTurnAborted(messageId: Long)

        /** One-shot user-facing error. */
        fun onUserError(message: String)

        /**
         * Guaranteed turn cleanup, invoked inside a NonCancellable context
         * after the generation loop ends (natural stop, error or cancel).
         * [ourJob] and [messageId] identify this turn so the implementation
         * can detect a newer turn having superseded it.
         */
        suspend fun onTurnFinished(
            ourJob: Job?,
            messageId: Long,
            sessionId: String,
            totalTokens: Int,
        )
    }

    /**
     * Run one full generation turn against the runtime's live session.
     * Returns without doing anything when no session is live. Cancellation
     * flows through the calling coroutine (see the loop comment below).
     */
    suspend fun runTurn(
        content: String,
        enableThinking: Boolean,
        persistedImageFile: File?,
        modelFilename: String?,
        supportsToolCalling: Boolean,
        supportsThinking: Boolean,
        systemPrompt: String,
        messageId: Long,
        sessionId: String,
        ourJob: Job?,
    ) = withContext(Dispatchers.Default) {
        val llamaSession = runtime.session ?: return@withContext

        // Read + downscale the picked image off the binder thread so
        // setImageData (below) can hand the encoded bytes to the
        // native layer before addMessage. Failures degrade to a
        // text-only turn rather than aborting the message.
        val imageBytes: ByteArray? = if (persistedImageFile != null) {
            try {
                withContext(Dispatchers.IO) {
                    imageStore.resizeImageForVision(persistedImageFile)
                }?.also {
                    Log.d(TAG, "Image loaded: ${it.size} bytes")
                } ?: run {
                    Log.e(TAG, "Failed to decode image ${persistedImageFile.absolutePath}")
                    null
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error reading image: ${e.message}")
                null
            }
        } else {
            null
        }

        // Re-hydrate per-tool enablement from prefs before every turn so
        // a tool toggled in Settings -> Tools (global default) or a
        // model's params sheet (per-model override) takes effect on the
        // next message — without this, enabling a tool after the model
        // was loaded had no effect (the registry was only hydrated at
        // load time), so the model never saw any tools.
        modelFilename?.let { filename ->
            val states = mutableMapOf<String, Boolean>()
            for (tool in toolRegistry.getAllTools()) {
                val enabled = storagePreferences.effectiveToolEnabled(filename, tool.name)
                toolRegistry.setToolEnabled(tool.name, enabled)
                states[tool.name] = enabled
            }
            listener.onToolStatesHydrated(states)
        }

        // Tools are active when model supports it and user has tools enabled
        val toolsActive = supportsToolCalling && toolRegistry.hasEnabledTools()
        try {
            val toolsJson = if (toolsActive) toolRegistry.toOpenAIToolsJson() else "[]"
            llamaSession.setTools(toolsJson)
            // Persistent preamble KV cache: must be set after setTools
            // (the fingerprint covers the active tool set) and before
            // addMessage (the lazy load/save runs on the first
            // addMessage of the session). It's a no-op if the model
            // info is unavailable. Pruning the cache directory is
            // best-effort; failures don't block generation.
            val modelSize = try { runtime.model?.getModelSize() ?: 0L } catch (_: Throwable) { 0L }
            preambleCache.apply(llamaSession, modelFilename, modelSize, systemPrompt, toolsJson)
            // Hand the encoded image to the native layer before
            // addMessage so the multimodal preprocessor can fold it
            // into the prompt for this turn.
            if (imageBytes != null) {
                llamaSession.setImageData(imageBytes)
            }
            llamaSession.addMessage(content, enableThinking)
        } catch (e: InferenceUnavailableException) {
            Log.w(TAG, "addMessage failed: service unavailable", e)
            listener.onUserError(
                app.getString(R.string.inference_engine_unavailable)
            )
            listener.onTurnAborted(messageId)
            return@withContext
        }

        // Resolve the haptic gate once per turn: the in-app setting
        // AND the system-wide haptic toggle (a ContentResolver query
        // — too heavy to run per token).
        val hapticsAllowed = storagePreferences.hapticOnGeneration &&
            GenerationHaptics.isSystemHapticsEnabled(app)
        val callback = object : LlamaGenerationCallback {
            var totalTokens = 0
            var thinkingTokenCount = 0
            var thinkingComplete = !enableThinking
            var modelIsThinking = enableThinking
            // Throttle the silent token-count notification update to
            // ~1/sec: setForegroundContent is a blocking binder call,
            // so we must not fire it on every streamed token.
            var lastNotifUpdateMs = 0L
            // Throttle the per-token haptic tick so fast streams feel
            // like rapid typing instead of one continuous buzz.
            var lastHapticMs = 0L
            override fun onFullResponse(response: String) {
                totalTokens++
                val nowMs = System.currentTimeMillis()
                if (nowMs - lastNotifUpdateMs >= 1000L) {
                    lastNotifUpdateMs = nowMs
                    notifications.update(
                        R.string.inference_notification_generating_title,
                        notifications.tokensLine(totalTokens),
                    )
                }
                val string = ResponseProcessor.process(response)

                // Detect thinking from model output even when the
                // toggle is off (models like LFM 2.5 always think)
                var thinkingJustStarted = false
                if (!modelIsThinking && string.startsWith("<think>")) {
                    modelIsThinking = true
                    thinkingComplete = false
                    thinkingJustStarted = true
                }

                if (!thinkingComplete && string.contains("</think>")) {
                    thinkingComplete = true
                    thinkingTokenCount = totalTokens
                }
                val currentThinkingTokens = if (thinkingComplete) thinkingTokenCount else totalTokens

                // Typewriter-style haptic: a light tick per *output*
                // token. Gated on thinkingComplete so the stream only
                // buzzes for the visible answer, never the hidden
                // thinking. Throttled, and only while the chat is
                // on-screen (also satisfies the OS rule that bars
                // background vibration).
                if (hapticsAllowed &&
                    thinkingComplete &&
                    nowMs - lastHapticMs >= HAPTIC_MIN_INTERVAL_MS &&
                    (app as? App)?.isAppInForeground == true
                ) {
                    lastHapticMs = nowMs
                    GenerationHaptics.tick(app)
                }

                listener.onAssistantDelta(
                    string,
                    thinkingTokens = currentThinkingTokens,
                    responseTokens = totalTokens - currentThinkingTokens,
                    thinkingJustStarted = thinkingJustStarted,
                )
            }
        }
        // Drive the generation loop, draining tool calls between
        // rounds. `generateAll()` is a single AIDL call that runs
        // service-side until the worker stops; it returns 2 when
        // the model emitted tool calls, 0 on natural stop, or
        // non-zero on error / cancel.
        //
        // Cancellation flows through the coroutine: cancelling
        // the generating job cancels the suspend inside generateAll(),
        // which calls service.cancelGeneration() under the hood
        // and re-throws CancellationException once the worker
        // exits. We never re-enter the loop after a cancel.
        //
        // Silently flip the notification to "Generating…" with a
        // starting token count; the callback above bumps the count
        // ~1/sec as tokens stream. The finally block below freezes
        // it at the final total under "Response ready".
        notifications.update(
            R.string.inference_notification_generating_title,
            notifications.tokensLine(0),
        )
        try {
            // The loop itself lives in :llamacpp (ToolCallLoop) so the
            // macOS harness drives the exact same code — including the
            // forced-thinking rule below, which is where the Gemma-family
            // empty-reply bug lived. Everything Android-specific (UI token
            // counters, the thinking timer) stays here in the observer.
            ToolCallLoop(
                session = llamaSession,
                tools = object : ToolCallLoop.ToolExecutor {
                    override fun executeToolCalls(toolCallsJson: String): String {
                        Log.d(TAG, "Tool calls: $toolCallsJson")
                        return toolRegistry.executeToolCalls(toolCallsJson)
                    }
                    override fun cancelInFlight() = toolRegistry.cancelInFlight()
                },
                observer = object : ToolCallLoop.Observer {
                    override fun onToolCalls(calls: List<ToolCallRecord>) {
                        listener.onToolCalls(
                            calls.map {
                                ToolCallInfo(
                                    name = it.name,
                                    arguments = it.arguments,
                                    result = it.result,
                                    durationMs = it.durationMs,
                                )
                            }
                        )
                    }

                    override fun onRoundStarted(round: Int, responseThinking: Boolean) {
                        // Reset the streaming callback's per-round counters so
                        // the next generateAll() reports a fresh
                        // thinking-vs-response token split. The callback tracks
                        // WHICH phase the model is in for THIS round, so it
                        // follows responseThinking (the just-submitted flag) —
                        // not the user toggle — so the UI shows the thinking
                        // indicator while we wait for the answer.
                        callback.totalTokens = 0
                        callback.thinkingTokenCount = 0
                        callback.thinkingComplete = !responseThinking
                        callback.modelIsThinking = responseThinking

                        // Restart the thinking timer for the post-tool phase:
                        // the tool-call attach reset thinkingStartTimeMs to 0,
                        // and the callback's modelIsThinking is pre-set true so
                        // the streaming path won't fire markThinkingStarted
                        // itself — without this the post-tool "Thinking"
                        // duration stays 0s.
                        if (responseThinking) {
                            listener.onThinkingRestarted()
                        }
                    }
                },
            ).run(
                callback = callback,
                supportsThinking = supportsThinking,
                enableThinking = enableThinking,
                isActive = { this.isActive },
            )
        } catch (e: InferenceUnavailableException) {
            Log.w(TAG, "generateAll failed: service unavailable", e)
            listener.onUserError(
                app.getString(R.string.inference_engine_unavailable)
            )
        } finally {
            // Cleanup must complete even if the coroutine was
            // cancelled (Stop tapped). NonCancellable lets us
            // finish the Room writes and UI tear-down without
            // re-throwing CancellationException mid-cleanup.
            withContext(NonCancellable) {
                try { llamaSession.printReport() } catch (_: Throwable) {}
                listener.onTurnFinished(ourJob, messageId, sessionId, callback.totalTokens)
            }
        }
    }

    private companion object {
        private const val TAG = "GenerationCoordinator"
    }
}
