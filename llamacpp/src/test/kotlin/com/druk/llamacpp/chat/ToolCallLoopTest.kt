package com.druk.llamacpp.chat

import com.druk.llamacpp.GenerationSession
import com.druk.llamacpp.LlamaGenerationCallback
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCallLoopTest {

    /** A model that asks for a tool [toolRounds] times, then (if allowed) answers. */
    private class FakeSession(
        private val toolRounds: Int,
        /** If true the model keeps calling tools even after being told the budget is spent. */
        private val stubborn: Boolean = false,
    ) : GenerationSession {
        var generations = 0
        val submitted = mutableListOf<String>()

        override fun addMessage(message: String, enableThinking: Boolean) {}
        override fun setImageData(imageData: ByteArray) {}
        override fun setTools(toolsJson: String) {}
        override suspend fun generateAll(callback: LlamaGenerationCallback): Int {
            generations++
            val wantsTool = generations <= toolRounds || (stubborn && generations > toolRounds)
            return if (wantsTool) ToolCallLoop.RC_TOOL_CALLS else 0
        }
        override fun getToolCallsJson(): String =
            """[{"id":"call$generations","name":"web_search","arguments":"{\"query\":\"q$generations\"}"}]"""
        override fun submitToolResults(resultsJson: String, enableThinking: Boolean) {
            submitted += resultsJson
        }
        override fun rewindToTurn(turnIndex: Int, expectedText: String): Int = -1
        override fun requestAbort() {}
        override fun destroy() {}
    }

    private class CountingTools : ToolCallLoop.ToolExecutor {
        var executed = 0
        override fun executeToolCalls(toolCallsJson: String): String {
            val calls = JSONArray(toolCallsJson)
            executed += calls.length()
            val out = JSONArray()
            for (i in 0 until calls.length()) {
                out.put(JSONObject().put("id", calls.getJSONObject(i).getString("id")).put("name", "web_search").put("content", "{}"))
            }
            return out.toString()
        }
        override fun cancelInFlight() {}
    }

    private val callback = object : LlamaGenerationCallback {
        override fun onFullResponse(response: String) {}
    }

    private fun run(loop: ToolCallLoop) = runBlocking {
        loop.run(callback, supportsThinking = false, enableThinking = false)
    }

    @Test
    fun finishesNormallyWithinBudget() {
        val session = FakeSession(toolRounds = 2)
        val tools = CountingTools()
        val result = run(ToolCallLoop(session, tools, maxToolRounds = 5))
        assertEquals(0, result.rc)
        assertEquals(2, result.toolRounds)
        assertFalse(result.hitRoundLimit)
        assertEquals(2, tools.executed)
    }

    @Test
    fun withoutWrapUpTheLoopJustStopsAtTheLimit() {
        val session = FakeSession(toolRounds = 10)
        val tools = CountingTools()
        val result = run(ToolCallLoop(session, tools, maxToolRounds = 3))
        assertTrue(result.hitRoundLimit)
        assertEquals(ToolCallLoop.RC_TOOL_CALLS, result.rc)
        assertEquals(3, tools.executed)
        assertEquals(3, session.submitted.size)
    }

    @Test
    fun wrapUpRefusesPendingCallsAndLetsTheModelAnswer() {
        val session = FakeSession(toolRounds = 4)
        val tools = CountingTools()
        val result = run(ToolCallLoop(session, tools, maxToolRounds = 3, wrapUpOnLimit = true))
        // 3 real rounds, then the 4th tool request is refused and the model answers.
        assertEquals(0, result.rc)
        assertTrue(result.hitRoundLimit)
        assertEquals(3, tools.executed) // the refused call was NOT executed
        assertEquals(4, session.submitted.size)
        val refusal = JSONArray(session.submitted.last()).getJSONObject(0)
        assertEquals("call4", refusal.getString("id"))
        assertTrue(JSONObject(refusal.getString("content")).getString("error").contains("limit reached"))
        assertEquals(5, session.generations) // 4 tool requests + the final answer
    }

    @Test
    fun wrapUpHappensOnlyOnce() {
        val session = FakeSession(toolRounds = 0, stubborn = true)
        val tools = CountingTools()
        val result = run(ToolCallLoop(session, tools, maxToolRounds = 2, wrapUpOnLimit = true))
        assertTrue(result.hitRoundLimit)
        assertEquals(ToolCallLoop.RC_TOOL_CALLS, result.rc) // gave up after one wrap-up attempt
        assertEquals(2, tools.executed)
        assertEquals(3, session.submitted.size) // 2 real + 1 refusal
    }
}
