#if DEBUG
import Foundation

/// The app's services with canned content and no engine behind them: a
/// models folder of empty placeholder files, an in-memory database with the
/// chats the scenes show, and Gemma 4 E2B staged as the loaded model.
/// Downloads and dictation are the real (idle) ones.
@MainActor
final class StoreFixtures {
    let store: ModelStore
    let downloads: DownloadManager
    let runtime: ModelRuntime
    let chatStore: ChatStore
    let conversation: ConversationModel
    let dictation: DictationController

    /// The models the picker lists, as on Android.
    private static let pickerModels = ["Gemma 4 E2B", "Llama 3.2 3B", "Qwen 3.5 2B", "Phi-4 mini",
                                       "Ministral 3 3B Instruct", "Nemotron 3 Nano 4B"]
    private static let loadedModel = "Gemma 4 E2B"

    private let database: AppDatabase
    private var chats: [StoreScene: ChatSession] = [:]

    private init(models: URL, database: AppDatabase) {
        self.database = database
        store = ModelStore(directory: models)
        downloads = AppServices.shared.downloads
        dictation = AppServices.shared.dictation
        runtime = ModelRuntime(store: store)
        chatStore = ChatStore(database: database)
        let root = AppDirectories.caches.appending(path: "StoreScenes", directoryHint: .isDirectory)
        let rag = RagStore(database: database, embedder: EmbeddingModelManager(directory: models),
                           filesDirectory: root.appending(path: "Documents", directoryHint: .isDirectory))
        conversation = ConversationModel(store: chatStore, runtime: runtime, models: store,
                                         downloads: downloads, rag: rag)
    }

    static func make(for scene: StoreScene) async throws -> StoreFixtures {
        let root = AppDirectories.caches.appending(path: "StoreScenes", directoryHint: .isDirectory)
        try? FileManager.default.removeItem(at: root)
        let models = root.appending(path: "Models", directoryHint: .isDirectory)
        try FileManager.default.createDirectory(at: models, withIntermediateDirectories: true)
        for name in pickerModels {
            guard let model = ModelCatalog.chatModels.first(where: { $0.name == name }) else { continue }
            let sizes = Self.sizes(in: model.summary)
            try placeholder(models.appending(path: model.filename), size: sizes.first ?? 1)
            if let projector = model.mmprojFilename {
                try placeholder(models.appending(path: projector), size: sizes.dropFirst().first ?? 1)
            }
        }

        let fixtures = StoreFixtures(models: models, database: try AppDatabase.makeEmpty())
        try await fixtures.populate()
        fixtures.open(for: scene)
        return fixtures
    }

    private func populate() async throws {
        if let gemma = store.downloaded.first(where: { $0.name == Self.loadedModel }) {
            await runtime.stage(gemma, contextTrainSize: 131_072, thinking: true, tools: true, vision: true)
        }
        let model = runtime.loaded?.descriptor

        // Oldest first, so the sidebar lists the plain chat on top.
        let image = "store_scene_sheep.jpg"
        if let source = Bundle.main.url(forResource: "sample_vision_sheep", withExtension: "jpg") {
            try? FileManager.default.removeItem(at: ChatImageStore.url(for: image))
            try FileManager.default.copyItem(at: source, to: ChatImageStore.url(for: image))
        }
        chats[.chatWithImage] = try addChat(
            model: model, question: storeText("screenshot_vision_question"), image: image,
            reply: storeText("screenshot_vision_response"), tokens: 38, seconds: 1.4)

        let documents = try addChat(
            model: model, question: storeText("screenshot_documents_question"),
            reply: storeText("screenshot_documents_response"), tokens: 52, seconds: 1.8)
        chats[.documents] = documents
        // The filename stays English in every language, as on Android.
        let document = RagDocument(
            id: UUID().uuidString, sessionId: documents.id, displayName: "rental-agreement.pdf",
            mimeType: "application/pdf", storedPath: nil, sizeBytes: 245_000, status: RagDocument.ready,
            chunkCount: 18, embeddingDim: 768, embeddingModel: ModelCatalog.embeddingModel.filename,
            createdAt: Date().millisecondsSince1970)
        try await database.writer.write { try document.insert($0) }

        chats[.chat] = try addChat(
            model: model, question: storeText("screenshot_chat_question"),
            reply: "<think>Let me explain machine learning clearly and concisely.</think>\n"
                + storeText("screenshot_chat_response"),
            thinking: (seconds: 3, tokens: 42), tokens: 156, seconds: 4.2)

        // Most recently edited first: the sample prompt leads the list. The
        // pause gives each prompt its own millisecond timestamp.
        for key in ["screenshot_prompt_sample_brainstorm", "screenshot_prompt_sample_translator",
                    "screenshot_prompt_sample_code", "screenshot_system_prompt_sample"] {
            _ = try chatStore.createPrompt(text: storeText(key))
            try await Task.sleep(for: .milliseconds(5))
        }
        for tool in ToolRegistry.toolNames {
            ToolSettings.setEnabledByDefault(tool, true)
            if let model { ToolSettings.setOverride(tool, model: model.filename, nil) }
        }
    }

