package com.druk.lmplayground.conversation

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.druk.lmplayground.R

@Composable
fun ChatItemBubble(
    message: Message,
    showActions: Boolean = true,
    onTokenCountClicked: (() -> Unit)? = null
) {
    var showRatingSheet by remember { mutableStateOf(false) }
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    val hasToolCalls = !message.toolCalls.isNullOrEmpty()
    val isWaitingForResponse = !showActions
        && message.content.isEmpty()
        && !hasToolCalls
    val isGenerating = !showActions

    Column {
        if (isWaitingForResponse) {
            ThinkingIndicator()
        } else {
            // Tool rounds: render each round's thinking immediately before its
            // tool call(s), in chronological order.
            if (hasToolCalls) {
                val thinkingText = stringResource(R.string.thinking)
                val inputLabel = stringResource(R.string.tool_call_input)
                val outputLabel = stringResource(R.string.tool_call_output)
                for (toolCall in message.toolCalls!!) {
                    // This round's thinking, rendered immediately before its
                    // tool call so multi-step turns read in chronological order.
                    if (toolCall.precedingThinking.isNotEmpty()) {
                        val pre = remember(toolCall.precedingThinking) {
                            splitThinking(toolCall.precedingThinking)
                        }
                        if (pre.thinkingContent.isNotEmpty()) {
                            CollapsibleSection(
                                label = buildString {
                                    append("$thinkingText \u00B7 ${formatDuration(toolCall.precedingThinkingDurationSeconds)}")
                                    if (toolCall.precedingThinkingTokens > 0) {
                                        append(" \u00B7 ${toolCall.precedingThinkingTokens} tokens")
                                    }
                                },
                                content = pre.thinkingContent
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                        }
                    }
                    val durationSec = (toolCall.durationMs / 1000).toInt().coerceAtLeast(
                        if (toolCall.durationMs > 0) 1 else 0
                    )
                    CollapsibleSection(
                        label = "${toolDisplayName(toolCall.name)} \u00B7 ${formatDuration(durationSec)}",
                        content = buildString {
                            if (toolCall.arguments.isNotBlank()) {
                                append(inputLabel).append('\n')
                                append(prettyJson(toolCall.arguments))
                                append("\n\n")
                            }
                            append(outputLabel).append('\n')
                            append(prettyJson(toolCall.result))
                        }
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                }
            }

            // ③ If still generating after tool calls, show thinking indicator
            if (isGenerating && hasToolCalls && message.content.isEmpty()) {
                ThinkingIndicator()
            }

            // ④ Post-tool thinking + ⑤ Response (from content)
            val split = remember(message.content) { splitThinking(message.content) }
            val hasThinking = split.thinkingContent.isNotEmpty()

            if (hasThinking) {
                val thinkingText = stringResource(R.string.thinking)
                CollapsibleSection(
                    label = buildString {
                        append("$thinkingText \u00B7 ${formatDuration(message.thinkingDurationSeconds)}")
                        if (message.thinkingTokens > 0) {
                            append(" \u00B7 ${message.thinkingTokens} tokens")
                        }
                    },
                    content = split.thinkingContent
                )
                if (split.responseContent.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }

            if (split.responseContent.isNotEmpty()) {
                val styledMessage = messageFormatter(
                    text = split.responseContent,
                    primary = false
                )
                SelectionContainer {
                    Text(
                        text = styledMessage,
                        style = MaterialTheme.typography.bodyLarge.copy(color = LocalContentColor.current)
                    )
                }
            }
        }

        message.image?.let {
            Spacer(modifier = Modifier.height(4.dp))
            Image(
                painter = painterResource(it),
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(160.dp),
                contentDescription = stringResource(id = R.string.attached_image)
            )
        }

        message.imageUri?.let { uri ->
            Spacer(modifier = Modifier.height(4.dp))
            coil.compose.AsyncImage(
                model = uri,
                contentDescription = stringResource(id = R.string.attached_image),
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(160.dp)
            )
        }

        if (showActions) {
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(0.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val shareLabel = stringResource(id = R.string.share)
                IconButton(
                    onClick = {
                        val sendIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, stripThinkTags(message.content))
                        }
                        context.startActivity(Intent.createChooser(sendIntent, shareLabel))
                    },
                    modifier = Modifier.size(38.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Share,
                        contentDescription = shareLabel,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.secondary
                    )
                }
                IconButton(
                    onClick = {
                        clipboardManager.setText(AnnotatedString(stripThinkTags(message.content)))
                    },
                    modifier = Modifier.size(38.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ContentCopy,
                        contentDescription = stringResource(id = R.string.copy),
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.secondary
                    )
                }
                IconButton(
                    onClick = { /* upvote - no-op */ },
                    modifier = Modifier.size(38.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ThumbUp,
                        contentDescription = stringResource(id = R.string.upvote),
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.secondary
                    )
                }
                IconButton(
                    onClick = { showRatingSheet = true },
                    modifier = Modifier.size(38.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ThumbDown,
                        contentDescription = stringResource(id = R.string.downvote),
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.secondary
                    )
                }
                if (message.responseTokens + message.thinkingTokens > 0) {
                    Text(
                        text = formatResponseStats(message),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .then(
                                if (onTokenCountClicked != null) {
                                    Modifier.clickable(onClick = onTokenCountClicked)
                                } else {
                                    Modifier
                                }
                            )
                    )
                }
            }
        }
    }

    if (showRatingSheet) {
        RatingBottomSheet(onDismiss = { showRatingSheet = false })
    }
}

/**
 * Reusable collapsible section with arrow icon, label, and expandable content.
 * Used for both thinking blocks and tool call results.
 */
@Composable
private fun CollapsibleSection(
    label: String,
    content: String
) {
    var expanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .clickable { expanded = !expanded }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (expanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
            contentDescription = if (expanded) stringResource(R.string.collapse_thinking) else stringResource(R.string.expand_thinking),
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.outline
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(start = 4.dp)
        )
    }
    AnimatedVisibility(visible = expanded) {
        Surface {
            SelectionContainer {
                Text(
                    text = content,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontStyle = FontStyle.Italic,
                        color = MaterialTheme.colorScheme.outline
                    )
                )
            }
        }
    }
}

