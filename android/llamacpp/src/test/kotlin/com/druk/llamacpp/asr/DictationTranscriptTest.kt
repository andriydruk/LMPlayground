package com.druk.llamacpp.asr

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The deltas below are the recognizer's real output for jfk.wav and
 * micro-machines.wav fed in 100 ms slices, as the phone's microphone delivers
 * them.
 */
class DictationTranscriptTest {

    private fun assemble(vararg deltas: String) =
        DictationTranscript().apply { deltas.forEach(::append) }.text

    @Test
    fun `a word split across deltas is not broken apart`() {
        assertEquals(
            "And so my fellow Americans",
            assemble("And", " so", " my fel", "low", " Americans"),
        )
    }

    @Test
    fun `punctuation at the start of a delta stays attached`() {
        assertEquals(
            "at the airport marine, man the gun turret",
            assemble(" at the airport", " marine", ", man the", " gun turret"),
        )
    }

    @Test
    fun `the space left by a stripped locale tag collapses`() {
        // "you. <en-US> Ask" with the tag removed natively; the two spaces can
        // also arrive in different deltas.
        assertEquals(
            "can do for you. Ask what you can do for your country.",
            assemble(" can do", " for", " you", ".  Ask", " what", " you can", " do for", " your", " country", ". "),
        )
        assertEquals("you. Ask", assemble("you. ", " Ask"))
    }

    @Test
    fun `utterances are separated by one space`() {
        val transcript = DictationTranscript()
        transcript.append("Hello")
        transcript.append(" there. ")
        transcript.endUtterance()
        assertEquals("Hello there.", transcript.text)

        // A new stream starts without a leading space.
        transcript.append("General")
        transcript.append(" Ken")
        transcript.append("obi")
        assertEquals("Hello there. General Kenobi", transcript.text)
    }

    @Test
    fun `an utterance with no speech adds nothing`() {
        val transcript = DictationTranscript()
        transcript.append("One")
        transcript.endUtterance()
        transcript.endUtterance()
        transcript.append(" ")
        assertEquals("One", transcript.text)
    }

    @Test
    fun `clear starts over`() {
        val transcript = DictationTranscript()
        transcript.append("One")
        transcript.endUtterance()
        transcript.append(" two")
        transcript.clear()
        assertEquals("", transcript.text)
    }
}
