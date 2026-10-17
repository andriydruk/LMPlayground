package com.druk.lmplayground.inference

import android.app.Application
import android.text.format.Formatter
import android.util.Log
import com.druk.llamacpp.InferenceClient
import com.druk.llamacpp.InferenceState
import com.druk.llamacpp.InferenceUnavailableException
import com.druk.llamacpp.LlamaCpp
import com.druk.llamacpp.LlamaGenerationSession
import com.druk.llamacpp.LlamaModel
import com.druk.llamacpp.LlamaProgressCallback
import com.druk.llamacpp.PayloadTooLargeException
import com.druk.lmplayground.R
import com.druk.lmplayground.conversation.GenerationParams
import com.druk.lmplayground.conversation.HistoryReplay
import com.druk.lmplayground.conversation.InferenceNotificationUpdater
import com.druk.lmplayground.conversation.Message
import com.druk.lmplayground.models.ChatTemplateOverrides
import com.druk.lmplayground.models.ModelInfo
import com.druk.lmplayground.storage.StoragePreferences
import com.druk.lmplayground.storage.StorageRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.round

/**
 * Owns the native inference handles — the loaded [LlamaModel], its live
 * [LlamaGenerationSession], the model file descriptor keeping the mmap
 * alive, and the in-flight generation [Job] — and every lifecycle
 * transition that touches them: load, session recreation, crash recovery
 * and teardown.
 *
 * UI state stays out: progress, status strings and capability changes are
 * reported through [Listener] (implemented by the ViewModel, which
 * translates them into its LiveData). All listener methods may be invoked
 * from background dispatchers.
 *
 * Threading contract: the public mutators must be called from the main
 * dispatcher (they capture-and-null handles on the caller's thread before
 * hopping to [Dispatchers.Default] for the blocking native work, mirroring
 * the original ViewModel code).
 */
