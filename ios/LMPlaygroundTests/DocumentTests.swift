import Compression
import Foundation
import GRDB
import Testing
@testable import LMPlayground

/// Ports of the Android RAG tests (TextChunkerTest, RagPromptBuilderTest,
/// DocumentTextExtractorsTest, RagRepositoryTest).
struct TextChunkerTests {

    @Test func emptyInputProducesNoChunks() {
        #expect(TextChunker.chunk("").isEmpty)
        #expect(TextChunker.chunk("  \n\n \n").isEmpty)
    }

    @Test func shortTextIsOneChunk() {
        let chunks = TextChunker.chunk("Hello world.")
        #expect(chunks == [TextChunker.Chunk(text: "Hello world.", ordinal: 0)])
    }

    @Test func smallParagraphsMerge() {
        let chunks = TextChunker.chunk("First paragraph.\n\nSecond paragraph.\n\nThird.")
        #expect(chunks.count == 1)
        #expect(chunks[0].text == "First paragraph.\nSecond paragraph.\nThird.")
    }

    @Test func longTextSplitsWithSequentialOrdinals() {
        let paragraph = String(repeating: "This is a sentence that fills space. ", count: 10)
            .trimmingCharacters(in: .whitespaces)
        let text = Array(repeating: paragraph, count: 10).joined(separator: "\n\n")
        let chunks = TextChunker.chunk(text, targetChars: 500, overlapChars: 100, maxChars: 800)
        #expect(chunks.count > 1)
        #expect(chunks.map(\.ordinal) == Array(0..<chunks.count))
    }

    @Test func chunksStayWithinTheHardBound() {
        let text = String(repeating: "a", count: 20_000)
        let chunks = TextChunker.chunk(text)
        #expect(chunks.allSatisfy { $0.text.count <= 150 + 1600 + 1 })
        #expect(chunks.reduce(0) { $0 + $1.text.count } >= text.count)
    }

    @Test func consecutiveChunksOverlap() {
        let text = String(repeating: "The quick brown fox jumps over the lazy dog near the river bank. ", count: 60)
            .trimmingCharacters(in: .whitespaces)
        let chunks = TextChunker.chunk(text, targetChars: 600, overlapChars: 120, maxChars: 900)
        #expect(chunks.count > 1)
        for i in 1..<chunks.count {
            let head = chunks[i].text.split(separator: "\n", maxSplits: 1).first.map(String.init) ?? ""
            #expect(!head.isEmpty && chunks[i - 1].text.hasSuffix(head))
        }
    }

    @Test func oversizedParagraphSplitsAtSentences() {
        let text = (1...40).map { "Sentence number \($0) is here." }.joined(separator: " ")
        let chunks = TextChunker.chunk(text, targetChars: 300, overlapChars: 50, maxChars: 400)
        #expect(chunks.count > 1)
        for chunk in chunks.dropLast() {
            #expect(chunk.text.trimmingCharacters(in: .whitespaces).hasSuffix("."))
        }
    }

    @Test func multilingualTextChunks() {
        let text = String(repeating: "Це перше речення. Це друге речення довше за перше. ", count: 30)
        let chunks = TextChunker.chunk(text, targetChars: 400, overlapChars: 80, maxChars: 600)
        #expect(!chunks.isEmpty)
        #expect(chunks.allSatisfy { !$0.text.trimmingCharacters(in: .whitespaces).isEmpty })
    }
}

struct RagPromptBuilderTests {

    private func chunk(_ text: String, _ score: Float, name: String = "doc.pdf") -> RetrievedChunk {
        RetrievedChunk(documentName: name, text: text, score: score)
    }

    @Test func noChunksOrBudgetReturnsTheText() {
        #expect(RagPromptBuilder.build(userText: "Q?", chunks: [], maxContextChars: 1000) == "Q?")
        #expect(RagPromptBuilder.build(userText: "Q?", chunks: [chunk("x", 0.9)], maxContextChars: 0) == "Q?")
    }

