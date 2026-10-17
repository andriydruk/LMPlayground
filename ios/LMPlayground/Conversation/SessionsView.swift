import SwiftUI

/// Saved conversations, pinned first (Android: SessionListDrawer).
struct SessionsView: View {
    @Environment(ConversationModel.self) private var conversation
    @Environment(\.chatStore) private var chatStore
    @Binding var showSettings: Bool
    var onOpen: () -> Void = {}

    @State private var sessions: [ChatSession] = []
    @State private var renaming: ChatSession?
    @State private var newTitle = ""
    @State private var search = ""

    var body: some View {
        List(selection: Binding(
            get: { conversation.session?.id },
            set: { id in
                if let id, let chat = sessions.first(where: { $0.id == id }) {
                    conversation.open(chat)
                    onOpen()
                }
            })) {
            ForEach(filtered) { chat in
                VStack(alignment: .leading, spacing: 2) {
                    HStack(spacing: 4) {
                        if chat.pinned { Image(systemName: "pin.fill").font(.caption2).foregroundStyle(.orange) }
                        Text(chat.title).lineLimit(1)
                    }
                    Text("\(chat.modelName) · \(Date(milliseconds: chat.updatedAt).formatted(.relative(presentation: .named)))")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
                .tag(chat.id)
                .swipeActions(edge: .leading) {
                    Button(chat.pinned ? "Unpin" : "Pin", systemImage: chat.pinned ? "pin.slash" : "pin") {
                        try? chatStore?.pinSession(chat.id, pinned: !chat.pinned)
                    }
                    .tint(.orange)
                }
                .swipeActions(edge: .trailing) {
                    Button("Delete", systemImage: "trash", role: .destructive) { delete(chat) }
                    Button("Rename", systemImage: "pencil") {
                        newTitle = chat.title
                        renaming = chat
                    }
                }
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
        }
        .overlay {
            if sessions.isEmpty {
                ContentUnavailableView("No conversations yet", systemImage: "bubble.left.and.bubble.right",
                                       description: Text("Your chats are saved here."))
            }
        }
        .searchable(text: $search, placement: .sidebar)
        .navigationTitle("Chats")
        .toolbar {
            ToolbarItem(placement: .topBarLeading) {
                Button("Settings", systemImage: "gearshape") { showSettings = true }
            }
            ToolbarItem(placement: .topBarTrailing) {
                Button("New chat", systemImage: "square.and.pencil") {
                    conversation.newConversation()
                    onOpen()
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

    private var filtered: [ChatSession] {
        let query = search.trimmingCharacters(in: .whitespaces)
        guard !query.isEmpty else { return sessions }
        return sessions.filter { $0.title.localizedCaseInsensitiveContains(query) || $0.modelName.localizedCaseInsensitiveContains(query) }
    }

    private func delete(_ chat: ChatSession) {
        if conversation.session?.id == chat.id { conversation.newConversation() }
        let images = (try? chatStore?.deleteSession(chat.id)) ?? []
        ChatImageStore.delete(images)
    }
}
