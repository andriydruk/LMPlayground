package com.druk.lmplayground.harness

import com.druk.lmplayground.harness.probes.AsrProbe
import com.druk.lmplayground.harness.probes.EmbeddingProbe
import java.io.File

/**
 * Capability sweep over the local model corpus.
 *
 *   ./gradlew :model-harness:capabilityReport
 *   ./gradlew :model-harness:capabilityReport --args="--models SmolLM3 --probes tools"
 *
 * Each model runs in its own worker JVM: loading a multi-GB GGUF through JNI
 * can take the process down (bad quant, mmproj mismatch, OOM), and one bad
 * model must not end a sweep that takes an hour. The parent records a load
 * failure for that row and moves on.
 */
fun main(args: Array<String>) {
    val opts = Args(args)
    if (opts.worker != null) { runWorker(opts); return }

    val repo = Repo.root()
    val modelsDir = modelsDir()
    val reportDir = File(repo, "build/reports/model-capabilities").apply { mkdirs() }
    val overrideDir = File(repo, "app/src/main/assets/chat_templates")

    // Held for the whole run; released on exit, including a crash.
    val lock = RunGuard.lockReportDir(reportDir)
    val buildStamp = RunGuard.buildStamp()

    val catalog = Catalog.parse(Catalog.defaultProviderFile(repo))
    val expectations = Expectations.byFilename()

    val declared = Expectations.ALL.mapNotNull { e ->
        catalog.firstOrNull { it.filename == e.filename }?.let { it to e }
    }
    // Catalog models with no expectation are reported, not skipped: that is
    // what stops a newly-added model from shipping unverified.
    val undeclared = catalog.filter { c ->
        !c.deprecated &&
            c.filename !in expectations &&
            // The embedding and dictation models are parsed out of the catalog
            // like any other ModelInfo, but neither can chat — they have their
            // own probes below and must not also be reported as unverified
            // chat models.
            c.filename != EmbeddingProbe.MODEL_FILENAME &&
            c.filename != AsrProbe.MODEL_FILENAME &&
            File(modelsDir, c.filename).isFile
    }

    val selected = declared.filter { (c, _) -> opts.matchesModel(c.name, c.filename) }
    println("corpus    ${selected.size} model(s), models dir $modelsDir")
    println("probes    ${Runner.probesFor(opts.probes).joinToString(", ") { it.name }}")
    println()

    val started = System.currentTimeMillis()
    val reports = mutableListOf<ModelReport>()
    for ((entry, expectation) in selected) {
        if (!File(modelsDir, entry.filename).isFile) {
            println("skip      ${entry.name} - not downloaded")
            reports += ModelReport(entry.name, entry.filename, present = false)
            continue
        }
        println("run       ${entry.name}")
        RunGuard.requireUnchangedBuild(buildStamp)
        reports += if (opts.inProcess) {
            Runner.runModel(entry, expectation, modelsDir, reportDir,
                Runner.probesFor(opts.probes), overrideDir)
        } else {
            runInWorker(entry, opts)
        }
        val last = reports.last()
        val bad = last.results.count { it.status == Status.FAIL || it.status == Status.ERROR }
        println("          ${last.results.size} results, $bad failing" +
            (last.loadError?.let { " - LOAD FAILED: $it" } ?: ""))
    }
    reports += undeclared.map {
        ModelReport(it.name, it.filename, present = true, hasExpectation = false)
    }

    // The embedding model sits outside the chat catalog — it cannot generate,
    // so it flows through its own probe rather than the per-model loop.
    if (opts.matchesModel("EmbeddingGemma 300M", EmbeddingProbe.MODEL_FILENAME) &&
        (opts.probes.isEmpty() || "embeddings" in opts.probes)
    ) {
        println("run       EmbeddingGemma 300M (document Q&A)")
        val embedding = EmbeddingProbe.run(modelsDir, reportDir)
        val bad = embedding.results.count { it.status == Status.FAIL || it.status == Status.ERROR }
        println("          ${embedding.results.size} results, $bad failing")
        reports += embedding
    }

    // Voice dictation runs on parakeet.cpp, not llama.cpp, and transcribes
    // audio rather than generating tokens — so like the embedding model it
    // sits outside the per-model chat loop.
    if (opts.matchesModel("Parakeet TDT 0.6B v3", AsrProbe.MODEL_FILENAME) &&
        (opts.probes.isEmpty() || "asr" in opts.probes)
    ) {
        println("run       Parakeet TDT 0.6B v3 (voice dictation)")
        val asr = AsrProbe.run(modelsDir, reportDir)
        val bad = asr.results.count { it.status == Status.FAIL || it.status == Status.ERROR }
        println("          ${asr.results.size} results, $bad failing" +
            (asr.loadError?.let { " - LOAD FAILED: $it" } ?: ""))
        reports += asr
    }

    val meta = linkedMapOf(
        "run" to java.time.LocalDateTime.now().withNano(0).toString(),
        "host" to "${System.getProperty("os.name")} ${System.getProperty("os.arch")}",
        "backend" to (System.getenv("LMP_HOST_N_GPU_LAYERS")
            ?.let { "CPU (ngl=$it)" } ?: "Metal (default ngl)"),
        "llama.cpp" to submoduleSha(repo),
        "harness build" to buildStamp,
        "duration" to "${(System.currentTimeMillis() - started) / 1000}s",
    )

    File(reportDir, "report.md").writeText(Report.markdown(reports, meta))
    File(reportDir, "results.json").writeText(Report.json(reports))
    println()
    println(Report.console(reports))
    println("report    ${File(reportDir, "report.md")}")
    lock.close()
}

