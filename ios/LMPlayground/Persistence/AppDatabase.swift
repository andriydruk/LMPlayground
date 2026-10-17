import Foundation
import GRDB

/// The app's SQLite database. Tables mirror Android's Room schema (v8) so the
/// two apps store conversations the same way; see
/// ios/plan/04-architecture.md, "Persistence".
final class AppDatabase: Sendable {

    let writer: any DatabaseWriter

    init(_ writer: any DatabaseWriter) throws {
        self.writer = writer
        try Self.migrator.migrate(writer)
    }

    /// The on-disk database in Application Support.
    static func makeShared() throws -> AppDatabase {
        let url = AppDirectories.applicationSupport.appending(path: "lmplayground.sqlite")
        var config = Configuration()
        config.foreignKeysEnabled = true
        return try AppDatabase(DatabasePool(path: url.path, configuration: config))
    }

    /// An empty in-memory database, for tests and previews.
    static func makeEmpty() throws -> AppDatabase {
        var config = Configuration()
        config.foreignKeysEnabled = true
        return try AppDatabase(DatabaseQueue(configuration: config))
    }

    private static var migrator: DatabaseMigrator {
        var migrator = DatabaseMigrator()
        #if DEBUG
        migrator.eraseDatabaseOnSchemaChange = false
        #endif

        migrator.registerMigration("v1") { db in
            try db.create(table: "chat_sessions") { t in
                t.primaryKey("id", .text)
                t.column("title", .text).notNull()
                t.column("modelFilename", .text).notNull()
                t.column("modelName", .text).notNull()
                t.column("createdAt", .integer).notNull()
                t.column("updatedAt", .integer).notNull().indexed()
                t.column("pinned", .boolean).notNull().defaults(to: false)
                t.column("contextSize", .integer).notNull().defaults(to: 4096)
                t.column("temperature", .double).notNull().defaults(to: 0.8)
                t.column("topP", .double).notNull().defaults(to: 0.95)
                t.column("repetitionPenalty", .double).notNull().defaults(to: 1.0)
                t.column("topK", .integer).notNull().defaults(to: 40)
                t.column("minP", .double).notNull().defaults(to: 0.05)
                t.column("seed", .integer).notNull().defaults(to: -1)
                t.column("thinkingBudget", .integer).notNull().defaults(to: 1024)
                t.column("systemPrompt", .text).notNull().defaults(to: "")
                t.column("metadata", .text).notNull().defaults(to: "{}")
            }

            try db.create(table: "chat_messages") { t in
                t.autoIncrementedPrimaryKey("id")
                t.belongsTo("session", inTable: "chat_sessions", onDelete: .cascade)
                    .notNull()
                t.column("author", .text).notNull()
                t.column("content", .text).notNull()
                t.column("thinkingDurationSeconds", .integer).notNull().defaults(to: 0)
                t.column("thinkingTokens", .integer).notNull().defaults(to: 0)
                t.column("responseTokens", .integer).notNull().defaults(to: 0)
                t.column("responseDurationSeconds", .double).notNull().defaults(to: 0)
                t.column("timestamp", .integer).notNull()
                t.column("imagePath", .text)
                // iOS additions: a reply cut short by backgrounding or Stop,
                // and the tool rounds of a reply (JSON array of ToolCallInfo).
                t.column("interrupted", .boolean).notNull().defaults(to: false)
                t.column("toolCalls", .text)
            }
            try db.create(index: "chat_messages_on_session_timestamp", on: "chat_messages",
                          columns: ["sessionId", "timestamp"])

            try db.create(table: "system_prompts") { t in
                t.primaryKey("id", .text)
                t.column("text", .text).notNull()
                t.column("createdAt", .integer).notNull()
                t.column("updatedAt", .integer).notNull().defaults(to: 0)
            }

            try db.create(table: "prompt_usage") { t in
                t.belongsTo("prompt", inTable: "system_prompts", onDelete: .cascade).notNull()
                t.column("modelFilename", .text).notNull().indexed()
                t.column("lastUsedAt", .integer).notNull()
                t.primaryKey(["promptId", "modelFilename"])
            }
        }

        return migrator
    }
}
