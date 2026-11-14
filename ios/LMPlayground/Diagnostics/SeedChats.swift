#if DEBUG
import Foundation

/// Two saved chats for scroll tests, debug builds only (`-uiSeedChats YES`):
/// a long one of Markdown replies of every height and a short one. Seeding
/// again replaces them.
enum SeedChats {
    static let long = "Scroll test: long"
    static let short = "Scroll test: short"

    static func seed(_ store: ChatStore) async {
        let existing = try? await store.observeSessions().first { _ in true }
        for session in existing ?? [] where session.title == long || session.title == short {
            _ = try? store.deleteSession(session.id)
        }
        // The long chat is created first, so the short one is the latest.
        add(long, turns: 30, end: "End of long chat", to: store)
        add(short, turns: 4, end: "End of short chat", to: store)
    }

    private static func add(_ title: String, turns: Int, end: String, to store: ChatStore) {
        guard let session = try? store.createSession(title: title, model: nil, params: GenerationParams(),
                                                     systemPrompt: "") else { return }
        var time = Date().millisecondsSince1970 - Int64(turns) * 60_000
        func insert(_ author: String, _ content: String, thinking: Bool = false) {
            time += 1000
            _ = try? store.insert(ChatMessageRecord(
                id: nil, sessionId: session.id, author: author, content: content,
                thinkingDurationSeconds: thinking ? 3 : 0, thinkingTokens: thinking ? 90 : 0, responseTokens: 120,
                responseDurationSeconds: 4, timestamp: time, imagePath: nil, interrupted: false,
                toolCalls: nil))
        }
        for turn in 1...turns {
            insert("User", turn % 3 == 0 ? "Question \(turn): explain it again, in more detail please." : "Question \(turn)?")
            var reply = reply(turn)
            if turn == turns { reply += "\n\n\(end)." }
            // Every other reply thought first, as a thinking model's do.
            let thinks = turn % 2 == 0
            if thinks {
                // Some run to many screens, as a long reasoning does.
                let sentences = turn % 4 == 0 || turn == turns ? 150 : 12
                let thought = Array(repeating: "Thinking about question \(turn), step by step, before answering.", count: sentences)
                reply = "<think>\(thought.joined(separator: " "))</think>\n\n" + reply
            }
            insert("Assistant", reply, thinking: thinks)
        }
    }

    /// Replies from one line to a screen and more: lists, code and tables.
    private static func reply(_ turn: Int) -> String {
        switch turn % 5 {
        case 0:
            return "Short answer \(turn)."
        case 1:
            return """
            ## Answer \(turn)

            A paragraph long enough to wrap over several lines on a phone, so that \
            the row is taller than an estimate would make it, and then some more words.

            - First point
            - Second point, which also runs on long enough to wrap onto a second line
            - Third point
            """
        case 2:
            return """
            Here is some code for \(turn):

            ```swift
            func greet(_ name: String) -> String {
                "Hello, \\(name)!"
            }
            print(greet("World"))
            ```

            And a closing sentence.
            """
        case 3:
            return """
            | Option | Speed | Quality |
            |---|---|---|
            | A | Fast | Good |
            | B | Slow | Best |

            1. Pick one
            2. Try it
            3. Compare the results with the other
            """
        default:
            return Array(repeating: "A long reply \(turn), paragraph after paragraph of text that wraps.", count: 8)
                .joined(separator: "\n\n")
        }
    }
}
#endif
