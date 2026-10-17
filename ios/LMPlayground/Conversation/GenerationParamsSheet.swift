import SwiftUI

/// Sampling, context and system prompt for the current conversation
/// (Android: GenerationParamsSheet + SystemPromptPickerRow).
struct GenerationParamsSheet: View {
    @Environment(ConversationModel.self) private var model
    @Environment(ModelRuntime.self) private var runtime
    @Environment(\.dismiss) private var dismiss

    @State private var params = GenerationParams()
    @State private var systemPrompt = ""

    private static let contextSizes = [1024, 2048, 4096, 8192, 16384, 32768]

    var body: some View {
        NavigationStack {
            Form {
                Section("System prompt") {
                    TextField("Optional instructions for the model", text: $systemPrompt, axis: .vertical)
                        .lineLimit(3...10)
                    SystemPromptPicker(selection: $systemPrompt)
                }

                Section {
                    Picker("Context size", selection: $params.contextSize) {
                        ForEach(contextSizes, id: \.self) { Text("\($0) tokens").tag($0) }
                    }
                    if runtime.loaded?.supportsThinking == true {
                        Stepper(params.thinkingBudget < 0 ? String(localized: "Thinking budget: unlimited")
                                                          : String(localized: "Thinking budget: \(params.thinkingBudget)"),
                                value: $params.thinkingBudget, in: -1...16384, step: 256)
                    }
                } footer: {
                    Text("Larger contexts remember longer conversations but use more memory.")
                }

                Section("Sampling") {
                    slider("Temperature", value: $params.temperature, range: 0...2, format: "%.2f")
                    slider("Top P", value: $params.topP, range: 0...1, format: "%.2f")
                    slider("Min P", value: $params.minP, range: 0...1, format: "%.2f")
                    slider("Repetition penalty", value: $params.repetitionPenalty, range: 1...2, format: "%.2f")
                    Stepper("Top K: \(params.topK)", value: $params.topK, in: 0...200)
                    HStack {
                        Text("Seed")
                        Spacer()
                        TextField("Random", value: $params.seed, format: .number)
                            .keyboardType(.numbersAndPunctuation)
                            .multilineTextAlignment(.trailing)
                    }
                }

                Section {
                    Button("Reset to defaults") { params = GenerationParams() }
                }
            }
            .navigationTitle("Chat settings")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") {
                        if params != model.params { model.updateParams(params) }
                        if systemPrompt != model.systemPrompt { model.updateSystemPrompt(systemPrompt) }
                        dismiss()
                    }
                }
            }
            .onAppear {
                params = model.params
                systemPrompt = model.systemPrompt
            }
        }
    }

    private var contextSizes: [Int] {
        let limit = runtime.loaded?.contextTrainSize ?? Int.max
        return Self.contextSizes.filter { $0 <= max(limit, 1024) }
    }

    private func slider(_ title: LocalizedStringKey, value: Binding<Double>, range: ClosedRange<Double>,
                        format: String) -> some View {
        VStack(alignment: .leading) {
            HStack {
                Text(title)
                Spacer()
                Text(String(format: format, value.wrappedValue)).monospacedDigit().foregroundStyle(.secondary)
            }
            Slider(value: value, in: range)
        }
    }
}

/// Saved system prompts, most recently used with this model first.
struct SystemPromptPicker: View {
    @Environment(ModelRuntime.self) private var runtime
    @Environment(\.chatStore) private var chatStore
    @Binding var selection: String
    @State private var prompts: [SystemPrompt] = []

    var body: some View {
        Group {
            if !prompts.isEmpty {
                Menu("Saved prompts") {
                    ForEach(prompts) { prompt in
                        Button(prompt.text.prefix(60) + (prompt.text.count > 60 ? "…" : "")) {
                            selection = prompt.text
                            if let filename = runtime.loaded?.descriptor.filename {
                                try? chatStore?.touchPromptUsage(promptId: prompt.id, modelFilename: filename)
                            }
                        }
                    }
                }
            }
            if !selection.isEmpty, !prompts.contains(where: { $0.text == selection }) {
                Button("Save this prompt") {
                    _ = try? chatStore?.createPrompt(text: selection)
                    reload()
                }
            }
        }
        .onAppear(perform: reload)
    }

    private func reload() {
        prompts = (try? chatStore?.prompts(recentFor: runtime.loaded?.descriptor.filename)) ?? []
    }
}
