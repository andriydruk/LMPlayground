package com.druk.lmplayground.tools

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebView
import org.json.JSONObject
import org.json.JSONTokener
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * [HtmlPreviewRenderer] backed by a detached WebView on the main thread, with
 * the same sandbox as the chat card. The tool loop calls it from a
 * background dispatcher and waits up to [timeoutMs].
 */
class WebViewHtmlRenderer(
    context: Context,
    private val timeoutMs: Long = 6_000,
    private val settleMs: Long = 700,
) : HtmlPreviewRenderer {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val inFlight = AtomicReference<CountDownLatch?>(null)

    override fun render(document: String): HtmlRenderReport? {
        // Never block the main thread waiting for work queued on it.
        if (Looper.myLooper() == Looper.getMainLooper()) return null

        val latch = CountDownLatch(1)
        inFlight.set(latch)
        val errors = Collections.synchronizedList(mutableListOf<String>())
        val dialogs = Collections.synchronizedList(mutableListOf<String>())
        val probe = AtomicReference<JSONObject?>(null)
        val webViewRef = AtomicReference<WebView?>(null)
        val probed = AtomicBoolean(false)

        mainHandler.post {
            try {
                val webView = WebView(appContext)
                webViewRef.set(webView)
                HtmlPreviewWebView.configure(webView)
                webView.webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                        if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR &&
                            errors.size < MAX_ERRORS
                        ) {
                            errors += formatConsoleError(message)
                        }
                        return true
                    }

                    // Nobody is there to answer dialogs in the off-screen run:
                    // record them and let the page carry on.
                    override fun onJsAlert(view: WebView, url: String?, msg: String?, result: JsResult): Boolean {
                        if (dialogs.size < MAX_ERRORS) dialogs += "alert: ${msg.orEmpty().take(200)}"
                        result.confirm()
                        return true
                    }

                    override fun onJsConfirm(view: WebView, url: String?, msg: String?, result: JsResult): Boolean {
                        if (dialogs.size < MAX_ERRORS) dialogs += "confirm: ${msg.orEmpty().take(200)}"
                        result.confirm()
                        return true
                    }

                    override fun onJsPrompt(
                        view: WebView, url: String?, msg: String?, defaultValue: String?, result: JsPromptResult,
                    ): Boolean {
                        if (dialogs.size < MAX_ERRORS) dialogs += "prompt: ${msg.orEmpty().take(200)}"
                        result.confirm(defaultValue.orEmpty())
                        return true
                    }
                }
                webView.webViewClient = object : HtmlPreviewWebViewClient(
                    document = { document },
                    onExternalLink = {},
                ) {
                    override fun onPageFinished(view: WebView, url: String?) {
                        if (!probed.compareAndSet(false, true)) return
                        // Let load handlers, timers and CDN scripts run first.
                        mainHandler.postDelayed({
                            view.evaluateJavascript(PROBE_JS) { raw ->
                                probe.set(decodeProbe(raw))
                                latch.countDown()
                            }
                        }, settleMs)
                    }
                }
                // A detached WebView has no size; give it a phone-shaped viewport
                // so layout, innerText and overflow checks mean something.
                val density = appContext.resources.displayMetrics.density
                val width = (VIEWPORT_WIDTH_DP * density).toInt()
                val height = (VIEWPORT_HEIGHT_DP * density).toInt()
                webView.measure(
                    View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
                )
                webView.layout(0, 0, width, height)
                HtmlPreviewWebView.load(webView)
            } catch (t: Throwable) {
                // No WebView provider (rare: disabled/updating). Report nothing
                // and let the tool fall back to static checks.
                Log.w(TAG, "Headless render failed", t)
                latch.countDown()
            }
        }

        val finished = try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        } finally {
            inFlight.compareAndSet(latch, null)
        }
        mainHandler.post {
            webViewRef.getAndSet(null)?.let { wv ->
                wv.stopLoading()
                wv.destroy()
            }
        }
        val result = probe.get()
        return HtmlRenderReport(
            loaded = finished && result != null,
            title = result?.optString("title").orEmpty(),
            visibleText = result?.optString("text").orEmpty(),
            elementCount = result?.optInt("elements") ?: 0,
            graphicsCount = result?.optInt("graphics") ?: 0,
            contentHeight = result?.optInt("height") ?: 0,
            overflowsHorizontally = result?.optBoolean("overflowX") ?: false,
            errors = synchronized(errors) { errors.toList() },
            dialogs = synchronized(dialogs) { dialogs.toList() },
        )
    }

    override fun cancel() {
        inFlight.getAndSet(null)?.countDown()
    }

    private fun decodeProbe(raw: String?): JSONObject? = try {
        // evaluateJavascript hands back the JSON encoding of the returned
        // value; the probe returns a JSON string, so decode twice.
        val inner = JSONTokener(raw ?: "null").nextValue() as? String
        inner?.let { JSONObject(it) }
    } catch (_: Exception) {
        null
    }

    private fun formatConsoleError(message: ConsoleMessage): String {
        val line = message.lineNumber()
        val source = message.sourceId().orEmpty()
        val where = when {
            source.isEmpty() || HtmlPreviewWebView.isPreviewUrl(android.net.Uri.parse(source)) ->
                if (line > 0) " (line $line)" else ""
            else -> " ($source:$line)"
        }
        return message.message().take(300) + where
    }

    companion object {
        private const val TAG = "WebViewHtmlRenderer"
        private const val MAX_ERRORS = 8
        private const val VIEWPORT_WIDTH_DP = 390
        private const val VIEWPORT_HEIGHT_DP = 760

        private const val PROBE_JS = """(function(){
  var d=document, b=d.body, e=d.documentElement;
  var text=(b&&b.innerText?b.innerText:'').replace(/\s+/g,' ').trim();
  return JSON.stringify({
    title: d.title||'',
    text: text.slice(0,400),
    elements: d.getElementsByTagName('*').length,
    graphics: d.querySelectorAll('canvas,svg,img,video').length,
    height: Math.max(e?e.scrollHeight:0, b?b.scrollHeight:0),
    overflowX: (e?e.scrollWidth:0) > window.innerWidth + 4
  });
})()"""
    }
}
