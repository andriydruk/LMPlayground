package com.druk.lmplayground.harness

import java.io.File

/**
 * The app's model catalog, read at runtime from ModelInfoProvider.kt.
 *
 * Parsed rather than imported: ModelInfoProvider pulls in android.net.Uri and
 * the generated R class, so it can never be compiled into a JVM module. The
 * parse is the point — it means a model added to the catalog automatically
 * shows up here, and one without a declared expectation is reported rather
 * than silently skipped. That is how "every model we ship gets tested" is
 * enforced without a CI gate.
 */
data class CatalogEntry(
    val name: String,
    val filename: String,
    val remoteUrl: String?,
    val mmprojFilename: String?,
    val mmprojUrl: String?,
    val deprecated: Boolean,
    /** Badge flags from ModelInfoProvider's TOOL_CAPABLE / THINKING_CAPABLE. */
    val catalogSupportsTools: Boolean,
    val catalogSupportsThinking: Boolean,
) {
    val isVision: Boolean get() = mmprojFilename != null
}

object Catalog {

    private val ENTRY = Regex("""ModelInfo\((.*?)\n        \)""", RegexOption.DOT_MATCHES_ALL)

    fun parse(providerFile: File): List<CatalogEntry> {
        require(providerFile.isFile) { "ModelInfoProvider.kt not found at $providerFile" }
        val src = providerFile.readText()

        val toolCapable = namedSet(src, "TOOL_CAPABLE")
        val thinkingCapable = namedSet(src, "THINKING_CAPABLE")

        val entries = ENTRY.findAll(src).mapNotNull { m ->
            val b = m.groupValues[1]
            val filename = field(b, "filename") ?: return@mapNotNull null
            CatalogEntry(
                name = field(b, "name") ?: filename,
                filename = filename,
                remoteUrl = uriField(b, "remoteUri"),
                mmprojFilename = field(b, "mmprojFilename"),
                mmprojUrl = uriField(b, "mmprojUri"),
                deprecated = Regex("""deprecated\s*=\s*true""").containsMatchIn(b),
                catalogSupportsTools = filename in toolCapable,
                catalogSupportsThinking = filename in thinkingCapable,
            )
        }.toList()

        // Self-test: a reformat of ModelInfoProvider must fail loudly here
        // rather than quietly shrink the corpus to nothing (which would make
        // the report read all-green).
        check(entries.size >= 25) {
            "parsed only ${entries.size} catalog entries — ModelInfoProvider.kt layout changed?"
        }
        check(entries.any { it.filename == CANARY && it.remoteUrl != null }) {
            "canary entry $CANARY missing or has no remoteUri — parser is out of date"
        }
        check(toolCapable.isNotEmpty() && thinkingCapable.isNotEmpty()) {
            "capability sets parsed empty — TOOL_CAPABLE/THINKING_CAPABLE layout changed?"
        }
        return entries
    }

    /** Default location, relative to the repo root. */
    fun defaultProviderFile(repoRoot: File) = File(
        repoRoot,
        "app/src/main/java/com/druk/lmplayground/models/ModelInfoProvider.kt",
    )

    private fun field(block: String, key: String): String? =
        Regex("""$key\s*=\s*"([^"]+)"""").find(block)?.groupValues?.get(1)

    private fun uriField(block: String, key: String): String? =
        Regex("""$key\s*=\s*Uri\.parse\("([^"]+)"\)""").find(block)?.groupValues?.get(1)

    private fun namedSet(src: String, name: String): Set<String> {
        val body = Regex("""$name = setOf\((.*?)\n    \)""", RegexOption.DOT_MATCHES_ALL)
            .find(src)?.groupValues?.get(1) ?: return emptySet()
        return Regex(""""([^"]+\.gguf)"""").findAll(body).map { it.groupValues[1] }.toSet()
    }

    private const val CANARY = "HuggingFaceTB_SmolLM3-3B-Q4_K_M.gguf"
}
