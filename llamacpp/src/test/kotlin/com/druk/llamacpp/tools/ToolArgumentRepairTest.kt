package com.druk.llamacpp.tools

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ToolArgumentRepairTest {

    private val searchSchema =
        """{"type":"object","properties":{"query":{"type":"string"},"max_results":{"type":"integer"}},"required":["query"]}"""

    private fun repaired(raw: String, schema: String? = searchSchema): JSONObject {
        val out = ToolArgumentRepair.repair(raw, schema)
        assertNotNull("expected '$raw' to be repairable", out)
        return JSONObject(out!!)
    }

    @Test
    fun validJsonPassesThrough() {
        assertEquals("cats", repaired("""{"query":"cats"}""").getString("query"))
    }

    @Test
    fun wellFormedArgumentsAreReturnedByteForByte() {
        val raw = """{ "code" : "a/b \u00e9\n", "n":1.0 }"""
        assertEquals(raw, ToolArgumentRepair.repair(raw, null))
    }

    @Test
    fun blankMeansNoArguments() {
        assertEquals("{}", ToolArgumentRepair.repair("  ", searchSchema))
    }

    @Test
    fun stripsMarkdownFences() {
        assertEquals("cats", repaired("```json\n{\"query\":\"cats\"}\n```").getString("query"))
        assertEquals("cats", repaired("```\n{\"query\":\"cats\"}\n```").getString("query"))
    }

    @Test
    fun ignoresProseAroundTheObject() {
        assertEquals("cats", repaired("""Sure! {"query":"cats"} Hope that helps.""").getString("query"))
    }

    @Test
    fun closesTruncatedObjects() {
        assertEquals("cats", repaired("""{"query":"cats"""").getString("query"))
        assertEquals("cats", repaired("""{"query":"cats""").getString("query"))
        assertEquals("cats", repaired("""{"query":"cats",""").getString("query"))
        assertEquals(3, repaired("""{"query":"cats","max_results":3""").getInt("max_results"))
    }

    @Test
    fun braceInsideStringDoesNotConfuseNesting() {
        assertEquals("a } b", repaired("""{"query":"a } b"""").getString("query"))
    }

    @Test
    fun unwrapsDoubleEncodedString() {
        assertEquals("cats", repaired("\"{\\\"query\\\":\\\"cats\\\"}\"").getString("query"))
    }

    @Test
    fun unwrapsRedundantArgumentsWrapper() {
        assertEquals("cats", repaired("""{"arguments":{"query":"cats"}}""").getString("query"))
        assertEquals("cats", repaired("""{"parameters":"{\"query\":\"cats\"}"}""").getString("query"))
    }

    @Test
    fun keepsAWrapperNamedParameterThatTheToolActuallyDeclares() {
        val schema = """{"type":"object","properties":{"input":{"type":"object"}},"required":["input"]}"""
        val out = repaired("""{"input":{"a":1}}""", schema)
        assertEquals(1, out.getJSONObject("input").getInt("a"))
    }

    @Test
    fun unwrapsSingleElementArray() {
        assertEquals("cats", repaired("""[{"query":"cats"}]""").getString("query"))
    }

    @Test
    fun wrapsBareValueForSingleRequiredStringParameter() {
        assertEquals("cats", repaired("cats").getString("query"))
        assertEquals("cats", repaired("\"cats\"").getString("query"))
    }

    @Test
    fun doesNotWrapWhenAmbiguous() {
        val two = """{"type":"object","properties":{"a":{"type":"string"},"b":{"type":"string"}},"required":["a","b"]}"""
        assertNull(ToolArgumentRepair.repair("cats", two))
        assertNull(ToolArgumentRepair.repair("cats", null))
    }

    @Test
    fun brokenJsonIsNotSentAsAValue() {
        assertNull(ToolArgumentRepair.repair("""{"query": """, searchSchema))
    }
}
