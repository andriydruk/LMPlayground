package com.druk.llamacpp.asr

/**
 * Builds dictated text from the streaming recognizer's deltas.
 *
 * A delta is a raw slice of the detokenized transcript and carries its own
 * spacing: " world" starts a new word, while "ld" finishes one ("wor" + "ld")
 * and "," follows the previous word with no space at all. Slices are cut
 * wherever an encoder chunk happens to end, so a word is often split across
 * two of them. Deltas must therefore be concatenated verbatim — trimming them
 * and re-inserting spaces is what turned "fellow" into "fel low".
 *
 * Whitespace is tidied only over a whole utterance: the streaming checkpoints
 * emit a locale tag between sentences ("you. <en-US> Ask"), and once the tag
 * is stripped the spaces on either side of it — which can arrive in separate
 * deltas — collapse into one here.
 *
 * Shared by the app and the macOS harness, so the probe grades exactly the text
 * the user would see. Not thread-safe; the caller serializes access.
 */
class DictationTranscript {

    /** Finished utterances, already tidied. */
    private val committed = StringBuilder()

    /** Raw deltas of the utterance being recognized. */
    private val utterance = StringBuilder()

    /** Appends a delta exactly as the recognizer returned it. */
    fun append(delta: String) {
        utterance.append(delta)
    }

    /**
     * Closes the current utterance. The next stream detokenizes from scratch
     * and so starts without a leading space; this is where the words of two
     * utterances get separated.
     */
    fun endUtterance() {
        val all = text
        committed.setLength(0)
        committed.append(all)
        utterance.setLength(0)
    }

    /** Everything recognized so far, with the utterance in progress. */
    val text: String
        get() {
            val current = tidy(utterance)
            return when {
                committed.isEmpty() -> current
                current.isEmpty() -> committed.toString()
                else -> "$committed $current"
            }
        }

    fun clear() {
        committed.setLength(0)
        utterance.setLength(0)
    }

    companion object {
        private val WHITESPACE = Regex("\\s+")

        /** Collapses whitespace runs to one space and trims the ends. */
        fun tidy(raw: CharSequence): String = WHITESPACE.replace(raw, " ").trim()
    }
}
