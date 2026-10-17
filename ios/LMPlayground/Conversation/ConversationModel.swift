import Foundation
import LlamaKit
import Observation

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

    /// The model the next message goes to.
    var selectedModel: ModelDescriptor? { runtime.loaded?.descriptor }

    private let store: ChatStore
    private let runtime: ModelRuntime
    private let models: ModelStore
    @ObservationIgnored let tools = ToolRegistry()

    /// The engine session for the current conversation, model and settings.
    @ObservationIgnored private var generation: GenerationSession?
    @ObservationIgnored private var generationKey: String?
    @ObservationIgnored private var turnTask: Task<Void, Never>?

    init(store: ChatStore, runtime: ModelRuntime, models: ModelStore) {
        self.store = store
        self.runtime = runtime
        self.models = models
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

    // MARK: Models

    /// Load `model` for this conversation and remember it as the
    /// conversation's model.
    func select(model: ModelDescriptor, force: Bool = false) async {
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

    // MARK: Turns

    func send() {
        let text = draft.trimmingCharacters(in: .whitespacesAndNewlines)
        guard canSend, let loaded = runtime.loaded else { return }
        draft = ""

        do {
            if session == nil {
                session = try store.createSession(title: text, model: loaded.descriptor,
                                                  params: params, systemPrompt: systemPrompt)
            }
        } catch {
            errorMessage = error.localizedDescription
            return
        }
        guard let sessionId = session?.id else { return }

        // Only a model with an attached projector can see the image.
        let image = loaded.supportsVision ? pendingImage : nil
        pendingImage = nil
        var user = ChatMessage(role: .user, content: text, imagePath: image)
        user.recordId = (try? store.insert(user.record(sessionId: sessionId)))?.id
        messages.append(user)

        var reply = ChatMessage(role: .assistant, content: "")
        reply.responseStartedAt = Date()
        if thinkingEnabled && loaded.supportsThinking { reply.thinkingStartedAt = Date() }
        messages.append(reply)
        isGenerating = true

        let thinking = thinkingEnabled && loaded.supportsThinking
        let supportsThinking = loaded.supportsThinking
        let tools: ToolRegistry? = loaded.supportsToolCalling ? self.tools : nil
        let filename = loaded.descriptor.filename

        turnTask = Task {
            do {
                await self.tools.setModel(filename)
                let generation = try await prepareGeneration(for: loaded)
                let observer = makeObserver()
                let imageData: Data? = if let image {
                    await Task.detached { ChatImageStore.visionData(for: image) }.value
                } else {
                    nil
                }
                _ = try await TurnRunner.run(session: generation, message: text, image: imageData,
                                             enableThinking: thinking, supportsThinking: supportsThinking,
                                             tools: tools, observer: observer)
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

    private func applyDelta(_ text: String, thinkingTokens: Int, responseTokens: Int, thinkingJustStarted: Bool) {
        guard isGenerating, let last = messages.indices.last else { return }
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
        reply.recordId = (try? store.insert(reply.record(sessionId: sessionId)))?.id
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
    private func prepareGeneration(for loaded: ModelRuntime.LoadedModel) async throws -> GenerationSession {
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

        // Complete (user, assistant) pairs before the message being sent,
        // which is already appended along with its empty reply.
        let history = Self.pairTurns(Array(messages.dropLast(2)))
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
