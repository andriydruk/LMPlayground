import Foundation

/// Copies of chat images, kept with the conversation (Android: ChatImageStore).
enum ChatImageStore {

    static var directory: URL {
        let url = AppDirectories.applicationSupport.appending(path: "ChatImages", directoryHint: .isDirectory)
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }

    static func url(for path: String) -> URL {
        directory.appending(path: path)
    }

    /// Delete stored images by their saved names.
    static func delete(_ paths: [String]) {
        for path in paths {
            try? FileManager.default.removeItem(at: url(for: path))
        }
    }
}
