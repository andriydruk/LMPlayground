import SwiftUI

struct ConversationView: View {
    @Environment(ConversationModel.self) private var model
    @Environment(DictationController.self) private var dictation
    @Environment(ModelRuntime.self) private var runtime
    @Environment(ModelStore.self) private var store
    @Environment(\.scenePhase) private var scenePhase

    @State private var showParams = false
    @State private var showLibrary = false
    @State private var pendingTooLarge: (ModelDescriptor, String)?
    /// Whether the end of the conversation is on screen: streaming keeps it
    /// there, and scrolling up offers a way back instead.
    @State private var atBottom = true

    var body: some View {
        @Bindable var model = model
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 16) {
                    if model.messages.isEmpty {
                        EmptyConversationView()
                    }
                    ForEach(model.messages) { message in
                        let isLast = message.id == model.messages.last?.id
                        MessageView(message: message,
                                    isStreaming: model.isGenerating && isLast,
                                    onContinue: isLast && !model.isGenerating && runtime.loaded != nil
                                        ? { model.continueReply() } : nil)
                            .id(message.id)
                    }
                    // On screen exactly when the end of the conversation is.
                    Color.clear.frame(height: 1).id("bottom")
                        .onAppear { atBottom = true }
                        .onDisappear { atBottom = false }
                }
                .padding()
            }
            .defaultScrollAnchor(.bottom)
            .scrollDismissesKeyboard(.interactively)
            .onChange(of: model.messages.last?.content) {
                if model.isGenerating && atBottom { proxy.scrollTo("bottom", anchor: .bottom) }
            }
            .overlay(alignment: .bottomTrailing) {
                if !atBottom && !model.messages.isEmpty {
                    Button("Jump to bottom", systemImage: "arrow.down") {
                        withAnimation { proxy.scrollTo("bottom", anchor: .bottom) }
                    }
                    .labelStyle(.iconOnly)
                    .font(.headline)
                    .padding(12)
                    .background(.regularMaterial, in: Circle())
                    .overlay(Circle().stroke(.quaternary))
                    .padding()
                    .transition(.opacity.combined(with: .scale))
                    .accessibilityIdentifier("jumpToBottom")
                }
            }
            .animation(.easeInOut(duration: 0.2), value: atBottom)
        }
        .safeAreaInset(edge: .bottom) {
            VStack(spacing: 8) {
                HintPills()
                InputBar()
            }
        }
        .navigationTitle(model.session?.title ?? String(localized: "New chat"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .principal) {
                ModelPicker(onTooLarge: { descriptor, message in pendingTooLarge = (descriptor, message) },
                            onManage: { showLibrary = true })
            }
            ToolbarItemGroup(placement: .topBarTrailing) {
                Button("Settings", systemImage: "slider.horizontal.3") { showParams = true }
                Button("New chat", systemImage: "square.and.pencil") { model.newConversation() }
                    .keyboardShortcut("n", modifiers: .command)
            }
        }
        .sheet(isPresented: $showParams) {
            GenerationParamsSheet()
        }
        .sheet(isPresented: $showLibrary) {
            NavigationStack {
                ModelLibraryView()
                    .toolbar { Button("Done") { showLibrary = false } }
            }
        }
        .alert("Not enough memory", isPresented: Binding(
            get: { pendingTooLarge != nil }, set: { if !$0 { pendingTooLarge = nil } })) {
            Button("Load anyway", role: .destructive) {
                if let (descriptor, _) = pendingTooLarge {
                    Task { await model.select(model: descriptor, force: true) }
                }
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text(pendingTooLarge?.1 ?? "")
        }
        .alert("Something went wrong", isPresented: Binding(
            get: { model.errorMessage != nil }, set: { if !$0 { model.errorMessage = nil } })) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(model.errorMessage ?? "")
        }
        .alert("Enable document Q&A", isPresented: Binding(
            get: { model.documentAwaitingEmbeddingModel != nil },
            set: { if !$0 { model.dismissEmbeddingModelPrompt() } })) {
            Button("Download") { model.confirmEmbeddingModelDownload() }
            Button("Cancel", role: .cancel) { model.dismissEmbeddingModelPrompt() }
        } message: {
            let embedding = ModelCatalog.embeddingModel
            Text("Answering questions about documents needs a small embedding model, \(embedding.name) (\(embedding.sizeLabel)), downloaded once and kept on your device.")
        }
        .alert("Enable voice dictation", isPresented: Binding(
            get: { dictation.needsModelDownload }, set: { dictation.needsModelDownload = $0 })) {
            Button("Download") { dictation.downloadModel() }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Speaking instead of typing needs a speech-recognition model, \(dictation.model.name) (\(dictation.model.sizeLabel)), downloaded once and kept on your device. It works offline, in 35 languages.")
        }
        .alert("Dictation", isPresented: Binding(
            get: { dictation.errorMessage != nil }, set: { if !$0 { dictation.errorMessage = nil } })) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(dictation.errorMessage ?? "")
        }
        .onChange(of: scenePhase) { _, phase in
            if phase == .background {
                model.interruptForBackground()
                // iOS silences the microphone of a background app.
                dictation.onMicReleased()
                AppServices.releaseAuxiliaryModels(rag: model.rag)
            }
        }
    }
}

