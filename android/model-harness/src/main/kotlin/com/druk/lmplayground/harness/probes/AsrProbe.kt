package com.druk.lmplayground.harness.probes

import com.druk.llamacpp.asr.DictationTranscript
import com.druk.llamacpp.asr.WordErrorRate
import com.druk.llamacpp.jni.NativeAsr
import com.druk.lmplayground.harness.*
import java.io.File

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

    const val MODEL_FILENAME = "nemotron-3.5-asr-streaming-0.6b-q4_k.gguf"
    private const val MODEL_NAME = "Nemotron 3.5 ASR Streaming 0.6B"

    /** Live dictation feeds the encoder in slices this long. */
    private const val FEED_MS = 640

    /**
     * Real speech, q4_k, greedy TDT decoding. NVIDIA reports ~6% WER for this
     * checkpoint across the Open ASR corpus; on one clean 11 s clip anything
     * above 15% means something is wrong with the pipeline, not the model.
     */
    private const val WER_BUDGET = 0.15

    /** Streaming decodes with limited right context, so it is graded looser. */
    private const val STREAM_WER_BUDGET = 0.25

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

            results += streaming(asr, handle, artifacts)

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

    /**
     * Live dictation: the same clip fed in slices, as the microphone delivers
     * it. Checks that text arrives *before* the audio ends and that what
     * accumulates is still right.
     *
     * A checkpoint that cannot stream fails here rather than silently leaving
     * the user holding the mic with nothing appearing.
     */
    private fun streaming(asr: NativeAsr, handle: Long, artifacts: ArtifactSink): ProbeResult {
        val clip = AudioFixtures.anchor()
            ?: return ProbeResult(
                "asr-streaming", null, Status.SKIP, "NO_FIXTURE",
                "jfk.wav is missing — live dictation unverified",
            )

        val t0 = System.currentTimeMillis()
        val stream = asr.streamBegin(handle, clip.lang)
        if (stream == 0L) {
            return ProbeResult(
                "asr-streaming", null, Status.FAIL, "NOT_STREAMING_MODEL",
                "streamBegin failed — this checkpoint is not cache-aware streaming, so no " +
                    "text can appear while the user speaks",
                System.currentTimeMillis() - t0,
                mapOf("lastError" to asr.lastError(handle)),
            )
        }

        val samples = readPcm(clip.pcm)
        val slice = AudioFixtures.SAMPLE_RATE * FEED_MS / 1000
        // The app's own assembly, so this grades the text the user would see.
        val transcript = DictationTranscript()
        var firstTextMs = -1L
        var feeds = 0

        try {
            var offset = 0
            while (offset < samples.size) {
                val n = minOf(slice, samples.size - offset)
                val delta = asr.streamFeed(stream, samples.copyOfRange(offset, offset + n), n)
                feeds++
                if (!delta.isNullOrEmpty()) {
                    if (firstTextMs < 0) firstTextMs = System.currentTimeMillis() - t0
                    transcript.append(delta)
                }
                offset += n
            }
            asr.streamFinalize(stream)?.let { transcript.append(it) }
        } finally {
            asr.streamFree(stream)
        }

        val ms = System.currentTimeMillis() - t0
        val text = transcript.text
        val wer = if (text.isEmpty()) 1.0 else wordErrorRate(clip.reference, text)
        val realtime = ms / 1000.0 / clip.seconds
        // WER strips punctuation and splits on any whitespace, so it cannot see
        // "word ," or a doubled space. Checked separately, exactly.
        val badSpacing = SPACING_DEFECT.find(text)?.let { text.around(it.range) }
        val ok = text.isNotEmpty() && firstTextMs >= 0 && wer <= STREAM_WER_BUDGET &&
            badSpacing == null
        return ProbeResult(
            "asr-streaming", null,
            if (ok) Status.PASS else Status.FAIL,
            when {
                text.isEmpty() -> "NO_STREAM_TEXT"
                firstTextMs < 0 -> "NO_PARTIALS"
                wer > STREAM_WER_BUDGET -> "HIGH_WER"
                badSpacing != null -> "BAD_SPACING"
                else -> "OK"
            },
            if (ok) "streamed ${"%.0f".format(clip.seconds)}s in $feeds feeds, WER ${pct(wer)}, " +
                "first text after ${firstTextMs}ms"
            else if (text.isEmpty()) "streaming produced no text at all"
            else if (wer > STREAM_WER_BUDGET) "streamed WER ${pct(wer)} exceeds the ${pct(STREAM_WER_BUDGET)} budget"
            else "assembled text is mis-spaced near \"$badSpacing\" — deltas were not joined verbatim",
            ms,
            mapOf(
                "wer" to pct(wer),
                "firstText" to "${firstTextMs}ms",
                // Above 1.0 the decoder is slower than speech, so the text lags
                // further behind the longer the user talks.
                "speed" to "${"%.2f".format(realtime)}x realtime",
                "heard" to text,
            ),
            rawArtifact = artifacts.write(
                "asr-streaming", "reference: ${clip.reference}\nheard:     $text",
            ),
        )
    }

    /** A doubled space, or a space before punctuation. */
    private val SPACING_DEFECT = Regex("\\s{2}|\\s[,.!?;:]")

    private fun String.around(range: IntRange) =
        substring(maxOf(0, range.first - 12), minOf(length, range.last + 12))

    private fun readPcm(file: java.io.File): FloatArray {
        val bytes = file.readBytes()
        val bb = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        return FloatArray(bytes.size / 4) { bb.getFloat(it * 4) }
    }

    private fun pct(v: Double) = WordErrorRate.percent(v)

    private fun wordErrorRate(reference: String, hypothesis: String) =
        WordErrorRate.of(reference, hypothesis)
}
