import Foundation

/// Builds dictated text from the streaming recognizer's deltas (Android:
/// DictationTranscript).
///
/// A delta is a raw slice of the detokenized transcript and carries its own
/// spacing: " world" starts a word, "ld" finishes one ("wor" + "ld"), and ","
/// follows the previous word directly. Slices end wherever an encoder chunk
/// ends, so deltas are concatenated verbatim; trimming them and re-inserting
/// spaces is what turned "fellow" into "fel low".
///
/// Whitespace is tidied only over a whole utterance: the spaces around a
/// stripped locale tag ("you. <en-US> Ask") can arrive in separate deltas.
struct DictationTranscript {

    /// Finished utterances, already tidied.
    private var committed = ""
    /// Raw deltas of the utterance being recognized.
    private var utterance = ""

    /// Append a delta exactly as the recognizer returned it.
    mutating func append(_ delta: String) {
        utterance += delta
    }

    /// Close the current utterance. The next stream detokenizes from scratch
    /// and starts without a leading space; this is where two utterances'
    /// words get separated.
    mutating func endUtterance() {
        committed = text
        utterance = ""
    }

    /// Everything recognized so far, with the utterance in progress.
    var text: String {
        let current = Self.tidy(utterance)
        if committed.isEmpty { return current }
        if current.isEmpty { return committed }
        return committed + " " + current
    }

    mutating func clear() {
        committed = ""
        utterance = ""
    }

    /// Whitespace runs collapsed to one space, ends trimmed.
    static func tidy(_ raw: String) -> String {
        raw.replacing(/\s+/, with: " ").trimmingCharacters(in: .whitespaces)
    }
}
