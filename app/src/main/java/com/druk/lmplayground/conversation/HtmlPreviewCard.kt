package com.druk.lmplayground.conversation

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.MotionEvent
import android.webkit.ConsoleMessage
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.EditText
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material.icons.outlined.OpenInFull
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.Web
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.druk.lmplayground.R
import com.druk.lmplayground.tools.HtmlPreview
import com.druk.lmplayground.tools.HtmlPreviewWebView
import com.druk.lmplayground.tools.HtmlPreviewWebViewClient
import java.io.File

/**
 * The user-facing half of the `html_preview` tool: the page the model wrote,
 * running live inside the chat. Rendered from the tool call's persisted
 * arguments, so it comes back when a conversation is reopened.
 *
 * Inline, the WebView keeps touches for itself (so games, sliders and inner
 * scrolling work) and the chat scrolls from the header or around the card;
 * fullscreen gives the page the whole screen.
 */
@Composable
fun HtmlPreviewCard(
    request: HtmlPreview.Request,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val document = remember(request.html) { HtmlPreview.buildDocument(request.html) }
    val title = request.title ?: stringResource(R.string.html_preview_default_title)

    var showSource by remember { mutableStateOf(false) }
    var fullscreen by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableIntStateOf(0) }
    val consoleErrors = remember { mutableStateListOf<String>() }

    val screenHeightDp = LocalConfiguration.current.screenHeightDp
    val maxInlineHeight = (screenHeightDp * 0.6f).toInt().coerceIn(MIN_HEIGHT_DP, MAX_HEIGHT_DP)
    var pageHeightDp by remember { mutableIntStateOf(DEFAULT_HEIGHT_DP) }
    val inlineHeight = pageHeightDp.coerceIn(MIN_HEIGHT_DP, maxInlineHeight)

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.animateContentSize()) {
            // Header: title + actions. Also the drag handle for scrolling the
            // chat past a preview that keeps its own touches.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Web,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 8.dp),
                )
                IconButton(onClick = {
                    consoleErrors.clear()
                    showSource = false
                    reloadKey++
                }) {
                    Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.html_preview_reload))
                }
                IconButton(onClick = { showSource = !showSource }) {
                    if (showSource) {
                        Icon(Icons.Outlined.Visibility, contentDescription = stringResource(R.string.html_preview_show_page))
                    } else {
                        Icon(Icons.Outlined.Code, contentDescription = stringResource(R.string.html_preview_show_source))
                    }
                }
                IconButton(onClick = { fullscreen = true }) {
                    Icon(Icons.Outlined.OpenInFull, contentDescription = stringResource(R.string.html_preview_fullscreen))
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.more_options))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.copy)) },
                            leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                clipboard.setText(AnnotatedString(request.html))
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.share)) },
                            leadingIcon = { Icon(Icons.Outlined.Share, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                sharePage(context, document, title)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.html_preview_open_in_browser)) },
                            leadingIcon = { Icon(Icons.Outlined.OpenInBrowser, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                openPageInBrowser(context, document, title)
                            },
                        )
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            if (showSource) {
                SourceView(
                    html = request.html,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = maxInlineHeight.dp),
                )
            } else {
                PreviewWebView(
                    document = document,
                    reloadKey = reloadKey,
                    keepTouches = true,
                    onConsoleError = { if (consoleErrors.size < MAX_ERRORS) consoleErrors += it },
                    onContentHeight = { pageHeightDp = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(inlineHeight.dp),
                )
            }

            if (consoleErrors.isNotEmpty()) {
                ConsoleErrors(consoleErrors)
            }
        }
    }

    if (fullscreen) {
        FullscreenPreview(
            title = title,
            document = document,
            onDismiss = { fullscreen = false },
        )
    }
}

@Composable
private fun FullscreenPreview(
    title: String,
    document: String,
    onDismiss: () -> Unit,
) {
    var reloadKey by remember { mutableIntStateOf(0) }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.close))
                    }
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { reloadKey++ }) {
                        Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.html_preview_reload))
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                PreviewWebView(
                    document = document,
                    reloadKey = reloadKey,
                    keepTouches = false,
                    onConsoleError = {},
                    onContentHeight = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )
            }
        }
    }
}

