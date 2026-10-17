import SwiftUI
import LlamaKit

@main
struct LMPlaygroundApp: App {

    init() {
        let state = AppDirectories.applicationSupport.appending(path: "engine", directoryHint: .isDirectory)
        Task { @InferenceActor in
            Engine.initialize(stateDirectory: state)
        }
    }

    var body: some Scene {
        WindowGroup {
            EngineLabView()
        }
    }
}
