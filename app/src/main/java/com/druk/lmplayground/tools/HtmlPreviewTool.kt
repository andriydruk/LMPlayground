package com.druk.lmplayground.tools

import com.druk.llamacpp.tools.Tool
import org.json.JSONArray
import org.json.JSONObject

/**
 * Lets the model show a web page it wrote as a live, interactive preview in
 * the chat.
 *
 * Rendering for the user happens in the chat bubble (HtmlPreviewCard), which
 * reads the HTML straight from this call's persisted arguments. This class is
 * the model's side of the loop: it validates the page, actually runs it
 * off-screen via [renderer], and reports back script errors, what visibly
 * rendered and layout problems, so the model can fix its page and call again.
 *
 * @param renderer null skips the run (static checks only), e.g. in tests.
 */
class HtmlPreviewTool(
    private val renderer: HtmlPreviewRenderer? = null,
) : Tool {
    override val name = HtmlPreview.TOOL_NAME
    override val description =
        "Show an HTML page live in the chat. The user sees it rendered and can interact with it " +
            "(tap buttons, fill forms, play games, view charts and animations). " +
            "Use this whenever you write HTML, a web page, UI mockup, chart, game or visual demo, instead of only printing code. " +
            "Send ONE complete self-contained document with inline <style> and <script>; https CDN links are allowed, local files are not. " +
            "The result reports script errors and what rendered; if there are errors, fix them and call again with the full corrected HTML."
    override val parametersSchema =
        """{"type":"object","properties":{"html":{"type":"string","description":"The complete HTML document, from <!DOCTYPE html> to </html>, with inline CSS and JavaScript."},"title":{"type":"string","description":"Short title shown above the preview, e.g. \"Tip calculator\"."}},"required":["html"]}"""

    override fun execute(arguments: String): String {
        val request = HtmlPreview.parse(arguments)
            ?: return error("Arguments must be a JSON object with a non-empty \"html\" string containing the page.")
        if (request.html.length > HtmlPreview.MAX_HTML_CHARS) {
            return error(
                "The HTML is ${request.html.length} characters; the limit is ${HtmlPreview.MAX_HTML_CHARS}. " +
                    "Send a smaller page."
            )
        }

        val warnings = HtmlPreview.lint(request.html).toMutableList()
        val report = try {
            renderer?.render(HtmlPreview.buildDocument(request.html))
        } catch (_: Exception) {
            null
        }

        val result = JSONObject()
        result.put("status", "displayed")
        if (report != null && report.loaded) {
            if (report.title.isNotEmpty()) result.put("page_title", report.title)
            result.put("visible_text", report.visibleText)
            if (report.visibleText.isEmpty() && report.graphicsCount == 0 && report.contentHeight < 20) {
                warnings += "The page rendered blank: no visible text or graphics."
            }
            if (report.overflowsHorizontally) {
                warnings += "The page is wider than a phone screen and scrolls sideways; use responsive widths (max-width:100%, flex-wrap)."
            }
        } else if (report != null) {
            warnings += "The page did not finish loading within a few seconds (slow CDN, or an infinite loop?)."
        }
        if (report != null && report.errors.isNotEmpty()) {
            result.put("console_errors", JSONArray(report.errors))
        }
        if (report != null && report.dialogs.isNotEmpty()) {
            result.put("dialogs_on_load", JSONArray(report.dialogs))
        }
        if (warnings.isNotEmpty()) result.put("warnings", JSONArray(warnings))

        val hasProblems = (report?.errors?.isNotEmpty() == true) || warnings.isNotEmpty()
        result.put(
            "message",
            if (hasProblems) {
                "The page is shown to the user, but it has problems (see above). " +
                    "Fix them by calling html_preview again with the complete corrected HTML, " +
                    "then briefly tell the user what you built."
            } else {
                "The page is now live in the chat and the user can interact with it. " +
                    "Do not repeat the HTML in your reply; briefly describe what you built and how to use it."
            },
        )
        return result.toString()
    }

    override fun cancelInFlight() {
        renderer?.cancel()
    }

    private fun error(message: String) = JSONObject().put("error", message).toString()
}