private fun modelsDir() = File(
    System.getenv("LMP_MODELS_DIR")
        ?: File(System.getProperty("user.home"), ".cache/lmplayground/models").path
)

private fun runInWorker(entry: CatalogEntry, opts: Args): ModelReport {
    val java = File(File(System.getProperty("java.home"), "bin"), "java").path
    val cmd = mutableListOf(
        java,
        "-Djava.library.path=" + System.getProperty("java.library.path"),
        "-Dlmp.nativeLibDir=" + System.getProperty("lmp.nativeLibDir"),
        "-cp", System.getProperty("java.class.path"),
        "com.druk.lmplayground.harness.MainKt",
        "--worker", entry.filename,
    )
    if (opts.probes.isNotEmpty()) { cmd += "--probes"; cmd += opts.probes.joinToString(",") }
    val proc = ProcessBuilder(cmd)
        .directory(Repo.root())
        .redirectError(ProcessBuilder.Redirect.INHERIT)
        .start()
    val out = proc.inputStream.bufferedReader().readText()
    val code = proc.waitFor()
    val payload = out.substringAfter(WORKER_MARKER, "").trim()
    val partial = WorkerCodec.decode(entry.name, entry.filename, payload)
    if (code != 0) {
        // Keep whatever the worker managed to stream, and name the probe it
        // died in — that is usually the whole diagnosis.
        val died = Runner.probesFor(opts.probes)
            .map { it.name }
            .firstOrNull { name -> partial.results.none { it.probe == name } }
        return partial.copy(
            loadError = "worker exited with code $code (native crash)" +
                (died?.let { ", died during probe '" + it + "'" } ?: ""),
            results = partial.results +
                ProbeResult(died ?: "?", null, Status.ERROR, "NATIVE_CRASH",
                    "process aborted with exit code " + code),
        )
    }
    if (payload.isEmpty()) {
        return ModelReport(entry.name, entry.filename, present = true,
            loadError = "worker produced no output (exit $code)")
    }
    return partial
}

