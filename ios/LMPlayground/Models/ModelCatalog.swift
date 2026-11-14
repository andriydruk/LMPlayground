import Foundation

/// How a model behaves when asked to think, as measured rather than as its
/// chat template advertises (Android's ThinkingMode).
enum ThinkingMode: Sendable {
    /// Never produces a thinking block, whatever the template claims.
    case none
    /// The toggle works: on produces a block, off suppresses it.
    case optional
    /// Always reasons; "off" cannot be honoured.
    case always
    /// Not measured: fall back to the template's own capability.
    case unknown
}

/// Whether a catalog model was verified on the reference device.
enum IOSStatus: Sendable {
    /// Shown in release builds.
    case verified
    /// Not yet measured on device: debug builds only.
    case candidate
    /// Measured and doesn't fit or misbehaves: never shown.
    case excluded
}

struct MeasuredPerformance: Sendable, Hashable {
    let footprintMB: Int
    let tokensPerSecond: Double
    let device: String
}

/// A model the app knows how to download. Generated from Android's catalog
/// (see ModelCatalog+Generated.swift and ios/scripts/generate-catalog.py).
struct CatalogModel: Sendable, Hashable, Identifiable {
    enum Kind: Sendable { case chat, embedding, dictation }

    let kind: Kind
    let name: String
    let filename: String
    let remoteURL: String?
    let releaseDate: String?
    let summary: String
    /// Asset catalog image name.
    let logo: String
    /// ISO 639-1 codes; empty means unknown (shown for every language).
    let supportedLanguages: [String]
    let supportsTools: Bool
    let supportsThinking: Bool
    let thinkingMode: ThinkingMode
    /// Kept only so an existing file is still recognized; never offered.
    let deprecated: Bool
    let mmprojFilename: String?
    let mmprojURL: String?
    let iosStatus: IOSStatus
    /// Largest context that fits on the reference device, if limited.
    let maxContext: Int?
    let measured: MeasuredPerformance?

    var id: String { filename }
    var isVision: Bool { mmprojFilename != nil }
    /// The download size that ends the summary ("… · 278Mb").
    var sizeLabel: String { summary.components(separatedBy: " · ").last ?? "" }

    func supportsLanguage(_ code: String) -> Bool {
        supportedLanguages.isEmpty || supportedLanguages.contains(code)
    }
}

enum ModelCatalog {

    /// Chat models offered in this build: verified ones, plus candidates in
    /// debug builds so they can be measured.
    static var offeredChatModels: [CatalogModel] {
        chatModels.filter { model in
            switch model.iosStatus {
            case .verified: return true
            case .candidate:
                #if DEBUG
                return true
                #else
                return false
                #endif
            case .excluded: return false
            }
        }
    }

    static func model(forFilename filename: String) -> CatalogModel? {
        if filename == embeddingModel.filename { return embeddingModel }
        if filename == dictationModel.filename { return dictationModel }
        return chatModels.first { $0.filename == filename }
    }

    /// Every file the catalog accounts for (models, projectors, the
    /// embedding and dictation models), so a storage scan never reports them
    /// as custom models.
    static var knownFilenames: Set<String> {
        var names = Set(chatModels.flatMap { [$0.filename, $0.mmprojFilename].compactMap { $0 } })
        names.insert(embeddingModel.filename)
        names.insert(dictationModel.filename)
        // Retired files waiting for SupersededFileCleanup aren't custom models.
        names.formUnion(SupersededFileCleanup.superseded.keys)
        return names
    }
}
