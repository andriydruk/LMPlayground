import Foundation

/// Pure planning for edit, regenerate and branch: which messages survive,
/// which user turn the engine rewinds to, and what gets re-sent (Android:
/// ChatRewind).
///
/// Turn numbers count user messages from the start of the chat, as the engine
/// does. The turn's text rides along so a drifted numbering is refused by the
/// engine rather than rewinding the wrong turn; the session is then rebuilt
/// from `kept`.
enum ChatRewind {

    struct EngineRewind: Equatable {
        let turnIndex: Int
        let anchorText: String
    }

    struct Plan: Equatable {
        /// Messages that stay, in order.
        let kept: [ChatMessage]
        /// Messages removed from the chat and from storage.
        let dropped: [ChatMessage]
        /// The rewind needed, or nil when the engine already matches `kept`.
        let rewind: EngineRewind?
        /// The user turn to send after the rewind, or nil (branch).
        let resend: Resend?
    }

    struct Resend: Equatable {
        let text: String
        /// The original attachment, kept.
        let imagePath: String?
    }

    private static func turnIndex(in messages: [ChatMessage], at index: Int) -> Int {
        messages[..<index].count { $0.role == .user }
    }

    /// Regenerate the reply at `assistantIndex`: drop it and everything after,
    /// and re-send the user message that prompted it.
    static func regenerate(_ messages: [ChatMessage], assistantIndex: Int) -> Plan? {
        guard messages.indices.contains(assistantIndex), messages[assistantIndex].role == .assistant,
              let userIndex = messages[..<assistantIndex].lastIndex(where: { $0.role == .user }) else { return nil }
        return resend(messages, from: userIndex, text: messages[userIndex].content)
    }

    /// Replace the user message at `userIndex` with `newText` and re-send it.
    static func edit(_ messages: [ChatMessage], userIndex: Int, newText: String) -> Plan? {
        guard messages.indices.contains(userIndex), messages[userIndex].role == .user else { return nil }
        let text = newText.trimmingCharacters(in: .whitespacesAndNewlines)
        if text.isEmpty && messages[userIndex].imagePath == nil { return nil }
        return resend(messages, from: userIndex, text: text)
    }

    private static func resend(_ messages: [ChatMessage], from userIndex: Int, text: String) -> Plan {
        let original = messages[userIndex]
        return Plan(kept: Array(messages[..<userIndex]),
                    dropped: Array(messages[userIndex...]),
                    rewind: EngineRewind(turnIndex: turnIndex(in: messages, at: userIndex), anchorText: original.content),
                    resend: Resend(text: text, imagePath: original.imagePath))
    }

    /// Branch the chat at the reply at `index`: a new chat holding everything
    /// up to and including it. The engine drops the turns after it, if any, so
    /// the branch continues on the cache it already has.
    static func branch(_ messages: [ChatMessage], at index: Int) -> Plan? {
        guard messages.indices.contains(index), messages[index].role == .assistant else { return nil }
        let next = messages.indices[(index + 1)...].first { messages[$0].role == .user }
        return Plan(kept: Array(messages[...index]),
                    dropped: Array(messages[(index + 1)...]),
                    rewind: next.map { EngineRewind(turnIndex: turnIndex(in: messages, at: $0), anchorText: messages[$0].content) },
                    resend: nil)
    }
}
