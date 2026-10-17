import Accelerate
import Foundation
import GRDB

/// Document Q&A storage and orchestration (Android: RagRepository): index an
/// attached document (extract → chunk → embed → store) and retrieve the
/// chunks closest to a query.
///
/// The app keeps its own copy of each attached file (the picker's access
/// doesn't last), which indexing reads and the chip previews. Only indexing
/// and ready documents have rows; a failed one is deleted along with its
/// copy, and the failure is reported to the caller.
final class RagStore: Sendable {

    static let defaultTopK = 6
    static let defaultMinScore: Float = 0.35
    /// Chunks per embedding call, so a query embed in another chat waits
    /// one batch rather than the whole document.
    static let embedBatchSize = 16

    let embedder: any TextEmbedder
    private let database: AppDatabase
    private let filesDirectory: URL
    private var writer: any DatabaseWriter { database.writer }

    init(database: AppDatabase, embedder: any TextEmbedder,
         filesDirectory: URL = AppDirectories.applicationSupport.appending(path: "AttachedDocuments", directoryHint: .isDirectory)) {
        self.database = database
        self.embedder = embedder
        self.filesDirectory = filesDirectory
    }

    // MARK: Documents

    func observeDocuments(sessionId: String) -> AsyncValueObservation<[RagDocument]> {
        ValueObservation
            .tracking { db in
                try RagDocument
                    .filter(Column("sessionId") == sessionId)
                    .order(Column("createdAt"))
                    .fetchAll(db)
            }
            .values(in: writer)
    }

    func documents(sessionId: String) throws -> [RagDocument] {
        try writer.read { db in
            try RagDocument.filter(Column("sessionId") == sessionId).order(Column("createdAt")).fetchAll(db)
        }
    }

    func hasReadyDocuments(sessionId: String) throws -> Bool {
        try writer.read { db in
            try RagDocument
                .filter(Column("sessionId") == sessionId && Column("status") == RagDocument.ready)
                .fetchCount(db) > 0
        }
    }

    func fileURL(for document: RagDocument) -> URL? {
        document.storedPath.map { filesDirectory.appending(path: $0) }
    }

    /// Copy `source` into the app and add its row (status indexing, the
    /// chip's spinner). The session row must exist (foreign key).
    func attach(sessionId: String, source: URL, displayName: String, mimeType: String?,
                sizeBytes: Int64) throws -> RagDocument {
        let id = UUID().uuidString
        let folder = filesDirectory.appending(path: id, directoryHint: .isDirectory)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        let name = Self.safeFilename(displayName)
        try FileManager.default.copyItem(at: source, to: folder.appending(path: name))
        let document = RagDocument(
            id: id, sessionId: sessionId, displayName: displayName, mimeType: mimeType,
            storedPath: "\(id)/\(name)", sizeBytes: sizeBytes, status: RagDocument.indexing,
            chunkCount: 0, embeddingDim: 0, embeddingModel: embedder.modelFilename,
            createdAt: Date().millisecondsSince1970)
        do {
            try writer.write { try document.insert($0) }
        } catch {
            try? FileManager.default.removeItem(at: folder)
            throw error
        }
        return document
    }

    func deleteDocument(_ id: String) {
        _ = try? writer.write { try RagDocument.deleteOne($0, key: id) }
        try? FileManager.default.removeItem(at: filesDirectory.appending(path: id, directoryHint: .isDirectory))
    }

    /// App start: drop documents a previous run left mid-indexing, and file
    /// copies whose row is gone (deleted chats cascade their rows).
    func cleanUp() {
        _ = try? writer.write { db in
            try RagDocument.filter(Column("status") == RagDocument.indexing).deleteAll(db)
        }
        sweepOrphanFiles()
    }

    func sweepOrphanFiles() {
        let ids = Set((try? writer.read { try String.fetchAll($0, sql: "SELECT id FROM rag_documents") }) ?? [])
        let folders = (try? FileManager.default.contentsOfDirectory(at: filesDirectory, includingPropertiesForKeys: nil)) ?? []
        for folder in folders where !ids.contains(folder.lastPathComponent) {
            try? FileManager.default.removeItem(at: folder)
        }
    }

    // MARK: Indexing

