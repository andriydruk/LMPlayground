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
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the microphone button is doing right now. */
sealed interface DictationState {
    /** Ready to record (or the model still has to be downloaded — see [DictationController.isModelReady]). */
    data object Idle : DictationState
    data class Recording(val elapsedMs: Long, val amplitude: Float) : DictationState
    data object Transcribing : DictationState
}

/**
 * Drives voice dictation for the chat input: record → transcribe → hand the
 * text back to the composer.
 *
 * Recording and transcription both run off the main thread; the LiveData here
 * is what the input bar renders. The caller (the fragment) owns the
 * RECORD_AUDIO permission prompt and must only call [startRecording] once the
 * permission is granted.
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

    /** Transcript awaiting insertion into the composer; cleared by [consumeTranscript]. */
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
     * Start recording. The caller must hold RECORD_AUDIO. Recording stops on
     * [stopRecording], [cancelRecording], or the recorder's own duration cap.
     */
    fun startRecording() {
        if (_state.value !is DictationState.Idle) return
        val output = AudioRecorder.outputFile(app)
        _state.value = DictationState.Recording(0L, 0f)

        recordingJob = recorder.record(output)
            .onEach { level ->
                // A cancel may have already moved us out of Recording; don't
                // resurrect the state from a buffered emission.
                if (_state.value is DictationState.Recording) {
                    _state.postValue(DictationState.Recording(level.elapsedMs, level.amplitude))
                }
            }
            .catch { t ->
                Log.e(TAG, "recording failed", t)
                _state.postValue(DictationState.Idle)
                _error.postValue(app.getString(R.string.dictation_record_failed))
            }
            .onCompletion { cause ->
                // Cancelled by cancelRecording(): the file is discarded.
                if (cause != null || _state.value !is DictationState.Recording) return@onCompletion
                transcribe(output)
            }
            .launchIn(scope)
    }

    /** Finish recording and transcribe what was captured. */
    fun stopRecording() {
        if (_state.value !is DictationState.Recording) return
        recorder.stop()
    }

    /** Abandon the recording without transcribing. */
    fun cancelRecording() {
        if (_state.value !is DictationState.Recording) return
        _state.value = DictationState.Idle
        recorder.stop()
        recordingJob?.cancel()
        recordingJob = null
        AudioRecorder.outputFile(app).delete()
    }

    private fun transcribe(pcmFile: java.io.File) {
        _state.postValue(DictationState.Transcribing)
        scope.launch {
            val text = withContext(Dispatchers.IO) { manager.transcribe(pcmFile) }
            pcmFile.delete()
            _state.postValue(DictationState.Idle)
            when {
                text == null -> _error.postValue(app.getString(R.string.dictation_failed))
                text.isBlank() -> _error.postValue(app.getString(R.string.dictation_no_speech))
                else -> _transcript.postValue(text.trim())
            }
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
    }
}
