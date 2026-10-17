import Foundation
import LlamaKit
import Observation

/// The models on disk (Documents/Models) and what the catalog offers.
@Observable
@MainActor
final class ModelStore {

    struct CustomModel: Hashable, Sendable {
        let filename: String
        let name: String
        let size: UInt64
    }

    struct StorageInfo: Sendable {
        let modelsBytes: UInt64
        let freeBytes: UInt64
    }

    enum ImportError: LocalizedError {
        case notGGUF
        case isProjector
        case isSpeechModel
        case noChatTemplate
        case alreadyExists
        case notEnoughSpace(needed: UInt64, free: UInt64)

        var errorDescription: String? {
            switch self {
            case .notGGUF: return String(localized: "This file isn't a GGUF model.")
            case .isProjector: return String(localized: "This is a vision module (mmproj). Import the model it belongs to; the module is picked up automatically when it sits next to it.")
            case .isSpeechModel: return String(localized: "This is a speech-recognition model, not a chat model.")
            case .noChatTemplate: return String(localized: "This model has no chat template, so it can't be used for chat.")
            case .alreadyExists: return String(localized: "A model with this file name is already in the library.")
            case .notEnoughSpace(let needed, let free):
                return String(localized: "Not enough free space: \(needed.formattedBytes) needed, \(free.formattedBytes) available.")
            }
        }
    }

    /// filename → size of every GGUF in the models folder.
    private(set) var files: [String: UInt64] = [:]
    private(set) var customModels: [CustomModel] = []

    let directory: URL

    init(directory: URL = AppDirectories.models) {
        self.directory = directory
        SupersededFileCleanup.run(in: directory)
        refresh()
    }

