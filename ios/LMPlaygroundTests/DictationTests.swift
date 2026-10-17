import Foundation
import Testing
@testable import LMPlayground

/// Ports of DictationTranscriptTest: the deltas are the recognizer's real
/// output for jfk.wav and micro-machines.wav fed in 100 ms slices.
struct DictationTranscriptTests {

    private func assemble(_ deltas: String...) -> String {
        var transcript = DictationTranscript()
        deltas.forEach { transcript.append($0) }
        return transcript.text
    }

    @Test func wordSplitAcrossDeltasStaysWhole() {
        #expect(assemble("And", " so", " my fel", "low", " Americans") == "And so my fellow Americans")
    }

    @Test func leadingPunctuationStaysAttached() {
        #expect(assemble(" at the airport", " marine", ", man the", " gun turret")
                == "at the airport marine, man the gun turret")
    }

    @Test func spaceLeftByAStrippedLocaleTagCollapses() {
        #expect(assemble(" can do", " for", " you", ".  Ask", " what", " you can", " do for", " your", " country", ". ")
                == "can do for you. Ask what you can do for your country.")
        #expect(assemble("you. ", " Ask") == "you. Ask")
    }

    @Test func utterancesAreSeparatedByOneSpace() {
        var transcript = DictationTranscript()
        transcript.append("Hello")
        transcript.append(" there. ")
        transcript.endUtterance()
        #expect(transcript.text == "Hello there.")
        transcript.append("General")
        transcript.append(" Ken")
        transcript.append("obi")
        #expect(transcript.text == "Hello there. General Kenobi")
    }

    @Test func utteranceWithNoSpeechAddsNothing() {
        var transcript = DictationTranscript()
        transcript.append("One")
        transcript.endUtterance()
        transcript.endUtterance()
        transcript.append(" ")
        #expect(transcript.text == "One")
    }

    @Test func clearStartsOver() {
        var transcript = DictationTranscript()
        transcript.append("One")
        transcript.endUtterance()
        transcript.append(" two")
        transcript.clear()
        #expect(transcript.text == "")
    }
}

struct DictationUITests {

    @Test func levelIsADecibelScale() {
        #expect(DictationController.level(rms: 0) == 0)
        #expect(DictationController.level(rms: 0.0001) == 0)      // -80 dB
        #expect(DictationController.level(rms: 1) == 1)           // 0 dB
        let speech = DictationController.level(rms: 0.02)         // ~-34 dB
        #expect(speech > 0.4 && speech < 0.6)
    }

    @Test func dictatedTextJoinsTheDraft() {
        #expect(InputBar.join("", "Hello") == "Hello")
        #expect(InputBar.join("Note:", "hello") == "Note: hello")
        #expect(InputBar.join("Note: ", "hello") == "Note: hello")
        #expect(InputBar.join("Draft", "") == "Draft")
    }

    @Test @MainActor func earconsAreShortWavs() {
        let wav = DictationEarcon.twoNotes(660, 880)
        #expect(wav.prefix(4) == Data("RIFF".utf8))
        // 2 × 70 ms of 16-bit mono at 44.1 kHz plus the 44-byte header.
        #expect(wav.count == 44 + 2 * 3087 * 2)
    }
}
