import CryptoKit
import Foundation
import LlamaKit

/// Persistent KV cache for the static preamble (system prompt + tools), keyed
/// by model, system prompt and tool set, shared by every conversation that
/// matches. Port of Android's PreambleCacheManager; the engine writes and
/// reads `<path>.bin` / `<path>.json` itself.
enum PreambleCache {

    /// Cache files kept (least recently used pruned).
    static let keep = 8

    static var directory: URL {
        AppDirectories.caches.appending(path: "kv_preamble", directoryHint: .isDirectory)
    }

    @InferenceActor
    static func apply(to session: GenerationSession, modelFilename: String, modelSize: UInt64,
                      systemPrompt: String, toolsJSON: String) {
        // The size is part of the key: a re-quantized file under the same
        // name must not reuse a stale cache.
        let key = "\(modelFilename):\(modelSize) \(systemPrompt) \(toolsJSON)"
        let fingerprint = Insecure.SHA1.hash(data: Data(key.utf8)).map { String(format: "%02x", $0) }.joined()
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        session.setPreambleCache(path: directory.appending(path: fingerprint).path, fingerprint: fingerprint)
        prune()
    }

    private static func prune() {
        let fm = FileManager.default
        let bins = ((try? fm.contentsOfDirectory(at: directory, includingPropertiesForKeys: [.contentModificationDateKey])) ?? [])
            .filter { $0.pathExtension == "bin" }
        guard bins.count > keep else { return }
        let ordered = bins.sorted {
            let a = (try? $0.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast
            let b = (try? $1.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast
            return a > b
        }
        for bin in ordered.dropFirst(keep) {
            try? fm.removeItem(at: bin)
            try? fm.removeItem(at: bin.deletingPathExtension().appendingPathExtension("json"))
        }
    }
}
