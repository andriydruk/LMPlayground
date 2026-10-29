package com.druk.llamacpp.tools

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentToolTest {

    private val state = AgentState()
    private val tool = AgentTool(state)

    private fun call(json: String) = JSONObject(tool.execute(json))

    private fun plan(call: JSONObject): List<String> =
        call.getJSONArray("plan").let { arr -> (0 until arr.length()).map { arr.getString(it) } }

    @Test
    fun schemaIsFlatValidJsonWithActionRequired() {
        val schema = JSONObject(tool.parametersSchema)
        assertEquals("object", schema.getString("type"))
        assertEquals("action", schema.getJSONArray("required").getString(0))
        val props = schema.getJSONObject("properties")
        for (key in props.keys()) {
            // Flat on purpose: no nested arrays/objects for small models to get wrong.
            val type = props.getJSONObject(key).getString("type")
            assertTrue("$key has type $type", type == "string" || type == "integer")
        }
    }

    @Test
    fun planStartsFirstStepAndPointsAtIt() {
        val r = call("""{"action":"plan","steps":"Search for X\nRead the best result\nSummarize"}""")
        assertTrue(r.getBoolean("ok"))
        assertEquals(
            listOf("1. [in_progress] Search for X", "2. [pending] Read the best result", "3. [pending] Summarize"),
            plan(r),
        )
        assertTrue(r.getString("next").contains("step 1"))
        assertTrue(r.getString("next").contains("action=update"))
    }

    @Test
    fun planAcceptsJsonArrayNumberedAndBulletedLists() {
        val fromArray = call("""{"action":"plan","steps":["a","b"]}""")
        assertEquals(listOf("1. [in_progress] a", "2. [pending] b"), plan(fromArray))

        val numbered = call("""{"action":"plan","steps":"1. a\n2) b\n- c\n* d"}""")
        assertEquals(4, plan(numbered).size)
        assertEquals("1. [in_progress] a", plan(numbered)[0])
        assertEquals("4. [pending] d", plan(numbered)[3])

        val oneLine = call("""{"action":"plan","steps":"1. look up a 2. read b 3. answer"}""")
        assertEquals(listOf("1. [in_progress] look up a", "2. [pending] read b", "3. [pending] answer"), plan(oneLine))

        val arrayInString = call("""{"action":"plan","steps":"[\"a\",\"b\"]"}""")
        assertEquals(2, plan(arrayInString).size)
    }

    @Test
    fun numberInsideAStepIsNotMistakenForAListMarker() {
        val r = call("""{"action":"plan","steps":"Find the top 10. Then compare them"}""")
        assertEquals(listOf("1. [in_progress] Find the top 10. Then compare them"), plan(r))
    }

    @Test
    fun planWithoutStepsIsAnError() {
        assertNotNull(call("""{"action":"plan"}""").optString("error").takeIf { it.isNotEmpty() })
        assertNotNull(call("""{"action":"plan","steps":" \n "}""").optString("error").takeIf { it.isNotEmpty() })
    }

    @Test
    fun planIsCappedAndWarns() {
        val many = (1..20).joinToString("\\n") { "step $it" }
        val r = call("""{"action":"plan","steps":"$many"}""")
        assertEquals(AgentState.MAX_STEPS, plan(r).size)
        assertTrue(r.has("warning"))
    }

    @Test
    fun updatingAdvancesToNextStepAndEventuallyAsksToFinish() {
        call("""{"action":"plan","steps":"a\nb"}""")
        val first = call("""{"action":"update","step":1,"status":"done"}""")
        assertEquals(listOf("1. [done] a", "2. [in_progress] b"), plan(first))
        assertTrue(first.getString("next").contains("step 2"))

        val second = call("""{"action":"update","step":2,"status":"done"}""")
        assertEquals(listOf("1. [done] a", "2. [done] b"), plan(second))
        assertTrue(second.getString("next").contains("action=finish"))
    }

    @Test
    fun onlyOneStepIsInProgress() {
        call("""{"action":"plan","steps":"a\nb\nc"}""")
        val r = call("""{"action":"update","step":3,"status":"in_progress"}""")
        assertEquals(listOf("1. [pending] a", "2. [pending] b", "3. [in_progress] c"), plan(r))
    }

    @Test
    fun updateIsLenientAboutStepAndStatusSpelling() {
        call("""{"action":"plan","steps":"a\nb"}""")
        val r = call("""{"action":"update","step":"step 1","status":"Completed"}""")
        assertEquals("1. [done] a", plan(r)[0])
        // No status at all means "I finished it".
        val r2 = call("""{"action":"update","step":2}""")
        assertEquals("2. [done] b", plan(r2)[1])
    }

    @Test
    fun updateErrorsAreSpecific() {
        assertTrue(call("""{"action":"update","step":1,"status":"done"}""").getString("error").contains("no plan"))
        call("""{"action":"plan","steps":"a\nb"}""")
        assertTrue(call("""{"action":"update","step":5,"status":"done"}""").getString("error").contains("2 steps"))
        assertTrue(call("""{"action":"update","status":"done"}""").getString("error").contains("step"))
        assertTrue(call("""{"action":"update","step":1,"status":"banana"}""").getString("error").contains("banana"))
    }

    @Test
    fun notesAreSavedDedupedAndEchoed() {
        val r = call("""{"action":"note","text":"Kyiv is 12C"}""")
        assertEquals("Kyiv is 12C", r.getJSONArray("notes").getString(0))
        val again = call("""{"action":"note","text":"Kyiv is 12C"}""")
        assertEquals(1, again.getJSONArray("notes").length())
        assertTrue(again.has("info"))
        assertTrue(call("""{"action":"note"}""").has("error"))
    }

    @Test
    fun finishTellsModelToAnswerAndStopsCallingTools() {
        call("""{"action":"plan","steps":"a"}""")
        val r = call("""{"action":"finish","text":"all done"}""")
        assertTrue(r.getString("next").contains("final answer"))
        assertTrue(r.getString("next").contains("do not call more tools"))
        assertTrue(state.view().finished)
        assertEquals("all done", state.view().summary)
    }

    @Test
    fun missingActionIsInferredFromOtherFields() {
        assertEquals(1, plan(call("""{"steps":"a"}""")).size)
        assertEquals("1. [done] a", plan(call("""{"step":1,"status":"done"}"""))[0])
        assertEquals(1, call("""{"text":"fact"}""").getJSONArray("notes").length())
        assertTrue(call("""{}""").has("error"))
    }

    @Test
    fun actionSynonymsAndUnknownActions() {
        assertEquals(1, plan(call("""{"action":"Create","steps":"a"}""")).size)
        assertTrue(call("""{"action":"show"}""").has("plan"))
        assertTrue(call("""{"action":"dance"}""").getString("error").contains("dance"))
    }

    @Test
    fun statusWithoutPlanSaysSo() {
        val r = call("""{"action":"status"}""")
        assertFalse(r.has("plan"))
        assertTrue(r.getString("next").contains("no plan"))
    }

    @Test
    fun nonJsonArgumentsAreAnErrorNotAnException() {
        assertTrue(JSONObject(tool.execute("not json")).has("error"))
    }

    // ── AgentState ──────────────────────────────────────────────────────────

    @Test
    fun replanKeepsNotes() {
        call("""{"action":"plan","steps":"a"}""")
        call("""{"action":"note","text":"keep me"}""")
        call("""{"action":"plan","steps":"x\ny"}""")
        val v = state.view()
        assertEquals(2, v.steps.size)
        assertEquals(listOf("keep me"), v.notes)
    }

    @Test
    fun notesAreBounded() {
        for (i in 1..(AgentState.MAX_NOTES + 5)) state.addNote("n$i")
        val notes = state.view().notes
        assertEquals(AgentState.MAX_NOTES, notes.size)
        assertEquals("n6", notes.first())
    }

    @Test
    fun finishedWorkflowClearsOnNextTurnButUnfinishedSurvives() {
        call("""{"action":"plan","steps":"a\nb"}""")
        state.onTurnStart()
        assertEquals(2, state.view().steps.size) // unfinished: kept for the next message

        call("""{"action":"finish"}""")
        state.onTurnStart()
        assertTrue(state.view().isEmpty)
    }

    @Test
    fun jsonRoundTrip() {
        call("""{"action":"plan","steps":"a\nb\nc"}""")
        call("""{"action":"update","step":1,"status":"done"}""")
        call("""{"action":"note","text":"fact"}""")
        val json = state.toJson()
        assertNotNull(json)

        val restored = AgentState().apply { restore(json) }
        assertEquals(state.view(), restored.view())
    }

    @Test
    fun emptyStateHasNoJsonAndBadJsonRestoresEmpty() {
        assertNull(AgentState().toJson())
        val s = AgentState()
        s.addNote("x")
        s.restore("{not json")
        assertTrue(s.view().isEmpty)
        s.restore(null)
        assertTrue(s.view().isEmpty)
    }
}
