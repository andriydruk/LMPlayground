import Foundation
import GRDB

/// A conversation (Android: ChatSessionEntity).
struct ChatSession: Codable, Identifiable, Hashable, Sendable, FetchableRecord, PersistableRecord {
    static let databaseTableName = "chat_sessions"

    var id: String
    var title: String
    var modelFilename: String
    var modelName: String
    /// Milliseconds since 1970, as on Android.
    var createdAt: Int64
    var updatedAt: Int64
    var pinned: Bool
    var contextSize: Int
    var temperature: Double
    var topP: Double
    var repetitionPenalty: Double
    var topK: Int
    var minP: Double
    var seed: Int64
    var thinkingBudget: Int
    var systemPrompt: String
    /// JSON object; see ConversationMetadata.
    var metadata: String

    var params: GenerationParams {
        get {
            GenerationParams(contextSize: contextSize, temperature: temperature, topP: topP,
                             repetitionPenalty: repetitionPenalty, topK: topK, minP: minP,
                             seed: seed, thinkingBudget: thinkingBudget)
        }
        set {
            contextSize = newValue.contextSize
            temperature = newValue.temperature
            topP = newValue.topP
            repetitionPenalty = newValue.repetitionPenalty
            topK = newValue.topK
            minP = newValue.minP
            seed = newValue.seed
            thinkingBudget = newValue.thinkingBudget
        }
    }
}

/// One message (Android: ChatMessageEntity).
struct ChatMessageRecord: Codable, Identifiable, Hashable, Sendable, FetchableRecord, MutablePersistableRecord {
    static let databaseTableName = "chat_messages"

    var id: Int64?
    var sessionId: String
    /// "User" or "Assistant", as Android stores it.
    var author: String
    var content: String
    var thinkingDurationSeconds: Int
    var thinkingTokens: Int
    var responseTokens: Int
    var responseDurationSeconds: Double
    var timestamp: Int64
    var imagePath: String?
    var interrupted: Bool
    var toolCalls: String?

    mutating func didInsert(_ inserted: InsertionSuccess) {
        id = inserted.rowID
    }
}

/// A reusable system prompt (Android: SystemPromptEntity).
struct SystemPrompt: Codable, Identifiable, Hashable, Sendable, FetchableRecord, PersistableRecord {
    static let databaseTableName = "system_prompts"

    var id: String
    var text: String
    var createdAt: Int64
    var updatedAt: Int64
}

/// When a prompt was last used with a model (Android: PromptUsage).
struct PromptUsage: Codable, Hashable, Sendable, FetchableRecord, PersistableRecord {
    static let databaseTableName = "prompt_usage"

    var promptId: String
    var modelFilename: String
    var lastUsedAt: Int64
}

/// Sampling settings for a conversation (Android: GenerationParams).
struct GenerationParams: Codable, Hashable, Sendable {
    var contextSize = 4096
    var temperature = 0.8
    var topP = 0.95
    var repetitionPenalty = 1.0
    var topK = 40
    var minP = 0.05
    /// Negative means random.
    var seed: Int64 = -1
    var thinkingBudget = 1024
}

/// Per-conversation key/value data stored as JSON in `chat_sessions.metadata`
/// (Android: ConversationMetadata).
struct ConversationMetadata {
    static let webLinksKey = "webLinks"

    private var object: [String: Any]

    init(json: String?) {
        if let data = json?.data(using: .utf8),
           let parsed = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
            object = parsed
        } else {
            object = [:]
        }
    }

    func stringMap(_ key: String) -> [String: String] {
        (object[key] as? [String: Any])?.compactMapValues { $0 as? String } ?? [:]
    }

    mutating func setStringMap(_ key: String, _ map: [String: String]) {
        object[key] = map.isEmpty ? nil : map
    }

    var json: String {
        guard let data = try? JSONSerialization.data(withJSONObject: object, options: [.sortedKeys]) else { return "{}" }
        return String(decoding: data, as: UTF8.self)
    }
}

extension Date {
    var millisecondsSince1970: Int64 { Int64((timeIntervalSince1970 * 1000).rounded()) }

    init(milliseconds: Int64) {
        self.init(timeIntervalSince1970: TimeInterval(milliseconds) / 1000)
    }
}
