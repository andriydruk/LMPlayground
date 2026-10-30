package com.druk.lmplayground.tools

import org.json.JSONObject

/**
 * Pure helpers shared by [HtmlPreviewTool] (what the model sees) and the chat
 * UI (what the user sees), so both sides agree on what counts as a renderable
 * preview. Kept free of Android UI types so it can be unit tested on the JVM.
 */
object HtmlPreview {
    const val TOOL_NAME = "html_preview"

    /** Upper bound on one document; stays well under the AIDL payload cap and a small model's context window. */
    const val MAX_HTML_CHARS = 100_000

    /**
     * Fake, non-resolvable origin the page is loaded under. Gives the page a
     * stable origin (so localStorage / fetch behave normally) while making any
     * request back to the "page itself" fail harmlessly instead of hitting a
     * real server. The `.invalid` TLD is reserved and never resolves.
     */
    const val BASE_URL = "https://preview.lmplayground.invalid/"

    data class Request(val title: String, val html: String)

    /** Parses the model's tool arguments; null when they are malformed or carry no HTML. */
    fun parse(arguments: String): Request? {
        val obj = try {
            JSONObject(arguments)
        } catch (_: Exception) {
            return null
        }
        val html = obj.optString("html", "")
        if (html.isBlank() || html == "null") return null
        val title = obj.optString("title", "").trim().takeIf { it != "null" }.orEmpty()
        return Request(title = title, html = html)
    }

    /** True when [result] is the tool's success payload (as opposed to an error). */
    fun wasRendered(result: String): Boolean = try {
        JSONObject(result).optString("status") == "rendered"
    } catch (_: Exception) {
        false
    }

    /**
     * Turns the model's HTML into a full document. Fragments (no `<html>` /
     * doctype) get wrapped with a charset and mobile viewport so they lay out
     * sensibly on a phone; complete documents are loaded exactly as written.
     */
    fun toDocument(html: String): String {
        val head = html.take(1_000).lowercase()
        if ("<html" in head || "<!doctype" in head) return html
        return "<!DOCTYPE html><html><head><meta charset=\"utf-8\">" +
            "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">" +
            "</head><body>$html</body></html>"
    }

    /** Cheap static checks whose findings are handed back to the model so it can fix itself. */
    fun lint(html: String): List<String> {
        val warnings = mutableListOf<String>()
        val lower = html.lowercase()
        for (tag in listOf("script", "style")) {
            val opens = Regex("<$tag[\\s>]").findAll(lower).count()
            val closes = Regex("</$tag\\s*>").findAll(lower).count()
            if (opens != closes) warnings += "Unbalanced <$tag> tags ($opens opened, $closes closed)."
        }
        if (Regex("<script[^>]+src=[\"']?(?:\\./|/)(?!/)").containsMatchIn(lower)) {
            warnings += "A <script src> points at a relative path; there are no sibling files, so inline it or use an https CDN URL."
        }
        return warnings
    }
}
