package com.druk.lmplayground.dictation

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlin.math.sqrt
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Records microphone audio in the format the Parakeet engine consumes:
 * 16 kHz mono, little-endian 32-bit float PCM, written raw (no container) to a
 * file in the app cache. The file is handed to the inference process as a file
 * descriptor — a minute of audio is several times the binder transaction cap,
 * so it never crosses as a byte array.
 *
 * One recorder instance handles one recording at a time.
 */
class AudioRecorder(private val context: Context) {

    /**
     * A slice of freshly recorded audio, handed straight to the recognizer.
     * [samples] is 16 kHz mono f32 and is only valid until the next emission.
     */
    data class Chunk(
        val samples: FloatArray,
        val elapsedMs: Long,
        val amplitude: Float,
    )

    @Volatile private var stopRequested = false

    /**
     * Records until [stop] is called, the flow is cancelled, or
     * [MAX_DURATION_MS] elapses, emitting each buffer as it arrives so the
     * recognizer can transcribe while the user is still speaking.
     *
     * Nothing is written to disk: live dictation consumes the audio as it is
     * produced, so there is no recording to keep.
     *
     * The caller must hold RECORD_AUDIO — [android.media.AudioRecord] silently
     * yields empty buffers otherwise.
     */
    @SuppressLint("MissingPermission")
    fun record(): Flow<Chunk> = callbackFlow {
        stopRequested = false

        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (minBuffer <= 0) {
            close(IllegalStateException("AudioRecord.getMinBufferSize returned $minBuffer"))
            return@callbackFlow
        }
        // 4× the minimum: enough slack that a scheduling hiccup on the reader
        // thread doesn't drop samples mid-word.
        val bufferSize = minBuffer * 4

        val recorder = AudioRecord(
            // VOICE_RECOGNITION skips the aggressive processing the
            // communication sources apply, which ASR models dislike.
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            bufferSize,
        )
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            close(IllegalStateException("AudioRecord failed to initialize"))
            return@callbackFlow
        }

        val thread = Thread({
            val samples = FloatArray(bufferSize / Float.SIZE_BYTES)
            var totalSamples = 0L
            try {
                recorder.startRecording()
                while (!stopRequested && totalSamples < MAX_SAMPLES) {
                    val read = recorder.read(samples, 0, samples.size, AudioRecord.READ_BLOCKING)
                    if (read <= 0) {
                        if (read < 0) Log.w(TAG, "AudioRecord.read returned $read")
                        continue
                    }
                    totalSamples += read
                    var sumSquares = 0.0
                    for (i in 0 until read) sumSquares += samples[i] * samples[i].toDouble()
                    trySend(
                        Chunk(
                            // Copied: the buffer is reused by the next read.
                            samples = samples.copyOf(read),
                            elapsedMs = totalSamples * 1000 / SAMPLE_RATE,
                            amplitude = sqrt(sumSquares / read).toFloat(),
                        ),
                    )
                }
                close()
            } catch (t: Throwable) {
                Log.e(TAG, "recording failed", t)
                close(t)
            } finally {
                try {
                    recorder.stop()
                } catch (_: IllegalStateException) {
                    // Never started (e.g. immediate cancel) — nothing to stop.
                }
                recorder.release()
            }
        }, "audio-recorder")
        thread.start()

        awaitClose {
            stopRequested = true
            thread.join(STOP_TIMEOUT_MS)
        }
    }

    /** Ends the in-flight recording; the flow completes once the file is closed. */
    fun stop() {
        stopRequested = true
    }

    companion object {
        private const val TAG = "AudioRecorder"

        const val SAMPLE_RATE = 16_000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_FLOAT

        /**
         * Dictation is a sentence or two, not a memo. The cap bounds both the
         * temp file and the worst-case transcription latency.
         */
        const val MAX_DURATION_MS = 60_000L
        private const val MAX_SAMPLES = SAMPLE_RATE * MAX_DURATION_MS / 1000

        private const val STOP_TIMEOUT_MS = 2_000L
    }
}