private fun formatResponseStats(message: Message): String {
    val totalTokens = message.responseTokens + message.thinkingTokens
    return buildString {
        append("$totalTokens tokens")
        val duration = message.responseDurationSeconds
        if (duration > 0f) {
            append(" \u00B7 ${formatDuration(duration.toInt())}")
            val speed = totalTokens / duration
            append(" \u00B7 ${"%.1f".format(speed)} tok/s")
        }
    }
}

private fun formatDuration(seconds: Int): String {
    return if (seconds < 60) {
        "${seconds}s"
    } else {
        val m = seconds / 60
        val s = seconds % 60
        "${m}m ${s}s"
    }
}

/** Friendly display name for a tool (falls back to the raw name for unknowns). */
@Composable
private fun toolDisplayName(name: String): String = when (name) {
    "run_javascript" -> stringResource(R.string.tool_run_javascript_title)
    "web_search" -> stringResource(R.string.tool_web_search_title)
    "web_fetch" -> stringResource(R.string.tool_web_fetch_title)
    else -> name
}

/** Pretty-print a JSON object/array for the tool input/output view; raw on failure. */
private fun prettyJson(raw: String): String {
    val trimmed = raw.trim()
    return try {
        when {
            trimmed.startsWith("{") -> org.json.JSONObject(trimmed).toString(2)
            trimmed.startsWith("[") -> org.json.JSONArray(trimmed).toString(2)
            else -> raw
        }
    } catch (_: Exception) {
        raw
    }
}

@Composable
private fun ThinkingIndicator() {
    val transition = rememberInfiniteTransition(label = "thinking")
    val dotCount by transition.animateFloat(
        initialValue = 0f,
        targetValue = 4f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "dots"
    )
    val dots = ".".repeat(dotCount.toInt().coerceIn(0, 3))
    val thinkingText = stringResource(R.string.thinking)
    Text(
        text = "$thinkingText$dots",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.outline,
        fontStyle = FontStyle.Italic
    )
}
