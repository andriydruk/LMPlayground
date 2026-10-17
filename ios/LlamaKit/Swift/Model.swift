import Foundation

/// Sampling and context settings. Defaults match Android's session defaults.
public struct SamplerParams: Sendable, Equatable {
    public var contextSize: Int32 = 4096
    public var temperature: Float = 0.8
    public var topP: Float = 0.95
    public var repetitionPenalty: Float = 1.0
    public var topK: Int32 = 40
    public var minP: Float = 0.05
    /// Negative means random.
    public var seed: Int64 = -1
    /// Thinking tokens; -1 means unlimited.
    public var thinkingBudget: Int32 = 1024
    public var systemPrompt: String = ""

    public init() {}

    var native: LMKSamplerParams {
        let p = LMKSamplerParams()
        p.contextSize = contextSize
        p.temperature = temperature
        p.topP = topP
        p.repetitionPenalty = repetitionPenalty
        p.topK = topK
        p.minP = minP
        p.seed = seed
        p.thinkingBudget = thinkingBudget
        p.systemPrompt = systemPrompt
        return p
    }
}

/// A loaded model.
@InferenceActor
public final class Model {
    private let native: LMKModel

    init(native: LMKModel) {
        self.native = native
    }

    public var size: UInt64 { native.modelSize }
    public var contextTrainSize: Int32 { native.contextTrainSize }
    public var report: String { native.modelReport }
    public var supportsThinking: Bool { native.supportsThinking }
    public var supportsToolCalling: Bool { native.supportsToolCalling }
    public var supportsVision: Bool { native.supportsVision }

    /// Attach the multimodal projector (mmproj).
    public func attachProjector(at url: URL) throws {
        try native.attachProjector(atPath: url.path)
    }

    public func makeSession(_ params: SamplerParams = SamplerParams()) throws -> GenerationSession {
        GenerationSession(native: try native.createSession(with: params.native))
    }

    public func makeEmbeddingSession(contextSize: Int32) throws -> EmbeddingSession {
        EmbeddingSession(native: try native.createEmbeddingSession(withContextSize: contextSize))
    }

    /// Free the weights. Destroy this model's sessions first.
    public func unload() {
        native.unload()
    }
}

/// Mean-pooled, L2-normalized text embeddings.
@InferenceActor
public final class EmbeddingSession {
    private let native: LMKEmbeddingSession

    init(native: LMKEmbeddingSession) {
        self.native = native
    }

    public var dimension: Int { Int(native.dimension) }

    public func embed(_ text: String) throws -> [Float] {
        let data = try native.embedText(text)
        return data.withUnsafeBytes { Array($0.bindMemory(to: Float.self)) }
    }

    public func destroy() {
        native.destroy()
    }
}
