import Foundation
import PDFKit
import SwiftSoup
import UniformTypeIdentifiers

/// Why a document couldn't be indexed (Android: DocumentExtractionException).
/// Raw values match Android's reason codes.
enum DocumentExtractionError: String, Error {
    /// Password-protected document.
    case encrypted = "ENCRYPTED"
    /// No extractable text (a scanned or image-only PDF).
    case noText = "NO_TEXT"
    /// Bigger than DocumentText.maxFileBytes.
    case tooLarge = "TOO_LARGE"
    /// No extractor for this format.
    case unsupported = "UNSUPPORTED"
    /// Corrupt or unreadable.
    case parseFailed = "PARSE_FAILED"
    /// The embedding model failed or isn't available.
    case embeddingFailed = "EMBEDDING_FAILED"
    /// The document was removed while indexing; nothing to report.
    case cancelled = "CANCELLED"

    var message: String {
        switch self {
        case .encrypted: String(localized: "This document is password-protected and can't be read.")
        case .noText: String(localized: "No readable text found — scanned documents aren't supported yet.")
        case .tooLarge: String(localized: "This file is too large. Documents up to 30 MB are supported.")
        case .unsupported: String(localized: "This file format isn't supported. Try PDF, TXT, Markdown, HTML, EPUB or DOCX.")
        case .parseFailed: String(localized: "This document couldn't be read. It may be corrupt.")
        case .embeddingFailed: String(localized: "Indexing failed. Try attaching the document again.")
        case .cancelled: ""
        }
    }
}

/// Plain text out of an attached document, one format family per case
/// (Android: DocumentTextExtractors).
enum DocumentText {

    /// Files bigger than this are rejected before extraction.
    static let maxFileBytes: Int64 = 30 * 1024 * 1024
    /// Extraction stops after this many characters (~200K tokens); longer
    /// documents are truncated rather than rejected.
    static let maxExtractedChars = 800_000
    /// Bounds extraction time on huge PDFs.
    static let maxPdfPages = 300

    enum Format: CaseIterable {
        case pdf, docx, epub, html, plainText
    }

    /// Types offered in the document picker.
    static let openableTypes: [UTType] = [
        .pdf, .plainText, .html,
        UTType("net.daringfireball.markdown") ?? .plainText,
        UTType("org.idpf.epub-container") ?? .data,
        UTType("org.openxmlformats.wordprocessingml.document") ?? .data,
    ]

    static func format(mimeType: String?, displayName: String) -> Format? {
        let lower = displayName.lowercased()
        func ext(_ list: String...) -> Bool { list.contains { lower.hasSuffix($0) } }
        if mimeType == "application/pdf" || ext(".pdf") { return .pdf }
        if mimeType == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" || ext(".docx") { return .docx }
        if mimeType == "application/epub+zip" || ext(".epub") { return .epub }
        if mimeType == "text/html" || ext(".html", ".htm") { return .html }
        if mimeType == "text/plain" || mimeType == "text/markdown" || ext(".txt", ".md", ".markdown") { return .plainText }
        return nil
    }

    static func extract(_ url: URL, format: Format) throws -> String {
        switch format {
        case .pdf: try pdf(url)
        case .docx: try docx(url)
        case .epub: try epub(url)
        case .html: try html(readCapped(url))
        case .plainText: try readCapped(url)
        }
    }

    // MARK: Formats

    /// Image-only PDFs usually still shed a few stray characters; below ~25
    /// per page the "text" is noise and indexing it would produce a document
    /// the model knows nothing about.
    static func isPdfTextStarved(extractedChars: Int, pages: Int) -> Bool {
        extractedChars < pages * 25
    }

    private static func pdf(_ url: URL) throws -> String {
        guard let document = PDFDocument(url: url) else { throw DocumentExtractionError.parseFailed }
        if document.isLocked { throw DocumentExtractionError.encrypted }
        let pages = min(document.pageCount, maxPdfPages)
        var text = ""
        for index in 0..<pages {
            guard let page = document.page(at: index) else { continue }
            text += page.string ?? ""
            text += "\n\n"
            if text.utf16.count >= maxExtractedChars { break }
        }
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty || isPdfTextStarved(extractedChars: trimmed.count, pages: max(pages, 1)) {
            throw DocumentExtractionError.noText
        }
        return String(trimmed.prefix(maxExtractedChars))
    }

