package com.druk.lmplayground.conversation

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.view.MotionEvent
import androidx.annotation.StringRes
import android.webkit.ConsoleMessage
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.druk.lmplayground.R
import com.druk.lmplayground.tools.HtmlPreview

private val INLINE_HEIGHT = 360.dp
private const val MAX_CONSOLE_LINES = 100

/**
 * A live, interactive render of the page the model passed to `html_preview`.
 *
 * The page runs in a locked-down WebView (see [PreviewWebView]); this card adds
 * the chrome around it: reload, view source, copy, fullscreen and a console
 * readout so runtime errors in model-written code are visible to the user.
 */
@Composable
fun HtmlPreviewCard(request: HtmlPreview.Request) {
    val document = remember(request.html) { HtmlPreview.toDocument(request.html) }
    val clipboard = LocalClipboardManager.current
    val console = remember(request.html) { mutableStateListOf<String>() }
    var reloadKey by remember { mutableIntStateOf(0) }
    var showCode by remember { mutableStateOf(false) }
    var showConsole by remember { mutableStateOf(false) }
    var fullscreen by remember { mutableStateOf(false) }

    val onConsole: (String) -> Unit = { line ->
        if (console.size >= MAX_CONSOLE_LINES) console.removeAt(0)
        console.add(line)
    }
    val reload: () -> Unit = {
        console.clear()
        reloadKey++
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            PreviewHeader(
                title = request.title.ifBlank { stringResource(R.string.html_preview_title) },
                showCode = showCode,
                onReload = reload,
                onToggleCode = { showCode = !showCode },
                onCopy = { clipboard.setText(AnnotatedString(request.html)) },
                onFullscreen = { fullscreen = true },
            )
            if (showCode) {
                SelectionContainer {
                    Text(
                        text = request.html,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = INLINE_HEIGHT)
                            .verticalScroll(rememberScrollState())
                            .padding(12.dp),
                    )
                }
            } else if (!fullscreen) {
                // While fullscreen the dialog owns the live page, so the inline
                // copy is released instead of running the page twice.
                PreviewWebView(
                    document = document,
                    reloadKey = reloadKey,
                    onConsole = onConsole,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(INLINE_HEIGHT),
                )
            } else {
                Surface(modifier = Modifier.fillMaxWidth().height(INLINE_HEIGHT)) {}
            }
            if (console.isNotEmpty()) {
                ConsoleFooter(
                    lines = console,
                    expanded = showConsole,
                    onToggle = { showConsole = !showConsole },
                )
            }
        }
    }

    if (fullscreen) {
        Dialog(
            onDismissRequest = { fullscreen = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(modifier = Modifier.fillMaxSize()) {
                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
                    ) {
                        Text(
                            text = request.title.ifBlank { stringResource(R.string.html_preview_title) },
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = reload) {
                            Icon(Icons.Outlined.Refresh, stringResource(R.string.html_preview_reload))
                        }
                        IconButton(onClick = { fullscreen = false }) {
                            Icon(Icons.Outlined.Close, stringResource(R.string.html_preview_close))
                        }
                    }
                    PreviewWebView(
                        document = document,
                        reloadKey = reloadKey,
                        onConsole = onConsole,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun PreviewHeader(
    title: String,
    showCode: Boolean,
    onReload: () -> Unit,
    onToggleCode: () -> Unit,
    onCopy: () -> Unit,
    onFullscreen: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(0.dp),
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 2.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        HeaderButton(Icons.Outlined.Refresh, R.string.html_preview_reload, onReload)
        HeaderButton(
            if (showCode) Icons.Outlined.Visibility else Icons.Outlined.Code,
            if (showCode) R.string.html_preview_show_preview else R.string.html_preview_show_code,
            onToggleCode,
        )
        HeaderButton(Icons.Outlined.ContentCopy, R.string.html_preview_copy, onCopy)
        HeaderButton(Icons.Outlined.Fullscreen, R.string.html_preview_fullscreen, onFullscreen)
    }
}

@Composable
private fun HeaderButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    @StringRes descriptionRes: Int,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(40.dp)) {
        Icon(icon, stringResource(descriptionRes), modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun ConsoleFooter(lines: List<String>, expanded: Boolean, onToggle: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(12.dp, 6.dp)) {
        Text(
            text = stringResource(R.string.html_preview_console, lines.size),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.outline,
        )
        if (expanded) {
            SelectionContainer {
                Text(
                    text = lines.joinToString("\n"),
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                    ),
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.heightIn(max = 160.dp).verticalScroll(rememberScrollState()),
                )
            }
        }
    }
}

/**
 * The sandbox for model-written pages.
 *
 * JavaScript and DOM storage are on so the page works like a normal one, but it
 * gets no bridge into the app (no addJavascriptInterface), no file or content
 * URI access, and no permission grants (camera, mic, location are denied by
 * the default WebChromeClient). Link taps leave the sandbox to the user's
 * browser instead of navigating the preview away.
 */
@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
@Composable
private fun PreviewWebView(
    document: String,
    reloadKey: Int,
    onConsole: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val loadKey = remember(document, reloadKey) { document.hashCode() * 31 + reloadKey }
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                // Pages assume a white canvas; leaving the theme's dark
                // background would turn unstyled pages into black-on-black.
                setBackgroundColor(AndroidColor.WHITE)
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    allowFileAccess = false
                    allowContentAccess = false
                    setSupportMultipleWindows(false)
                    mediaPlaybackRequiresUserGesture = true
                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    setGeolocationEnabled(false)
                }
                // The preview scrolls itself; stop the chat list from stealing
                // the gesture while a finger is on the page.
                setOnTouchListener { v, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> v.parent?.requestDisallowInterceptTouchEvent(true)
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                            v.parent?.requestDisallowInterceptTouchEvent(false)
                    }
                    false
                }
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: WebResourceRequest,
                    ): Boolean {
                        val url = request.url
                        // Embedded iframes (maps, videos, demos) load in place.
                        if (!request.isForMainFrame) return false
                        // Same-document anchors (#section) stay in the preview.
                        if (url.buildUpon().fragment(null).build().toString() == HtmlPreview.BASE_URL) {
                            return false
                        }
                        // Anything else on the fake preview origin (a GET form,
                        // a relative link) has nowhere to go: swallow it rather
                        // than send the user's browser to an unresolvable host.
                        if (url.host == PREVIEW_HOST) return true
                        // Real links leave the sandbox for the user's browser,
                        // but only on a tap: scripts can't pop external apps.
                        if (request.hasGesture() && url.scheme in EXTERNAL_SCHEMES) {
                            try {
                                view.context.startActivity(
                                    Intent(Intent.ACTION_VIEW, url)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            } catch (_: Exception) {
                                // No handler for the link; nothing to do.
                            }
                        }
                        return true
                    }
                }
                webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                        onConsole(
                            "[${message.messageLevel().name.lowercase()}] ${message.message()}" +
                                " (line ${message.lineNumber()})"
                        )
                        return true
                    }

                    override fun onJsAlert(
                        view: WebView, url: String?, message: String?, result: JsResult,
                    ): Boolean = showDialog(view, message, result, withCancel = false)

                    override fun onJsConfirm(
                        view: WebView, url: String?, message: String?, result: JsResult,
                    ): Boolean = showDialog(view, message, result, withCancel = true)
                }
            }
        },
        update = { view ->
            if (view.tag != loadKey) {
                view.tag = loadKey
                view.loadDataWithBaseURL(
                    HtmlPreview.BASE_URL, document, "text/html", "UTF-8", HtmlPreview.BASE_URL,
                )
            }
        },
        onRelease = { view ->
            view.stopLoading()
            view.destroy()
        },
    )
}

private val EXTERNAL_SCHEMES = setOf("http", "https", "mailto", "tel")
private val PREVIEW_HOST = android.net.Uri.parse(HtmlPreview.BASE_URL).host

/** Native stand-in for the page's alert()/confirm(); the page blocks until [result] is answered. */
private fun showDialog(view: WebView, message: String?, result: JsResult, withCancel: Boolean): Boolean {
    return try {
        val builder = AlertDialog.Builder(view.context)
            .setMessage(message.orEmpty())
            .setCancelable(false)
            .setPositiveButton(android.R.string.ok) { _, _ -> result.confirm() }
        if (withCancel) builder.setNegativeButton(android.R.string.cancel) { _, _ -> result.cancel() }
        builder.show()
        true
    } catch (_: Exception) {
        // Window not available (e.g. activity finishing): answer so the page isn't left hanging.
        result.cancel()
        true
    }
}
