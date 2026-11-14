package com.druk.lmplayground.tools

import android.content.Context
import com.druk.llamacpp.tools.ToolRegistry

/**
 * Builds the registry the app ships with.
 *
 * [ToolRegistry] itself lives in :llamacpp so the harness and any non-Android
 * caller can use the real registry (its OpenAI tool JSON and tool-result
 * shapes are part of what we test). The concrete tools can't follow it there:
 * they need a Context, okhttp and the androidx JavaScript engine.
 */
fun ToolRegistry.Companion.createDefault(context: Context): ToolRegistry =
    ToolRegistry().apply {
        // Shared link store: web_search hands the model compact "ddg:N"
        // references instead of long URLs, web_fetch resolves them back at
        // call time. See WebLinkStore.
        register(WebSearchTool(webLinkStore))
        register(WebFetchTool(webLinkStore))
        register(JavaScriptTool(context))
    }
