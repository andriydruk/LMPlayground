package com.druk.lmplayground.harness

import com.druk.lmplayground.harness.probes.*
import java.io.File

/** Everything observed about one model. */
data class ModelReport(
    val name: String,
    val filename: String,
    val present: Boolean,
    val results: List<ProbeResult> = emptyList(),
    val loadError: String? = null,
    val loadMs: Long = 0,
    val hasExpectation: Boolean = true,
    val overridden: Boolean = false,
)

object Runner {

    val PROBES: List<Probe> = listOf(
        TemplateCapsProbe,      // cheapest first: no decoding
        PreambleProbe,
        ToolRoundTripProbe,
        ThinkingProbe,
        VisionProbe,
        MultiTurnProbe,
        ReplayProbe,
        PreambleCacheProbe,
        AbortProbe,
        ContextOverflowProbe,
        ToolsWhileThinkingProbe,
        ToolsWithVisionProbe,
    )

    fun probesFor(filter: Set<String>): List<Probe> =
        if (filter.isEmpty()) PROBES else PROBES.filter { it.name in filter }

    /**
     * Runs every probe against one model. The model is loaded once and reused;
     * each probe gets its own session.
     */
    fun runModel(
        entry: CatalogEntry,
        expectation: ModelExpectation,
        modelsDir: File,
        reportDir: File,
        probes: List<Probe>,
        overrideDir: File?,
        /** Called as each result lands, so a later native crash can't lose it. */
        onResult: (ProbeResult) -> Unit = {},
    ): ModelReport {
        val file = File(modelsDir, entry.filename)
        if (!file.isFile) return ModelReport(entry.name, entry.filename, present = false)

        val mmproj = entry.mmprojFilename?.let { File(modelsDir, it) }?.takeIf { it.isFile }
        val override = overrideDir
            ?.resolve(entry.filename.removeSuffix(".gguf") + ".jinja")
            ?.takeIf { it.isFile }

        val started = System.currentTimeMillis()
        val llama = Engine.init()
        val model = try {
            Engine.loadModel(llama, file, override?.readText().orEmpty())
        } catch (t: Throwable) {
            return ModelReport(entry.name, entry.filename, present = true,
                loadError = "${t::class.simpleName}: ${t.message}",
                loadMs = System.currentTimeMillis() - started,
                overridden = override != null)
        }
        val loadMs = System.currentTimeMillis() - started

        val ctx = ProbeContext(
            expectation = expectation,
            catalog = entry,
            model = model,
            modelFile = file,
            mmprojFile = mmproj,
            artifacts = ArtifactSink(reportDir, entry.filename),
        )

        val results = mutableListOf<ProbeResult>()
        try {
            for (probe in probes) {
                val cap = probe.cap
                if (cap != null && Expectations.expect(expectation, cap) == Expect.UNSUPPORTED) {
                    val skipped = ProbeResult(probe.name, cap, Status.SKIP, "UNSUPPORTED",
                        "declared unsupported for this model")
                    results += skipped
                    onResult(skipped)
                    continue
                }
                System.err.println("  · ${entry.name}: ${probe.name}")
                val produced = try {
                    probe.run(ctx)
                } catch (t: Throwable) {
                    listOf(ProbeResult(probe.name, cap, Status.ERROR, "PROBE_THREW",
                        "${t::class.simpleName}: ${t.message}"))
                }
                results += produced
                produced.forEach(onResult)
            }
        } finally {
            model.unloadModel()
        }
        return ModelReport(entry.name, entry.filename, present = true, results = results,
            loadMs = loadMs, overridden = override != null)
    }
}
