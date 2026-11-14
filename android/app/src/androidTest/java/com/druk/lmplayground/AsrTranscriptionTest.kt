package com.druk.lmplayground

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.druk.llamacpp.ILlamaService
import com.druk.llamacpp.ITranscriptionCallback
import com.druk.llamacpp.asr.WordErrorRate
import com.druk.lmplayground.inference.LlamaService
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Voice dictation end to end on real hardware: the Parakeet model loads through
 * the AIDL surface and transcribes audio handed over as a file descriptor.
 *
 * The clip and the reference transcript are the same ones the macOS harness
 * uses (`app/src/androidTest/assets/audio/`), and both grade with
 * [WordErrorRate], so "passes on the Mac" and "passes on the phone" mean the
 * same thing. What only this test can show is what the phone's CPU does with
 * it — accuracy is hardware-independent, speed very much is not.
 *
 * Setup: copy the dictation model into /data/local/tmp/, e.g.
 *   adb push tdt-0.6b-v3-q4_k.gguf /data/local/tmp/
 *   adb shell chmod 666 /data/local/tmp/tdt-0.6b-v3-q4_k.gguf
 */
@RunWith(AndroidJUnit4::class)
class AsrTranscriptionTest {

    companion object {
        private const val TAG = "AsrTranscriptionTest"
        private const val MODELS_PATH = "/data/local/tmp"
        private const val ASR_MODEL = "nemotron-3.5-asr-streaming-0.6b-q4_k.gguf"
        private const val CLIP = "audio/jfk.wav"
        private const val SAMPLE_RATE = 16_000

        /**
         * Real recorded speech, greedy TDT decoding. NVIDIA reports ~6% WER for
         * this checkpoint across the Open ASR corpus; above 15% on one clean
         * clip means the pipeline is broken, not that the model is imperfect.
         */
        private const val WER_BUDGET = 0.15

        private const val REFERENCE =
            "And so my fellow Americans, ask not what your country can do for you, " +
                "ask what you can do for your country."
    }

