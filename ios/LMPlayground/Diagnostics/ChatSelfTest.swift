import SwiftUI

/// Debug self-test of the chat stack on a device, without UI automation:
/// launch with `-labChat <model filename>`. Drives the real ConversationModel
/// (persistence, history replay, interruption, tools, vision) and writes
/// Documents/lab-chat.json.
struct ChatSelfTestView: View {
    @Environment(ConversationModel.self) private var conversation
    @Environment(ModelStore.self) private var store
    @Environment(\.chatStore) private var chatStore
    @State private var log: [String] = []

    struct Step: Codable {
        var name: String
        var passed: Bool
        var detail: String
    }

    var body: some View {
        List(log, id: \.self) { Text($0).font(.caption.monospaced()) }
            .navigationTitle("Chat self-test")
            .task { await run() }
    }

    private func note(_ line: String) {
        log.append(line)
    }

    private func waitForReply() async {
        while conversation.isGenerating { try? await Task.sleep(for: .milliseconds(200)) }
    }

    private func ask(_ text: String) async -> ChatMessage? {
        conversation.draft = text
        conversation.send()
        await waitForReply()
        return conversation.messages.last
    }

    private func run() async {
        var steps: [Step] = []
        func check(_ name: String, _ passed: Bool, _ detail: String) {
            steps.append(Step(name: name, passed: passed, detail: detail))
            note("\(passed ? "PASS" : "FAIL") \(name): \(detail.prefix(160))")
        }

        try? FileManager.default.removeItem(at: AppDirectories.documents.appending(path: "lab-chat.json"))
        let filename = UserDefaults.standard.string(forKey: "labChat") ?? ""
        store.refresh()
        guard let descriptor = store.downloaded.first(where: { $0.filename == filename }) else {
            check("model", false, "\(filename) is not downloaded")
            write(steps)
            return
        }

        conversation.newConversation()
        await conversation.select(model: descriptor)
        check("load", conversation.selectedModel?.filename == filename, conversation.errorMessage ?? "loaded")
        conversation.thinkingEnabled = false

        // 1. A turn, saved.
        let first = await ask("What is the capital of France? Answer with one word.")
        let firstText = ThinkingSplit(first?.content ?? "").response
        check("answer", firstText.localizedCaseInsensitiveContains("paris"), firstText)
        check("saved", first?.recordId != nil, "record \(first?.recordId.map(String.init) ?? "nil")")

        // 2. A follow-up uses the conversation so far.
        let second = await ask("And of Germany? One word.")
        let secondText = ThinkingSplit(second?.content ?? "").response
        check("follow-up", secondText.localizedCaseInsensitiveContains("berlin"), secondText)

        // 3. Reopen the saved conversation: history is replayed into a new session.
        if let chat = conversation.session, let reopened = try? chatStore?.session(chat.id) {
            conversation.open(reopened)
            check("reopen", conversation.messages.count == 4, "\(conversation.messages.count) messages")
            let third = await ask("Which two cities did you name? Reply with both names.")
            let thirdText = ThinkingSplit(third?.content ?? "").response
            check("replay", thirdText.localizedCaseInsensitiveContains("paris") && thirdText.localizedCaseInsensitiveContains("berlin"), thirdText)
        }

        // 4. Interruption (what backgrounding does) keeps the partial reply.
        conversation.draft = "Write a long, detailed story about a lighthouse keeper, at least 800 words."
        conversation.send()
        // Interrupt once the reply is well under way.
        while conversation.isGenerating, (conversation.messages.last?.content.count ?? 0) < 200 {
            try? await Task.sleep(for: .milliseconds(100))
        }
        let wasGenerating = conversation.isGenerating
        conversation.interruptForBackground()
        await waitForReply()
        let interrupted = conversation.messages.last
        check("interrupt", wasGenerating && interrupted?.interrupted == true && !(interrupted?.content.isEmpty ?? true),
              "\(interrupted?.content.count ?? 0) chars, interrupted=\(interrupted?.interrupted == true)")
        if let id = interrupted?.recordId, let sessionId = conversation.session?.id {
            let saved = (try? chatStore?.messages(in: sessionId))?.first { $0.id == id }
            check("interrupt saved", saved?.interrupted == true, "persisted interrupted=\(saved?.interrupted == true)")
        }
        let after = await ask("Say OK.")
        // The stopped reply is committed with its turn closed, so this gets
        // an answer rather than a continuation of the story.
        let afterText = ThinkingSplit(after?.content ?? "").response
        check("after interrupt", afterText.localizedCaseInsensitiveContains("ok"), afterText)

        // 5. A tool round with the offline JavaScript tool.
        do {
            ToolSettings.setOverride("run_javascript", model: filename, true)
            conversation.newConversation()
            let tool = await ask("Use the run_javascript tool to compute 1234*5678 and tell me the result.")
            let calls = tool?.toolCalls ?? []
            let text = ThinkingSplit(tool?.content ?? "").response
            // The pipeline: the model called the tool, it ran, and the model
            // answered after it. Whether a small model writes correct code is
            // the model's business.
            check("tool call", calls.contains { $0.name == "run_javascript" && $0.result.contains("\"result\"") },
                  calls.map { "\($0.name)(\($0.arguments)) -> \($0.result)" }.joined(separator: "; "))
            check("tool answer", !text.isEmpty, text)
            ToolSettings.setOverride("run_javascript", model: filename, nil)
        }

        // 6. Vision, when the model's image module is on disk.
        let cat = AppDirectories.documents.appending(path: "Fixtures/test_cat.jpg")
        if descriptor.isMmprojDownloaded, let data = try? Data(contentsOf: cat) {
            conversation.newConversation()
            conversation.attachImage(data)
            let vision = await ask("What animal is in this picture? One word.")
            let text = ThinkingSplit(vision?.content ?? "").response
            check("vision", text.localizedCaseInsensitiveContains("cat") || text.localizedCaseInsensitiveContains("kitten"), text)
            let user = conversation.messages.dropLast().last
            check("image saved", user?.imagePath != nil, user?.imagePath ?? "nil")
        }

        write(steps)
        note("done: \(steps.filter(\.passed).count)/\(steps.count) passed")
    }

    private func write(_ steps: [Step]) {
        let url = AppDirectories.documents.appending(path: "lab-chat.json")
        if let data = try? JSONEncoder.lab.encode(steps) { try? data.write(to: url) }
    }
}
