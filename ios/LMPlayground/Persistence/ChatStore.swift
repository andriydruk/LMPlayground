import Foundation
import GRDB

/// Conversations, messages and system prompts (Android: ChatSessionStore,
/// ChatRepository, SystemPromptRepository).
final class ChatStore: Sendable {

    private let database: AppDatabase
    private var writer: any DatabaseWriter { database.writer }

    init(database: AppDatabase) {
        self.database = database
    }

    // MARK: - Sessions

    /// Pinned first, then most recently updated.
    func observeSessions() -> AsyncValueObservation<[ChatSession]> {
        ValueObservation
            .tracking { db in
                try ChatSession
                    .order(Column("pinned").desc, Column("updatedAt").desc)
                    .fetchAll(db)
            }
            .values(in: writer)
    }

    func session(_ id: String) throws -> ChatSession? {
        try writer.read { try ChatSession.fetchOne($0, key: id) }
    }

    func createSession(title: String, model: ModelDescriptor?, params: GenerationParams,
                       systemPrompt: String) throws -> ChatSession {
        let now = Date().millisecondsSince1970
        var session = ChatSession(
            id: UUID().uuidString, title: String(title.prefix(50)),
            modelFilename: model?.filename ?? "", modelName: model?.name ?? "Unknown",
            createdAt: now, updatedAt: now, pinned: false,
            contextSize: 0, temperature: 0, topP: 0, repetitionPenalty: 0, topK: 0, minP: 0,
            seed: 0, thinkingBudget: 0, systemPrompt: systemPrompt, metadata: "{}")
        session.params = params
        try writer.write { try session.insert($0) }
        return session
    }

    func updateSession(_ id: String, _ change: @escaping @Sendable (inout ChatSession) -> Void) throws {
        try writer.write { db in
            guard var session = try ChatSession.fetchOne(db, key: id) else { return }
            change(&session)
            try session.update(db)
        }
    }

    func touchSession(_ id: String) throws {
        try updateSession(id) { $0.updatedAt = Date().millisecondsSince1970 }
    }

    func renameSession(_ id: String, title: String) throws {
        try updateSession(id) { $0.title = title }
    }

    func pinSession(_ id: String, pinned: Bool) throws {
        try updateSession(id) { $0.pinned = pinned }
    }

    /// Deletes the session; its messages go with it (foreign-key cascade).
    /// Returns the image files the session's messages referenced.
    @discardableResult
    func deleteSession(_ id: String) throws -> [String] {
        try writer.write { db in
            let images = try String.fetchAll(db, sql: """
                SELECT imagePath FROM chat_messages WHERE sessionId = ? AND imagePath IS NOT NULL
                """, arguments: [id])
            _ = try ChatSession.deleteOne(db, key: id)
            return images
        }
    }

    // MARK: - Messages

    func messages(in sessionId: String) throws -> [ChatMessageRecord] {
        try writer.read { db in
            try ChatMessageRecord
                .filter(Column("sessionId") == sessionId)
                .order(Column("timestamp"), Column("id"))
                .fetchAll(db)
        }
    }

    @discardableResult
    func insert(_ message: ChatMessageRecord) throws -> ChatMessageRecord {
        var message = message
        try writer.write { db in
            try message.insert(db)
            try db.execute(sql: "UPDATE chat_sessions SET updatedAt = ? WHERE id = ?",
                           arguments: [Date().millisecondsSince1970, message.sessionId])
        }
        return message
    }

    func update(_ message: ChatMessageRecord) throws {
        try writer.write { try message.update($0) }
    }

    func deleteMessage(_ id: Int64) throws {
        try writer.write { _ = try ChatMessageRecord.deleteOne($0, key: id) }
    }

    // MARK: - System prompts

    /// Most recently edited first.
    func observePrompts() -> AsyncValueObservation<[SystemPrompt]> {
        ValueObservation
            .tracking { db in
                try SystemPrompt
                    .order(sql: "MAX(updatedAt, createdAt) DESC")
                    .fetchAll(db)
            }
            .values(in: writer)
    }

    /// Prompts ordered by when they were last used with `modelFilename`,
    /// then by recency (Android: recentSystemPromptsForModel).
    func prompts(recentFor modelFilename: String?) throws -> [SystemPrompt] {
        try writer.read { db in
            try SystemPrompt.fetchAll(db, sql: """
                SELECT p.* FROM system_prompts p
                LEFT JOIN prompt_usage u ON u.promptId = p.id AND u.modelFilename = ?
                ORDER BY COALESCE(u.lastUsedAt, 0) DESC, MAX(p.updatedAt, p.createdAt) DESC
                """, arguments: [modelFilename ?? ""])
        }
    }

    func createPrompt(text: String) throws -> SystemPrompt? {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }
        return try writer.write { db in
            if let existing = try SystemPrompt.filter(Column("text") == trimmed).fetchOne(db) {
                return existing
            }
            let now = Date().millisecondsSince1970
            let prompt = SystemPrompt(id: UUID().uuidString, text: trimmed, createdAt: now, updatedAt: now)
            try prompt.insert(db)
            return prompt
        }
    }

    func updatePrompt(_ id: String, text: String) throws {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        try writer.write { db in
            try db.execute(sql: "UPDATE system_prompts SET text = ?, updatedAt = ? WHERE id = ?",
                           arguments: [trimmed, Date().millisecondsSince1970, id])
        }
    }

    func deletePrompt(_ id: String) throws {
        try writer.write { _ = try SystemPrompt.deleteOne($0, key: id) }
    }

    func touchPromptUsage(promptId: String, modelFilename: String) throws {
        try writer.write { db in
            try PromptUsage(promptId: promptId, modelFilename: modelFilename,
                            lastUsedAt: Date().millisecondsSince1970).save(db)
        }
    }
}
