package com.druk.lmplayground.tools

import com.druk.llamacpp.tools.Tool
import org.json.JSONArray
import org.json.JSONObject

/**
 * Lets the model show a web page it wrote directly in the chat.
 *
 * The tool itself does no rendering: the HTML travels in the call's
 * `arguments`, and the chat bubble (see HtmlPreviewCard) renders those into a
 * sandboxed WebView. What this class adds is validation and feedback, so the
 * model learns whether the page was accepted and can fix obvious mistakes.
 */
class HtmlPreviewTool : Tool {
    override val name = HtmlPreview.TOOL_NAME
    override val description =
        "Show an HTML page live in the chat so the user can see it and interact with it (buttons, forms, canvas, games). " +
            "Use it whenever you write a web page, UI, chart, game or visual demo. " +
            "Pass ONE complete self-contained document with inline CSS and JavaScript; https CDN scripts are allowed. " +
            "To revise the page, call again with the full updated HTML."
    override val parametersSchema =
        """{"type":"object","properties":{"html":{"type":"string","description":"The complete HTML document (<!DOCTYPE html> ... </html>) with inline <style> and <script>."},"title":{"type":"string","description":"Optional short title shown above the preview."}},"required":["html"]}"""

    override fun execute(arguments: String): String {
        val request = HtmlPreview.parse(arguments)
            ?: return error("Arguments must be a JSON object with a non-empty \"html\" string.")
        if (request.html.length > HtmlPreview.MAX_HTML_CHARS) {
            return error(
                "HTML is ${request.html.length} characters; the limit is ${HtmlPreview.MAX_HTML_CHARS}. " +
                    "Send a smaller, simpler page."
            )
        }
        val warnings = HtmlPreview.lint(request.html)
        return JSONObject().apply {
            put("status", "rendered")
            put("chars", request.html.length)
            put(
                "message",
                "The page is now displayed live in the chat and the user can interact with it. " +
                    "Do not repeat the HTML in your reply; briefly describe what you built. " +
                    "To change it, call html_preview again with the complete updated HTML."
            )
            if (warnings.isNotEmpty()) put("warnings", JSONArray(warnings))
        }.toString()
    }

    private fun error(message: String) = JSONObject().put("error", message).toString()
}
