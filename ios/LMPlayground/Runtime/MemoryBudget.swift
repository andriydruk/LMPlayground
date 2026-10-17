import Foundation
import LlamaKit

/// Whether a model fits in this process's memory limit before loading it.
///
/// Measured on device (ios/plan/05-platform-research.md): llama.cpp maps the
/// GGUF and Metal wraps those pages without copying, so weights are clean,
/// file-backed memory that barely counts toward the footprint. What counts is
/// the KV cache, the compute buffers and the vision projector, which mtmd
/// loads into RAM.
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

    /// Bytes a load will add to the footprint.
    static func estimate(metadata: ModelMetadata?, contextSize: Int, projectorBytes: UInt64) -> UInt64 {
        let kv = (metadata?.kvBytesPerToken ?? 0) * UInt64(max(contextSize, 0))
        // The projector's weights plus its encoder buffers, measured at ~1.3x.
        let projector = projectorBytes + projectorBytes * 3 / 10
        return kv + projector + headroomBytes
    }

    static func check(metadata: ModelMetadata?, contextSize: Int, projectorBytes: UInt64,
                      available: UInt64 = ProcessMemory.available) -> Verdict {
        let needed = estimate(metadata: metadata, contextSize: contextSize, projectorBytes: projectorBytes)
        if needed > available {
            return .tooLarge(neededMB: needed.megabytes, availableMB: available.megabytes)
        }
        if needed + tightMarginBytes > available {
            return .tight(neededMB: needed.megabytes, availableMB: available.megabytes)
        }
        return .fits
    }

    /// The largest context that fits, for the context-size control.
    static func maxContext(metadata: ModelMetadata?, projectorBytes: UInt64,
                           available: UInt64 = ProcessMemory.available) -> Int? {
        guard let perToken = metadata?.kvBytesPerToken, perToken > 0 else { return nil }
        let fixed = estimate(metadata: nil, contextSize: 0, projectorBytes: projectorBytes) + tightMarginBytes
        guard available > fixed else { return 0 }
        return Int((available - fixed) / perToken)
    }
}
