package com.druk.lmplayground.harness.probes

import com.druk.llamacpp.jni.NativeAsr
import com.druk.lmplayground.harness.*
import java.io.File
import java.util.Locale

/**
 * Voice dictation: the Parakeet speech-recognition engine.
 *
 * This is the only part of the app that does not run on llama.cpp — it is
 * parakeet.cpp, vendored into the fork and linked against the same ggml. That
 * makes it the piece most likely to break silently on a submodule bump: it
 * compiles against whatever ggml API is current, and a graph that still
 * compiles can still decode to nonsense after an op changes meaning. A
 * transcript is either right or it isn't, so this probe checks the words.
 *
 * Three levels, weakest to strongest:
 *
 *  - the model loads and returns a transcript at all;
 *  - word error rate on real recorded speech (jfk.wav) is within budget —
 *    the check that a numerically broken encoder cannot pass;
 *  - synthesized clips in four languages come back in the right language,
 *    which is what exercises multilingual auto-detection.
 *
 * Speed is reported, never graded: it is dominated by whatever else the Mac
 * is doing, and the Android build is a different machine entirely.
 */
object AsrProbe {

    const val MODEL_FILENAME = "tdt-0.6b-v3-q4_k.gguf"
    private const val MODEL_NAME = "Parakeet TDT 0.6B v3"

    /**
     * Real speech, q4_k, greedy TDT decoding. NVIDIA reports ~6% WER for this
     * checkpoint across the Open ASR corpus; on one clean 11 s clip anything
     * above 15% means something is wrong with the pipeline, not the model.
     */
    private const val WER_BUDGET = 0.15

    fun run(modelsDir: File, reportDir: File): ModelReport {
        val file = File(modelsDir, MODEL_FILENAME)
        if (!file.isFile) return ModelReport(MODEL_NAME, MODEL_FILENAME, present = false)

        val artifacts = ArtifactSink(reportDir, MODEL_FILENAME)
        val results = mutableListOf<ProbeResult>()
        val started = System.currentTimeMillis()

        // Same JNI the app uses; the engine picks its backend from
        // PARAKEET_DEVICE (CPU unless overridden) on first load.
        val asr = NativeAsr()
        Engine.init()  // registers the ggml backends parakeet then selects from
        val handle = try {
            asr.loadModel(file.absolutePath)
        } catch (t: Throwable) {
            return ModelReport(
                MODEL_NAME, MODEL_FILENAME, present = true,
                loadError = "${t::class.simpleName}: ${t.message}",
                loadMs = System.currentTimeMillis() - started,
            )
        }
        val loadMs = System.currentTimeMillis() - started
        if (handle == 0L) {
            return ModelReport(
                MODEL_NAME, MODEL_FILENAME, present = true,
                loadError = "parakeet_capi_load returned null — not a Parakeet GGUF?",
                loadMs = loadMs,
            )
        }

        try {
            val anchor = AudioFixtures.anchor()
            if (anchor == null) {
                results += ProbeResult(
                    "asr", null, Status.SKIP, "NO_FIXTURE",
                    "app/src/androidTest/assets/audio/jfk.wav is missing — accuracy unverified",
                )
            } else {
                results += accuracy(asr, handle, anchor, artifacts)
            }

            val synthetic = AudioFixtures.synthetic()
            results += if (synthetic.isEmpty()) {
                ProbeResult(
                    "asr-multilingual", null, Status.SKIP, "NO_VOICES",
                    "no macOS voices installed for the test languages",
                )
            } else {
                multilingual(asr, handle, synthetic, artifacts)
            }
        } catch (t: Throwable) {
            results += ProbeResult(
                "asr", null, Status.ERROR, "ERROR",
                "${t::class.simpleName}: ${t.message}", System.currentTimeMillis() - started,
            )
        } finally {
            asr.freeModel(handle)
        }

        return ModelReport(
            MODEL_NAME, MODEL_FILENAME, present = true, results = results, loadMs = loadMs,
        )
    }

