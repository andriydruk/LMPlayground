package com.druk.lmplayground.inference

import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.util.Log
import com.druk.llamacpp.ILlamaGenerationCallback
import com.druk.llamacpp.ILlamaProgressCallback
import com.druk.llamacpp.ILlamaService
import com.druk.llamacpp.ITranscriptionCallback
import com.druk.llamacpp.LlamaProgressCallback
import com.druk.llamacpp.SamplerParams
import com.druk.llamacpp.jni.NativeAsr
import com.druk.llamacpp.jni.NativeLlamaCpp
import com.druk.llamacpp.jni.NativeLlamaEmbeddingSession
import com.druk.llamacpp.jni.NativeLlamaModel
import com.druk.llamacpp.jni.NativeLlamaSession
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Bound service that owns the llama.cpp native state.
 *
 * In step 3 this still runs in the main app process (same UID, same
 * memory). In step 5 the manifest declaration adds `android:process=":llama"`
 * so this service hosts the JNI in a separate OS process — at which point
 * a SIGSEGV inside llama.cpp only kills `:llama`, not the UI.
 *
 * The service hands out positive int handles (`modelId`, `sessionId`).
 * Native pointers never leave this process. Map entries are guarded by
 * `ConcurrentHashMap`'s implicit locking.
 */
class LlamaService : Service() {

    private val nativeLlamaCpp by lazy {
        NativeLlamaCpp().also { it.init(applicationInfo.nativeLibraryDir, filesDir.absolutePath) }
    }

    private val nextModelId = AtomicInteger(1)
    private val nextSessionId = AtomicInteger(1)
    private val nextEmbeddingSessionId = AtomicInteger(1)

    private class ModelEntry(
        val modelId: Int,
        val nativeModel: NativeLlamaModel,
        // Held alive for the model's lifetime when loaded from a PFD
        val pfd: ParcelFileDescriptor?,
    ) {
        /**
         * Set to true when the client called `unloadModel` but at least
         * one of the model's sessions still has a live worker. The model
         * stays in [models] until the last such worker exits and clears
         * the way via [tryFinalizeModelTeardown].
         */
        @Volatile var pendingDestroy: Boolean = false

        /**
         * Serializes session creation against the native model free.
         *
         * A session being created is not yet in [sessions], so it is
         * invisible to the `stillHasSessions` check in
         * [tryFinalizeModelTeardown] — without this lock a concurrent
         * `unloadModel` frees the model while `llama_init_from_model`
         * is still reading it, and the context constructor faults on the
         * dangling pointer. Creation checks [pendingDestroy] and
         * registers the new session while holding the lock; the free
         * takes the same lock, so it either observes the registered
         * session or runs before creation started.
         */
        val lock = Any()

        /**
         * Held alive for the model's lifetime when a vision projector
         * (mmproj) was loaded from a PFD. Closed in
         * [tryFinalizeModelTeardown] alongside [pfd].
         */
        @Volatile var mmprojPfd: ParcelFileDescriptor? = null
    }

    private class SessionEntry(
        val sessionId: Int,
        val modelId: Int,
        val nativeSession: NativeLlamaSession,
    ) {
        /**
         * Active generation worker, or null if no generation is running.
         *
         * AtomicReference so we can `compareAndSet` to atomically claim
         * the slot in [startGeneration] — the binder dispatcher can
         * deliver two startGeneration calls for the same session
         * concurrently on different binder threads, and starting two
         * workers on the same `nativeSession` (two threads inside
         * `nativeSession.generate()`) is undefined behavior that
         * crashes `:llama`.
         */
        val worker = AtomicReference<GenerationWorker?>(null)

        /**
         * Set to true when the client called `destroySession` (or
         * `unloadModel` on the parent model) but the worker hadn't
         * exited yet. The entry stays in [sessions] under this flag so
         * future operations can observe the live worker; once the worker
         * actually exits, [onWorkerExit] sees the flag and finishes the
         * cleanup that was deferred.
         */
        @Volatile var pendingDestroy: Boolean = false

        /**
         * Accumulators for chunked replayHistory. Each call carries at
         * most one string (≤700 KB) so it fits under the binder cap; we
         * zip the two lists into the native call on finalize.
         */
        val pendingReplayUsers: MutableList<String> = mutableListOf()
        val pendingReplayAssistants: MutableList<String> = mutableListOf()
    }

