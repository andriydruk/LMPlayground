import SwiftUI

/// Debug self-test of the chat stack on a device, without UI automation:
/// launch with `-labChat <model filename>`. Drives the real ConversationModel
/// (persistence, history replay, interruption, tools, vision, documents) and
/// writes Documents/lab-chat.json. `-labChatParts chat,tools,vision,rag`
/// runs only some parts.
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
        let parts = UserDefaults.standard.string(forKey: "labChatParts")?.split(separator: ",").map(String.init)
        func wants(_ part: String) -> Bool { parts?.contains(part) ?? true }

        if wants("chat") {
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
        }

        // 5. A tool round with the offline JavaScript tool.
        if wants("tools") {
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
        if wants("vision"), descriptor.isMmprojDownloaded, let data = try? Data(contentsOf: cat) {
            conversation.newConversation()
            conversation.attachImage(data)
            let vision = await ask("What animal is in this picture? One word.")
            let text = ThinkingSplit(vision?.content ?? "").response
            check("vision", text.localizedCaseInsensitiveContains("cat") || text.localizedCaseInsensitiveContains("kitten"), text)
            let user = conversation.messages.dropLast().last
            check("image saved", user?.imagePath != nil, user?.imagePath ?? "nil")
        }

        // 7. Document Q&A, when the embedding model is on disk: a text file
        // and a generated PDF, each holding a fact the model can't know.
        if wants("rag"), conversation.rag.embedder.isModelOnDisk() {
            conversation.newConversation()
            let folder = URL.temporaryDirectory.appending(path: "selftest-docs", directoryHint: .isDirectory)
            try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
            let notes = folder.appending(path: "zorblat-notes.txt")
            try? Data(Self.notesText.utf8).write(to: notes)
            let pdf = folder.appending(path: "harbor-report.pdf")
            try? Self.makePDF(Self.pdfText).write(to: pdf)

            let started = ContinuousClock.now
            conversation.attachDocument(notes)
            conversation.attachDocument(pdf)
            // The chips' list arrives through a database observation.
            while ContinuousClock.now - started < .seconds(120), conversation.errorMessage == nil,
                  !(conversation.documents.count == 2 && conversation.documents.allSatisfy(\.isReady)) {
                try? await Task.sleep(for: .milliseconds(200))
            }
            let documents = conversation.documents
            check("rag index", documents.count == 2 && documents.allSatisfy(\.isReady),
                  documents.map { "\($0.displayName) \($0.status) \($0.chunkCount) chunks dim \($0.embeddingDim)" }.joined(separator: "; ")
                  + " in \(ContinuousClock.now - started)" + (conversation.errorMessage.map { " error: \($0)" } ?? ""))
            conversation.errorMessage = nil

            let fromText = await ask("What is the access code of the Zorblat project? Reply with the number only.")
            let textAnswer = ThinkingSplit(fromText?.content ?? "").response
            check("rag text", textAnswer.contains("7391"), textAnswer)
            let fromPDF = await ask("According to the harbor report, what colour is the lighthouse at Pelican Point? One word.")
            let pdfAnswer = ThinkingSplit(fromPDF?.content ?? "").response
            check("rag pdf", pdfAnswer.localizedCaseInsensitiveContains("violet"), pdfAnswer)
            // The UI and history keep the question, not the excerpts.
            let question = conversation.messages.dropLast().last?.content ?? ""
            check("rag wire only", !question.contains("[Source:"), String(question.prefix(80)))
        }

        // 8. A 100-page PDF indexed next to the loaded chat model (the M6
        // exit check): time, chunk count and peak footprint.
        if wants("ragbig"), conversation.rag.embedder.isModelOnDisk() {
            conversation.newConversation()
            let pdf = URL.temporaryDirectory.appending(path: "selftest-docs/long-report.pdf")
            try? FileManager.default.createDirectory(at: pdf.deletingLastPathComponent(), withIntermediateDirectories: true)
            try? Self.makeLongPDF(pages: 100).write(to: pdf)
            let before = ProcessMemory.footprint.megabytes
            var peak = before
            let started = ContinuousClock.now
            conversation.attachDocument(pdf)
            while ContinuousClock.now - started < .seconds(600), conversation.errorMessage == nil,
                  !(conversation.documents.first?.isReady ?? false) {
                peak = max(peak, ProcessMemory.footprint.megabytes)
                try? await Task.sleep(for: .milliseconds(100))
            }
            let elapsed = ContinuousClock.now - started
            let document = conversation.documents.first
            check("rag 100 pages", document?.isReady == true,
                  "\(document?.chunkCount ?? 0) chunks in \(elapsed), footprint \(before) → peak \(peak) MB, available \(ProcessMemory.available.megabytes) MB"
                  + (conversation.errorMessage.map { " error: \($0)" } ?? ""))
            conversation.errorMessage = nil
            let answer = await ask("In the long report, which city hosts depot 87? Reply with the city name only.")
            let text = ThinkingSplit(answer?.content ?? "").response
            check("rag 100 pages answer", text.localizedCaseInsensitiveContains(Self.depotCity(87)), text)
        }

        // 9. Dictation through the real controller, with jfk.wav fed at
        // real-time pace in place of the microphone.
        let jfk = AppDirectories.documents.appending(path: "Fixtures/jfk.wav")
        if wants("dictation"), DictationEngine.shared.isModelOnDisk(),
           FileManager.default.fileExists(atPath: jfk.path) {
            await runDictation(jfk, check)
        }

        write(steps)
        note("done: \(steps.filter(\.passed).count)/\(steps.count) passed")
    }

    private func runDictation(_ audio: URL, _ check: (String, Bool, String) -> Void) async {
        let dictation = DictationController(downloads: nil, makeSource: { FileAudioSource(url: audio) })
        func waitForTranscript(_ limit: Duration = .seconds(60)) async -> String? {
            let started = ContinuousClock.now
            while dictation.transcript == nil, ContinuousClock.now - started < limit {
                try? await Task.sleep(for: .milliseconds(50))
            }
            defer { dictation.consumeTranscript() }
            return dictation.transcript
        }

        // One utterance: words arrive while "speaking", then the final text.
        let before = ProcessMemory.footprint.megabytes
        let started = ContinuousClock.now
        dictation.onMicPressed()
        dictation.startListening()
        var firstWords: Duration?
        var peak = before
        while dictation.isRecording || dictation.micOn {
            if firstWords == nil, case .listening(let text) = dictation.state, !text.isEmpty {
                firstWords = ContinuousClock.now - started
            }
            peak = max(peak, ProcessMemory.footprint.megabytes)
            try? await Task.sleep(for: .milliseconds(50))
        }
        let audioDone = ContinuousClock.now - started
        let text = await waitForTranscript() ?? ""
        check("dictation", text.localizedCaseInsensitiveContains("fellow Americans") && text.localizedCaseInsensitiveContains("your country"),
              "\(text) | first words at \(firstWords.map { "\($0)" } ?? "never"), audio \(audioDone), final \(ContinuousClock.now - started), footprint \(before) → \(peak) MB")
        check("dictation live", firstWords.map { $0 < audioDone } ?? false, "first words \(firstWords.map { "\($0)" } ?? "never") of \(audioDone)")

        // Tap off part-way and straight back on: both utterances land, in order.
        dictation.onMicPressed()
        dictation.startListening()
        try? await Task.sleep(for: .seconds(4))
        dictation.onMicReleased()
        dictation.onMicPressed()
        dictation.startListening()
        let joined = await waitForTranscript(.seconds(90)) ?? ""
        let americans = joined.ranges(of: /(?i)americans/).count
        check("dictation queue", joined.hasPrefix("And so") && americans == 2 && joined.localizedCaseInsensitiveContains("your country"), joined)

        // Editing the text mid-dictation abandons it: nothing more arrives.
        dictation.onMicPressed()
        dictation.startListening()
        try? await Task.sleep(for: .seconds(2))
        dictation.abandon()
        try? await Task.sleep(for: .seconds(3))
        check("dictation abandon", dictation.transcript == nil && dictation.state == .idle && !dictation.micOn,
              "transcript \(dictation.transcript ?? "nil"), state \(dictation.state)")

        // The model goes once idle.
        await DictationEngine.shared.unloadIfIdle()
        let loaded = await DictationEngine.shared.isLoaded
        check("dictation unload", !loaded, "footprint after unload \(ProcessMemory.footprint.megabytes) MB")
    }

    private static let cities = ["Lisbon", "Oslo", "Tallinn", "Valencia", "Krakow", "Porto", "Bergen", "Lyon",
                                 "Ghent", "Turin", "Gdansk", "Malmo", "Zagreb"]

    static func depotCity(_ page: Int) -> String { cities[(page * 7) % cities.count] }

    /// `pages` pages of report prose, each naming one depot's city.
    private static func makeLongPDF(pages: Int) -> Data {
        let bounds = CGRect(x: 0, y: 0, width: 612, height: 792)
        let filler = "Operations continued as planned across the network this quarter. Staff rotated between shifts, "
            + "inventory was counted twice, and the maintenance crews replaced worn conveyor belts. "
        return UIGraphicsPDFRenderer(bounds: bounds).pdfData { context in
            for page in 1...pages {
                context.beginPage()
                let text = "Section \(page)\n\n" + String(repeating: filler, count: 6)
                    + "\n\nDepot \(page) is located in \(depotCity(page)) and employs \(page * 3) people.\n\n"
                    + String(repeating: filler, count: 4)
                (text as NSString).draw(in: bounds.insetBy(dx: 54, dy: 54),
                                        withAttributes: [.font: UIFont.systemFont(ofSize: 11)])
            }
        }
    }

    private static let notesText = """
        Project notes, spring planning.

        The Zorblat project is our internal codename for the new irrigation controller. \
        Its access code is 7391, and only the field team may use it.

        Weekly meetings happen on Thursdays at the north office. Bring the soil moisture logs.

        Budget review is scheduled for the end of the quarter; the finance team will send a template.
        """

    private static let pdfText = """
        Harbor Report

        The harbor authority repainted the lighthouse at Pelican Point this year. \
        The new colour of the lighthouse at Pelican Point is violet, chosen by a public vote.

        Fishing quotas remain unchanged. The ferry timetable adds a late crossing on Saturdays.
        """

    /// A one-page PDF with real text, drawn the way any app would export it.
    private static func makePDF(_ text: String) -> Data {
        let bounds = CGRect(x: 0, y: 0, width: 612, height: 792)
        return UIGraphicsPDFRenderer(bounds: bounds).pdfData { context in
            context.beginPage()
            (text as NSString).draw(in: bounds.insetBy(dx: 54, dy: 54),
                                    withAttributes: [.font: UIFont.systemFont(ofSize: 14)])
        }
    }

    private func write(_ steps: [Step]) {
        let url = AppDirectories.documents.appending(path: "lab-chat.json")
        if let data = try? JSONEncoder.lab.encode(steps) { try? data.write(to: url) }
    }
}
