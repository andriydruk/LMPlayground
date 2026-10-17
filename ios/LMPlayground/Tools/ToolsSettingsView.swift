import SwiftUI

/// Human-facing names and explanations of the tools.
enum ToolInfo {
    static func title(_ name: String) -> LocalizedStringKey {
        switch name {
        case "web_search": "Web search"
        case "web_fetch": "Read web pages"
        case "run_javascript": "Run JavaScript"
        default: LocalizedStringKey(name)
        }
    }

    static func detail(_ name: String) -> LocalizedStringKey {
        switch name {
        case "web_search": "Searches DuckDuckGo. Your query leaves the device."
        case "web_fetch": "Downloads a page the model asks for. The address leaves the device."
        case "run_javascript": "Calculations, dates and text processing in a sandbox, offline."
        default: ""
        }
    }
}

/// Settings › Tools: what's on for every model (Android: ToolsScreen).
struct ToolsSettingsView: View {
    @State private var enabled: [String: Bool] = [:]

    var body: some View {
        Form {
            Section {
                ForEach(ToolRegistry.toolNames, id: \.self) { name in
                    Toggle(isOn: Binding(
                        get: { enabled[name] ?? false },
                        set: { enabled[name] = $0; ToolSettings.setEnabledByDefault(name, $0) })) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(ToolInfo.title(name))
                            Text(ToolInfo.detail(name)).font(.caption).foregroundStyle(.secondary)
                        }
                    }
                }
            } footer: {
                Text("Models that support tools can use them mid-reply. You can change this for a single model in its chat settings.")
            }
        }
        .navigationTitle("Tools")
        .onAppear {
            for name in ToolRegistry.toolNames { enabled[name] = ToolSettings.isEnabledByDefault(name) }
        }
    }
}

/// Per-model tool toggles in chat settings: they override the defaults for
/// this model only.
struct ModelToolsSection: View {
    let modelFilename: String
    @State private var enabled: [String: Bool] = [:]

    var body: some View {
        Section {
            ForEach(ToolRegistry.toolNames, id: \.self) { name in
                Toggle(ToolInfo.title(name), isOn: Binding(
                    get: { enabled[name] ?? false },
                    set: { enabled[name] = $0; ToolSettings.setOverride(name, model: modelFilename, $0) }))
            }
        } header: {
            Text("Tools for this model")
        }
        .onAppear {
            for name in ToolRegistry.toolNames { enabled[name] = ToolSettings.isEnabled(name, model: modelFilename) }
        }
    }
}
