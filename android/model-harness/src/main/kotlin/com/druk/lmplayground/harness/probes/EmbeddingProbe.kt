package com.druk.lmplayground.harness.probes

import com.druk.lmplayground.harness.*
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * The embedding path behind document Q&A (RAG).
 *
 * Nothing else in the harness touches it: it uses a different session type, a
 * different model, and never generates a token. Its failures are also the
 * quietest in the app — embeddings that are subtly wrong still produce
 * plausible-looking vectors, so retrieval silently returns the wrong passages
 * and the model answers confidently from irrelevant context. There is no error
 * anywhere in that story.
 *
 * Three things are checked, in increasing strength:
 *
 *  - vectors come back at all, with a sane dimension and no NaNs;
 *  - they are L2-normalized, because RagRepository ranks by dot product and
 *    treats that as cosine similarity. If normalization regressed, longer
 *    chunks would quietly outrank more relevant ones;
 *  - the ranking is semantically right — a query retrieves the passage about
 *    its own topic ahead of unrelated ones. That is the property users
 *    actually depend on, and the only one that catches a wrong task prefix or
 *    a broken pooling mode.
 *
 * The task prefixes are copied from EmbeddingModelManager, since retrieval
 * quality depends on the query and document sides using the matching pair.
 */
object EmbeddingProbe {

    const val MODEL_FILENAME = "embeddinggemma-300m-Q4_0.gguf"
    private const val N_CTX = 2048

    private fun document(text: String) = "title: none | text: $text"
    private fun query(text: String) = "task: search result | query: $text"

    private val CHUNKS = listOf(
        "The tortoise Ada eats dandelion leaves and basks under a heat lamp each morning.",
        "Diesel locomotives replaced steam on the branch line in nineteen sixty-two.",
        "To reset the router, hold the recessed button for ten seconds until the light blinks amber.",
    )

    /** query -> index in [CHUNKS] it must rank first */
    private val QUERIES = listOf(
        "what does the tortoise eat" to 0,
        "when did steam trains stop running" to 1,
        "how do I reset my router" to 2,
    )

    fun run(modelsDir: File, reportDir: File): ModelReport {
        val file = File(modelsDir, MODEL_FILENAME)
        if (!file.isFile) {
            return ModelReport("EmbeddingGemma 300M", MODEL_FILENAME, present = false)
        }
        val artifacts = ArtifactSink(reportDir, MODEL_FILENAME)
        val started = System.currentTimeMillis()
        val results = mutableListOf<ProbeResult>()

        val llama = Engine.init()
        val model = try {
            Engine.loadModel(llama, file)
        } catch (t: Throwable) {
            return ModelReport(
                "EmbeddingGemma 300M", MODEL_FILENAME, present = true,
                loadError = "${t::class.simpleName}: ${t.message}",
                loadMs = System.currentTimeMillis() - started,
            )
        }
        val loadMs = System.currentTimeMillis() - started

        val session = model.createEmbeddingSession(N_CTX)
        if (session == null) {
            model.unloadModel()
            return ModelReport(
                "EmbeddingGemma 300M", MODEL_FILENAME, present = true,
                results = listOf(
                    ProbeResult("embeddings", null, Status.FAIL, "NO_EMBEDDING_SESSION",
                        "createEmbeddingSession returned null — document Q&A cannot index anything",
                        System.currentTimeMillis() - started)
                ),
                loadMs = loadMs,
            )
        }

        try {
            val dim = session.getEmbeddingDim()
            results += ProbeResult(
                "embeddings", null,
                if (dim > 0) Status.PASS else Status.FAIL,
                if (dim > 0) "OK" else "BAD_DIM",
                if (dim > 0) "embedding dimension $dim" else "embedding dimension is $dim",
                System.currentTimeMillis() - started,
            )
            if (dim <= 0) return finish(results, loadMs)

            val flat = session.embedTexts(CHUNKS.map { document(it) }.toTypedArray())
            if (flat == null || flat.size != CHUNKS.size * dim) {
                results += ProbeResult(
                    "embeddings", null, Status.FAIL, "EMBED_FAILED",
                    "embedTexts returned ${flat?.size ?: -1} floats, expected ${CHUNKS.size * dim}",
                    System.currentTimeMillis() - started,
                )
                return finish(results, loadMs)
            }
            val vectors = (CHUNKS.indices).map { i ->
                FloatArray(dim) { j -> flat[i * dim + j] }
            }

            val bad = vectors.withIndex().firstOrNull { (_, v) ->
                v.any { it.isNaN() || it.isInfinite() } || v.all { it == 0f }
            }
            results += ProbeResult(
                "embeddings", null,
                if (bad == null) Status.PASS else Status.FAIL,
                if (bad == null) "OK" else "DEGENERATE_VECTOR",
                if (bad == null) "${vectors.size} vectors, finite and non-zero"
                else "chunk ${bad.index} embedded to NaN/inf or all zeros",
                System.currentTimeMillis() - started,
            )

            // RagRepository ranks by dot product, which is only cosine
            // similarity if the vectors are unit length.
            val norms = vectors.map { v -> sqrt(v.sumOf { (it * it).toDouble() }) }
            val worst = norms.maxByOrNull { abs(it - 1.0) } ?: 1.0
            val normalized = abs(worst - 1.0) < 0.02
            results += ProbeResult(
                "embeddings", null,
                if (normalized) Status.PASS else Status.FAIL,
                if (normalized) "OK" else "NOT_NORMALIZED",
                if (normalized) "vectors are unit length (worst norm ${"%.4f".format(worst)})"
                else "vectors are not unit length (worst norm ${"%.4f".format(worst)}) — " +
                    "retrieval ranks by dot product and would skew toward longer chunks",
                System.currentTimeMillis() - started,
            )

            // The property users depend on: the right passage comes back first.
            var wrong = 0
            val detail = mutableMapOf<String, String>()
            for ((q, expected) in QUERIES) {
                val qflat = session.embedTexts(arrayOf(query(q)))
                if (qflat == null || qflat.size < dim) { wrong++; continue }
                val qv = FloatArray(dim) { qflat[it] }
                val scores = vectors.map { v -> v.indices.sumOf { (v[it] * qv[it]).toDouble() } }
                val best = scores.indices.maxByOrNull { scores[it] } ?: -1
                if (best != expected) wrong++
                detail[q.take(28)] = "top=$best want=$expected " +
                    scores.joinToString(",") { "%.3f".format(it) }
            }
            results += ProbeResult(
                "embeddings", null,
                if (wrong == 0) Status.PASS else Status.FAIL,
                if (wrong == 0) "OK" else "RETRIEVAL_WRONG",
                if (wrong == 0) "all ${QUERIES.size} queries retrieved their own passage first"
                else "$wrong of ${QUERIES.size} queries retrieved the wrong passage — document " +
                    "Q&A would answer from irrelevant context",
                System.currentTimeMillis() - started,
                detail,
                rawArtifact = if (wrong == 0) null
                else artifacts.write("embeddings", detail.entries.joinToString("\n")),
            )
        } catch (t: Throwable) {
            results += ProbeResult(
                "embeddings", null, Status.ERROR, "ERROR",
                "${t::class.simpleName}: ${t.message}", System.currentTimeMillis() - started,
            )
        } finally {
            session.destroy()
            model.unloadModel()
        }
        return finish(results, loadMs)
    }

    private fun finish(results: List<ProbeResult>, loadMs: Long) = ModelReport(
        "EmbeddingGemma 300M", MODEL_FILENAME, present = true, results = results, loadMs = loadMs,
    )
}