    private lateinit var context: Context
    private var service: ILlamaService? = null
    private var connection: ServiceConnection? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        assumeTrue(
            "No dictation model at $MODELS_PATH/$ASR_MODEL",
            File(MODELS_PATH, ASR_MODEL).let { it.exists() && it.canRead() },
        )
        service = bindServiceBlocking()
        assertNotNull("Failed to bind LlamaService", service)
        service!!.initBackend()
    }

    @After
    fun tearDown() {
        try {
            service?.unloadAsrModel()
        } catch (_: Throwable) {
        }
        connection?.let { context.unbindService(it) }
        connection = null
        service = null
    }

    @Test(timeout = 300_000)
    fun loadAsrModel_reportsLoaded() {
        val service = service!!
        assertFalse("no model should be loaded yet", service.isAsrModelLoaded)
        assertTrue(
            "loadAsrModel failed for $ASR_MODEL",
            service.loadAsrModel(File(MODELS_PATH, ASR_MODEL).absolutePath, null),
        )
        assertTrue(service.isAsrModelLoaded)

        service.unloadAsrModel()
        // Unload claims the slot synchronously even though the native free is
        // queued behind the worker thread.
        assertFalse(service.isAsrModelLoaded)
    }

    @Test(timeout = 600_000)
    fun transcribe_realSpeech_isWithinWerBudget() {
        val service = service!!
        val loadStart = System.currentTimeMillis()
        assertTrue(service.loadAsrModel(File(MODELS_PATH, ASR_MODEL).absolutePath, null))
        val loadMs = System.currentTimeMillis() - loadStart

        val samples = decodeWavAsset(CLIP)
        val seconds = samples.size / SAMPLE_RATE.toDouble()
        val pcm = writePcm(samples)

        val started = System.currentTimeMillis()
        val transcript = transcribeBlocking(pcm)
        val ms = System.currentTimeMillis() - started
        pcm.delete()

        assertNotNull("transcription produced no result", transcript)
        val wer = WordErrorRate.of(REFERENCE, transcript!!)
        // Speed is reported, never asserted: it is the whole point of running
        // on real hardware, but it is not a correctness property.
        Log.i(
            TAG,
            "WER ${WordErrorRate.percent(wer)} | ${ms}ms for ${"%.1f".format(seconds)}s " +
                "(${"%.2f".format(ms / 1000.0 / seconds)}x realtime) | load ${loadMs}ms",
        )
        Log.i(TAG, "heard: $transcript")
        assertTrue(
            "WER ${WordErrorRate.percent(wer)} exceeds the " +
                "${WordErrorRate.percent(WER_BUDGET)} budget — heard: $transcript",
            wer <= WER_BUDGET,
        )
    }

    /** Streaming throughput at the current thread setting. */
    @Test(timeout = 900_000)
    fun streaming_throughput() {
        val service = service!!
        assertTrue(service.loadAsrModel(File(MODELS_PATH, ASR_MODEL).absolutePath, null))
        val samples = decodeWavAsset(CLIP)
        val seconds = samples.size / SAMPLE_RATE.toDouble()
        val streamId = service.startDictationStream("en")
        assertTrue("not a streaming checkpoint", streamId > 0)

        val slice = SAMPLE_RATE * 640 / 1000
        val heard = StringBuilder()
        var firstMs = -1L
        val t0 = System.currentTimeMillis()
        var off = 0
        while (off < samples.size) {
            val n = minOf(slice, samples.size - off)
            val d = service.feedDictationAudio(streamId, samples.copyOfRange(off, off + n))
            if (!d.isNullOrEmpty()) {
                if (firstMs < 0) firstMs = System.currentTimeMillis() - t0
                heard.append(d)
            }
            off += n
        }
        service.finishDictationStream(streamId)?.let { heard.append(it) }
        val total = System.currentTimeMillis() - t0
        Log.i(
            TAG,
            "THROUGHPUT total=${total}ms rt=${"%.2f".format(total / 1000.0 / seconds)}x " +
                "firstText=${firstMs}ms wer=" +
                WordErrorRate.percent(WordErrorRate.of(REFERENCE, heard.toString().trim())),
        )
    }

    @Test(timeout = 180_000)
    fun transcribe_withoutModel_reportsError() {
        val error = AtomicReference<String?>(null)
        val latch = CountDownLatch(1)
        val pcm = writePcm(FloatArray(SAMPLE_RATE) { 0f })
        ParcelFileDescriptor.open(pcm, ParcelFileDescriptor.MODE_READ_ONLY).use {
            service!!.transcribe(it, "auto", object : ITranscriptionCallback.Stub() {
                override fun onTranscription(text: String) = latch.countDown()
                override fun onTranscriptionError(message: String) {
                    error.set(message)
                    latch.countDown()
                }
            })
            assertTrue("callback never fired", latch.await(30, TimeUnit.SECONDS))
        }
        pcm.delete()
        assertEquals("ASR model not loaded", error.get())
    }

    private fun transcribeBlocking(pcmFile: File, timeoutSec: Long = 480): String? {
        val result = AtomicReference<String?>(null)
        val latch = CountDownLatch(1)
        ParcelFileDescriptor.open(pcmFile, ParcelFileDescriptor.MODE_READ_ONLY).use {
            service!!.transcribe(it, "auto", object : ITranscriptionCallback.Stub() {
                override fun onTranscription(text: String) {
                    result.set(text)
                    latch.countDown()
                }

                override fun onTranscriptionError(message: String) {
                    Log.e(TAG, "transcription error: $message")
                    latch.countDown()
                }
            })
            assertTrue("transcription timed out", latch.await(timeoutSec, TimeUnit.SECONDS))
        }
        return result.get()
    }

    /** Writes the engine's input format: little-endian f32, 16 kHz mono. */
    private fun writePcm(samples: FloatArray): File {
        val file = File(context.cacheDir, "asr-test.pcm")
        val bb = ByteBuffer.allocate(samples.size * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { bb.putFloat(it) }
        file.writeBytes(bb.array())
        return file
    }

    /** Minimal 16-bit PCM WAV reader for our own committed fixtures. */
    private fun decodeWavAsset(name: String): FloatArray {
        val bytes = InstrumentationRegistry.getInstrumentation().context.assets
            .open(name).use { it.readBytes() }
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 12
        var dataOff = -1
        var dataLen = 0
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4)
            val size = bb.getInt(pos + 4)
            if (id == "data") { dataOff = pos + 8; dataLen = size; break }
            pos += 8 + size + (size and 1)
        }
        assertTrue("$name has no data chunk", dataOff >= 0)
        val n = minOf(dataLen, bytes.size - dataOff) / 2
        return FloatArray(n) { bb.getShort(dataOff + it * 2) / 32768f }
    }

    private fun bindServiceBlocking(timeoutMs: Long = 5_000): ILlamaService? {
        val latch = CountDownLatch(1)
        var bound: ILlamaService? = null
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                bound = ILlamaService.Stub.asInterface(binder)
                latch.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName) {
                bound = null
            }
        }
        connection = conn
        context.bindService(
            Intent(context, LlamaService::class.java),
            conn,
            Context.BIND_AUTO_CREATE,
        )
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        return bound
    }
}
