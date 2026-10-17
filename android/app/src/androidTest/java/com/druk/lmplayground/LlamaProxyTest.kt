package com.druk.lmplayground

import android.content.ComponentName
import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.BufferedReader
import java.io.InputStreamReader
import com.druk.llamacpp.InferenceClient
import com.druk.llamacpp.InferenceState
import com.druk.llamacpp.LlamaCpp
import com.druk.llamacpp.LlamaGenerationCallback
import com.druk.llamacpp.LlamaProgressCallback
import com.druk.lmplayground.inference.LlamaService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotEquals
import org.junit.Assume.assumeNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * End-to-end test of the **proxy** API surface (LlamaCpp / LlamaModel /
 * LlamaGenerationSession) — the same classes that ConversationViewModel
 * uses. Exercises proxy → AIDL → LlamaService → JNI.
 *
 * If this passes, the only thing the UI ConversationViewModel adds on top
 * is Compose state plumbing, which is independent of the IPC change.
 */
@RunWith(AndroidJUnit4::class)
class LlamaProxyTest {

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
            withTimeout(5_000) {
                client.state.first { it is InferenceState.Connected }
            }
        }
        llamaCpp = LlamaCpp(client)
    }

    @After
    fun tearDown() {
        client.unbind()
        // Force-stop :llama between tests so the next test doesn't
        // inherit Android's exponential-backoff penalty for repeat
        // crashes in this session. Without this, two crash tests in a
        // row can flake because the OS schedules the second restart
        // 10–30 s out, exceeding the test's polling window.
        try {
            val pkg = ApplicationProvider.getApplicationContext<Context>().packageName
            val pfd = InstrumentationRegistry.getInstrumentation()
                .uiAutomation.executeShellCommand("am kill $pkg:llama")
            ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
        } catch (_: Throwable) { /* best effort */ }
        Thread.sleep(500)
    }

    private fun findModel(): File? = CANDIDATE_MODELS
        .map { File(MODELS_PATH, it) }
        .firstOrNull { it.exists() && it.canRead() }

    /**
     * Crash-isolation test: bind to the inference engine, kill the :llama
     * process from underneath it via the debug-only crashForTest() AIDL
     * method, and verify that
     *  (a) the test process (i.e. the app process) survives, and
     *  (b) InferenceClient observes the binder death and transitions to Crashed.
     *  (c) acknowledgeCrash() recovers using the auto-rebound service.
     *
     * This is the core proof-of-value for the whole refactor — a SIGSEGV in
     * llama.cpp must NOT take the UI down.
     */
    // Android's bound-service restart logic uses exponential backoff
    // ("Scheduling restart of crashed service in 8110ms for connection")
    // so the second and third crash tests in a session may wait a while
    // before the OS actually re-spawns :llama. Tests need generous
    // polling windows; production users only see one crash so they
    // never notice.
    @Test(timeout = 60_000)
    fun crashLlamaProcess_appSurvives_andStateGoesCrashed() {
        val pkg = ApplicationProvider.getApplicationContext<Context>().packageName
        val pidBefore = pidOf("$pkg:llama")
        assertTrue("Expected $pkg:llama to be running before the crash", pidBefore > 0)

        // Trigger Process.killProcess(myPid) inside :llama via AIDL.
        // The AIDL transaction itself raises DeadObject because the
        // process dies mid-call — that's fine.
        val svc = (client.state.value as InferenceState.Connected).service
        try { svc.crashForTest() } catch (_: Throwable) { /* expected */ }

        // Wait for the death recipient → Crashed (sticky, so first will see it
        // even if we observe slightly after the transition).
        val crashed = runBlocking {
            withTimeoutOrNull(10_000) {
                client.state.first { it is InferenceState.Crashed }
            }
        }
        assertNotNull("InferenceClient should observe Crashed within 10s", crashed)

        // The app process is still alive (we just executed code inside it).
        val mainPid = pidOf(pkg)
        assertTrue("Main app process should still be alive", mainPid > 0)

        // Wait for Android to auto-restart the bound service. The OS
        // applies exponential backoff (~8s on the second crash in a
        // session, longer thereafter), so the polling window has to
        // cover the worst case.
        runBlocking {
            withTimeoutOrNull(30_000) {
                while (pidOf("$pkg:llama") == pidBefore || pidOf("$pkg:llama") <= 0) {
                    kotlinx.coroutines.delay(200)
                }
            }
        }
        val pidAfter = pidOf("$pkg:llama")
        assertTrue("New :llama pid should be present after auto-restart", pidAfter > 0)
        assertNotEquals("New pid must differ from the killed one", pidBefore, pidAfter)

        // Recovery: acknowledgeCrash() returns the new service handle if
        // Android has already re-bound, otherwise it leaves us in Connecting
        // and the next Connected transition has the fresh service. Wait
        // for whichever lands first.
        val recovered = runBlocking {
            val ack = client.acknowledgeCrash()
            ack ?: withTimeoutOrNull(20_000) {
                // Android applies exponential backoff to service restarts
                // when a service has crashed recently — the rebind can take
                // up to ~10s on the second crash in a session.
                (client.state.first { it is InferenceState.Connected } as InferenceState.Connected).service
            }
        }
        assertNotNull("Should have a usable service after recovery", recovered)
        assertTrue("Recovered service should respond", recovered!!.systemInfo().isNotBlank())
    }

    private fun pidOf(processName: String): Int {
        val out = execShell("pidof $processName").trim()
        return out.split(Regex("\\s+")).firstOrNull()?.toIntOrNull() ?: -1
    }

    private fun execShell(cmd: String): String {
        val pfd = InstrumentationRegistry.getInstrumentation()
            .uiAutomation.executeShellCommand(cmd)
        return BufferedReader(
            InputStreamReader(ParcelFileDescriptor.AutoCloseInputStream(pfd))
        ).use { it.readText() }
    }

    /**
     * Full recovery flow: load a model, run generation, crash :llama mid-flight,
     * acknowledge the crash, load the model AGAIN, run another generation,
     * and verify the second generation streams successfully.
     *
     * This is the integration test for what the user actually does after
     * a crash: reload the model and keep going.
     */
    // Same Android backoff caveat as crashLlamaProcess_*. We don't try
    // to keep this test fast — the recovery flow has to be patient
    // enough to cover the OS-imposed backoff that real users hit too.
    @Test(timeout = 300_000)
    fun crashThenReload_secondGenerationSucceeds() {
        val modelFile = findModel()
        assumeNotNull("No model in $MODELS_PATH", modelFile)

        // ── First load + start a generation we'll deliberately interrupt ──
        val pfd1 = ParcelFileDescriptor.open(modelFile!!, ParcelFileDescriptor.MODE_READ_ONLY)
        val model1 = llamaCpp.loadModel(pfd1, object : LlamaProgressCallback {
            override fun onProgress(progress: Float) {}
        })
        val session1 = model1.createSession(
            2048, 0.8f, 0.95f, 1.0f, 40, 0.05f, -1, -1, "",
        )!!
        session1.addMessage("Tell me a long story about dragons.", false)

        // Crash :llama from underneath — the active generation explodes.
        val svc = (client.state.value as InferenceState.Connected).service
        try { svc.crashForTest() } catch (_: Throwable) { /* expected */ }

        // Sticky Crashed. Death recipient fires nearly immediately after
        // the process actually exits, but Android may take up to a few
        // seconds to deliver the binder death notice under load.
        val crashed = runBlocking {
            withTimeoutOrNull(20_000) {
                client.state.first { it is InferenceState.Crashed }
            }
        }
        assertNotNull("Should observe Crashed", crashed)

        // ── User-recovery flow: acknowledge then reload ──
        // Same flow as ConversationViewModel.loadModel uses: ack the crash,
        // and if Android hasn't re-bound yet, suspend until the next
        // Connected lands.
        val recovered = runBlocking {
            val ack = client.acknowledgeCrash()
            ack ?: withTimeoutOrNull(20_000) {
                // Android applies exponential backoff to service restarts
                // when a service has crashed recently — the rebind can take
                // up to ~10s on the second crash in a session.
                (client.state.first { it is InferenceState.Connected } as InferenceState.Connected).service
            }
        }
        assertNotNull("Should have a usable service after recovery", recovered)

        // The OLD model/session handles are stale — drop them. The user's
        // ConversationViewModel does this in onInferenceCrashed.
        // (We don't call destroy()/unloadModel() — those would throw
        // InferenceUnavailableException; the service is fresh.)

        // ── Second load + generation ──
        val pfd2 = ParcelFileDescriptor.open(modelFile, ParcelFileDescriptor.MODE_READ_ONLY)
        val model2 = llamaCpp.loadModel(pfd2, object : LlamaProgressCallback {
            override fun onProgress(progress: Float) {}
        })
        try {
            val session2 = model2.createSession(
                2048, 0.8f, 0.95f, 1.0f, 40, 0.05f, -1, -1, "",
            )!!
            try {
                session2.addMessage("Say hello in one short sentence.", false)
                var lastFull = ""
                val rc = runBlocking {
                    session2.generateAll(object : LlamaGenerationCallback {
                        override fun onFullResponse(response: String) { lastFull = response }
                    })
                }
                assertTrue(
                    "Second generation should produce text after recovery (rc=$rc)",
                    lastFull.isNotBlank(),
                )
            } finally {
                session2.destroy()
            }
        } finally {
            model2.unloadModel()
        }
    }

    @Test(timeout = 240_000)
    fun proxyLoadAndGenerate_endToEnd() {
        val modelFile = findModel()
        assumeNotNull("No model in $MODELS_PATH", modelFile)

        // Exercise the production path: PFD across the binder. The local
        // PFD is dup'd into the service process; the service uses its own
        // FD to construct the `fd:N` string for ggml_fopen_override.
        val pfd = ParcelFileDescriptor.open(modelFile!!, ParcelFileDescriptor.MODE_READ_ONLY)
        val model = llamaCpp.loadModel(pfd, object : LlamaProgressCallback {
            override fun onProgress(progress: Float) {}
        })
        try {
            assertTrue("Model size should be > 0", model.getModelSize() > 0)

            val session = model.createSession(
                contextSize = 2048,
                temperature = 0.8f,
                topP = 0.95f,
                repetitionPenalty = 1.0f,
                topK = 40,
                minP = 0.05f,
                seed = -1,
                thinkingBudget = -1,
                systemPrompt = "",
            )
            assertNotNull("createSession returned null", session)

            try {
                session!!.addMessage("Say hello in one short sentence.", false)

                var lastFull = ""
                val callback = object : LlamaGenerationCallback {
                    override fun onFullResponse(response: String) { lastFull = response }
                }
                val rc = runBlocking { session.generateAll(callback) }

                assertTrue(
                    "Accumulated proxy response should be non-empty (rc=$rc)",
                    lastFull.isNotBlank(),
                )
            } finally {
                session?.destroy()
            }
        } finally {
            model.unloadModel()
        }
    }

    private fun findVisionPair(modelName: String, mmprojName: String): Pair<File, File>? {
        val m = File(MODELS_PATH, modelName)
        val p = File(MODELS_PATH, mmprojName)
        return if (m.exists() && m.canRead() && p.exists() && p.canRead()) m to p else null
    }

    private fun loadAssetBytes(name: String): ByteArray =
        InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { it.readBytes() }

    /**
     * Service-path vision regression: Qwen 3.5 (M-RoPE / Qwen3VL) image input
     * through proxy → AIDL → LlamaService → GenerationWorker → native mtmd —
     * the exact path ConversationViewModel uses (minus the photo picker).
     *
     * Guards the bug where the image-conditioned LLM decode bled onto Vulkan0
     * and the Mali Vulkan backend failed the graph (compute status 1 → garbage
     * tokens). The LLM must run CPU-only; Vulkan is reserved for the CLIP
     * encoder. Pre-fix this produced symbol/digit garbage; post-fix it produces
     * coherent text describing the image.
     */
    @Test(timeout = 300_000)
    fun proxyVisionGenerate_qwen_endToEnd() {
        val pair = findVisionPair(
            "Qwen_Qwen3.5-0.8B-Q3_K_M.gguf",
            "mmproj-Qwen_Qwen3.5-0.8B-f16.gguf",
        )
        assumeNotNull("No Qwen 3.5 vision model + mmproj in $MODELS_PATH", pair)
        val (modelFile, mmprojFile) = pair!!

        val pfd = ParcelFileDescriptor.open(modelFile, ParcelFileDescriptor.MODE_READ_ONLY)
        val model = llamaCpp.loadModel(pfd, object : LlamaProgressCallback {
            override fun onProgress(progress: Float) {}
        })
        try {
            val visionOk = model.loadMmprojModel(mmprojFile.absolutePath)
            assertTrue("loadMmprojModel should report vision support", visionOk)
            assertTrue("supportsVision() should be true", model.supportsVision())

            // Greedy for determinism so the coherence check is stable.
            val session = model.createSession(
                contextSize = 4096,
                temperature = 0.0f,
                topP = 1.0f,
                repetitionPenalty = 1.0f,
                topK = 0,
                minP = 0.0f,
                seed = 0,
                thinkingBudget = -1,
                systemPrompt = "",
            )
            assertNotNull("createSession returned null", session)
            try {
                session!!.setImageData(loadAssetBytes("test_cat.jpg"))
                session.addMessage("What animal is in this image? Answer in one word.", false)

                var lastFull = ""
                val rc = runBlocking {
                    session.generateAll(object : LlamaGenerationCallback {
                        override fun onFullResponse(response: String) { lastFull = response }
                    })
                }
                android.util.Log.d(
                    "LlamaProxyTest",
                    "Qwen vision response (rc=$rc, ${lastFull.length} chars): $lastFull",
                )

                assertTrue("Vision response should be non-empty (rc=$rc)", lastFull.isNotBlank())
                // Pre-fix the failed Vulkan decode produced symbol/digit garbage
                // with almost no letters/spaces; coherent text is mostly both.
                val coherent =
                    lastFull.count { it.isLetter() || it.isWhitespace() }.toFloat() / lastFull.length
                assertTrue(
                    "Vision response looks like garbage (letter+space ratio=$coherent): \"$lastFull\"",
                    coherent > 0.6f,
                )
            } finally {
                session?.destroy()
            }
        } finally {
            model.unloadModel()
        }
    }

    /**
     * Proves vision is independent of the text-model quantization: the QAT
     * Q4_0 Gemma 4 E2B uses the SAME (quant-independent) BF16 mmproj as the
     * Q4_K_M build. Confirms q4_0 supports vision — the catalog default just
     * lacks the mmproj wiring, not a quantization limitation.
     */
    @Test(timeout = 300_000)
    fun proxyVisionGenerate_gemma4Qat_endToEnd() {
        val pair = findVisionPair(
            "gemma-4-E2B_q4_0-it.gguf",
            "mmproj-gemma-4-E2B-it-BF16.gguf",
        )
        assumeNotNull("No Gemma 4 E2B q4_0 model + BF16 mmproj in $MODELS_PATH", pair)
        val (modelFile, mmprojFile) = pair!!

        val pfd = ParcelFileDescriptor.open(modelFile, ParcelFileDescriptor.MODE_READ_ONLY)
        val model = llamaCpp.loadModel(pfd, object : LlamaProgressCallback {
            override fun onProgress(progress: Float) {}
        })
        try {
            val visionOk = model.loadMmprojModel(mmprojFile.absolutePath)
            assertTrue("q4_0 Gemma 4 should support vision with the BF16 mmproj", visionOk)
            assertTrue("supportsVision() should be true", model.supportsVision())

            val session = model.createSession(
                contextSize = 4096,
                temperature = 0.0f,
                topP = 1.0f,
                repetitionPenalty = 1.0f,
                topK = 0,
                minP = 0.0f,
                seed = 0,
                thinkingBudget = -1,
                systemPrompt = "",
            )
            assertNotNull("createSession returned null", session)
            try {
                session!!.setImageData(loadAssetBytes("test_cat.jpg"))
                session.addMessage("What animal is in this image? Answer in one word.", false)
                var lastFull = ""
                val rc = runBlocking {
                    session.generateAll(object : LlamaGenerationCallback {
                        override fun onFullResponse(response: String) { lastFull = response }
                    })
                }
                android.util.Log.d(
                    "LlamaProxyTest",
                    "Gemma4-q4_0 vision response (rc=$rc, ${lastFull.length} chars): $lastFull",
                )
                assertTrue("Vision response should be non-empty (rc=$rc)", lastFull.isNotBlank())
                val coherent =
                    lastFull.count { it.isLetter() || it.isWhitespace() }.toFloat() / lastFull.length
                assertTrue(
                    "Vision response looks like garbage (ratio=$coherent): \"$lastFull\"",
                    coherent > 0.6f,
                )
            } finally {
                session?.destroy()
            }
        } finally {
            model.unloadModel()
        }
    }
}
