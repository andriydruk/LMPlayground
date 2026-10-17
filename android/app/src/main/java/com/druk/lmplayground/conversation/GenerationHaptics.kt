package com.druk.lmplayground.conversation

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings

/**
 * Tiny per-token haptic "tick" that gives the streaming response a
 * typewriter-like feel on the conversation screen. Fire-and-forget — the
 * caller throttles the rate and only ticks while the chat is on-screen
 * (the foreground gate also satisfies the OS rule that bars background
 * apps from vibrating).
 */
object GenerationHaptics {

    /**
     * Strength of each token tick, 0f (off) .. 1f (full). Tuned low for a
     * subtle typewriter feel. Only honored on devices with composition or
     * amplitude support; otherwise the fixed system tick is used.
     */
    const val DEFAULT_STRENGTH = 0.4f

    /** Honors the system-wide "touch/haptic feedback" toggle. */
    fun isSystemHapticsEnabled(context: Context): Boolean = try {
        Settings.System.getInt(
            context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1,
        ) == 1
    } catch (_: Throwable) {
        true
    }

    fun tick(context: Context, strength: Float = DEFAULT_STRENGTH) {
        val vibrator = vibrator(context) ?: return
        if (!vibrator.hasVibrator()) return
        val scale = strength.coerceIn(0f, 1f)
        if (scale <= 0f) return
        try {
            when {
                // Best: a TICK primitive whose intensity we scale directly.
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                    vibrator.areAllPrimitivesSupported(
                        VibrationEffect.Composition.PRIMITIVE_TICK,
                    ) -> {
                    val effect = VibrationEffect.startComposition()
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, scale)
                        .compose()
                    vibrator.vibrate(effect)
                }
                // Fallback: a short one-shot whose amplitude we scale (1..255).
                vibrator.hasAmplitudeControl() -> {
                    val amplitude = (scale * 255f).toInt().coerceIn(1, 255)
                    vibrator.vibrate(VibrationEffect.createOneShot(10L, amplitude))
                }
                // Last resort: fixed system tick (strength not adjustable).
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
                    vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
                }
                else -> {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(8L)
                }
            }
        } catch (_: Throwable) {
            // Vibration is a nicety; never let it break generation.
        }
    }

    private fun vibrator(context: Context): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
                ?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
}
