import SwiftUI

/// Sessions sidebar and the conversation. On iPhone the conversation is the
/// landing screen and the sidebar is one step back; on iPad both show.
struct RootView: View {
    @Environment(ModelRuntime.self) private var runtime
    @State private var showCrash = false
    @State private var showSettings = false
    @State private var column = NavigationSplitViewColumn.detail

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

/// Settings (Android: SettingsScreen). Sections fill in over the parity
/// milestones.
struct SettingsView: View {
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                NavigationLink {
                    ModelLibraryView()
                } label: {
                    Label("Models", systemImage: "square.stack.3d.down.right")
                }
                NavigationLink {
                    SystemPromptsView()
                } label: {
                    Label("System prompts", systemImage: "text.quote")
                }
                NavigationLink {
                    ToolsSettingsView()
                } label: {
                    Label("Tools", systemImage: "wrench.and.screwdriver")
                }
                #if DEBUG
                Section("Debug") {
                    NavigationLink {
                        EngineLabView()
                    } label: {
                        Label("Engine Lab", systemImage: "gauge.with.dots.needle.67percent")
                    }
                }
                #endif
            }
            .navigationTitle("Settings")
            .toolbar { Button("Done") { dismiss() } }
        }
    }
}
