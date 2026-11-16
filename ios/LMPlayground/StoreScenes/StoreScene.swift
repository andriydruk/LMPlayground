#if DEBUG
import Foundation

/// An App Store screenshot, drawn by the app so the system compositor renders
/// it (Liquid Glass included) and `simctl io screenshot` captures it. Debug
/// builds only: `-storeScene <id>` opens one (ios/scripts/store-screenshots.sh).
/// Ids and order match Android's StoreScreenshots.kt.
enum StoreScene: String, CaseIterable, Sendable {
    case hero = "0_hero"
    case chooseModel = "1_choose_model"
    case chat = "2_chat"
    case documents = "3_documents"
    case chatWithImage = "4_chat_with_image"
    case systemPrompts = "5_system_prompts"
    case tools = "6_tools"
    case generationParams = "7_generation_params"

    /// The scene a launch argument asked for.
    static var requested: StoreScene? {
        UserDefaults.standard.string(forKey: "storeScene").flatMap(StoreScene.init(rawValue:))
    }
}

/// Marketing copy shared with Android's `screenshot_*` strings, from the
/// StoreScreenshots table that generate-strings.py writes. Looked up at run
/// time so none of it lands in the app's own string catalog.
func storeText(_ key: String) -> String {
    Bundle.main.localizedString(forKey: key, value: nil, table: "StoreScreenshots")
}

/// The file the capture script waits for: written once a scene has settled.
enum StoreSceneReady {
    static var url: URL { AppDirectories.documents.appending(path: "store-scene-ready") }

    static func clear() {
        try? FileManager.default.removeItem(at: url)
    }

    static func mark(_ scene: StoreScene) {
        try? scene.rawValue.write(to: url, atomically: true, encoding: .utf8)
    }
}
#endif
