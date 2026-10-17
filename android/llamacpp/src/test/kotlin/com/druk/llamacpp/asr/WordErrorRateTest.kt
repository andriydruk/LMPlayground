package com.druk.llamacpp.asr

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ASR probes fail a build on this number, so a bug here would either hide
 * a broken decoder or block a good one.
 */
class WordErrorRateTest {

    private fun assertWer(expected: Double, reference: String, hypothesis: String) {
        val actual = WordErrorRate.of(reference, hypothesis)
        assertTrue(
            "expected $expected but was $actual for '$hypothesis'",
            abs(actual - expected) < 1e-9,
        )
    }

    @Test
    fun `identical text scores zero`() {
        assertWer(0.0, "ask not what your country can do for you", "ask not what your country can do for you")
    }

    @Test
    fun `case and punctuation are not errors`() {
        // The real jfk.wav result: an added comma, otherwise word-perfect.
        assertWer(
            0.0,
            "And so my fellow Americans, ask not what your country can do for you.",
            "and so, my fellow americans ask not what your country can do for you",
        )
    }

    @Test
    fun `one substitution in eleven words`() {
        // The real German result: "zwanzig" came back as "20".
        val reference = "Der Zug nach Hamburg fährt heute leider zwanzig Minuten später ab."
        val heard = "Der Zug nach Hamburg fährt heute leider 20 Minuten später ab."
        assertWer(1.0 / 11.0, reference, heard)
        assertEquals("9.1%", WordErrorRate.percent(WordErrorRate.of(reference, heard)))
    }

    @Test
    fun `deletions and insertions both count`() {
        assertWer(1.0 / 4.0, "one two three four", "one two four")
        assertWer(1.0 / 4.0, "one two three four", "one two two three four")
    }

    @Test
    fun `empty hypothesis is a total miss, not a pass`() {
        assertWer(1.0, "anything at all", "")
        assertWer(0.0, "", "")
    }

    @Test
    fun `an invented transcript can exceed one`() {
        assertTrue(WordErrorRate.of("hello", "the quick brown fox jumps") > 1.0)
    }
}
