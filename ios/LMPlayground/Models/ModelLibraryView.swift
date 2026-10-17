import SwiftUI
import UniformTypeIdentifiers

/// Downloaded and available models, storage and import: Android's Models
/// screen.
struct ModelLibraryView: View {
    @Environment(ModelStore.self) private var store
    @Environment(DownloadManager.self) private var downloads

    @State private var pendingDelete: ModelDescriptor?
    @State private var visionChoice: ModelDescriptor?
    @State private var importing = false
    @State private var importError: String?

    private var language: String { Locale.current.language.languageCode?.identifier ?? "en" }

    var body: some View {
        let available = store.available
        let splitByLanguage = language != "en"
        let supported = splitByLanguage ? available.filter { $0.catalog?.supportsLanguage(language) ?? true } : available
        let others = splitByLanguage ? available.filter { !($0.catalog?.supportsLanguage(language) ?? true) } : []

        List {
            Section {
                StorageSummary(info: store.storageInfo)
            }

            Section("Downloaded") {
                if store.downloaded.isEmpty && !auxiliaryVisible {
                    Text("No models yet. Download one below, or import a GGUF file.")
                        .foregroundStyle(.secondary)
                }
                ForEach(store.downloaded) { model in
                    ModelRow(model: model, mode: .downloaded)
                        .swipeActions {
                            Button("Delete", role: .destructive) { pendingDelete = model }
                        }
                        .contextMenu {
                            Button("Delete", systemImage: "trash", role: .destructive) { pendingDelete = model }
                        }
                }
                AuxiliaryModelRow(model: ModelCatalog.embeddingModel, purpose: "Document search")
                AuxiliaryModelRow(model: ModelCatalog.dictationModel, purpose: "Voice dictation")
            }

            Section(splitByLanguage ? "Available in your language" : "Available") {
                ForEach(supported) { model in
                    availableRow(model)
                }
            }

            if !others.isEmpty {
                Section("Other languages") {
                    ForEach(others) { model in
                        availableRow(model)
                    }
                }
            }

            Section {
                Toggle("Download over cellular", isOn: Binding(
                    get: { downloads.allowsCellular },
                    set: { downloads.allowsCellular = $0 }))
            } footer: {
                Text("Models are several gigabytes. Files you put in On My iPhone › LM Playground › Models also appear here.")
            }
        }
        .navigationTitle("Models")
        .toolbar {
            Button("Import", systemImage: "square.and.arrow.down") { importing = true }
        }
        .refreshable { store.refresh() }
        .onAppear { store.refresh() }
        .fileImporter(isPresented: $importing, allowedContentTypes: [.data]) { result in
            guard case .success(let url) = result else { return }
            Task {
                do { _ = try await store.importModel(from: url) }
                catch { importError = error.localizedDescription }
            }
        }
        .alert("Couldn't import the model", isPresented: Binding(
            get: { importError != nil }, set: { if !$0 { importError = nil } })) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(importError ?? "")
        }
        .confirmationDialog(pendingDelete.map { "Delete \($0.name)?" } ?? "",
                            isPresented: Binding(get: { pendingDelete != nil }, set: { if !$0 { pendingDelete = nil } }),
                            titleVisibility: .visible) {
            if let model = pendingDelete {
                Button("Delete (\(store.reclaimableBytes(model).formattedBytes))", role: .destructive) {
                    store.delete(model)
                }
            }
        }
        .confirmationDialog(visionChoice.map { "Download \($0.name)" } ?? "",
                            isPresented: Binding(get: { visionChoice != nil }, set: { if !$0 { visionChoice = nil } }),
                            titleVisibility: .visible) {
            if let model = visionChoice, let catalog = model.catalog {
                Button("With image understanding") { downloads.download(catalog, includeProjector: true) }
                Button("Text only") { downloads.download(catalog, includeProjector: false) }
            }
        } message: {
            Text("The image module lets the model look at photos. You can add it later.")
        }
    }

    private var auxiliaryVisible: Bool {
        [ModelCatalog.embeddingModel, ModelCatalog.dictationModel].contains {
            store.isOnDisk($0.filename) || downloads.downloads[$0.filename] != nil
        }
    }

    @ViewBuilder
    private func availableRow(_ model: ModelDescriptor) -> some View {
        ModelRow(model: model, mode: .available) {
            guard let catalog = model.catalog else { return }
            if model.needsVisionModule, let name = catalog.mmprojFilename,
               let url = catalog.mmprojURL.flatMap(URL.init(string:)) {
                downloads.start(filename: name, url: url)
            } else if model.isVision {
                visionChoice = model
            } else {
                downloads.download(catalog, includeProjector: false)
            }
        }
    }
}

/// One model: logo, name, summary, capability badges and download state.
struct ModelRow: View {
    enum Mode { case downloaded, available }

    @Environment(DownloadManager.self) private var downloads

