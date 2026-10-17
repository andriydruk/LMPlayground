import SwiftUI
import LlamaKit

@main
struct LMPlaygroundApp: App {
    @UIApplicationDelegateAdaptor private var appDelegate: AppDelegate
    @State private var services = AppServices.shared

    init() {
        let state = AppDirectories.applicationSupport.appending(path: "engine", directoryHint: .isDirectory)
        Task { @InferenceActor in
            Engine.initialize(stateDirectory: state)
        }
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(services.store)
                .environment(services.downloads)
                .environment(services.runtime)
        }
    }
}

/// App-wide services, created once.
@MainActor
final class AppServices {
    static let shared = AppServices()

    let store: ModelStore
    let downloads: DownloadManager
    let runtime: ModelRuntime

    private init() {
        store = ModelStore()
        downloads = DownloadManager()
        runtime = ModelRuntime(store: store)
        downloads.onFinished = { [store] _ in store.refresh() }
    }
}

final class AppDelegate: NSObject, UIApplicationDelegate {
    /// iOS relaunches the app when background downloads finish; hand the
    /// completion handler to the download manager.
    func application(_ application: UIApplication,
                     handleEventsForBackgroundURLSession identifier: String,
                     completionHandler: @escaping () -> Void) {
        MainActor.assumeIsolated {
            AppServices.shared.downloads.backgroundCompletionHandler = completionHandler
        }
    }
}
