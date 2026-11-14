import SwiftUI

/// User preferences kept in UserDefaults (Android: StoragePreferences).
enum AppSettings {
    static let hapticsKey = "hapticsOnGeneration"
    static let gpuKey = "useGPU"

    /// A light tick as the reply streams in. On by default, as on Android.
    static var hapticsOnGeneration: Bool {
        UserDefaults.standard.object(forKey: hapticsKey) as? Bool ?? true
    }

    /// Run models on the GPU (Metal). Off is an escape hatch for a model
    /// that misbehaves on the GPU.
    static var useGPU: Bool {
        UserDefaults.standard.object(forKey: gpuKey) as? Bool ?? true
    }
}

/// Settings (Android: SettingsScreen).
struct SettingsView: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.openURL) private var openURL
    @AppStorage(AppSettings.hapticsKey) private var haptics = true

    var body: some View {
        NavigationStack {
            List {
                Section {
                    NavigationLink {
                        ModelLibraryView()
                    } label: {
                        SettingsRow(title: "Models", subtitle: Text("Download and manage models"),
                                    systemImage: "square.stack.3d.down.right")
                    }
                    NavigationLink {
                        SystemPromptsView()
                    } label: {
                        SettingsRow(title: "System prompts", subtitle: Text("Reusable instructions for any model"),
                                    systemImage: "text.quote")
                    }
                    NavigationLink {
                        ToolsSettingsView()
                    } label: {
                        SettingsRow(title: "Tools", subtitle: Text("Let capable models take actions"),
                                    systemImage: "wrench.and.screwdriver")
                    }
                }

                Section {
                    Toggle(isOn: $haptics) {
                        SettingsRow(title: "Haptic feedback on generation",
                                    subtitle: Text("A gentle tick as each part of the response streams in."),
                                    systemImage: "iphone.radiowaves.left.and.right")
                    }
                    NavigationLink {
                        AdvancedSettingsView()
                    } label: {
                        SettingsRow(title: "Advanced", subtitle: Text("Memory and performance options"),
                                    systemImage: "slider.horizontal.3")
                    }
                    Button {
                        if let url = URL(string: UIApplication.openSettingsURLString) { openURL(url) }
                    } label: {
                        SettingsRow(title: "Language", subtitle: Text(verbatim: currentLanguage), systemImage: "globe")
                    }
                    .foregroundStyle(.primary)
                }

                Section {
                    NavigationLink {
                        PrivacyPolicyView()
                    } label: {
                        SettingsRow(title: "Privacy Policy", subtitle: nil, systemImage: "hand.raised")
                    }
                    NavigationLink {
                        FAQView()
                    } label: {
                        SettingsRow(title: "FAQ", subtitle: Text("Frequently asked questions"),
                                    systemImage: "questionmark.circle")
                    }
                } footer: {
                    Text("Version \(Self.version)")
                        .frame(maxWidth: .infinity)
                        .padding(.top, 8)
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

    /// The app's language, chosen per app in iOS Settings.
    private var currentLanguage: String {
        let code = Bundle.main.preferredLocalizations.first ?? "en"
        let locale = Locale(identifier: code)
        return locale.localizedString(forIdentifier: code)?.localizedCapitalized ?? code
    }

    static var version: String {
        let info = Bundle.main.infoDictionary
        let short = info?["CFBundleShortVersionString"] as? String ?? "?"
        let build = info?["CFBundleVersion"] as? String ?? "?"
        return "\(short) (\(build))"
    }
}

private struct SettingsRow: View {
    let title: LocalizedStringKey
    let subtitle: Text?
    let systemImage: String

    var body: some View {
        Label {
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                subtitle?.font(.subheadline).foregroundStyle(.secondary)
            }
        } icon: {
            Image(systemName: systemImage)
        }
    }
}

/// Settings → Advanced (Android: AdvancedScreen).
struct AdvancedSettingsView: View {
    @AppStorage(AppSettings.gpuKey) private var useGPU = true

    var body: some View {
        Form {
            Section {
                Toggle("Run models on the GPU", isOn: $useGPU)
            } footer: {
                Text("Metal makes replies much faster. Turn it off only if a model produces garbled output or crashes; it applies the next time a model loads.")
            }
        }
        .navigationTitle("Advanced")
    }
}

/// Settings → Privacy Policy (Android: PrivacyPolicyScreen).
struct PrivacyPolicyView: View {
    private let sections: [(LocalizedStringKey, LocalizedStringKey)] = [
        ("1. No Personal Information Collection",
         "I, Andriy Druk, the developer of LM Playground, do not collect, store, use, or share any personal information from the users of my app. There are no accounts, no advertising, and no usage analytics or tracking. The app is designed with your privacy as a priority, so you can use it without compromising your personal data."),
        ("2. On-Device Processing",
         "Your conversations, system prompts, and the model's responses are processed locally on your device by the on-device model. This content is never sent to me or to any server for inference. It stays on your device unless you choose to use a feature that reaches the internet, as described below."),
        ("3. Internet Access",
         "LM Playground connects to the internet for two purposes: to download the model files you choose, and — only if you turn them on — to power the optional web tools. The app contains no advertising, telemetry, or usage analytics, and it does not report your activity back to me."),
        ("4. Tools and Web Access",
         "Capable models can use optional tools, which are turned off by default. When you enable the web tools, some information leaves your device: the Web search tool sends the model's search query to DuckDuckGo, and the Fetch web page tool downloads the pages the model decides to open from those websites. Those requests are handled by the third-party sites under their own privacy policies — I neither receive nor store them. The Run JavaScript tool runs entirely on your device in a sandbox with no internet access. To keep everything offline, simply leave the tools off."),
        ("5. Privacy Policy Updates",
         "As the developer of LM Playground, I reserve the right to update this Privacy Policy at any time. Any changes will be effective immediately upon posting the updated policy. Users are encouraged to review this page periodically and stay informed about how their privacy is protected."),
        ("6. Acceptance of This Policy",
         "By using LM Playground, you acknowledge and agree to this Privacy Policy. If you do not agree with the policy, please refrain from using the app. Your continued use of the app following the posting of any updates or changes to this policy will be deemed your acceptance of those changes."),
        ("7. Contact Me",
         "If you have any questions or suggestions about my Privacy Policy, do not hesitate to contact me."),
    ]

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Text("Effective Date: June 2, 2026").font(.subheadline).foregroundStyle(.secondary)
                Text("Welcome to LM Playground! I am committed to maintaining the trust and confidence of everyone who uses my app. This Privacy Policy explains what the app does and does not collect, how your conversations stay on your device, and the few cases — such as the optional web tools — where information leaves your device, so you always know what to expect.")
                ForEach(sections.indices, id: \.self) { index in
                    VStack(alignment: .leading, spacing: 6) {
                        Text(sections[index].0).font(.headline)
                        Text(sections[index].1)
                    }
                }
                Link("me@andriydruk.com", destination: URL(string: "mailto:me@andriydruk.com")!)
            }
            .padding()
            .frame(maxWidth: 700, alignment: .leading)
            .frame(maxWidth: .infinity)
        }
        .navigationTitle("Privacy Policy")
        .navigationBarTitleDisplayMode(.inline)
    }
}

