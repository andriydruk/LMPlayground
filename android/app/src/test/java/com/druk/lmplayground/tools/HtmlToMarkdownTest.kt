package com.druk.lmplayground.tools

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for [HtmlToMarkdown]. Jsoup is JVM-clean so these run
 * fast — no instrumented test rig needed. Cases here cover the
 * structural signal we care about for LLM consumption (headings, lists,
 * links) plus a few traps real-world HTML throws at us.
 */
class HtmlToMarkdownTest {

    private fun md(html: String, baseUri: String = ""): String =
        HtmlToMarkdown.convert(Jsoup.parse(html, baseUri))

    // ── Headings ─────────────────────────────────────────────────────

    @Test
    fun `headings emit one to six hash markers`() {
        val out = md(
            """
            <html><body>
              <h1>One</h1><h2>Two</h2><h3>Three</h3>
              <h4>Four</h4><h5>Five</h5><h6>Six</h6>
            </body></html>
            """.trimIndent()
        )
        assertTrue("h1: $out", out.contains("# One"))
        assertTrue("h2: $out", out.contains("## Two"))
        assertTrue("h3: $out", out.contains("### Three"))
        assertTrue("h4: $out", out.contains("#### Four"))
        assertTrue("h5: $out", out.contains("##### Five"))
        assertTrue("h6: $out", out.contains("###### Six"))
    }

    @Test
    fun `headings are separated from body by blank lines`() {
        val out = md("<html><body><h1>Title</h1><p>Body.</p></body></html>")
        // Heading followed by one blank line then the paragraph.
        assertTrue("expected heading then blank line then body, got:\n$out",
            out.contains("# Title\n\nBody."))
    }

    // ── Paragraphs and breaks ────────────────────────────────────────

    @Test
    fun `paragraphs are separated by blank lines`() {
        val out = md("<html><body><p>Alpha.</p><p>Beta.</p></body></html>")
        assertEquals("Alpha.\n\nBeta.", out)
    }

    @Test
    fun `br emits a single newline inside a paragraph`() {
        val out = md("<html><body><p>One<br>Two</p></body></html>")
        assertEquals("One\nTwo", out)
    }

    @Test
    fun `hr renders as three dashes`() {
        val out = md("<html><body><p>Above</p><hr><p>Below</p></body></html>")
        assertTrue("expected --- between paragraphs, got:\n$out",
            out.contains("Above\n\n---\n\nBelow"))
    }

    // ── Lists ────────────────────────────────────────────────────────

    @Test
    fun `unordered list uses dash markers`() {
        val out = md("<html><body><ul><li>One</li><li>Two</li></ul></body></html>")
        assertTrue("expected dash-bulleted list, got:\n$out",
            out.contains("- One\n- Two"))
    }

    @Test
    fun `ordered list uses numeric markers starting at 1`() {
        val out = md("<html><body><ol><li>First</li><li>Second</li><li>Third</li></ol></body></html>")
        assertTrue("expected numeric list, got:\n$out",
            out.contains("1. First\n2. Second\n3. Third"))
    }

    @Test
    fun `list items collapse internal whitespace to keep one bullet per line`() {
        val out = md(
            """
            <html><body>
              <ul>
                <li>Line one
                    continues here</li>
                <li>Line two</li>
              </ul>
            </body></html>
            """.trimIndent()
        )
        assertTrue("expected single-line bullets, got:\n$out",
            out.contains("- Line one continues here\n- Line two"))
    }

    // ── Links ────────────────────────────────────────────────────────

    @Test
    fun `link with absolute href emits markdown link form`() {
        val out = md("""<html><body><a href="https://example.com/x">click</a></body></html>""")
        assertTrue("expected [click](url), got:\n$out", out.contains("[click](https://example.com/x)"))
    }

    @Test
    fun `relative href is resolved against baseUri`() {
        val out = md(
            """<html><body><a href="/about">About</a></body></html>""",
            baseUri = "https://lmplayground.app/"
        )
        assertTrue("relative href should resolve, got:\n$out",
            out.contains("[About](https://lmplayground.app/about)"))
    }

    @Test
    fun `anchor and javascript hrefs unwrap to bare text`() {
        val anchor = md("""<html><body><a href="#top">top</a></body></html>""")
        val js = md("""<html><body><a href="javascript:void(0)">x</a></body></html>""")
        assertEquals("top", anchor)
        assertEquals("x", js)
    }

    @Test
    fun `empty href unwraps to bare text`() {
        val out = md("""<html><body><a href="">label</a></body></html>""")
        assertEquals("label", out)
    }

    // ── Emphasis and code ────────────────────────────────────────────

    @Test
    fun `bold and italic wrap with markdown markers`() {
        val out = md("<html><body><p><strong>bold</strong> and <em>italic</em></p></body></html>")
        assertEquals("**bold** and *italic*", out)
    }

    @Test
    fun `inline code wraps with backticks`() {
        val out = md("<html><body><p>Use <code>kotlin run</code> to start.</p></body></html>")
        assertEquals("Use `kotlin run` to start.", out)
    }

    @Test
    fun `pre block fences with triple backticks and preserves newlines`() {
        val out = md(
            "<html><body><pre>fun main() {\n  println(42)\n}</pre></body></html>"
        )
        assertTrue("expected fenced block, got:\n$out",
            out.contains("```\nfun main() {\n  println(42)\n}\n```"))
    }

    @Test
    fun `pre with nested code does not double-wrap`() {
        val out = md(
            "<html><body><pre><code>val x = 1</code></pre></body></html>"
        )
        assertTrue("expected single fence, got:\n$out",
            out.contains("```\nval x = 1\n```"))
        // Ensure no extra inline backticks slipped in around the code.
        assertFalse("unexpected inline backtick: $out", out.contains("`val x"))
    }

