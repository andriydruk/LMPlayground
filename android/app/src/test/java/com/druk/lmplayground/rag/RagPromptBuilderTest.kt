package com.druk.lmplayground.rag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RagPromptBuilderTest {

    private fun chunk(name: String, text: String, score: Float) =
        RagRepository.RetrievedChunk(documentName = name, text = text, score = score)

    @Test
    fun `no chunks returns the user text unchanged`() {
        assertEquals("hi", RagPromptBuilder.build("hi", emptyList(), 8000))
    }

    @Test
    fun `zero budget returns the user text unchanged`() {
        val chunks = listOf(chunk("a.pdf", "content", 0.9f))
        assertEquals("hi", RagPromptBuilder.build("hi", chunks, 0))
    }

    @Test
    fun `wraps excerpts with sources and keeps the question last`() {
        val chunks = listOf(
            chunk("report.pdf", "Revenue grew 12% in Q3.", 0.9f),
            chunk("notes.md", "The team shipped v2.", 0.8f),
        )
        val result = RagPromptBuilder.build("How did Q3 go?", chunks, 8000)
        assertTrue(result.contains("[Source: report.pdf — excerpt 1]"))
        assertTrue(result.contains("Revenue grew 12% in Q3."))
        assertTrue(result.contains("[Source: notes.md — excerpt 2]"))
        assertTrue(result.endsWith("Question: How did Q3 go?"))
        // Excerpts are ordered by score (input order preserved).
        assertTrue(
            result.indexOf("Revenue grew") < result.indexOf("The team shipped")
        )
    }

    @Test
    fun `budget drops lowest-scoring chunks`() {
        val big = "x".repeat(400)
        val chunks = listOf(
            chunk("a.txt", big, 0.9f),
            chunk("b.txt", big, 0.8f),
            chunk("c.txt", "LAST_CHUNK", 0.7f),
        )
        // Budget fits only the first chunk (+ header).
        val result = RagPromptBuilder.build("q", chunks, 500)
        assertTrue(result.contains("excerpt 1"))
        assertFalse(result.contains("excerpt 2"))
        assertFalse(result.contains("LAST_CHUNK"))
    }

    @Test
    fun `nothing fits returns the user text unchanged`() {
        val chunks = listOf(chunk("a.txt", "x".repeat(400), 0.9f))
        assertEquals("q", RagPromptBuilder.build("q", chunks, 100))
    }

    @Test
    fun `budget helper caps at 2048 tokens worth`() {
        assertEquals(4096, RagPromptBuilder.budgetChars(4096))
        assertEquals(8192, RagPromptBuilder.budgetChars(32768))
    }
}
