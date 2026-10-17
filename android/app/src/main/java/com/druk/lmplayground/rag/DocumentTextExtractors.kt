package com.druk.lmplayground.rag

import android.content.Context
import android.net.Uri
import android.util.Log
import com.druk.lmplayground.rag.DocumentTextExtractor.Companion.MAX_EXTRACTED_CHARS
import com.druk.lmplayground.rag.DocumentTextExtractor.Companion.MAX_PDF_PAGES
import com.druk.lmplayground.tools.HtmlToMarkdown
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.jsoup.Jsoup
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.util.Locale
import java.util.zip.ZipInputStream

private const val TAG = "DocumentTextExtractors"

/** Routes a picked document to the extractor for its format. */
object DocumentTextExtractors {

    private val extractors: List<DocumentTextExtractor> = listOf(
        PdfTextExtractor(),
        DocxTextExtractor(),
        EpubTextExtractor(),
        HtmlTextExtractor(),
        PlainTextExtractor(),
    )

    /** MIME types offered to the system document picker. */
    val OPENABLE_MIME_TYPES = arrayOf(
        "application/pdf",
        "text/plain",
        "text/markdown",
        "text/html",
        "application/epub+zip",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    )

    fun forDocument(mimeType: String?, displayName: String): DocumentTextExtractor? =
        extractors.firstOrNull { it.supports(mimeType, displayName) }
}

private fun hasExtension(displayName: String, vararg extensions: String): Boolean {
    val lower = displayName.lowercase(Locale.ROOT)
    return extensions.any { lower.endsWith(it) }
}

/**
 * Image-only PDFs (scans, or design exports with text rasterized into the
 * artwork) usually still shed a few stray characters, so a blank check
 * isn't enough. Below ~25 chars per page the "text" is noise: indexing it
 * would produce a READY document the model knows nothing about.
 */
internal fun isPdfTextStarved(extractedChars: Int, pages: Int): Boolean =
    extractedChars < pages * 25

private fun openStream(context: Context, uri: Uri): InputStream =
    context.contentResolver.openInputStream(uri)
        ?: throw DocumentExtractionException(
            DocumentExtractionException.Reason.PARSE_FAILED,
            "Cannot open document stream",
        )

/** Read [reader]'s text up to [MAX_EXTRACTED_CHARS], truncating past it. */
private fun readCapped(stream: InputStream): String {
    val builder = StringBuilder()
    val buffer = CharArray(16 * 1024)
    stream.bufferedReader(Charsets.UTF_8).use { reader ->
        while (true) {
            val read = reader.read(buffer)
            if (read < 0) break
            val remaining = MAX_EXTRACTED_CHARS - builder.length
            if (read >= remaining) {
                builder.appendRange(buffer, 0, remaining)
                Log.w(TAG, "Extraction truncated at $MAX_EXTRACTED_CHARS chars")
                break
            }
            builder.appendRange(buffer, 0, read)
        }
    }
    return builder.toString()
}

private class PlainTextExtractor : DocumentTextExtractor {

    override fun supports(mimeType: String?, displayName: String): Boolean =
        mimeType == "text/plain" || mimeType == "text/markdown" ||
            hasExtension(displayName, ".txt", ".md", ".markdown")

    override fun extract(context: Context, uri: Uri): String =
        readCapped(openStream(context, uri))
}

private class HtmlTextExtractor : DocumentTextExtractor {

    override fun supports(mimeType: String?, displayName: String): Boolean =
        mimeType == "text/html" || hasExtension(displayName, ".html", ".htm")

    override fun extract(context: Context, uri: Uri): String {
        val html = readCapped(openStream(context, uri))
        return try {
            HtmlToMarkdown.convert(Jsoup.parse(html))
        } catch (e: Exception) {
            throw DocumentExtractionException(
                DocumentExtractionException.Reason.PARSE_FAILED,
                "HTML parse failed",
                e,
            )
        }
    }
}

private class PdfTextExtractor : DocumentTextExtractor {

    override fun supports(mimeType: String?, displayName: String): Boolean =
        mimeType == "application/pdf" || hasExtension(displayName, ".pdf")

