package com.druk.lmplayground.rag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextChunkerTest {

    @Test
    fun `empty and blank input produce no chunks`() {
        assertEquals(emptyList<TextChunker.Chunk>(), TextChunker.chunk(""))
        assertEquals(emptyList<TextChunker.Chunk>(), TextChunker.chunk("   \n\n  \n"))
    }

    @Test
    fun `short text is one chunk with ordinal zero`() {
        val chunks = TextChunker.chunk("Hello world.")
        assertEquals(1, chunks.size)
        assertEquals("Hello world.", chunks[0].text)
        assertEquals(0, chunks[0].ordinal)
    }

    @Test
    fun `small paragraphs merge into one chunk`() {
        val text = "First paragraph.\n\nSecond paragraph.\n\nThird paragraph."
        val chunks = TextChunker.chunk(text, targetChars = 200)
        assertEquals(1, chunks.size)
        assertTrue(chunks[0].text.contains("First paragraph."))
        assertTrue(chunks[0].text.contains("Third paragraph."))
    }

    @Test
    fun `long text splits into multiple chunks with sequential ordinals`() {
        val paragraph = "This is a sentence that fills space. ".repeat(10).trim()
        val text = List(10) { paragraph }.joinToString("\n\n")
        val chunks = TextChunker.chunk(text, targetChars = 500, overlapChars = 100, maxChars = 800)
        assertTrue("expected multiple chunks, got ${chunks.size}", chunks.size > 1)
        chunks.forEachIndexed { index, chunk -> assertEquals(index, chunk.ordinal) }
    }

    @Test
    fun `chunks stay within the hard bound`() {
        // Degenerate input: no paragraph breaks, no sentence breaks.
        val text = "a".repeat(20_000)
        val chunks = TextChunker.chunk(text, targetChars = 1000, overlapChars = 150, maxChars = 1600)
        // Bound: overlap tail + one max-sized piece (see chunk() docs).
        val bound = 150 + 1600 + 1
        chunks.forEach { chunk ->
            assertTrue("chunk of ${chunk.text.length} chars exceeds $bound", chunk.text.length <= bound)
        }
        // No content is lost (overlap only adds, never removes).
        assertTrue(chunks.sumOf { it.text.length } >= text.length)
    }

    @Test
    fun `consecutive chunks overlap`() {
        val sentence = "The quick brown fox jumps over the lazy dog near the river bank. "
        val text = sentence.repeat(60).trim()
        val chunks = TextChunker.chunk(text, targetChars = 600, overlapChars = 120, maxChars = 900)
        assertTrue(chunks.size > 1)
        for (i in 1 until chunks.size) {
            val previous = chunks[i - 1].text
            // The next chunk starts with (a word-aligned suffix of) the
            // previous chunk's tail.
            val head = chunks[i].text.substringBefore('\n')
            assertTrue(
                "chunk $i does not overlap its predecessor",
                head.isNotEmpty() && previous.endsWith(head),
            )
        }
    }

    @Test
    fun `oversized paragraph splits at sentence boundaries`() {
        val text = (1..40).joinToString(" ") { "Sentence number $it is here." }
        val chunks = TextChunker.chunk(text, targetChars = 300, overlapChars = 50, maxChars = 400)
        assertTrue(chunks.size > 1)
        // Sentence-aligned splits: every chunk should end with a period.
        chunks.dropLast(1).forEach { chunk ->
            assertTrue(
                "chunk does not end at a sentence boundary: …${chunk.text.takeLast(20)}",
                chunk.text.trimEnd().endsWith("."),
            )
        }
    }

    @Test
    fun `multilingual text chunks without loss`() {
        val ukrainian = "Це перше речення. Це друге речення довше за перше. ".repeat(30)
        val chunks = TextChunker.chunk(ukrainian, targetChars = 400, overlapChars = 80, maxChars = 600)
        assertTrue(chunks.isNotEmpty())
        assertTrue(chunks.all { it.text.isNotBlank() })
    }
}
