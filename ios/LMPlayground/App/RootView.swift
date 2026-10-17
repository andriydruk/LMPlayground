import SwiftUI

/// Temporary root while the conversation screen is built (M3): the model
/// library, the engine lab and a load test.
struct RootView: View {
    @Environment(ModelRuntime.self) private var runtime
    @State private var showCrash = false

    /// Launched for an automated Engine Lab run (`-labAutorun` / `-labDownload`).
    private static let labLaunch = UserDefaults.standard.bool(forKey: "labAutorun")
        || UserDefaults.standard.string(forKey: "labDownload") != nil

    var body: some View {
        NavigationStack {
            if Self.labLaunch {
                EngineLabView()
            } else {
                ModelLibraryView()
                    .toolbar {
                        ToolbarItem(placement: .topBarLeading) {
                            NavigationLink("Lab") { EngineLabView() }
                        }
                    }
            }
        }
        .onAppear { showCrash = runtime.crashFromLastLaunch != nil }
        .alert("A model crashed the app", isPresented: $showCrash) {
            Button("OK", role: .cancel) { runtime.crashFromLastLaunch = nil }
        } message: {
            if let crash = runtime.crashFromLastLaunch {
                Text("\(crash.filename) stopped the app while loading (\(crash.step)). It won't be loaded automatically.")
            }
        }
    }
}
