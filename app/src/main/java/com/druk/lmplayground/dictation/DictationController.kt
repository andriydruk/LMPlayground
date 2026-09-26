package com.druk.lmplayground.dictation

import android.app.Application
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.map
import com.druk.llamacpp.asr.DictationTranscript
import com.druk.lmplayground.R
import com.druk.lmplayground.download.DownloadRepository
import com.druk.lmplayground.storage.StorageRepository
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** What the microphone button is doing right now. */
sealed interface DictationState {
    data object Idle : DictationState

    /**
     * Dictation is under way: recording, recognizing, or both. [text] is
     * everything recognized so far in this session and grows as words land.
     */
    data class Listening(val text: String) : DictationState

    /** Recording has ended; the recognizer is still draining its backlog. */
    data object Finishing : DictationState
}

/**
 * Drives voice dictation for the chat input.
 *
 * Audio is fed to the recognizer as it is recorded and partial text comes back
 * within a few hundred milliseconds, so words appear while the user is still
 * speaking.
 *
 * Recording and recognition are deliberately separate. The recognizer runs
 * behind the microphone — on a phone it decodes slower than speech — so it is
 * still draining its backlog for a moment after the user lets go. Pressing
 * again during that moment must not be swallowed: the new recording starts
 * immediately and its audio waits in a queue for the recognizer, and the two
 * utterances land in the input field in the order they were spoken.
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

    /**
     * Serializes access to the recognizer. One utterance holds it from
     * beginStream to endStream; the next waits here while still recording, so
     * its audio is captured rather than dropped.
     */
    private val recognizer = Mutex()

    /** Text recognized since the composer's base was captured, across utterances. */
    private val sessionText = DictationTranscript()

    /** Recordings plus recognitions still in flight; at zero the session is done. */
    private val outstanding = AtomicInteger(0)

    /** True while the microphone is capturing — one recording at a time. */
    @Volatile private var recording = false

    private val jobs = mutableListOf<Job>()

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

    /** Called the moment the button goes down, before any async work. */
    fun onMicPressed() {
        held = true
    }

    /** Called on release or gesture cancel; ends dictation if it got going. */
    fun onMicReleased() {
        held = false
        stopListening()
    }

    /**
     * Start listening. The caller must hold RECORD_AUDIO. Ends on
     * [stopListening] or the recorder's duration cap.
     */
    fun startListening() {
        // Released again before the start caught up.
        if (!held) return
        // Only the microphone is exclusive. Recognition of a previous utterance
        // may still be running, and that must not block a new one.
        if (recording) return
        recording = true
        outstanding.incrementAndGet()
        _state.postValue(DictationState.Listening(synchronized(sessionText) { sessionText.text }))

        val job = scope.launch {
            // Audio is buffered so recording never waits for the recognizer.
            val audio = Channel<FloatArray>(Channel.UNLIMITED)

            val capture = launch(Dispatchers.IO) {
                try {
                    recorder.record().collect { audio.send(it.samples) }
                } catch (t: CancellationException) {
                    throw t
                } catch (t: Throwable) {
                    Log.e(TAG, "recording failed", t)
                    _error.postValue(app.getString(R.string.dictation_record_failed))
                } finally {
                    audio.close()
                    // The microphone is free now, even though this utterance is
                    // still being recognized below.
                    recording = false
                }
            }

            try {
                recognizer.withLock {
                    val streamId = manager.beginStream()
                    if (streamId == 0) {
                        _error.postValue(app.getString(R.string.dictation_failed))
                        audio.cancel()
                        return@withLock
                    }
                    var closed = false
                    try {
                        for (pcm in audio) {
                            val delta = withContext(Dispatchers.IO) { manager.feed(streamId, pcm) }
                            if (!delta.isNullOrEmpty()) appendRecognized(delta)
                        }
                        val tail = withContext(Dispatchers.IO) { manager.endStream(streamId) }
                        closed = true
                        if (!tail.isNullOrEmpty()) appendRecognized(tail)
                    } finally {
                        // The next stream starts a fresh detokenization, so
                        // its first word must not run into this one's last.
                        synchronized(sessionText) { sessionText.endUtterance() }
                        // Cancelled part-way (the user discarded): the native
                        // stream still has to be released.
                        if (!closed) withContext(NonCancellable) { manager.cancelStream(streamId) }
                    }
                }
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                Log.e(TAG, "recognition failed", t)
                _error.postValue(app.getString(R.string.dictation_failed))
            } finally {
                capture.join()
                if (outstanding.decrementAndGet() == 0) finishSession()
            }
        }
        jobs += job
        job.invokeOnCompletion { jobs.remove(job) }
    }

    /**
     * Finish the current recording and keep what was recognized. Safe to call
     * when nothing is running — a press whose start was still pending is
     * stopped by [held].
     */
    fun stopListening() {
        held = false
        recorder.stop()
        // Recording has ended but the recognizer is still catching up; the
        // composer keeps the text it already has.
        if (outstanding.get() > 0) _state.postValue(DictationState.Finishing)
    }

    /**
     * Appends newly recognized text and shows the session so far. The delta
     * goes in verbatim: it carries its own spacing, and often ends mid-word.
     */
    private fun appendRecognized(delta: String) {
        val snapshot = synchronized(sessionText) {
            sessionText.append(delta)
            sessionText.text
        }
        _state.postValue(DictationState.Listening(snapshot))
    }

    /** Everything spoken has been recognized: hand the text to the composer. */
    private fun finishSession() {
        val text = synchronized(sessionText) {
            sessionText.text.also { sessionText.clear() }
        }
        emitTranscript(text)
        _state.postValue(DictationState.Idle)
    }

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
