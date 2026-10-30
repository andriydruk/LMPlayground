package com.druk.lmplayground.tools

import org.json.JSONObject

/**
 * Pure (no Android view) logic behind the `html_preview` tool, shared by the
 * tool itself ([HtmlPreviewTool]), the headless render check
 * ([WebViewHtmlRenderer]) and the in-chat card (HtmlPreviewCard).
 *
 * The HTML lives in the tool call's `arguments`, which are persisted with the
 * message, so a reopened conversation re-renders the page from history with no
 * extra storage.
 */
object HtmlPreview {

    const val TOOL_NAME = "html_preview"

    /**
     * Upper bound on the page source. Far above anything an on-device model
     * writes in one call, but keeps a runaway generation from producing a
     * multi-megabyte argument that the chat then has to keep re-laying out.
     */
    const val MAX_HTML_CHARS = 200_000

    /**
     * Pages are served from this origin through WebView request interception
     * (see HtmlPreviewWebView), never from the network. appassets.androidplatform.net
     * is the host Android reserves for exactly this; using a real https origin
     * gives the page working localStorage, relative #anchors and form posts.
     */
    const val PREVIEW_HOST = "appassets.androidplatform.net"
    const val PREVIEW_URL = "https://$PREVIEW_HOST/html-preview/index.html"

    private const val VIEWPORT_META =
        "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"

    data class Request(val html: String, val title: String?)

    /**
     * Parses tool-call arguments. Tolerates the common ways small models get
     * this slightly wrong: the page wrapped in a ```html fence, or JSON-escaped
     * a second time so newlines arrive as a literal backslash-n.
     */
    fun parse(arguments: String): Request? {
        val obj = try {
            JSONObject(arguments)
        } catch (_: Exception) {
            return null
        }
        val raw = obj.optString("html", "").ifEmpty {
            // Some models pick their own key name; accept the obvious ones.
            obj.optString("code", "").ifEmpty { obj.optString("content", "") }
        }
        val html = normalize(raw)
        if (html.isBlank()) return null
        val title = obj.optString("title", "").trim().takeIf { it.isNotEmpty() }
        return Request(html, title)
    }

    /** Strips a surrounding markdown fence and undoes a second layer of escaping. */
    fun normalize(raw: String): String {
        var s = raw.trim()
        if (s.startsWith("```")) {
            val firstNewline = s.indexOf('\n')
            s = if (firstNewline >= 0) s.substring(firstNewline + 1) else ""
            s = s.trimEnd()
            if (s.endsWith("```")) s = s.dropLast(3)
            s = s.trim()
        }
        val literalNewlines = LITERAL_NEWLINE.findAll(s).count()
        if (!s.contains('\n') && literalNewlines >= 3) {
            s = s.replace("\\r\\n", "\n")
                .replace("\\n", "\n")
                .replace("\\t", "\t")
                .replace("\\\"", "\"")
        }
        return s
    }

    /**
     * Turns what the model sent into a full document that renders well on a
     * phone: a bare fragment gets a document shell, and a page without a
     * viewport meta tag gets one (otherwise WebView lays it out at desktop
     * width and shrinks it to unreadable text).
     */
    fun buildDocument(html: String): String {
        val lower = html.lowercase()
        val isFragment = !lower.contains("<html") && !lower.contains("<head") &&
            !lower.contains("<body")
        if (isFragment) {
            return "<!DOCTYPE html>\n<html>\n<head>\n<meta charset=\"utf-8\">\n" +
                "$VIEWPORT_META\n</head>\n<body>\n$html\n</body>\n</html>\n"
        }
        if (VIEWPORT.containsMatchIn(html)) return html

        HEAD_OPEN.find(html)?.let { m ->
            return html.substring(0, m.range.last + 1) + VIEWPORT_META + html.substring(m.range.last + 1)
        }
        HTML_OPEN.find(html)?.let { m ->
            return html.substring(0, m.range.last + 1) + "<head>$VIEWPORT_META</head>" +
                html.substring(m.range.last + 1)
        }
        return VIEWPORT_META + html
    }

    /**
     * Static checks for mistakes that make a page silently fail. Returned to
     * the model as warnings so it can fix them on its next call.
     */
    fun lint(html: String): List<String> {
        val warnings = mutableListOf<String>()
        val lower = html.lowercase()

        if (lower.contains("<html") && !lower.contains("</html>")) {
            warnings += "The document has <html> but no closing </html>; it may be truncated."
        }
        val scriptOpen = SCRIPT_OPEN.findAll(html).count()
        val scriptClose = SCRIPT_CLOSE.findAll(html).count()
        if (scriptOpen != scriptClose) {
            warnings += "Unbalanced <script> tags ($scriptOpen opening, $scriptClose closing)."
        }
        val styleOpen = STYLE_OPEN.findAll(html).count()
        val styleClose = STYLE_CLOSE.findAll(html).count()
        if (styleOpen != styleClose) {
            warnings += "Unbalanced <style> tags ($styleOpen opening, $styleClose closing)."
        }

        val localRefs = LOCAL_RESOURCE.findAll(html)
            .map { it.groupValues[2] }
            .filterNot { isInlineOrRemote(it) }
            .distinct()
            .take(3)
            .toList()
        if (localRefs.isNotEmpty()) {
            warnings += "Local file references can't load in the preview: " +
                localRefs.joinToString(", ") +
                ". Inline the CSS/JS in <style>/<script>, or use full https:// URLs."
        }
        if (INSECURE_RESOURCE.containsMatchIn(html)) {
            warnings += "http:// resources are blocked on the https preview page; use https:// URLs."
        }
        return warnings
    }

    private fun isInlineOrRemote(ref: String): Boolean {
        val r = ref.trim().lowercase()
        return r.isEmpty() || r.startsWith("https://") || r.startsWith("http://") ||
            r.startsWith("//") || r.startsWith("data:") || r.startsWith("blob:") ||
            r.startsWith("#") || r.startsWith("javascript:") || r.startsWith("mailto:") ||
            r.startsWith("tel:") || r.startsWith("about:")
    }

    private val LITERAL_NEWLINE = Regex("""\\n""")
    private val VIEWPORT = Regex("""<meta[^>]+name\s*=\s*["']?viewport""", RegexOption.IGNORE_CASE)
    private val HEAD_OPEN = Regex("""<head(\s[^>]*)?>""", RegexOption.IGNORE_CASE)
    private val HTML_OPEN = Regex("""<html(\s[^>]*)?>""", RegexOption.IGNORE_CASE)
    private val SCRIPT_OPEN = Regex("""<script(\s[^>]*)?>""", RegexOption.IGNORE_CASE)
    private val SCRIPT_CLOSE = Regex("""</script\s*>""", RegexOption.IGNORE_CASE)
    private val STYLE_OPEN = Regex("""<style(\s[^>]*)?>""", RegexOption.IGNORE_CASE)
    private val STYLE_CLOSE = Regex("""</style\s*>""", RegexOption.IGNORE_CASE)

    // src="..." on script/img/iframe/audio/video/source, href="..." on <link>.
    private val LOCAL_RESOURCE = Regex(
        """<(?:script|img|iframe|audio|video|source|link)\b[^>]*?\b(src|href)\s*=\s*["']([^"']*)["']""",
        RegexOption.IGNORE_CASE,
    )
    private val INSECURE_RESOURCE = Regex(
        """<(?:script|img|iframe|link|audio|video|source)\b[^>]*?\b(?:src|href)\s*=\s*["']http://""",
        RegexOption.IGNORE_CASE,
    )
}
