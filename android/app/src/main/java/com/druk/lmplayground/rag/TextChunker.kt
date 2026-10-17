package com.druk.lmplayground.rag

import java.text.BreakIterator

/**
 * Splits extracted document text into overlapping chunks sized for the
 * embedding model. Paragraph-aware first (blank-line boundaries), then
 * sentence-aware via [BreakIterator] (multilingual) for oversized
 * paragraphs, with a hard character cap as the last resort.
 *
 * Defaults: ~1000 chars ≈ 250 tokens per chunk — comfortably inside
 * EmbeddingGemma's 2048-token context even after the task prefix.
 */
object TextChunker {

    data class Chunk(val text: String, val ordinal: Int)

    private val PARAGRAPH_SPLIT = Regex("\\n{2,}")

    /**
     * @param targetChars soft chunk size — a chunk closes once appending
     *   the next piece would push it past this.
     * @param overlapChars tail of each chunk repeated at the start of the
     *   next one, so sentences straddling a boundary stay retrievable.
     * @param maxChars hard cap for a single piece (splits mid-sentence
     *   beyond it); a chunk tops out around [overlapChars] + [maxChars].
     */
    fun chunk(
        text: String,
        targetChars: Int = 1000,
        overlapChars: Int = 150,
        maxChars: Int = 1600,
    ): List<Chunk> {
        require(targetChars > 0 && maxChars >= targetChars && overlapChars < targetChars)

        val pieces = text.split(PARAGRAPH_SPLIT)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .flatMap { paragraph -> splitOversized(paragraph, maxChars) }
        if (pieces.isEmpty()) return emptyList()

        val chunks = mutableListOf<String>()
        val current = StringBuilder()
        for (piece in pieces) {
            if (current.isNotEmpty() && current.length + piece.length + 1 > targetChars) {
                chunks.add(current.toString())
                val tail = overlapTail(current.toString(), overlapChars)
                current.setLength(0)
                if (tail.isNotEmpty()) current.append(tail)
            }
            if (current.isNotEmpty()) current.append('\n')
            current.append(piece)
        }
        if (current.isNotBlank()) chunks.add(current.toString())

        return chunks.mapIndexed { index, chunkText -> Chunk(chunkText, index) }
    }

    /** Split a paragraph longer than [maxChars] at sentence boundaries. */
    private fun splitOversized(paragraph: String, maxChars: Int): List<String> {
        if (paragraph.length <= maxChars) return listOf(paragraph)

        val sentences = mutableListOf<String>()
        val iterator = BreakIterator.getSentenceInstance()
        iterator.setText(paragraph)
        var start = iterator.first()
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            val sentence = paragraph.substring(start, end).trim()
            if (sentence.isNotEmpty()) {
                if (sentence.length > maxChars) {
                    // Degenerate input (no sentence breaks) — hard split.
                    sentences += sentence.chunked(maxChars)
                } else {
                    sentences.add(sentence)
                }
            }
            start = end
            end = iterator.next()
        }

        // Re-merge sentences up to maxChars so pieces stay large enough
        // to carry context.
        val pieces = mutableListOf<String>()
        val current = StringBuilder()
        for (sentence in sentences) {
            if (current.isNotEmpty() && current.length + sentence.length + 1 > maxChars) {
                pieces.add(current.toString())
                current.setLength(0)
            }
            if (current.isNotEmpty()) current.append(' ')
            current.append(sentence)
        }
        if (current.isNotEmpty()) pieces.add(current.toString())
        return pieces
    }

    /** Last ≤[overlapChars] of [chunk], snapped forward to a word start. */
    private fun overlapTail(chunk: String, overlapChars: Int): String {
        if (overlapChars <= 0 || chunk.length <= overlapChars) return ""
        val raw = chunk.substring(chunk.length - overlapChars)
        val firstSpace = raw.indexOfFirst { it.isWhitespace() }
        return if (firstSpace < 0) raw else raw.substring(firstSpace).trim()
    }
}