    // ── Blockquote ───────────────────────────────────────────────────

    @Test
    fun `blockquote prefixes each line with greater-than`() {
        val out = md(
            "<html><body><blockquote><p>First line.</p><p>Second.</p></blockquote></body></html>"
        )
        // Both paragraph lines must have the marker.
        assertTrue("first line missing marker, got:\n$out", out.contains("> First line."))
        assertTrue("second line missing marker, got:\n$out", out.contains("> Second."))
    }

    // ── Images ───────────────────────────────────────────────────────

    @Test
    fun `image with alt text keeps alt inline`() {
        val out = md("""<html><body><p>See <img src="/x.png" alt="diagram"> here.</p></body></html>""")
        assertEquals("See diagram here.", out)
    }

    @Test
    fun `image with no alt text disappears`() {
        val out = md("""<html><body><p>before<img src="/x.png">after</p></body></html>""")
        assertEquals("beforeafter", out)
    }

    // ── Tables ───────────────────────────────────────────────────────

    @Test
    fun `tables flatten to pipe-separated rows`() {
        val out = md(
            """
            <html><body><table>
              <tr><th>H1</th><th>H2</th></tr>
              <tr><td>A</td><td>B</td></tr>
              <tr><td>C</td><td>D</td></tr>
            </table></body></html>
            """.trimIndent()
        )
        assertTrue("expected header row, got:\n$out", out.contains("H1 | H2"))
        assertTrue("expected data row, got:\n$out", out.contains("A | B"))
        assertTrue("expected data row, got:\n$out", out.contains("C | D"))
    }

    // ── Root selection and stripping ─────────────────────────────────

    @Test
    fun `article is preferred root over body`() {
        val out = md(
            """
            <html><body>
              <header><h1>Brand</h1></header>
              <article><h2>The story</h2><p>Body.</p></article>
              <footer>Junk</footer>
            </body></html>
            """.trimIndent()
        )
        assertTrue("article body should be present: $out", out.contains("## The story"))
        assertTrue("article body para should be present: $out", out.contains("Body."))
        // <header> sits *outside* <article> so we never see "Brand".
        assertFalse("Brand from outer <header> should not leak: $out", out.contains("Brand"))
        assertFalse("Footer junk should be stripped: $out", out.contains("Junk"))
    }

    @Test
    fun `main is fallback when article is absent`() {
        val out = md(
            """
            <html><body>
              <header><h1>Brand</h1></header>
              <main><h2>Topic</h2></main>
            </body></html>
            """.trimIndent()
        )
        assertTrue(out.contains("## Topic"))
        assertFalse("Brand from <header> outside <main> should not leak: $out", out.contains("Brand"))
    }

    @Test
    fun `script style and nav inside the chosen root are stripped`() {
        val out = md(
            """
            <html><body>
              <article>
                <nav>NAV LINKS</nav>
                <script>alert(1);</script>
                <style>.x{color:red}</style>
                <p>Real content.</p>
              </article>
            </body></html>
            """.trimIndent()
        )
        assertEquals("Real content.", out)
    }

    @Test
    fun `aria-hidden true elements are stripped`() {
        val out = md(
            """
            <html><body><article>
              <p>Visible</p>
              <p aria-hidden="true">Hidden decoration</p>
            </article></body></html>
            """.trimIndent()
        )
        assertTrue(out.contains("Visible"))
        assertFalse("aria-hidden should be stripped: $out", out.contains("Hidden decoration"))
    }

    // ── Whitespace and entities ──────────────────────────────────────

    @Test
    fun `html entities are decoded by jsoup`() {
        val out = md("<html><body><p>Tom &amp; Jerry &mdash; 1940s.</p></body></html>")
        // & survives unescaped, em-dash is the unicode char.
        assertTrue(out.contains("Tom & Jerry"))
        assertTrue(out.contains("—"))
    }

    @Test
    fun `runs of blank lines collapse to a single blank line`() {
        // Three paragraphs with empty paragraphs between them — the
        // renderer pads each with \n\n so the raw output has many
        // newlines; collapse() must cap at \n\n.
        val out = md(
            "<html><body><p>A</p><p></p><p></p><p>B</p></body></html>"
        )
        assertFalse("unexpected triple blank line: $out", out.contains("\n\n\n"))
        assertTrue("expected A then blank then B: $out", out.contains("A\n\nB"))
    }

    @Test
    fun `unknown tags fall through to children`() {
        val out = md("<html><body><custom-thing>kept</custom-thing></body></html>")
        assertTrue(out.contains("kept"))
    }

    // ── Smoke: end-to-end on a small realistic snippet ───────────────

    @Test
    fun `realistic snippet produces clean structured markdown`() {
        val html = """
            <html><body>
              <article>
                <h1>Pricing</h1>
                <p>Choose the plan that fits.</p>
                <h2>Tiers</h2>
                <ul>
                  <li><strong>Free</strong> &mdash; 5 GB</li>
                  <li><strong>Pro</strong> &mdash; <a href="/pro">unlimited</a></li>
                </ul>
                <pre><code>$ install --plan=free</code></pre>
              </article>
            </body></html>
        """.trimIndent()
        val out = md(html, baseUri = "https://example.com/")

        assertTrue("heading: $out", out.contains("# Pricing"))
        assertTrue("subheading: $out", out.contains("## Tiers"))
        assertTrue("first bullet: $out", out.contains("- **Free**"))
        assertTrue(
            "pro bullet with link: $out",
            out.contains("- **Pro**") &&
                out.contains("[unlimited](https://example.com/pro)")
        )
        assertTrue("fenced block: $out", out.contains("```\n\$ install --plan=free\n```"))
    }
}