    @Test func wrapsExcerptsAndKeepsTheQuestionLast() {
        let wire = RagPromptBuilder.build(userText: "What colour?", chunks: [chunk("The sky is blue.", 0.9),
                                                                           chunk("Grass is green.", 0.8)],
                                          maxContextChars: 1000)
        #expect(wire.contains("[Source: doc.pdf — excerpt 1]\nThe sky is blue."))
        #expect(wire.contains("[Source: doc.pdf — excerpt 2]\nGrass is green."))
        #expect(wire.hasSuffix("Question: What colour?"))
    }

    @Test func budgetDropsTheLowestScoring() {
        let wire = RagPromptBuilder.build(userText: "Q", chunks: [chunk(String(repeating: "a", count: 100), 0.9),
                                                                chunk(String(repeating: "b", count: 100), 0.5)],
                                          maxContextChars: 160)
        #expect(wire.contains("aaaa"))
        #expect(!wire.contains("bbbb"))
    }

    @Test func budgetHelperCaps() {
        #expect(RagPromptBuilder.budgetChars(contextSize: 4096) == 4096)
        #expect(RagPromptBuilder.budgetChars(contextSize: 32768) == 8192)
    }
}

/// A ZIP written in memory: stored or raw-DEFLATE entries, CRCs left zero
/// (the reader doesn't check them).
func makeZip(_ entries: [(String, String)], deflate: Bool = true) -> Data {
    func le16(_ v: Int) -> [UInt8] { [UInt8(v & 0xff), UInt8((v >> 8) & 0xff)] }
    func le32(_ v: Int) -> [UInt8] { (0..<4).map { UInt8((v >> ($0 * 8)) & 0xff) } }
    var out = [UInt8]()
    var central = [UInt8]()
    for (name, text) in entries {
        let raw = Array(text.utf8)
        var payload = raw
        var method = 0
        if deflate, !raw.isEmpty {
            var buffer = [UInt8](repeating: 0, count: raw.count + 1024)
            let n = compression_encode_buffer(&buffer, buffer.count, raw, raw.count, nil, COMPRESSION_ZLIB)
            if n > 0 {
                payload = Array(buffer.prefix(n))
                method = 8
            }
        }
        let nameBytes = Array(name.utf8)
        let offset = out.count
        out += le32(0x0403_4b50) + le16(20) + le16(0) + le16(method) + le16(0) + le16(0) + le32(0)
            + le32(payload.count) + le32(raw.count) + le16(nameBytes.count) + le16(0) + nameBytes + payload
        central += le32(0x0201_4b50) + le16(20) + le16(20) + le16(0) + le16(method) + le16(0) + le16(0) + le32(0)
            + le32(payload.count) + le32(raw.count) + le16(nameBytes.count) + le16(0) + le16(0) + le16(0) + le16(0)
            + le32(0) + le32(offset) + nameBytes
    }
    let centralOffset = out.count
    out += central
    out += le32(0x0605_4b50) + le16(0) + le16(0) + le16(entries.count) + le16(entries.count)
        + le32(central.count) + le32(centralOffset) + le16(0)
    return Data(out)
}

struct DocumentExtractorTests {

    private func write(_ name: String, _ data: Data) throws -> URL {
        let url = FileManager.default.temporaryDirectory.appending(path: UUID().uuidString + "-" + name)
        try data.write(to: url)
        return url
    }

    private func docxXml(_ body: String) -> String {
        """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>\(body)</w:body></w:document>
        """
    }

    @Test func routesByMimeOrExtension() {
        #expect(DocumentText.format(mimeType: "application/pdf", displayName: "x") == .pdf)
        #expect(DocumentText.format(mimeType: nil, displayName: "Report.PDF") == .pdf)
        #expect(DocumentText.format(mimeType: nil, displayName: "a.docx") == .docx)
        #expect(DocumentText.format(mimeType: "application/epub+zip", displayName: "b") == .epub)
        #expect(DocumentText.format(mimeType: nil, displayName: "page.htm") == .html)
        #expect(DocumentText.format(mimeType: "text/markdown", displayName: "c") == .plainText)
        #expect(DocumentText.format(mimeType: "application/octet-stream", displayName: "d.bin") == nil)
    }

