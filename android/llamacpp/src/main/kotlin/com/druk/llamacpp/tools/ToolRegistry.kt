package com.druk.llamacpp.tools

import org.json.JSONArray
import org.json.JSONObject

class ToolRegistry {
    private val tools = mutableMapOf<String, Tool>()
    private val enabledState = mutableMapOf<String, Boolean>()

    /**
     * Shared by web_search / web_fetch for compact result references. Exposed so
     * the conversation layer can snapshot it into a session's metadata and
     * restore it when that conversation is reopened. See [WebLinkStore].
     */
    val webLinkStore = WebLinkStore()

    fun register(tool: Tool) {
        tools[tool.name] = tool
    }

    fun getAllTools(): List<Tool> = tools.values.toList()

    fun getTool(name: String): Tool? = tools[name]

    fun isToolEnabled(name: String): Boolean = enabledState[name] ?: false

    fun setToolEnabled(name: String, enabled: Boolean) {
        enabledState[name] = enabled
    }

    fun hasEnabledTools(): Boolean = tools.keys.any { isToolEnabled(it) }

    /** Abort in-flight work across all tools (e.g. on Stop during a tool call). */
    fun cancelInFlight() {
        for (tool in tools.values) {
            try { tool.cancelInFlight() } catch (_: Throwable) {}
        }
    }

    /**
     * Returns OpenAI-format JSON for enabled tools only.
     */
    fun toOpenAIToolsJson(): String {
        val arr = JSONArray()
        for (tool in tools.values) {
            if (!isToolEnabled(tool.name)) continue
            val obj = JSONObject()
            obj.put("type", "function")
            val func = JSONObject()
            func.put("name", tool.name)
            func.put("description", tool.description)
            func.put("parameters", JSONObject(tool.parametersSchema))
            obj.put("function", func)
            arr.put(obj)
        }
        return arr.toString()
    }

    fun executeToolCalls(toolCallsJson: String): String {
        val calls = JSONArray(toolCallsJson)
        val results = JSONArray()
        for (i in 0 until calls.length()) {
            val call = calls.getJSONObject(i)
            val name = call.getString("name")
            val arguments = call.getString("arguments")
            val id = call.getString("id")

            val tool = getTool(name)
            val content = tool?.execute(arguments) ?: """{"error":"Unknown tool: $name"}"""

            val result = JSONObject()
            result.put("id", id)
            result.put("name", name)
            result.put("content", content)
            results.put(result)
        }
        return results.toString()
    }

    /**
     * Exists so :app can hang [createDefault] off it as an extension: the
     * concrete tools need a Context / okhttp / the JS engine and stay there,
     * while the registry itself must be usable from a plain JVM.
     */
    companion object
}