    override fun extract(context: Context, uri: Uri): String {
        // Idempotent; PDFBox needs Android asset access for fonts/encodings.
        PDFBoxResourceLoader.init(context.applicationContext)
        try {
            openStream(context, uri).use { stream ->
                PDDocument.load(stream).use { document ->
                    if (document.isEncrypted) {
                        throw DocumentExtractionException(
                            DocumentExtractionException.Reason.ENCRYPTED,
                            "PDF is password-protected",
                        )
                    }
                    val stripper = PDFTextStripper()
                    stripper.startPage = 1
                    stripper.endPage = minOf(document.numberOfPages, MAX_PDF_PAGES)
                    val text = stripper.getText(document)
                    if (text.isBlank() || isPdfTextStarved(text.length, stripper.endPage)) {
                        // Image-only PDF (scanned, or designed with the text
                        // rasterized/outlined) — no OCR on device. Failing
                        // honestly beats indexing a document the model would
                        // know nothing about.
                        throw DocumentExtractionException(
                            DocumentExtractionException.Reason.NO_TEXT,
                            "PDF has no usable text (${text.length} chars over ${stripper.endPage} pages)",
                        )
                    }
                    return text.take(MAX_EXTRACTED_CHARS)
                }
            }
        } catch (e: DocumentExtractionException) {
            throw e
        } catch (e: Exception) {
            throw DocumentExtractionException(
                DocumentExtractionException.Reason.PARSE_FAILED,
                "PDF parse failed",
                e,
            )
        }
    }
}

/**
 * DOCX without Apache POI: the file is a ZIP whose word/document.xml holds
 * the text — `w:t` elements carry runs, `w:p` ends are paragraph breaks,
 * `w:tab`/`w:br` map to tab/newline. Matches element local names only, so
 * both transitional and strict OOXML namespaces work.
 */
private class DocxTextExtractor : DocumentTextExtractor {

    override fun supports(mimeType: String?, displayName: String): Boolean =
        mimeType == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" ||
            hasExtension(displayName, ".docx")

    override fun extract(context: Context, uri: Uri): String {
        try {
            ZipInputStream(openStream(context, uri).buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.name == "word/document.xml") {
                        return parseDocumentXml(zip)
                    }
                    zip.closeEntry()
                }
            }
        } catch (e: DocumentExtractionException) {
            throw e
        } catch (e: Exception) {
            throw DocumentExtractionException(
                DocumentExtractionException.Reason.PARSE_FAILED,
                "DOCX parse failed",
                e,
            )
        }
        throw DocumentExtractionException(
            DocumentExtractionException.Reason.PARSE_FAILED,
            "DOCX has no word/document.xml",
        )
    }

    private fun parseDocumentXml(stream: InputStream): String {
        val parser = android.util.Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(stream, null)
        val builder = StringBuilder()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT && builder.length < MAX_EXTRACTED_CHARS) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "tab" -> builder.append('\t')
                    "br" -> builder.append('\n')
                    "t" -> {
                        if (parser.next() == XmlPullParser.TEXT) {
                            builder.append(parser.text)
                        }
                    }
                }
                XmlPullParser.END_TAG -> if (parser.name == "p") builder.append('\n')
            }
            event = parser.next()
        }
        return builder.toString()
    }
}

/**
 * EPUB is a ZIP of XHTML chapters. Reads content documents in zip order
 * (spine-accurate ordering via the OPF is not worth the complexity here)
 * and converts each through the existing HTML→Markdown path.
 */
private class EpubTextExtractor : DocumentTextExtractor {

    override fun supports(mimeType: String?, displayName: String): Boolean =
        mimeType == "application/epub+zip" || hasExtension(displayName, ".epub")

    override fun extract(context: Context, uri: Uri): String {
        val builder = StringBuilder()
        try {
            ZipInputStream(openStream(context, uri).buffered()).use { zip ->
                while (builder.length < MAX_EXTRACTED_CHARS) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name.lowercase(Locale.ROOT)
                    val isContent = !entry.isDirectory &&
                        (name.endsWith(".xhtml") || name.endsWith(".html") || name.endsWith(".htm")) &&
                        !name.contains("nav") && !name.contains("toc") && !name.contains("cover")
                    if (isContent) {
                        // ZipInputStream closes per-entry on closeEntry(),
                        // so read the chapter fully before parsing.
                        val html = zip.readBytes().toString(Charsets.UTF_8)
                        val markdown = HtmlToMarkdown.convert(Jsoup.parse(html))
                        if (markdown.isNotBlank()) {
                            builder.append(markdown.take(MAX_EXTRACTED_CHARS - builder.length))
                            builder.append("\n\n")
                        }
                    }
                    zip.closeEntry()
                }
            }
        } catch (e: Exception) {
            throw DocumentExtractionException(
                DocumentExtractionException.Reason.PARSE_FAILED,
                "EPUB parse failed",
                e,
            )
        }
        return builder.toString()
    }
}
