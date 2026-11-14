package com.druk.lmplayground.tools

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/**
 * Minimal HTML → Markdown converter tuned for LLM consumption.
 *
 * Goals:
 *   - Preserve the structural signal that lets a model reason about the
 *     page: headings, paragraph boundaries, list shape, link targets.
 *   - Stay tiny and dependency-free (only Jsoup, which we already ship).
 *   - Handle the long tail of malformed real-world HTML gracefully —
 *     unknown tags fall through to their children rather than throwing.
 *
 * Non-goals:
 *   - Full CommonMark spec compliance.
 *   - JavaScript-rendered pages (server-rendered HTML only).
 *   - Image bytes — `<img>` keeps its `alt` text inline if non-empty,
 *     drops the binary entirely.
 *   - GFM tables. We flatten `<table>` to plain `cell | cell` rows
 *     because models read that fine without needing the pipe header.
 *
 * Root selection: prefers `<article>` then `<main>` then `<body>`. That
 * way pages with a headline in `<header>` (which we strip) don't lose
 * the headline if it also lives inside `<article>`/`<main>`.
 */
object HtmlToMarkdown {

    /**
     * Convert a parsed HTML document to markdown. The document's
     * `baseUri` should be set (`Jsoup.parse(body, url)`) so relative
     * `<a href="…">` and `<img src="…">` resolve to absolute URLs.
     */
    fun convert(doc: Document): String {
        // Jsoup's Document.body() auto-creates a <body> when one isn't
        // present, so the chain below is non-null without a fallback.
        val root: Element = doc.selectFirst("article")
            ?: doc.selectFirst("main")
            ?: doc.body()

        // Drop noise inside whatever root we picked. Keep this list in
        // sync with the strip set in WebFetchTool's pre-Jsoup era so
        // behaviour is at least as good as the old plain-text path.
        root.select(
            "script, style, nav, footer, header, aside, " +
                "form, iframe, noscript, " +
                ".ad, .ads, .advertisement, [aria-hidden=true]"
        ).remove()

        val sb = StringBuilder(root.html().length / 2)
        renderNode(root, sb)
        return collapse(sb.toString())
    }

    // ── Walk ─────────────────────────────────────────────────────────

    private fun renderNode(node: Node, out: StringBuilder) {
        when (node) {
            is TextNode -> {
                // Collapse intra-text whitespace (matches how a browser
                // renders text). `<pre>` is handled separately so its
                // line breaks survive.
                val text = node.wholeText.replace(WHITESPACE_RUN, " ")
                out.append(text)
            }
            is Element -> renderElement(node, out)
        }
    }

