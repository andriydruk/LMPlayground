package com.druk.lmplayground.models

/**
 * Pairs a base model GGUF with its multimodal projector (mmproj) GGUF by
 * filename convention, so vision lights up for sideloaded/custom models and
 * for catalog models whose projector is present on disk under a slightly
 * different name than the catalog declares.
 *
 * Real-world projector names embed the base model's identity, e.g.
 *   gemma-3-4b-it-Q4_K_M.gguf        <- gemma-3-4b-it-mmproj-f16.gguf
 *   Qwen_Qwen3.5-0.8B-Q3_K_M.gguf    <- mmproj-Qwen_Qwen3.5-0.8B-f16.gguf
 *   gemma-4-E2B_q4_0-it.gguf         <- mmproj-gemma-4-E2B-it-BF16.gguf
 * The quant/precision tags differ between the two files, so matching is done
 * on a normalized "identity" with the mmproj marker and quant/precision tags
 * stripped.
 */
object MmprojPairing {

    fun isMmproj(filename: String): Boolean =
        filename.contains("mmproj", ignoreCase = true)

    // Quantization tags (q4_0, q3_k_m, iq4_xs, q6_k, q8_0 ...) and float
    // precision tags (f16, f32, bf16, fp16). These vary between a model and its
    // projector, so they're dropped before comparing identities.
    private val DROP = Regex("i?q\\d[0-9a-z]*(_[0-9a-z]+)*|bf16|fp16|f16|f32", RegexOption.IGNORE_CASE)

    // Below this length a containment match is too loose to trust (e.g. a bare
    // "gemma" would latch onto any projector mentioning Gemma). Exact identity
    // matches are accepted at any length.
    private const val MIN_CONTAINMENT_LEN = 4

    /** Collapse a GGUF filename to a comparable model identity. */
    private fun core(filename: String): String =
        filename
            .removeSuffix(".gguf")
            .replace("mmproj", " ", ignoreCase = true)
            .replace(DROP, " ")
            .lowercase()
            .filter { it.isLetterOrDigit() }

    /**
     * The best matching mmproj filename in [candidates] for [modelFilename],
     * or null if none looks like its projector. [modelFilename] itself and any
     * non-mmproj candidates are ignored.
     */
    fun findMmprojFor(modelFilename: String, candidates: Collection<String>): String? {
        if (isMmproj(modelFilename)) return null
        val target = core(modelFilename)
        if (target.isEmpty()) return null

        val projectors = candidates.filter { isMmproj(it) && it != modelFilename }

        // 1. Exact identity match wins outright.
        projectors.firstOrNull { core(it) == target }?.let { return it }

        // 2. Otherwise the projector whose identity is contained in (or
        //    contains) the model's, preferring the longest overlap — but only
        //    when both identities are long enough to be meaningful.
        if (target.length < MIN_CONTAINMENT_LEN) return null
        return projectors
            .map { it to core(it) }
            .filter { (_, c) ->
                c.length >= MIN_CONTAINMENT_LEN && (target.contains(c) || c.contains(target))
            }
            .maxByOrNull { (_, c) -> c.length }
            ?.first
    }
}
