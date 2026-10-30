package com.druk.lmplayground.tools

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Robolectric for a real org.json (the android.jar stub throws in plain JVM tests).
@RunWith(RobolectricTestRunner::class)
class HtmlPreviewTest {

    private fun args(html: String, title: String? = null) = JSONObject().apply {
        put("html", html)
        if (title != null) put("title", title)
    }.toString()

    // ── parse / normalize ────────────────────────────────────────────

    @Test
    fun `parse reads html and title`() {
        val request = HtmlPreview.parse(args("<p>Hi</p>", "Greeting"))
        assertNotNull(request)
        assertEquals("<p>Hi</p>", request!!.html)
        assertEquals("Greeting", request.title)
    }

    @Test
    fun `parse rejects blank html and bad json`() {
        assertNull(HtmlPreview.parse(args("   ")))
        assertNull(HtmlPreview.parse("not json"))
        assertNull(HtmlPreview.parse("{}"))
    }

    @Test
    fun `parse accepts code as an alias for html`() {
        val request = HtmlPreview.parse("""{"code":"<b>x</b>"}""")
        assertEquals("<b>x</b>", request?.html)
    }

    @Test
    fun `blank title becomes null`() {
        assertNull(HtmlPreview.parse(args("<p>x</p>", "  "))!!.title)
    }

    @Test
    fun `normalize strips a markdown fence`() {
        val fenced = "```html\n<!DOCTYPE html>\n<html><body>x</body></html>\n```"
        assertEquals("<!DOCTYPE html>\n<html><body>x</body></html>", HtmlPreview.normalize(fenced))
    }

    @Test
    fun `normalize undoes double escaped newlines`() {
        val escaped = """<html>\n<body>\n<p class=\"a\">x</p>\n</body>\n</html>"""
        val normalized = HtmlPreview.normalize(escaped)
        assertTrue(normalized.contains('\n'))
        assertTrue(normalized.contains("<p class=\"a\">"))
    }

    @Test
    fun `normalize leaves real multi-line pages alone`() {
        // A real newline means the escaping was fine; a "\n" inside JS strings must survive.
        val page = "<script>\nconsole.log('a\\nb');\n</script>"
        assertEquals(page, HtmlPreview.normalize(page))
    }

    // ── buildDocument ────────────────────────────────────────────────

    @Test
    fun `fragment gets a document shell with viewport`() {
        val doc = HtmlPreview.buildDocument("<button>Go</button>")
        assertTrue(doc.startsWith("<!DOCTYPE html>"))
        assertTrue(doc.contains("name=\"viewport\""))
        assertTrue(doc.contains("<body>\n<button>Go</button>\n</body>"))
    }

    @Test
    fun `viewport is injected into an existing head`() {
        val doc = HtmlPreview.buildDocument("<html><head><title>T</title></head><body></body></html>")
        assertTrue(doc.contains("<head><meta name=\"viewport\""))
        assertEquals(1, Regex("viewport").findAll(doc).count())
    }

    @Test
    fun `viewport is added when there is no head`() {
        val doc = HtmlPreview.buildDocument("<html lang=\"en\"><body>x</body></html>")
        assertTrue(doc.contains("<html lang=\"en\"><head><meta name=\"viewport\""))
    }

    @Test
    fun `existing viewport is kept untouched`() {
        val page = "<html><head><meta name=\"viewport\" content=\"width=500\"></head><body></body></html>"
        assertEquals(page, HtmlPreview.buildDocument(page))
    }

    // ── lint ─────────────────────────────────────────────────────────

    @Test
    fun `clean page has no warnings`() {
        val page = """<!DOCTYPE html><html><head><style>p{}</style>
            <script src="https://cdn.jsdelivr.net/npm/chart.js"></script></head>
            <body><img src="data:image/png;base64,AAA"><script>1</script></body></html>"""
        assertEquals(emptyList<String>(), HtmlPreview.lint(page))
    }

    @Test
    fun `truncated page is flagged`() {
        val warnings = HtmlPreview.lint("<html><body><script>let a = 1;")
        assertTrue(warnings.any { it.contains("truncated") })
        assertTrue(warnings.any { it.contains("<script>") })
    }

