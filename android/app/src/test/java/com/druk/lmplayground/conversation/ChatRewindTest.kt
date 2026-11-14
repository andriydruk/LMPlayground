package com.druk.lmplayground.conversation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatRewindTest {

    private fun user(text: String, ts: Long, imagePath: String? = null) =
        Message("User", text, timestamp = ts, imagePath = imagePath)

    private fun assistant(text: String, ts: Long) = Message("Assistant", text, timestamp = ts)

    // u0 a0 u1 a1 u2 a2
    private val chat = listOf(
        user("first", 1), assistant("r0", 2),
        user("second", 3), assistant("r1", 4),
        user("third", 5), assistant("r2", 6),
    )

    @Test
    fun regenerateResendsThePromptingTurn() {
        val plan = ChatRewind.regenerate(chat, 5)!!
        assertEquals(chat.subList(0, 4), plan.kept)
        assertEquals(chat.subList(4, 6), plan.dropped)
        assertEquals(ChatRewind.EngineRewind(turnIndex = 2, anchorText = "third"), plan.rewind)
        assertEquals("third", plan.resend!!.content)
        // A new message, not the old one re-added.
        assertNotEquals(chat[4].id, plan.resend!!.id)
    }

    @Test
    fun regenerateSkipsOverMessagesBetweenPromptAndReply() {
        // An aborted turn can leave two user messages in a row.
        val messages = listOf(user("a", 1), user("b", 2), assistant("r", 3))
        val plan = ChatRewind.regenerate(messages, 2)!!
        assertEquals(1, plan.rewind!!.turnIndex)
        assertEquals("b", plan.resend!!.content)
    }

    @Test
    fun regenerateNeedsAnAssistantReplyWithAPrompt() {
        assertNull(ChatRewind.regenerate(chat, 2))
        assertNull(ChatRewind.regenerate(chat, 99))
        assertNull(ChatRewind.regenerate(listOf(assistant("hi", 1)), 0))
    }

    @Test
    fun editDropsTheTurnAndEverythingAfter() {
        val plan = ChatRewind.edit(chat, 2, "  changed  ")!!
        assertEquals(chat.subList(0, 2), plan.kept)
        assertEquals(chat.subList(2, 6), plan.dropped)
        // The anchor is the ORIGINAL text — that's what the engine holds.
        assertEquals(ChatRewind.EngineRewind(1, "second"), plan.rewind)
        assertEquals("changed", plan.resend!!.content)
    }

    @Test
    fun editKeepsTheAttachment() {
        val messages = listOf(user("look", 1, imagePath = "/img.jpg"), assistant("a cat", 2))
        val plan = ChatRewind.edit(messages, 0, "")!!
        assertEquals("/img.jpg", plan.resend!!.imagePath)
        assertEquals(ChatRewind.EngineRewind(0, "look"), plan.rewind)
    }

    @Test
    fun editRejectsBlankTextWithoutAnImage() {
        assertNull(ChatRewind.edit(chat, 0, "   "))
        assertNull(ChatRewind.edit(chat, 1, "not a user message"))
    }

    @Test
    fun branchKeepsUpToTheReplyAndRewindsTheNextTurn() {
        val plan = ChatRewind.branch(chat, 3)!!
        assertEquals(chat.subList(0, 4), plan.kept)
        assertEquals(chat.subList(4, 6), plan.dropped)
        assertEquals(ChatRewind.EngineRewind(2, "third"), plan.rewind)
        assertNull(plan.resend)
    }

    @Test
    fun branchFromTheLastReplyNeedsNoRewind() {
        val plan = ChatRewind.branch(chat, 5)!!
        assertEquals(chat, plan.kept)
        assertNull(plan.rewind)
    }

    @Test
    fun branchOnlyFromAssistantReplies() {
        assertNull(ChatRewind.branch(chat, 0))
        assertNull(ChatRewind.branch(chat, -1))
    }
}
