import Foundation
import LlamaKit
import Observation

/// Owns the loaded chat model: load / unload transitions, the memory
/// preflight, the load-crash sentinel and capability detection. Android's
/// ModelRuntime, minus the service binding.
@Observable
@MainActor
final class ModelRuntime {

    enum Phase: Equatable {
        case idle
        case loading(filename: String, progress: Double)
        case ready
        case failed(filename: String, message: String)
    }

    enum LoadError: LocalizedError {
        case notDownloaded
        case tooLarge(neededMB: Int, availableMB: Int)

        var errorDescription: String? {
            switch self {
            case .notDownloaded:
                return String(localized: "The model file is missing.")
            case .tooLarge(let needed, let available):
                return String(localized: "This model needs about \(needed) MB of memory, but only \(available) MB is available. Try a smaller context size or a smaller model.")
            }
        }
    }

    struct LoadedModel {
        let descriptor: ModelDescriptor
        let model: Model
        let contextTrainSize: Int
        let supportsThinking: Bool
        let supportsToolCalling: Bool
        let supportsVision: Bool
    }

    private(set) var phase: Phase = .idle
    private(set) var loaded: LoadedModel?
    /// A model that crashed the previous launch while loading.
    var crashFromLastLaunch: LoadSentinel.Record?

    private let store: ModelStore

    init(store: ModelStore) {
        self.store = store
        crashFromLastLaunch = LoadSentinel.takeCrashFromLastLaunch()
    }

    /// The memory verdict for loading `descriptor` with `contextSize`.
    func memoryVerdict(for descriptor: ModelDescriptor, contextSize: Int) -> MemoryBudget.Verdict {
        let metadata = Engine.probe(store.url(for: descriptor.filename))
        let projector = descriptor.isMmprojDownloaded ? (descriptor.mmprojFilename.flatMap { store.files[$0] } ?? 0) : 0
        // The current model is about to be unloaded; don't count it.
        let available = ProcessMemory.available
        return MemoryBudget.check(metadata: metadata, contextSize: contextSize,
                                  projectorBytes: projector, available: available)
    }

    /// Load `descriptor`, replacing the current model. `force` skips the
    /// memory preflight (the user chose "Load anyway").
    func load(_ descriptor: ModelDescriptor, contextSize: Int, force: Bool = false) async throws {
        guard store.isOnDisk(descriptor.filename) else { throw LoadError.notDownloaded }
        await unload()

        if !force, case .tooLarge(let needed, let available) = memoryVerdict(for: descriptor, contextSize: contextSize) {
            phase = .failed(filename: descriptor.filename, message: LoadError.tooLarge(neededMB: needed, availableMB: available).localizedDescription)
            throw LoadError.tooLarge(neededMB: needed, availableMB: available)
        }

        phase = .loading(filename: descriptor.filename, progress: 0)
        let url = store.url(for: descriptor.filename)
        let projector = descriptor.isMmprojDownloaded ? descriptor.mmprojFilename.map(store.url(for:)) : nil
        let template = ChatTemplateOverrides.template(for: descriptor.filename)
        let filename = descriptor.filename

        do {
            let loaded = try await Self.loadOnEngine(url: url, filename: filename, projector: projector,
                                                     template: template) { [weak self] value in
                Task { @MainActor in
                    if case .loading(let name, _) = self?.phase, name == filename {
                        self?.phase = .loading(filename: name, progress: value)
                    }
                }
            }
            ModelPreferences.record(.init(tools: loaded.tools, thinking: loaded.thinking), for: filename)
            self.loaded = LoadedModel(descriptor: descriptor,
                                      model: loaded.model,
                                      contextTrainSize: loaded.contextTrainSize,
                                      supportsThinking: loaded.thinking,
                                      supportsToolCalling: loaded.tools,
                                      supportsVision: loaded.vision)
            phase = .ready
        } catch {
            phase = .failed(filename: filename, message: error.localizedDescription)
            throw error
        }
    }

    private struct EngineResult: Sendable {
        let model: Model
        let contextTrainSize: Int
        let thinking: Bool
        let tools: Bool
        let vision: Bool
    }

    @InferenceActor
    private static func loadOnEngine(url: URL, filename: String, projector: URL?, template: String?,
                                     progress: @escaping @Sendable (Double) -> Void) throws -> EngineResult {
        LoadSentinel.begin(filename: filename, step: "load")
        defer { LoadSentinel.end() }
        let model = try Engine.loadModel(at: url,
                                         options: LoadOptions(chatTemplateOverride: template),
                                         progress: progress)
        if let projector {
            LoadSentinel.begin(filename: filename, step: "vision")
            // A projector that fails to load leaves the model usable as
            // text-only, as on Android.
            try? model.attachProjector(at: projector)
        }
        return EngineResult(model: model,
                            contextTrainSize: Int(model.contextTrainSize),
                            thinking: model.supportsThinking,
                            tools: model.supportsToolCalling,
                            vision: model.supportsVision)
    }

    func unload() async {
        guard let current = loaded else {
            if case .failed = phase { phase = .idle }
            return
        }
        loaded = nil
        phase = .idle
        await current.model.unload()
    }
}

/// Replacement chat templates for models whose published template is broken
/// (shared with Android in core/resources/chat_templates).
enum ChatTemplateOverrides {

    private static let overrides: [String: String] = {
        guard let url = Bundle.main.url(forResource: "overrides", withExtension: "json", subdirectory: "chat_templates"),
              let data = try? Data(contentsOf: url),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return [:] }
        return json.compactMapValues { $0 as? String }.filter { !$0.key.hasPrefix("_") }
    }()

    static func template(for filename: String) -> String? {
        guard let name = overrides[filename],
              let url = Bundle.main.url(forResource: (name as NSString).deletingPathExtension,
                                        withExtension: "jinja", subdirectory: "chat_templates") else { return nil }
        return try? String(contentsOf: url, encoding: .utf8)
    }

    static var overriddenFilenames: Set<String> { Set(overrides.keys) }
}