private struct EmptyConversationView: View {
    @Environment(ModelRuntime.self) private var runtime

    var body: some View {
        VStack(spacing: 12) {
            Image(systemName: "bubble.left.and.text.bubble.right")
                .font(.largeTitle)
                .foregroundStyle(.tertiary)
            if let loaded = runtime.loaded {
                Text("Ask \(loaded.descriptor.name) anything").font(.headline)
                Text("Everything runs on this device.").foregroundStyle(.secondary)
            } else {
                Text("Choose a model to start").font(.headline)
                Text("Pick one from the menu at the top, or download one in Models.")
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.top, 80)
    }
}

/// Model menu in the navigation bar: downloaded models, load state, and a way
/// to the library.
struct ModelPicker: View {
    @Environment(ConversationModel.self) private var model
    @Environment(ModelRuntime.self) private var runtime
    @Environment(ModelStore.self) private var store
    var onTooLarge: (ModelDescriptor, String) -> Void
    var onManage: () -> Void

    var body: some View {
        Menu {
            ForEach(store.downloaded) { descriptor in
                Button {
                    select(descriptor)
                } label: {
                    if runtime.loaded?.descriptor.filename == descriptor.filename {
                        Label(descriptor.name, systemImage: "checkmark")
                    } else {
                        Text(descriptor.name)
                    }
                }
            }
            Divider()
            Button("Manage models…", systemImage: "square.stack.3d.down.right", action: onManage)
        } label: {
            HStack(spacing: 4) {
                switch runtime.phase {
                case .loading(_, let progress):
                    ProgressView(value: progress).frame(width: 60)
                    Text("Loading…")
                default:
                    Text(runtime.loaded?.descriptor.name ?? String(localized: "Choose a model"))
                        .lineLimit(1)
                }
                Image(systemName: "chevron.down").font(.caption2)
            }
            .font(.headline)
        }
        .accessibilityIdentifier("modelPicker")
        .disabled(model.isGenerating)
    }

    private func select(_ descriptor: ModelDescriptor) {
        if case .tooLarge(let needed, let available) = runtime.memoryVerdict(for: descriptor, contextSize: model.params.contextSize) {
            onTooLarge(descriptor, ModelRuntime.LoadError.tooLarge(neededMB: needed, availableMB: available).localizedDescription)
            return
        }
        Task { await model.select(model: descriptor) }
    }
}

/// Text field, thinking toggle, dictation and send / stop.
struct InputBar: View {
    @Environment(ConversationModel.self) private var model
    @Environment(ModelRuntime.self) private var runtime
    @Environment(DictationController.self) private var dictation
    @FocusState private var focused: Bool
    /// The text before dictation began; recognized words are written on top
    /// of it, so rewriting never compounds. Nil while dictation isn't writing.
    @State private var dictationBase: String?
    /// What dictation last wrote, to tell the user's own edits apart.
    @State private var dictationWritten: String?

