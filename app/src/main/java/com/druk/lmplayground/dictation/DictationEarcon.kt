package com.druk.lmplayground.dictation

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * The short tones that mark dictation starting and stopping: a soft rising
 * two-note blip when the microphone begins recording, the same notes falling
 * when it stops. Synthesized once, so there is no audio asset to ship.
 *
 * Played as an assistant sound (media volume), not a notification: the user
 * just tapped the button and is waiting for the cue, so it must not be
 * swallowed by silent mode the way the background "response ready" chime is.
 * Best-effort — any audio failure is logged and ignored.
 */
object DictationEarcon {

    private const val TAG = "DictationEarcon"
    private const val SAMPLE_RATE = 44_100
    private const val NOTE_MS = 70
    private const val VOLUME = 0.22f

    private val start by lazy { twoNotes(660.0, 880.0) }
    private val stop by lazy { twoNotes(880.0, 660.0) }

    fun playStart() = play(start)

    fun playStop() = play(stop)

    private fun play(pcm: ShortArray) {
        try {
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.size * 2)
                .build()
            track.write(pcm, 0, pcm.size)
            // Released once the last frame plays, so a tap never leaks a track.
            track.notificationMarkerPosition = pcm.size
            track.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
                override fun onMarkerReached(t: AudioTrack) = t.release()
                override fun onPeriodicNotification(t: AudioTrack) = Unit
            })
            track.play()
        } catch (t: Throwable) {
            Log.w(TAG, "earcon failed", t)
        }
    }

    /** Two sine notes back to back, each with a short fade in and out. */
    private fun twoNotes(first: Double, second: Double): ShortArray {
        val perNote = SAMPLE_RATE * NOTE_MS / 1000
        val fade = SAMPLE_RATE * 8 / 1000
        val out = ShortArray(perNote * 2)
        for ((n, freq) in listOf(first, second).withIndex()) {
            for (i in 0 until perNote) {
                val envelope = min(1f, min(i, perNote - 1 - i) / fade.toFloat())
                val sample = sin(2 * PI * freq * i / SAMPLE_RATE) * VOLUME * envelope
                out[n * perNote + i] = (sample * Short.MAX_VALUE).toInt().toShort()
            }
        }
        return out
    }
}
