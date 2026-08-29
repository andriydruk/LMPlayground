package com.druk.llamacpp.asr

import java.util.Locale

/**
 * Word error rate for speech-recognition results.
 *
 * Shared by the macOS harness probe and the on-device instrumented test so a
 * transcript is graded the same way in both places — otherwise "it passes on
 * my Mac" and "it passes on the phone" would not be comparable claims.
 */
object WordErrorRate {

    /**
     * Levenshtein distance over words, divided by the reference length:
     * (substitutions + insertions + deletions) / reference words.
     *
     * 0.0 is a perfect transcript; values above 1.0 are possible when the
     * engine invents more words than were spoken.
     *
     * Case and punctuation are stripped first. Dictation drops text into a
     * field the user then edits, so "France?" against "france" is not a defect
     * worth failing a build over — but a wrong *word* still counts.
     */
    fun of(reference: String, hypothesis: String): Double {
        val ref = normalize(reference)
        val hyp = normalize(hypothesis)
        if (ref.isEmpty()) return if (hyp.isEmpty()) 0.0 else 1.0

        // Row-by-row edit distance: only the previous row is ever needed.
        var prev = IntArray(hyp.size + 1) { it }
        val cur = IntArray(hyp.size + 1)
        for (i in 1..ref.size) {
            cur[0] = i
            for (j in 1..hyp.size) {
                val sub = prev[j - 1] + if (ref[i - 1] == hyp[j - 1]) 0 else 1
                cur[j] = minOf(sub, prev[j] + 1, cur[j - 1] + 1)
            }
            prev = cur.copyOf()
        }
        return prev[hyp.size].toDouble() / ref.size
    }

    fun percent(value: Double): String = "${"%.1f".format(value * 100)}%"

    /** Lowercase, drop punctuation, split on whitespace. Keeps letters, digits, apostrophes. */
    fun normalize(s: String): List<String> = s
        .lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{Nd}\\s']"), " ")
        .split(Regex("\\s+"))
        .filter { it.isNotEmpty() }
}
