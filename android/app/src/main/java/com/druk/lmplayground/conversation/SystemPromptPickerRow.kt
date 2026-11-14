package com.druk.lmplayground.conversation

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.druk.lmplayground.data.SystemPromptEntity
import kotlinx.coroutines.delay

/**
 * Most-recently-used system prompts shown at the bottom of the chat while the
 * chat is empty and no prompt is active.
 *
 * Tap behavior is sequenced so the user's focus stays on the picked card:
 *   1. Fade   — every OTHER card fades out in place. The tapped card doesn't
 *               move and doesn't change opacity.
 *   2. Flight — after the others have disappeared the tapped card translates
 *               downward into the composer area.
 * When the total animation finishes we invoke [onPick], which causes the
 * parent to hide the row (the AnimatedVisibility wrapper on the caller side
 * uses `ExitTransition.None` so the hand-off is seamless).
 */
@Composable
fun SystemPromptPickerRow(
    prompts: List<SystemPromptEntity>,
    selectedText: String,
    onPick: (SystemPromptEntity) -> Unit,
    modifier: Modifier = Modifier
) {
    if (prompts.isEmpty()) return

    var flyingId by remember { mutableStateOf<String?>(null) }
    val density = LocalDensity.current
    val flightDistancePx = with(density) { 140.dp.toPx() }

    // After flight + fade, notify the caller. Reset the local state on the way
    // out so the row is safe to recompose if it ever reappears.
    LaunchedEffect(flyingId) {
        val id = flyingId ?: return@LaunchedEffect
        val prompt = prompts.find { it.id == id } ?: run {
            flyingId = null
            return@LaunchedEffect
        }
        delay((FADE_DURATION_MS + FLY_DURATION_MS).toLong())
        onPick(prompt)
        flyingId = null
    }

    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(prompts, key = { it.id }) { prompt ->
            val isFlying = prompt.id == flyingId
            val someoneElseIsFlying = flyingId != null && !isFlying

            // Phase 1: OTHER cards fade. The tapped card keeps alpha = 1.
            val alpha by animateFloatAsState(
                targetValue = if (someoneElseIsFlying) 0f else 1f,
                animationSpec = tween(durationMillis = FADE_DURATION_MS),
                label = "promptFlyAlpha"
            )

            // Phase 2: the tapped card translates Y after the fade finishes.
            val translationY by animateFloatAsState(
                targetValue = if (isFlying) flightDistancePx else 0f,
                animationSpec = tween(
                    durationMillis = FLY_DURATION_MS,
                    delayMillis = FADE_DURATION_MS,
                    easing = FastOutSlowInEasing
                ),
                label = "promptFlyY"
            )

            PromptCard(
                prompt = prompt,
                selected = prompt.text == selectedText && selectedText.isNotEmpty(),
                onClick = {
                    // Ignore taps while another card is already flying.
                    if (flyingId == null) flyingId = prompt.id
                },
                modifier = Modifier.graphicsLayer {
                    this.translationY = translationY
                    this.alpha = alpha
                }
            )
        }
    }
}

private const val FLY_DURATION_MS = 280
private const val FADE_DURATION_MS = 160

@Composable
private fun PromptCard(
    prompt: SystemPromptEntity,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val border: BorderStroke = if (selected) {
        BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
    } else {
        CardDefaults.outlinedCardBorder()
    }
    OutlinedCard(
        modifier = modifier
            .width(140.dp)
            .clickable(onClick = onClick),
        border = border,
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Text(
                text = prompt.text,
                style = MaterialTheme.typography.bodySmall,
                minLines = 3,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
