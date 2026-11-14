package com.druk.lmplayground.harness

import com.druk.llamacpp.jni.NativeLlamaModel
import java.io.File

enum class Status { PASS, FAIL, WARN, SKIP, ERROR }

/**
 * One probe outcome.
 *
 * [code] is the machine-readable reason (PARSER_FAILURE, NO_EMISSION, ...) —
 * the distinction that makes the report actionable rather than a wall of red.
 * [nextStep] carries a copy-pasteable command for digging further.
 */
data class ProbeResult(
    val probe: String,
    val cap: Cap?,
    val status: Status,
    val code: String,
    val reason: String,
    val durationMs: Long = 0,
    val detail: Map<String, String> = emptyMap(),
    val rawArtifact: String? = null,
    val nextStep: String? = null,
)

class ProbeContext(
    val expectation: ModelExpectation,
    val catalog: CatalogEntry,
    val model: NativeLlamaModel,
    val modelFile: File,
    val mmprojFile: File?,
    val artifacts: ArtifactSink,
) {
    fun expect(cap: Cap) = Expectations.expect(expectation, cap)

    /** Apply expectation severity: OPTIONAL failures warn instead of failing. */
    fun grade(cap: Cap, failed: Boolean): Status = when {
        !failed -> Status.PASS
        expect(cap) == Expect.OPTIONAL -> Status.WARN
        else -> Status.FAIL
    }
}

interface Probe {
    val name: String
    val cap: Cap?
    fun run(ctx: ProbeContext): List<ProbeResult>
}

/** Writes raw model output next to the report so failures can be inspected. */
class ArtifactSink(private val dir: File, private val modelName: String) {
    fun write(label: String, content: String): String {
        val target = File(dir, "raw/${modelName.removeSuffix(".gguf")}/$label.txt")
        target.parentFile.mkdirs()
        target.writeText(content)
        return target.relativeTo(dir).path
    }
}
