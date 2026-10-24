import SwiftUI

/// Sessions sidebar and the conversation. On iPhone the conversation is the
/// landing screen and the sidebar is one step back; on iPad both show.
struct RootView: View {
    @Environment(ModelRuntime.self) private var runtime
    @State private var showCrash = false
    @State private var showSettings = false
    @State private var column = NavigationSplitViewColumn.detail
    @State private var debugScreen: DebugScreen?

    /// Launched for an automated Engine Lab run (`-labAutorun` / `-labDownload`).
    private static let labLaunch = UserDefaults.standard.bool(forKey: "labAutorun")
        || UserDefaults.standard.string(forKey: "labDownload") != nil

    var body: some View {
        Group {
            if UserDefaults.standard.string(forKey: "labChat") != nil {
                NavigationStack { ChatSelfTestView() }
            } else if Self.labLaunch {
                NavigationStack { EngineLabView() }
            } else {
                NavigationSplitView(preferredCompactColumn: $column) {
                    SessionsView(showSettings: $showSettings, onOpen: { column = .detail })
                } detail: {
                    NavigationStack { ConversationView() }
                }
            }
        }
        .sheet(isPresented: $showSettings) {
            SettingsView()
        }
        .sheet(item: $debugScreen) { screen in
            NavigationStack { screen.view }
        }
        #if DEBUG
        .task { await openDebugRoute() }
        #endif
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

#if DEBUG
extension RootView {
    /// Screenshot and review hook, debug builds only: `-uiOpenLatest YES`
    /// opens the newest chat, `-uiModel <filename>` loads a model and
    /// `-uiScreen settings|faq|privacy|advanced|models` opens a screen.
    private func openDebugRoute() async {
        let defaults = UserDefaults.standard
        // `-labClean YES`: delete the test models (see EngineLab.cleanUpModels).
        if defaults.bool(forKey: "labClean") {
            EngineLab.cleanUpModels()
            AppServices.shared.store.refresh()
            try? Date().formatted(.iso8601).write(to: AppDirectories.documents.appending(path: "lab-clean.txt"),
                                                  atomically: true, encoding: .utf8)
        }
        if defaults.bool(forKey: "uiOpenLatest"),
           let latest = await AppServices.shared.chatStore.latestSession() {
            AppServices.shared.conversation.open(latest)
        }
        if let filename = defaults.string(forKey: "uiModel"),
           let descriptor = AppServices.shared.store.downloaded.first(where: { $0.filename == filename }) {
            await AppServices.shared.conversation.select(model: descriptor)
        }
        switch defaults.string(forKey: "uiScreen") {
        case "settings": showSettings = true
        case let name?: debugScreen = DebugScreen(rawValue: name)
        case nil: break
        }
    }
}
#endif

/// Screens a debug launch argument can open on their own.
enum DebugScreen: String, Identifiable {
    case faq, privacy, advanced, models

    var id: String { rawValue }

    @MainActor @ViewBuilder
    var view: some View {
        switch self {
        case .faq: FAQView()
        case .privacy: PrivacyPolicyView()
        case .advanced: AdvancedSettingsView()
        case .models: ModelLibraryView()
        }
    }
}