private fun runWorker(opts: Args) {
    val repo = Repo.root()
    val catalog = Catalog.parse(Catalog.defaultProviderFile(repo))
    val entry = catalog.first { it.filename == opts.worker }
    // Stream results as they land: a probe that takes the process down (a bad
    // mmproj, a ggml_abort) must not cost us the probes that already passed.
    println(WORKER_MARKER)
    System.out.flush()
    var emittedLoad = false
    val report = Runner.runModel(
        entry,
        Expectations.byFilename().getValue(opts.worker!!),
        modelsDir(),
        File(repo, "build/reports/model-capabilities"),
        Runner.probesFor(opts.probes),
        File(repo, "app/src/main/assets/chat_templates"),
    ) { result ->
        if (!emittedLoad) { println(WorkerCodec.loadLine(0, null, false)); emittedLoad = true }
        println(WorkerCodec.resultLine(result))
        System.out.flush()
    }
    // Final LOAD line carries the real timings and overrides; the decoder
    // takes the last one it sees.
    println(WorkerCodec.loadLine(report.loadMs, report.loadError, report.overridden))
    System.out.flush()
}

private const val WORKER_MARKER = "###HARNESS-RESULT###"

/**
 * One line per result, tab-separated. Deliberately not JSON: a worker killed
 * mid-write leaves a truncated last line the parent can simply drop, rather
 * than an unparseable document.
 */
private object WorkerCodec {
    private const val SEP = "\t"
    private const val KV = "|;|"

    fun loadLine(loadMs: Long, loadError: String?, overridden: Boolean): String =
        listOf("LOAD", loadMs.toString(), loadError ?: "", overridden.toString()).joinToString(SEP)

    fun resultLine(it: ProbeResult): String = listOf(
        "R", it.probe, it.cap?.name ?: "", it.status.name, it.code,
        clean(it.reason), it.durationMs.toString(),
        it.rawArtifact ?: "", it.nextStep ?: "",
        it.detail.entries.joinToString(KV) { (k, v) -> k + "=" + clean(v) },
    ).joinToString(SEP)

    fun decode(name: String, filename: String, payload: String): ModelReport {
        var loadMs = 0L
        var loadError: String? = null
        var overridden = false
        val results = mutableListOf<ProbeResult>()
        payload.lines().filter { it.isNotBlank() }.forEach { line ->
            val f = line.split(SEP)
            when {
                f[0] == "LOAD" && f.size >= 4 -> {
                    loadMs = f[1].toLongOrNull() ?: 0
                    loadError = f[2].ifEmpty { null }
                    overridden = f[3].toBoolean()
                }
                f[0] == "R" && f.size >= 10 -> results += ProbeResult(
                    probe = f[1],
                    cap = f[2].ifEmpty { null }?.let { runCatching { Cap.valueOf(it) }.getOrNull() },
                    status = Status.valueOf(f[3]),
                    code = f[4],
                    reason = f[5],
                    durationMs = f[6].toLongOrNull() ?: 0,
                    rawArtifact = f[7].ifEmpty { null },
                    nextStep = f[8].ifEmpty { null },
                    detail = f[9].split(KV).filter { it.contains("=") }
                        .associate { it.substringBefore("=") to it.substringAfter("=") },
                )
            }
        }
        return ModelReport(name, filename, present = true, results = results,
            loadError = loadError, loadMs = loadMs, overridden = overridden)
    }

    private fun clean(s: String) = s.replace("\t", " ").replace("\n", " ").replace(KV, " ")
}

private class Args(argv: Array<String>) {
    val worker: String? = value(argv, "--worker")
    val probes: Set<String> =
        value(argv, "--probes")?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
            ?: emptySet()
    val inProcess: Boolean = argv.contains("--in-process")
    private val models: List<String> =
        value(argv, "--models")?.split(",")?.map { it.trim().lowercase() } ?: emptyList()

    fun matchesModel(name: String, filename: String): Boolean =
        models.isEmpty() || models.any {
            name.lowercase().contains(it) || filename.lowercase().contains(it)
        }

    private fun value(argv: Array<String>, flag: String): String? {
        val i = argv.indexOf(flag)
        return if (i >= 0 && i + 1 < argv.size) argv[i + 1] else null
    }
}

private fun submoduleSha(repo: File): String = runCatching {
    ProcessBuilder("git", "-C", File(repo, "app/src/main/cpp/llama.cpp").path,
        "describe", "--tags", "--always")
        .start().inputStream.bufferedReader().readText().trim()
}.getOrDefault("unknown")