    /**
     * Embedding sessions are deliberately separate from [sessions]: they
     * have no GenerationWorker, no replay buffers and no pendingDestroy
     * dance — embed calls are short synchronous binder calls serialized
     * against destroy by [lock].
     */
    private class EmbeddingEntry(
        val embeddingSessionId: Int,
        val modelId: Int,
        val nativeSession: NativeLlamaEmbeddingSession,
    ) {
        /**
         * embedTexts and the destroy path both take this lock, so a native
         * embed in flight on one binder thread can't have its context freed
         * out from under it by unloadModel/destroyEmbeddingSession on another.
         */
        val lock = Any()
        @Volatile var destroyed = false
    }

    private val models = ConcurrentHashMap<Int, ModelEntry>()
    private val sessions = ConcurrentHashMap<Int, SessionEntry>()
    private val embeddingSessions = ConcurrentHashMap<Int, EmbeddingEntry>()

    /**
     * The loaded voice-dictation model, or null. Unlike chat models there is
     * at most one, it has no sessions, and it is driven exclusively from
     * [asrExecutor] — the native context is not re-entrant.
     */
    private class AsrEntry(
        val handle: Long,
        val pfd: ParcelFileDescriptor?,
    )

    private val nativeAsr by lazy { NativeAsr() }
    private val asrModel = AtomicReference<AsrEntry?>(null)

    /**
     * Single thread: serializes transcription against itself and against
     * load/unload, so a transcribe in flight can never have its context freed
     * underneath it.
     */
    private val asrExecutor: ExecutorService by lazy {
        Executors.newSingleThreadExecutor { r -> Thread(r, "asr-worker") }
    }

    /**
     * Live dictation streams, keyed by an int handed to the client so native
     * pointers never cross the process boundary.
     */
    private val dictationStreams = ConcurrentHashMap<Int, Long>()
    private val nextStreamId = AtomicInteger(1)

    private val asrIdleHandler by lazy { Handler(Looper.getMainLooper()) }
    private val asrIdleUnload = Runnable {
        if (asrModel.get() != null) {
            Log.d(TAG, "unloading ASR model after ${ASR_IDLE_TIMEOUT_MS / 60_000} min idle")
            unloadAsr()
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "LlamaService.onCreate (pid=${android.os.Process.myPid()})")
        InferenceNotification.ensureChannel(this)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        // Mark every model and session for destroy, then tear down what
        // we can synchronously. Anything still pending (worker alive)
        // will leak until the worker exits, at which point onWorkerExit
        // → tryFinalizeModelTeardown will finish the cleanup. The
        // process is going away anyway, so the leak is bounded.
        models.values.forEach { it.pendingDestroy = true }
        embeddingSessions.values.toList().forEach { tearDownEmbeddingSession(it) }
        sessions.values.toList().forEach { tearDownSession(it) }
        unloadAsr()
        asrExecutor.shutdown()
        super.onDestroy()
    }

    /**
     * Destroy [entry]'s native context and remove it from
     * [embeddingSessions]. Blocks until any in-flight embedTexts on
     * another binder thread finishes (embed calls are short — one small
     * batch per call). Idempotent.
     */
    private fun tearDownEmbeddingSession(entry: EmbeddingEntry) {
        synchronized(entry.lock) {
            if (!entry.destroyed) {
                entry.destroyed = true
                entry.nativeSession.destroy()
            }
        }
        embeddingSessions.remove(entry.embeddingSessionId, entry)
        tryFinalizeModelTeardown(entry.modelId)
    }

