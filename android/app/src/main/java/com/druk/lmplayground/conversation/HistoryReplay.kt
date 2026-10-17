package com.druk.lmplayground.conversation

import com.druk.llamacpp.InferenceLimits
import com.druk.llamacpp.LlamaGenerationSession

/**
 * Pure history-replay helpers: pre-flight validation of persisted payloads
 * against the AIDL transaction cap, and pairing of persisted messages into
 * (user, assistant) turns for [LlamaGenerationSession.replayHistory].
 */
object HistoryReplay {

    /** Result of [validateReplaySize]; the caller maps failures to UI strings. */
    sealed class ValidationResult {
        object Ok : ValidationResult()
        data class SystemPromptTooLarge(
            val promptBytes: Int,
            val maxBytes: Int,
        ) : ValidationResult()
        object MessageTooLarge : ValidationResult()
    }

    /**
     * Pre-flight [systemPrompt] and [messages] against the binder payload cap
     * BEFORE any session state is mutated. Recreating a session destroys the
     * old one, and then throwing inside `createSession` / `replayHistory`
     * leaves the UI with a ready model but no live session, and the next
     * Send breaks.
     */
    fun validateReplaySize(systemPrompt: String, messages: List<Message>): ValidationResult {
        val promptBytes = systemPrompt.length * 2
        if (promptBytes > InferenceLimits.MAX_PAYLOAD_BYTES) {
            return ValidationResult.SystemPromptTooLarge(
                promptBytes, InferenceLimits.MAX_PAYLOAD_BYTES
            )
        }
        for (msg in messages) {
            if (msg.content.length * 2 > InferenceLimits.MAX_PAYLOAD_BYTES) {
                return ValidationResult.MessageTooLarge
            }
        }
        return ValidationResult.Ok
    }

    /**
     * Pair [messages] into consecutive (User, Assistant) turns. Unpaired
     * messages (a trailing unanswered user message, a leading assistant
     * message) are skipped — only complete turns feed the KV cache.
     * Returns parallel (user contents, assistant contents) lists.
     */
    fun pairTurns(messages: List<Message>): Pair<List<String>, List<String>> {
        val userMessages = mutableListOf<String>()
        val assistantMessages = mutableListOf<String>()

        var i = 0
        while (i < messages.size) {
            val msg = messages[i]
            if (msg.author == "User" && i + 1 < messages.size && messages[i + 1].author == "Assistant") {
                userMessages.add(msg.content)
                assistantMessages.add(messages[i + 1].content)
                i += 2
            } else {
                i++
            }
        }
        return userMessages to assistantMessages
    }

    /** Replay the paired turns of [messages] into [session]. */
    fun replayToSession(session: LlamaGenerationSession, messages: List<Message>) {
        val (userMessages, assistantMessages) = pairTurns(messages)
        if (userMessages.isNotEmpty()) {
            session.replayHistory(
                userMessages.toTypedArray(),
                assistantMessages.toTypedArray()
            )
        }
    }
}
