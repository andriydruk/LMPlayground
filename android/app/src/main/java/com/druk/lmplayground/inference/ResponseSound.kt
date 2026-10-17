package com.druk.lmplayground.inference

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.util.Log
import com.druk.lmplayground.R

/**
 * Plays a short "response ready" chime. Intended to be fired when a
 * generation finishes while the app is in the background, so the user
 * gets an audible cue without a second notification.
 *
 * Stays silent when the phone is on silent/vibrate or in Do-Not-Disturb,
 * so it never blares in a meeting. Best-effort and fire-and-forget — any
 * failure (no audio focus, missing resource) is swallowed.
 */
object ResponseSound {

    private const val TAG = "ResponseSound"

    fun playIfAudible(context: Context) {
        val audio = context.getSystemService(AudioManager::class.java) ?: return
        // Silent / vibrate-only → stay quiet.
        if (audio.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
        // Do-Not-Disturb (any filter other than "allow all") → stay quiet.
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm != null &&
            nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
        ) {
            return
        }

        try {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            val player = MediaPlayer.create(
                context, R.raw.response_ready, attrs, audio.generateAudioSessionId(),
            ) ?: return
            // Release the player once the (short) clip finishes; also on error
            // so a failed clip never leaks the native handle.
            player.setOnCompletionListener { it.release() }
            player.setOnErrorListener { mp, _, _ -> mp.release(); true }
            player.start()
        } catch (t: Throwable) {
            Log.w(TAG, "response chime failed", t)
        }
    }
}