    /// Open the chat the scene shows behind or around its main content. The
    /// picker gets an empty one: a chat would show through its glass.
    private func open(for scene: StoreScene) {
        if scene == .chooseModel {
            conversation.newConversation()
            return
        }
        guard let chat = chats[scene] ?? chats[.chat] else { return }
        conversation.open(chat)
        if scene == .generationParams {
            var params = GenerationParams()
            params.temperature = 0.7
            params.topP = 0.9
            params.repetitionPenalty = 1.1
            conversation.updateParams(params)
            conversation.updateSystemPrompt(storeText("screenshot_system_prompt_sample"))
        }
    }

    private func addChat(model: ModelDescriptor?, question: String, image: String? = nil, reply: String,
                         thinking: (seconds: Int, tokens: Int)? = nil, tokens: Int, seconds: Double) throws -> ChatSession {
        let session = try chatStore.createSession(title: question, model: model, params: GenerationParams(),
                                                  systemPrompt: "")
        let now = Date().millisecondsSince1970
        try chatStore.insert(ChatMessageRecord(
            id: nil, sessionId: session.id, author: ChatMessage.Role.user.rawValue, content: question,
            thinkingDurationSeconds: 0, thinkingTokens: 0, responseTokens: 0, responseDurationSeconds: 0,
            timestamp: now, imagePath: image, interrupted: false, toolCalls: nil))
        // Android's copy draws its lists with "•" lines; as Markdown they'd
        // run together.
        let markdown = reply.replacingOccurrences(of: "\n• ", with: "\n- ")
        try chatStore.insert(ChatMessageRecord(
            id: nil, sessionId: session.id, author: ChatMessage.Role.assistant.rawValue, content: markdown,
            thinkingDurationSeconds: thinking?.seconds ?? 0, thinkingTokens: thinking?.tokens ?? 0,
            responseTokens: tokens, responseDurationSeconds: seconds,
            timestamp: now + 1, imagePath: nil, interrupted: false, toolCalls: nil))
        return session
    }

    /// An empty file of `size` bytes (sparse, so it takes no space): enough
    /// for the store to list it as downloaded.
    private static func placeholder(_ url: URL, size: UInt64) throws {
        FileManager.default.createFile(atPath: url.path, contents: nil)
        let handle = try FileHandle(forWritingTo: url)
        try handle.truncate(atOffset: size)
        try handle.close()
    }

    /// The download sizes in a catalog summary, "… · 3.35Gb + 532Mb mmproj".
    private static func sizes(in summary: String) -> [UInt64] {
        summary.matches(of: /([0-9.]+)(Gb|Mb)/).compactMap { match in
            guard let value = Double(match.output.1) else { return nil }
            return UInt64(value * (match.output.2 == "Gb" ? 1e9 : 1e6))
        }
    }
}
#endif
