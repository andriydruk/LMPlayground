import Foundation
import LlamaKit
import Observation
import UIKit

/// The conversation screen's state and actions (Android: ConversationViewModel
/// + GenerationCoordinator + ChatSessionStore wiring).
@Observable
@MainActor
final class ConversationModel {

    // MARK: State

    private(set) var session: ChatSession?
    private(set) var messages: [ChatMessage] = []
    private(set) var isGenerating = false
    var draft = ""
    /// A picked or captured image waiting to go with the next message (saved
    /// name in ChatImageStore).
    var pendingImage: String?
    var thinkingEnabled = true
    var params = GenerationParams()
    var systemPrompt = ""
    var errorMessage: String?
    /// Documents attached to this conversation (indexing or ready).
    private(set) var documents: [RagDocument] = []
    /// The model a reopened conversation used, when another one (or none) is
    /// loaded: offered as a one-tap switch for a few seconds.
    var sessionModelHint: ModelDescriptor?
    /// A document waiting for the user to agree to download the embedding
    /// model.
    private(set) var documentAwaitingEmbeddingModel: RagDocument?
    /// A brief confirmation, shown for a few seconds.
    var notice: String?

    /// The model the next message goes to.
    var selectedModel: ModelDescriptor? { runtime.loaded?.descriptor }

    /// Whether an engine session is alive to report on.
    var hasEngineSession: Bool { generation != nil }

    private let store: ChatStore
    private let runtime: ModelRuntime
    private let models: ModelStore
    private let downloads: DownloadManager
    let rag: RagStore
    @ObservationIgnored let tools = ToolRegistry()
    @ObservationIgnored private var documentsTask: Task<Void, Never>?
    /// The streaming tick (Android: GenerationHaptics), throttled so a fast
    /// stream feels like typing rather than one long buzz.
    @ObservationIgnored private let haptics = UIImpactFeedbackGenerator(style: .light)
    @ObservationIgnored private var hapticsThisTurn = false
    @ObservationIgnored private var lastHaptic = Date.distantPast
    private static let hapticInterval: TimeInterval = 0.06

    /// The engine session for the current conversation, model and settings.
    private var generation: GenerationSession?
    @ObservationIgnored private var generationKey: String?
    @ObservationIgnored private var turnTask: Task<Void, Never>?

    init(store: ChatStore, runtime: ModelRuntime, models: ModelStore, downloads: DownloadManager, rag: RagStore) {
        self.store = store
        self.runtime = runtime
        self.models = models
        self.downloads = downloads
        self.rag = rag
    }