/// Settings → FAQ (Android: FaqScreen), adapted for iPhone and iPad.
struct FAQView: View {
    private let entries: [(LocalizedStringKey, LocalizedStringKey)] = [
        ("What is LM Playground?",
         "LM Playground lets you run large language models directly on your iPhone or iPad. All processing happens locally – no cloud servers needed."),
        ("What models are supported?",
         "Models in the GGUF format. The catalog in Settings → Models lists models tested on iPhone and iPad, including Qwen, Gemma, Llama, LFM and Granite in various sizes, and you can import other GGUF chat models."),
        ("How much storage space do I need?",
         "Model sizes vary from about 500 MB for small models to several GB for larger ones. The app checks free space before downloading."),
        ("Does the app require an internet connection?",
         "Internet is needed to download models, and — if you turn them on — for the optional web tools (web search and fetch page). Apart from that, once a model is downloaded it runs completely offline on your device."),
        ("Is my data private?",
         "Your conversations are processed locally on your device and are never sent to a server by the app. The one exception is the optional web tools: if you enable web search or fetch page, the model's search queries and the pages it opens are sent to those websites (search uses DuckDuckGo). Tools are off by default, so nothing leaves your device unless you turn them on. See the Privacy Policy for details."),
        ("Why is model loading slow?",
         "Larger models take more time to load into memory. Loading times depend on your device's hardware. Once loaded, the model stays in memory until you choose another one."),
        ("Which devices work best?",
         "Devices with more memory can run larger models. The app checks how much memory is free before loading a model and warns you when it may not fit."),
        ("Can I load a custom GGUF model?",
         "Yes. In Settings → Models, tap Import and choose a .gguf file, or copy it into the Models folder of LM Playground in the Files app. The app picks it up automatically and shows it alongside the built-in catalog. Chat template and tokenizer settings are read from the GGUF metadata. If a specific model doesn't work, please open an issue on GitHub."),
        ("Where are models stored?",
         "In the Models folder of LM Playground, which you can see in the Files app and in Finder when your device is connected to a Mac."),
        ("How do I delete a model?",
         "Go to Settings, then Models. Swipe left on a downloaded model and tap Delete."),
        ("What are system prompts?",
         "A system prompt is a reusable instruction that shapes how the model responds — for example, “You are a concise science tutor. Explain ideas clearly with everyday examples” or “You are a senior code reviewer. Point out bugs, edge cases, and security issues.” Create and manage them in Settings → System prompts, then apply one to your chat. They are stored on your device and sent only to the local model."),
        ("What are tools and which ones are available?",
         "Tools let capable models take actions during a reply. Three are available: Run JavaScript (math, dates, and text processing in an on-device sandbox), Web search (finds current information on the web), and Fetch web page (reads a page so the model can summarize or quote it). Turn them on in Settings → Tools. Each is off by default and works only with models that support tool calling — look for the tools badge in the model list."),
        ("Do the web tools send my data online?",
         "Only when you enable them. Web search sends the model's query to DuckDuckGo, and Fetch web page downloads the pages the model chooses to open — those requests go to the websites themselves, not to the developer. Run JavaScript stays entirely on your device with no internet access. With every tool off (the default), nothing leaves your device. See the Privacy Policy for details."),
        ("Does a reply keep going when I leave the app?",
         "No. iOS doesn't let apps use the GPU in the background, so a reply stops when you switch away. What was written so far is kept, and a Continue button finishes it when you come back."),
        ("Does voice dictation send my voice anywhere?",
         "No. Tap the microphone in the chat input and speak — a speech-recognition model turns your words into text on your device as you talk, in 35 languages. The audio is never saved or sent anywhere, so dictation works fully offline. The model (718 MB) downloads once, the first time you use the microphone."),
        ("Can I ask questions about a document?",
         "Yes. Tap + in the chat input and choose Document to attach a PDF, Word, EPUB, HTML, Markdown or text file. The app indexes it on your device with a small embedding model (downloaded once) and gives the model the most relevant passages with each question."),
    ]

    var body: some View {
        List(entries.indices, id: \.self) { index in
            DisclosureGroup {
                Text(entries[index].1)
                    .foregroundStyle(.secondary)
                    .padding(.vertical, 4)
            } label: {
                Text(entries[index].0).font(.headline)
            }
        }
        .navigationTitle("FAQ")
    }
}
