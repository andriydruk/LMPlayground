import Foundation
import LlamaKit
import Metal

/// Whether a model fits before loading it. Two limits apply (measured on
/// device):
///
/// - The process footprint. llama.cpp maps the GGUF and Metal wraps those
///   pages without copying, so weights are clean, file-backed memory that
///   barely counts. What counts is the KV cache, the compute buffers and the
///   vision projector, which mtmd loads into RAM.
/// - The GPU working set (Metal's recommendedMaxWorkingSetSize). The weights
///   do count here: past it, an 8B model on an iPhone 16 Pro loaded but
///   generated a single garbage token, or the system ran out of pages and
///   killed the app.
enum MemoryBudget {

    enum Verdict: Equatable {
        case fits
        /// Fits, but with little headroom; the app may be killed under pressure.
        case tight(neededMB: Int, availableMB: Int)
        case tooLarge(neededMB: Int, availableMB: Int)
    }

    /// Compute buffers, sampler and app overhead on top of the KV cache.
    static let headroomBytes: UInt64 = 400 << 20
    /// Margin under which a load is "tight".
    static let tightMarginBytes: UInt64 = 500 << 20
    /// Metal compute buffers next to the weights and the KV cache. Calibrated
    /// on an iPhone 16 Pro: Llama 3.1 8B works with a 2K context and fails
    /// with 3K.
    static let gpuComputeBytes: UInt64 = 450 << 20

    /// What the GPU can keep resident on this device; 0 if unknown.
    static let gpuWorkingSet: UInt64 = MTLCreateSystemDefaultDevice()?.recommendedMaxWorkingSetSize ?? 0

    /// Bytes a load will add to the footprint.
    static func estimate(metadata: ModelMetadata?, contextSize: Int, projectorBytes: UInt64) -> UInt64 {
        let kv = (metadata?.kvBytesPerToken ?? 0) * UInt64(max(contextSize, 0))
        // The projector's weights plus its encoder buffers, measured at ~1.3x.
        let projector = projectorBytes + projectorBytes * 3 / 10
        return kv + projector + headroomBytes
    }

    /// Bytes the GPU must hold: weights, KV cache, compute buffers and the
    /// projector. Zero when the weights are unknown.
    static func gpuEstimate(metadata: ModelMetadata?, contextSize: Int, projectorBytes: UInt64) -> UInt64 {
        guard let metadata, metadata.gpuWeightBytes > 0 else { return 0 }
        let kv = metadata.kvBytesPerToken * UInt64(max(contextSize, 0))
        return metadata.gpuWeightBytes + kv + gpuComputeBytes + projectorBytes
    }

    static func check(metadata: ModelMetadata?, contextSize: Int, projectorBytes: UInt64,
                      available: UInt64 = ProcessMemory.available,
                      gpuWorkingSet: UInt64 = gpuWorkingSet) -> Verdict {
        let gpuNeeded = gpuEstimate(metadata: metadata, contextSize: contextSize, projectorBytes: projectorBytes)
        if gpuWorkingSet > 0, gpuNeeded > gpuWorkingSet {
            return .tooLarge(neededMB: gpuNeeded.megabytes, availableMB: gpuWorkingSet.megabytes)
        }
        // No process limit to check against (simulator, Mac).
        guard available > 0 else { return .fits }
        let needed = estimate(metadata: metadata, contextSize: contextSize, projectorBytes: projectorBytes)
        if needed > available {
            return .tooLarge(neededMB: needed.megabytes, availableMB: available.megabytes)
        }
        if needed + tightMarginBytes > available {
            return .tight(neededMB: needed.megabytes, availableMB: available.megabytes)
        }
        return .fits
    }

    /// The largest context that fits both limits, for the context-size control.
    static func maxContext(metadata: ModelMetadata?, projectorBytes: UInt64,
                           available: UInt64 = ProcessMemory.available,
                           gpuWorkingSet: UInt64 = gpuWorkingSet) -> Int? {
        guard let perToken = metadata?.kvBytesPerToken, perToken > 0 else { return nil }
        let fixed = estimate(metadata: nil, contextSize: 0, projectorBytes: projectorBytes) + tightMarginBytes
        var limit = Int.max
        if available > 0 {
            guard available > fixed else { return 0 }
            limit = Int((available - fixed) / perToken)
        }
        let gpuFixed = gpuEstimate(metadata: metadata, contextSize: 0, projectorBytes: projectorBytes)
        if gpuWorkingSet > 0, gpuFixed > 0 {
            guard gpuWorkingSet > gpuFixed else { return 0 }
            limit = min(limit, Int((gpuWorkingSet - gpuFixed) / perToken))
        }
        return limit
    }
}