    @Test func zipReadsStoredAndDeflatedEntries() throws {
        let text = String(repeating: "hello zip ", count: 200)
        for deflate in [false, true] {
            let zip = try ZipArchive(data: makeZip([("a/b.txt", text), ("empty", "")], deflate: deflate))
            #expect(zip.entries.map(\.name) == ["a/b.txt", "empty"])
            #expect(String(decoding: try zip.contents(of: zip.entries[0]), as: UTF8.self) == text)
            #expect(try zip.contents(of: zip.entries[1]).isEmpty)
        }
        #expect(throws: ZipArchive.ZipError.self) { try ZipArchive(data: Data("not a zip at all, sorry".utf8)) }
    }

    @Test func docxExtractsRunsParagraphsAndTabs() throws {
        let url = try write("doc.docx", makeZip([
            ("[Content_Types].xml", "<Types/>"),
            ("word/document.xml", docxXml(
                #"<w:p><w:r><w:t>Hello</w:t></w:r><w:r><w:t xml:space="preserve"> world</w:t></w:r></w:p>"#
                    + "<w:p><w:r><w:tab/><w:t>Indented second</w:t></w:r></w:p>")),
        ]))
        let text = try DocumentText.extract(url, format: .docx)
        #expect(text.contains("Hello world\n\tIndented second"))
    }

    @Test func docxWithoutDocumentXmlFails() throws {
        let url = try write("broken.docx", makeZip([("word/other.xml", "<x/>")]))
        #expect(throws: DocumentExtractionError.parseFailed) { try DocumentText.extract(url, format: .docx) }
    }

    @Test func epubExtractsChaptersAndSkipsNav() throws {
        let url = try write("book.epub", makeZip([
            ("mimetype", "application/epub+zip"),
            ("OEBPS/nav.xhtml", "<html><body><p>SKIP_NAV</p></body></html>"),
            ("OEBPS/chapter1.xhtml", "<html><body><h1>Chapter One</h1><p>It was a dark night.</p></body></html>"),
            ("OEBPS/chapter2.xhtml", "<html><body><p>The morning came.</p></body></html>"),
        ]))
        let text = try DocumentText.extract(url, format: .epub)
        #expect(text.contains("# Chapter One"))
        #expect(text.contains("It was a dark night."))
        #expect(text.contains("The morning came."))
        #expect(!text.contains("SKIP_NAV"))
    }

    @Test func htmlAndPlainText() throws {
        let html = try write("p.html", Data("<html><body><h2>Title</h2><p>Body text.</p><script>x()</script></body></html>".utf8))
        let markdown = try DocumentText.extract(html, format: .html)
        #expect(markdown.contains("## Title"))
        #expect(!markdown.contains("x()"))
        let plain = try write("n.txt", Data("Plain notes\nline two".utf8))
        #expect(try DocumentText.extract(plain, format: .plainText) == "Plain notes\nline two")
    }

    @Test func textStarvedHeuristic() {
        #expect(DocumentText.isPdfTextStarved(extractedChars: 90, pages: 13))
        #expect(!DocumentText.isPdfTextStarved(extractedChars: 13 * 1500, pages: 13))
    }

    @Test func garbagePdfFails() throws {
        let url = try write("x.pdf", Data("definitely not a pdf".utf8))
        #expect(throws: DocumentExtractionError.parseFailed) { try DocumentText.extract(url, format: .pdf) }
    }
}

/// Deterministic embedder: a 4-dim normalized vector from marker words, so
/// texts sharing markers score higher (Android: FakeEmbedder).
final class FakeEmbedder: TextEmbedder, @unchecked Sendable {
    let modelFilename = "fake-embedder.gguf"
    var failEmbeds = false

    func isModelOnDisk() -> Bool { true }

