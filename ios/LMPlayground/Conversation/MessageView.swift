import SwiftUI

/// What can be done with a message; nil hides the action (Android: the
/// callbacks of Message and ChatItemBubble).
struct MessageActions {
    /// Offered on an interrupted last reply.
    var onContinue: (() -> Void)?
    /// Open the in-place editor on a user message.
    var onEdit: (() -> Void)?
    var onRegenerate: (() -> Void)?
    var onBranch: (() -> Void)?
    var onSessionInfo: (() -> Void)?
}

/// One message. User messages are right-aligned bubbles, with Copy and Edit
/// on a long press; assistant replies show their thinking (collapsed), tool
/// rounds, the Markdown answer and a row of actions.
struct MessageView: View {
    let message: ChatMessage
    let isStreaming: Bool
    var actions = MessageActions()
    var isEditing = false
    /// Ends editing, with the new text or nil for Cancel.
    var onEditEnded: (String?) -> Void = { _ in }
    @State private var viewing: String?

    private struct ViewedImage: Identifiable {
        let path: String
        var id: String { path }
    }

    private var bubbleShape: UnevenRoundedRectangle {
        UnevenRoundedRectangle(topLeadingRadius: 20, bottomLeadingRadius: 20, bottomTrailingRadius: 4, topTrailingRadius: 20)
    }

    var body: some View {
        switch message.role {
        case .user:
            HStack {
                Spacer(minLength: isEditing ? 0 : 48)
                VStack(alignment: .trailing, spacing: 6) {
                if let path = message.imagePath {
                    Button { viewing = path } label: {
                        StoredImage(path: path)
                            .frame(width: 180, height: 180)
                            .clipShape(RoundedRectangle(cornerRadius: 14))
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("Attached image")
                }
                if isEditing {
                    UserMessageEditor(initialText: message.content, allowsEmpty: message.imagePath != nil,
                                      onCancel: { onEditEnded(nil) }, onSend: { onEditEnded($0) })
                } else {
                    // A menu anchored to the bubble, opened by a long press
                    // (Android: a dropdown). A context menu would preview the
                    // text, and iOS adds its own items, Ask Siri among them.
                    Menu {
                        Button("Copy", systemImage: "doc.on.doc") {
                            UIPasteboard.general.string = message.content
                        }
                        if let onEdit = actions.onEdit {
                            Button("Edit", systemImage: "pencil", action: onEdit)
                        }
                    } label: {
                        Text(message.content)
                            .multilineTextAlignment(.leading)
                            .padding(.horizontal, 14)
                            .padding(.vertical, 10)
                            .background(Color.accentColor, in: bubbleShape)
                            .foregroundStyle(.white)
                    } primaryAction: {}
                    .menuIndicator(.hidden)
                    .buttonStyle(.plain)
                    .accessibilityIdentifier("userMessage")
                }
                }
            }
            .fullScreenCover(item: Binding(get: { viewing.map(ViewedImage.init) }, set: { viewing = $0?.path })) { item in
                ImageViewer(path: item.path)
            }
        case .assistant:
            AssistantMessageView(message: message, isStreaming: isStreaming, actions: actions)
        }
    }
}

/// Replaces a user bubble: Send re-sends the edited text from this point of
/// the chat, replacing what followed it.
private struct UserMessageEditor: View {
    let initialText: String
    let allowsEmpty: Bool
    var onCancel: () -> Void
    var onSend: (String) -> Void

    @State private var text = ""
    @FocusState private var focused: Bool

    private var canSend: Bool {
        allowsEmpty || !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    var body: some View {
        VStack(alignment: .trailing, spacing: 8) {
            TextField("Message", text: $text, axis: .vertical)
                .lineLimit(1...10)
                .focused($focused)
                .padding(.horizontal, 14)
                .padding(.vertical, 10)
                .overlay(RoundedRectangle(cornerRadius: 20).stroke(.tint))
                .accessibilityIdentifier("messageEditor")
            HStack(spacing: 8) {
                Button("Cancel", action: onCancel)
                    .buttonStyle(.bordered)
                Button("Send") { onSend(text) }
                    .buttonStyle(.borderedProminent)
                    .disabled(!canSend)
                    .accessibilityIdentifier("editorSendButton")
            }
        }
        .onAppear {
            text = initialText
            focused = true
        }
    }
}

private struct AssistantMessageView: View {
    let message: ChatMessage
    let isStreaming: Bool
    var actions = MessageActions()

