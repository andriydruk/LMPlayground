import Foundation

/// A chat model the app can show: a catalog entry or a user-imported GGUF,
/// with its vision projector resolved against the files on disk.
struct ModelDescriptor: Identifiable, Hashable, Sendable {
    let name: String
    let filename: String
    let summary: String
    let logo: String
    /// Nil for custom (imported) models.
    let catalog: CatalogModel?
    /// The projector to use: the catalog's when that file is present,
    /// otherwise one on disk that pairs by name (MmprojPairing).
    let mmprojFilename: String?
    let isDownloaded: Bool
    let isMmprojDownloaded: Bool
    /// Capabilities: detected from the chat template after a load when known,
    /// otherwise the catalog's best guess.
    let supportsTools: Bool
    let supportsThinking: Bool

    var id: String { filename }
    var isCustom: Bool { catalog == nil }
    var isVision: Bool { mmprojFilename != nil }
    var thinkingMode: ThinkingMode { catalog?.thinkingMode ?? .unknown }

    /// Main file present, image module missing: usable as text-only now.
    var needsVisionModule: Bool { isVision && isDownloaded && !isMmprojDownloaded }

    /// Whether the thinking toggle does anything, following the measured
    /// behaviour when there is one (Android's ThinkingMode rules).
    var offersThinkingToggle: Bool {
        switch thinkingMode {
        case .optional: return true
        case .none, .always: return false
        case .unknown: return supportsThinking
        }
    }
}

/// Capabilities read from each model's chat template the first time it loads.
/// They override the catalog's static flags (which can be wrong) and give
/// imported models their badges. Android's StoragePreferences.detectedCaps.
enum ModelPreferences {

    struct DetectedCapabilities: Codable, Sendable {
        var tools: Bool
        var thinking: Bool
    }

    private static let key = "detectedCapabilities"

    static func detected(_ filename: String) -> DetectedCapabilities? {
        all()[filename]
    }

    static func record(_ caps: DetectedCapabilities, for filename: String) {
        var map = all()
        map[filename] = caps
        if let data = try? JSONEncoder().encode(map) {
            UserDefaults.standard.set(data, forKey: key)
        }
    }

    static func forget(_ filename: String) {
        var map = all()
        map[filename] = nil
        if let data = try? JSONEncoder().encode(map) {
            UserDefaults.standard.set(data, forKey: key)
        }
    }

    private static func all() -> [String: DetectedCapabilities] {
        guard let data = UserDefaults.standard.data(forKey: key) else { return [:] }
        return (try? JSONDecoder().decode([String: DetectedCapabilities].self, from: data)) ?? [:]
    }
}