    func embedDocumentChunks(_ chunks: [String]) async -> [[Float]]? {
        failEmbeds ? nil : chunks.map(embed)
    }

    func embedQuery(_ query: String) async -> [Float]? {
        failEmbeds ? nil : embed(query)
    }

    private func embed(_ text: String) -> [Float] {
        let lower = text.lowercased()
        let vector: [Float] = [
            1,
            lower.contains("cat") || lower.contains("kitten") ? 3 : 0,
            lower.contains("engine") || lower.contains("carburetor") ? 3 : 0,
            lower.contains("revenue") || lower.contains("profit") ? 3 : 0,
        ]
        let norm = vector.reduce(0) { $0 + $1 * $1 }.squareRoot()
        return vector.map { $0 / norm }
    }
}

struct RagStoreTests {
    let database: AppDatabase
    let chats: ChatStore
    let embedder = FakeEmbedder()
    let rag: RagStore
    let files: URL

    init() throws {
        database = try AppDatabase.makeEmpty()
        chats = ChatStore(database: database)
        files = FileManager.default.temporaryDirectory.appending(path: "rag-\(UUID().uuidString)")
        rag = RagStore(database: database, embedder: embedder, filesDirectory: files)
    }

    private func session() throws -> String {
        try chats.createSession(title: "t", model: nil, params: GenerationParams(), systemPrompt: "").id
    }

    private func attach(_ sessionId: String, _ name: String, _ content: String,
                        mime: String? = "text/plain") throws -> RagDocument {
        let url = FileManager.default.temporaryDirectory.appending(path: UUID().uuidString + "-" + name)
        try Data(content.utf8).write(to: url)
        return try rag.attach(sessionId: sessionId, source: url, displayName: name, mimeType: mime,
                              sizeBytes: Int64(content.utf8.count))
    }

    private func chunkCount() throws -> Int {
        try database.writer.read { try RagChunk.fetchCount($0) }
    }

    @Test func indexesATextDocumentToReady() async throws {
        let id = try session()
        let document = try attach(id, "notes.txt", "The cat sat on the mat.")
        #expect(document.status == RagDocument.indexing)
        #expect(try !rag.hasReadyDocuments(sessionId: id))
        try await rag.index(document)
        let stored = try #require(try rag.documents(sessionId: id).first)
        #expect(stored.isReady && stored.chunkCount == 1 && stored.embeddingDim == 4)
        #expect(try rag.hasReadyDocuments(sessionId: id))
        // The app's copy is what the chip previews.
        let copy = try #require(rag.fileURL(for: stored))
        #expect(try String(contentsOf: copy, encoding: .utf8) == "The cat sat on the mat.")
    }

    @Test func retrieveRanksAndAppliesMinScore() async throws {
        let id = try session()
        let text = (0..<3).map { _ in
            [String(repeating: "The cat chased a kitten around the house. ", count: 12),
             String(repeating: "The engine needs a new carburetor this year. ", count: 12),
             String(repeating: "Quarterly revenue and profit both grew. ", count: 12)]
                .joined(separator: "\n\n")
        }.joined(separator: "\n\n")
        try await rag.index(try attach(id, "mixed.txt", text))

        let results = await rag.retrieve(sessionId: id, query: "why does my cat like kittens", topK: 2, minScore: 0.5)
        #expect(!results.isEmpty)
        #expect(results[0].text.contains("cat"))
        #expect(results[0].documentName == "mixed.txt")
        #expect(zip(results, results.dropFirst()).allSatisfy { $0.score >= $1.score })
        // Normalized vectors never score above 1.
        #expect(await rag.retrieve(sessionId: id, query: "cat", minScore: 1.01).isEmpty)
    }

    @Test func retrievalIsScopedToTheSession() async throws {
        let a = try session()
        let b = try session()
        try await rag.index(try attach(a, "a.txt", "The cat document."))
        #expect(try !rag.hasReadyDocuments(sessionId: b))
        #expect(await rag.retrieve(sessionId: b, query: "cat").isEmpty)
    }