    /**
     * Mark [entry] for destroy and try to finish it now. Returns `true`
     * if the entry has been freed and removed from [sessions], `false`
     * if the worker is still alive — in which case the entry stays in
     * [sessions] under the `pendingDestroy` flag and will be finalized
     * by [onWorkerExit] when the worker eventually returns from
     * `nativeSession.generate()`.
     *
     * Critical for UAF avoidance: callers MUST NOT remove [entry] from
     * [sessions] themselves on a `false` return, otherwise a follow-up
     * `unloadModel` for the same model would no longer see the live
     * worker and would happily free the model out from under it.
     */
    private fun tearDownSession(entry: SessionEntry): Boolean {
        // Mark first — a racing onWorkerExit must observe pendingDestroy.
        entry.pendingDestroy = true
        val worker = entry.worker.get()
        if (worker == null) {
            return finalizeSessionTeardown(entry)
        }
        worker.cancel()
        if (worker.join()) {
            // Thread.run's finally has already fired onWorkerExit, which
            // saw pendingDestroy=true and finalized. Idempotent re-check.
            return !sessions.containsKey(entry.sessionId)
        }
        Log.w(
            TAG,
            "session ${entry.sessionId}: worker still alive after join budget; " +
                "deferring native destroy to onWorkerExit (entry stays in sessions)",
        )
        return false
    }

    /**
     * Called by the worker thread right before it exits. CAS-clears the
     * worker slot so we don't clobber a successor that may have been
     * installed by a concurrent `startGeneration` between the two
     * lifecycle events. If the entry has already been marked for
     * destroy, completes the teardown we couldn't do synchronously.
     */
    private fun onWorkerExit(sessionId: Int, finishedWorker: GenerationWorker) {
        val entry = sessions[sessionId] ?: return
        entry.worker.compareAndSet(finishedWorker, null)
        if (entry.pendingDestroy) {
            finalizeSessionTeardown(entry)
        }
    }

    /**
     * Free the native session and remove [entry] from [sessions], then
     * re-attempt any pending model teardown that this session was
     * blocking. Returns `true` if the entry was actually removed (false
     * if a concurrent finalize beat us to it — that's fine, the
     * native destroy is still safe).
     */
    private fun finalizeSessionTeardown(entry: SessionEntry): Boolean {
        // remove() returns the prior value; we own the destroy iff WE
        // removed it. Avoids double-free when destroySession races with
        // onWorkerExit.
        val removed = sessions.remove(entry.sessionId, entry)
        if (removed) {
            entry.nativeSession.destroy()
            tryFinalizeModelTeardown(entry.modelId)
        }
        return removed
    }

    /**
     * If [modelId] was marked for destroy and its last surviving session
     * worker has now exited, free the native model and PFD. No-op
     * otherwise.
     */
    private fun tryFinalizeModelTeardown(modelId: Int) {
        val entry = models[modelId] ?: return
        if (!entry.pendingDestroy) return
        // Under the lock a session creation in flight has either not
        // started (it will then see pendingDestroy and bail) or has
        // already registered itself, so `stillHasSessions` sees it.
        synchronized(entry.lock) {
            val stillHasSessions = sessions.values.any { it.modelId == modelId } ||
                embeddingSessions.values.any { it.modelId == modelId }
            if (stillHasSessions) return
            if (models.remove(modelId, entry)) {
                entry.nativeModel.unloadModel()
                entry.pfd?.close()
                entry.mmprojPfd?.close()
                // A loaded ASR model keeps the process worth protecting even
                // when no chat model is left.
                if (models.isEmpty() && asrModel.get() == null) demoteFromForeground()
            }
        }
    }

    /**
     * Free the ASR context. Runs the native free on [asrExecutor] so it can
     * never overlap a transcription, but claims the slot immediately so
     * callers (and [isAsrModelLoaded]) see the model as gone right away.
     */
    private fun unloadAsr() {
        val entry = asrModel.getAndSet(null) ?: return
        asrIdleHandler.removeCallbacks(asrIdleUnload)
        // Streams hold state inside the model's context, so they go first.
        val streams = dictationStreams.keys.toList().mapNotNull { dictationStreams.remove(it) }
        asrExecutor.execute {
            streams.forEach { runCatching { nativeAsr.streamFree(it) } }
            try {
                nativeAsr.freeModel(entry.handle)
            } catch (t: Throwable) {
                Log.w(TAG, "ASR unload failed", t)
            }
            entry.pfd?.close()
            // The ASR model can be the only thing keeping :llama alive.
            if (models.isEmpty()) demoteFromForeground()
        }
    }

