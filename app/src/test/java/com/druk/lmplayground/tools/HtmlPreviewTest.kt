package com.druk.lmplayground.tools

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests for [HtmlPreview] and [HtmlPreviewTool]: argument handling, results, and document wrapping. */
class HtmlPreviewTest {

    private fun args(html: String, title: String? = null): String =
        JSONObject().put("html", html).apply { title?.let { put("title", it) } }.toString()

    // ── parse ────────────────────────────────────────────────────────

    @Test
    fun `parse reads html and trims title`() {
        val req = HtmlPreview.parse(args("<p>hi</p>", "  Demo  "))
        assertNotNull(req)
        assertEquals("<p>hi</p>", req!!.html)
        assertEquals("Demo", req.title)
    }

    @Test
    fun `parse rejects malformed json, missing and blank html`() {
        assertNull(HtmlPreview.parse("not json"))
        assertNull(HtmlPreview.parse("{}"))
        assertNull(HtmlPreview.parse("""{"html":"   "}"""))
        assertNull(HtmlPreview.parse("""{"html":null}"""))
    }

    @Test
    fun `missing or null title becomes empty`() {
        assertEquals("", HtmlPreview.parse(args("<p>x</p>"))!!.title)
        assertEquals("", HtmlPreview.parse("""{"html":"<p>x</p>","title":null}""")!!.title)
    }

    // ── toDocument ───────────────────────────────────────────────────

    @Test
    fun `fragments are wrapped with charset and viewport`() {
        val doc = HtmlPreview.toDocument("<h1>Hello</h1>")
        assertTrue(doc.startsWith("<!DOCTYPE html>"))
        assertTrue(doc.contains("name=\"viewport\""))
        assertTrue(doc.contains("<body><h1>Hello</h1></body>"))
    }

    @Test
    fun `full documents are left untouched`() {
        val full = "<!DOCTYPE html><html><head></head><body>x</body></html>"
        assertEquals(full, HtmlPreview.toDocument(full))
        val upper = "<HTML><BODY>x</BODY></HTML>"
        assertEquals(upper, HtmlPreview.toDocument(upper))
    }

    // ── lint ─────────────────────────────────────────────────────────

    @Test
    fun `lint flags unbalanced script and style tags`() {
        assertTrue(HtmlPreview.lint("<script>let a=1;").any { it.contains("<script>") })
        assertTrue(HtmlPreview.lint("<style>p{}").any { it.contains("<style>") })
    }

    @Test
    fun `lint flags relative script src but not cdn urls`() {
        assertTrue(HtmlPreview.lint("""<script src="./app.js"></script>""").isNotEmpty())
        assertTrue(HtmlPreview.lint("""<script src="/app.js"></script>""").isNotEmpty())
        assertTrue(HtmlPreview.lint("""<script src="https://cdn.example.com/x.js"></script>""").isEmpty())
        assertTrue(HtmlPreview.lint("""<script src="//cdn.example.com/x.js"></script>""").isEmpty())
    }

    @Test
    fun `lint is quiet for a clean page`() {
        assertTrue(HtmlPreview.lint("<style>p{}</style><p>x</p><script>1</script>").isEmpty())
    }

    // ── tool ─────────────────────────────────────────────────────────

    @Test
    fun `tool reports rendered with size and no warnings for a clean page`() {
        val html = "<!DOCTYPE html><html><body><p>ok</p></body></html>"
        val out = JSONObject(HtmlPreviewTool().execute(args(html)))
        assertEquals("rendered", out.getString("status"))
        assertEquals(html.length, out.getInt("chars"))
        assertFalse(out.has("warnings"))
        assertTrue(HtmlPreview.wasRendered(out.toString()))
    }

    @Test
    fun `tool surfaces lint warnings to the model`() {
        val out = JSONObject(HtmlPreviewTool().execute(args("<script>oops")))
        assertEquals("rendered", out.getString("status"))
        assertEquals(1, out.getJSONArray("warnings").length())
    }

    @Test
    fun `tool errors on bad arguments and oversized pages`() {
        val bad = HtmlPreviewTool().execute("{}")
        assertTrue(JSONObject(bad).has("error"))
        assertFalse(HtmlPreview.wasRendered(bad))

        val huge = HtmlPreviewTool().execute(args("a".repeat(HtmlPreview.MAX_HTML_CHARS + 1)))
        assertTrue(JSONObject(huge).getString("error").contains("limit"))
        assertFalse(HtmlPreview.wasRendered(huge))
    }

    @Test
    fun `tool exposes a valid openai schema`() {
        val schema = JSONObject(HtmlPreviewTool().parametersSchema)
        assertEquals("html", schema.getJSONArray("required").getString(0))
        assertTrue(schema.getJSONObject("properties").has("title"))
    }
}
