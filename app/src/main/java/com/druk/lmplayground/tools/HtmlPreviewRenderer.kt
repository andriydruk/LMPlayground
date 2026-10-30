package com.druk.lmplayground.tools

/** What a page did when it was actually run; see [HtmlPreviewRenderer]. */
data class HtmlRenderReport(
    val loaded: Boolean,
    val title: String = "",
    val visibleText: String = "",
    val elementCount: Int = 0,
    /** canvas / svg / img / video elements: a page can be all graphics and no text. */
    val graphicsCount: Int = 0,
    val contentHeight: Int = 0,
    val overflowsHorizontally: Boolean = false,
    val errors: List<String> = emptyList(),
    val dialogs: List<String> = emptyList(),
)

/**
 * Runs a page off-screen so the model learns what really happened — script
 * errors, whether anything visible rendered — instead of assuming success.
 * An interface so the tool can be unit-tested without a WebView.
 */
fun interface HtmlPreviewRenderer {
    /** Blocks the calling (background) thread; returns null if rendering isn't possible here. */
    fun render(document: String): HtmlRenderReport?

    /** Stop waiting for an in-flight [render] (the user pressed Stop). */
    fun cancel() {}
}
