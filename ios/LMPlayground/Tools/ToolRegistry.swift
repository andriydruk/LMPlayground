import Foundation

/// A tool the model can call (Android: com.druk.llamacpp.tools.Tool).
protocol Tool: Sendable {
    var name: String { get }
    var description: String { get }
    /// JSON Schema of the arguments object.
    var parametersSchema: String { get }
    /// Run with the model's JSON arguments; returns a JSON result.
    func execute(arguments: String) async -> String
    /// Abort in-flight work (Stop during a tool call).
    func cancelInFlight() async
}

extension Tool {
    func errorJSON(_ message: String) -> String {
        let data = try? JSONSerialization.data(withJSONObject: ["error": message])
        return data.map { String(decoding: $0, as: UTF8.self) } ?? #"{"error":"failed"}"#
    }
}

/// Which tools are on: a global default (Settings › Tools) and a per-model
/// override (chat settings). Off unless turned on (Android: StoragePreferences).
enum ToolSettings {
    static func isEnabledByDefault(_ tool: String) -> Bool {
        UserDefaults.standard.bool(forKey: "tool_enabled_\(tool)")
    }

    static func setEnabledByDefault(_ tool: String, _ enabled: Bool) {
        UserDefaults.standard.set(enabled, forKey: "tool_enabled_\(tool)")
    }

    static func override(_ tool: String, model: String) -> Bool? {
        let key = "tool_override_\(model)_\(tool)"
        return UserDefaults.standard.object(forKey: key) == nil ? nil : UserDefaults.standard.bool(forKey: key)
    }

    static func setOverride(_ tool: String, model: String, _ enabled: Bool?) {
        let key = "tool_override_\(model)_\(tool)"
        if let enabled { UserDefaults.standard.set(enabled, forKey: key) } else { UserDefaults.standard.removeObject(forKey: key) }
    }

    static func isEnabled(_ tool: String, model: String) -> Bool {
        override(tool, model: model) ?? isEnabledByDefault(tool)
    }
}

/// The app's tools and their enablement for the loaded model; executes the
/// calls the engine reports (Android: ToolRegistry + DefaultToolRegistry).
actor ToolRegistry: ToolExecutor {

    /// Search result references (ddg:N) for the current conversation.
    let links = WebLinkStore()
    let tools: [any Tool]
    private var modelFilename: String?

    init() {
        tools = [WebSearchTool(links: links), WebFetchTool(links: links), JavaScriptTool()]
    }

    static let toolNames = ["web_search", "web_fetch", "run_javascript"]

    func setModel(_ filename: String?) {
        modelFilename = filename
    }

    private var enabledTools: [any Tool] {
        guard let modelFilename else { return [] }
        return tools.filter { ToolSettings.isEnabled($0.name, model: modelFilename) }
    }

    /// OpenAI-format array of the enabled tools, or nil when none are on.
    var toolsJSON: String? {
        let enabled = enabledTools
        guard !enabled.isEmpty else { return nil }
        let array: [[String: Any]] = enabled.map { tool in
            let schema = (try? JSONSerialization.jsonObject(with: Data(tool.parametersSchema.utf8))) ?? [:]
            return ["type": "function",
                    "function": ["name": tool.name, "description": tool.description, "parameters": schema]]
        }
        let data = (try? JSONSerialization.data(withJSONObject: array, options: [.sortedKeys])) ?? Data("[]".utf8)
        return String(decoding: data, as: UTF8.self)
    }

    func execute(callsJSON: String) async -> String {
        let calls = (try? JSONSerialization.jsonObject(with: Data(callsJSON.utf8)) as? [[String: Any]]) ?? []
        var results: [[String: String]] = []
        for call in calls {
            let name = call["name"] as? String ?? ""
            let id = call["id"] as? String ?? ""
            let arguments = call["arguments"] as? String ?? "{}"
            let content: String
            if let tool = tools.first(where: { $0.name == name }) {
                content = await tool.execute(arguments: arguments)
            } else {
                content = #"{"error":"Unknown tool: \#(name)"}"#
            }
            results.append(["id": id, "name": name, "content": content])
        }
        let data = (try? JSONSerialization.data(withJSONObject: results)) ?? Data("[]".utf8)
        return String(decoding: data, as: UTF8.self)
    }

    func cancelInFlight() async {
        for tool in tools { await tool.cancelInFlight() }
    }
}

/// Compact references for search results (ddg:N) so long URLs don't fill the
/// model's context; web_fetch resolves them back. Port of WebLinkStore.kt.
final class WebLinkStore: @unchecked Sendable {
    static let prefix = "ddg:"

    private let lock = NSLock()
    private var counter = 0
    private var order: [String] = []
    private var map: [String: String] = [:]
    private let maxEntries: Int

    init(maxEntries: Int = 64) {
        self.maxEntries = maxEntries
    }

    func reference(_ url: String) -> String {
        lock.withLock {
            counter += 1
            let key = "\(Self.prefix)\(counter)"
            map[key] = url
            order.append(key)
            while order.count > maxEntries { map[order.removeFirst()] = nil }
            return key
        }
    }

    func resolve(_ token: String) -> String? {
        lock.withLock { map[token.trimmingCharacters(in: .whitespaces)] }
    }

    func isReference(_ value: String) -> Bool {
        value.trimmingCharacters(in: .whitespaces).hasPrefix(Self.prefix)
    }

    func snapshot() -> [String: String] {
        lock.withLock { map }
    }

    func restore(_ entries: [String: String]) {
        lock.withLock {
            map = entries
            order = entries.keys.sorted { (Int($0.dropFirst(Self.prefix.count)) ?? 0) < (Int($1.dropFirst(Self.prefix.count)) ?? 0) }
            counter = order.compactMap { Int($0.dropFirst(Self.prefix.count)) }.max() ?? 0
        }
    }
}
