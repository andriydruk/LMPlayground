package com.druk.llamacpp.tools

import com.druk.llamacpp.chat.ToolCallLoop
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolRegistryTest {

    private class Recorder(
        override val name: String,
        private val reply: (String) -> String = { """{"ok":true}""" },
    ) : Tool {
        val calls = mutableListOf<String>()
        override val description = "test tool"
        override val parametersSchema =
            """{"type":"object","properties":{"query":{"type":"string"}},"required":["query"]}"""
        override fun execute(arguments: String): String {
            calls += arguments
            return reply(arguments)
        }
    }

    private fun registry(vararg tools: Tool, agent: Boolean = false): ToolRegistry =
        ToolRegistry().apply {
            tools.forEach { register(it); setToolEnabled(it.name, true) }
            if (agent) {
                register(AgentTool(agentState))
                setToolEnabled(AgentTool.NAME, true)
            }
        }

    private fun run(registry: ToolRegistry, name: String, arguments: String, id: String = "c1"): JSONObject {
        val calls = JSONArray().put(JSONObject().put("id", id).put("name", name).put("arguments", arguments))
        val results = JSONArray(registry.executeToolCalls(calls.toString()))
        assertEquals(1, results.length())
        assertEquals(id, results.getJSONObject(0).getString("id"))
        return JSONObject(results.getJSONObject(0).getString("content"))
    }

    // ── Self-healing ────────────────────────────────────────────────────────

    @Test
    fun exactCallRunsAsBefore() {
        val search = Recorder("web_search")
        val out = run(registry(search), "web_search", """{"query":"cats"}""")
        assertTrue(out.getBoolean("ok"))
        assertEquals(1, search.calls.size)
    }

    @Test
    fun malformedArgumentsAreRepairedBeforeTheToolSeesThem() {
        val search = Recorder("web_search")
        run(registry(search), "web_search", "```json\n{\"query\":\"cats\"\n```")
        assertEquals("cats", JSONObject(search.calls.single()).getString("query"))
    }

    @Test
    fun unrecoverableArgumentsGiveAnActionableError() {
        val search = Recorder("web_search")
        val out = run(registry(search), "web_search", """{"query": """)
        assertTrue(out.getString("error").contains("not valid JSON"))
        assertTrue(search.calls.isEmpty())
    }

    @Test
    fun nearMissToolNamesResolveToEnabledTools() {
        val fetch = Recorder("web_fetch")
        val js = Recorder("run_javascript")
        val reg = registry(fetch, js)
        for (name in listOf("Web_Fetch", "web-fetch", "web fetch", "webfetch", "functions.web_fetch", "fetch")) {
            run(reg, name, """{"query":"x"}""")
        }
        assertEquals(6, fetch.calls.size)
        run(reg, "javascript", """{"query":"1+1"}""")
        assertEquals(1, js.calls.size)
    }

    @Test
    fun nearMissNeverReachesADisabledTool() {
        val fetch = Recorder("web_fetch")
        val reg = registry(fetch)
        reg.setToolEnabled("web_fetch", false)
        val out = run(reg, "webfetch", """{"query":"x"}""")
        assertTrue(out.getString("error").contains("Unknown tool"))
        assertTrue(fetch.calls.isEmpty())
    }

    @Test
    fun unknownToolListsWhatIsAvailable() {
        val out = run(registry(Recorder("web_search"), Recorder("web_fetch")), "teleport", "{}")
        val msg = out.getString("error")
        assertTrue(msg.contains("Unknown tool: teleport"))
        assertTrue(msg.contains("web_search"))
        assertTrue(msg.contains("web_fetch"))
    }

    @Test
    fun unknownToolNameWithQuotesStillProducesValidJson() {
        // The old string-templated error produced invalid JSON here.
        val out = run(registry(Recorder("web_search")), "bad\"name", "{}")
        assertTrue(out.getString("error").contains("bad\"name"))
    }

    @Test
    fun aThrowingToolDoesNotFailTheRound() {
        val boom = Recorder("boom") { throw IllegalStateException("kaput") }
        val ok = Recorder("fine")
        val reg = registry(boom, ok)
        val calls = JSONArray()
            .put(JSONObject().put("id", "1").put("name", "boom").put("arguments", """{"query":"x"}"""))
            .put(JSONObject().put("id", "2").put("name", "fine").put("arguments", """{"query":"x"}"""))
        val results = JSONArray(reg.executeToolCalls(calls.toString()))
        assertEquals(2, results.length())
        assertEquals("kaput", JSONObject(results.getJSONObject(0).getString("content")).getString("error"))
        assertTrue(JSONObject(results.getJSONObject(1).getString("content")).getBoolean("ok"))
    }

    // ── Agent mode ──────────────────────────────────────────────────────────

    @Test
    fun budgetIsLargerOnlyWhenAgentToolIsEnabled() {
        val reg = registry(Recorder("web_search"), agent = true)
        assertTrue(reg.isAgentMode())
        assertEquals(AgentTool.MAX_TOOL_ROUNDS, reg.toolRoundBudget())

        reg.setToolEnabled(AgentTool.NAME, false)
        assertFalse(reg.isAgentMode())
        assertEquals(ToolCallLoop.DEFAULT_MAX_TOOL_ROUNDS, reg.toolRoundBudget())

        // Not registered at all (e.g. the harness registry).
        assertEquals(ToolCallLoop.DEFAULT_MAX_TOOL_ROUNDS, registry(Recorder("web_search")).toolRoundBudget())
    }

    @Test
    fun agentToolIsAdvertisedOnlyWhenEnabled() {
        val reg = registry(Recorder("web_search"), agent = true)
        fun names() = JSONArray(reg.toOpenAIToolsJson()).let { a ->
            (0 until a.length()).map { a.getJSONObject(it).getJSONObject("function").getString("name") }
        }
        assertEquals(listOf("web_search", "agent"), names())
        reg.setToolEnabled(AgentTool.NAME, false)
        assertEquals(listOf("web_search"), names())
    }

    @Test
    fun repeatedIdenticalCallsAreSuppressedInAgentMode() {
        val search = Recorder("web_search")
        val reg = registry(search, agent = true)
        reg.beginTurn()
        run(reg, "web_search", """{"query":"cats","max":5}""")
        // Same call, different key order and whitespace.
        val dup = run(reg, "web_search", """{ "max": 5, "query": "cats" }""")
        assertTrue(dup.getString("error").contains("Duplicate call"))
        assertEquals(1, search.calls.size)

        // A different argument is a different call.
        run(reg, "web_search", """{"query":"dogs","max":5}""")
        assertEquals(2, search.calls.size)
    }

    @Test
    fun duplicatesAreAllowedAgainOnTheNextTurn() {
        val search = Recorder("web_search")
        val reg = registry(search, agent = true)
        reg.beginTurn()
        run(reg, "web_search", """{"query":"cats"}""")
        reg.beginTurn()
        run(reg, "web_search", """{"query":"cats"}""")
        assertEquals(2, search.calls.size)
    }

    @Test
    fun aFailedCallCanBeRetried() {
        var attempt = 0
        val flaky = Recorder("web_fetch") { if (attempt++ == 0) """{"error":"timeout"}""" else """{"content":"ok"}""" }
        val reg = registry(flaky, agent = true)
        reg.beginTurn()
        assertEquals("timeout", run(reg, "web_fetch", """{"query":"u"}""").getString("error"))
        assertEquals("ok", run(reg, "web_fetch", """{"query":"u"}""").getString("content"))
    }

    @Test
    fun noDedupWithoutAgentMode() {
        val search = Recorder("web_search")
        val reg = registry(search)
        reg.beginTurn()
        run(reg, "web_search", """{"query":"cats"}""")
        run(reg, "web_search", """{"query":"cats"}""")
        assertEquals(2, search.calls.size)
    }

    @Test
    fun theAgentToolItselfIsNeverDeduped() {
        val reg = registry(agent = true)
        reg.beginTurn()
        run(reg, "agent", """{"action":"status"}""")
        assertTrue(run(reg, "agent", """{"action":"status"}""").getBoolean("ok"))
    }

    @Test
    fun beginTurnClearsAFinishedWorkflowOnly() {
        val reg = registry(agent = true)
        run(reg, "agent", """{"action":"plan","steps":"a\nb"}""")
        reg.beginTurn()
        assertEquals(2, reg.agentState.view().steps.size)
        run(reg, "agent", """{"action":"finish"}""")
        reg.beginTurn()
        assertTrue(reg.agentState.view().isEmpty)
    }
}
