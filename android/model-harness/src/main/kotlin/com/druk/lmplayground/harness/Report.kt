package com.druk.lmplayground.harness

import java.io.File

/**
 * Renders the capability matrix.
 *
 * The matrix answers "what is broken"; the per-failure blocks answer "why, and
 * what do I run next". The failure-class table exists because PARSER_FAILURE
 * ("our parser is broken") and NO_EMISSION ("this model is small") need
 * completely different responses and would otherwise look identical in red.
 */
object Report {

    private val COLUMNS = listOf(
        "Load" to null,
        "Caps" to null,
        "Preamble" to null,
        "Tools" to Cap.TOOLS,
        "Think" to Cap.THINKING,
        "NoThink" to Cap.NO_THINKING,
        "Vision" to Cap.VISION,
        "MultiTurn" to Cap.MULTI_TURN,
        "Replay" to null,
        "Cache" to null,
        "Abort" to null,
        "Overflow" to null,
        "Tool+Think" to Cap.TOOLS_WITH_THINKING,
        "Tool+Vis" to Cap.TOOLS_WITH_VISION,
        "Embed" to null,
        "ASR" to null,
        "ASR-lang" to null,
        "ASR-live" to null,
    )

    private fun cell(r: ModelReport, column: String, cap: Cap?): String {
        if (!r.present) return "--"
        // A worker that crashed part-way still streamed real results; show
        // them rather than blanking the whole row.
        if (column == "Load") return if (r.loadError != null) "**err**" else "ok"
        if (r.results.isEmpty()) return "--"
        val relevant = when (column) {
            "Caps" -> r.results.filter { it.probe == "caps" }
            "Preamble" -> r.results.filter { it.probe == "preamble" }
            "Think" -> r.results.filter { it.probe == "think" && it.detail["mode"] == "think" }
            "NoThink" -> r.results.filter { it.probe == "think" && it.detail["mode"] == "no-think" }
            "Vision" -> r.results.filter { it.probe.startsWith("vision") }
            "Tools" -> r.results.filter { it.probe == "tools" }
            "Replay" -> r.results.filter { it.probe == "replay" }
            "Cache" -> r.results.filter { it.probe == "preamble-cache" }
            "Abort" -> r.results.filter { it.probe == "abort" }
            "Overflow" -> r.results.filter { it.probe == "overflow" }
            "Tool+Think" -> r.results.filter { it.probe == "tools+think" }
            "Tool+Vis" -> r.results.filter { it.probe == "tools+vision" }
            "Embed" -> r.results.filter { it.probe == "embeddings" }
            "ASR" -> r.results.filter { it.probe == "asr" }
            "ASR-lang" -> r.results.filter { it.probe == "asr-multilingual" }
            "ASR-live" -> r.results.filter { it.probe == "asr-streaming" }
            else -> r.results.filter { it.cap == cap }
        }
        if (relevant.isEmpty()) return "·"
        return when {
            relevant.any { it.status == Status.ERROR } -> "**err**"
            relevant.any { it.status == Status.FAIL } -> "**FAIL**"
            relevant.any { it.status == Status.WARN } -> "warn"
            relevant.all { it.status == Status.SKIP } -> "n/a"
            else -> "ok"
        }
    }

