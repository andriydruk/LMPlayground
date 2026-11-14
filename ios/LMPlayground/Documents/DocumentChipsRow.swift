import QuickLook
import SwiftUI

/// One chip per document attached to the conversation, above the text
/// field (Android: DocumentChipsRow): a spinner while indexing, a document
/// icon when ready (tap previews the file). Failed documents never appear;
/// their reason is shown as an alert instead.
struct DocumentChipsRow: View {
    @Environment(ConversationModel.self) private var model
    @State private var preview: URL?
    @State private var pendingRemoval: RagDocument?

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(model.documents) { document in
                    chip(document)
                }
            }
            .padding(.horizontal)
        }
        .quickLookPreview($preview)
        .confirmationDialog("Remove document?", isPresented: Binding(
            get: { pendingRemoval != nil }, set: { if !$0 { pendingRemoval = nil } }),
                            titleVisibility: .visible, presenting: pendingRemoval) { document in
            Button("Remove", role: .destructive) { model.removeDocument(document) }
        } message: { document in
            Text("“\(document.displayName)” and its indexed content will be removed from this chat.")
        }
    }

    private func chip(_ document: RagDocument) -> some View {
        HStack(spacing: 6) {
            Button {
                if document.isReady { preview = model.rag.fileURL(for: document) }
            } label: {
                HStack(spacing: 6) {
                    if document.isReady {
                        Image(systemName: "doc.text")
                            .accessibilityLabel("Attached document")
                    } else {
                        ProgressView().controlSize(.small)
                            .accessibilityLabel("Indexing")
                    }
                    Text(document.displayName)
                        .lineLimit(1)
                        .truncationMode(.middle)
                        .frame(maxWidth: 180)
                }
            }
            .buttonStyle(.plain)
            Button("Remove document", systemImage: "xmark") { pendingRemoval = document }
                .labelStyle(.iconOnly)
                .font(.caption.bold())
                .foregroundStyle(.secondary)
                .buttonStyle(.borderless)
        }
        .font(.subheadline)
        .padding(.horizontal, 10)
        .padding(.vertical, 6)
        .background(.background.secondary, in: RoundedRectangle(cornerRadius: 8))
        .overlay(RoundedRectangle(cornerRadius: 8).stroke(.quaternary))
        .accessibilityIdentifier("documentChip")
    }
}