    private fun scheduleAsrIdleUnload() {
        asrIdleHandler.removeCallbacks(asrIdleUnload)
        asrIdleHandler.postDelayed(asrIdleUnload, ASR_IDLE_TIMEOUT_MS)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Dictation is a transient convenience; a ~700 MB ASR model is the
        // first thing worth giving back when the system is under pressure.
        if (level >= TRIM_MEMORY_RUNNING_CRITICAL && asrModel.get() != null) {
            Log.i(TAG, "onTrimMemory($level): unloading ASR model")
            unloadAsr()
        }
    }

    private val binder = object : ILlamaService.Stub() {

        override fun initBackend(): Int {
            // Lazy-init triggers System.loadLibrary + llama_backend_init
            nativeLlamaCpp
            return 0
        }

        override fun systemInfo(): String = nativeLlamaCpp.systemInfo()

        override fun probeModelMetadata(path: String?, pfd: ParcelFileDescriptor?): Array<String>? {
            val resolved = resolvePath(path, pfd) ?: return null
            return try {
                nativeLlamaCpp.probeModelMetadata(resolved)
            } finally {
                // probe doesn't retain the FD — close immediately
                pfd?.close()
            }
        }

        override fun loadModel(
            path: String?,
            pfd: ParcelFileDescriptor?,
            progress: ILlamaProgressCallback,
            disableRepack: Boolean,
            chatTemplateOverride: String?,
        ): Int {
            val resolved = resolvePath(path, pfd) ?: return 0
            val nativeProgress = object : LlamaProgressCallback {
                override fun onProgress(p: Float) {
                    try { progress.onProgress(p) } catch (_: RemoteException) {}
                }
            }
            return try {
                val nativeModel = nativeLlamaCpp.loadModel(
                    resolved, nativeProgress, disableRepack, chatTemplateOverride.orEmpty(),
                )
                if (nativeModel == null) {
                    Log.e(
                        TAG,
                        "native loadModel returned null (corrupt GGUF or " +
                            "unsupported architecture at $resolved)",
                    )
                    pfd?.close()
                    return 0
                }
                val id = nextModelId.getAndIncrement()
                models[id] = ModelEntry(id, nativeModel, pfd)
                // First model loaded → promote to foreground so the OS doesn't
                // evict the :llama process under memory pressure mid-inference.
                if (models.size == 1) promoteToForeground()
                id
            } catch (t: Throwable) {
                Log.e(TAG, "loadModel failed", t)
                pfd?.close()
                0
            }
        }

        override fun getModelSize(modelId: Int): Long =
            models[modelId]?.nativeModel?.getModelSize() ?: 0L

        override fun getModelReport(modelId: Int): String =
            models[modelId]?.nativeModel?.getModelReport() ?: ""

        override fun getContextTrainSize(modelId: Int): Int =
            models[modelId]?.nativeModel?.getContextTrainSize() ?: 0

        override fun supportsThinking(modelId: Int): Boolean =
            models[modelId]?.nativeModel?.supportsThinking() == true

        override fun supportsToolCalling(modelId: Int): Boolean =
            models[modelId]?.nativeModel?.supportsToolCalling() == true

        override fun loadMmprojModel(
            modelId: Int,
            path: String?,
            pfd: ParcelFileDescriptor?,
        ): Boolean {
            val entry = models[modelId] ?: run { pfd?.close(); return false }
            val resolved = resolvePath(path, pfd) ?: return false
            return try {
                entry.nativeModel.loadMmprojModel(resolved)
                // mtmd loads the projector at init; when it came from a PFD,
                // hold it alive for the model's lifetime (closed in
                // tryFinalizeModelTeardown), mirroring the model PFD.
                if (pfd != null) entry.mmprojPfd = pfd
                entry.nativeModel.supportsVision()
            } catch (t: Throwable) {
                Log.e(TAG, "loadMmprojModel failed", t)
                pfd?.close()
                false
            }
        }

        override fun supportsVision(modelId: Int): Boolean =
            models[modelId]?.nativeModel?.supportsVision() == true

        override fun unloadModel(modelId: Int) {
            val entry = models[modelId] ?: return
            // Mark for destroy first (DON'T remove from `models` yet).
            // Then tear down all attached sessions. tearDownSession leaves
            // any leaked entries in `sessions` under their pendingDestroy
            // flag; tryFinalizeModelTeardown then refuses to free the
            // model until the last such session's worker has exited.
            // This prevents the UAF where a worker would still be inside
            // nativeSession.generate() (and thus holding a llama_context
            // derived from this model) when we freed the model.
            entry.pendingDestroy = true
            embeddingSessions.values.filter { it.modelId == modelId }.toList().forEach { e ->
                tearDownEmbeddingSession(e)
            }
            sessions.values.filter { it.modelId == modelId }.toList().forEach { s ->
                tearDownSession(s)
            }
            tryFinalizeModelTeardown(modelId)
        }

        override fun createEmbeddingSession(modelId: Int, contextSize: Int): Int {
            val model = models[modelId] ?: return 0
            synchronized(model.lock) {
                if (model.pendingDestroy) return 0
                return try {
                    val native = model.nativeModel.createEmbeddingSession(contextSize) ?: return 0
                    val id = nextEmbeddingSessionId.getAndIncrement()
                    embeddingSessions[id] = EmbeddingEntry(id, modelId, native)
                    id
                } catch (t: Throwable) {
                    Log.e(TAG, "createEmbeddingSession failed", t)
                    0
                }
            }
        }

        override fun getEmbeddingDim(embeddingSessionId: Int): Int {
            val entry = embeddingSessions[embeddingSessionId] ?: return 0
            synchronized(entry.lock) {
                if (entry.destroyed) return 0
                return entry.nativeSession.getEmbeddingDim()
            }
        }

        override fun embedTexts(embeddingSessionId: Int, texts: Array<String>): FloatArray? {
            val entry = embeddingSessions[embeddingSessionId] ?: return null
            synchronized(entry.lock) {
                if (entry.destroyed) return null
                return entry.nativeSession.embedTexts(texts)
            }
        }

        override fun destroyEmbeddingSession(embeddingSessionId: Int) {
            val entry = embeddingSessions[embeddingSessionId] ?: return
            tearDownEmbeddingSession(entry)
        }

        override fun createSession(modelId: Int, params: SamplerParams): Int {
            val model = models[modelId] ?: return 0
            synchronized(model.lock) {
                if (model.pendingDestroy) return 0
                val nativeSession = model.nativeModel.createSession(
                    params.contextSize,
                    params.temperature,
                    params.topP,
                    params.repetitionPenalty,
                    params.topK,
                    params.minP,
                    params.seed,
                    params.thinkingBudget,
                    params.systemPrompt,
                ) ?: return 0
                val id = nextSessionId.getAndIncrement()
                sessions[id] = SessionEntry(id, modelId, nativeSession)
                return id
            }
        }

        override fun addMessage(sessionId: Int, message: String, enableThinking: Boolean) {
            val rc = sessions[sessionId]?.nativeSession?.addMessage(message, enableThinking)
            if (rc != null && rc != 0) {
                Log.w(TAG, "addMessage rejected for session $sessionId (rc=$rc); turn dropped")
            }
        }

        override fun setImageData(sessionId: Int, data: ByteArray) {
            sessions[sessionId]?.nativeSession?.setImageData(data)
        }

        override fun beginReplayHistory(sessionId: Int) {
            sessions[sessionId]?.let {
                it.pendingReplayUsers.clear()
                it.pendingReplayAssistants.clear()
            }
        }

        override fun appendReplayUser(sessionId: Int, userMessage: String) {
            sessions[sessionId]?.pendingReplayUsers?.add(userMessage)
        }

        override fun appendReplayAssistant(sessionId: Int, assistantMessage: String) {
            sessions[sessionId]?.pendingReplayAssistants?.add(assistantMessage)
        }

        override fun finalizeReplayHistory(sessionId: Int) {
            val entry = sessions[sessionId] ?: return
            val users = entry.pendingReplayUsers
            val assistants = entry.pendingReplayAssistants
            if (users.size != assistants.size) {
                Log.w(
                    TAG,
                    "finalizeReplayHistory: mismatched buffers " +
                        "users=${users.size} assistants=${assistants.size} — discarding",
                )
                users.clear(); assistants.clear()
                return
            }
            if (users.isEmpty()) return
            val u = users.toTypedArray()
            val a = assistants.toTypedArray()
            users.clear(); assistants.clear()
            entry.nativeSession.replayHistory(u, a)
        }

        override fun startGeneration(sessionId: Int, cb: ILlamaGenerationCallback) {
            val entry = sessions[sessionId] ?: run {
                try { cb.onGenerationFinished(-1) } catch (_: RemoteException) {}
                return
            }
            // Two-phase atomic claim, all serialized through the
            // entry.worker AtomicReference:
            //  1. Drain any prior worker (cancel + join). If join times
            //     out, refuse — prior is still inside generate() and a
            //     parallel worker on the same llama_context would crash.
            //  2. CAS-claim the slot from null to our new worker. If a
            //     concurrent startGeneration on a different binder
            //     thread already installed a different worker between
            //     phases 1 and 2, our CAS fails and we refuse — the
            //     other call will run.
            val prior = entry.worker.get()
            if (prior != null) {
                prior.cancel()
                if (!prior.join()) {
                    Log.w(
                        TAG,
                        "session $sessionId: prior worker still alive after join " +
                            "budget; refusing concurrent startGeneration",
                    )
                    try { cb.onGenerationFinished(-3) } catch (_: RemoteException) {}
                    return
                }
                // join() returning true means Thread.run's finally has
                // already executed onWorkerExit, which CAS-cleared the
                // slot to null (unless the slot has since been claimed).
            }
            // Construct the worker (Thread already alive but blocked on
            // its publish latch — see GenerationWorker docs). We must
            // call publish() on the winner OR cancel() on the loser so
            // the latched thread eventually exits.
            val newWorker = GenerationWorker(
                sessionId = sessionId,
                nativeSession = entry.nativeSession,
                cb = cb,
                onFinished = { id, w -> onWorkerExit(id, w) },
            )
            if (!entry.worker.compareAndSet(null, newWorker)) {
                Log.w(
                    TAG,
                    "session $sessionId: lost CAS for worker slot " +
                        "(concurrent startGeneration); refusing",
                )
                // The worker thread is alive on the latch — release it
                // via cancel() so it sees cancelled=true and exits.
                newWorker.cancel()
                try { cb.onGenerationFinished(-3) } catch (_: RemoteException) {}
                return
            }
            // Publish: lets the worker thread proceed past the latch
            // and start the actual generation loop. Until this call,
            // tearDownSession can safely cancel + join the worker
            // because the thread is alive (blocked on the latch) and
            // join() will not return prematurely.
            newWorker.publish()
        }

        override fun cancelGeneration(sessionId: Int) {
            // .cancel() just flips a @Volatile flag the worker checks
            // between tokens — safe to call even if the worker slot has
            // been swapped under us.
            sessions[sessionId]?.worker?.get()?.cancel()
        }

        override fun getSessionReport(sessionId: Int): String =
            sessions[sessionId]?.nativeSession?.getReport() ?: ""

        override fun printSessionReport(sessionId: Int) {
            sessions[sessionId]?.nativeSession?.printReport()
        }

        override fun destroySession(sessionId: Int) {
            // Don't pre-remove — tearDownSession only removes from
            // [sessions] after the native destroy actually succeeds.
            // Otherwise a still-running worker would be invisible to a
            // follow-up unloadModel and the model could be freed under it.
            val entry = sessions[sessionId] ?: return
            tearDownSession(entry)
        }

        override fun setTools(sessionId: Int, toolsJson: String) {
            sessions[sessionId]?.nativeSession?.setTools(toolsJson)
        }

        override fun getToolCallsJson(sessionId: Int): String =
            sessions[sessionId]?.nativeSession?.getToolCallsJson() ?: "[]"

        override fun submitToolResults(
            sessionId: Int,
            resultsJson: String,
            enableThinking: Boolean,
        ): Int {
            val entry = sessions[sessionId] ?: return 1
            return entry.nativeSession.submitToolResults(resultsJson, enableThinking)
        }

        override fun setPreambleCachePath(
            sessionId: Int,
            path: String,
            fingerprint: String,
        ) {
            sessions[sessionId]?.nativeSession?.setPreambleCachePath(path, fingerprint)
        }

        override fun transcribeSamples(pcm: FloatArray, targetLang: String): String? {
            val entry = asrModel.get() ?: return null
            asrIdleHandler.removeCallbacks(asrIdleUnload)
            return try {
                asrExecutor.submit<String?> {
                    // Re-read the slot on the worker: an unload may have claimed
                    // it between the binder call and this task.
                    asrModel.get()?.let { nativeAsr.transcribeSamples(it.handle, pcm, pcm.size, targetLang) }
                }.get()
            } catch (t: Throwable) {
                Log.e(TAG, "transcribeSamples failed", t)
                null
            } finally {
                scheduleAsrIdleUnload()
            }
        }

        override fun startDictationStream(targetLang: String): Int {
            val entry = asrModel.get() ?: return 0
            asrIdleHandler.removeCallbacks(asrIdleUnload)
            return try {
                val stream = asrExecutor.submit<Long> {
                    nativeAsr.streamBegin(entry.handle, targetLang)
                }.get()
                if (stream == 0L) {
                    Log.e(TAG, "streamBegin failed: ${nativeAsr.lastError(entry.handle)}")
                    return 0
                }
                val id = nextStreamId.getAndIncrement()
                dictationStreams[id] = stream
                id
            } catch (t: Throwable) {
                Log.e(TAG, "startDictationStream failed", t)
                0
            }
        }

        override fun feedDictationAudio(streamId: Int, pcm: FloatArray): String? {
            val stream = dictationStreams[streamId] ?: return null
            return try {
                asrExecutor.submit<String?> {
                    nativeAsr.streamFeed(stream, pcm, pcm.size)
                }.get()
            } catch (t: Throwable) {
                Log.e(TAG, "feedDictationAudio failed", t)
                null
            }
        }

        override fun finishDictationStream(streamId: Int): String? {
            val stream = dictationStreams.remove(streamId) ?: return null
            return try {
                asrExecutor.submit<String?> {
                    val tail = nativeAsr.streamFinalize(stream)
                    nativeAsr.streamFree(stream)
                    tail
                }.get()
            } catch (t: Throwable) {
                Log.e(TAG, "finishDictationStream failed", t)
                null
            } finally {
                scheduleAsrIdleUnload()
            }
        }

        override fun cancelDictationStream(streamId: Int) {
            val stream = dictationStreams.remove(streamId) ?: return
            asrExecutor.execute { nativeAsr.streamFree(stream) }
            scheduleAsrIdleUnload()
        }

        override fun loadAsrModel(path: String?, pfd: ParcelFileDescriptor?): Boolean {
            val resolved = resolvePath(path, pfd) ?: return false
            // Replacing an existing model: drop the old one first so the two
            // never coexist in RAM.
            unloadAsr()
            return try {
                // Load on the ASR thread and wait: the caller is a background
                // coroutine and the result decides whether dictation can run.
                val handle = asrExecutor.submit<Long> {
                    nativeAsr.loadModel(resolved)
                }.get()
                if (handle == 0L) {
                    Log.e(TAG, "native ASR loadModel returned 0 for $resolved")
                    pfd?.close()
                    return false
                }
                asrModel.set(AsrEntry(handle, pfd))
                // Loading is not itself a reason to stay resident forever.
                scheduleAsrIdleUnload()
                if (models.isEmpty()) promoteToForeground()
                true
            } catch (t: Throwable) {
                Log.e(TAG, "loadAsrModel failed", t)
                pfd?.close()
                false
            }
        }

        override fun isAsrModelLoaded(): Boolean = asrModel.get() != null

        override fun unloadAsrModel() = unloadAsr()

        override fun transcribe(
            pcmFd: ParcelFileDescriptor,
            targetLang: String,
            cb: ITranscriptionCallback,
        ) {
            val entry = asrModel.get()
            if (entry == null) {
                pcmFd.close()
                try { cb.onTranscriptionError("ASR model not loaded") } catch (_: RemoteException) {}
                return
            }
            asrIdleHandler.removeCallbacks(asrIdleUnload)
            asrExecutor.execute {
                try {
                    // Re-read the slot on the worker thread: an unload may have
                    // claimed it between the binder call and this runnable.
                    val live = asrModel.get()
                    if (live == null) {
                        try {
                            cb.onTranscriptionError("ASR model not loaded")
                        } catch (_: RemoteException) {}
                        return@execute
                    }
                    val text = nativeAsr.transcribe(live.handle, pcmFd.fd, targetLang)
                    if (text == null) {
                        val error = nativeAsr.lastError(live.handle)
                        try {
                            cb.onTranscriptionError(
                                error.ifEmpty { "Transcription failed" },
                            )
                        } catch (_: RemoteException) {}
                    } else {
                        try { cb.onTranscription(text) } catch (_: RemoteException) {}
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "transcribe failed", t)
                    try {
                        cb.onTranscriptionError(t.message ?: "Transcription failed")
                    } catch (_: RemoteException) {}
                } finally {
                    pcmFd.close()
                    scheduleAsrIdleUnload()
                }
            }
        }

        // Self-managed via loadModel/unloadModel — kept here for the AIDL
        // contract; callers don't need to invoke them.
        override fun requestForeground() = promoteToForeground()
        override fun releaseForeground() = demoteFromForeground()

        override fun setForegroundContent(title: String?, text: String?, actionBody: String?) {
            if (title != null) foregroundTitle = title
            if (text != null) foregroundText = text
            // Not sticky: each call sets the action body verbatim (null
            // clears the Copy/Share buttons for the loaded/generating states).
            foregroundActionBody = actionBody
            if (!isForeground) return
            try {
                val notification = InferenceNotification.build(
                    this@LlamaService,
                    foregroundTitle,
                    foregroundText,
                    foregroundActionBody,
                )
                getSystemService(NotificationManager::class.java)
                    ?.notify(InferenceNotification.NOTIFICATION_ID, notification)
            } catch (t: Throwable) {
                Log.w(TAG, "setForegroundContent notify failed", t)
            }
        }

        override fun crashForTest() {
            // Debug-only fault injection. In release builds this is a no-op
            // so production users can never trip the kill switch. Used by
            // instrumented tests and the debug-only Settings button to
            // validate crash isolation.
            if (!com.druk.lmplayground.BuildConfig.DEBUG) {
                Log.w(TAG, "crashForTest ignored in release build")
                return
            }
            android.os.Process.killProcess(android.os.Process.myPid())
        }
    }

