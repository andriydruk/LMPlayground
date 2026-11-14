import Foundation

/// Detects a crash while loading a model. On Android a native crash only kills
/// the `:llama` service process; here the engine is in-process, so a GGUF that
/// crashes the loader would crash the app on every launch that loads it.
///
/// A marker naming the model and step is written before each crash-prone
/// step and removed after it. If it is still there at the next launch, that
/// step took the app down, and the UI asks what to do instead of retrying.
enum LoadSentinel {

    struct Record: Codable, Equatable, Sendable {
        var filename: String
        var step: String
        var date: Date
    }

    private static var url: URL {
        AppDirectories.applicationSupport.appending(path: "engine/loading.json")
    }

    static func begin(filename: String, step: String) {
        let record = Record(filename: filename, step: step, date: Date())
        guard let data = try? JSONEncoder().encode(record) else { return }
        try? FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        // Durable before the risky step starts.
        try? data.write(to: url, options: .atomic)
    }

    static func end() {
        try? FileManager.default.removeItem(at: url)
    }

    /// The step that crashed the previous launch, if any. Clears the marker.
    static func takeCrashFromLastLaunch() -> Record? {
        defer { end() }
        guard let data = try? Data(contentsOf: url) else { return nil }
        return try? JSONDecoder().decode(Record.self, from: data)
    }
}
