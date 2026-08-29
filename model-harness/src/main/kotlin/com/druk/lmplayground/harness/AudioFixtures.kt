package com.druk.lmplayground.harness

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Speech clips for the ASR probes, in the format the engine consumes:
 * 16 kHz mono little-endian f32 PCM.
 *
 * Two sources, deliberately different in kind:
 *
 *  - `jfk.wav`, committed next to the instrumented tests: real recorded human
 *    speech, public domain. The only clip that says anything about real-world
 *    accuracy, so it carries the word-error-rate check.
 *  - synthesized clips, generated here by macOS `say` and cached outside the
 *    repo. They exist to prove the multilingual path and language
 *    auto-detection work; TTS is markedly easier to recognize than a real
 *    speaker, so they are graded loosely on purpose.
 */
object AudioFixtures {

    const val SAMPLE_RATE = 16_000

    data class Clip(
        val id: String,
        val lang: String,
        val reference: String,
        val pcm: File,
        val seconds: Double,
        val synthetic: Boolean,
    )

    private val assetsDir = File(Repo.root(), "app/src/androidTest/assets/audio")

    private val cacheDir: File = File(
        System.getenv("LMP_AUDIO_DIR")
            ?: File(System.getProperty("user.home"), ".cache/lmplayground/audio").path
    ).apply { mkdirs() }

    /**
     * Sentences long enough that a wrong language or a broken decoder shows up
     * as garbled words rather than a coin flip. Voice names are the macOS
     * defaults for each locale; a missing voice skips that language.
     */
    private val SYNTHETIC = listOf(
        Synth("de", "Anna", "Der Zug nach Hamburg fährt heute leider zwanzig Minuten später ab."),
        Synth("fr", "Jacques", "Je voudrais réserver une table pour quatre personnes ce soir."),
        Synth("es", "Mónica", "Mañana por la mañana tengo una reunión importante en la oficina."),
        Synth("uk", "Lesya", "Завтра вранці я поїду до Києва на важливу зустріч."),
    )

    private data class Synth(val lang: String, val voice: String, val text: String)

    /** The committed real-speech clip, or null if the asset is missing. */
    fun anchor(): Clip? {
        val wav = File(assetsDir, "jfk.wav")
        if (!wav.isFile) return null
        val reference = readReference("jfk.wav") ?: return null
        val pcm = File(cacheDir, "jfk.pcm")
        val samples = decodeWav(wav)
        if (!pcm.isFile || pcm.length() != samples.size * 4L) writePcm(samples, pcm)
        return Clip("jfk.wav", "en", reference, pcm, samples.size / SAMPLE_RATE.toDouble(), false)
    }

    /** Synthesized clips for every locale whose voice is installed. */
    fun synthetic(): List<Clip> = SYNTHETIC.mapNotNull { s ->
        val pcm = File(cacheDir, "say-${s.lang}.pcm")
        if (!pcm.isFile && !synthesize(s, pcm)) return@mapNotNull null
        val seconds = pcm.length() / 4.0 / SAMPLE_RATE
        Clip("say-${s.lang}", s.lang, s.text, pcm, seconds, true)
    }

    private fun installedVoices(): Set<String> = runCatching {
        ProcessBuilder("say", "-v", "?").start().let { p ->
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor()
            out.lines().mapNotNull { it.trim().substringBefore(' ').takeIf(String::isNotEmpty) }.toSet()
        }
    }.getOrDefault(emptySet())

    private val voices by lazy { installedVoices() }

    /** `say` -> AIFF -> 16 kHz mono f32; returns false if the voice is absent. */
    private fun synthesize(s: Synth, out: File): Boolean {
        if (s.voice !in voices) {
            System.err.println("  no macOS voice '${s.voice}' for ${s.lang} — skipping that language")
            return false
        }
        val aiff = File(out.parentFile, "${out.nameWithoutExtension}.aiff")
        val wav = File(out.parentFile, "${out.nameWithoutExtension}.wav")
        return runCatching {
            exec("say", "-v", s.voice, "-o", aiff.path, s.text)
            // afconvert, not a decoder of ours: AIFF is not what the engine
            // wants and resampling is not this harness's job.
            exec("afconvert", "-f", "WAVE", "-d", "LEI16@16000", "-c", "1", aiff.path, wav.path)
            writePcm(decodeWav(wav), out)
            aiff.delete(); wav.delete()
            true
        }.getOrElse {
            System.err.println("  could not synthesize ${s.lang}: ${it.message}")
            false
        }
    }

    private fun exec(vararg cmd: String) {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        check(p.waitFor() == 0) { "${cmd.first()} failed: ${out.take(200)}" }
    }

    private fun readReference(name: String): String? {
        val f = File(assetsDir, "transcripts.json")
        if (!f.isFile) return null
        // One flat object of {file: {lang, text, source}} — a regex keeps the
        // harness free of a JSON dependency for three fields.
        val block = Regex(""""${Regex.escape(name)}"\s*:\s*\{(.*?)\}""", RegexOption.DOT_MATCHES_ALL)
            .find(f.readText())?.groupValues?.get(1) ?: return null
        return Regex(""""text"\s*:\s*"(.*?)"""", RegexOption.DOT_MATCHES_ALL)
            .find(block)?.groupValues?.get(1)
    }

    /** Minimal 16-bit PCM WAV reader — enough for our own fixtures. */
    private fun decodeWav(file: File): FloatArray {
        val bytes = file.readBytes()
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(bytes.size > 44 && String(bytes, 0, 4) == "RIFF") { "${file.name} is not a WAV" }
        var pos = 12
        var dataOff = -1
        var dataLen = 0
        var bits = 16
        var channels = 1
        var rate = SAMPLE_RATE
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4)
            val size = bb.getInt(pos + 4)
            when (id) {
                "fmt " -> {
                    channels = bb.getShort(pos + 10).toInt()
                    rate = bb.getInt(pos + 12)
                    bits = bb.getShort(pos + 22).toInt()
                }
                "data" -> { dataOff = pos + 8; dataLen = size }
            }
            pos += 8 + size + (size and 1)
            if (dataOff >= 0) break
        }
        require(dataOff >= 0) { "${file.name} has no data chunk" }
        require(bits == 16 && channels == 1 && rate == SAMPLE_RATE) {
            "${file.name} must be 16-bit mono ${SAMPLE_RATE}Hz, got ${bits}-bit ${channels}ch ${rate}Hz"
        }
        val n = minOf(dataLen, bytes.size - dataOff) / 2
        return FloatArray(n) { bb.getShort(dataOff + it * 2) / 32768f }
    }

    private fun writePcm(samples: FloatArray, out: File) {
        val bb = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { bb.putFloat(it) }
        out.writeBytes(bb.array())
    }
}
