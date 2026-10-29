package com.druk.llamacpp.tools

import com.druk.llamacpp.chat.ToolCallLoop
import org.json.JSONArray
import org.json.JSONObject
import java.util.Collections

class ToolRegistry {
    private val tools = mutableMapOf<String, Tool>()
    private val enabledState = mutableMapOf<String, Boolean>()

    /**
     * Shared by web_search / web_fetch for compact result references. Exposed so
     * the conversation layer can snapshot it into a session's metadata and
     * restore it when that conversation is reopened. See [WebLinkStore].
     */
    val webLinkStore = WebLinkStore()

    /**
     * The `agent` tool's plan and notes, shared the same way: snapshotted into
     * conversation metadata and restored on reopen. See [AgentState].
     */
    val agentState = AgentState()

    /**
     * Keys (tool + canonical arguments) of calls that already succeeded this
     * turn; only consulted in agent mode. See [beginTurn].
     */
    private val turnCalls: MutableSet<String> = Collections.synchronizedSet(HashSet())

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

    /**
     * Agent mode is on when the `agent` tool is registered and enabled. It
     * widens the turn's tool budget, suppresses repeated identical calls, and
     * makes the loop force a final answer when the budget runs out.
     */
    fun isAgentMode(): Boolean = tools.containsKey(AgentTool.NAME) && isToolEnabled(AgentTool.NAME)

    /** Tool rounds one turn may use: larger in agent mode so multi-step plans can finish. */
    fun toolRoundBudget(): Int =
        if (isAgentMode()) AgentTool.MAX_TOOL_ROUNDS else ToolCallLoop.DEFAULT_MAX_TOOL_ROUNDS

    /**
     * Call at the start of every user turn, before the first generation. Clears
     * the duplicate-call memory (a new message may legitimately repeat a search)
     * and lets a finished agent workflow from the previous request go.
     */
    fun beginTurn() {
        turnCalls.clear()
        agentState.onTurnStart()
    }

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

    /**
     * Runs every call and returns the results array.
     *
     * One bad call never fails the round: an unknown tool, unparseable
     * arguments, a repeated call, or a tool that throws each come back as an
     * `{"error": ...}` result the model can read and correct, instead of an
     * exception that would abort the whole turn. Near-misses are fixed up
     * before they count as failures — see [resolveTool] and
     * [ToolArgumentRepair].
     */
    fun executeToolCalls(toolCallsJson: String): String {
        val calls = JSONArray(toolCallsJson)
        val results = JSONArray()
        for (i in 0 until calls.length()) {
            val call = calls.getJSONObject(i)
            val name = call.getString("name")
            val arguments = call.optString("arguments", "{}")
            val id = call.getString("id")

            val result = JSONObject()
            result.put("id", id)
            result.put("name", name)
            result.put("content", executeOne(name, arguments))
            results.put(result)
        }
        return results.toString()
    }

    private fun executeOne(rawName: String, rawArguments: String): String {
        val tool = resolveTool(rawName) ?: return errorJson(
            enabledToolNames().let { names ->
                if (names.isEmpty()) "Unknown tool: $rawName. No tools are enabled."
                else "Unknown tool: $rawName. Available tools: ${names.joinToString(", ")}."
            }
        )

        val arguments = ToolArgumentRepair.repair(rawArguments, tool.parametersSchema)
            ?: return errorJson(
                "The arguments for ${tool.name} are not valid JSON. " +
                    "Call it again with a JSON object that matches its parameters."
            )

        // Repeat-suppression is for the research tools. The agent tool is a
        // checklist: repeating "status" or an idempotent "update" is harmless
        // and sometimes exactly what the model needs.
        val dedupKey = if (isAgentMode() && tool.name != AgentTool.NAME) {
            tool.name + "\u0000" + canonicalize(arguments)
        } else {
            null
        }
        if (dedupKey != null && dedupKey in turnCalls) {
            return errorJson(
                "Duplicate call: you already made this exact ${tool.name} call this turn and its result is above. " +
                    "Use that result, change the arguments, or give your final answer."
            )
        }

        val content = try {
            tool.execute(arguments)
        } catch (e: Exception) {
            errorJson(e.message ?: "${tool.name} failed")
        }
        // Only remember calls that worked: a failed fetch is worth retrying.
        if (dedupKey != null && !isErrorResult(content)) turnCalls.add(dedupKey)
        return content
    }

    /**
     * Find the tool a call is aimed at. An exact name always wins (and, as
     * before, doesn't require the tool to be enabled). Otherwise tolerate the
     * slips small models make — case, `-`/space for `_`, a `functions.`
     * prefix, `webfetch` — and a few common aliases, but only among *enabled*
     * tools so a near-miss can't reach a tool the user switched off.
     */
    private fun resolveTool(rawName: String): Tool? {
        tools[rawName]?.let { return it }

        var key = rawName.trim().lowercase()
        for (prefix in NAME_PREFIXES) key = key.removePrefix(prefix)
        key = key.replace('-', '_').replace(' ', '_')

        val enabled = tools.values.filter { isToolEnabled(it.name) }
        enabled.firstOrNull { it.name == key }?.let { return it }

        val compact = key.filter { it.isLetterOrDigit() }
        enabled.firstOrNull { it.name.filter { c -> c.isLetterOrDigit() } == compact }?.let { return it }

        val aliased = NAME_ALIASES[key] ?: NAME_ALIASES[compact]
        return aliased?.let { name -> enabled.firstOrNull { it.name == name } }
    }

    private fun enabledToolNames(): List<String> = tools.values.filter { isToolEnabled(it.name) }.map { it.name }

    private fun isErrorResult(content: String): Boolean = content.trimStart().startsWith("{\"error\"")

    /** Key order and whitespace don't make two calls different. */
    private fun canonicalize(json: String): String = try {
        canonical(JSONObject(json))
    } catch (_: Exception) {
        json
    }

    private fun canonical(value: Any?): String = when (value) {
        is JSONObject -> value.keys().asSequence().toList().sorted()
            .joinToString(",", "{", "}") { k -> JSONObject.quote(k) + ":" + canonical(value.opt(k)) }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.opt(it)) }
        is String -> JSONObject.quote(value)
        else -> value.toString()
    }

    private fun errorJson(message: String): String = JSONObject().put("error", message).toString()

    /**
     * Exists so :app can hang [createDefault] off it as an extension: the
     * concrete tools need a Context / okhttp / the JS engine and stay there,
     * while the registry itself must be usable from a plain JVM.
     */
    companion object {
        private val NAME_PREFIXES = listOf("functions.", "function.", "tools.", "tool.", "default_api.")

        private val NAME_ALIASES = mapOf(
            "search" to "web_search",
            "fetch" to "web_fetch",
            "browse" to "web_fetch",
            "javascript" to "run_javascript",
            "js" to "run_javascript",
            "runjs" to "run_javascript",
        )
    }
}
