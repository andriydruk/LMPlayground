package com.druk.lmplayground.tools

import com.druk.llamacpp.tools.Tool
import com.druk.llamacpp.tools.WebLinkStore
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup
import java.util.concurrent.TimeUnit

class WebFetchTool(private val linkStore: WebLinkStore? = null) : Tool {
    override val name = "web_fetch"
    override val description = "Fetch a web page and return its main content as markdown. Headings, lists, links and code blocks are preserved so you can read the page structure."
    override val parametersSchema = """{"type":"object","properties":{"url":{"type":"string","description":"The URL to fetch, or a reference from web_search results such as \"ddg:3\""},"max_length":{"type":"integer","description":"Maximum content length in characters (default 5000)"}},"required":["url"]}"""

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    override fun cancelInFlight() {
        client.dispatcher.cancelAll()
    }

    override fun execute(arguments: String): String {
        return try {
            val args = JSONObject(arguments)
            val rawUrl = args.getString("url").trim()
            // A web_search result reference (e.g. "ddg:3") maps back to the real
            // URL we stored at search time. If it looks like a reference but
            // isn't known, tell the model to search first rather than fetching
            // a bogus host.
            val resolved = linkStore?.resolve(rawUrl)
            if (resolved == null && linkStore?.isReference(rawUrl) == true) {
                return errorJson("Unknown search reference '$rawUrl'. Call web_search first, then fetch a returned ref.")
            }
            val target = resolved ?: rawUrl
            val url = if (!target.startsWith("http")) "https://$target" else target
            val maxLength = args.optInt("max_length", 5000).coerceIn(50, 20000)

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .get()
                .build()

            val response = client.newCall(request).execute()
            val contentType = response.header("Content-Type") ?: ""
            val body = response.body?.string() ?: return errorJson("Empty response")

            val result = JSONObject()
            result.put("url", url)

            if (contentType.contains("text/html") || contentType.contains("application/xhtml")) {
                // Pass `url` as baseUri so Jsoup resolves relative
                // <a href> / <img src> to absolute URLs in markdown.
                val doc = Jsoup.parse(body, url)
                val title = doc.title()
                val markdown = HtmlToMarkdown.convert(doc)
                val truncated = if (markdown.length > maxLength) {
                    markdown.substring(0, maxLength) + "..."
                } else {
                    markdown
                }

                result.put("title", title)
                result.put("content", truncated)
                result.put("length", truncated.length)
            } else {
                // Non-HTML content — return raw text truncated. No
                // markdown conversion makes sense here.
                val truncated = if (body.length > maxLength) body.substring(0, maxLength) + "..." else body
                result.put("title", "")
                result.put("content", truncated)
                result.put("length", truncated.length)
            }

            result.toString()
        } catch (e: Exception) {
            errorJson(e.message ?: "Fetch failed")
        }
    }

    private fun errorJson(message: String): String {
        return """{"error":"${message.replace("\"", "'")}"}"""
    }

    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    }
}
