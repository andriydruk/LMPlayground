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

        /** Id of the conversation this one was branched from. */
        const val KEY_BRANCHED_FROM = "branchedFrom"

        /**
         * The original (non-branch) conversation a branch descends from, its
         * title at the time, and this branch's number among its branches.
         * Branches of branches share the original, so titles read
         * "Title (branch)", "Title (branch 2)", … instead of nesting.
         */
        const val KEY_BRANCH_ROOT = "branchRoot"
        const val KEY_BRANCH_ROOT_TITLE = "branchRootTitle"
        const val KEY_BRANCH_NUMBER = "branchNumber"

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
