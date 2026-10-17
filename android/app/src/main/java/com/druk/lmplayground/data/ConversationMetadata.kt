package com.druk.lmplayground.data

import org.json.JSONObject

/**
 * Generic per-conversation metadata: a small string-keyed store persisted as a
 * JSON object in [ChatSessionEntity.metadata]. Lets features attach data to a
 * conversation (first use: the web_search link map) without adding a column per
 * feature. Parse from the stored string, read/modify, then write [toJson] back.
 */
class ConversationMetadata private constructor(private val obj: JSONObject) {

    fun getString(key: String): String? =
        if (obj.has(key) && !obj.isNull(key)) obj.optString(key) else null

    fun putString(key: String, value: String?): ConversationMetadata {
        if (value == null) obj.remove(key) else obj.put(key, value)
        return this
    }

    /** Read a nested string -> string map stored under [key] (empty if absent). */
    fun getStringMap(key: String): Map<String, String> {
        val nested = obj.optJSONObject(key) ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        val keys = nested.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            out[k] = nested.optString(k)
        }
        return out
    }

    /** Store a nested string -> string map under [key] (removes the key when empty). */
    fun putStringMap(key: String, map: Map<String, String>): ConversationMetadata {
        if (map.isEmpty()) {
            obj.remove(key)
        } else {
            val nested = JSONObject()
            for ((k, v) in map) nested.put(k, v)
            obj.put(key, nested)
        }
        return this
    }

    fun toJson(): String = obj.toString()

    companion object {
        /** Key for the web_search reference -> URL map. */
        const val KEY_WEB_LINKS = "webLinks"

        fun parse(json: String?): ConversationMetadata {
            if (json.isNullOrBlank()) return ConversationMetadata(JSONObject())
            return try {
                ConversationMetadata(JSONObject(json))
            } catch (_: Exception) {
                ConversationMetadata(JSONObject())
            }
        }
    }
}