    let model: ModelDescriptor
    let mode: Mode
    var onDownload: () -> Void = {}

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            ModelLogo(name: model.logo)
            VStack(alignment: .leading, spacing: 4) {
                HStack(spacing: 6) {
                    Text(model.name).font(.headline)
                    CapabilityBadges(model: model, showVision: mode == .downloaded ? model.isMmprojDownloaded : model.isVision)
                }
                Text(mode == .available && model.needsVisionModule
                     ? String(localized: "Image module for the downloaded model")
                     : model.summary)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                if let date = model.catalog?.releaseDate, mode == .available {
                    Text("Released \(date)").font(.caption).foregroundStyle(.tertiary)
                }
                transferStatus
            }
            Spacer(minLength: 0)
            if mode == .available, !isTransferring {
                Button("Download", systemImage: "arrow.down.circle", action: onDownload)
                    .labelStyle(.iconOnly)
                    .font(.title2)
                    .buttonStyle(.borderless)
            }
        }
        .padding(.vertical, 2)
    }

    private var transferFilenames: [String] {
        [model.filename, model.mmprojFilename].compactMap { $0 }
    }

    private var isTransferring: Bool {
        transferFilenames.contains { downloads.isDownloading($0) }
    }

    @ViewBuilder
    private var transferStatus: some View {
        ForEach(transferFilenames, id: \.self) { filename in
            if let progress = downloads.downloads[filename] {
                DownloadProgressView(filename: filename, progress: progress)
            }
        }
    }
}

struct DownloadProgressView: View {
    @Environment(DownloadManager.self) private var downloads
    let filename: String
    let progress: DownloadManager.Progress

    var body: some View {
        if let failure = progress.failure {
            HStack {
                Label(failure, systemImage: "exclamationmark.triangle").font(.caption).foregroundStyle(.red)
                Button("Dismiss") { downloads.dismissFailure(filename) }.font(.caption).buttonStyle(.borderless)
            }
        } else {
            VStack(alignment: .leading, spacing: 2) {
                HStack {
                    ProgressView(value: progress.fraction)
                    Button("Cancel", systemImage: "xmark.circle.fill") { downloads.cancel(filename) }
                        .labelStyle(.iconOnly)
                        .buttonStyle(.borderless)
                        .foregroundStyle(.secondary)
                }
                Text(detail).font(.caption.monospacedDigit()).foregroundStyle(.secondary)
            }
        }
    }

    private var detail: String {
        var parts = ["\(UInt64(progress.received).formattedBytes) of \(UInt64(max(progress.expected, 0)).formattedBytes)"]
        if progress.bytesPerSecond > 0 {
            parts.append("\(UInt64(progress.bytesPerSecond).formattedBytes)/s")
        }
        if let remaining = progress.remainingSeconds {
            parts.append(Duration.seconds(remaining).formatted(.units(allowed: [.hours, .minutes, .seconds], width: .narrow, maximumUnitCount: 2)))
        }
        if MmprojPairing.isMmproj(filename) {
            parts.insert(String(localized: "Image module"), at: 0)
        }
        return parts.joined(separator: " · ")
    }
}

/// The embedding and dictation models: shown only once on disk or arriving,
/// and never offered for chat. Their features download them on demand.
struct AuxiliaryModelRow: View {
    @Environment(ModelStore.self) private var store
    @Environment(DownloadManager.self) private var downloads
    let model: CatalogModel
    let purpose: LocalizedStringKey

    var body: some View {
        if store.isOnDisk(model.filename) || downloads.downloads[model.filename] != nil {
            HStack(alignment: .top, spacing: 12) {
                ModelLogo(name: model.logo)
                VStack(alignment: .leading, spacing: 4) {
                    Text(model.name).font(.headline)
                    Text(purpose).font(.subheadline).foregroundStyle(.secondary)
                    if let progress = downloads.downloads[model.filename] {
                        DownloadProgressView(filename: model.filename, progress: progress)
                    }
                }
            }
            .swipeActions {
                if store.isOnDisk(model.filename) {
                    Button("Delete", role: .destructive) {
                        try? FileManager.default.removeItem(at: store.url(for: model.filename))
                        store.refresh()
                    }
                }
            }
        }
    }
}

struct ModelLogo: View {
    let name: String
    var size: CGFloat = 40

    var body: some View {
        Image(name)
            .resizable()
            .scaledToFill()
            .frame(width: size, height: size)
            .clipShape(Circle())
            .overlay(Circle().stroke(.quaternary))
            .accessibilityHidden(true)
    }
}

struct CapabilityBadges: View {
    let model: ModelDescriptor
    let showVision: Bool

    var body: some View {
        HStack(spacing: 4) {
            if showVision { badge("eye", "Vision") }
            if model.supportsTools { badge("wrench.and.screwdriver", "Tools") }
            if model.offersThinkingToggle || model.thinkingMode == .always { badge("brain", "Thinking") }
        }
        .font(.caption)
        .foregroundStyle(.secondary)
    }

    private func badge(_ symbol: String, _ label: LocalizedStringKey) -> some View {
        Image(systemName: symbol).accessibilityLabel(label)
    }
}

struct StorageSummary: View {
    let info: ModelStore.StorageInfo

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            LabeledContent("Models", value: info.modelsBytes.formattedBytes)
            LabeledContent("Free space", value: info.freeBytes.formattedBytes)
        }
    }
}
