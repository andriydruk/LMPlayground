import SwiftUI

/// Saved conversations, pinned first, one line each (Android:
/// SessionListDrawer). In the iPhone drawer it draws its own header; in the
/// iPad split view the navigation bar carries the title and buttons.
struct SessionsView: View {
    enum Style { case drawer, sidebar }

    @Environment(ConversationModel.self) private var conversation
    @Environment(\.chatStore) private var chatStore
    var style: Style = .sidebar
    @Binding var showSettings: Bool
    var onOpen: () -> Void = {}

    @State private var sessions: [ChatSession] = []
    @State private var renaming: ChatSession?
    @State private var newTitle = ""

    var body: some View {
        VStack(spacing: 0) {
            if style == .drawer {
                header
            }
            list
        }
        .navigationTitle("Chats")
        .toolbar {
            if style == .sidebar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("Settings", systemImage: "gearshape") { showSettings = true }
                        .keyboardShortcut(",", modifiers: .command)
                }
            }
        }
        .alert("Rename chat", isPresented: Binding(get: { renaming != nil }, set: { if !$0 { renaming = nil } })) {
            TextField("Title", text: $newTitle)
            Button("Save") {
                if let chat = renaming { try? chatStore?.renameSession(chat.id, title: newTitle) }
            }
            Button("Cancel", role: .cancel) {}
        }
        .task {
            guard let chatStore else { return }
            do {
                for try await value in chatStore.observeSessions() { sessions = value }
            } catch {}
        }
    }

    private var header: some View {
        HStack {
            Text("Chats")
                .font(.title2.weight(.semibold))
            Spacer()
            Button("Settings", systemImage: "gearshape") { showSettings = true }
                .labelStyle(.iconOnly)
                .font(.title3)
                .frame(width: 44, height: 44)
                .glassEffect(.regular.interactive(), in: .circle)
                .accessibilityIdentifier("settingsButton")
        }
        .padding(.leading, 20)
        .padding(.trailing, 12)
        .padding(.vertical, 8)
    }

    private var list: some View {
        List {
            ForEach(sessions) { chat in
                row(chat)
            }
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
        .overlay {
            if sessions.isEmpty {
                ContentUnavailableView("No conversations yet", systemImage: "bubble.left.and.bubble.right",
                                       description: Text("Your chats are saved here."))
            }
        }
    }

    private func row(_ chat: ChatSession) -> some View {
        let selected = conversation.session?.id == chat.id
        return Button {
            if !selected { conversation.open(chat) }
            onOpen()
        } label: {
            HStack(spacing: 6) {
                Text(chat.title)
                    .lineLimit(1)
                    .frame(maxWidth: .infinity, alignment: .leading)
                if chat.pinned {
                    Image(systemName: "pin.fill")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .accessibilityLabel("Pinned")
                }
            }
            .contentShape(.rect)
        }
        .buttonStyle(.plain)
        .listRowSeparator(.hidden)
        .listRowInsets(EdgeInsets(top: 0, leading: 20, bottom: 0, trailing: 16))
        .listRowBackground(
            Capsule()
                .fill(selected ? Color.secondary.opacity(0.2) : .clear)
                .padding(.horizontal, 8)
                .padding(.vertical, 1)
        )
        // A long press, as on Android; horizontal drags close the drawer.
        .contextMenu {
            Button(chat.pinned ? "Unpin" : "Pin", systemImage: chat.pinned ? "pin.slash" : "pin") {
                try? chatStore?.pinSession(chat.id, pinned: !chat.pinned)
            }
            Button("Rename", systemImage: "pencil") {
                newTitle = chat.title
                renaming = chat
            }
            Button("Delete", systemImage: "trash", role: .destructive) { delete(chat) }
        }
    }

    private func delete(_ chat: ChatSession) {
        if conversation.session?.id == chat.id { conversation.newConversation() }
        let images = (try? chatStore?.deleteSession(chat.id)) ?? []
        ChatImageStore.delete(images)
        // Its documents' rows went with it; drop their file copies.
        conversation.rag.sweepOrphanFiles()
    }
}