    fun markdown(reports: List<ModelReport>, meta: Map<String, String>): String {
        val sb = StringBuilder()
        sb.appendLine("# Model capability matrix").appendLine()
        meta.forEach { (k, v) -> sb.appendLine("- **$k**: $v") }
        sb.appendLine()

        sb.appendLine("| Model | " + COLUMNS.joinToString(" | ") { it.first } + " |")
        sb.appendLine("|" + "---|".repeat(COLUMNS.size + 1))
        for (r in reports) {
            val name = if (r.overridden) "${r.name} ᵗ" else r.name
            sb.appendLine("| $name | " + COLUMNS.joinToString(" | ") { (c, cap) -> cell(r, c, cap) } + " |")
        }
        sb.appendLine()
        sb.appendLine("`ok` pass · `FAIL` required probe failed · `warn` optional probe failed · " +
            "`n/a` declared unsupported · `--` model not downloaded · `err` crash/error · " +
            "`ᵗ` shipped chat-template override")
        sb.appendLine()

        // Failures, with enough context to act without re-running anything.
        val failures = reports.flatMap { r ->
            r.results.filter { it.status == Status.FAIL || it.status == Status.ERROR }.map { r to it }
        }
        if (failures.isNotEmpty()) {
            sb.appendLine("## Failures").appendLine()
            for ((r, f) in failures) {
                sb.appendLine("### ${r.name} — ${f.probe} — ${f.status} · ${f.code}").appendLine()
                sb.appendLine("```")
                sb.appendLine("file      ${r.filename}")
                sb.appendLine("verdict   ${f.reason}")
                f.detail.forEach { (k, v) -> sb.appendLine("${k.padEnd(9)} $v") }
                f.rawArtifact?.let { sb.appendLine("raw       $it") }
                f.nextStep?.let { sb.appendLine("next      $it") }
                sb.appendLine("```").appendLine()
            }
        }

        // The distinction that makes the matrix actionable.
        val classes = reports.flatMap { r -> r.results.map { r.name to it } }
            .filter { (_, res) -> res.code in INTERESTING }
            .groupBy({ it.second.code }, { it.first })
        if (classes.isNotEmpty()) {
            sb.appendLine("## Failure classes").appendLine()
            sb.appendLine("| class | count | models |")
            sb.appendLine("|---|---|---|")
            INTERESTING.forEach { code ->
                classes[code]?.let { models ->
                    sb.appendLine("| ${describe(code)} | ${models.size} | ${models.joinToString(", ")} |")
                }
            }
            sb.appendLine()
        }

        val drift = reports.flatMap { r -> r.results.filter { it.code == "CATALOG_FLAG_DRIFT" }.map { r to it } }
        if (drift.isNotEmpty()) {
            sb.appendLine("## Catalog badge drift").appendLine()
            sb.appendLine("| model | detail |").appendLine("|---|---|")
            drift.forEach { (r, d) -> sb.appendLine("| ${r.name} | ${d.reason} |") }
            sb.appendLine()
        }

        val undeclared = reports.filter { !it.hasExpectation }
        if (undeclared.isNotEmpty()) {
            sb.appendLine("## No expectation declared").appendLine()
            sb.appendLine("These are in the catalog but absent from `Expectations.kt`, so nothing " +
                "verifies them. Add an entry or state explicitly that they are out of corpus.")
            sb.appendLine()
            undeclared.forEach { sb.appendLine("- ${it.name} (`${it.filename}`)") }
            sb.appendLine()
        }
        return sb.toString()
    }

    private val INTERESTING = listOf(
        "PARSER_FAILURE", "NO_EMISSION", "EMPTY_FINAL", "WRONG_TOOL",
        "CHANNEL_LEAK", "IMAGE_TOKEN_LEAK", "NO_THINK_BLOCK", "THINK_NOT_SUPPRESSED",
    )

    private fun describe(code: String) = when (code) {
        "PARSER_FAILURE" -> "PARSER_FAILURE — model emitted a tool call, parser missed it"
        "NO_EMISSION" -> "NO_EMISSION — model never emitted a tool call"
        "EMPTY_FINAL" -> "EMPTY_FINAL — blank reply after the tool result"
        "WRONG_TOOL" -> "WRONG_TOOL — called a different tool"
        "CHANNEL_LEAK" -> "CHANNEL_LEAK — raw channel markup reached the caller"
        "IMAGE_TOKEN_LEAK" -> "IMAGE_TOKEN_LEAK — image placeholder in the reply"
        "NO_THINK_BLOCK" -> "NO_THINK_BLOCK — thinking on, no <think> block"
        "THINK_NOT_SUPPRESSED" -> "THINK_NOT_SUPPRESSED — thinking off, <think> anyway"
        else -> code
    }

    fun json(reports: List<ModelReport>): String {
        fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\t", "\\t")
        val sb = StringBuilder("[\n")
        reports.forEachIndexed { i, r ->
            sb.append("""  {"model":"${esc(r.name)}","file":"${esc(r.filename)}",""")
            sb.append(""""present":${r.present},"overridden":${r.overridden},""")
            sb.append(""""hasExpectation":${r.hasExpectation},"loadMs":${r.loadMs},""")
            r.loadError?.let { sb.append(""""loadError":"${esc(it)}",""") }
            sb.append(""""results":[""")
            sb.append(r.results.joinToString(",") { p ->
                """{"probe":"${esc(p.probe)}","cap":"${p.cap ?: ""}","status":"${p.status}",""" +
                """"code":"${esc(p.code)}","reason":"${esc(p.reason)}","ms":${p.durationMs},""" +
                """"detail":{""" +
                p.detail.entries.joinToString(",") { (k, v) -> """"${esc(k)}":"${esc(v)}"""" } +
                """}}"""
            })
            sb.append("]}").append(if (i == reports.lastIndex) "\n" else ",\n")
        }
        return sb.append("]\n").toString()
    }

    fun console(reports: List<ModelReport>): String {
        val w = maxOf(24, reports.maxOfOrNull { it.name.length + 2 } ?: 24)
        val sb = StringBuilder()
        sb.appendLine(" ".repeat(w) + COLUMNS.joinToString("") { it.first.take(9).padEnd(11) })
        for (r in reports) {
            sb.append(r.name.padEnd(w))
            sb.appendLine(COLUMNS.joinToString("") { (c, cap) ->
                cell(r, c, cap).replace("*", "").padEnd(11)
            })
        }
        return sb.toString()
    }
}
