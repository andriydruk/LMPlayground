package com.druk.lmplayground.rag

import android.util.Log
import com.druk.llamacpp.LlamaCpp
import com.druk.llamacpp.LlamaEmbeddingSession
import com.druk.llamacpp.LlamaModel
import com.druk.llamacpp.LlamaProgressCallback
import com.druk.lmplayground.models.ModelInfo
import com.druk.lmplayground.models.ModelInfoProvider
import com.druk.lmplayground.storage.StorageRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The embedding surface [RagRepository] depends on. Extracted so tests can
 * substitute a deterministic embedder for [EmbeddingModelManager].
 */
interface TextEmbedder {
    val modelInfo: ModelInfo

    /** Whether the embedding model's GGUF is present in the model folder. */
    fun isModelOnDisk(): Boolean

    /**
     * Embed document [chunks] (with the document task prefix). Returns
     * one L2-normalized vector per chunk, or null on failure.
     */
    suspend fun embedDocumentChunks(chunks: List<String>): List<FloatArray>?

    /**
     * Embed a retrieval [query] (with the query task prefix). Returns an
     * L2-normalized vector, or null on failure.
     */
    suspend fun embedQuery(query: String): FloatArray?
}

/**
 * Owns the embedding model's lifecycle (EmbeddingGemma, see
 * [ModelInfoProvider.embeddingModel]) and applies its task prefixes.
 *
 * The model is loaded transiently: on first use, kept warm for
 * [IDLE_UNLOAD_MS] after the last call, then unloaded — ~300 MB should
 * not sit next to a multi-GB chat model for the whole app lifetime.
 * Handle management mirrors ModelRuntime: the [StorageRepository.ModelFileHandle]
 * stays open for the model's lifetime (the service-side mmap dies with
 * the descriptor) and is closed on unload.
 *
 * All entry points are suspend and hop through [mutex]; call them off
 * the main thread (binder round-trips block).
 */
class EmbeddingModelManager(
    private val llamaCpp: LlamaCpp?,
    private val storageRepository: StorageRepository,
    private val scope: CoroutineScope,
) : TextEmbedder {
    private val mutex = Mutex()
    private var model: LlamaModel? = null
    private var session: LlamaEmbeddingSession? = null
    private var fileHandle: StorageRepository.ModelFileHandle? = null
    private var idleUnloadJob: Job? = null

    override val modelInfo = ModelInfoProvider.embeddingModel

    override fun isModelOnDisk(): Boolean =
        storageRepository.getModelFiles().any { it.name == modelInfo.filename }

    override suspend fun embedDocumentChunks(chunks: List<String>): List<FloatArray>? =
        withSession { session ->
            session.embed(chunks.map { "title: none | text: $it" })
        }

    override suspend fun embedQuery(query: String): FloatArray? =
        withSession { session ->
            session.embed(listOf("task: search result | query: $query"))?.firstOrNull()
        }

    suspend fun unload() = mutex.withLock {
        idleUnloadJob?.cancel()
        idleUnloadJob = null
        unloadLocked()
    }

    private suspend fun <T> withSession(block: (LlamaEmbeddingSession) -> T?): T? =
        mutex.withLock {
            idleUnloadJob?.cancel()
            idleUnloadJob = null
            val session = ensureSessionLocked() ?: return null
            try {
                block(session)
            } catch (t: Throwable) {
                Log.e(TAG, "Embedding call failed", t)
                // The session may be in an undefined state (service crash
                // mid-call) — drop it; the next call reloads from scratch.
                unloadLocked()
                null
            } finally {
                scheduleIdleUnloadLocked()
            }
        }

    private fun ensureSessionLocked(): LlamaEmbeddingSession? {
        session?.let { return it }
        val llamaCpp = llamaCpp ?: return null

        val handle = storageRepository.openModelFile(modelInfo.filename)
        if (handle == null) {
            Log.w(TAG, "Embedding model not available: ${modelInfo.filename}")
            return null
        }
        return try {
            val loaded = llamaCpp.loadModel(
                handle.pfd,
                object : LlamaProgressCallback {
                    override fun onProgress(progress: Float) {}
                },
            )
            val created = loaded.createEmbeddingSession(EMBEDDING_CONTEXT_SIZE)
            if (created == null) {
                Log.e(TAG, "createEmbeddingSession failed")
                loaded.unloadModel()
                handle.close()
                return null
            }
            model = loaded
            session = created
            fileHandle = handle
            Log.i(TAG, "Embedding model loaded (dim=${created.getEmbeddingDim()})")
            created
        } catch (t: Throwable) {
            Log.e(TAG, "Embedding model load failed", t)
            handle.close()
            null
        }
    }

    private fun unloadLocked() {
        session?.destroy()
        session = null
        model?.unloadModel()
        model = null
        fileHandle?.close()
        fileHandle = null
    }

    private fun scheduleIdleUnloadLocked() {
        idleUnloadJob?.cancel()
        if (session == null) return
        idleUnloadJob = scope.launch {
            delay(IDLE_UNLOAD_MS)
            mutex.withLock {
                // A new use cancels this job before taking the mutex, so
                // reaching this point means the model sat idle the full
                // window. The isActive check keeps a cancel that raced
                // the delay from unloading a session a fresh caller is
                // about to use.
                if (isActive) unloadLocked()
            }
        }
    }

    companion object {
        private const val TAG = "EmbeddingModelManager"

        /** EmbeddingGemma's training context; chunks are far smaller. */
        private const val EMBEDDING_CONTEXT_SIZE = 2048

        /** Keep-warm window after the last embed call. */
        private const val IDLE_UNLOAD_MS = 60_000L
    }
}
