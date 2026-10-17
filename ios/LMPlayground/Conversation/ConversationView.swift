import SwiftUI

struct ConversationView: View {
    @Environment(ConversationModel.self) private var model
    @Environment(ModelRuntime.self) private var runtime
    @Environment(ModelStore.self) private var store
    @Environment(\.scenePhase) private var scenePhase

    @State private var showParams = false
    @State private var showLibrary = false
    @State private var pendingTooLarge: (ModelDescriptor, String)?

    var body: some View {
        @Bindable var model = model
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 16) {
                    if model.messages.isEmpty {
                        EmptyConversationView()
                    }
                    ForEach(model.messages) { message in
                        MessageView(message: message,
                                    isStreaming: model.isGenerating && message.id == model.messages.last?.id)
                            .id(message.id)
                    }
                    Color.clear.frame(height: 1).id("bottom")
                }
                .padding()
            }
            .defaultScrollAnchor(.bottom)
            .scrollDismissesKeyboard(.interactively)
            .onChange(of: model.messages.last?.content) {
                if model.isGenerating { proxy.scrollTo("bottom", anchor: .bottom) }
            }
        }
        .safeAreaInset(edge: .bottom) {
            InputBar()
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
        .onChange(of: scenePhase) { _, phase in
            if phase == .background { model.interruptForBackground() }
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

/// Text field, thinking toggle and send / stop.
struct InputBar: View {
    @Environment(ConversationModel.self) private var model
    @Environment(ModelRuntime.self) private var runtime
    @FocusState private var focused: Bool

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
    }
}
