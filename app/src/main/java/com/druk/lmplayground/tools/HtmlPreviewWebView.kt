package com.druk.lmplayground.tools

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream

/**
 * One WebView setup for every place a model-written page runs: the inline
 * chat card, its fullscreen view, and the headless check in
 * [WebViewHtmlRenderer]. Keeping it in one place means the model's
 * "it rendered" feedback comes from the same sandbox the user sees.
 *
 * Sandbox: JavaScript on (pages are meant to be interactive), but no file or
 * content:// access, no JavaScript bridge into the app, no popups, no
 * geolocation, third-party cookies off. The page is served from
 * [HtmlPreview.PREVIEW_URL] by request interception, so nothing about the
 * app's own origin or files is reachable from it.
 */
object HtmlPreviewWebView {

    @SuppressLint("SetJavaScriptEnabled")
    fun configure(webView: WebView) {
        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            setGeolocationEnabled(false)
            mediaPlaybackRequiresUserGesture = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            safeBrowsingEnabled = true
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false)
    }

    fun load(webView: WebView) {
        webView.loadUrl(HtmlPreview.PREVIEW_URL)
    }

    fun isPreviewUrl(uri: Uri?): Boolean = uri?.host == HtmlPreview.PREVIEW_HOST
}

/**
 * Serves the page document for any main-frame request to the preview origin
 * (initial load, reload, a form that submits to itself) and a 404 for any
 * other path there, so relative file references fail fast instead of hitting
 * the network. Links leaving the page go to [onExternalLink] instead of
 * replacing the preview.
 */
open class HtmlPreviewWebViewClient(
    private val document: () -> String,
    private val onExternalLink: (Uri) -> Unit,
) : WebViewClient() {

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest,
    ): WebResourceResponse? {
        if (!HtmlPreviewWebView.isPreviewUrl(request.url)) return null
        return if (request.isForMainFrame) {
            WebResourceResponse(
                "text/html",
                "utf-8",
                ByteArrayInputStream(document().toByteArray(Charsets.UTF_8)),
            )
        } else {
            WebResourceResponse(
                "text/plain",
                "utf-8",
                404,
                "Not Found",
                mapOf("Access-Control-Allow-Origin" to "*"),
                ByteArrayInputStream(
                    "Not found: the preview has no files besides the page itself."
                        .toByteArray(Charsets.UTF_8)
                ),
            )
        }
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url
        if (HtmlPreviewWebView.isPreviewUrl(url)) return false
        // Only a user's tap should leave the app; a page redirecting itself
        // on load is just dropped.
        if (request.hasGesture()) onExternalLink(url)
        return true
    }
}
