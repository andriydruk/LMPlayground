import Compression
import Foundation

/// Just enough ZIP reading for DOCX and EPUB (Android uses
/// java.util.zip): the central directory, stored and deflated entries. No
/// ZIP64, encryption or multi-disk archives; documents are capped at 30 MB.
struct ZipArchive {

    struct Entry {
        let name: String
        let method: UInt16
        let compressedSize: Int
        let uncompressedSize: Int
        let localHeaderOffset: Int

        var isDirectory: Bool { name.hasSuffix("/") }
    }

    enum ZipError: Error {
        case notAZip
        case unsupportedEntry(String)
        case corrupt(String)
    }

    /// Entries larger than this when inflated are refused (zip bombs).
    static let maxEntryBytes = 64 * 1024 * 1024

    let data: Data
    /// In central-directory order (which is archive order in practice).
    let entries: [Entry]

    init(data: Data) throws {
        self.data = data
        entries = try Self.readCentralDirectory(data)
    }

    init(url: URL) throws {
        try self.init(data: Data(contentsOf: url, options: .mappedIfSafe))
    }

    func entry(named name: String) -> Entry? {
        entries.first { $0.name == name }
    }

    /// The entry's uncompressed bytes.
    func contents(of entry: Entry) throws -> Data {
        guard entry.uncompressedSize <= Self.maxEntryBytes else {
            throw ZipError.unsupportedEntry("\(entry.name) is too large")
        }
        let header = entry.localHeaderOffset
        guard header + 30 <= data.count, u32(data, header) == 0x0403_4b50 else {
            throw ZipError.corrupt("bad local header for \(entry.name)")
        }
        let start = header + 30 + Int(u16(data, header + 26)) + Int(u16(data, header + 28))
        let end = start + entry.compressedSize
        guard end <= data.count else { throw ZipError.corrupt("\(entry.name) runs past the end") }
        let compressed = data[data.startIndex + start ..< data.startIndex + end]

        switch entry.method {
        case 0:
            return Data(compressed)
        case 8:
            return try inflate(compressed, expected: entry.uncompressedSize, name: entry.name)
        default:
            throw ZipError.unsupportedEntry("\(entry.name) uses method \(entry.method)")
        }
    }

    /// Raw DEFLATE (Compression's ZLIB algorithm has no zlib header, which
    /// is exactly ZIP's format).
    private func inflate(_ input: Data, expected: Int, name: String) throws -> Data {
        if expected == 0 { return Data() }
        var output = Data(count: expected)
        let written = output.withUnsafeMutableBytes { out in
            input.withUnsafeBytes { inp in
                compression_decode_buffer(out.bindMemory(to: UInt8.self).baseAddress!, expected,
                                          inp.bindMemory(to: UInt8.self).baseAddress!, input.count,
                                          nil, COMPRESSION_ZLIB)
            }
        }
        guard written == expected else { throw ZipError.corrupt("\(name) inflated to \(written) of \(expected) bytes") }
        return output
    }

    private static func readCentralDirectory(_ data: Data) throws -> [Entry] {
        // End of central directory: 22 bytes plus an optional comment of up
        // to 64 KB, at the very end.
        guard data.count >= 22 else { throw ZipError.notAZip }
        let lowest = max(0, data.count - 22 - 65_535)
        var eocd = -1
        var i = data.count - 22
        while i >= lowest {
            if u32(data, i) == 0x0605_4b50 { eocd = i; break }
            i -= 1
        }
        guard eocd >= 0 else { throw ZipError.notAZip }

        let count = Int(u16(data, eocd + 10))
        var offset = Int(u32(data, eocd + 16))
        var entries: [Entry] = []
        entries.reserveCapacity(count)
        for _ in 0..<count {
            guard offset + 46 <= data.count, u32(data, offset) == 0x0201_4b50 else {
                throw ZipError.corrupt("bad central directory")
            }
            let nameLength = Int(u16(data, offset + 28))
            let extraLength = Int(u16(data, offset + 30))
            let commentLength = Int(u16(data, offset + 32))
            guard offset + 46 + nameLength <= data.count else { throw ZipError.corrupt("bad entry name") }
            let nameStart = data.startIndex + offset + 46
            let name = String(decoding: data[nameStart ..< nameStart + nameLength], as: UTF8.self)
            entries.append(Entry(name: name,
                                 method: u16(data, offset + 10),
                                 compressedSize: Int(u32(data, offset + 20)),
                                 uncompressedSize: Int(u32(data, offset + 24)),
                                 localHeaderOffset: Int(u32(data, offset + 42))))
            offset += 46 + nameLength + extraLength + commentLength
        }
        return entries
    }
}

private func u16(_ data: Data, _ offset: Int) -> UInt16 {
    data.withUnsafeBytes { UInt16(littleEndian: $0.loadUnaligned(fromByteOffset: offset, as: UInt16.self)) }
}

private func u32(_ data: Data, _ offset: Int) -> UInt32 {
    data.withUnsafeBytes { UInt32(littleEndian: $0.loadUnaligned(fromByteOffset: offset, as: UInt32.self)) }
}
