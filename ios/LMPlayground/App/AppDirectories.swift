import Foundation

/// Where the app keeps its files.
enum AppDirectories {

    /// User-visible in Files and Finder (UIFileSharingEnabled).
    static var documents: URL {
        URL.documentsDirectory
    }

    /// Downloaded and imported GGUFs, excluded from backup file by file.
    static var models: URL {
        let url = documents.appending(path: "Models", directoryHint: .isDirectory)
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }

    static var applicationSupport: URL {
        let url = URL.applicationSupportDirectory
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }

    static var caches: URL {
        URL.cachesDirectory
    }
}