    /// DOCX without a library: word/document.xml holds the text in `w:t`
    /// runs, `w:p` ends paragraphs, `w:tab`/`w:br` are tab and newline.
    /// Local names only, so transitional and strict OOXML both work.
    private static func docx(_ url: URL) throws -> String {
        let xml: Data
        do {
            let zip = try ZipArchive(url: url)
            guard let entry = zip.entry(named: "word/document.xml") else { throw DocumentExtractionError.parseFailed }
            xml = try zip.contents(of: entry)
        } catch let error as DocumentExtractionError {
            throw error
        } catch {
            throw DocumentExtractionError.parseFailed
        }
        let collector = DocxTextCollector()
        let parser = XMLParser(data: xml)
        parser.shouldProcessNamespaces = true
        parser.delegate = collector
        guard parser.parse() || collector.text.count >= maxExtractedChars else {
            throw DocumentExtractionError.parseFailed
        }
        return String(collector.text.prefix(maxExtractedChars))
    }

    /// EPUB chapters in archive order (spine order isn't worth the
    /// complexity), each through the HTML → Markdown path.
    private static func epub(_ url: URL) throws -> String {
        var text = ""
        do {
            let zip = try ZipArchive(url: url)
            for entry in zip.entries where !entry.isDirectory {
                if text.count >= maxExtractedChars { break }
                let name = entry.name.lowercased()
                let isContent = (name.hasSuffix(".xhtml") || name.hasSuffix(".html") || name.hasSuffix(".htm"))
                    && !name.contains("nav") && !name.contains("toc") && !name.contains("cover")
                guard isContent else { continue }
                let chapter = String(decoding: try zip.contents(of: entry), as: UTF8.self)
                let markdown = try HtmlToMarkdown.convert(SwiftSoup.parse(chapter))
                if !markdown.isEmpty {
                    text += markdown.prefix(maxExtractedChars - text.count)
                    text += "\n\n"
                }
            }
        } catch {
            throw DocumentExtractionError.parseFailed
        }
        return text
    }

    private static func html(_ source: String) throws -> String {
        do {
            return try HtmlToMarkdown.convert(SwiftSoup.parse(source))
        } catch {
            throw DocumentExtractionError.parseFailed
        }
    }

    /// The file as text (UTF-8, falling back to what Foundation detects),
    /// truncated at maxExtractedChars.
    private static func readCapped(_ url: URL) throws -> String {
        guard let data = try? Data(contentsOf: url) else { throw DocumentExtractionError.parseFailed }
        var text = String(data: data, encoding: .utf8)
        if text == nil {
            var converted: NSString?
            _ = NSString.stringEncoding(for: data, encodingOptions: nil, convertedString: &converted, usedLossyConversion: nil)
            text = converted as String?
        }
        guard let text else { throw DocumentExtractionError.parseFailed }
        return String(text.prefix(maxExtractedChars))
    }
}

private final class DocxTextCollector: NSObject, XMLParserDelegate {
    var text = ""
    private var inText = false

    func parser(_ parser: XMLParser, didStartElement elementName: String, namespaceURI: String?,
                qualifiedName: String?, attributes: [String: String] = [:]) {
        switch elementName {
        case "tab": text += "\t"
        case "br": text += "\n"
        case "t": inText = true
        default: break
        }
    }

    func parser(_ parser: XMLParser, didEndElement elementName: String, namespaceURI: String?, qualifiedName: String?) {
        switch elementName {
        case "t": inText = false
        case "p":
            text += "\n"
            if text.utf16.count >= DocumentText.maxExtractedChars { parser.abortParsing() }
        default: break
        }
    }

    func parser(_ parser: XMLParser, foundCharacters string: String) {
        if inText { text += string }
    }
}
