package com.druk.lmplayground.dictation

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.map
import com.druk.llamacpp.asr.DictationTranscript
import com.druk.lmplayground.R
import com.druk.lmplayground.download.DownloadRepository
import com.druk.lmplayground.storage.StorageRepository
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.log10
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
 * The microphone button is a toggle: one tap starts dictation, the next
 * stops it. [micOn] is what the button shows, and [level] drives its live
 * waveform.
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

    /**
     * Whether the microphone button is on. Set the moment the user taps — the
     * model check and load come after — and cleared when they tap again or the
     * recording ends on its own (duration cap, error, [abandon]).
     */
    private val _micOn = MutableLiveData(false)
    val micOn: LiveData<Boolean> = _micOn

    /**
     * Loudness of the audio being recorded, 0 (silence) to 1 (loud speech),
     * about 16 times a second; 0 whenever nothing is recording.
     */
    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level.asStateFlow()

    /**
     * True from the first chunk of audio the microphone actually delivers
     * until the recording ends. Unlike [micOn] it waits out the model load,
     * so the button can signal "you're being recorded now", not "you tapped".
     */
    private val _recording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _recording.asStateFlow()

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

    /**
     * One dictation session: from the first press until everything spoken has
     * been recognized and handed to the composer. Pressing again while the
     * recognizer is still catching up joins the running session, so the
     * utterances land together.
     */
    private inner class Session {
        /** Text recognized so far, across utterances. Guarded by itself. */
        val transcript = DictationTranscript()

        /** Recordings plus recognitions still in flight; at zero it is done. */
        val outstanding = AtomicInteger(0)

        /** Parent of the session's coroutines, so [abandon] can stop them all. */
        val job = SupervisorJob(scope.coroutineContext[Job])

        /**
         * Set on the main thread by [abandon]. Once set, nothing from this
         * session reaches the composer — including updates already posted.
         */
        @Volatile var abandoned = false

        fun text(): String = synchronized(transcript) { transcript.text }
    }

    private val sessionLock = Any()
    private var current = Session()  // guarded by sessionLock

    /** True while the microphone is capturing — one recording at a time. */
    @Volatile private var recording = false

    private val main = Handler(Looper.getMainLooper())

    /**
     * Whether the user wants the microphone on.
     *
     * Starting is asynchronous (an on-disk check, then loading the model), so
     * the user can tap off again before recording has begun. Every step on the
     * way up re-checks this, and turning off clears it.
     */
    @Volatile private var held = false

    /**
     * Bumped on every tap-on (main thread). A recording remembers the one it
     * served, so when it ends it can tell "the user tapped off, or the
     * recording hit its cap" (turn the button off) from "the user already
     * tapped on again" (leave the new request alone).
     */
    private var micRequest = 0

    /**
     * A tap-on arrived while the previous recording was still releasing the
     * microphone. That recording starts this one when it lets go.
     */
    private var startPending = false

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

    /** Whether the button is on, for deciding what a tap means. Main thread. */
    val isMicOn: Boolean get() = _micOn.value == true

    /** The user turned the microphone on, before any async work. Main thread. */
    fun onMicPressed() {
        held = true
        micRequest++
        _micOn.value = true
    }

    /**
     * The user turned the microphone off (or never got it started: model
     * missing, permission denied). Ends dictation if it got going. Main thread.
     */
    fun onMicReleased() {
        held = false
        _micOn.value = false
        stopListening()
    }

    /**
     * Start listening. The caller must hold RECORD_AUDIO. Ends on
     * [stopListening], [abandon], or the recorder's duration cap.
     */
    fun startListening() {
        // Turned off again before the start caught up.
        if (!held) return
        // Only the microphone is exclusive. Recognition of a previous utterance
        // may still be running, and that must not block a new one. A recording
        // still letting go of the mic starts this one when it has.
        if (recording) {
            startPending = true
            return
        }
        recording = true
        val request = micRequest
        val session = synchronized(sessionLock) {
            current.also { it.outstanding.incrementAndGet() }
        }
        publish(session) { _state.value = DictationState.Listening(session.text()) }

        scope.launch(session.job) {
            // Audio is buffered so recording never waits for the recognizer.
            val audio = Channel<FloatArray>(Channel.UNLIMITED)

            val capture = launch(Dispatchers.IO) {
                try {
                    recorder.record().collect {
                        _recording.value = true
                        _level.value = levelOf(it.amplitude)
                        audio.send(it.samples)
                    }
                } catch (t: CancellationException) {
                    throw t
                } catch (t: Throwable) {
                    Log.e(TAG, "recording failed", t)
                    _error.postValue(app.getString(R.string.dictation_record_failed))
                } finally {
                    audio.close()
                    _recording.value = false
                    _level.value = 0f
                    // The microphone is free now, even though this utterance is
                    // still being recognized below.
                    recording = false
                    main.post { onRecordingEnded(request) }
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
                            if (!delta.isNullOrEmpty()) appendRecognized(session, delta)
                        }
                        val tail = withContext(Dispatchers.IO) { manager.endStream(streamId) }
                        closed = true
                        if (!tail.isNullOrEmpty()) appendRecognized(session, tail)
                    } finally {
                        // The next stream starts a fresh detokenization, so
                        // its first word must not run into this one's last.
                        synchronized(session.transcript) { session.transcript.endUtterance() }
                        // Abandoned part-way: the native stream still has to
                        // be released.
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
                if (session.outstanding.decrementAndGet() == 0) finishSession(session)
            }
        }
    }

    /**
     * A recording let go of the microphone. If it was still serving the
     * latest tap, the button turns off (that covers the duration cap and a
     * recorder error, where nobody tapped). If the user already tapped on
     * again and that start was waiting for the mic, it runs now. Main thread.
     */
    private fun onRecordingEnded(request: Int) {
        if (request == micRequest) {
            held = false
            _micOn.value = false
        } else if (startPending) {
            startPending = false
            startListening()
        }
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
        val session = synchronized(sessionLock) { current }
        if (session.outstanding.get() > 0) {
            publish(session) { _state.value = DictationState.Finishing }
        }
    }

    /**
     * The user took the text over — edited it by hand, or sent it — while
     * dictation was still writing into it. Stop at once: end the recording,
     * drop whatever the recognizer has not delivered yet, and hand nothing
     * more to the composer. Otherwise the tail of a phrase keeps landing in a
     * field the user is busy changing. Main thread only.
     */
    fun abandon() {
        held = false
        startPending = false
        _micOn.value = false
        val session = synchronized(sessionLock) { current.also { current = Session() } }
        session.abandoned = true
        recorder.stop()
        session.job.cancel()
        _state.value = DictationState.Idle
    }

    /**
     * Appends newly recognized text and shows the session so far. The delta
     * goes in verbatim: it carries its own spacing, and often ends mid-word.
     */
    private fun appendRecognized(session: Session, delta: String) {
        if (session.abandoned) return
        val snapshot = synchronized(session.transcript) {
            session.transcript.append(delta)
            session.transcript.text
        }
        publish(session) { _state.value = DictationState.Listening(snapshot) }
    }

    /** Everything spoken has been recognized: hand the text to the composer. */
    private fun finishSession(session: Session) {
        synchronized(sessionLock) {
            // Another press joined after the count reached zero (its own
            // completion finishes the session), or it was abandoned.
            if (session.outstanding.get() != 0 || current !== session) return
            current = Session()
        }
        session.job.complete()
        val text = session.text()
        // Nothing recognized needs no message: the stop tone and the ring
        // going away already say it ended, and an empty field says the rest.
        publish(session) {
            _transcript.value = text
            _state.value = DictationState.Idle
        }
    }

    /**
     * Delivers a session's update on the main thread, unless the session was
     * abandoned in the meantime. [abandon] runs on the main thread too, so an
     * update already on its way when the user starts typing is dropped here —
     * LiveData.postValue offers no way to take one back.
     */
    private fun publish(session: Session, update: () -> Unit) {
        main.post { if (!session.abandoned) update() }
    }

    fun consumeTranscript() {
        _transcript.value = null
    }

    fun consumeError() {
        _error.value = null
    }

    companion object {
        private const val TAG = "DictationController"

        /** Quiet-room noise; anything below reads as silence. */
        private const val LEVEL_FLOOR_DB = -55f

        /** Loud, close speech; anything above reads as full scale. */
        private const val LEVEL_CEIL_DB = -15f

        /**
         * Maps a chunk's RMS amplitude (float PCM, full scale 1.0) to 0..1 on a
         * decibel scale, which is how loudness is heard: linear RMS would sit
         * near zero for normal speech and only move for shouting.
         */
        internal fun levelOf(rms: Float): Float {
            if (rms <= 0f) return 0f
            val db = 20f * log10(rms)
            return ((db - LEVEL_FLOOR_DB) / (LEVEL_CEIL_DB - LEVEL_FLOOR_DB)).coerceIn(0f, 1f)
        }
    }
}
