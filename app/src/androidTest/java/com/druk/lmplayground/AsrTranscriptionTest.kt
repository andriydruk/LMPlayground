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
import com.druk.llamacpp.ILlamaService
import com.druk.llamacpp.ITranscriptionCallback
import com.druk.lmplayground.inference.LlamaService
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.PI
import kotlin.math.sin
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
 * End-to-end instrumented test for the voice-dictation path: loads the Parakeet
 * GGUF through the AIDL surface and transcribes raw PCM handed over as a file
 * descriptor.
 *
 * Setup: copy the dictation model into /data/local/tmp/, e.g.
 *   adb push tdt-0.6b-v3-q4_k.gguf /data/local/tmp/
 *   adb shell chmod 666 /data/local/tmp/tdt-0.6b-v3-q4_k.gguf
 *
 * To assert on real words, also push a 16 kHz mono f32 recording of a known
 * phrase as speech.pcm (see [SPEECH_PCM]); without it the test still exercises
 * load → transcribe → unload using synthesized audio.
 */
@RunWith(AndroidJUnit4::class)
class AsrTranscriptionTest {

    companion object {
        private const val TAG = "AsrTranscriptionTest"
        private const val MODELS_PATH = "/data/local/tmp"
        private const val ASR_MODEL = "tdt-0.6b-v3-q4_k.gguf"

        /** Optional: raw 16 kHz mono little-endian f32 PCM of a spoken phrase. */
        private const val SPEECH_PCM = "speech.pcm"

        /** Words the recording in [SPEECH_PCM] is expected to contain. */
        private val EXPECTED_WORDS = listOf("capital", "france")

        private const val SAMPLE_RATE = 16_000
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

    @Test(timeout = 180_000)
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

    @Test(timeout = 300_000)
    fun transcribe_returnsTextForSpokenAudio() {
        val service = service!!
        assertTrue(service.loadAsrModel(File(MODELS_PATH, ASR_MODEL).absolutePath, null))

        val speech = File(MODELS_PATH, SPEECH_PCM)
        val hasSpeech = speech.exists() && speech.canRead()
        val pcmFile = if (hasSpeech) speech else synthesizeTone()

        val transcript = transcribeBlocking(pcmFile)
        assertNotNull("transcription produced no result", transcript)
        Log.i(TAG, "transcript: '$transcript'")

        if (hasSpeech) {
            val lower = transcript!!.lowercase()
            EXPECTED_WORDS.forEach {
                assertTrue("expected '$it' in transcript '$transcript'", lower.contains(it))
            }
        }
        // Synthesized audio has no words in it: reaching a result at all proves
        // the PCM crossed the fd, the encoder ran and the decoder returned.
    }

    @Test(timeout = 180_000)
    fun transcribe_withoutModel_reportsError() {
        val error = AtomicReference<String?>(null)
        val latch = CountDownLatch(1)
        val pcmFd = ParcelFileDescriptor.open(
            synthesizeTone(),
            ParcelFileDescriptor.MODE_READ_ONLY,
        )
        pcmFd.use {
            service!!.transcribe(it, "auto", object : ITranscriptionCallback.Stub() {
                override fun onTranscription(text: String) = latch.countDown()
                override fun onTranscriptionError(message: String) {
                    error.set(message)
                    latch.countDown()
                }
            })
            assertTrue("callback never fired", latch.await(30, TimeUnit.SECONDS))
        }
        assertEquals("ASR model not loaded", error.get())
    }

    private fun transcribeBlocking(pcmFile: File, timeoutSec: Long = 240): String? {
        val result = AtomicReference<String?>(null)
        val latch = CountDownLatch(1)
        val pcmFd = ParcelFileDescriptor.open(pcmFile, ParcelFileDescriptor.MODE_READ_ONLY)
        pcmFd.use {
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

    /** Two seconds of a quiet 440 Hz tone in the engine's input format. */
    private fun synthesizeTone(): File {
        val file = File(context.cacheDir, "asr-test-tone.pcm")
        val samples = SAMPLE_RATE * 2
        val buffer = ByteBuffer.allocate(samples * Float.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until samples) {
            buffer.putFloat((0.05 * sin(2.0 * PI * 440.0 * i / SAMPLE_RATE)).toFloat())
        }
        file.writeBytes(buffer.array())
        return file
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
