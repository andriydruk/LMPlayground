import Foundation

/// One tool call in an assistant reply (Android: ToolCallInfo).
struct ToolCallInfo: Codable, Hashable, Sendable {
    var name: String
    var arguments: String
    var result: String
    var durationMs: Int64 = 0
    /// Thinking produced in this round before the call. Only the first call of
    /// a round carries it, so the UI can render think → call → think → call.
    var precedingThinking = ""
    var precedingThinkingDurationSeconds = 0
    var precedingThinkingTokens = 0
}

/// A message as the conversation screen shows it (Android: Message).
struct ChatMessage: Identifiable, Equatable, Sendable {
    enum Role: String, Sendable {
        case user = "User"
        case assistant = "Assistant"
    }

    let id: UUID
    var recordId: Int64?
    var role: Role
    /// Raw text; assistant replies keep thinking inside `<think>…</think>`.
    var content: String
    var imagePath: String?
    var timestamp: Date
    var thinkingDurationSeconds = 0
    var thinkingTokens = 0
    var responseTokens = 0
    var responseDurationSeconds = 0.0
    var interrupted = false
    var toolCalls: [ToolCallInfo] = []

    // Live timers while the reply streams; not persisted.
    var thinkingStartedAt: Date?
    var responseStartedAt: Date?

    init(id: UUID = UUID(), role: Role, content: String, imagePath: String? = nil, timestamp: Date = Date()) {
        self.id = id
        self.role = role
        self.content = content
        self.imagePath = imagePath
        self.timestamp = timestamp
    }

    init(record: ChatMessageRecord) {
        id = UUID()
        recordId = record.id
        role = Role(rawValue: record.author) ?? .assistant
        content = record.content
        imagePath = record.imagePath
        timestamp = Date(milliseconds: record.timestamp)
        thinkingDurationSeconds = record.thinkingDurationSeconds
        thinkingTokens = record.thinkingTokens
        responseTokens = record.responseTokens
        responseDurationSeconds = record.responseDurationSeconds
        interrupted = record.interrupted
        toolCalls = record.toolCalls
            .flatMap { $0.data(using: .utf8) }
            .flatMap { try? JSONDecoder().decode([ToolCallInfo].self, from: $0) } ?? []
    }

    func record(sessionId: String) -> ChatMessageRecord {
        ChatMessageRecord(
            id: recordId, sessionId: sessionId, author: role.rawValue, content: content,
            thinkingDurationSeconds: thinkingDurationSeconds, thinkingTokens: thinkingTokens,
            responseTokens: responseTokens, responseDurationSeconds: responseDurationSeconds,
            timestamp: timestamp.millisecondsSince1970, imagePath: imagePath,
            interrupted: interrupted,
            toolCalls: toolCalls.isEmpty ? nil : (try? JSONEncoder().encode(toolCalls)).map { String(decoding: $0, as: UTF8.self) })
    }

    var totalTokens: Int { thinkingTokens + responseTokens }
}

/// Thinking and answer, split out of a raw reply.
struct ThinkingSplit: Equatable {
    var thinking: String
    var response: String

    /// Port of Android's splitThinking: every `<think>…</think>` block (or an
    /// unterminated trailing `<think>…` while it streams) is thinking; the rest
    /// after the last block is the answer.
    init(_ text: String) {
        let matches = text.matches(of: ThinkingSplit.pattern)
        guard !matches.isEmpty else {
            thinking = ""
            response = text
            return
        }
        var parts: [String] = []
        var cursor = text.startIndex
        for match in matches {
            let content = String(match.output.1 ?? match.output.2 ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            if !content.isEmpty { parts.append(content) }
            cursor = match.range.upperBound
        }
        thinking = parts.joined(separator: "\n")
        response = cursor < text.endIndex
            ? String(text[cursor...]).replacing(/^\s+/, with: "")
            : ""
    }

    // <think>(.*?)</think>\n?|<think>(.*)  with "dot matches newlines".
    nonisolated(unsafe) private static let pattern = /<think>(.*?)<\/think>\n?|<think>(.*)/.dotMatchesNewlines()
}

/// Port of :llamacpp's ResponseProcessor.
enum ResponseProcessor {
    /// Remove separator lines ("---", "———") some models emit between the
    /// `</think>` block and the answer.
    static func process(_ raw: String) -> String {
        guard let close = raw.range(of: "</think>") else { return raw }
        let rest = raw[close.upperBound...]
        let cleaned = String(rest).replacing(/^\s*[-—_]{2,}\s*/, with: "\n\n", maxReplacements: 1)
        return String(raw[..<close.upperBound]) + cleaned
    }
}
