package com.druk.lmplayground

import android.content.ComponentName
import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.druk.llamacpp.InferenceClient
import com.druk.llamacpp.InferenceLimits
import com.druk.llamacpp.InferenceState
import com.druk.llamacpp.LlamaCpp
import com.druk.llamacpp.LlamaGenerationCallback
import com.druk.llamacpp.LlamaModel
import com.druk.llamacpp.LlamaProgressCallback
import com.druk.llamacpp.PayloadTooLargeException
import com.druk.lmplayground.inference.LlamaService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Verifies the proxy enforces the 700 KB AIDL payload ceiling AND that
 * the chunked replayHistory path can carry a chat history far larger than
 * a single binder transaction (which would otherwise blow up with
 * TransactionTooLargeException).
 */
@RunWith(AndroidJUnit4::class)
class InferenceLimitsTest {

    companion object {
        private const val MODELS_PATH = "/data/local/tmp"
        private val CANDIDATE_MODELS = listOf(
            "LFM2.5-350M-Q4_K_M.gguf",
            "Qwen3-0.6B-Q4_K_M.gguf",
            "Qwen_Qwen3.5-0.8B-Q3_K_M.gguf",
        )
    }

    private lateinit var client: InferenceClient
    private lateinit var llamaCpp: LlamaCpp

