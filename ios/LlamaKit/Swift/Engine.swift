import Foundation

/// The one thread the engine runs on. Every chat and embedding call goes
/// through it, like Android's single GenerationWorker: model loads, prompt
/// evaluation and token sampling never overlap. Only `abort()` is callable
/// from elsewhere.
@globalActor
public actor InferenceActor {
    public static let shared = InferenceActor()

    private let queue = DispatchSerialQueue(label: "com.druk.lmplayground.inference",
                                            qos: .userInitiated)

    public nonisolated var unownedExecutor: UnownedSerialExecutor {
        queue.asUnownedSerialExecutor()
    }
}

public struct ModelMetadata: Sendable, Equatable {
    public let name: String
    public let hasChatTemplate: Bool
    /// general.architecture, e.g. "qwen35"; "clip" for a vision projector.
    public let architecture: String
    /// Trained context length; 0 if undeclared.
    public let contextLength: Int
    /// Upper bound on f16 KV-cache bytes per context token; 0 if unknown.
    public let kvBytesPerToken: UInt64
    /// Weights the GPU holds with every layer offloaded.
    public let gpuWeightBytes: UInt64
    public let isProjector: Bool

    public init(name: String, hasChatTemplate: Bool, architecture: String, contextLength: Int,
                kvBytesPerToken: UInt64, gpuWeightBytes: UInt64, isProjector: Bool) {
        self.name = name
        self.hasChatTemplate = hasChatTemplate
        self.architecture = architecture
        self.contextLength = contextLength
        self.kvBytesPerToken = kvBytesPerToken
        self.gpuWeightBytes = gpuWeightBytes
        self.isProjector = isProjector
    }
}

public struct LoadOptions: Sendable {
    /// Layers offloaded to the GPU: -1 for all (Metal), 0 for none.
    public var gpuLayers: Int32
    /// Keep quantized weights memory-mapped instead of repacking them into
    /// RAM. Only matters for layers that run on the CPU.
    public var disableRepack: Bool
    /// Replaces the GGUF's embedded chat template when set.
    public var chatTemplateOverride: String?

    public init(gpuLayers: Int32 = Engine.defaultGPULayers,
                disableRepack: Bool = false,
                chatTemplateOverride: String? = nil) {
        self.gpuLayers = gpuLayers
        self.disableRepack = disableRepack
        self.chatTemplateOverride = chatTemplateOverride
    }
}

public enum Engine {

    /// Full Metal offload on hardware; the simulator's Metal cannot run these
    /// graphs, so it stays on the CPU.
    public static var defaultGPULayers: Int32 {
        #if targetEnvironment(simulator)
        0
        #else
        -1
        #endif
    }

    @InferenceActor private static var initialized = false

    /// One-time setup. `stateDirectory` keeps the crash-sentinel markers.
    @InferenceActor
    public static func initialize(stateDirectory: URL) {
        guard !initialized else { return }
        try? FileManager.default.createDirectory(at: stateDirectory, withIntermediateDirectories: true)
        #if targetEnvironment(simulator)
        let vision = LMKVisionDevice.CPU
        #else
        let vision = LMKVisionDevice.automatic
        #endif
        LMKEngine.initialize(withStateDirectory: stateDirectory.path, visionDevice: vision)
        initialized = true
    }

    /// Backend and CPU features, for diagnostics.
    public static var systemInfo: String { LMKEngine.systemInfo }

    /// Read a GGUF header without loading tensors. Nil if it isn't GGUF.
    public static func probe(_ url: URL) -> ModelMetadata? {
        guard let meta = LMKEngine.probeModel(atPath: url.path) else { return nil }
        return ModelMetadata(name: meta.name,
                             hasChatTemplate: meta.hasChatTemplate,
                             architecture: meta.architecture,
                             contextLength: Int(meta.contextLength),
                             kvBytesPerToken: meta.kvBytesPerToken,
                             gpuWeightBytes: meta.gpuWeightBytes,
                             isProjector: meta.isProjector)
    }

    @InferenceActor
    public static func loadModel(at url: URL,
                                 options: LoadOptions = LoadOptions(),
                                 progress: ((Double) -> Void)? = nil) throws -> Model {
        let native = try LMKEngine.loadModel(atPath: url.path,
                                             gpuLayers: options.gpuLayers,
                                             disableRepack: options.disableRepack,
                                             chatTemplateOverride: options.chatTemplateOverride,
                                             progress: { value in progress?(Double(value)) })
        return Model(native: native)
    }
}