@SuppressLint("ClickableViewAccessibility")
@Composable
private fun PreviewWebView(
    document: String,
    reloadKey: Int,
    keepTouches: Boolean,
    onConsoleError: (String) -> Unit,
    onContentHeight: ((Int) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val currentDocument by rememberUpdatedState(document)
    val currentOnError by rememberUpdatedState(onConsoleError)
    val currentOnHeight by rememberUpdatedState(onContentHeight)

    key(document) {
        AndroidView(
            modifier = modifier,
            factory = { ctx ->
                WebView(ctx).apply {
                    HtmlPreviewWebView.configure(this)
                    webChromeClient = PreviewChromeClient { currentOnError(it) }
                    webViewClient = object : HtmlPreviewWebViewClient(
                        document = { currentDocument },
                        onExternalLink = { uri -> openExternal(ctx, uri) },
                    ) {
                        override fun onPageFinished(view: WebView, url: String?) {
                            super.onPageFinished(view, url)
                            if (currentOnHeight == null) return
                            measureContentHeight(view)
                            // Again once fonts, images and load handlers settle.
                            view.postDelayed({ measureContentHeight(view) }, 800)
                        }

                        private fun measureContentHeight(view: WebView) {
                            view.evaluateJavascript(CONTENT_HEIGHT_JS) { raw ->
                                val height = raw?.trim('"')?.toDoubleOrNull()?.toInt() ?: return@evaluateJavascript
                                if (height > 0) currentOnHeight?.invoke(height)
                            }
                        }
                    }
                    if (keepTouches) {
                        // Inside the chat list, let the page own its gestures
                        // (canvas games, sliders, inner scroll) instead of the
                        // list stealing vertical drags.
                        setOnTouchListener { v, event ->
                            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                                v.parent?.requestDisallowInterceptTouchEvent(true)
                            }
                            false
                        }
                    }
                    tag = reloadKey
                    HtmlPreviewWebView.load(this)
                }
            },
            update = { webView ->
                if (webView.tag != reloadKey) {
                    webView.tag = reloadKey
                    HtmlPreviewWebView.load(webView)
                }
            },
            onRelease = { webView ->
                webView.stopLoading()
                webView.destroy()
            },
        )
    }
}

/** Surfaces page errors to the card and gives alert/confirm/prompt real dialogs. */
private class PreviewChromeClient(
    private val onConsoleError: (String) -> Unit,
) : WebChromeClient() {

    override fun onConsoleMessage(message: ConsoleMessage): Boolean {
        if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
            val line = message.lineNumber()
            onConsoleError(message.message().take(300) + if (line > 0) " (line $line)" else "")
        }
        return true
    }

    override fun onJsAlert(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
        AlertDialog.Builder(view.context)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok) { _, _ -> result.confirm() }
            .setOnCancelListener { result.confirm() }
            .show()
        return true
    }

    override fun onJsConfirm(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
        AlertDialog.Builder(view.context)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok) { _, _ -> result.confirm() }
            .setNegativeButton(android.R.string.cancel) { _, _ -> result.cancel() }
            .setOnCancelListener { result.cancel() }
            .show()
        return true
    }

    override fun onJsPrompt(
        view: WebView,
        url: String?,
        message: String?,
        defaultValue: String?,
        result: JsPromptResult,
    ): Boolean {
        val input = EditText(view.context).apply { setText(defaultValue.orEmpty()) }
        AlertDialog.Builder(view.context)
            .setMessage(message)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ -> result.confirm(input.text.toString()) }
            .setNegativeButton(android.R.string.cancel) { _, _ -> result.cancel() }
            .setOnCancelListener { result.cancel() }
            .show()
        return true
    }
}

@Composable
private fun SourceView(html: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .horizontalScroll(rememberScrollState())
            .padding(12.dp),
    ) {
        SelectionContainer {
            Text(
                text = html,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                softWrap = false,
            )
        }
    }
}

@Composable
private fun ConsoleErrors(errors: List<String>) {
    var expanded by remember { mutableStateOf(false) }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.ErrorOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = stringResource(R.string.html_preview_console_errors, errors.size),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
    AnimatedVisibility(visible = expanded) {
        SelectionContainer {
            Text(
                text = errors.joinToString("\n"),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
            )
        }
    }
}

private fun openExternal(context: Context, uri: Uri) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, uri.toString(), Toast.LENGTH_SHORT).show()
    }
}

/** Writes the page to cache so other apps can receive it as a real .html file. */
private fun writePageFile(context: Context, document: String, title: String): Uri {
    val dir = File(context.cacheDir, "html_preview").apply { mkdirs() }
    val safeName = title.replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_').take(40).ifEmpty { "page" }
    val file = File(dir, "$safeName.html")
    file.writeText(document, Charsets.UTF_8)
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}

private fun sharePage(context: Context, document: String, title: String) {
    val uri = writePageFile(context, document, title)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/html"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, title)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, title))
}

private fun openPageInBrowser(context: Context, document: String, title: String) {
    val uri = writePageFile(context, document, title)
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "text/html")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, R.string.html_preview_no_browser, Toast.LENGTH_SHORT).show()
    }
}

private const val DEFAULT_HEIGHT_DP = 360
private const val MIN_HEIGHT_DP = 140
private const val MAX_HEIGHT_DP = 560
private const val MAX_ERRORS = 20

/**
 * Height of what the page actually draws, in CSS px (== dp, since every
 * preview has a device-width viewport). documentElement.scrollHeight can't be
 * used: it never reports less than the WebView's current height, so the card
 * could grow but never shrink to fit a small widget. Fixed/fullscreen layouts
 * report the current height, which keeps them stable.
 */
private const val CONTENT_HEIGHT_JS = """(function(){
  var b=document.body; if(!b) return document.documentElement.scrollHeight;
  var m=0, els=b.getElementsByTagName('*'), n=Math.min(els.length,3000);
  for(var i=0;i<n;i++){var r=els[i].getBoundingClientRect(); if(r.width>0&&r.height>0&&r.bottom>m) m=r.bottom;}
  if(m===0) return b.scrollHeight;
  return Math.ceil(m + window.scrollY + (parseFloat(getComputedStyle(b).marginBottom)||0));
})()"""