    @Before
    fun setUp() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        client = InferenceClient(
            appContext = ctx,
            serviceComponent = ComponentName(ctx, LlamaService::class.java),
        )
        client.bind()
        runBlocking {
            withTimeout(5_000) { client.state.first { it is InferenceState.Connected } }
        }
        llamaCpp = LlamaCpp(client)
    }

    @After
    fun tearDown() = client.unbind()

    private fun findModel(): File? = CANDIDATE_MODELS
        .map { File(MODELS_PATH, it) }
        .firstOrNull { it.exists() && it.canRead() }

    /**
     * Get a model file or skip the test gracefully if no GGUF is staged
     * in `/data/local/tmp` — keeps these tests usable on devices without
     * the test fixture installed (matches the pattern in
     * ModelGenerationTest et al.).
     */
    private fun requireModel(): File {
        val f = findModel()
        assumeNotNull(
            "No GGUF in $MODELS_PATH (run `adb push ... /data/local/tmp` first)",
            f,
        )
        return f!!
    }

    private fun loadModel(): LlamaModel {
        val pfd = ParcelFileDescriptor.open(requireModel(), ParcelFileDescriptor.MODE_READ_ONLY)
        return llamaCpp.loadModel(pfd, object : LlamaProgressCallback {
            override fun onProgress(progress: Float) {}
        })
    }

    private fun openSession(model: LlamaModel, systemPrompt: String = "") =
        model.createSession(2048, 0.8f, 0.95f, 1.0f, 40, 0.05f, -1, -1, systemPrompt)!!

    /**
     * `addMessage` with a single payload above the 700 KB cap must throw
     * [PayloadTooLargeException] *before* hitting the binder. This is the
     * critical guarantee — without it the user would see a hard
     * TransactionTooLargeException and possibly destabilize unrelated
     * binder traffic in the same process.
     */
    @Test(timeout = 60_000)
    fun addMessage_aboveLimit_throwsPayloadTooLarge() {
        val model = loadModel()
        try {
            val session = openSession(model)
            try {
                val oversized = "x".repeat(InferenceLimits.MAX_PAYLOAD_BYTES + 1)
                try {
                    session.addMessage(oversized, false)
                    fail("Expected PayloadTooLargeException for ${oversized.length}-byte message")
                } catch (e: PayloadTooLargeException) {
                    assertTrue(
                        "Exception message should mention the limit",
                        e.message!!.contains("700"),
                    )
                }
            } finally {
                session.destroy()
            }
        } finally {
            model.unloadModel()
        }
    }

    /**
     * Same guard for `createSession`'s `systemPrompt` parameter. We don't
     * even create the session — fail fast, no binder traffic.
     */
    @Test(timeout = 60_000)
    fun createSession_oversizedSystemPrompt_throwsPayloadTooLarge() {
        val model = loadModel()
        try {
            val oversized = "y".repeat(InferenceLimits.MAX_PAYLOAD_BYTES + 1)
            try {
                openSession(model, systemPrompt = oversized)
                fail("Expected PayloadTooLargeException for oversized system prompt")
            } catch (e: PayloadTooLargeException) {
                assertTrue(e.message!!.contains("system prompt"))
            }
        } finally {
            model.unloadModel()
        }
    }

    /**
     * The chunked replay path must succeed with a chat history *much*
     * larger than the binder transaction cap. We synthesize ~2 MB of
     * history (4× the per-message ceiling) and verify the session is
     * usable afterward — i.e. all chunks landed and finalize wired
     * everything into the native session.
     */
    @Test(timeout = 240_000)
    fun replayHistory_largeHistory_succeedsViaChunking() {
        val model = loadModel()
        try {
            val session = openSession(model)
            try {
                // 5 turns × ~340 KB chars each. Each message serializes to
                // ~680 KB on the binder wire (UTF-16 = 2 B/char), safely
                // under the 700 KB per-string cap. Total across all 10
                // AIDL calls is ~6.8 MB — many times the 1 MB binder
                // transaction ceiling, so this *only* succeeds because
                // each turn ships in its own transaction.
                val chunkChars = 340 * 1024
                val turns = 5
                val users = Array(turns) { i -> "user $i: " + "u".repeat(chunkChars) }
                val assistants = Array(turns) { i -> "assistant $i: " + "a".repeat(chunkChars) }
                val totalBinderBytes = (users + assistants).sumOf { it.length * 2 }
                assertTrue(
                    "Test should send > 4 MB to genuinely exercise chunking",
                    totalBinderBytes > 4_000_000,
                )

                // Replay through the chunked AIDL path.
                session.replayHistory(users, assistants)

                // Session should still be usable: send a real prompt and
                // verify generation streams something back.
                session.addMessage("Reply with the word ok.", false)
                var response = ""
                runBlocking {
                    session.generateAll(object : LlamaGenerationCallback {
                        override fun onFullResponse(r: String) { response = r }
                    })
                }
                assertTrue(
                    "Generation after large replay should produce output",
                    response.isNotBlank(),
                )
            } finally {
                session.destroy()
            }
        } finally {
            model.unloadModel()
        }
    }

    /**
     * Regression for the UTF-16 vs UTF-8 fix: an ASCII string that's
     * under 700 KB in UTF-8 but OVER 700 KB on the binder wire (because
     * Parcel serializes String as UTF-16) must be rejected by the proxy.
     * Before the fix, this would have slipped past the guard and thrown
     * TransactionTooLargeException at the kernel boundary, possibly
     * destabilizing unrelated binder traffic in the same process.
     */
    @Test(timeout = 60_000)
    fun addMessage_asciiAboveUtf16Boundary_throwsPayloadTooLarge() {
        val model = loadModel()
        try {
            val session = openSession(model)
            try {
                // 400 KB chars of ASCII = 400 KB in UTF-8, but 800 KB on
                // the binder wire (UTF-16). Above the 700 KB cap.
                val ascii = "x".repeat(400 * 1024)
                val utf8Bytes = ascii.toByteArray(Charsets.UTF_8).size
                val utf16Bytes = ascii.length * 2
                assertTrue(
                    "Test setup: must be < cap in UTF-8 ($utf8Bytes) and > cap in UTF-16 ($utf16Bytes)",
                    utf8Bytes < InferenceLimits.MAX_PAYLOAD_BYTES &&
                        utf16Bytes > InferenceLimits.MAX_PAYLOAD_BYTES,
                )
                try {
                    session.addMessage(ascii, false)
                    fail("Expected PayloadTooLargeException for ASCII payload at the UTF-16 boundary")
                } catch (e: PayloadTooLargeException) {
                    assertNotNull(e.message)
                }
            } finally {
                session.destroy()
            }
        } finally {
            model.unloadModel()
        }
    }

    /**
     * Per-pair validation: if any single message in the history exceeds
     * the cap, the proxy must reject before sending any AIDL calls
     * (don't half-replay then explode).
     */
    @Test(timeout = 60_000)
    fun replayHistory_singleOversizedMessage_rejectedBeforeAidl() {
        val model = loadModel()
        try {
            val session = openSession(model)
            try {
                val users = arrayOf(
                    "ok message",
                    "x".repeat(InferenceLimits.MAX_PAYLOAD_BYTES + 1),
                )
                val assistants = arrayOf("ok reply", "ok reply")
                try {
                    session.replayHistory(users, assistants)
                    fail("Expected PayloadTooLargeException for oversized history entry")
                } catch (e: PayloadTooLargeException) {
                    assertNotNull(e.message)
                }
            } finally {
                session.destroy()
            }
        } finally {
            model.unloadModel()
        }
    }
}