class ModelRuntime(
    private val app: Application,
    private val llamaCpp: LlamaCpp?,
    private val inferenceClient: InferenceClient?,
    private val storageRepository: StorageRepository,
    private val storagePreferences: StoragePreferences,
    private val notifications: InferenceNotificationUpdater,
    private val listener: Listener,
) {

    interface Listener {
        fun onLoadProgress(progress: Float)
        fun onLoadStatus(status: String?)

        /** A load began for [model]; capability flags should reset. */
        fun onModelResolved(model: ModelInfo)
        fun onVisionModuleHint(model: ModelInfo?)
        fun onMaxContextSize(maxContextSize: Int)

        /** Per-model saved params (or defaults) resolved during load. */
        fun onGenerationParams(params: GenerationParams)

        /** Every model load starts without a system prompt. */
        fun onSystemPromptReset()

        /** Template-detected capabilities of the freshly loaded model. */
        fun onCapabilities(
            model: ModelInfo,
            thinking: Boolean,
            vision: Boolean,
            toolCalling: Boolean,
        )

        /** Load + history replay succeeded; the engine is ready. */
        fun onModelReady(model: ModelInfo)

        /** The native loader failed for [modelName] (corrupt/unsupported GGUF). */
        fun onModelLoadFailed(modelName: String)

        /** One-shot user-facing error (e.g. service died, payload too large). */
        fun onUserError(message: String)
    }

    /** Handles that were live at the moment a `:llama` crash was observed. */
    class CrashSnapshot internal constructor(
        internal val session: LlamaGenerationSession?,
        internal val model: LlamaModel?,
        internal val handle: StorageRepository.ModelFileHandle?,
        internal val job: Job?,
    )

    var model: LlamaModel? = null
        private set
    var session: LlamaGenerationSession? = null
        private set

    /**
     * The in-flight generation coroutine. Owned here so every lifecycle
     * transition can cancel-and-join it; set by the ViewModel when a
     * generation turn starts.
     */
    var generatingJob: Job? = null

    // Keep strong reference to prevent GC from closing the file descriptor
    private var modelFileHandle: StorageRepository.ModelFileHandle? = null

    /** Cancel and drain any in-flight generation. */
    suspend fun cancelAndJoinGeneration() {
        generatingJob?.cancel()
        generatingJob?.join()
        generatingJob = null
    }

    /**
     * Load [model] (already mmproj-resolved) and create its first session.
     * [messagesToReplay] is invoked right before history replay so it sees
     * the messages as of that moment.
     */
    suspend fun loadModel(
        model: ModelInfo,
        disableRepack: Boolean,
        messagesToReplay: () -> List<Message>,
    ) {
        val llamaCpp = llamaCpp ?: return

        // If we're recovering from a `:llama` crash, the InferenceClient
        // is in sticky `Crashed` state. Acknowledge it so the next AIDL
        // call uses the freshly auto-rebound service. Safe no-op when
        // the state is already Connected.
        inferenceClient?.let { ic ->
            if (ic.state.value is InferenceState.Crashed) {
                ic.acknowledgeCrash()
                // The auto-rebound service may still be landing — wait
                // up to 5s for the next Connected transition before
                // proceeding with loadModel.
                try {
                    kotlinx.coroutines.withTimeout(5_000) {
                        ic.awaitConnected()
                    }
                } catch (_: Throwable) {
                    listener.onLoadStatus(
                        app.getString(R.string.inference_engine_crashed)
                    )
                    return
                }
            }
        }

        // Stop any in-flight generation and tear down previous model
        cancelAndJoinGeneration()

        // Capture and null references on main thread to prevent races
        val prevSession = session
        val prevModel = this.model
        val prevHandle = modelFileHandle
        session = null
        this.model = null
        modelFileHandle = null

        withContext(Dispatchers.Default) {
            prevSession?.destroy()
            prevModel?.unloadModel()
        }

        prevHandle?.close()

        withContext(Dispatchers.Default) {
            listener.onLoadProgress(0f)
            listener.onModelResolved(model)
            listener.onLoadStatus("Loading...")

            val fileHandle = storageRepository.openModelFile(model.filename)
            if (fileHandle == null) {
                listener.onLoadStatus("Cannot open file")
                return@withContext
            }

            modelFileHandle = fileHandle

            // llama.cpp only reports progress during tensor pointer setup,
            // which is near-instant with mmap. The slow parts (GGUF metadata
            // parsing, mmap init, buffer allocation) report nothing.
            // Animate estimated progress as a fallback so the bar moves
            // during the silent phases; the real callback overrides as
            // soon as the first real value arrives.
            val realProgressSeen = AtomicBoolean(false)
            val progressJob = CoroutineScope(Dispatchers.Main).launch {
                val startTime = System.currentTimeMillis()
                while (isActive) {
                    if (!realProgressSeen.get()) {
                        val elapsed = (System.currentTimeMillis() - startTime) / 1000f
                        // Logarithmic curve: rises quickly then slows, caps at 0.9
                        val estimated = min(0.9f, ln(1f + elapsed) / ln(1f + 30f))
                        listener.onLoadProgress(estimated)
                        listener.onLoadStatus("${round(100 * estimated).toInt()}%")
                    }
                    delay(100)
                }
            }

            // Wrap the entire load + session setup so that the
            // progress-animation job is cancelled on every exit
            // path (success, exception, coroutine cancel). Without
            // this, a binder failure during loadModel would leave
            // the progress job ticking forever, overwriting the
            // crash status with bogus "85%" updates.
            try {
                // Send the PFD across the binder. The service dups the
                // FD into its own process and builds a process-local
                // fd:N string. The app keeps `fileHandle` (the original
                // PFD) alive via `modelFileHandle` for the model's
                // lifetime.
                val llamaModel = llamaCpp.loadModel(
                    fileHandle.pfd,
                    object : LlamaProgressCallback {
                        override fun onProgress(progress: Float) {
                            realProgressSeen.set(true)
                            listener.onLoadProgress(progress)
                            listener.onLoadStatus(
                                "${round(100 * progress).toInt()}%"
                            )
                        }
                    },
                    disableRepack = disableRepack,
                    // Non-null only for models whose published template is
                    // known-broken; see ChatTemplateOverrides.
                    chatTemplateOverride = ChatTemplateOverrides.forModel(app, model.filename),
                )

                // Load mmproj for vision models. The mtmd loader needs a
                // real filesystem path, so we copy the mmproj GGUF to a
                // temp file in app-private storage before handing it off.
                if (model.mmprojFilename != null) {
                    listener.onLoadStatus("Loading vision...")
                    val mmprojTempFile = File(app.cacheDir, "mmproj_temp.gguf")
                    val copied = storageRepository.copyModelToFile(
                        model.mmprojFilename, mmprojTempFile
                    )
                    if (copied) {
                        Log.d(TAG, "Loading mmproj from ${mmprojTempFile.absolutePath}")
                        llamaModel.loadMmprojModel(mmprojTempFile.absolutePath)
                        mmprojTempFile.delete()
                        Log.d(TAG, "mmproj loaded, supportsVision=${llamaModel.supportsVision()}")
                        listener.onVisionModuleHint(null)
                    } else {
                        Log.d(TAG, "mmproj file not found: ${model.mmprojFilename}")
                        // Vision-capable model loaded without its image
                        // module: offer to download it, once per model.
                        if (model.mmprojUri != null &&
                            !storagePreferences.wasVisionModuleHintShown(model.filename)
                        ) {
                            storagePreferences.setVisionModuleHintShown(model.filename)
                            listener.onVisionModuleHint(model)
                        }
                    }
                }

                val modelSize = llamaModel.getModelSize()
                val modelDescription = Formatter.formatFileSize(app, modelSize)
                // Surface "Model is loaded" + "<name> - <size>" in the
                // FGS notification (otherwise hidden under MIN importance,
                // but visible when the user expands the Silent group in
                // the shade). The description line is cached so generation
                // can later flip the title to "Generating…"/"Response
                // ready" without re-deriving it.
                notifications.modelLine = "${model.name} - $modelDescription"
                notifications.modelName = model.name
                notifications.update(R.string.inference_notification_loaded_title)
                val nCtxTrain = llamaModel.getContextTrainSize()
                listener.onMaxContextSize(minOf(nCtxTrain, 16384))
                // Load saved per-model params, or use defaults
                val savedMap = storagePreferences.getModelGenerationParams(model.filename)
                val params = if (savedMap != null) {
                    GenerationParams.fromMap(savedMap)
                } else {
                    GenerationParams()
                }
                listener.onGenerationParams(params)
                // Every model load starts without a system prompt. Per-model
                // MRU is surfaced in the picker row so the user can one-tap
                // re-apply their most-recent prompt for this model.
                listener.onSystemPromptReset()
                val llamaSession = createSessionWithParams(llamaModel, params, "")
                if (llamaSession == null) {
                    listener.onLoadStatus("Failed to create session")
                    llamaModel.unloadModel()
                    return@withContext
                }
                this@ModelRuntime.model = llamaModel
                this@ModelRuntime.session = llamaSession
                val thinkingSupported = llamaModel.supportsThinking()
                val visionSupported = llamaModel.supportsVision()
                val toolCallingSupported = llamaModel.supportsToolCalling()
                // Cache the real, template-detected capabilities so the model
                // list can show accurate badges for this model (and any custom
                // GGUF) without having to load it again.
                storagePreferences.setDetectedCaps(
                    model.filename, toolCallingSupported, thinkingSupported
                )
                listener.onCapabilities(
                    model, thinkingSupported, visionSupported, toolCallingSupported
                )
                listener.onLoadProgress(0f)
                listener.onLoadStatus(modelDescription)

                // Replay history into the new session BEFORE marking the
                // model ready. If a persisted message exceeds the
                // 700 KB binder ceiling, replayHistory throws — and we
                // do NOT want the user to start a new turn against a
                // session that's missing prior context (the model
                // would answer follow-up questions as if they were
                // fresh prompts). Tear the session+model down on
                // failure and surface a clear error.
                val messages = messagesToReplay()
                if (messages.isNotEmpty()) {
                    try {
                        HistoryReplay.replayToSession(llamaSession, messages)
                    } catch (e: PayloadTooLargeException) {
                        this@ModelRuntime.session = null
                        this@ModelRuntime.model = null
                        try { llamaSession.destroy() } catch (_: Throwable) {}
                        try { llamaModel.unloadModel() } catch (_: Throwable) {}
                        listener.onLoadStatus(
                            app.getString(R.string.replay_history_too_large)
                        )
                        return@withContext
                    }
                }
                listener.onModelReady(model)
            } catch (t: Throwable) {
                // Surface the failure to the user instead of leaving
                // the picker stuck on "Loading…" forever.
                listener.onLoadProgress(0f)
                listener.onLoadStatus(app.getString(R.string.model_load_failed_status))
                fileHandle.close()
                if (t !is CancellationException) {
                    Log.w(TAG, "loadModel failed", t)
                    listener.onModelLoadFailed(model.name)
                } else {
                    throw t
                }
            } finally {
                progressJob.cancel()
            }
        }
    }

    /**
     * Create a session on [model] with [params], converting a dead-service
     * AIDL failure into a user-visible error instead of an app crash.
     */
    fun createSessionWithParams(
        model: LlamaModel,
        params: GenerationParams,
        systemPrompt: String,
    ): LlamaGenerationSession? {
        return try {
            model.createSession(
                params.contextSize,
                params.temperature,
                params.topP,
                params.repetitionPenalty,
                params.topK,
                params.minP,
                params.seed,
                params.thinkingBudget,
                systemPrompt
            )
        } catch (e: InferenceUnavailableException) {
            // The :llama service died (or hasn't bound yet). Surface a
            // recoverable error to the UI rather than letting the AIDL
            // exception propagate and crash the app process.
            Log.w(TAG, "createSession failed: service unavailable", e)
            listener.onUserError(
                app.getString(R.string.inference_engine_unavailable)
            )
            null
        }
    }

    /**
     * Swap [newSession] in as the live session after replaying [messages]
     * into it, destroying [prevSession] only once the replay succeeds (so a
     * late failure leaves the prior session intact instead of stranding the
     * UI session-less).
     *
     * Centralises the create-then-replace error handling shared by the
     * params-change, session-load and system-prompt paths:
     *   - [PayloadTooLargeException]: a persisted message exceeds the binder
     *     cap; tear the new session down and keep the old one.
     *   - [InferenceUnavailableException]: the :llama service died mid-replay
     *     (or never re-connected). Previously this escaped the viewModelScope
     *     coroutine and crashed the app process — surfacing on Google Play as
     *     withService / requireConnected IUE. Now we tear the half-built
     *     session down and surface a recoverable error; the crash-recovery
     *     flow cleans up the stale handles.
     *
     * Returns true if the swap happened, false if the caller should abort.
     */
    private fun swapInSessionWithReplay(
        newSession: LlamaGenerationSession,
        prevSession: LlamaGenerationSession?,
        messages: List<Message>,
    ): Boolean {
        try {
            if (messages.isNotEmpty()) {
                HistoryReplay.replayToSession(newSession, messages)
            }
        } catch (e: PayloadTooLargeException) {
            try { newSession.destroy() } catch (_: Throwable) {}
            listener.onUserError(
                app.getString(R.string.history_message_too_large)
            )
            return false
        } catch (e: InferenceUnavailableException) {
            Log.w(TAG, "replayHistory failed: service unavailable", e)
            try { newSession.destroy() } catch (_: Throwable) {}
            listener.onUserError(
                app.getString(R.string.inference_engine_unavailable)
            )
            return false
        }
        session = newSession
        prevSession?.destroy()
        return true
    }

    /**
     * Recreate the session with [params] + [systemPrompt], replaying
     * [messages] into it. Create-then-swap: the previous session is
     * destroyed only after a successful create + replay, so a late failure
     * leaves the prior session usable. No-op when no model is loaded.
     *
     * [catchPayloadTooLargeOnCreate] mirrors the system-prompt path's
     * defense-in-depth catch around createSession itself (on top of the
     * caller's validateReplaySize pre-check).
     */
    suspend fun recreateSessionWithReplay(
        params: GenerationParams,
        systemPrompt: String,
        messages: List<Message>,
        catchPayloadTooLargeOnCreate: Boolean = false,
    ) {
        val model = this.model ?: return
        cancelAndJoinGeneration()

        val prevSession = session

        withContext(Dispatchers.Default) {
            // Create the new session FIRST. Only after a successful
            // create + replay do we destroy the old one — this way
            // a late failure leaves the prior session intact and
            // usable instead of stranding the UI session-less.
            val newSession = if (catchPayloadTooLargeOnCreate) {
                try {
                    createSessionWithParams(model, params, systemPrompt)
                } catch (e: PayloadTooLargeException) {
                    listener.onUserError(
                        app.getString(
                            R.string.system_prompt_too_large,
                            systemPrompt.length * 2 / 1024,
                            com.druk.llamacpp.InferenceLimits.MAX_PAYLOAD_BYTES / 1024,
                        )
                    )
                    null
                }
            } else {
                createSessionWithParams(model, params, systemPrompt)
            } ?: return@withContext
            swapInSessionWithReplay(newSession, prevSession, messages)
        }
    }

    /**
     * Replace the session with a fresh one (no replay) after a context-size
     * change. The previous session is destroyed either way; on create
     * failure the runtime is left session-less (the old context size is
     * unusable with the new params).
     */
    suspend fun resetSessionForContextChange(params: GenerationParams, systemPrompt: String) {
        val model = this.model ?: return
        cancelAndJoinGeneration()

        val prevSession = session

        withContext(Dispatchers.Default) {
            val newSession = createSessionWithParams(model, params, systemPrompt)
            if (newSession != null) {
                session = newSession
                prevSession?.destroy()
            } else {
                // Keep using the old session if we couldn't make a new one.
                prevSession?.destroy()
                session = null
            }
        }
    }

    /**
     * Start a clean conversation: destroy the previous session first, then
     * create a fresh one with a clean KV cache. No-op when no model is
     * loaded.
     */
    suspend fun resetSessionDestroyFirst(params: GenerationParams, systemPrompt: String) {
        val model = this.model ?: return
        cancelAndJoinGeneration()

        val prevSession = session
        session = null
        withContext(Dispatchers.Default) {
            prevSession?.destroy()
            val newSession = createSessionWithParams(model, params, systemPrompt) ?: return@withContext
            session = newSession
        }
    }

    /**
     * Snapshot the references that are live AT THE TIME OF THE CRASH.
     * Needed because cleanup runs *after* a potentially long wait — during
     * which the user may have acknowledged the crash and successfully
     * loaded a NEW model. Cleanup must only clear handles that still point
     * at the dead session/model; otherwise it would close the new model's
     * PFD and null the new session, leaving the UI ready with no engine.
     */
    fun crashSnapshot(): CrashSnapshot =
        CrashSnapshot(session, model, modelFileHandle, generatingJob)

    /**
     * Drain the crashed generation and clear the dead handles. Returns true
     * when the snapshot's handles were still current and have been cleaned
     * up — the caller should then surface the crash in the UI. Returns
     * false when the user already reloaded a model during the wait (the
     * current handles belong to a working session on a fresh `:llama`
     * process; nothing is touched).
     */
    suspend fun recoverFromCrash(snapshot: CrashSnapshot): Boolean {
        // The cancelled generation coroutine isn't done yet —
        // generateAll() suspends up to 30 s waiting for the dead
        // worker to drain. Until that finally block has run, the
        // job's NonCancellable cleanup could still mutate the last
        // UI message and persist whatever it sees. We MUST wait for
        // it to drain before we touch any shared state, or it'll
        // persist a future placeholder against the now-stale sessionId.
        val priorJob = generatingJob
        generatingJob = null
        try {
            priorJob?.cancelAndJoin()
        } catch (_: Throwable) { /* job is dead either way */ }

        if (model !== snapshot.model ||
            session !== snapshot.session ||
            modelFileHandle !== snapshot.handle
        ) {
            return false
        }

        // Still pointing at the dead handles — clean them up.
        session = null
        model = null
        modelFileHandle?.close()
        modelFileHandle = null
        return true
    }

    /** True when a model (or at least its file handle) is loaded. */
    fun hasNativeHandles(): Boolean = modelFileHandle != null || model != null

    /**
     * Tear down the native handles (generation, session, model, file
     * descriptor). Safe to call when nothing is loaded.
     */
    suspend fun unloadNative() {
        cancelAndJoinGeneration()

        // Capture and null references on main thread to prevent races
        val prevSession = session
        val prevModel = model
        val prevHandle = modelFileHandle
        session = null
        model = null
        modelFileHandle = null

        withContext(Dispatchers.Default) {
            prevSession?.destroy()
            prevModel?.unloadModel()
        }

        prevHandle?.close()
    }

    /**
     * Fire-and-forget teardown for ViewModel.onCleared: the handles are
     * nulled synchronously; the blocking native cleanup finishes on a
     * detached scope.
     */
    fun shutdown() {
        val job = generatingJob
        val session = this.session
        val model = this.model
        val handle = modelFileHandle
        generatingJob = null
        this.session = null
        this.model = null
        modelFileHandle = null

        CoroutineScope(Dispatchers.Default).launch {
            job?.cancel()
            job?.join()
            session?.destroy()
            model?.unloadModel()
            handle?.close()
        }
    }

    fun getReport(): String? {
        // Invoked synchronously on the main thread from the token-count tap.
        // Both proxy calls go over AIDL and throw InferenceUnavailableException
        // if the :llama service crashed or hasn't bound — there's nothing to
        // report in that case, so swallow it instead of crashing the app
        // (seen on Google Play as withService / requireConnected IUE).
        return try {
            val modelReport = model?.getModelReport() ?: return null
            val sessionReport = session?.getReport() ?: return null
            modelReport + "\n" + sessionReport
        } catch (e: InferenceUnavailableException) {
            Log.w(TAG, "getReport failed: service unavailable", e)
            null
        }
    }

    private companion object {
        private const val TAG = "ModelRuntime"
    }
}
