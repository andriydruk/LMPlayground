package com.druk.llamacpp.tools

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Best-effort repair of a tool call's `arguments` string.
 *
 * Small on-device models regularly emit arguments that are *almost* JSON:
 * wrapped in a markdown fence, surrounded by prose, cut off before the closing
 * brace, double-encoded as a JSON string, nested under a redundant
 * `{"arguments": {...}}`, or a bare value with no object at all. Instead of
 * failing the call (which costs a whole extra model round, if the model
 * recovers at all), the registry runs the string through [repair] first.
 *
 * Repair never invents data: it only reshapes what the model wrote. Anything it
 * can't make sense of returns null and the caller reports a precise error the
 * model can correct.
 */
object ToolArgumentRepair {

    /** Keys models use to wrap the real arguments object. */
    private val WRAPPER_KEYS = listOf("arguments", "parameters", "params", "input", "args")

    private const val MAX_DEPTH = 3

    /**
     * @param parametersSchema the tool's JSON-schema string; used to keep a
     *        legitimate parameter that happens to share a wrapper name, and to
     *        wrap a bare value when the tool has exactly one required string.
     * @return a JSON object string, or null if the arguments are unrecoverable.
     */
    fun repair(raw: String, parametersSchema: String? = null): String? =
        repair(raw, parametersSchema, 0)

    private fun repair(raw: String, schema: String?, depth: Int): String? {
        if (depth > MAX_DEPTH) return null
        var text = stripFence(raw.trim())
        if (text.isEmpty()) return "{}"

        parseAny(text)?.let { parsed -> normalize(parsed, text, schema, depth)?.let { return it } }

        // Prose around the object: keep first '{' .. last '}'.
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start >= 0 && end > start) {
            val slice = text.substring(start, end + 1)
            parseAny(slice)?.let { parsed -> normalize(parsed, slice, schema, depth)?.let { return it } }
        }

        // Truncated object: close whatever the model left open.
        if (start >= 0) {
            val closed = closeUnterminated(text.substring(start))
            parseAny(closed)?.let { parsed -> normalize(parsed, closed, schema, depth)?.let { return it } }
        }

        // No object anywhere: a bare value for a single-parameter tool.
        return wrapBareValue(text, schema)
    }

    /** Parse [text] as an object, an array, or a (possibly double-encoded) JSON string. */
    private fun parseAny(text: String): Any? = try {
        when (text.firstOrNull()) {
            '{' -> JSONObject(text)
            '[' -> JSONArray(text)
            '"' -> JSONTokener(text).nextValue().takeIf { it is String }
            else -> null
        }
    } catch (_: Exception) {
        null
    }

    /**
     * [source] is the exact text that parsed to [parsed]. An object that needs no
     * reshaping is returned as [source] itself, so well-formed calls reach the
     * tool byte-for-byte as the model wrote them.
     */
    private fun normalize(parsed: Any, source: String, schema: String?, depth: Int): String? = when (parsed) {
        is JSONObject -> unwrap(parsed, schema)?.toString() ?: source
        // `[{"query": "x"}]` — a one-element call list instead of an object.
        is JSONArray ->
            if (parsed.length() == 1) parsed.optJSONObject(0)?.let { unwrap(it, schema)?.toString() ?: it.toString() }
            else null
        // `"{\"query\": \"x\"}"` — the object JSON-encoded a second time.
        is String -> repair(parsed, schema, depth + 1)
        else -> null
    }

    /**
     * `{"arguments": {...}}` -> `{...}` when the wrapper is the only key and the
     * tool has no parameter of that name. Wrapper values may themselves be
     * JSON-encoded strings.
     */
    private fun unwrap(obj: JSONObject, schema: String?): JSONObject? {
        if (obj.length() != 1) return null
        val key = obj.keys().next()
        if (key !in WRAPPER_KEYS) return null
        if (key in declaredProperties(schema)) return null
        return when (val inner = obj.opt(key)) {
            is JSONObject -> inner
            is String -> try { JSONObject(inner) } catch (_: Exception) { null }
            else -> null
        }
    }

    private fun wrapBareValue(text: String, schema: String?): String? {
        // Broken JSON is not a bare value; wrapping it would send garbage as the argument.
        if (text.startsWith("{") || text.startsWith("[")) return null
        val only = singleRequiredStringParam(schema) ?: return null
        val value = if (text.length >= 2 && text.first() == '"' && text.last() == '"') {
            text.substring(1, text.length - 1)
        } else {
            text
        }
        return JSONObject().put(only, value).toString()
    }

    private fun declaredProperties(schema: String?): Set<String> = try {
        val props = JSONObject(schema ?: return emptySet()).optJSONObject("properties")
        props?.keys()?.asSequence()?.toSet() ?: emptySet()
    } catch (_: Exception) {
        emptySet()
    }

    private fun singleRequiredStringParam(schema: String?): String? = try {
        val obj = JSONObject(schema ?: return null)
        val required = obj.optJSONArray("required")
        if (required != null && required.length() == 1) {
            val name = required.getString(0)
            val type = obj.optJSONObject("properties")?.optJSONObject(name)?.optString("type")
            name.takeIf { type == "string" }
        } else {
            null
        }
    } catch (_: Exception) {
        null
    }

    /** ```json ... ``` (or a bare ``` fence) -> the contents. */
    private fun stripFence(text: String): String {
        if (!text.startsWith("```")) return text
        val afterOpen = text.substringAfter('\n', missingDelimiterValue = "")
        return afterOpen.substringBeforeLast("```").trim()
    }

    /**
     * Append the quotes/brackets a cut-off object is missing. Tracks string and
     * escape state so braces inside string values don't confuse the nesting.
     */
    private fun closeUnterminated(s: String): String {
        val stack = ArrayDeque<Char>()
        var inString = false
        var escaped = false
        for (c in s) {
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
            } else {
                when (c) {
                    '"' -> inString = true
                    '{' -> stack.addLast('}')
                    '[' -> stack.addLast(']')
                    '}', ']' -> if (stack.isNotEmpty() && stack.last() == c) stack.removeLast()
                }
            }
        }
        val sb = StringBuilder(s)
        if (inString) {
            if (escaped) sb.setLength(sb.length - 1) // dangling backslash
            sb.append('"')
        } else {
            // `{"a": 1,` -> drop the dangling comma before closing.
            while (sb.isNotEmpty() && (sb.last() == ',' || sb.last().isWhitespace())) sb.setLength(sb.length - 1)
        }
        while (stack.isNotEmpty()) sb.append(stack.removeLast())
        return sb.toString()
    }
}
