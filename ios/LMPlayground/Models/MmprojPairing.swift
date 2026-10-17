import Foundation

/// Pairs a base model GGUF with its multimodal projector (mmproj) GGUF by
/// filename convention, so vision lights up for sideloaded models and for
/// catalog models whose projector is on disk under a slightly different name.
///
/// Port of Android's MmprojPairing.kt. Real-world projector names embed the
/// base model's identity, e.g.
///   gemma-3-4b-it-Q4_K_M.gguf        <- gemma-3-4b-it-mmproj-f16.gguf
///   Qwen_Qwen3.5-0.8B-Q3_K_M.gguf    <- mmproj-Qwen_Qwen3.5-0.8B-f16.gguf
///   gemma-4-E2B_q4_0-it.gguf         <- mmproj-gemma-4-E2B-it-BF16.gguf
/// The quant/precision tags differ between the two files, so matching is done
/// on a normalized identity with the mmproj marker and those tags stripped.
enum MmprojPairing {

    static func isMmproj(_ filename: String) -> Bool {
        filename.localizedCaseInsensitiveContains("mmproj")
    }

    // Quantization tags (q4_0, q3_k_m, iq4_xs, q6_k, q8_0 ...) and float
    // precision tags (f16, f32, bf16, fp16). These vary between a model and
    // its projector, so they're dropped before comparing identities.
    nonisolated(unsafe) private static let drop =
        /i?q\d[0-9a-z]*(_[0-9a-z]+)*|bf16|fp16|f16|f32/.ignoresCase()

    // Below this length a containment match is too loose to trust (a bare
    // "gemma" would latch onto any projector mentioning Gemma). Exact identity
    // matches are accepted at any length.
    private static let minContainmentLength = 4

    /// Collapse a GGUF filename to a comparable model identity.
    static func core(_ filename: String) -> String {
        var name = filename
        if name.lowercased().hasSuffix(".gguf") {
            name.removeLast(5)
        }
        name = name.replacing(/mmproj/.ignoresCase(), with: " ")
        name = name.replacing(drop, with: " ")
        return String(name.lowercased().filter { $0.isLetter || $0.isNumber })
    }

    /// The best matching projector in `candidates` for `modelFilename`, or nil
    /// if none looks like its projector.
    static func findMmproj(for modelFilename: String, in candidates: some Collection<String>) -> String? {
        if isMmproj(modelFilename) { return nil }
        let target = core(modelFilename)
        if target.isEmpty { return nil }

        let projectors = candidates.filter { isMmproj($0) && $0 != modelFilename }

        // 1. Exact identity match wins outright.
        if let exact = projectors.first(where: { core($0) == target }) {
            return exact
        }

        // 2. Otherwise the projector whose identity is contained in (or
        //    contains) the model's, preferring the longest overlap, but only
        //    when both identities are long enough to be meaningful.
        if target.count < minContainmentLength { return nil }
        return projectors
            .map { ($0, core($0)) }
            .filter { _, c in
                c.count >= minContainmentLength && (target.contains(c) || c.contains(target))
            }
            .max { $0.1.count < $1.1.count }?
            .0
    }
}