    var body: some View {
        let split = ThinkingSplit(message.content)
        VStack(alignment: .leading, spacing: 10) {
            // Tool rounds, each preceded by the thinking that led to it.
            ForEach(Array(message.toolCalls.enumerated()), id: \.offset) { _, call in
                let pre = ThinkingSplit(call.precedingThinking)
                if !pre.thinking.isEmpty {
                    ThinkingSection(text: pre.thinking, seconds: call.precedingThinkingDurationSeconds,
                                    tokens: call.precedingThinkingTokens, active: false)
                }
                ToolCallView(call: call)
            }

            if !split.thinking.isEmpty {
                ThinkingSection(text: split.thinking, seconds: message.thinkingDurationSeconds,
                                tokens: message.thinkingTokens,
                                active: isStreaming && split.response.isEmpty && !message.content.contains("</think>"))
            } else if isStreaming && message.content.isEmpty {
                TypingIndicator()
            }

            if !split.response.isEmpty {
                MarkdownView(text: split.response)
                    .accessibilityElement(children: .combine)
                    .accessibilityIdentifier("assistantAnswer")
            }

            if message.interrupted && !isStreaming {
                HStack(spacing: 12) {
                    Label("Stopped when the app went to the background", systemImage: "pause.circle")
                        .accessibilityIdentifier("interruptedLabel")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    if let onContinue = actions.onContinue {
                        Button("Continue", systemImage: "play.fill", action: onContinue)
                            .font(.caption.bold())
                            .buttonStyle(.bordered)
                            .buttonBorderShape(.capsule)
                            .accessibilityIdentifier("continueButton")
                    }
                }
            }

            if !isStreaming {
                ReplyActions(text: split.response, stats: message.totalTokens > 0 ? stats : nil, actions: actions)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var stats: AttributedString {
        // Plural forms per language come from the string catalog.
        var text = AttributedString(localized: "\(message.totalTokens) tokens")
        if message.responseDurationSeconds > 0 {
            let rate = (Double(message.totalTokens) / message.responseDurationSeconds)
                .formatted(.number.precision(.fractionLength(1)))
            text += AttributedString(" · ") + AttributedString(localized: "\(rate) tok/s")
        }
        return text
    }
}

/// The row under a reply: share, copy, rating, a menu with Regenerate and
/// Branch, and the stats, which open Session Info (Android: ChatItemBubble).
private struct ReplyActions: View {
    let text: String
    let stats: AttributedString?
    let actions: MessageActions

    private enum Rating { case up, down }

    @State private var rating: Rating?
    @State private var showRatingSheet = false

    var body: some View {
        HStack(spacing: 0) {
            ShareLink(item: text) { icon("Share", "square.and.arrow.up") }
            Button { UIPasteboard.general.string = text } label: { icon("Copy", "doc.on.doc") }
            Button {
                rating = rating == .up ? nil : .up
            } label: {
                icon("Upvote", rating == .up ? "hand.thumbsup.fill" : "hand.thumbsup")
            }
            Button {
                rating = .down
                showRatingSheet = true
            } label: {
                icon("Downvote", rating == .down ? "hand.thumbsdown.fill" : "hand.thumbsdown")
            }
            if actions.onRegenerate != nil || actions.onBranch != nil {
                Menu {
                    if let onRegenerate = actions.onRegenerate {
                        Button("Regenerate", systemImage: "arrow.clockwise", action: onRegenerate)
                    }
                    if let onBranch = actions.onBranch {
                        Button("Branch in new chat", systemImage: "arrow.triangle.branch", action: onBranch)
                    }
                } label: {
                    icon("More options", "ellipsis")
                }
                .accessibilityIdentifier("moreOptionsButton")
            }
            if let stats {
                Group {
                    if let onSessionInfo = actions.onSessionInfo {
                        Button(action: onSessionInfo) { Text(stats) }
                            .accessibilityIdentifier("sessionInfoButton")
                    } else {
                        Text(stats)
                    }
                }
                .font(.caption.monospacedDigit())
                .foregroundStyle(.tertiary)
                .lineLimit(1)
                .minimumScaleFactor(0.8)
                .padding(.leading, 6)
            }
        }
        .buttonStyle(.borderless)
        .foregroundStyle(.secondary)
        .sheet(isPresented: $showRatingSheet) {
            RatingSheet()
        }
    }

    private func icon(_ title: LocalizedStringKey, _ systemImage: String) -> some View {
        Label(title, systemImage: systemImage)
            .labelStyle(.iconOnly)
            .font(.footnote)
            .frame(width: 36, height: 36)
            .contentShape(.rect)
    }
}

/// Why a reply was rated down (Android: RatingBottomSheet).
private struct RatingSheet: View {
    @Environment(\.dismiss) private var dismiss
    @State private var selected: Set<String> = []

    private let reasons: [LocalizedStringResource] = [
        "Offensive/Unsafe", "Not correct", "Didn't follow instructions", "Wrong language", "Other",
    ]

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Why did you choose this rating?")
                .font(.headline)
            ForEach(reasons, id: \.key) { reason in
                Toggle(isOn: Binding(
                    get: { selected.contains(reason.key) },
                    set: { isOn in
                        if isOn { selected.insert(reason.key) } else { selected.remove(reason.key) }
                    })) {
                    Text(reason)
                }
                .toggleStyle(.button)
                .buttonStyle(.bordered)
                .buttonBorderShape(.capsule)
            }
            Button {
                dismiss()
            } label: {
                Text("Send").frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
            .padding(.top, 4)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(20)
        .presentationDetents([.medium])
    }
}

/// The model's reasoning: collapsed by default, with duration and tokens.
struct ThinkingSection: View {
    let text: String
    let seconds: Int
    let tokens: Int
    let active: Bool
    @State private var expanded = false

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Button {
                withAnimation(.snappy) { expanded.toggle() }
            } label: {
                HStack(spacing: 6) {
                    Image(systemName: "lightbulb")
                        .accessibilityHidden(true)
                    Text(title)
                    Image(systemName: "chevron.right")
                        .rotationEffect(.degrees(expanded ? 90 : 0))
                        .font(.caption2)
                        .accessibilityHidden(true)
                }
                .font(.subheadline)
                .foregroundStyle(.secondary)
            }
            .buttonStyle(.plain)
            .accessibilityHint(expanded ? "Collapse thinking" : "Expand thinking")

            if expanded {
                Text(text)
                    .font(.callout)
                    .foregroundStyle(.secondary)
                    .textSelection(.enabled)
                    .padding(.leading, 10)
                    .overlay(alignment: .leading) {
                        Rectangle().fill(.quaternary).frame(width: 2)
                    }
            }
        }
    }

    private var title: String {
        if active { return String(localized: "Thinking… \(seconds)s") }
        var parts = [String(localized: "Thought for \(seconds)s")]
        if tokens > 0 { parts.append(String(localized: "\(tokens) tokens")) }
        return parts.joined(separator: " · ")
    }
}

struct ToolCallView: View {
    let call: ToolCallInfo
    @State private var expanded = false

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Button {
                withAnimation(.snappy) { expanded.toggle() }
            } label: {
                Label("\(call.name) · \(Double(call.durationMs) / 1000, specifier: "%.1f")s", systemImage: "wrench.and.screwdriver")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
            .buttonStyle(.plain)
            if expanded {
                Text(call.arguments).font(.caption.monospaced())
                Text(call.result).font(.caption.monospaced()).foregroundStyle(.secondary).lineLimit(20)
            }
        }
    }
}

private struct TypingIndicator: View {
    var body: some View {
        ProgressView().controlSize(.small)
            .accessibilityLabel("Generating")
    }
}
