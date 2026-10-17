package com.druk.lmplayground.conversation

import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.toMutableStateList
import java.util.concurrent.atomic.AtomicLong

class ConversationUiState(
    initialMessages: List<Message>
) {
    private val _messages: MutableList<Message> = initialMessages.toMutableStateList()
    val messages: List<Message> = _messages

    fun addMessage(msg: Message) {
        _messages.add(msg) // Add to the end of the list
    }

    fun markThinkingStarted() {
        if (_messages.isEmpty()) return
        val message = _messages.last()
        if (message.thinkingStartTimeMs == 0L) {
            _messages[_messages.size - 1] = message.copy(
                thinkingStartTimeMs = System.currentTimeMillis()
            )
        }
    }

    fun updateLastMessage(msg: String, thinkingTokens: Int = 0, responseTokens: Int = 0) {
        if (_messages.isEmpty()) return
        val message = _messages.last()
        val isThinkingActive = message.thinkingStartTimeMs > 0
        val thinkingJustEnded = isThinkingActive && msg.contains("</think>")

        val duration = if (isThinkingActive) {
            ((System.currentTimeMillis() - message.thinkingStartTimeMs) / 1000).toInt()
        } else {
            message.thinkingDurationSeconds
        }

        val responseDuration = if (message.responseStartTimeMs > 0) {
            (System.currentTimeMillis() - message.responseStartTimeMs) / 1000f
        } else {
            message.responseDurationSeconds
        }

        _messages[_messages.size - 1] = message.copy(
            content = msg,
            thinkingDurationSeconds = duration,
            thinkingStartTimeMs = if (thinkingJustEnded) 0L else message.thinkingStartTimeMs,
            thinkingTokens = thinkingTokens,
            responseTokens = responseTokens,
            responseDurationSeconds = responseDuration
        )
    }

    fun addToolCallsToLastMessage(calls: List<ToolCallInfo>) {
        if (_messages.isEmpty() || calls.isEmpty()) return
        val message = _messages.last()
        // Attach this round's thinking (everything streamed into `content`
        // before the tool call) to the first call of the round, so the renderer
        // can show think → call → think → call in order. Then clear `content`
        // and the thinking timer for the next round.
        val enriched = calls.toMutableList()
        enriched[0] = enriched[0].copy(
            precedingThinking = message.content,
            precedingThinkingDurationSeconds = message.thinkingDurationSeconds,
            precedingThinkingTokens = message.thinkingTokens
        )
        _messages[_messages.size - 1] = message.copy(
            content = "",
            thinkingDurationSeconds = 0,
            thinkingStartTimeMs = 0,
            thinkingTokens = 0,
            toolCalls = (message.toolCalls.orEmpty()) + enriched,
            responseStartTimeMs = System.currentTimeMillis()
        )
    }

    fun finalizeLastMessage() {
        if (_messages.isEmpty()) return
        val message = _messages.last()
        if (message.responseStartTimeMs > 0) {
            _messages[_messages.size - 1] = message.copy(
                responseDurationSeconds = (System.currentTimeMillis() - message.responseStartTimeMs) / 1000f,
                responseStartTimeMs = 0L
            )
        }
    }

    fun setMessages(messages: List<Message>) {
        Snapshot.withMutableSnapshot {
            _messages.clear()
            _messages.addAll(messages)
        }
    }

    fun resetMessages() {
        _messages.clear()
    }

    fun removeLastMessage() {
        if (_messages.isNotEmpty()) {
            _messages.removeAt(_messages.size - 1)
        }
    }
}

private val messageIdCounter = AtomicLong(0)

@Immutable
data class ToolCallInfo(
    val name: String,
    val arguments: String,
    val result: String,
    val durationMs: Long = 0,
    // The thinking/content the model produced in THIS round, before emitting
    // this tool call. Carried per-call so the UI can render
    // think → call → think → call in chronological order across multiple
    // rounds. Only the first call of a round carries it (parallel calls in the
    // same round share the one preceding-thinking block).
    val precedingThinking: String = "",
    val precedingThinkingDurationSeconds: Int = 0,
    val precedingThinkingTokens: Int = 0
)

@Immutable
data class Message(
    val author: String,
    val content: String,
    val image: Int? = null,
    val imageUri: Uri? = null,
    val thinkingDurationSeconds: Int = 0,
    val thinkingStartTimeMs: Long = 0,
    val thinkingTokens: Int = 0,
    val responseTokens: Int = 0,
    val responseStartTimeMs: Long = 0,
    val responseDurationSeconds: Float = 0f,
    val timestamp: Long = System.currentTimeMillis(),
    val id: Long = messageIdCounter.incrementAndGet(),
    val toolCalls: List<ToolCallInfo>? = null
)