    /// Extract, chunk, embed and store `document`, then mark it ready. On
    /// failure the document is removed and the reason thrown.
    func index(_ document: RagDocument) async throws(DocumentExtractionError) {
        do {
            let (chunkCount, dim) = try await indexChunks(document)
            _ = try? await writer.write { db in
                try db.execute(sql: "UPDATE rag_documents SET status = ?, chunkCount = ?, embeddingDim = ? WHERE id = ?",
                               arguments: [RagDocument.ready, chunkCount, dim, document.id])
            }
        } catch {
            let removed = (try? await writer.read { try RagDocument.exists($0, key: document.id) }) != true
            deleteDocument(document.id)
            throw removed ? .cancelled : error
        }
    }

    private func indexChunks(_ document: RagDocument) async throws(DocumentExtractionError) -> (Int, Int) {
        guard document.sizeBytes <= DocumentText.maxFileBytes else { throw .tooLarge }
        guard let format = DocumentText.format(mimeType: document.mimeType, displayName: document.displayName) else {
            throw .unsupported
        }
        guard let url = fileURL(for: document) else { throw .parseFailed }
        let result = await Task.detached { () -> Result<[TextChunker.Chunk], DocumentExtractionError> in
            do {
                return .success(TextChunker.chunk(try DocumentText.extract(url, format: format)))
            } catch let error as DocumentExtractionError {
                return .failure(error)
            } catch {
                return .failure(.parseFailed)
            }
        }.value
        let chunks = try result.get()
        if chunks.isEmpty { throw .noText }

        var dim = 0
        var stored = 0
        for start in stride(from: 0, to: chunks.count, by: Self.embedBatchSize) {
            // The document was removed (or its chat deleted) meanwhile.
            guard (try? await writer.read({ try RagDocument.exists($0, key: document.id) })) == true else {
                throw .cancelled
            }
            let batch = Array(chunks[start ..< min(start + Self.embedBatchSize, chunks.count)])
            guard let vectors = await embedder.embedDocumentChunks(batch.map(\.text)),
                  vectors.count == batch.count else {
                throw .embeddingFailed
            }
            dim = vectors.first?.count ?? 0
            do {
                try await writer.write { db in
                    for (chunk, vector) in zip(batch, vectors) {
                        var row = RagChunk(id: nil, documentId: document.id, ordinal: chunk.ordinal,
                                           text: chunk.text, embedding: EmbeddingCodec.encode(vector))
                        try row.insert(db)
                    }
                }
            } catch {
                throw .embeddingFailed
            }
            stored += batch.count
        }
        return (stored, dim)
    }

    // MARK: Retrieval

    /// The top `topK` chunks of the session's ready documents by cosine
    /// similarity (vectors are normalized, so a dot product). Brute force:
    /// a few thousand 768-dim vectors take milliseconds. Empty when nothing
    /// clears `minScore` or the embedder is unavailable.
    func retrieve(sessionId: String, query: String, topK: Int = defaultTopK,
                  minScore: Float = defaultMinScore) async -> [RetrievedChunk] {
        struct Row: FetchableRecord, Decodable {
            let text: String
            let embedding: Data
            let displayName: String
        }
        let rows = (try? await writer.read { db in
            try Row.fetchAll(db, sql: """
                SELECT c.text, c.embedding, d.displayName FROM rag_chunks c
                JOIN rag_documents d ON d.id = c.documentId
                WHERE d.sessionId = ? AND d.status = ?
                """, arguments: [sessionId, RagDocument.ready])
        }) ?? []
        if rows.isEmpty { return [] }
        guard let queryVector = await embedder.embedQuery(query) else { return [] }

        return rows.compactMap { row -> RetrievedChunk? in
            let vector = EmbeddingCodec.decode(row.embedding)
            guard vector.count == queryVector.count else { return nil }
            let score = vDSP.dot(queryVector, vector)
            return score >= minScore ? RetrievedChunk(documentName: row.displayName, text: row.text, score: score) : nil
        }
        .sorted { $0.score > $1.score }
        .prefix(topK)
        .map { $0 }
    }

    private static func safeFilename(_ name: String) -> String {
        let cleaned = name.replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: ":", with: "_")
        return cleaned.isEmpty || cleaned.hasPrefix(".") ? "document" + cleaned : cleaned
    }
}
