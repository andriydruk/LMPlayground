import SwiftUI

/// The saved system prompts library (Android: SystemPromptsScreen).
struct SystemPromptsView: View {
    @Environment(\.chatStore) private var chatStore
    @State private var prompts: [SystemPrompt] = []
    @State private var editing: SystemPrompt?
    @State private var creating = false

    var body: some View {
        List {
            ForEach(prompts) { prompt in
                Button {
                    editing = prompt
                } label: {
                    Text(prompt.text).lineLimit(3).foregroundStyle(.primary)
                }
            }
            .onDelete { offsets in
                for index in offsets { try? chatStore?.deletePrompt(prompts[index].id) }
            }
        }
        .overlay {
            if prompts.isEmpty {
                ContentUnavailableView("No saved prompts", systemImage: "text.quote",
                                       description: Text("Save a persona, tone or output format once and use it with any model."))
            }
        }
        .navigationTitle("System prompts")
        .toolbar {
            Button("Add", systemImage: "plus") { creating = true }
        }
        .sheet(item: $editing) { prompt in
            PromptEditor(initial: prompt.text) { text in try? chatStore?.updatePrompt(prompt.id, text: text) }
        }
        .sheet(isPresented: $creating) {
            PromptEditor(initial: "") { text in _ = try? chatStore?.createPrompt(text: text) }
        }
        .task {
            guard let chatStore else { return }
            do {
                for try await value in chatStore.observePrompts() { prompts = value }
            } catch {}
        }
    }
}

private struct PromptEditor: View {
    @Environment(\.dismiss) private var dismiss
    let initial: String
    let onSave: (String) -> Void
    @State private var text = ""

    var body: some View {
        NavigationStack {
            TextEditor(text: $text)
                .padding()
                .navigationTitle(initial.isEmpty ? "New prompt" : "Edit prompt")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                    ToolbarItem(placement: .confirmationAction) {
                        Button("Save") { onSave(text); dismiss() }
                            .disabled(text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                    }
                }
                .onAppear { text = initial }
        }
    }
}
