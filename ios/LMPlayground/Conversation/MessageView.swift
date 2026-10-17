import SwiftUI

/// One message. User messages are right-aligned bubbles; assistant replies
/// show their thinking (collapsed), tool rounds, the Markdown answer and stats.
struct MessageView: View {
    let message: ChatMessage
    let isStreaming: Bool
    /// Offered on an interrupted last reply.
    var onContinue: (() -> Void)?
    @State private var viewing: String?

    private struct ViewedImage: Identifiable {
        let path: String
        var id: String { path }
    }

    var body: some View {
        switch message.role {
        case .user:
            HStack {
                Spacer(minLength: 48)
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
                Text(message.content)
                    .textSelection(.enabled)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 10)
                    .background(Color.accentColor, in: UnevenRoundedRectangle(
                        topLeadingRadius: 20, bottomLeadingRadius: 20, bottomTrailingRadius: 4, topTrailingRadius: 20))
                    .foregroundStyle(.white)
                }
            }
            .fullScreenCover(item: Binding(get: { viewing.map(ViewedImage.init) }, set: { viewing = $0?.path })) { item in
                ImageViewer(path: item.path)
            }
        case .assistant:
            AssistantMessageView(message: message, isStreaming: isStreaming, onContinue: onContinue)
        }
    }
}

private struct AssistantMessageView: View {
    let message: ChatMessage
    let isStreaming: Bool
    var onContinue: (() -> Void)?

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
                    if let onContinue {
                        Button("Continue", systemImage: "play.fill", action: onContinue)
                            .font(.caption.bold())
                            .buttonStyle(.bordered)
                            .buttonBorderShape(.capsule)
                            .accessibilityIdentifier("continueButton")
                    }
                }
            }

            if !isStreaming && message.totalTokens > 0 {
                HStack(spacing: 12) {
                    Text(stats).font(.caption.monospacedDigit()).foregroundStyle(.tertiary)
                    Spacer()
                    Button("Copy", systemImage: "doc.on.doc") {
                        UIPasteboard.general.string = split.response
                    }
                    ShareLink(item: split.response) { Image(systemName: "square.and.arrow.up") }
                }
                .labelStyle(.iconOnly)
                .buttonStyle(.borderless)
                .font(.footnote)
                .foregroundStyle(.secondary)
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
                    Image(systemName: "brain")
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
