import Foundation
import ImageIO
import UniformTypeIdentifiers

/// Copies of chat images, kept with the conversation, and their preparation
/// for the vision encoder (Android: ChatImageStore).
enum ChatImageStore {

    /// Longest edge handed to the vision encoder, as on Android.
    static let visionMaxDimension = 768

    static var directory: URL {
        let url = AppDirectories.applicationSupport.appending(path: "ChatImages", directoryHint: .isDirectory)
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }

    static func url(for path: String) -> URL {
        directory.appending(path: path)
    }

    /// Store a picked or captured image (any format ImageIO reads, HEIC
    /// included) as a JPEG. Returns the saved name for the message row.
    static func persist(_ data: Data) -> String? {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil) else { return nil }
        // Bake the orientation in and cap the stored size (a 48 MP photo
        // doesn't need to be kept at full resolution for chat).
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: 2048,
        ]
        guard let image = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary) else { return nil }
        let name = "img_\(Date().millisecondsSince1970).jpg"
        return writeJPEG(image, to: url(for: name), quality: 0.9) ? name : nil
    }

    /// The image downscaled for the vision encoder: longest edge 768 px,
    /// JPEG quality 85.
    static func visionData(for path: String) -> Data? {
        guard let source = CGImageSourceCreateWithURL(url(for: path) as CFURL, nil) else { return nil }
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: visionMaxDimension,
        ]
        guard let image = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary) else { return nil }
        let data = NSMutableData()
        guard let destination = CGImageDestinationCreateWithData(data, UTType.jpeg.identifier as CFString, 1, nil) else { return nil }
        CGImageDestinationAddImage(destination, image, [kCGImageDestinationLossyCompressionQuality: 0.85] as CFDictionary)
        return CGImageDestinationFinalize(destination) ? data as Data : nil
    }

    private static func writeJPEG(_ image: CGImage, to url: URL, quality: Double) -> Bool {
        guard let destination = CGImageDestinationCreateWithURL(url as CFURL, UTType.jpeg.identifier as CFString, 1, nil) else { return false }
        CGImageDestinationAddImage(destination, image, [kCGImageDestinationLossyCompressionQuality: quality] as CFDictionary)
        return CGImageDestinationFinalize(destination)
    }

    /// A copy of a stored image, so a branched chat owns its files. Returns
    /// the copy's saved name.
    static func copy(_ path: String) -> String? {
        let name = "img_\(UUID().uuidString).jpg"
        do {
            try FileManager.default.copyItem(at: url(for: path), to: url(for: name))
            return name
        } catch {
            return nil
        }
    }

    /// Delete stored images by their saved names.
    static func delete(_ paths: [String]) {
        for path in paths {
            try? FileManager.default.removeItem(at: url(for: path))
        }
    }
}
