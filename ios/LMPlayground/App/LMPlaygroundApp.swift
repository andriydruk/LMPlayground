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
            #if DEBUG
            if let scene = StoreScene.requested {
                StoreSceneRoot(scene: scene)
            } else {
                root
            }
            #else
            root
            #endif
        }
    }

    private var root: some View {
        RootView()
            .environment(services.store)
            .environment(services.downloads)
            .environment(services.runtime)
            .environment(services.conversation)
            .environment(services.dictation)
            .environment(\.chatStore, services.chatStore)
    }
}

/// App-wide services, created once.
@MainActor
final class AppServices {
    static let shared = AppServices()

    let store: ModelStore
    let downloads: DownloadManager
    let runtime: ModelRuntime
    let chatStore: ChatStore
    let conversation: ConversationModel
    let dictation: DictationController

    private init() {
        store = ModelStore()
        downloads = DownloadManager()
        runtime = ModelRuntime(store: store)
        let database: AppDatabase
        do {
            database = try AppDatabase.makeShared()
        } catch {
            // A database that can't open is unrecoverable for chat history;
            // keep the app usable with an in-memory store instead.
            database = try! AppDatabase.makeEmpty()
        }
        chatStore = ChatStore(database: database)
        let rag = RagStore(database: database, embedder: EmbeddingModelManager())
        rag.cleanUp()
        conversation = ConversationModel(store: chatStore, runtime: runtime, models: store,
                                         downloads: downloads, rag: rag)
        dictation = DictationController(downloads: downloads)
        downloads.onFinished = { [store] _ in store.refresh() }
        // Speech recognition and embedding models live in RAM (or close to
        // it): give them back under pressure.
        NotificationCenter.default.addObserver(forName: UIApplication.didReceiveMemoryWarningNotification,
                                               object: nil, queue: .main) { [rag] _ in
            Self.releaseAuxiliaryModels(rag: rag)
        }
    }

    /// Unload the dictation and embedding models if they're idle.
    nonisolated static func releaseAuxiliaryModels(rag: RagStore) {
        Task { @AsrActor in DictationEngine.shared.unloadIfIdle() }
        if let embedder = rag.embedder as? EmbeddingModelManager {
            Task { @InferenceActor in embedder.unload() }
        }
    }
}

extension EnvironmentValues {
    @Entry var chatStore: ChatStore?
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
