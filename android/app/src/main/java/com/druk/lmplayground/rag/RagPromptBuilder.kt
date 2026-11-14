package com.druk.lmplayground.rag

/**
 * Wraps retrieved document excerpts around the user's message. The result
 * replaces the message on the wire only — UI and persistence keep the
 * original text, and retrieval re-runs for every turn (excerpts ride in
 * the user turn because the system prompt is fixed at session creation
 * and covered by the preamble KV-cache fingerprint).
 */
object RagPromptBuilder {

    /**
     * Character budget for injected excerpts: ~25% of the session context
     * (4 chars ≈ 1 token), capped at ~2048 tokens' worth.
     */
    fun budgetChars(contextSize: Int): Int = minOf(contextSize, 8192)

    /**
     * Build the wire message. [chunks] must be sorted by descending score
     * (as returned by RagRepository.retrieve); lowest-scoring chunks are
     * dropped to fit [maxContextChars]. Returns [userText] unchanged when
     * no chunk fits.
     */
    fun build(
        userText: String,
        chunks: List<RagRepository.RetrievedChunk>,
        maxContextChars: Int,
    ): String {
        if (chunks.isEmpty() || maxContextChars <= 0) return userText

        val excerpts = StringBuilder()
        var included = 0
        for (chunk in chunks) {
            val header = "[Source: ${chunk.documentName} — excerpt ${included + 1}]\n"
            val addition = header.length + chunk.text.length + 2
            if (excerpts.length + addition > maxContextChars) break
            excerpts.append(header).append(chunk.text).append("\n\n")
            included++
        }
        if (included == 0) return userText

        return buildString {
            append(
                "Use the following excerpts from the user's attached document(s) " +
                    "to answer. If the answer is not in the excerpts, say you " +
                    "don't know.\n\n"
            )
            append(excerpts)
            append("Question: ")
            append(userText)
        }
    }
}
