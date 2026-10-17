package com.druk.llamacpp.tools

/**
 * Shared between [WebSearchTool] and [WebFetchTool].
 *
 * Web search results carry long real URLs (article slugs + tracking params)
 * that waste many tokens in the model's context — multiplied across 5–10
 * results per search. Instead of handing the model those URLs, web_search
 * stores each one here and returns a compact reference like "ddg:3". When the
 * model later calls web_fetch with that reference, [resolve] maps it back to
 * the real URL before the request goes out.
 *
 * One instance is shared by the two tools within a [ToolRegistry] (see
 * [ToolRegistry.createDefault]), so the mapping lives for the conversation.
 * Entries are bounded; the oldest are evicted once [maxEntries] is exceeded.
 */
class WebLinkStore(private val maxEntries: Int = 64) {

    private var counter = 0
    private val map = object : LinkedHashMap<String, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>): Boolean =
            size > maxEntries
    }

    /** Store [realUrl] and return a compact reference token (e.g. "ddg:3"). */
    @Synchronized
    fun reference(realUrl: String): String {
        val key = "$PREFIX${++counter}"
        map[key] = realUrl
        return key
    }

    /** Resolve a reference token back to its real URL, or null if unknown. */
    @Synchronized
    fun resolve(token: String): String? = map[token.trim()]

    /** True if [value] looks like a reference token this store hands out. */
    fun isReference(value: String): Boolean = value.trim().startsWith(PREFIX)

    /** Current reference -> URL mapping, for persisting alongside a conversation. */
    @Synchronized
    fun snapshot(): Map<String, String> = LinkedHashMap(map)

    /** Drop all references (e.g. when starting a new conversation). */
    @Synchronized
    fun clear() {
        map.clear()
        counter = 0
    }

    /**
     * Replace the contents with [entries] restored from a saved conversation,
     * and continue the counter past the highest existing index so newly minted
     * references never collide with restored ones.
     */
    @Synchronized
    fun restore(entries: Map<String, String>) {
        map.clear()
        var maxIndex = 0
        for ((key, value) in entries) {
            map[key] = value
            val n = key.removePrefix(PREFIX).toIntOrNull()
            if (n != null && n > maxIndex) maxIndex = n
        }
        counter = maxIndex
    }

    companion object {
        const val PREFIX = "ddg:"
    }
}