    func refresh() {
        let urls = (try? FileManager.default.contentsOfDirectory(
            at: directory, includingPropertiesForKeys: [.fileSizeKey], options: [.skipsHiddenFiles])) ?? []
        var found: [String: UInt64] = [:]
        for url in urls where url.pathExtension.lowercased() == "gguf" {
            found[url.lastPathComponent] = UInt64((try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0)
        }
        files = found

        let known = ModelCatalog.knownFilenames
        customModels = found.keys
            .filter { !known.contains($0) && !MmprojPairing.isMmproj($0) }
            .sorted()
            .compactMap { filename in
                let url = directory.appending(path: filename)
                guard let meta = Engine.probe(url), !meta.isProjector, meta.hasChatTemplate else { return nil }
                let name = meta.name.isEmpty ? String(filename.dropLast(5)) : meta.name
                return CustomModel(filename: filename, name: name, size: found[filename] ?? 0)
            }
    }

    func isOnDisk(_ filename: String) -> Bool {
        files[filename] != nil
    }

    func url(for filename: String) -> URL {
        directory.appending(path: filename)
    }

    // MARK: - Descriptors

    func descriptor(for model: CatalogModel) -> ModelDescriptor {
        let projector = resolveProjector(model.filename, declared: model.mmprojFilename)
        let detected = ModelPreferences.detected(model.filename)
        return ModelDescriptor(
            name: model.name,
            filename: model.filename,
            summary: model.summary,
            logo: model.logo,
            catalog: model,
            mmprojFilename: projector,
            isDownloaded: isOnDisk(model.filename),
            isMmprojDownloaded: projector.map(isOnDisk) ?? false,
            supportsTools: detected?.tools ?? model.supportsTools,
            supportsThinking: detected?.thinking ?? model.supportsThinking)
    }

    func descriptor(for custom: CustomModel) -> ModelDescriptor {
        let projector = resolveProjector(custom.filename, declared: nil)
        let detected = ModelPreferences.detected(custom.filename)
        return ModelDescriptor(
            name: custom.name,
            filename: custom.filename,
            summary: String(localized: "Custom model · \(custom.size.formattedBytes)"),
            logo: "penrose_triangle",
            catalog: nil,
            mmprojFilename: projector,
            isDownloaded: true,
            isMmprojDownloaded: projector.map(isOnDisk) ?? false,
            supportsTools: detected?.tools ?? false,
            supportsThinking: detected?.thinking ?? false)
    }

    /// A catalog model keeps its declared projector while that file is on
    /// disk; otherwise a projector on disk that pairs by name wins (this is
    /// what lights up vision for imported models). Falls back to the
    /// declared one so it can still be downloaded.
    private func resolveProjector(_ filename: String, declared: String?) -> String? {
        if let declared, isOnDisk(declared) { return declared }
        return MmprojPairing.findMmproj(for: filename, in: Array(files.keys)) ?? declared
    }

    /// Downloaded chat models: imported ones first, then the catalog's,
    /// newest first.
    var downloaded: [ModelDescriptor] {
        customModels.map(descriptor(for:)) +
            ModelCatalog.chatModels
                .filter { isOnDisk($0.filename) }
                .sorted { ($0.releaseDate ?? "") > ($1.releaseDate ?? "") }
                .map(descriptor(for:))
    }

    /// Catalog models offered for download: not yet downloaded, or missing
    /// their image module. Deprecated entries are never offered.
    var available: [ModelDescriptor] {
        ModelCatalog.offeredChatModels
            .filter { !$0.deprecated }
            .sorted { ($0.releaseDate ?? "") > ($1.releaseDate ?? "") }
            .map(descriptor(for:))
            .filter { !$0.isDownloaded || $0.needsVisionModule }
    }

    // MARK: - Storage

    var storageInfo: StorageInfo {
        let free = (try? directory.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey])
            .volumeAvailableCapacityForImportantUsage).map { UInt64(max(0, $0)) } ?? 0
        return StorageInfo(modelsBytes: files.values.reduce(0, +), freeBytes: free)
    }

    /// Delete a model and its projector, unless another downloaded model
    /// still pairs with that projector.
    func delete(_ descriptor: ModelDescriptor) {
        try? FileManager.default.removeItem(at: url(for: descriptor.filename))
        ModelPreferences.forget(descriptor.filename)
        if let projector = descriptor.mmprojFilename, isOnDisk(projector) {
            let others = files.keys.filter { $0 != descriptor.filename && $0 != projector && !MmprojPairing.isMmproj($0) }
            let stillUsed = others.contains { other in
                ModelCatalog.model(forFilename: other)?.mmprojFilename == projector
                    || MmprojPairing.findMmproj(for: other, in: Array(files.keys)) == projector
            }
            if !stillUsed {
                try? FileManager.default.removeItem(at: url(for: projector))
            }
        }
        refresh()
    }

    /// Bytes a delete would reclaim (model + projector it owns).
    func reclaimableBytes(_ descriptor: ModelDescriptor) -> UInt64 {
        (files[descriptor.filename] ?? 0) + (descriptor.mmprojFilename.flatMap { files[$0] } ?? 0)
    }

    // MARK: - Import

    /// Copy a GGUF picked in Files into the library after checking it is a
    /// chat model. Copying (rather than referencing the original) keeps the
    /// file available: an iCloud Drive file can be evicted under a loaded model.
    func importModel(from source: URL) async throws -> String {
        let accessed = source.startAccessingSecurityScopedResource()
        defer { if accessed { source.stopAccessingSecurityScopedResource() } }

        let filename = source.lastPathComponent
        guard filename.lowercased().hasSuffix(".gguf"), let meta = Engine.probe(source) else {
            throw ImportError.notGGUF
        }
        if meta.isProjector { throw ImportError.isProjector }
        if meta.architecture == "parakeet" { throw ImportError.isSpeechModel }
        if !meta.hasChatTemplate { throw ImportError.noChatTemplate }
        if isOnDisk(filename) { throw ImportError.alreadyExists }

        let size = UInt64((try? source.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0)
        let free = storageInfo.freeBytes
        if size > free { throw ImportError.notEnoughSpace(needed: size, free: free) }

        let destination = url(for: filename)
        let partial = destination.appendingPathExtension("importing")
        try await Task.detached(priority: .userInitiated) {
            try? FileManager.default.removeItem(at: partial)
            try FileManager.default.copyItem(at: source, to: partial)
            try FileManager.default.moveItem(at: partial, to: destination)
            try ModelFiles.excludeFromBackup(destination)
        }.value
        refresh()
        return filename
    }
}

enum ModelFiles {
    /// Multi-GB models must not go into iCloud backups.
    static func excludeFromBackup(_ url: URL) throws {
        var url = url
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try url.setResourceValues(values)
    }
}

extension UInt64 {
    var formattedBytes: String {
        ByteCountFormatter.string(fromByteCount: Int64(self), countStyle: .file)
    }
}
