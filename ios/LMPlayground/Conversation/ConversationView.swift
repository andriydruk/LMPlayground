import SwiftUI

/// The chat with its navigation bar, and the model picker card over both
/// (the card dims the bar too, so it lives outside the navigation stack).
struct ChatScreen: View {
    @Environment(ConversationModel.self) private var model

    @State private var showPicker = false
    @State private var showParams = false
    @State private var showLibrary = false
    @State private var pendingTooLarge: (ModelDescriptor, String)?

    var body: some View {
        NavigationStack {
            ConversationView(showPicker: $showPicker, showParams: $showParams, showLibrary: $showLibrary)
        }
        .overlay {
            if showPicker {
                ModelPickerCard(isPresented: $showPicker,
                                onTooLarge: { descriptor, message in pendingTooLarge = (descriptor, message) },
                                onBrowse: { showLibrary = true },
                                onParams: { showParams = true })
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
    }
}

struct ConversationView: View {
    @Environment(ConversationModel.self) private var model
    @Environment(DictationController.self) private var dictation
    @Environment(ModelRuntime.self) private var runtime
    @Environment(ModelStore.self) private var store
    @Environment(\.openSidebar) private var openSidebar
    @Environment(\.scenePhase) private var scenePhase

    @Binding var showPicker: Bool
    @Binding var showParams: Bool
    @Binding var showLibrary: Bool
    /// The user message open in the in-place editor.
    @State private var editing: ChatMessage.ID?
    @State private var sessionReport: String?

    var body: some View {
        messageList
        // An inset, not an overlay: the scroll view reaches under the bar, so
        // an overlay's top edge would sit under the status bar.
        .safeAreaInset(edge: .top, spacing: 0) {
            LoadingHairline()
        }
        .safeAreaInset(edge: .bottom) {
            VStack(spacing: 8) {
                HintPills()
                InputBar(showParams: $showParams)
            }
        }
        .navigationTitle(model.session?.title ?? String(localized: "New chat"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if let openSidebar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("Open navigation drawer", systemImage: "line.3.horizontal") { openSidebar() }
                        .accessibilityIdentifier("menuButton")
                }
            }
            ToolbarItem(placement: .principal) {
                ModelTitle(action: openPicker)
                    .disabled(model.isGenerating)
            }
            ToolbarItem(placement: .topBarTrailing) {
                Button("New chat", systemImage: "square.and.pencil") { model.newConversation() }
                    .keyboardShortcut("n", modifiers: .command)
                    .accessibilityIdentifier("newChatButton")
            }
        }
        .onChange(of: model.isGenerating) { _, generating in
            if generating { editing = nil }
        }
        .onChange(of: model.session?.id) { editing = nil }
        .alert("Session Info", isPresented: Binding(
            get: { sessionReport != nil }, set: { if !$0 { sessionReport = nil } })) {
            Button("Close", role: .cancel) {}
        } message: {
            Text(sessionReport ?? "")
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

    private var messageList: some View {
        MessageList(editing: editing) { message in
            let isLast = message.id == model.messages.last?.id
            MessageView(message: message,
                        isStreaming: model.isGenerating && isLast,
                        actions: actions(for: message, isLast: isLast),
                        isEditing: editing == message.id,
                        onEditEnded: { text in
                            editing = nil
                            if let text { model.edit(message, text: text) }
                        })
                .equatable()
        }
        // A list of its own per chat, which opens at its end: one opened in
        // the last chat's took over where that was scrolled to, which could
        // point where nothing was drawn.
        .id(model.session?.id)
        .scrollDismissesKeyboard(.interactively)
        // A tap on the chat closes the keyboard; buttons and links in the
        // messages take their taps first. Not while a message is being
        // edited: its field is in the chat.
        .onTapGesture {
            if editing == nil { UIApplication.shared.dismissKeyboard() }
        }
        .overlay {
            // Android: What's New while no model is loaded.
            if model.messages.isEmpty && runtime.loaded == nil && !isLoading {
                WhatsNewView()
            }
        }
    }

    /// Edit and regenerate run the model; branching only needs a saved chat
    /// to copy. Nothing changes a chat while a reply is being written.
    private func actions(for message: ChatMessage, isLast: Bool) -> MessageActions {
        let idle = !model.isGenerating
        let ready = idle && runtime.loaded != nil
        var actions = MessageActions()
        switch message.role {
        case .user:
            if ready { actions.onEdit = { editing = message.id } }
        case .assistant:
            if ready && isLast {
                actions.onContinue = { model.continueReply() }
                actions.onRegenerate = { model.regenerate(message) }
            }
            if idle && model.session != nil {
                actions.onBranch = { model.branch(from: message) }
            }
            if model.hasEngineSession {
                actions.onSessionInfo = { Task { sessionReport = await model.sessionReport() } }
            }
        }
        return actions
    }

    private var isLoading: Bool {
        if case .loading = runtime.phase { return true }
        return false
    }

    private func openPicker() {
        // The card sits over the chat, not above the keyboard.
        UIApplication.shared.dismissKeyboard()
        if store.downloaded.isEmpty {
            showLibrary = true
        } else {
            withAnimation(.snappy(duration: 0.25)) { showPicker = true }
        }
    }
}

/// The chat's messages, opening at the end. Made anew for each chat, so its
/// scroll position starts there.
private struct MessageList<Row: View>: View {
    @Environment(ConversationModel.self) private var model
    /// The user message open in the in-place editor, scrolled to the middle.
    let editing: ChatMessage.ID?
    @ViewBuilder var row: (ChatMessage) -> Row

    /// Where the chat is scrolled: the message at the top while scrolled up,
    /// which then stays put as rows above it are measured. Scrolled to the
    /// end by the last message: to the bottom edge, it overshoots by the
    /// input's inset.
    @State private var position = ScrollPosition(idType: ChatMessage.ID.self)
    /// Whether the end of the conversation is on screen: streaming keeps it
    /// there, and scrolling up offers a way back instead.
    @State private var atBottom = true
    /// A finger is on the chat or it coasts from one: it goes where that
    /// takes it, and where it ends up decides whether the end is followed.
    @State private var touching = false
    @State private var tracking = Tracking()

    /// Outside SwiftUI's state: these change with every frame of a scroll,
    /// and nothing drawn depends on them.
    private final class Tracking {
        /// The top of what is on screen.
        var top: CGFloat = 0
        /// Stay at the end as it moves. A fast reply outruns any check of
        /// how far the end is, so only a drag away turns this off.
        var following = true
    }

    /// How many of the latest messages are drawn; more join on the way up.
    @State private var shown = Self.page

    private static var page: Int { 30 }
    /// How far from the top the next page starts being drawn.
    private static var pageMargin: CGFloat { 1000 }
    /// How far from the end still counts as at it.
    private static var endSlack: CGFloat { 40 }

    /// What the chat reacts to as it scrolls or its frame changes.
    private struct Extent: Equatable {
        var top: CGFloat
        var fromEnd: CGFloat
        var contentHeight: CGFloat
        var visibleHeight: CGFloat
    }

    var body: some View {
        ScrollView {
            // Not lazy: a lazy stack estimates the rows it hasn't drawn, and
            // redoes that when one changes height, which moves the chat. The
            // cost stays down by drawing the latest messages only.
            VStack(alignment: .leading, spacing: 16) {
                ForEach(model.messages.suffix(shown)) { message in
                    row(message)
                        .id(message.id)
                }
            }
            .scrollTargetLayout()
            .padding()
            // At the end, the chat keeps the last message in place, so a
            // section opening in it would push the text above it up.
            .environment(\.holdScroll, HoldScrollAction { [tracking] in
                // Scrolled up, the chat already keeps the message at the top
                // in place.
                guard tracking.following else { return }
                tracking.following = false
                position.scrollTo(y: tracking.top)
            })
        }
        .scrollPosition($position)
        .defaultScrollAnchor(.bottom, for: .initialOffset)
        // A short chat sits at the bottom, by the input.
        .defaultScrollAnchor(.bottom, for: .alignment)
        // Content growing keeps what is on screen in place; following the
        // end is up to the chat.
        .defaultScrollAnchor(.top, for: .sizeChanges)
        .onScrollGeometryChange(for: Extent.self, of: Self.extent, action: scrolled)
        .onScrollPhaseChange { _, phase in touching = phase == .interacting || phase == .decelerating }
        .onChange(of: editing) { _, id in
            if let id { withAnimation { position.scrollTo(id: id, anchor: .center) } }
        }
        // A reply starting (send, edit, regenerate) brings the end into view.
        .onChange(of: model.messages.last?.id) {
            if model.isGenerating { scrollToEnd() }
        }
        .overlay(alignment: .bottomTrailing) {
            Group {
                if !atBottom && !model.messages.isEmpty {
                    Button("Jump to bottom", systemImage: "arrow.down", action: scrollToEnd)
                        .labelStyle(.iconOnly)
                        .font(.headline)
                        .frame(width: 44, height: 44)
                        .glassEffect(.regular.interactive(), in: .circle)
                        .padding()
                        .transition(.opacity.combined(with: .scale))
                        .accessibilityIdentifier("jumpToBottom")
                }
            }
            // On the button alone: around the list, it would animate whatever
            // else changes as the end goes out of view.
            .animation(.easeInOut(duration: 0.2), value: atBottom)
        }
    }

    /// Once there, again: the way down was worked out from the estimated
    /// heights of rows not drawn yet, and the last ones are measured now.
    private func scrollToEnd() {
        guard let last = model.messages.last?.id else { return }
        tracking.following = true
        withAnimation {
            position.scrollTo(id: last, anchor: .bottom)
        } completion: {
            position.scrollTo(id: last, anchor: .bottom)
        }
    }

    private static func extent(_ geometry: ScrollGeometry) -> Extent {
        // The container is the part between the bars; the offset counts from
        // under the top one.
        let top = geometry.contentOffset.y + geometry.contentInsets.top
        let visibleEnd = top + geometry.containerSize.height
        return Extent(top: top, fromEnd: geometry.contentSize.height - visibleEnd,
                      contentHeight: geometry.contentSize.height, visibleHeight: geometry.containerSize.height)
    }

    private func scrolled(from old: Extent, to new: Extent) {
        tracking.top = new.top
        if touching {
            tracking.following = new.fromEnd <= Self.endSlack
            if new.top < Self.pageMargin && shown < model.messages.count { shown += Self.page }
        }
        // At the end, it stays there as the reply streams in and as the
        // keyboard or a taller input takes room.
        let resized = new.visibleHeight != old.visibleHeight
            || (model.isGenerating && new.contentHeight != old.contentHeight)
        if resized && tracking.following && !touching, let last = model.messages.last?.id {
            position.scrollTo(id: last, anchor: .bottom)
            atBottom = true
        } else {
            atBottom = new.fromEnd <= Self.endSlack
        }
    }
}

/// Keeps the chat where it is scrolled to: called by a message just before
/// it opens or closes a section, so the section grows down from under the
/// finger.
struct HoldScrollAction {
    let action: () -> Void
    func callAsFunction() { action() }
}

extension EnvironmentValues {
    @Entry var holdScroll: HoldScrollAction?
}

/// The centered title: the model's name over its load progress or size, and
/// a chevron that opens the picker (Android: ConversationBar).
private struct ModelTitle: View {
    @Environment(ModelRuntime.self) private var runtime
    @Environment(ModelStore.self) private var store
    var action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(spacing: 0) {
                HStack(spacing: 4) {
                    Text(title)
                        .font(.headline)
                        .lineLimit(1)
                    Image(systemName: "chevron.down")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(.secondary)
                }
                if let subtitle {
                    Text(verbatim: subtitle)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .monospacedDigit()
                        .contentTransition(.numericText())
                }
            }
            .padding(.horizontal, 12)
            .contentShape(.rect)
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("modelPickerButton")
    }

    private var title: String {
        switch runtime.phase {
        case .loading(let filename, _):
            return store.downloaded.first { $0.filename == filename }?.name ?? filename
        default:
            return runtime.loaded?.descriptor.name ?? String(localized: "Choose a model")
        }
    }

    private var subtitle: String? {
        if case .loading(_, let progress) = runtime.phase {
            return progress > 0 ? progress.formatted(.percent.precision(.fractionLength(0)))
                                : String(localized: "Loading…")
        }
        guard let loaded = runtime.loaded?.descriptor else { return nil }
        return store.reclaimableBytes(loaded).formattedBytes
    }
}

/// Model-loading progress as a hairline along the bottom of the navigation
/// bar (Android: the line under ConversationBar).
private struct LoadingHairline: View {
    @Environment(ModelRuntime.self) private var runtime

    var body: some View {
        if case .loading(_, let progress) = runtime.phase {
            GeometryReader { geometry in
                Rectangle()
                    .fill(.tint)
                    .frame(width: geometry.size.width * progress)
                    .animation(.linear(duration: 0.2), value: progress)
            }
            .frame(height: 2)
            .accessibilityHidden(true)
        }
    }
}

/// What's New, centered in an empty chat while no model is loaded (Android:
/// WhatsNewText). Same strings as Android, so it reads the same everywhere.
private struct WhatsNewView: View {
    var body: some View {
        VStack(spacing: 8) {
            Text("What's New")
                .font(.title.weight(.semibold))
                .padding(.bottom, 8)
            Text("Voice — tap the mic and speak")
            Text("Your words appear as you talk")
            Text("Works offline in 35 languages")
        }
        .font(.body)
        .multilineTextAlignment(.center)
        .foregroundStyle(.secondary)
        .padding(.horizontal, 32)
        .allowsHitTesting(false)
    }
}

/// The input: one line of glass with attach and dictation while idle;
/// focused (or holding text) it grows into a multi-line editor with the
/// thinking toggle and send. Swiping it up opens the generation parameters,
/// as on Android. Dictation writes into the draft whether or not the field
/// has focus.
struct InputBar: View {
    @Environment(ConversationModel.self) private var model
    @Environment(ModelRuntime.self) private var runtime
    @Environment(DictationController.self) private var dictation
    @Binding var showParams: Bool
    @FocusState private var focused: Bool
    /// Set by a tap on the collapsed bar until the editor takes focus.
    @State private var expanding = false
    /// The text before dictation began; recognized words are written on top
    /// of it, so rewriting never compounds. Nil while dictation isn't writing.
    @State private var dictationBase: String?
    /// What dictation last wrote, to tell the user's own edits apart.
    @State private var dictationWritten: String?

    private var expanded: Bool {
        expanding || focused || !model.draft.isEmpty || model.pendingImage != nil
    }

    var body: some View {
        VStack(spacing: 8) {
            if !model.documents.isEmpty {
                DocumentChipsRow()
            }
            Group {
                if expanded { editor } else { collapsed }
            }
            .simultaneousGesture(
                DragGesture(minimumDistance: 24).onEnded { value in
                    if value.translation.height < -40, abs(value.translation.width) < 60, runtime.loaded != nil {
                        showParams = true
                    }
                })
        }
        .padding(.horizontal, 12)
        .padding(.bottom, 8)
        .animation(.snappy(duration: 0.25), value: expanded)
        .onChange(of: focused) { _, now in
            if now { expanding = false }
        }
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

    /// One line: attach, a tap target that opens the editor, and the mic.
    private var collapsed: some View {
        HStack(spacing: 2) {
            attach
            Button {
                expanding = true
            } label: {
                Text("Message")
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
                    .padding(.leading, 4)
                    .contentShape(.rect)
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("messageField")
            // Dictation runs its own engine, so it works with no chat model
            // loaded; only a reply in progress blocks it.
            MicButton(enabled: !model.isGenerating)
            if model.isGenerating { stop }
        }
        .padding(4)
        .glassEffect(.regular.interactive(), in: .capsule)
    }

    /// The text on top, the controls in a row under it.
    private var editor: some View {
        @Bindable var model = model
        return VStack(alignment: .leading, spacing: 6) {
            if let image = model.pendingImage {
                PendingImagePreview(path: image)
                    .padding([.top, .horizontal], 10)
            }
            TextField("Message", text: $model.draft, axis: .vertical)
                .lineLimit(1...8)
                .focused($focused)
                .accessibilityIdentifier("messageField")
                .padding(.horizontal, 14)
                .padding(.top, 12)
                .onSubmit { if model.canSend { model.send() } }
                .task { if expanding { focused = true } }
            HStack(spacing: 2) {
                attach
                if runtime.loaded?.descriptor.offersThinkingToggle == true {
                    Button {
                        model.thinkingEnabled.toggle()
                    } label: {
                        Image(systemName: model.thinkingEnabled ? "lightbulb.fill" : "lightbulb")
                            .font(.title3)
                            .foregroundStyle(model.thinkingEnabled ? AnyShapeStyle(.tint) : AnyShapeStyle(.primary))
                            .frame(width: 44, height: 44)
                            .contentShape(.rect)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(model.thinkingEnabled ? "Thinking on" : "Thinking off")
                    .accessibilityIdentifier("thinkingToggle")
                }
                Spacer()
                MicButton(enabled: !model.isGenerating)
                if model.isGenerating { stop } else { send }
            }
            .padding(.horizontal, 4)
            .padding(.bottom, 4)
        }
        .glassEffect(.regular, in: .rect(cornerRadius: 26))
    }

    private var attach: some View {
        AttachmentMenu(allowsImages: runtime.loaded?.supportsVision == true)
            .disabled(runtime.loaded == nil || model.isGenerating)
    }

    private var send: some View {
        Button {
            model.send()
        } label: {
            Image(systemName: "arrow.up")
                .font(.body.weight(.semibold))
                .foregroundStyle(.white)
                .frame(width: 36, height: 36)
                .background(Circle().fill(model.canSend ? AnyShapeStyle(.tint) : AnyShapeStyle(.gray.opacity(0.4))))
                .frame(width: 44, height: 44)
                .contentShape(.rect)
        }
        .buttonStyle(.plain)
        .disabled(!model.canSend)
        .accessibilityLabel("Send")
        .accessibilityIdentifier("sendButton")
        .keyboardShortcut(.return, modifiers: .command)
    }

    private var stop: some View {
        Button {
            model.stop()
        } label: {
            Image(systemName: "stop.fill")
                .font(.footnote)
                .foregroundStyle(Color(.systemBackground))
                .frame(width: 36, height: 36)
                .background(Circle().fill(.primary))
                .frame(width: 44, height: 44)
                .contentShape(.rect)
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Stop")
        .accessibilityIdentifier("stopButton")
        .keyboardShortcut(".", modifiers: .command)
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
            if let notice = model.notice {
                Text(verbatim: notice)
                    .font(.subheadline.weight(.medium))
                    .padding(.horizontal, 14)
                    .padding(.vertical, 8)
                    .background(.regularMaterial, in: Capsule())
                    .transition(.opacity.combined(with: .move(edge: .bottom)))
                    .task(id: notice) {
                        try? await Task.sleep(for: .seconds(3))
                        if !Task.isCancelled { model.notice = nil }
                    }
            }
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
        .animation(.easeInOut(duration: 0.2), value: model.notice)
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