    @Test
    fun `local file references are flagged`() {
        val warnings = HtmlPreview.lint(
            """<html><head><link rel="stylesheet" href="style.css"><script src="app.js"></script></head><body></body></html>"""
        )
        val local = warnings.single { it.startsWith("Local file") }
        assertTrue(local.contains("style.css"))
        assertTrue(local.contains("app.js"))
    }

    @Test
    fun `insecure http resources are flagged`() {
        val warnings = HtmlPreview.lint("""<html><body><img src="http://example.com/a.png"></body></html>""")
        assertTrue(warnings.any { it.contains("http://") })
    }

    // ── tool ─────────────────────────────────────────────────────────

    @Test
    fun `tool rejects missing html`() {
        val out = JSONObject(HtmlPreviewTool().execute("{}"))
        assertTrue(out.has("error"))
    }

    @Test
    fun `tool rejects oversized pages`() {
        val huge = "<p>" + "x".repeat(HtmlPreview.MAX_HTML_CHARS) + "</p>"
        val out = JSONObject(HtmlPreviewTool().execute(args(huge)))
        assertTrue(out.getString("error").contains("limit"))
    }

    @Test
    fun `tool without renderer reports displayed and no problems`() {
        val out = JSONObject(HtmlPreviewTool().execute(args("<html><body><p>Hi</p></body></html>")))
        assertEquals("displayed", out.getString("status"))
        assertFalse(out.has("warnings"))
        assertFalse(out.has("console_errors"))
    }

    @Test
    fun `tool passes the built document to the renderer and reports what rendered`() {
        var rendered: String? = null
        val tool = HtmlPreviewTool { doc ->
            rendered = doc
            HtmlRenderReport(loaded = true, title = "Calc", visibleText = "Tip: 15%", elementCount = 12, contentHeight = 400)
        }
        val out = JSONObject(tool.execute(args("<p>Tip</p>")))
        assertTrue(rendered!!.contains("name=\"viewport\""))
        assertEquals("Calc", out.getString("page_title"))
        assertEquals("Tip: 15%", out.getString("visible_text"))
        assertFalse(out.has("warnings"))
        assertTrue(out.getString("message").contains("live"))
    }

    @Test
    fun `tool surfaces console errors and asks for a fix`() {
        val tool = HtmlPreviewTool {
            HtmlRenderReport(
                loaded = true, visibleText = "x", contentHeight = 100,
                errors = listOf("Uncaught ReferenceError: foo is not defined (line 7)"),
            )
        }
        val out = JSONObject(tool.execute(args("<p>x</p>")))
        assertEquals(
            "Uncaught ReferenceError: foo is not defined (line 7)",
            out.getJSONArray("console_errors").getString(0),
        )
        assertTrue(out.getString("message").contains("Fix them"))
    }

    @Test
    fun `tool warns about blank pages but not graphics-only ones`() {
        val blank = HtmlPreviewTool { HtmlRenderReport(loaded = true, elementCount = 3) }
        assertTrue(JSONObject(blank.execute(args("<div></div>"))).getJSONArray("warnings").getString(0).contains("blank"))

        val canvas = HtmlPreviewTool { HtmlRenderReport(loaded = true, graphicsCount = 1, contentHeight = 300) }
        assertFalse(JSONObject(canvas.execute(args("<canvas></canvas>"))).has("warnings"))
    }

    @Test
    fun `tool warns about horizontal overflow and load timeouts`() {
        val wide = HtmlPreviewTool {
            HtmlRenderReport(loaded = true, visibleText = "x", contentHeight = 50, overflowsHorizontally = true)
        }
        assertTrue(JSONObject(wide.execute(args("<p>x</p>"))).getJSONArray("warnings").getString(0).contains("wider"))

        val slow = HtmlPreviewTool { HtmlRenderReport(loaded = false) }
        assertTrue(JSONObject(slow.execute(args("<p>x</p>"))).getJSONArray("warnings").getString(0).contains("finish loading"))
    }

    @Test
    fun `tool falls back to static checks when the renderer throws`() {
        val tool = HtmlPreviewTool { throw IllegalStateException("no webview") }
        val out = JSONObject(tool.execute(args("<p>x</p>")))
        assertEquals("displayed", out.getString("status"))
    }

    @Test
    fun `schema is valid json and requires html`() {
        val schema = JSONObject(HtmlPreviewTool().parametersSchema)
        assertEquals("html", schema.getJSONArray("required").getString(0))
    }
}
