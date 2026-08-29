package com.druk.lmplayground.dictation

import android.app.Application
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.druk.lmplayground.R
import com.druk.lmplayground.download.DownloadRepository
import com.druk.lmplayground.storage.StorageRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the microphone button is doing right now. */
sealed interface DictationState {
    data object Idle : DictationState

    /**
     * Live dictation in progress. [text] is everything recognized so far and
     * grows while the user speaks; the composer shows it as it arrives.
     */
    data class Listening(
        val elapsedMs: Long,
        val amplitude: Float,
        val text: String,
    ) : DictationState

    /** Stopped; flushing the decoder's tail. Brief — a fraction of a second. */
    data object Finishing : DictationState
}

/**
 * Drives voice dictation for the chat input.
 *
 * Audio is fed to the recognizer as it is recorded and partial text comes back
 * within a few hundred milliseconds, so the user sees words appear while
 * speaking rather than waiting for a transcription pass at the end. Stopping
 * flushes the decoder tail and hands over the final text.
 *
 * The caller (the fragment) owns the RECORD_AUDIO prompt and must only call
 * [startListening] once the permission is granted.
 */
class DictationController(
    private val app: Application,
    private val manager: DictationManager,
    private val storageRepository: StorageRepository,
    private val scope: CoroutineScope,
) {
    private val recorder = AudioRecorder(app)

    private val _state = MutableLiveData<DictationState>(DictationState.Idle)
    val state: LiveData<DictationState> = _state

    /** Whether the dictation model is on disk; null until first checked. */
    private val _isModelReady = MutableLiveData<Boolean?>(null)
    val isModelReady: LiveData<Boolean?> = _isModelReady

    /** Final transcript awaiting insertion into the composer. */
    private val _transcript = MutableLiveData<String?>(null)
    val transcript: LiveData<String?> = _transcript

    /** One-shot user-facing error; cleared by [consumeError]. */
    private val _error = MutableLiveData<String?>(null)
    val error: LiveData<String?> = _error

    val modelInfo = manager.modelInfo

    private var recordingJob: Job? = null

    fun refreshModelAvailability() {
        scope.launch { isModelAvailable() }
    }

    /**
     * Authoritative on-disk check (the cached [isModelReady] may be stale, or
     * still null right after launch). Updates [isModelReady] as a side effect.
     */
    suspend fun isModelAvailable(): Boolean {
        val onDisk = withContext(Dispatchers.IO) { manager.isModelOnDisk() }
        _isModelReady.postValue(onDisk)
        return onDisk
    }

    /** Enqueue the dictation model download through the regular pipeline. */
    fun downloadModel() {
        val storageUri = storageRepository.getStorageUri()
        if (storageUri == null) {
            _error.value = app.getString(R.string.dictation_storage_not_configured)
            return
        }
        DownloadRepository(app).startDownload(manager.modelInfo, storageUri)
    }

    /**
     * Start listening. The caller must hold RECORD_AUDIO. Recording ends on
     * [stopListening], [cancelListening], or the recorder's duration cap.
     *
     * Audio is transcribed in slices while the user is still speaking, so text
     * appears every few seconds rather than only at the end. Slices are cut at
     * a pause where possible — splitting mid-word costs a word at each seam,
     * and a speaker's natural gaps are the cheapest place to break.
     */
    fun startListening() {
        if (_state.value !is DictationState.Idle) return
        _state.value = DictationState.Listening(0L, 0f, "")

        recordingJob = scope.launch {
            val heard = StringBuilder()
            val pending = ArrayList<FloatArray>()
            var pendingSamples = 0
            var cancelled = false

            /** Transcribe everything buffered so far and append what came back. */
            suspend fun flush() {
                if (pendingSamples == 0) return
                val slice = FloatArray(pendingSamples)
                var at = 0
                for (part in pending) {
                    part.copyInto(slice, at)
                    at += part.size
                }
                pending.clear()
                pendingSamples = 0
                val text = withContext(Dispatchers.IO) { manager.transcribeChunk(slice) }
                if (!text.isNullOrBlank()) {
                    if (heard.isNotEmpty()) heard.append(' ')
                    heard.append(text.trim())
                }
            }

            try {
                recorder.record().collect { chunk ->
                    pending += chunk.samples
                    pendingSamples += chunk.samples.size

                    // Cut at a pause once there is enough audio to be worth a
                    // pass, and force a cut if the speaker never pauses.
                    val quiet = chunk.amplitude < SILENCE_LEVEL
                    if (pendingSamples >= MIN_SLICE_SAMPLES && quiet ||
                        pendingSamples >= MAX_SLICE_SAMPLES
                    ) {
                        flush()
                    }

                    _state.postValue(
                        DictationState.Listening(
                            elapsedMs = chunk.elapsedMs,
                            amplitude = chunk.amplitude,
                            text = heard.toString(),
                        ),
                    )
                }
            } catch (t: Throwable) {
                cancelled = true
                Log.e(TAG, "dictation failed", t)
                _error.postValue(app.getString(R.string.dictation_record_failed))
            }

            if (!cancelled) {
                // The tail after the last cut still holds words.
                if (pendingSamples > 0) _state.postValue(DictationState.Finishing)
                flush()
                emitTranscript(heard.toString().trim())
            }
            _state.postValue(DictationState.Idle)
        }
    }

    /** Finish dictation and keep what was recognized. */
    fun stopListening() {
        if (_state.value !is DictationState.Listening) return
        recorder.stop()
    }

    /** Abandon dictation and discard the text. */
    fun cancelListening() {
        if (_state.value !is DictationState.Listening) return
        _state.value = DictationState.Idle
        recorder.stop()
        recordingJob?.cancel()
        recordingJob = null
    }

    private fun emitTranscript(text: String) {
        if (text.isEmpty()) {
            _error.postValue(app.getString(R.string.dictation_no_speech))
        } else {
            _transcript.postValue(text)
        }
    }

    fun consumeTranscript() {
        _transcript.value = null
    }

    fun consumeError() {
        _error.value = null
    }

    companion object {
        private const val TAG = "DictationController"

        /**
         * Slice bounds. The engine transcribes roughly 3x faster than real time
         * on a mid-range phone, so a ~4 s slice is decoded well before the next
         * one is spoken and the text never falls behind. Below the minimum a
         * slice costs more in per-pass overhead than it returns in words.
         */
        private const val MIN_SLICE_SAMPLES = AudioRecorder.SAMPLE_RATE * 4
        private const val MAX_SLICE_SAMPLES = AudioRecorder.SAMPLE_RATE * 8

        /** RMS below this counts as a pause worth cutting on. */
        private const val SILENCE_LEVEL = 0.02f
    }
}