    @Volatile private var isForeground = false
    // Last values seen via setForegroundContent. Null until the UI process
    // calls in with the loaded model's name and formatted size; the build()
    // helper falls back to the localized defaults when null.
    @Volatile private var foregroundTitle: String? = null
    @Volatile private var foregroundText: String? = null
    // Response text for the Copy/Share notification actions; null = no buttons.
    @Volatile private var foregroundActionBody: String? = null

    @Synchronized
    private fun promoteToForeground() {
        if (isForeground) return
        try {
            val notification = InferenceNotification.build(
                this, foregroundTitle, foregroundText,
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    InferenceNotification.NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            } else {
                startForeground(InferenceNotification.NOTIFICATION_ID, notification)
            }
            isForeground = true
        } catch (t: Throwable) {
            Log.w(TAG, "startForeground failed (continuing as bound-only)", t)
        }
    }

    @Synchronized
    private fun demoteFromForeground() {
        if (!isForeground) return
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
            isForeground = false
            // Clear so the next promote starts from the localized default
            // again until the UI updates with a fresh model name + size.
            foregroundTitle = null
            foregroundText = null
        } catch (t: Throwable) {
            Log.w(TAG, "stopForeground failed", t)
        }
    }

    /**
     * Map an AIDL (path?, pfd?) pair into a string the native side accepts.
     * "fd:N" is a sentinel recognized by our llama.cpp fork's patched
     * ggml_fopen / llama-mmap (see andriydruk/llama.cpp-android). Plain
     * libc reopen via /proc/self/fd/N would EACCES under Scoped Storage
     * — the kernel re-runs path-based permission checks with our UID
     * instead of inheriting the SAF fd's privilege. dup() of the
     * Binder-inherited fd is the only thing that works.
     */
    private fun resolvePath(path: String?, pfd: ParcelFileDescriptor?): String? {
        if (path != null) return path
        if (pfd != null) return "fd:${pfd.fd}"
        return null
    }

    companion object {
        private const val TAG = "LlamaService"

        /**
         * How long a loaded ASR model survives without a transcription. Long
         * enough to keep a dictation-heavy session snappy, short enough that
         * ~700 MB doesn't sit idle behind a chat model for the whole session.
         */
        private const val ASR_IDLE_TIMEOUT_MS = 5 * 60 * 1000L
    }
}
