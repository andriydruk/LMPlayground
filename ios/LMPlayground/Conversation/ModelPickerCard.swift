import SwiftUI

/// The model picker: a card centered over the dimmed chat with the
/// downloaded models, then Browse Models and, with a model loaded, its
/// generation parameters and unloading (Android: SelectModelDialog).
struct ModelPickerCard: View {
    @Environment(ConversationModel.self) private var model
    @Environment(ModelRuntime.self) private var runtime
    @Environment(ModelStore.self) private var store
    @Binding var isPresented: Bool
    var onTooLarge: (ModelDescriptor, String) -> Void
    var onBrowse: () -> Void
    var onParams: () -> Void

    @State private var listHeight: CGFloat = 0

    var body: some View {
        ZStack {
            Color.black.opacity(0.4)
                .ignoresSafeArea()
                .onTapGesture { dismiss() }
                .accessibilityLabel("Close")
                .accessibilityAddTraits(.isButton)

            VStack(spacing: 0) {
                ScrollView {
                    VStack(spacing: 0) {
                        if store.downloaded.isEmpty {
                            Text("No models downloaded yet")
                                .foregroundStyle(.secondary)
                                .padding()
                        }
                        ForEach(store.downloaded) { descriptor in
                            row(descriptor)
                        }
                    }
                    .padding(.vertical, 8)
                    .onGeometryChange(for: CGFloat.self, of: \.size.height) { listHeight = $0 }
                }
                .frame(height: min(listHeight, 440))
                .scrollBounceBehavior(.basedOnSize)

                Divider().padding(.horizontal, 12)
                VStack(spacing: 0) {
                    action("Browse Models", systemImage: "magnifyingglass") { onBrowse() }
                    if runtime.loaded != nil {
                        action("Generation Parameters", systemImage: "slider.horizontal.3") { onParams() }
                        action("Unload Model", systemImage: "eject") {
                            Task { await model.unloadModel() }
                        }
                    }
                }
                .padding(.vertical, 8)
            }
            .frame(maxWidth: 560)
            .glassEffect(.regular, in: .rect(cornerRadius: 28))
            .padding(16)
            .transition(.scale(scale: 0.92).combined(with: .opacity))
        }
        .accessibilityIdentifier("modelPicker")
    }

    private func row(_ descriptor: ModelDescriptor) -> some View {
        let current = runtime.loaded?.descriptor.filename == descriptor.filename
        return Button {
            select(descriptor)
        } label: {
            HStack(spacing: 12) {
                ModelLogo(name: descriptor.logo, size: 36)
                VStack(alignment: .leading, spacing: 2) {
                    Text(descriptor.name)
                        .font(.headline)
                        .lineLimit(1)
                    Text(descriptor.summary)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                    if let date = Self.releaseLabel(descriptor.catalog?.releaseDate) {
                        Text(date)
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                    }
                }
                Spacer(minLength: 8)
                CapabilityBadges(model: descriptor, showVision: descriptor.isMmprojDownloaded)
                Image(systemName: current ? "checkmark" : "sparkles")
                    .font(.body.weight(current ? .semibold : .regular))
                    .foregroundStyle(current ? AnyShapeStyle(.tint) : AnyShapeStyle(.primary))
                    .frame(width: 24)
            }
            .padding(.horizontal, 20)
            .padding(.vertical, 10)
            .contentShape(.rect)
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(current ? .isSelected : [])
    }

    private func action(_ title: LocalizedStringKey, systemImage: String, perform: @escaping () -> Void) -> some View {
        Button {
            dismiss()
            perform()
        } label: {
            Label(title, systemImage: systemImage)
                .font(.body.weight(.medium))
                .frame(maxWidth: .infinity)
                .padding(.vertical, 12)
                .contentShape(.rect)
        }
        .buttonStyle(.plain)
        .foregroundStyle(.tint)
    }

    private func select(_ descriptor: ModelDescriptor) {
        dismiss()
        if case .tooLarge(let needed, let available) = runtime.memoryVerdict(for: descriptor, contextSize: model.params.contextSize) {
            onTooLarge(descriptor, ModelRuntime.LoadError.tooLarge(neededMB: needed, availableMB: available).localizedDescription)
            return
        }
        Task { await model.select(model: descriptor) }
    }

    private func dismiss() {
        withAnimation(.snappy(duration: 0.25)) { isPresented = false }
    }

    /// "Mar 25, 2026" from the catalog's "2026-03-25".
    static func releaseLabel(_ date: String?) -> String? {
        guard let date, let parsed = try? Date(date, strategy: .iso8601.year().month().day().dateSeparator(.dash)) else { return nil }
        return parsed.formatted(.dateTime.month(.abbreviated).day().year())
    }
}