    private fun accuracy(
        asr: NativeAsr,
        handle: Long,
        clip: AudioFixtures.Clip,
        artifacts: ArtifactSink,
    ): ProbeResult {
        val t0 = System.currentTimeMillis()
        val text = asr.transcribePath(handle, clip.pcm.absolutePath, clip.lang)
        val ms = System.currentTimeMillis() - t0

        if (text.isNullOrBlank()) {
            return ProbeResult(
                "asr", null, Status.FAIL, "NO_TRANSCRIPT",
                "real speech transcribed to ${if (text == null) "null" else "an empty string"} — " +
                    "dictation would silently insert nothing",
                ms, mapOf("lastError" to asr.lastError(handle)),
            )
        }

        val wer = wordErrorRate(clip.reference, text)
        val ok = wer <= WER_BUDGET
        val rtf = ms / 1000.0 / clip.seconds
        return ProbeResult(
            "asr", null,
            if (ok) Status.PASS else Status.FAIL,
            if (ok) "OK" else "HIGH_WER",
            if (ok) "WER ${pct(wer)} on ${"%.0f".format(clip.seconds)}s of real speech"
            else "WER ${pct(wer)} exceeds the ${pct(WER_BUDGET)} budget — the decoder is " +
                "producing wrong words, not just different punctuation",
            ms,
            mapOf(
                "wer" to pct(wer),
                "speed" to "${"%.2f".format(rtf)}x realtime (${ms}ms for ${"%.1f".format(clip.seconds)}s)",
                "heard" to text.trim(),
            ),
            rawArtifact = artifacts.write("asr-jfk", "reference: ${clip.reference}\nheard:     $text"),
        )
    }

    private fun multilingual(
        asr: NativeAsr,
        handle: Long,
        clips: List<AudioFixtures.Clip>,
        artifacts: ArtifactSink,
    ): ProbeResult {
        val t0 = System.currentTimeMillis()
        val detail = linkedMapOf<String, String>()
        val transcript = StringBuilder()
        var wrong = 0

        for (clip in clips) {
            // "auto" on purpose: dictation never tells the model which language
            // it is about to hear.
            val text = asr.transcribePath(handle, clip.pcm.absolutePath, "auto")
            transcript.append("[${clip.lang}]\n  want: ${clip.reference}\n  got:  $text\n")
            val wer = if (text.isNullOrBlank()) 1.0 else wordErrorRate(clip.reference, text)
            // Loose on purpose: synthetic speech, and a wrong language produces
            // a WER far above this rather than just below it.
            if (wer > 0.5) wrong++
            detail[clip.lang] = if (text.isNullOrBlank()) "no transcript" else "WER ${pct(wer)}"
        }

        val ms = System.currentTimeMillis() - t0
        return ProbeResult(
            "asr-multilingual", null,
            if (wrong == 0) Status.PASS else Status.FAIL,
            if (wrong == 0) "OK" else "LANG_FAILED",
            if (wrong == 0) "${clips.size} languages transcribed with auto-detection " +
                "(${clips.joinToString(", ") { it.lang }})"
            else "$wrong of ${clips.size} languages came back wrong — auto-detection or the " +
                "multilingual head is broken",
            ms, detail,
            rawArtifact = artifacts.write("asr-multilingual", transcript.toString()),
        )
    }

    private fun pct(v: Double) = "${"%.1f".format(v * 100)}%"

    /**
     * Word error rate: Levenshtein distance over words, normalized by the
     * reference length. Case and punctuation are stripped first — dictation
     * inserts text a human then edits, so "France?" vs "france" is not an
     * error worth failing a build over.
     */
    internal fun wordErrorRate(reference: String, hypothesis: String): Double {
        val ref = normalize(reference)
        val hyp = normalize(hypothesis)
        if (ref.isEmpty()) return if (hyp.isEmpty()) 0.0 else 1.0

        var prev = IntArray(hyp.size + 1) { it }
        val cur = IntArray(hyp.size + 1)
        for (i in 1..ref.size) {
            cur[0] = i
            for (j in 1..hyp.size) {
                val sub = prev[j - 1] + if (ref[i - 1] == hyp[j - 1]) 0 else 1
                cur[j] = minOf(sub, prev[j] + 1, cur[j - 1] + 1)
            }
            prev = cur.copyOf()
        }
        return prev[hyp.size].toDouble() / ref.size
    }

    private fun normalize(s: String): List<String> = s
        .lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{Nd}\\s']"), " ")
        .split(Regex("\\s+"))
        .filter { it.isNotEmpty() }
}
