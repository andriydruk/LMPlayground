package com.druk.llamacpp.jni

/**
 * Direct JNI binding to the Parakeet ASR engine. Loaded only inside the
 * `:llama` process; app code goes through `com.druk.llamacpp.LlamaCpp`.
 *
 * A loaded context is a raw native handle. It is not re-entrant: callers must
 * serialize [transcribe] per handle.
 */
class NativeAsr {

    companion object {
        init {
            System.loadLibrary("llamacpp")
        }
    }

    /**
     * @param modelPath a filesystem path or an `fd:N` pseudo-path for a file
     *   descriptor owned by this process (SAF storage).
     * @return a native handle, or 0 when the model could not be loaded.
     */
    external fun loadModel(modelPath: String): Long

    /**
     * Transcribes little-endian f32 PCM (16 kHz mono) read from [pcmFd].
     *
     * @param targetLang a locale such as "en" or "de"; "auto" (or null) lets the
     *   multilingual checkpoints detect the language themselves.
     * @return the transcript, or null on failure (see [lastError]).
     */
    external fun transcribe(handle: Long, pcmFd: Int, targetLang: String?): String?

    /**
     * Same as [transcribe] but opens [pcmPath] itself. Used off Android, where
     * the audio is a plain file rather than a descriptor handed over binder.
     */
    external fun transcribePath(handle: Long, pcmPath: String, targetLang: String?): String?

    /**
     * Transcribe samples already in memory. Live dictation uses this for each
     * few-second slice of speech, so nothing has to reach disk.
     */
    external fun transcribeSamples(
        handle: Long,
        pcm: FloatArray,
        nSamples: Int,
        targetLang: String?,
    ): String?

    /**
     * Begin a live transcription stream. Returns 0 when the model is not a
     * cache-aware streaming checkpoint.
     */
    external fun streamBegin(handle: Long, targetLang: String?): Long

    /**
     * Feed newly recorded samples. Returns the text finalized *since the
     * previous feed* ("" when nothing settled yet), null on error.
     */
    external fun streamFeed(streamHandle: Long, pcm: FloatArray, nSamples: Int): String?

    /** Flush the tail after the last feed. */
    external fun streamFinalize(streamHandle: Long): String?

    external fun streamFree(streamHandle: Long)

    external fun lastError(handle: Long): String

    external fun freeModel(handle: Long)
}