    @Test func failuresDeleteTheDocumentAndItsCopy() async throws {
        let id = try session()
        let unsupported = try attach(id, "data.bin", "binary-ish", mime: "application/octet-stream")
        await #expect(throws: DocumentExtractionError.unsupported) { try await rag.index(unsupported) }

        embedder.failEmbeds = true
        let failing = try attach(id, "notes.txt", "Some text.")
        await #expect(throws: DocumentExtractionError.embeddingFailed) { try await rag.index(failing) }

        #expect(try rag.documents(sessionId: id).isEmpty)
        let left = (try? FileManager.default.contentsOfDirectory(atPath: files.path)) ?? []
        #expect(left.isEmpty)
    }

    @Test func removedWhileIndexingIsCancelled() async throws {
        let id = try session()
        let document = try attach(id, "notes.txt", "Some text.")
        rag.deleteDocument(document.id)
        await #expect(throws: DocumentExtractionError.cancelled) { try await rag.index(document) }
    }

    @Test func deletingTheChatCascadesAndSweepsFiles() async throws {
        let id = try session()
        try await rag.index(try attach(id, "a.txt", "The cat document."))
        #expect(try chunkCount() == 1)
        try chats.deleteSession(id)
        #expect(try chunkCount() == 0)
        #expect(try rag.documents(sessionId: id).isEmpty)
        rag.sweepOrphanFiles()
        #expect(((try? FileManager.default.contentsOfDirectory(atPath: files.path)) ?? []).isEmpty)
    }

    @Test func staleIndexingIsDroppedAtStartup() async throws {
        let id = try session()
        _ = try attach(id, "stale.txt", "Never indexed.")
        let ready = try attach(id, "ready.txt", "Indexed.")
        try await rag.index(ready)
        rag.cleanUp()
        #expect(try rag.documents(sessionId: id).map(\.displayName) == ["ready.txt"])
    }

    @Test func embeddingCodecRoundTrips() {
        let vector: [Float] = [0, 1, -1.5, .pi, 1e-7]
        #expect(EmbeddingCodec.decode(EmbeddingCodec.encode(vector)) == vector)
        // Little-endian float32, as Android stores it.
        #expect(Array(EmbeddingCodec.encode([1])) == [0x00, 0x00, 0x80, 0x3f])
    }
}

struct MigrationTests {

    /// A database created by a v1 build keeps its chats when v2 adds the
    /// document tables.
    @Test func v1DatabaseMigratesToV2() throws {
        let queue = try DatabaseQueue()
        try AppDatabase.migrator.migrate(queue, upTo: "v1")
        try queue.write { db in
            try db.execute(sql: """
                INSERT INTO chat_sessions (id, title, modelFilename, modelName, createdAt, updatedAt)
                VALUES ('s1', 'Old chat', 'm.gguf', 'M', 1, 1)
                """)
            try db.execute(sql: """
                INSERT INTO chat_messages (sessionId, author, content, timestamp) VALUES ('s1', 'User', 'hi', 1)
                """)
        }

        let database = try AppDatabase(queue)
        let chats = ChatStore(database: database)
        #expect(try chats.session("s1")?.title == "Old chat")
        #expect(try chats.messages(in: "s1").map(\.content) == ["hi"])
        let applied = try queue.read { try AppDatabase.migrator.appliedIdentifiers($0) }
        #expect(applied.contains("v2"))

        let rag = RagStore(database: database, embedder: FakeEmbedder(),
                           filesDirectory: FileManager.default.temporaryDirectory.appending(path: UUID().uuidString))
        let url = FileManager.default.temporaryDirectory.appending(path: UUID().uuidString + ".txt")
        try Data("cat".utf8).write(to: url)
        let document = try rag.attach(sessionId: "s1", source: url, displayName: "a.txt", mimeType: "text/plain", sizeBytes: 3)
        #expect(try rag.documents(sessionId: "s1").map(\.id) == [document.id])
    }
}
