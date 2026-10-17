package com.druk.lmplayground.rag

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
class DocumentTextExtractorsTest {

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
    }

    private fun writeFile(name: String, bytes: ByteArray): Uri {
        val file = File(app.cacheDir, name)
        file.writeBytes(bytes)
        return Uri.fromFile(file)
    }

    private fun zip(name: String, entries: Map<String, String>): Uri {
        val file = File(app.cacheDir, name)
        ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEach { (entryName, content) ->
                zip.putNextEntry(ZipEntry(entryName))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return Uri.fromFile(file)
    }

    // ── Routing ──────────────────────────────────────────────────────────

    @Test
    fun `routing picks an extractor by mime or extension`() {
        assertNotNull(DocumentTextExtractors.forDocument("application/pdf", "x"))
        assertNotNull(DocumentTextExtractors.forDocument(null, "report.PDF"))
        assertNotNull(DocumentTextExtractors.forDocument("text/plain", "x"))
        assertNotNull(DocumentTextExtractors.forDocument(null, "notes.md"))
        assertNotNull(DocumentTextExtractors.forDocument("text/html", "x"))
        assertNotNull(
            DocumentTextExtractors.forDocument(
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "x"
            )
        )
        assertNotNull(DocumentTextExtractors.forDocument(null, "book.epub"))
        assertNull(DocumentTextExtractors.forDocument("application/octet-stream", "data.bin"))
    }

    // ── Plain text / HTML ────────────────────────────────────────────────

    @Test
    fun `plain text extracts as-is`() {
        val uri = writeFile("a.txt", "Hello\n\nWorld".toByteArray())
        val text = DocumentTextExtractors.forDocument("text/plain", "a.txt")!!.extract(app, uri)
        assertEquals("Hello\n\nWorld", text)
    }

    @Test
    fun `html extracts readable text without markup`() {
        val html = "<html><body><h1>Title</h1><p>Body <b>text</b> here.</p></body></html>"
        val uri = writeFile("page.html", html.toByteArray())
        val text = DocumentTextExtractors.forDocument("text/html", "page.html")!!.extract(app, uri)
        assertTrue(text.contains("Title"))
        assertTrue(text.contains("Body"))
        assertFalse(text.contains("<p>"))
    }

    // ── DOCX ─────────────────────────────────────────────────────────────

    private fun docxXml(body: String) = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
        <w:body>$body</w:body>
        </w:document>
    """.trimIndent()

    @Test
    fun `docx extracts runs paragraphs and tabs`() {
        val uri = zip(
            "doc.docx",
            mapOf(
                "[Content_Types].xml" to "<Types/>",
                "word/document.xml" to docxXml(
                    "<w:p><w:r><w:t>Hello</w:t></w:r><w:r><w:t xml:space=\"preserve\"> world</w:t></w:r></w:p>" +
                        "<w:p><w:r><w:tab/><w:t>Indented second</w:t></w:r></w:p>"
                ),
            ),
        )
        val text = DocumentTextExtractors.forDocument(null, "doc.docx")!!.extract(app, uri)
        assertTrue(text.contains("Hello world"))
        assertTrue(text.contains("\tIndented second"))
        // Paragraph boundary between the two.
        assertTrue(text.indexOf("Hello world") < text.indexOf("Indented second"))
        assertTrue(text.contains("\n"))
    }

    @Test
    fun `docx without document xml fails as PARSE_FAILED`() {
        val uri = zip("broken.docx", mapOf("word/other.xml" to "<x/>"))
        try {
            DocumentTextExtractors.forDocument(null, "broken.docx")!!.extract(app, uri)
            throw AssertionError("expected DocumentExtractionException")
        } catch (e: DocumentExtractionException) {
            assertEquals(DocumentExtractionException.Reason.PARSE_FAILED, e.reason)
        }
    }

    // ── EPUB ─────────────────────────────────────────────────────────────

    @Test
    fun `epub extracts chapters and skips nav`() {
        val uri = zip(
            "book.epub",
            mapOf(
                "mimetype" to "application/epub+zip",
                "OEBPS/nav.xhtml" to "<html><body><p>SKIP_NAV</p></body></html>",
                "OEBPS/chapter1.xhtml" to
                    "<html><body><h1>Chapter One</h1><p>It was a dark night.</p></body></html>",
                "OEBPS/chapter2.xhtml" to
                    "<html><body><p>The morning came.</p></body></html>",
            ),
        )
        val text = DocumentTextExtractors.forDocument("application/epub+zip", "book.epub")!!
            .extract(app, uri)
        assertTrue(text.contains("It was a dark night."))
        assertTrue(text.contains("The morning came."))
        assertFalse(text.contains("SKIP_NAV"))
    }

    // ── PDF ──────────────────────────────────────────────────────────────

    @Test
    fun `text-starved heuristic flags image-only pdfs and passes real ones`() {
        // The real-world case: 13-page designed PDF shedding 90 stray chars.
        assertTrue(isPdfTextStarved(extractedChars = 90, pages = 13))
        // A legit short one-pager passes.
        assertFalse(isPdfTextStarved(extractedChars = 90, pages = 1))
        // A normal text document is far above the floor.
        assertFalse(isPdfTextStarved(extractedChars = 24_000, pages = 12))
        // A scan with literally nothing.
        assertTrue(isPdfTextStarved(extractedChars = 0, pages = 1))
    }

    @Test
    fun `garbage pdf fails as PARSE_FAILED`() {
        val uri = writeFile("bad.pdf", "not a pdf at all".toByteArray())
        try {
            DocumentTextExtractors.forDocument("application/pdf", "bad.pdf")!!.extract(app, uri)
            throw AssertionError("expected DocumentExtractionException")
        } catch (e: DocumentExtractionException) {
            assertEquals(DocumentExtractionException.Reason.PARSE_FAILED, e.reason)
        }
    }
}