    var canSend: Bool {
        !isGenerating && runtime.loaded != nil
            && !draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    // MARK: Conversations

    func newConversation() {
        stop()
        session = nil
        messages = []
        tools.links.restore([:])
        discardPendingImage()
        systemPrompt = ""
        params = GenerationParams()
        sessionModelHint = nil
        observeDocuments()
        invalidateGeneration()
    }

    func open(_ chat: ChatSession) {
        stop()
        session = chat
        params = chat.params
        systemPrompt = chat.systemPrompt
        messages = ((try? store.messages(in: chat.id)) ?? []).map(ChatMessage.init(record:))
        // Search references from earlier turns keep resolving.
        tools.links.restore(ConversationMetadata(json: chat.metadata).stringMap(ConversationMetadata.webLinksKey))
        sessionModelHint = chat.modelFilename == runtime.loaded?.descriptor.filename ? nil
            : models.downloaded.first { $0.filename == chat.modelFilename }
        observeDocuments()
        invalidateGeneration()
    }

    /// Change sampling settings: the engine session is rebuilt and the
    /// history replayed on the next message.
    func updateParams(_ new: GenerationParams) {
        params = new
        if let id = session?.id {
            try? store.updateSession(id) { $0.params = new }
        }
        invalidateGeneration()
    }

    func updateSystemPrompt(_ text: String) {
        systemPrompt = text
        if let id = session?.id {
            try? store.updateSession(id) { $0.systemPrompt = text }
        }
        invalidateGeneration()
    }

    // MARK: Images

    func attachImage(_ data: Data) {
        discardPendingImage()
        pendingImage = ChatImageStore.persist(data)
        if pendingImage == nil { errorMessage = String(localized: "This image couldn't be read.") }
    }

    func discardPendingImage() {
        if let pendingImage { ChatImageStore.delete([pendingImage]) }
        pendingImage = nil
    }

    // MARK: Documents

    /// Attach a picked document: checked, copied into the app, and indexed
    /// (after the one-time embedding model download, if needed).
    func attachDocument(_ url: URL) {
        let accessed = url.startAccessingSecurityScopedResource()
        defer { if accessed { url.stopAccessingSecurityScopedResource() } }
        let values = try? url.resourceValues(forKeys: [.fileSizeKey, .contentTypeKey])
        let displayName = url.lastPathComponent
        let mimeType = values?.contentType?.preferredMIMEType
        let size = Int64(values?.fileSize ?? 0)
        guard DocumentText.format(mimeType: mimeType, displayName: displayName) != nil else {
            errorMessage = DocumentExtractionError.unsupported.message
            return
        }
        guard size <= DocumentText.maxFileBytes else {
            errorMessage = DocumentExtractionError.tooLarge.message
            return
        }
        do {
            let sessionId = try ensureSession(title: displayName)
            let document = try rag.attach(sessionId: sessionId, source: url, displayName: displayName,
                                          mimeType: mimeType, sizeBytes: size)
            if rag.embedder.isModelOnDisk() || downloads.isDownloading(rag.embedder.modelFilename) {
                startIndexing(document)
            } else {
                documentAwaitingEmbeddingModel = document
            }
        } catch {
            errorMessage = String(localized: "Couldn't open this document.")
        }
    }

    /// Agreed to the embedding model download: fetch it, then index.
    func confirmEmbeddingModelDownload() {
        guard let document = documentAwaitingEmbeddingModel else { return }
        documentAwaitingEmbeddingModel = nil
        let model = ModelCatalog.embeddingModel
        if let remote = model.remoteURL.flatMap(URL.init(string:)) {
            downloads.start(filename: model.filename, url: remote)
        }
        startIndexing(document)
    }

    func dismissEmbeddingModelPrompt() {
        guard let document = documentAwaitingEmbeddingModel else { return }
        documentAwaitingEmbeddingModel = nil
        rag.deleteDocument(document.id)
    }

    func removeDocument(_ document: RagDocument) {
        rag.deleteDocument(document.id)
    }

    private func startIndexing(_ document: RagDocument) {
        Task {
            if !rag.embedder.isModelOnDisk() {
                guard await waitForEmbeddingModel() else {
                    rag.deleteDocument(document.id)
                    errorMessage = String(localized: "Downloading the embedding model failed. Check your connection and attach the document again.")
                    return
                }
            }
            do throws(DocumentExtractionError) {
                try await rag.index(document)
            } catch {
                if error != .cancelled { errorMessage = "\(document.displayName): \(error.message)" }
            }
        }
    }

    /// Wait for the embedding model download; false if it failed or was
    /// cancelled.
    private func waitForEmbeddingModel() async -> Bool {
        let filename = rag.embedder.modelFilename
        while true {
            if rag.embedder.isModelOnDisk() { return true }
            guard let progress = downloads.downloads[filename], progress.failure == nil else {
                return rag.embedder.isModelOnDisk()
            }
            try? await Task.sleep(for: .milliseconds(500))
        }
    }

    private func observeDocuments() {
        documentsTask?.cancel()
        documentsTask = nil
        documentAwaitingEmbeddingModel = nil
        guard let sessionId = session?.id else {
            documents = []
            return
        }
        documents = (try? rag.documents(sessionId: sessionId)) ?? []
        let observation = rag.observeDocuments(sessionId: sessionId)
        documentsTask = Task { [weak self] in
            do {
                for try await value in observation {
                    guard let self, self.session?.id == sessionId else { return }
                    self.documents = value
                }
            } catch {}
        }
    }

    /// The message as the model sees it: with excerpts from the attached
    /// documents when any are relevant. The UI and history keep `userText`.
    private func wireContent(sessionId: String, userText: String) async -> String {
        guard (try? rag.hasReadyDocuments(sessionId: sessionId)) == true else { return userText }
        let retrieved = await rag.retrieve(sessionId: sessionId, query: userText)
        return RagPromptBuilder.build(userText: userText, chunks: retrieved,
                                      maxContextChars: RagPromptBuilder.budgetChars(contextSize: params.contextSize))
    }

    // MARK: Models

    /// Load `model` for this conversation and remember it as the
    /// conversation's model.
    func select(model: ModelDescriptor, force: Bool = false) async {
        sessionModelHint = nil
        guard runtime.loaded?.descriptor.filename != model.filename else { return }
        stop()
        await releaseGeneration()
        do {
            try await runtime.load(model, contextSize: params.contextSize, force: force)
            if let id = session?.id {
                try? store.updateSession(id) {
                    $0.modelFilename = model.filename
                    $0.modelName = model.name
                }
            }
            if !(runtime.loaded?.supportsThinking ?? false) { thinkingEnabled = false }
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    /// Unload the model, freeing its memory (Android: Unload Model).
    func unloadModel() async {
        stop()
        await releaseGeneration()
        await runtime.unload()
    }

    // MARK: Turns

    func send() {
        let text = draft.trimmingCharacters(in: .whitespacesAndNewlines)
        guard canSend, let loaded = runtime.loaded else { return }
        draft = ""

        let sessionId: String
        do {
            sessionId = try ensureSession(title: text)
        } catch {
            errorMessage = error.localizedDescription
            return
        }

        // Only a model with an attached projector can see the image.
        let image = loaded.supportsVision ? pendingImage : nil
        pendingImage = nil
        beginTurn(text: text, imagePath: image, sessionId: sessionId, loaded: loaded)
    }

    /// Add the user message and its reply, and generate the reply.
    private func beginTurn(text: String, imagePath image: String?, sessionId: String,
                           loaded: ModelRuntime.LoadedModel) {
        var user = ChatMessage(role: .user, content: text, imagePath: image)
        user.recordId = (try? store.insert(user.record(sessionId: sessionId)))?.id
        messages.append(user)

        var reply = ChatMessage(role: .assistant, content: "")
        reply.responseStartedAt = Date()
        if thinkingEnabled && loaded.supportsThinking { reply.thinkingStartedAt = Date() }
        messages.append(reply)
        isGenerating = true
        startHaptics()

        let thinking = thinkingEnabled && loaded.supportsThinking
        let supportsThinking = loaded.supportsThinking
        let tools: ToolRegistry? = loaded.supportsToolCalling ? self.tools : nil
        let filename = loaded.descriptor.filename
        // An edited or regenerated turn keeps its image, but a model without
        // a projector can't see it.
        let visionImage = loaded.supportsVision ? image : nil

        turnTask = Task {
            do {
                await self.tools.setModel(filename)
                // The history is everything before this message and its reply.
                let generation = try await prepareGeneration(for: loaded, upTo: messages.count - 2)
                let observer = makeObserver()
                let imageData: Data? = if let visionImage {
                    await Task.detached { ChatImageStore.visionData(for: visionImage) }.value
                } else {
                    nil
                }
                let wire = await wireContent(sessionId: sessionId, userText: text)
                _ = try await TurnRunner.run(session: generation, message: wire, image: imageData,
                                             enableThinking: thinking, supportsThinking: supportsThinking,
                                             tools: tools, observer: observer)
            } catch {
                errorMessage = error.localizedDescription
            }
            finishTurn(sessionId: sessionId)
        }
    }

    /// Regenerate a reply: everything from the user message that prompted it
    /// is dropped and that message re-sent. The engine rewinds its cache
    /// rather than evaluating the conversation again.
    func regenerate(_ reply: ChatMessage) {
        guard let index = messages.firstIndex(where: { $0.id == reply.id }) else { return }
        ChatRewind.regenerate(messages, assistantIndex: index).map(rewindAndResend)
    }

    /// Replace a user message with `text` and re-send it; what followed it is
    /// dropped.
    func edit(_ message: ChatMessage, text: String) {
        guard let index = messages.firstIndex(where: { $0.id == message.id }) else { return }
        ChatRewind.edit(messages, userIndex: index, newText: text).map(rewindAndResend)
    }

    private func rewindAndResend(_ plan: ChatRewind.Plan) {
        guard !isGenerating, let resend = plan.resend, let loaded = runtime.loaded,
              let sessionId = session?.id else { return }
        // Lock the chat's actions until the new turn takes over.
        isGenerating = true
        Task {
            await rewindEngine(plan.rewind, for: loaded)
            // The chat may have changed while the engine was busy.
            guard session?.id == sessionId, runtime.loaded != nil else {
                isGenerating = false
                return
            }
            try? store.deleteMessages(plan.dropped.compactMap(\.recordId))
            ChatImageStore.delete(plan.dropped.compactMap(\.imagePath).filter { $0 != resend.imagePath })
            messages = plan.kept
            beginTurn(text: resend.text, imagePath: resend.imagePath, sessionId: sessionId, loaded: loaded)
        }
    }

    /// Continue the conversation up to `reply` in a new chat, leaving this one
    /// as it is. The engine session carries on in the branch, so nothing is
    /// evaluated again; the original replays when reopened, as any other.
    func branch(from reply: ChatMessage) {
        guard !isGenerating, let sourceId = session?.id,
              let index = messages.firstIndex(where: { $0.id == reply.id }),
              let plan = ChatRewind.branch(messages, at: index) else { return }
        let keepsEngine = runtime.loaded.map { generation != nil && generationKey == currentKey(for: $0) } ?? false
        // Each chat owns its image files.
        let kept = plan.kept.map { message in
            var copy = message
            copy.recordId = nil
            copy.imagePath = message.imagePath.flatMap(ChatImageStore.copy)
            return copy.record(sessionId: sourceId)
        }
        isGenerating = true
        Task {
            defer { isGenerating = false }
            let result: (session: ChatSession, messages: [ChatMessageRecord])?
            do {
                result = try store.branchSession(sourceId: sourceId, kept: kept) { title, number in
                    number == 1 ? String(localized: "\(title) (branch)")
                                : String(localized: "\(title) (branch \(number))")
                }
            } catch {
                errorMessage = error.localizedDescription
                return
            }
            guard let result else { return }
            if let loaded = runtime.loaded { await rewindEngine(plan.rewind, for: loaded) }
            guard session?.id == sourceId else { return }
            let refused = generationKey == nil
            session = result.session
            messages = result.messages.map(ChatMessage.init(record:))
            observeDocuments()
            // The key includes the conversation id: move the live session over.
            if keepsEngine, !refused, let loaded = runtime.loaded { generationKey = currentKey(for: loaded) }
            notice = String(localized: "Continuing in a new chat")
        }
    }

    /// Drop the engine's turns from `rewind` on. A session that isn't this
    /// conversation's, or that refuses, is rebuilt from the messages on the
    /// next turn.
    private func rewindEngine(_ rewind: ChatRewind.EngineRewind?, for loaded: ModelRuntime.LoadedModel) async {
        guard let rewind, let generation, generationKey == currentKey(for: loaded) else { return }
        if !(await generation.rewind(toTurn: rewind.turnIndex, expectedText: rewind.anchorText)) {
            invalidateGeneration()
        }
    }

    /// The engine's report on the current session (Android: Session Info).
    func sessionReport() async -> String? {
        await generation?.report
    }

    /// The current conversation's id, creating its row first for a new chat.
    private func ensureSession(title: String) throws -> String {
        if let id = session?.id { return id }
        let created = try store.createSession(title: title, model: runtime.loaded?.descriptor,
                                              params: params, systemPrompt: systemPrompt)
        session = created
        observeDocuments()
        // The engine session's key includes the conversation id.
        invalidateGeneration()
        return created.id
    }

    /// Keep writing the last reply after it was interrupted by going to the
    /// background. Reuses the engine's cache when the session is still the
    /// same; otherwise the history is replayed and the partial reply re-fed.
    func continueReply() {
        guard !isGenerating, let loaded = runtime.loaded, let sessionId = session?.id,
              let last = messages.indices.last, messages[last].role == .assistant,
              messages[last].interrupted else { return }
        var reply = messages[last]
        reply.interrupted = false
        // Timers carry on from where the reply stopped.
        reply.responseStartedAt = Date().addingTimeInterval(-reply.responseDurationSeconds)
        let inThinking = reply.content.hasPrefix("<think>") && !reply.content.contains("</think>")
        if inThinking {
            reply.thinkingStartedAt = Date().addingTimeInterval(-Double(reply.thinkingDurationSeconds))
        }
        messages[last] = reply
        isGenerating = true
        startHaptics()

        let resume = TurnRunner.Resume(thinkingTokens: reply.thinkingTokens, responseTokens: reply.responseTokens,
                                       inThinking: inThinking)
        let thinking = thinkingEnabled && loaded.supportsThinking
        let supportsThinking = loaded.supportsThinking
        let tools: ToolRegistry? = loaded.supportsToolCalling ? self.tools : nil
        let filename = loaded.descriptor.filename

        turnTask = Task {
            do {
                await self.tools.setModel(filename)
                let generation = try await prepareGeneration(for: loaded, upTo: messages.count)
                _ = try await TurnRunner.run(session: generation, start: .continuation(resume),
                                             enableThinking: thinking, supportsThinking: supportsThinking,
                                             tools: tools, observer: makeObserver())
            } catch LlamaError.cannotContinue {
                errorMessage = LlamaError.cannotContinue.localizedDescription
                if let last = messages.indices.last { messages[last].interrupted = true }
            } catch {
                errorMessage = error.localizedDescription
            }
            finishTurn(sessionId: sessionId)
        }
    }

    /// Stop the reply in progress; what was generated so far is kept.
    func stop() {
        guard isGenerating else { return }
        generation?.abort()
        turnTask?.cancel()
    }

    /// The app went to the background mid-reply: iPhone apps can't use the
    /// GPU in the background, so the reply stops here and is marked
    /// interrupted (ios/plan/05-platform-research.md, "Background").
    func interruptForBackground() {
        guard isGenerating, let last = messages.indices.last else { return }
        messages[last].interrupted = true
        stop()
    }

    private func makeObserver() -> TurnObserver {
        TurnObserver(
            onDelta: { [weak self] text, thinkingTokens, responseTokens, justStarted in
                Task { @MainActor in
                    self?.applyDelta(text, thinkingTokens: thinkingTokens, responseTokens: responseTokens,
                                     thinkingJustStarted: justStarted)
                }
            },
            onToolCalls: { [weak self] calls in
                Task { @MainActor in self?.applyToolCalls(calls) }
            },
            onRoundStarted: { [weak self] thinking in
                Task { @MainActor in
                    guard let self, thinking, let last = self.messages.indices.last else { return }
                    self.messages[last].thinkingStartedAt = Date()
                }
            })
    }

    private func startHaptics() {
        hapticsThisTurn = AppSettings.hapticsOnGeneration
        if hapticsThisTurn { haptics.prepare() }
    }

    private func applyDelta(_ text: String, thinkingTokens: Int, responseTokens: Int, thinkingJustStarted: Bool) {
        guard isGenerating, let last = messages.indices.last else { return }
        if hapticsThisTurn, Date().timeIntervalSince(lastHaptic) >= Self.hapticInterval {
            haptics.impactOccurred(intensity: 0.5)
            lastHaptic = Date()
        }
        var message = messages[last]
        if thinkingJustStarted, message.thinkingStartedAt == nil { message.thinkingStartedAt = Date() }
        if let started = message.thinkingStartedAt {
            message.thinkingDurationSeconds = Int(Date().timeIntervalSince(started))
            if text.contains("</think>") { message.thinkingStartedAt = nil }
        }
        if let started = message.responseStartedAt {
            message.responseDurationSeconds = Date().timeIntervalSince(started)
        }
        message.content = text
        message.thinkingTokens = thinkingTokens
        message.responseTokens = responseTokens
        messages[last] = message
    }

    private func applyToolCalls(_ calls: [ToolCallInfo]) {
        guard let last = messages.indices.last, !calls.isEmpty else { return }
        var message = messages[last]
        var enriched = calls
        enriched[0].precedingThinking = message.content
        enriched[0].precedingThinkingDurationSeconds = message.thinkingDurationSeconds
        enriched[0].precedingThinkingTokens = message.thinkingTokens
        message.toolCalls += enriched
        message.content = ""
        message.thinkingDurationSeconds = 0
        message.thinkingTokens = 0
        message.thinkingStartedAt = nil
        messages[last] = message
    }

    private func finishTurn(sessionId: String) {
        defer {
            isGenerating = false
            turnTask = nil
        }
        guard let last = messages.indices.last, messages[last].role == .assistant else { return }
        var reply = messages[last]
        if let started = reply.responseStartedAt {
            reply.responseDurationSeconds = Date().timeIntervalSince(started)
        }
        reply.responseStartedAt = nil
        reply.thinkingStartedAt = nil
        // A reply that never produced anything (failed before the first
        // token) is dropped rather than saved empty.
        if reply.content.isEmpty && reply.toolCalls.isEmpty {
            messages.removeLast()
            return
        }
        if reply.recordId != nil {
            // A continued reply: its row already exists.
            try? store.update(reply.record(sessionId: sessionId))
        } else {
            reply.recordId = (try? store.insert(reply.record(sessionId: sessionId)))?.id
        }
        messages[last] = reply
        // Snapshot search references so they still resolve after a relaunch.
        let links = tools.links.snapshot()
        if !links.isEmpty {
            try? store.updateSession(sessionId) { session in
                var metadata = ConversationMetadata(json: session.metadata)
                metadata.setStringMap(ConversationMetadata.webLinksKey, links)
                session.metadata = metadata.json
            }
        }
    }

    // MARK: Engine session

    private func currentKey(for loaded: ModelRuntime.LoadedModel) -> String {
        let enabled = ToolRegistry.toolNames.filter { ToolSettings.isEnabled($0, model: loaded.descriptor.filename) }
        return "\(loaded.descriptor.filename)|\(params.hashValue)|\(systemPrompt.hashValue)|\(session?.id ?? "")|\(enabled)"
    }

    private func invalidateGeneration() {
        generationKey = nil
    }

    private func releaseGeneration() async {
        if let old = generation {
            generation = nil
            generationKey = nil
            await old.destroy()
        }
    }

    /// The engine session for the loaded model with this conversation's
    /// settings, recreated (and the history replayed) when anything changed.
    private func prepareGeneration(for loaded: ModelRuntime.LoadedModel, upTo historyEnd: Int) async throws -> GenerationSession {
        let key = currentKey(for: loaded)
        if let generation, generationKey == key { return generation }
        await releaseGeneration()

        var sampler = SamplerParams()
        sampler.contextSize = Int32(params.contextSize)
        sampler.temperature = Float(params.temperature)
        sampler.topP = Float(params.topP)
        sampler.repetitionPenalty = Float(params.repetitionPenalty)
        sampler.topK = Int32(params.topK)
        sampler.minP = Float(params.minP)
        sampler.seed = params.seed
        sampler.thinkingBudget = Int32(params.thinkingBudget)
        sampler.systemPrompt = systemPrompt

        // Complete (user, assistant) pairs in messages[..<historyEnd].
        let history = Self.pairTurns(Array(messages.prefix(max(0, historyEnd))))
        let filename = loaded.descriptor.filename
        let modelSize = models.files[filename] ?? 0
        let prompt = systemPrompt
        let toolsJSON = loaded.supportsToolCalling ? (await tools.toolsJSON ?? "[]") : "[]"

        let created = try await Self.makeSession(model: loaded.model, sampler: sampler, history: history,
                                                 filename: filename, modelSize: modelSize,
                                                 systemPrompt: prompt, toolsJSON: toolsJSON)
        generation = created
        generationKey = key
        return created
    }

    @InferenceActor
    private static func makeSession(model: Model, sampler: SamplerParams, history: [(user: String, assistant: String)],
                                    filename: String, modelSize: UInt64, systemPrompt: String,
                                    toolsJSON: String) throws -> GenerationSession {
        let session = try model.makeSession(sampler)
        session.setTools(toolsJSON)
        PreambleCache.apply(to: session, modelFilename: filename, modelSize: modelSize,
                            systemPrompt: systemPrompt, toolsJSON: toolsJSON)
        if !history.isEmpty {
            session.replay(history)
        }
        return session
    }

    /// Consecutive (user, assistant) pairs; unpaired messages are skipped
    /// (Android: HistoryReplay.pairTurns).
    nonisolated static func pairTurns(_ messages: [ChatMessage]) -> [(user: String, assistant: String)] {
        var pairs: [(user: String, assistant: String)] = []
        var i = 0
        while i < messages.count {
            if messages[i].role == .user, i + 1 < messages.count, messages[i + 1].role == .assistant {
                pairs.append((messages[i].content, messages[i + 1].content))
                i += 2
            } else {
                i += 1
            }
        }
        return pairs
    }
}
