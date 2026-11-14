package com.druk.lmplayground.conversation

import com.druk.llamacpp.InferenceLimits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryReplayTest {

    private fun user(content: String) = Message(author = "User", content = content)
    private fun assistant(content: String) = Message(author = "Assistant", content = content)

    // -- pairTurns --

    @Test
    fun pairsAlternatingTurns() {
        val (users, assistants) = HistoryReplay.pairTurns(
            listOf(user("q1"), assistant("a1"), user("q2"), assistant("a2"))
        )
        assertEquals(listOf("q1", "q2"), users)
        assertEquals(listOf("a1", "a2"), assistants)
    }

    @Test
    fun skipsTrailingUnansweredUserMessage() {
        val (users, assistants) = HistoryReplay.pairTurns(
            listOf(user("q1"), assistant("a1"), user("unanswered"))
        )
        assertEquals(listOf("q1"), users)
        assertEquals(listOf("a1"), assistants)
    }

    @Test
    fun skipsLeadingAssistantMessage() {
        val (users, assistants) = HistoryReplay.pairTurns(
            listOf(assistant("greeting"), user("q1"), assistant("a1"))
        )
        assertEquals(listOf("q1"), users)
        assertEquals(listOf("a1"), assistants)
    }

    @Test
    fun skipsFirstOfConsecutiveUserMessages() {
        val (users, assistants) = HistoryReplay.pairTurns(
            listOf(user("dropped"), user("q1"), assistant("a1"))
        )
        assertEquals(listOf("q1"), users)
        assertEquals(listOf("a1"), assistants)
    }

    @Test
    fun emptyHistoryYieldsNoTurns() {
        val (users, assistants) = HistoryReplay.pairTurns(emptyList())
        assertTrue(users.isEmpty())
        assertTrue(assistants.isEmpty())
    }

    // -- validateReplaySize --

    @Test
    fun acceptsPayloadsWithinBudget() {
        val result = HistoryReplay.validateReplaySize(
            "prompt", listOf(user("q"), assistant("a"))
        )
        assertEquals(HistoryReplay.ValidationResult.Ok, result)
    }

    @Test
    fun acceptsSystemPromptExactlyAtLimit() {
        val prompt = "x".repeat(InferenceLimits.MAX_PAYLOAD_BYTES / 2)
        val result = HistoryReplay.validateReplaySize(prompt, emptyList())
        assertEquals(HistoryReplay.ValidationResult.Ok, result)
    }

    @Test
    fun rejectsSystemPromptOverLimit() {
        val prompt = "x".repeat(InferenceLimits.MAX_PAYLOAD_BYTES / 2 + 1)
        val result = HistoryReplay.validateReplaySize(prompt, emptyList())
        assertTrue(result is HistoryReplay.ValidationResult.SystemPromptTooLarge)
        result as HistoryReplay.ValidationResult.SystemPromptTooLarge
        assertEquals(InferenceLimits.MAX_PAYLOAD_BYTES + 2, result.promptBytes)
        assertEquals(InferenceLimits.MAX_PAYLOAD_BYTES, result.maxBytes)
    }

    @Test
    fun rejectsOversizedMessage() {
        val big = user("x".repeat(InferenceLimits.MAX_PAYLOAD_BYTES / 2 + 1))
        val result = HistoryReplay.validateReplaySize("prompt", listOf(big))
        assertEquals(HistoryReplay.ValidationResult.MessageTooLarge, result)
    }
}
