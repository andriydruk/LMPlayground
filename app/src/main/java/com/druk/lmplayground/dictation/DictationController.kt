package com.druk.lmplayground.dictation

import android.app.Application
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.map
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
    // Lazy: touching DownloadRepository initializes WorkManager, and this
    // controller is built with the ViewModel — long before any download.
    private val downloads by lazy { DownloadRepository(app) }

    /**
     * Progress of the dictation model download, or null when none is running.
     * Negative means the job is queued but has no byte count yet (waiting for
     * network), which the UI shows as an indeterminate spinner.
     *
     * The mic surfaces this because the download is ~700 MB: without it the
     * user presses the button, is told to download, and then has no idea
     * anything is happening.
     */
    val downloadProgress: LiveData<Float?> by lazy {
        downloads.observeDownloads().map { it[modelInfo.name]?.progress }
    }

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

    /**
     * Whether the microphone button is currently held.
     *
     * Starting is asynchronous (an on-disk check, then loading the model), so a
     * quick tap can release before recording has begun. Every step on the way
     * up re-checks this, and releasing clears it — without that, a fast tap
     * would leave dictation running with no finger on the button.
     */
    @Volatile private var held = false

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
        downloads.startDownload(manager.modelInfo, storageUri)
    }

    /**
     * Start listening. The caller must hold RECORD_AUDIO. Ends on
     * [stopListening], [cancelListening], or the recorder's duration cap.
     *
     * Audio is fed to the recognizer as it is recorded and partial text comes
     * back within a few hundred milliseconds, so words appear while the user is
     * still speaking.
     */
    /** Called the moment the button goes down, before any async work. */
    fun onMicPressed() {
        held = true
    }

    /** Called on release or gesture cancel; ends dictation if it got going. */
    fun onMicReleased() {
        held = false
        stopListening()
    }

    fun startListening() {
        // Released again before the start caught up.
        if (!held) return
        // Synchronous guard: _state is updated with postValue, so a second
        // press can arrive before the first has been reflected there. Two
        // sessions would share one AudioRecorder and fight over its stop flag,
        // leaving the microphone running.
        if (recordingJob?.isActive == true) return
        if (_state.value !is DictationState.Idle) return
        _state.value = DictationState.Listening(0L, 0f, "")

        recordingJob = scope.launch {
            val streamId = manager.beginStream()
            if (streamId == 0) {
                _state.postValue(DictationState.Idle)
                _error.postValue(app.getString(R.string.dictation_failed))
                return@launch
            }
            // Loading the model can take a moment on first use; the finger may
            // be gone by now.
            if (!held) {
                withContext(Dispatchers.IO) { manager.cancelStream(streamId) }
                _state.postValue(DictationState.Idle)
                return@launch
            }

            val heard = StringBuilder()
            var cancelled = false
            try {
                // Feeds block for the decode; running them on the recorder's
                // own thread would drop audio.
                withContext(Dispatchers.IO) {
                    recorder.record().collect { chunk ->
                        val delta = manager.feed(streamId, chunk.samples)
                        if (!delta.isNullOrEmpty()) heard.append(delta)
                        _state.postValue(
                            DictationState.Listening(
                                elapsedMs = chunk.elapsedMs,
                                amplitude = chunk.amplitude,
                                text = heard.toString().trim(),
                            ),
                        )
                    }
                }
            } catch (t: Throwable) {
                cancelled = true
                Log.e(TAG, "dictation failed", t)
                _error.postValue(app.getString(R.string.dictation_record_failed))
            }

            if (cancelled) {
                withContext(Dispatchers.IO) { manager.cancelStream(streamId) }
            } else {
                // The decoder is behind the speaker by however long its backlog
                // is; finalizing drains it, so show that we are catching up.
                _state.postValue(DictationState.Finishing)
                val tail = withContext(Dispatchers.IO) { manager.endStream(streamId) }
                if (!tail.isNullOrEmpty()) heard.append(tail)
                emitTranscript(heard.toString().trim())
            }
            _state.postValue(DictationState.Idle)
        }
    }

    /**
     * Finish dictation and keep what was recognized. Safe to call when nothing
     * is running — a press whose start was still pending is stopped by [held].
     */
    fun stopListening() {
        held = false
        recorder.stop()
    }

    /** Abandon dictation and discard the text. */
    fun cancelListening() {
        held = false
        if (_state.value !is DictationState.Listening) return
        _state.value = DictationState.Idle
        recorder.stop()
        recordingJob?.cancel()
        recordingJob = null
        // Terminal event so the composer restores what the user had typed.
        _transcript.value = ""
    }

    /**
     * Always posts a terminal value, even an empty one: the composer writes
     * recognized text live and needs a definitive final answer to settle on —
     * "" meaning "put back what was there".
     */
    private fun emitTranscript(text: String) {
        if (text.isEmpty()) _error.postValue(app.getString(R.string.dictation_no_speech))
        _transcript.postValue(text)
    }

    fun consumeTranscript() {
        _transcript.value = null
    }

    fun consumeError() {
        _error.value = null
    }

    companion object {
        private const val TAG = "DictationController"

    }
}
