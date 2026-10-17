package com.druk.lmplayground.dictation

import android.os.ParcelFileDescriptor
import android.util.Log
import com.druk.llamacpp.LlamaCpp
import com.druk.lmplayground.models.ModelInfoProvider
import com.druk.lmplayground.storage.StorageRepository
import java.io.File
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/**
 * Owns the speech-recognition model's lifecycle (Parakeet TDT, see
 * [ModelInfoProvider.dictationModel]) and turns recorded PCM into text.
 *
 * Independent of the chat model: dictation works whatever is loaded, or even
 * with nothing loaded. The model is loaded on first use and released by the
 * service after an idle period (or under memory pressure), so this class only
 * has to notice when the service dropped it and load again.
 *
 * Handle management mirrors [com.druk.lmplayground.rag.EmbeddingModelManager]:
 * the [StorageRepository.ModelFileHandle] stays open while the model is loaded
 * and is closed on unload. All entry points are suspend and hop through
 * [mutex]; call them off the main thread (binder round-trips block).
 */
class DictationManager(
    private val llamaCpp: LlamaCpp?,
    private val storageRepository: StorageRepository,
) {
    private val mutex = Mutex()
    private var fileHandle: StorageRepository.ModelFileHandle? = null

    val modelInfo = ModelInfoProvider.dictationModel

    fun isModelOnDisk(): Boolean =
        storageRepository.getModelFiles().any { it.name == modelInfo.filename }

    /**
     * Transcribe a raw 16 kHz mono f32 PCM file produced by [AudioRecorder].
     *
     * @param targetLang a locale such as "en", or "auto" to let the model
     *   detect the language.
     * @return the transcript (possibly blank when the clip held no speech), or
     *   null when the model is unavailable or transcription failed.
     */
    suspend fun transcribe(pcmFile: File, targetLang: String = "auto"): String? = mutex.withLock {
        val llamaCpp = llamaCpp ?: return null
        if (!ensureModelLoadedLocked()) return null

        val pcmFd = try {
            ParcelFileDescriptor.open(pcmFile, ParcelFileDescriptor.MODE_READ_ONLY)
        } catch (t: Throwable) {
            Log.e(TAG, "Cannot open recording ${pcmFile.name}", t)
            return null
        }
        return try {
            // The service closes its copy of the descriptor; ours is closed by
            // `use` regardless of how the call ends. The timeout covers the case
            // where :llama dies mid-transcription — the callback would then never
            // fire and the caller would wait forever with the UI stuck on
            // "Transcribing…".
            pcmFd.use {
                withTimeout(TRANSCRIBE_TIMEOUT_MS) { llamaCpp.transcribe(it, targetLang) }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Transcription failed", t)
            null
        }
    }

    /**
     * Transcribe one slice of live dictation. Returns null when the model is
     * unavailable or the decode failed.
     *
     * Loading is serialized by [mutex], but the decode itself is not: slices
     * arrive every few seconds and the service already runs them in order on
     * its own ASR thread. Holding the lock across the decode would let an
     * idle-unload check stall dictation mid-sentence.
     */
    suspend fun transcribeChunk(pcm: FloatArray, targetLang: String = "auto"): String? {
        val llamaCpp = llamaCpp ?: return null
        mutex.withLock { if (!ensureModelLoadedLocked()) return null }
        return try {
            llamaCpp.transcribeSamples(pcm, targetLang)
        } catch (t: Throwable) {
            Log.e(TAG, "Chunk transcription failed", t)
            null
        }
    }

    /**
     * Open a live dictation stream, loading the model first if needed. Returns
     * 0 when the model is missing or cannot stream.
     */
    suspend fun beginStream(targetLang: String = "auto"): Int = mutex.withLock {
        val llamaCpp = llamaCpp ?: return 0
        if (!ensureModelLoadedLocked()) return 0
        return try {
            llamaCpp.startDictationStream(targetLang)
        } catch (t: Throwable) {
            Log.e(TAG, "beginStream failed", t)
            0
        }
    }

    /**
     * Feed one slice. Deliberately outside [mutex]: feeds happen several times
     * a second and the service already serializes them on its ASR thread;
     * taking the lock here would let an idle-unload check stall dictation.
     */
    fun feed(streamId: Int, pcm: FloatArray): String? = try {
        llamaCpp?.feedDictationAudio(streamId, pcm)
    } catch (t: Throwable) {
        Log.e(TAG, "feed failed", t)
        null
    }

    fun endStream(streamId: Int): String? = try {
        llamaCpp?.finishDictationStream(streamId)
    } catch (t: Throwable) {
        Log.e(TAG, "endStream failed", t)
        null
    }

    fun cancelStream(streamId: Int) {
        llamaCpp?.cancelDictationStream(streamId)
    }

    suspend fun unload() = mutex.withLock { unloadLocked() }

    private fun ensureModelLoadedLocked(): Boolean {
        val llamaCpp = llamaCpp ?: return false
        // The service unloads the model when idle or under memory pressure, so
        // a previously-loaded model may well be gone by now.
        if (llamaCpp.isAsrModelLoaded()) return true
        // Whatever the service had is gone; drop our stale descriptor with it.
        unloadLocked()

        val handle = storageRepository.openModelFile(modelInfo.filename)
        if (handle == null) {
            Log.w(TAG, "Dictation model not available: ${modelInfo.filename}")
            return false
        }
        return try {
            if (llamaCpp.loadAsrModel(handle.pfd)) {
                fileHandle = handle
                Log.i(TAG, "Dictation model loaded")
                true
            } else {
                Log.e(TAG, "loadAsrModel failed")
                handle.close()
                false
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Dictation model load failed", t)
            handle.close()
            false
        }
    }

    private fun unloadLocked() {
        try {
            llamaCpp?.unloadAsrModel()
        } catch (t: Throwable) {
            Log.w(TAG, "unloadAsrModel failed", t)
        }
        fileHandle?.close()
        fileHandle = null
    }

    companion object {
        private const val TAG = "DictationManager"

        /**
         * Generous upper bound for a 60 s clip on a slow CPU — this is a
         * liveness backstop for a dead service, not a performance budget.
         */
        private const val TRANSCRIBE_TIMEOUT_MS = 120_000L
    }
}
