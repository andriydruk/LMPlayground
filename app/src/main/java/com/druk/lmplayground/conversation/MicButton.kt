package com.druk.lmplayground.conversation

import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.druk.lmplayground.R
import com.druk.lmplayground.dictation.DictationEarcon
import kotlinx.coroutines.flow.StateFlow

/** The listening ring: a closed loop of hues, so the sweep has no seam. */
private val RING_COLORS = listOf(
    Color(0xFF4F8CFF),
    Color(0xFFB36BFF),
    Color(0xFFFF6B9A),
    Color(0xFFFFB547),
    Color(0xFF3DDC97),
    Color(0xFF4F8CFF),
)

/**
 * The dictation toggle, in three states:
 *
 *  - off: a plain microphone;
 *  - on, starting (model check, model load): the microphone on a soft disc,
 *    so the tap visibly registered;
 *  - recording: a slowly turning colour ring appears around the disc, with a
 *    short haptic tick, and breathes with the voice — thicker and wider as the
 *    speaker gets louder — so the user can see it hears them before any words
 *    arrive.
 *
 * The tick waits for [recording] (the first audio from the microphone), not
 * for the tap: loading the model can take a moment, and speaking into that
 * gap would be lost.
 *
 * [level] is 0..1, updated ~16 times a second while recording. It is collected
 * here, not in the caller, so only this button recomposes at that rate.
 */
@Composable
internal fun MicButton(
    micOn: Boolean,
    enabled: Boolean,
    downloadProgress: Float?,
    recording: StateFlow<Boolean>,
    level: StateFlow<Float>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isRecording by recording.collectAsState()
    val current by level.collectAsState()
    val listening = micOn && isRecording

    val disc by animateFloatAsState(if (micOn) 1f else 0f, label = "micDisc")
    val ring by animateFloatAsState(
        targetValue = if (listening) 1f else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "micRing",
    )
    val voice by animateFloatAsState(
        targetValue = if (listening) current else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "micVoice",
    )
    // The "you're being recorded" cue: a tick and a rising tone when audio
    // starts, a falling tone when it stops — never on first composition.
    val view = LocalView.current
    var wasListening by remember { mutableStateOf(false) }
    LaunchedEffect(listening) {
        if (listening) {
            view.performHapticFeedback(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM
                else HapticFeedbackConstants.VIRTUAL_KEY
            )
            DictationEarcon.playStart()
        } else if (wasListening) {
            DictationEarcon.playStop()
        }
        wasListening = listening
    }

    val discColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    val description = stringResource(
        if (micOn) R.string.dictation_stop else R.string.dictation_start
    )

    Box(
        modifier = modifier
            .size(48.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                // Round, and not clipped: clipping the button would also cut
                // off the ring, which overhangs the 40dp circle.
                indication = ripple(bounded = false, radius = 22.dp),
                enabled = enabled,
                role = Role.Button,
                onClickLabel = description,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(40.dp)) {
            val outer = size.minDimension / 2
            if (disc > 0f) {
                drawCircle(color = discColor.copy(alpha = discColor.alpha * disc), radius = outer - 2.dp.toPx())
            }
        }
        // Composed only while visible: its rotation is an endless animation,
        // and an idle button should not redraw every frame.
        if (ring > 0f) ListeningRing(alpha = ring, voice = voice)
        // The model is ~700 MB, so the wait needs to be visible where the
        // user asked for it rather than only on the models screen.
        if (downloadProgress != null) {
            if (downloadProgress >= 0f) {
                CircularProgressIndicator(
                    progress = { downloadProgress },
                    modifier = Modifier.size(44.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                // Queued with no byte count yet (waiting for network).
                CircularProgressIndicator(modifier = Modifier.size(44.dp), strokeWidth = 2.dp)
            }
        }
        Icon(
            imageVector = Icons.Filled.Mic,
            contentDescription = description,
            modifier = if (!enabled) Modifier.alpha(0.8f) else Modifier,
            tint = LocalContentColor.current,
        )
    }
}

/**
 * The turning colour ring around a recording mic. Breathes with [voice]
 * (0..1): thicker and wider as the speaker gets louder. May overhang its 40dp
 * canvas, which is fine — the 48dp touch target around it never moves.
 */
@Composable
private fun ListeningRing(alpha: Float, voice: Float) {
    val spin by rememberInfiniteTransition(label = "micSpin").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(3200, easing = LinearEasing), RepeatMode.Restart),
        label = "micSpinAngle",
    )
    Canvas(modifier = Modifier.size(40.dp)) {
        val outer = size.minDimension / 2
        val width = (2.dp + 2.5.dp * voice).toPx()
        val radius = outer - 1.dp.toPx() + 3.dp.toPx() * voice
        rotate(spin) {
            drawCircle(
                brush = Brush.sweepGradient(RING_COLORS, center),
                radius = radius,
                style = Stroke(width = width),
                alpha = alpha,
            )
        }
    }
}