    var body: some View {
        @Bindable var model = model
        VStack(spacing: 6) {
        if !model.documents.isEmpty {
            DocumentChipsRow()
        }
        if let image = model.pendingImage {
            PendingImagePreview(path: image)
        }
        HStack(alignment: .bottom, spacing: 8) {
            if let loaded = runtime.loaded {
                AttachmentMenu(allowsImages: loaded.supportsVision)
                    .disabled(model.isGenerating)
            }
            if runtime.loaded?.descriptor.offersThinkingToggle == true {
                Button {
                    model.thinkingEnabled.toggle()
                } label: {
                    Image(systemName: model.thinkingEnabled ? "brain.fill" : "brain")
                        .font(.title3)
                        .frame(width: 36, height: 36)
                }
                .accessibilityLabel(model.thinkingEnabled ? "Thinking on" : "Thinking off")
                .accessibilityIdentifier("thinkingToggle")
                .buttonStyle(.borderless)
            }
            TextField("Message", text: $model.draft, axis: .vertical)
                .lineLimit(1...8)
                .focused($focused)
                .accessibilityIdentifier("messageField")
                .padding(.horizontal, 14)
                .padding(.vertical, 9)
                .contentShape(.rect)
                .onTapGesture { focused = true }
                .background(.background.secondary, in: RoundedRectangle(cornerRadius: 20))
                .onSubmit { if model.canSend { model.send() } }
            // Dictation runs its own engine, so it works with no chat model
            // loaded; only a reply in progress blocks it.
            MicButton(enabled: !model.isGenerating)
            if model.isGenerating {
                Button("Stop", systemImage: "stop.circle.fill") { model.stop() }
                    .labelStyle(.iconOnly)
                    .font(.title)
                    .keyboardShortcut(".", modifiers: .command)
                    .accessibilityIdentifier("stopButton")
            } else {
                Button("Send", systemImage: "arrow.up.circle.fill") { model.send() }
                    .labelStyle(.iconOnly)
                    .font(.title)
                    .disabled(!model.canSend)
                    .accessibilityIdentifier("sendButton")
                    .keyboardShortcut(.return, modifiers: .command)
            }
        }
        .padding(.horizontal)
        }
        .padding(.vertical, 8)
        .background(.bar)
        .onChange(of: dictation.state) { _, state in
            guard case .listening(let text) = state else { return }
            let base = dictationBase ?? model.draft
            dictationBase = base
            writeDictation(Self.join(base, text))
        }
        .onChange(of: dictation.transcript) { _, transcript in
            guard let transcript else { return }
            // The final text replaces what was written live; an empty one
            // (nothing heard) restores the base.
            writeDictation(Self.join(dictationBase ?? model.draft, transcript))
            dictationBase = nil
            dictation.consumeTranscript()
        }
        .onChange(of: model.draft) { _, draft in
            // The field is the user's again the moment they change it (or
            // send it): dictation stops rather than overwrite their edit.
            if dictationBase != nil, draft != dictationWritten {
                dictationBase = nil
                dictation.abandon()
            }
        }
    }

    private func writeDictation(_ text: String) {
        dictationWritten = text
        model.draft = text
    }

    static func join(_ base: String, _ text: String) -> String {
        if text.isEmpty { return base }
        if base.isEmpty || base.last?.isWhitespace == true { return base + text }
        return base + " " + text
    }
}

/// One-tap offers above the text field (Android: the model-hint pills): the
/// model a reopened chat used, and the image module a loaded vision model is
/// missing. Plus a note when the device is running hot.
private struct HintPills: View {
    @Environment(ConversationModel.self) private var model
    @Environment(ModelRuntime.self) private var runtime
    @Environment(ModelStore.self) private var store
    @Environment(DownloadManager.self) private var downloads
    @State private var thermal = ProcessInfo.processInfo.thermalState
    @State private var dismissedVisionHint: String?

    var body: some View {
        VStack(spacing: 8) {
            if thermal == .serious || thermal == .critical {
                Label("Your device is hot, so replies may be slower.", systemImage: "thermometer.high")
                    .font(.footnote)
                    .foregroundStyle(.orange)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 6)
                    .background(.regularMaterial, in: Capsule())
            }
            if let hint = model.sessionModelHint {
                pill("Previously used: \(hint.name)", systemImage: "sparkles") {
                    Task { await model.select(model: hint) }
                }
                .task(id: hint.filename) {
                    try? await Task.sleep(for: .seconds(3))
                    if model.sessionModelHint?.filename == hint.filename { model.sessionModelHint = nil }
                }
            } else if let missing = missingVisionModule {
                pill("Add image support · Download", systemImage: "arrow.down.circle") {
                    if let catalog = missing.catalog { downloads.download(catalog, includeProjector: true) }
                    dismissedVisionHint = missing.filename
                }
            }
        }
        .animation(.easeInOut(duration: 0.2), value: model.sessionModelHint?.filename)
        .onReceive(NotificationCenter.default.publisher(for: ProcessInfo.thermalStateDidChangeNotification)) { _ in
            thermal = ProcessInfo.processInfo.thermalState
        }
    }

    /// The loaded catalog model, when its image module can be downloaded.
    private var missingVisionModule: ModelDescriptor? {
        guard let loaded = runtime.loaded?.descriptor, loaded.needsVisionModule,
              loaded.catalog?.mmprojURL != nil, dismissedVisionHint != loaded.filename,
              let projector = loaded.mmprojFilename, !downloads.isDownloading(projector) else { return nil }
        return loaded
    }

    private func pill(_ title: LocalizedStringKey, systemImage: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Label(title, systemImage: systemImage)
                .font(.subheadline.weight(.medium))
                .padding(.horizontal, 14)
                .padding(.vertical, 8)
                .background(.regularMaterial, in: Capsule())
                .overlay(Capsule().stroke(.quaternary))
        }
        .buttonStyle(.plain)
        .foregroundStyle(.tint)
        .transition(.opacity.combined(with: .move(edge: .bottom)))
    }
}
