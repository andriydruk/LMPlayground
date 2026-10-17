package com.druk.llamacpp.chat

import org.json.JSONArray

/**
 * Pairs the native layer's tool-call JSON with its tool-result JSON.
 *
 * Matched on the call `id` rather than by position: the results array is built
 * by executing the calls, and nothing guarantees the engine's ordering
 * survives that round trip.
 */
object ToolCallPairing {

    fun pair(
        toolCallsJson: String,
        toolResultsJson: String,
        totalDurationMs: Long,
    ): List<ToolCallRecord> {
        val calls = JSONArray(toolCallsJson)
        val results = JSONArray(toolResultsJson)
        val resultMap = mutableMapOf<String, String>()
        for (i in 0 until results.length()) {
            val r = results.getJSONObject(i)
            resultMap[r.getString("id")] = r.getString("content")
        }
        // Native reports one duration for the whole round; split it evenly so
        // parallel calls in a round don't each claim the full elapsed time.
        val count = calls.length().coerceAtLeast(1)
        val perCallMs = totalDurationMs / count
        return (0 until calls.length()).map { i ->
            val call = calls.getJSONObject(i)
            val id = call.getString("id")
            ToolCallRecord(
                id = id,
                name = call.getString("name"),
                arguments = call.getString("arguments"),
                result = resultMap[id] ?: "",
                durationMs = perCallMs,
            )
        }
    }
}
