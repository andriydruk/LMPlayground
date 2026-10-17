import Foundation
import NaturalLanguage

/// Splits document text into overlapping chunks sized for the embedding
/// model (Android: TextChunker). Paragraphs first, then sentences
/// (NLTokenizer, multilingual) for oversized paragraphs, with a hard cap as
/// the last resort. ~1000 chars ≈ 250 tokens, well inside EmbeddingGemma's
/// 2048-token context after the task prefix.
enum TextChunker {

    struct Chunk: Equatable {
        let text: String
        let ordinal: Int
    }

    /// - Parameters:
    ///   - targetChars: a chunk closes once the next piece would push it past this.
    ///   - overlapChars: tail of each chunk repeated at the start of the next,
    ///     so sentences straddling a boundary stay retrievable.
    ///   - maxChars: hard cap for one piece (splits mid-sentence beyond it).
    static func chunk(_ text: String, targetChars: Int = 1000, overlapChars: Int = 150,
                      maxChars: Int = 1600) -> [Chunk] {
        precondition(targetChars > 0 && maxChars >= targetChars && overlapChars < targetChars)

        let pieces = text.split(separator: /\n{2,}/)
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
            .flatMap { splitOversized($0, maxChars: maxChars) }
        if pieces.isEmpty { return [] }

        var chunks: [String] = []
        var current = ""
        var currentLength = 0
        for piece in pieces {
            let pieceLength = piece.count
            if currentLength > 0 && currentLength + pieceLength + 1 > targetChars {
                chunks.append(current)
                current = overlapTail(current, overlapChars: overlapChars)
                currentLength = current.count
            }
            if currentLength > 0 {
                current += "\n"
                currentLength += 1
            }
            current += piece
            currentLength += pieceLength
        }
        if !current.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { chunks.append(current) }

        return chunks.enumerated().map { Chunk(text: $1, ordinal: $0) }
    }

    /// A paragraph longer than maxChars, split at sentence boundaries and
    /// re-merged up to maxChars so pieces still carry context.
    private static func splitOversized(_ paragraph: String, maxChars: Int) -> [String] {
        if paragraph.count <= maxChars { return [paragraph] }

        var sentences: [String] = []
        let tokenizer = NLTokenizer(unit: .sentence)
        tokenizer.string = paragraph
        tokenizer.enumerateTokens(in: paragraph.startIndex..<paragraph.endIndex) { range, _ in
            let sentence = paragraph[range].trimmingCharacters(in: .whitespacesAndNewlines)
            if sentence.count > maxChars {
                // Degenerate input with no sentence breaks: hard split.
                var rest = Substring(sentence)
                while !rest.isEmpty {
                    sentences.append(String(rest.prefix(maxChars)))
                    rest = rest.dropFirst(maxChars)
                }
            } else if !sentence.isEmpty {
                sentences.append(sentence)
            }
            return true
        }

        var pieces: [String] = []
        var current = ""
        var currentLength = 0
        for sentence in sentences {
            let length = sentence.count
            if currentLength > 0 && currentLength + length + 1 > maxChars {
                pieces.append(current)
                current = ""
                currentLength = 0
            }
            if currentLength > 0 {
                current += " "
                currentLength += 1
            }
            current += sentence
            currentLength += length
        }
        if !current.isEmpty { pieces.append(current) }
        return pieces
    }

    /// The last ≤ overlapChars of `chunk`, snapped forward to a word start.
    private static func overlapTail(_ chunk: String, overlapChars: Int) -> String {
        guard overlapChars > 0, chunk.count > overlapChars else { return "" }
        let raw = chunk.suffix(overlapChars)
        guard let space = raw.firstIndex(where: \.isWhitespace) else { return String(raw) }
        return raw[space...].trimmingCharacters(in: .whitespacesAndNewlines)
    }
}

/// Wraps retrieved excerpts around the user's message (Android:
/// RagPromptBuilder). The result replaces the message on the wire only: the
/// UI and the database keep the original text, and retrieval runs again for
/// every turn. Excerpts ride in the user turn because the system prompt is
/// fixed per session and covered by the preamble cache.
enum RagPromptBuilder {

    /// Character budget for excerpts: ~25% of the context (4 chars ≈ 1
    /// token), capped at ~2048 tokens' worth.
    static func budgetChars(contextSize: Int) -> Int {
        min(contextSize, 8192)
    }

    /// `chunks` sorted by descending score; the lowest-scoring ones are
    /// dropped to fit. Returns `userText` unchanged when none fits.
    static func build(userText: String, chunks: [RetrievedChunk], maxContextChars: Int) -> String {
        guard !chunks.isEmpty, maxContextChars > 0 else { return userText }
        var excerpts = ""
        var length = 0
        var included = 0
        for chunk in chunks {
            let header = "[Source: \(chunk.documentName) — excerpt \(included + 1)]\n"
            let addition = header.count + chunk.text.count + 2
            if length + addition > maxContextChars { break }
            excerpts += header + chunk.text + "\n\n"
            length += addition
            included += 1
        }
        if included == 0 { return userText }
        return "Use the following excerpts from the user's attached document(s) to answer. "
            + "If the answer is not in the excerpts, say you don't know.\n\n"
            + excerpts
            + "Question: " + userText
    }
}

struct RetrievedChunk: Sendable, Equatable {
    let documentName: String
    let text: String
    let score: Float
}
