import Foundation
import LlamaKit

/// What RagStore needs from an embedder; tests substitute a deterministic one.
protocol TextEmbedder: Sendable {
    var modelFilename: String { get }
    /// Whether the embedding model's GGUF is in the models folder.
    func isModelOnDisk() -> Bool
    /// One L2-normalized vector per chunk (document task prefix), or nil.
    func embedDocumentChunks(_ chunks: [String]) async -> [[Float]]?
    /// An L2-normalized query vector (query task prefix), or nil.
    func embedQuery(_ query: String) async -> [Float]?
}

/// Owns EmbeddingGemma's lifecycle and task prefixes (Android:
/// EmbeddingModelManager). Loaded on first use, kept warm for a minute after
/// the last call, then unloaded: ~300 MB shouldn't sit next to a chat model
/// for the whole session. Runs on the inference actor, so an embed waits for
/// a reply in progress and vice versa.
@InferenceActor
final class EmbeddingModelManager: TextEmbedder {

    /// EmbeddingGemma's training context; chunks are far smaller.
    static let contextSize: Int32 = 2048
    /// Keep-warm window after the last embed call.
    static let idleUnload: Duration = .seconds(60)

    nonisolated let modelFilename = ModelCatalog.embeddingModel.filename
    nonisolated private let url: URL

    private var model: Model?
    private var session: EmbeddingSession?
    private var lastUse = 0

    nonisolated init(directory: URL = AppDirectories.models) {
        url = directory.appending(path: ModelCatalog.embeddingModel.filename)
    }

    nonisolated func isModelOnDisk() -> Bool {
        FileManager.default.fileExists(atPath: url.path)
    }

    func embedDocumentChunks(_ chunks: [String]) async -> [[Float]]? {
        withSession { session in
            try chunks.map { try session.embed("title: none | text: \($0)") }
        }
    }

    func embedQuery(_ query: String) async -> [Float]? {
        withSession { session in
            try session.embed("task: search result | query: \(query)")
        }
    }

    var isLoaded: Bool { session != nil }

    func unload() {
        session?.destroy()
        session = nil
        model?.unload()
        model = nil
    }

    private func withSession<T>(_ body: (EmbeddingSession) throws -> T) -> T? {
        defer { scheduleIdleUnload() }
        guard let session = ensureSession() else { return nil }
        do {
            return try body(session)
        } catch {
            // The session may be in an undefined state: drop it, the next
            // call reloads.
            unload()
            return nil
        }
    }

    private func ensureSession() -> EmbeddingSession? {
        if let session { return session }
        guard isModelOnDisk() else { return nil }
        do {
            let loaded = try Engine.loadModel(at: url)
            do {
                let created = try loaded.makeEmbeddingSession(contextSize: Self.contextSize)
                model = loaded
                session = created
                return created
            } catch {
                loaded.unload()
                return nil
            }
        } catch {
            return nil
        }
    }

    private func scheduleIdleUnload() {
        lastUse += 1
        let mark = lastUse
        guard session != nil else { return }
        Task { @InferenceActor [weak self] in
            try? await Task.sleep(for: Self.idleUnload)
            // A later use moved the mark: it scheduled its own unload.
            guard let self, self.lastUse == mark else { return }
            self.unload()
        }
    }
}