    private fun renderElement(el: Element, out: StringBuilder) {
        when (el.normalName()) {
            "h1" -> heading(el, "#", out)
            "h2" -> heading(el, "##", out)
            "h3" -> heading(el, "###", out)
            "h4" -> heading(el, "####", out)
            "h5" -> heading(el, "#####", out)
            "h6" -> heading(el, "######", out)

            "p" -> {
                out.append("\n\n")
                renderChildren(el, out)
                out.append("\n\n")
            }

            "br" -> out.append('\n')
            "hr" -> out.append("\n\n---\n\n")

            "strong", "b" -> wrap(el, "**", out)
            "em", "i" -> wrap(el, "*", out)
            "del", "s", "strike" -> wrap(el, "~~", out)

            "code" -> {
                // Inline code unless we're already inside a <pre>, in
                // which case the <pre> branch handles fencing.
                if (el.parent()?.normalName() == "pre") renderChildren(el, out)
                else wrap(el, "`", out)
            }

            "pre" -> {
                // Preserve internal whitespace verbatim — the whole
                // point of a code block.
                out.append("\n\n```\n")
                out.append(el.wholeText())
                if (!out.endsWith('\n')) out.append('\n')
                out.append("```\n\n")
            }

            "blockquote" -> {
                val inner = StringBuilder()
                renderChildren(el, inner)
                inner.toString()
                    .trim()
                    .lines()
                    .forEach { line ->
                        out.append("> ")
                        out.append(line)
                        out.append('\n')
                    }
                out.append('\n')
            }

            "ul", "ol" -> renderList(el, out)
            "li" -> {
                // Stray <li> not under <ul>/<ol> — render as a bullet.
                out.append("- ")
                renderChildren(el, out)
                out.append('\n')
            }

            "a" -> {
                val href = el.absUrl("href")
                    .ifEmpty { el.attr("href") }
                    .trim()
                if (href.isEmpty() ||
                    href.startsWith("#") ||
                    href.startsWith("javascript:", ignoreCase = true)
                ) {
                    renderChildren(el, out)
                } else {
                    out.append('[')
                    renderChildren(el, out)
                    out.append("](").append(href).append(')')
                }
            }

            "img" -> {
                // Drop the binary; keep alt text inline if meaningful.
                val alt = el.attr("alt").trim()
                if (alt.isNotEmpty()) out.append(alt)
            }

            "table" -> renderTable(el, out)

            // Block-level wrappers: pass through, let children render.
            "div", "section", "article", "main", "body", "html",
            "figure", "figcaption" -> {
                renderChildren(el, out)
                // Add a soft paragraph break after block-level divs so
                // adjacent text doesn't fuse together.
                if (!out.endsWith("\n\n")) out.append('\n')
            }

            // Inline wrappers: pass through unchanged.
            "span", "small", "u", "mark", "sub", "sup", "abbr",
            "cite", "q", "kbd", "samp", "var", "time" -> renderChildren(el, out)

            // Anything else: render children and hope for the best.
            else -> renderChildren(el, out)
        }
    }

    private fun renderChildren(el: Element, out: StringBuilder) {
        for (child in el.childNodes()) renderNode(child, out)
    }

    private fun heading(el: Element, prefix: String, out: StringBuilder) {
        out.append("\n\n").append(prefix).append(' ')
        renderChildren(el, out)
        out.append("\n\n")
    }

    private fun wrap(el: Element, marker: String, out: StringBuilder) {
        out.append(marker)
        renderChildren(el, out)
        out.append(marker)
    }

    private fun renderList(el: Element, out: StringBuilder) {
        val ordered = el.normalName() == "ol"
        out.append('\n')
        var i = 1
        for (child in el.children()) {
            if (child.normalName() != "li") continue
            if (ordered) {
                out.append(i++).append(". ")
            } else {
                out.append("- ")
            }
            // Per-item children — rendered inline. Multi-paragraph
            // items just collapse into a single line; that's acceptable
            // for LLM consumption.
            val itemBuf = StringBuilder()
            renderChildren(child, itemBuf)
            out.append(itemBuf.toString().trim().replace('\n', ' '))
            out.append('\n')
        }
        out.append('\n')
    }

    private fun renderTable(el: Element, out: StringBuilder) {
        out.append('\n')
        for (row in el.select("tr")) {
            val cells = row.select("th, td")
                .map { it.text().trim() }
                .filter { it.isNotEmpty() }
            if (cells.isEmpty()) continue
            out.append(cells.joinToString(" | "))
            out.append('\n')
        }
        out.append('\n')
    }

    // ── Output cleanup ───────────────────────────────────────────────

    private fun collapse(s: String): String {
        // 1. Strip trailing spaces on each line (cosmetic).
        // 2. Cap blank-line runs at one (i.e. \n\n max).
        // We deliberately do NOT collapse runs of spaces here — that
        // would destroy indentation inside ``` fenced code blocks. Per-
        // text-node whitespace collapse already handles intra-paragraph
        // double-spacing; anything left is intentional.
        return s
            .replace(TRAILING_SPACES, "\n")
            .replace(BLANK_LINE_RUN, "\n\n")
            .trim()
    }

    private val WHITESPACE_RUN = Regex("\\s+")
    private val TRAILING_SPACES = Regex("[ \\t]+\\n")
    private val BLANK_LINE_RUN = Regex("\\n{3,}")
}
