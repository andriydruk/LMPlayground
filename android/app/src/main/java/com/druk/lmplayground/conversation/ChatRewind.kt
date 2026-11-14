package com.druk.lmplayground.conversation

/**
 * Pure planning for edit, regenerate and branch: which messages survive,
 * which user turn the native session rewinds to, and what gets re-sent.
 *
 * Turn numbers count "User" messages from the start of the chat. That is the
 * numbering [com.druk.llamacpp.GenerationSession.rewindToTurn] uses, and the
 * turn's original text rides along as an anchor so a drifted numbering (a turn
 * the engine never received) is refused natively rather than rewinding the
 * wrong turn — the caller then rebuilds the session from [Plan.kept].
 */
object ChatRewind {

    /** Rewind the engine to user turn [turnIndex], anchored on [anchorText]. */
    data class EngineRewind(val turnIndex: Int, val anchorText: String)

    data class Plan(
        /** Messages that stay, in order. */
        val kept: List<Message>,
        /** Messages removed from the chat (and from storage). */
        val dropped: List<Message>,
        /** Engine rewind needed, or null when the engine already matches [kept]. */
        val rewind: EngineRewind?,
        /** User message to send after the rewind, or null (branch). */
        val resend: Message?,
    )

    private fun turnIndexAt(messages: List<Message>, index: Int): Int =
        messages.subList(0, index).count { it.author == AUTHOR_USER }

    /**
     * Regenerate the assistant reply at [assistantIndex]: drop it and
     * everything after, re-send the user message that prompted it.
     */
    fun regenerate(messages: List<Message>, assistantIndex: Int): Plan? {
        if (messages.getOrNull(assistantIndex)?.author != AUTHOR_ASSISTANT) return null
        val userIndex = (assistantIndex - 1 downTo 0).firstOrNull {
            messages[it].author == AUTHOR_USER
        } ?: return null
        return resendFrom(messages, userIndex, messages[userIndex].content)
    }

    /** Replace the user message at [userIndex] with [newText] and re-send it. */
    fun edit(messages: List<Message>, userIndex: Int, newText: String): Plan? {
        if (messages.getOrNull(userIndex)?.author != AUTHOR_USER) return null
        val text = newText.trim()
        val hasImage = messages[userIndex].imageUri != null || messages[userIndex].imagePath != null
        if (text.isEmpty() && !hasImage) return null
        return resendFrom(messages, userIndex, text)
    }

    private fun resendFrom(messages: List<Message>, userIndex: Int, text: String): Plan {
        val original = messages[userIndex]
        return Plan(
            kept = messages.subList(0, userIndex).toList(),
            dropped = messages.subList(userIndex, messages.size).toList(),
            rewind = EngineRewind(turnIndexAt(messages, userIndex), original.content),
            // A fresh message (new id/timestamp, no stats) that keeps the
            // original attachment.
            resend = Message(
                author = AUTHOR_USER,
                content = text,
                imageUri = original.imageUri,
                imagePath = original.imagePath,
            ),
        )
    }

    /**
     * Branch the chat at the assistant reply at [index]: a new chat holding
     * everything up to and including it. The engine drops the turns after it
     * (if any), so the branch continues on the cache it already has.
     */
    fun branch(messages: List<Message>, index: Int): Plan? {
        if (messages.getOrNull(index)?.author != AUTHOR_ASSISTANT) return null
        val kept = messages.subList(0, index + 1).toList()
        val dropped = messages.subList(index + 1, messages.size).toList()
        val nextUser = (index + 1 until messages.size).firstOrNull {
            messages[it].author == AUTHOR_USER
        }
        val rewind = nextUser?.let { EngineRewind(turnIndexAt(messages, it), messages[it].content) }
        return Plan(kept, dropped, rewind, resend = null)
    }

    private const val AUTHOR_USER = "User"
    private const val AUTHOR_ASSISTANT = "Assistant"
}
