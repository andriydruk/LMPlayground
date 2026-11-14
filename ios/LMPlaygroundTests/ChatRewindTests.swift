import Foundation
import Testing
@testable import LMPlayground

struct ChatRewindTests {

    private func user(_ text: String, imagePath: String? = nil) -> ChatMessage {
        ChatMessage(role: .user, content: text, imagePath: imagePath)
    }

    private func assistant(_ text: String) -> ChatMessage {
        ChatMessage(role: .assistant, content: text)
    }

    // u0 a0 u1 a1 u2 a2
    private let chat: [ChatMessage] = [
        ChatMessage(role: .user, content: "first"), ChatMessage(role: .assistant, content: "r0"),
        ChatMessage(role: .user, content: "second"), ChatMessage(role: .assistant, content: "r1"),
        ChatMessage(role: .user, content: "third"), ChatMessage(role: .assistant, content: "r2"),
    ]

    @Test func regenerateResendsThePromptingTurn() throws {
        let plan = try #require(ChatRewind.regenerate(chat, assistantIndex: 5))
        #expect(plan.kept == Array(chat[..<4]))
        #expect(plan.dropped == Array(chat[4...]))
        #expect(plan.rewind == .init(turnIndex: 2, anchorText: "third"))
        #expect(plan.resend?.text == "third")
    }

    @Test func regenerateSkipsOverMessagesBetweenPromptAndReply() throws {
        // An aborted turn can leave two user messages in a row.
        let plan = try #require(ChatRewind.regenerate([user("a"), user("b"), assistant("r")], assistantIndex: 2))
        #expect(plan.rewind?.turnIndex == 1)
        #expect(plan.resend?.text == "b")
    }

    @Test func regenerateNeedsAnAssistantReplyWithAPrompt() {
        #expect(ChatRewind.regenerate(chat, assistantIndex: 2) == nil)
        #expect(ChatRewind.regenerate(chat, assistantIndex: 99) == nil)
        #expect(ChatRewind.regenerate([assistant("hi")], assistantIndex: 0) == nil)
    }

    @Test func editDropsTheTurnAndEverythingAfter() throws {
        let plan = try #require(ChatRewind.edit(chat, userIndex: 2, newText: "  changed  "))
        #expect(plan.kept == Array(chat[..<2]))
        #expect(plan.dropped == Array(chat[2...]))
        // The anchor is the original text: that's what the engine holds.
        #expect(plan.rewind == .init(turnIndex: 1, anchorText: "second"))
        #expect(plan.resend?.text == "changed")
    }

    @Test func editKeepsTheAttachment() throws {
        let plan = try #require(ChatRewind.edit([user("look", imagePath: "img.jpg"), assistant("a cat")],
                                                userIndex: 0, newText: ""))
        #expect(plan.resend?.imagePath == "img.jpg")
        #expect(plan.rewind == .init(turnIndex: 0, anchorText: "look"))
    }

    @Test func editRejectsBlankTextWithoutAnImage() {
        #expect(ChatRewind.edit(chat, userIndex: 0, newText: "   ") == nil)
        #expect(ChatRewind.edit(chat, userIndex: 1, newText: "not a user message") == nil)
    }

    @Test func branchKeepsUpToTheReplyAndRewindsTheNextTurn() throws {
        let plan = try #require(ChatRewind.branch(chat, at: 3))
        #expect(plan.kept == Array(chat[..<4]))
        #expect(plan.dropped == Array(chat[4...]))
        #expect(plan.rewind == .init(turnIndex: 2, anchorText: "third"))
        #expect(plan.resend == nil)
    }

    @Test func branchFromTheLastReplyNeedsNoRewind() throws {
        let plan = try #require(ChatRewind.branch(chat, at: 5))
        #expect(plan.kept == chat)
        #expect(plan.rewind == nil)
    }

    @Test func branchOnlyFromAssistantReplies() {
        #expect(ChatRewind.branch(chat, at: 0) == nil)
        #expect(ChatRewind.branch(chat, at: -1) == nil)
    }
}
