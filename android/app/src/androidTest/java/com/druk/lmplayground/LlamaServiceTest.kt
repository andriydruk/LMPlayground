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
import com.druk.llamacpp.ILlamaGenerationCallback
import com.druk.llamacpp.ILlamaProgressCallback
import com.druk.llamacpp.ILlamaService
import com.druk.llamacpp.SamplerParams
import com.druk.lmplayground.inference.LlamaService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * End-to-end instrumented test for LlamaService.
 *
 * Binds to ILlamaService, loads a real GGUF model, runs a generation,
 * and verifies that delta callbacks stream back through the AIDL surface.
 *
 * Setup: copy a model into /data/local/tmp/, e.g.
 *   adb shell cp /sdcard/Models/Qwen3-0.6B-Q4_K_M.gguf /data/local/tmp/
 *   adb shell chmod 666 /data/local/tmp/Qwen3-0.6B-Q4_K_M.gguf
 */
@RunWith(AndroidJUnit4::class)
class LlamaServiceTest {

    companion object {
        private const val TAG = "LlamaServiceTest"
        private const val MODELS_PATH = "/data/local/tmp"

        // Smallest model first — we just want to validate the AIDL surface
        private val CANDIDATE_MODELS = listOf(
            "LFM2.5-350M-Q4_K_M.gguf",
            "Qwen3-0.6B-Q4_K_M.gguf",
            "Qwen_Qwen3.5-0.8B-Q3_K_M.gguf",
        )

        private val DEFAULT_PARAMS = SamplerParams(
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
    }

    private lateinit var context: Context
    private var service: ILlamaService? = null
    private var connection: ServiceConnection? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        service = bindServiceBlocking()
        assertNotNull("Failed to bind LlamaService", service)
        service!!.initBackend()
    }

    @After
    fun tearDown() {
        connection?.let { context.unbindService(it) }
        connection = null
        service = null
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

    private fun findModel(): File? {
        for (name in CANDIDATE_MODELS) {
            val f = File(MODELS_PATH, name)
            if (f.exists() && f.canRead()) {
                Log.d(TAG, "Using model: $name (${f.length() / 1024 / 1024}MB)")
                return f
            }
        }
        return null
    }

    @Test(timeout = 60_000)
    fun bindAndInit_succeeds() {
        // Bound in @Before — just verify systemInfo round-trips
        val info = service!!.systemInfo()
        assertTrue("systemInfo should be non-empty", info.isNotBlank())
    }

    @Test(timeout = 180_000)
    fun loadModel_returnsPositiveId() {
        val modelFile = findModel()
        assumeNotNull("No model in $MODELS_PATH", modelFile)

        val progress = object : ILlamaProgressCallback.Stub() {
            override fun onProgress(p: Float) {}
        }
        val modelId = service!!.loadModel(modelFile!!.absolutePath, null, progress, false, null)
        assertNotEquals("loadModel should return non-zero id", 0, modelId)
        try {
            assertTrue("getModelSize should be > 0", service!!.getModelSize(modelId) > 0)
        } finally {
            service!!.unloadModel(modelId)
        }
    }

    /**
     * Regression for the silent native-loadModel-failure case: pointing
     * at a path that doesn't exist (or isn't a valid GGUF) must surface
     * as the AIDL contract's `0`, not a positive id that breaks every
     * subsequent call. Before the fix, the JNI would return a wrapper
     * with a null internal model and the service stored it anyway.
     */
    @Test(timeout = 60_000)
    fun loadModel_invalidPath_returnsZero() {
        val progress = object : ILlamaProgressCallback.Stub() {
            override fun onProgress(p: Float) {}
        }
        val bogus = "/data/local/tmp/this-file-does-not-exist-${System.nanoTime()}.gguf"
        val modelId = service!!.loadModel(bogus, null, progress, false, null)
        assertEquals("Bogus path should return 0", 0, modelId)
    }

    @Test(timeout = 180_000)
    fun loadModelByPfd_returnsPositiveId() {
        val modelFile = findModel()
        assumeNotNull("No model in $MODELS_PATH", modelFile)

        val pfd = ParcelFileDescriptor.open(modelFile, ParcelFileDescriptor.MODE_READ_ONLY)
        val progress = object : ILlamaProgressCallback.Stub() {
            override fun onProgress(p: Float) {}
        }
        // PFD is dup'd into the service across the binder; the service
        // builds its own fd:N string from the dup. The local pfd is closed
        // by the service's PFD entry on unloadModel.
        val modelId = service!!.loadModel(null, pfd, progress, false, null)
        assertNotEquals("loadModel(pfd) should return non-zero id", 0, modelId)
        try {
            assertTrue("getModelSize should be > 0", service!!.getModelSize(modelId) > 0)
        } finally {
            service!!.unloadModel(modelId)
        }
    }

    @Test(timeout = 240_000)
    fun startGeneration_streamsDeltas() {
        val modelFile = findModel()
        assumeNotNull("No model in $MODELS_PATH", modelFile)

        val progress = object : ILlamaProgressCallback.Stub() {
            override fun onProgress(p: Float) {}
        }
        val modelId = service!!.loadModel(modelFile!!.absolutePath, null, progress, false, null)
        assertNotEquals("loadModel should return non-zero id", 0, modelId)

        try {
            val sessionId = service!!.createSession(modelId, DEFAULT_PARAMS)
            assertNotEquals("createSession should return non-zero id", 0, sessionId)

            try {
                service!!.addMessage(sessionId, "Say hello in one short sentence.", false)

                val finished = CountDownLatch(1)
                val deltaCount = AtomicInteger(0)
                val accumulated = StringBuilder()
                val statusCode = AtomicInteger(Int.MIN_VALUE)

                val cb = object : ILlamaGenerationCallback.Stub() {
                    override fun onResponseDelta(delta: String) {
                        deltaCount.incrementAndGet()
                        synchronized(accumulated) { accumulated.append(delta) }
                    }
                    override fun onGenerationFinished(sc: Int) {
                        statusCode.set(sc)
                        finished.countDown()
                    }
                }
                service!!.startGeneration(sessionId, cb)

                // Cap test runtime
                val finishedInTime = finished.await(120, TimeUnit.SECONDS)
                if (!finishedInTime) {
                    service!!.cancelGeneration(sessionId)
                    finished.await(10, TimeUnit.SECONDS)
                }

                Log.d(TAG, "deltas=${deltaCount.get()} status=${statusCode.get()} response=${accumulated.length}ch")
                Log.d(TAG, "Response:\n$accumulated")

                assertTrue("Should receive at least one delta (got ${deltaCount.get()})", deltaCount.get() > 0)
                // Natural EOS must report 0 — non-zero would mean error.
                // Regression for the GenerationWorker EOS-status fix.
                if (finishedInTime) {
                    assertEquals(
                        "Natural completion should report status 0",
                        0, statusCode.get(),
                    )
                }
                assertTrue("Accumulated response should be non-empty", accumulated.isNotEmpty())
            } finally {
                service!!.destroySession(sessionId)
            }
        } finally {
            service!!.unloadModel(modelId)
        }
    }

    /**
     * Regression test for the use-after-free risk in session teardown:
     * destroy a session while generation is still active. The service's
     * `tearDownSession` must wait for the worker thread to actually exit
     * before freeing the native session. Before the fix, a hard 5 s join
     * timeout would let `nativeSession.destroy()` run while
     * `nativeSession.generate()` was still in flight on the worker —
     * a SIGSEGV in the `:llama` process under realistic prompt-eval
     * latencies.
     *
     * If this regresses, we'd see the test process binder die mid-
     * `unloadModel`/`destroySession` and the assertion below would fail
     * because `:llama` died.
     */
    /**
     * Regression for the deferred-teardown / model-leak race: destroy a
     * session AND unload its parent model in tight sequence while a
     * worker is still inside `nativeSession.generate()`. This is the
     * exact sequence ConversationViewModel.loadModel triggers when the
     * user picks a different model mid-generation.
     *
     * Before the fix, `destroySession` removed the session from the map
     * even when teardown leaked, so the immediately-following
     * `unloadModel` no longer saw the live worker — and freed the model
     * while the worker was still using a `llama_context*` derived from
     * it. SIGSEGV in :llama.
     *
     * The new contract: tearDownSession leaves leaked entries in
     * `sessions` under `pendingDestroy=true`; unloadModel sees them and
     * defers the model destroy until `onWorkerExit` clears the way.
     */
    @Test(timeout = 240_000)
    fun destroySessionThenUnloadModel_duringActiveGeneration_doesNotCrash() {
        val modelFile = findModel()
        assumeNotNull("No model in $MODELS_PATH", modelFile)

        val progress = object : ILlamaProgressCallback.Stub() {
            override fun onProgress(p: Float) {}
        }
        val modelId = service!!.loadModel(modelFile!!.absolutePath, null, progress, false, null)
        val sessionId = service!!.createSession(modelId, DEFAULT_PARAMS)

        // Long prompt → at least some prompt-eval time before the first
        // token, so the destroy+unload race lands while generate() is
        // still using the model context.
        service!!.addMessage(sessionId, "Tell me a long story about a dragon.", false)

        val cb = object : ILlamaGenerationCallback.Stub() {
            override fun onResponseDelta(delta: String) {}
            override fun onGenerationFinished(sc: Int) {}
        }
        service!!.startGeneration(sessionId, cb)
        Thread.sleep(50)

        // Same back-to-back order ConversationViewModel uses on reload.
        service!!.destroySession(sessionId)
        service!!.unloadModel(modelId)

        // If model freeing raced the worker, :llama would have crashed
        // here and this call would throw DeadObject. Surviving means
        // the deferred-teardown contract held.
        val info = service!!.systemInfo()
        assertTrue(
            "Service should still respond after destroy+unload-during-generation",
            info.isNotBlank(),
        )
    }

    @Test(timeout = 240_000)
    fun destroySession_duringActiveGeneration_doesNotCrash() {
        val modelFile = findModel()
        assumeNotNull("No model in $MODELS_PATH", modelFile)

        val progress = object : ILlamaProgressCallback.Stub() {
            override fun onProgress(p: Float) {}
        }
        val modelId = service!!.loadModel(modelFile!!.absolutePath, null, progress, false, null)
        val sessionId = service!!.createSession(modelId, DEFAULT_PARAMS)

        // Long prompt → at least some processing time before the first token,
        // so destroySession lands while generate() is still working.
        service!!.addMessage(sessionId, "Tell me a long story about a dragon.", false)

        val cb = object : ILlamaGenerationCallback.Stub() {
            override fun onResponseDelta(delta: String) {}
            override fun onGenerationFinished(sc: Int) {}
        }
        service!!.startGeneration(sessionId, cb)
        // Race the destroy against the first token. If the safe-teardown
        // path is broken, the service process crashes here.
        Thread.sleep(50)
        service!!.destroySession(sessionId)

        // After destroy, the service should still respond (proves the
        // process didn't die from a UAF inside destroy).
        val info = service!!.systemInfo()
        assertTrue("Service should still respond after destroy-during-generation", info.isNotBlank())

        service!!.unloadModel(modelId)
    }

    /**
     * Regression for the double-start UAF window: while one worker is
     * already inside `nativeSession.generate()`, a second
     * `startGeneration` for the same session must NOT spin up a parallel
     * worker on the same llama_context — that's undefined behavior and
     * almost always crashes :llama. The contract is: cancel + join the
     * prior; if it actually exited, start the new worker; if it didn't
     * exit within the join budget, signal a non-zero status to the new
     * caller and leave the prior one to drain.
     *
     * Easiest way to drive this: start a generation, immediately fire
     * a second startGeneration (the prior likely hasn't finished even
     * one token yet). Both callbacks must terminate cleanly without
     * crashing :llama — the second may either pre-empt or be refused.
     */
    @Test(timeout = 240_000)
    fun startGeneration_calledTwiceBackToBack_doesNotCrash() {
        val modelFile = findModel()
        assumeNotNull("No model in $MODELS_PATH", modelFile)

        val progress = object : ILlamaProgressCallback.Stub() {
            override fun onProgress(p: Float) {}
        }
        val modelId = service!!.loadModel(modelFile!!.absolutePath, null, progress, false, null)
        val sessionId = service!!.createSession(modelId, DEFAULT_PARAMS)

        try {
            service!!.addMessage(sessionId, "Tell me a long story about a dragon.", false)

            val firstFinished = CountDownLatch(1)
            val firstStatus = AtomicInteger(Int.MIN_VALUE)
            val firstCb = object : ILlamaGenerationCallback.Stub() {
                override fun onResponseDelta(delta: String) {}
                override fun onGenerationFinished(sc: Int) {
                    firstStatus.set(sc); firstFinished.countDown()
                }
            }
            val secondFinished = CountDownLatch(1)
            val secondStatus = AtomicInteger(Int.MIN_VALUE)
            val secondCb = object : ILlamaGenerationCallback.Stub() {
                override fun onResponseDelta(delta: String) {}
                override fun onGenerationFinished(sc: Int) {
                    secondStatus.set(sc); secondFinished.countDown()
                }
            }

            service!!.startGeneration(sessionId, firstCb)
            // No sleep — we WANT the second call to land while the first
            // worker is still in flight.
            service!!.startGeneration(sessionId, secondCb)

            // Both callbacks must complete; neither may hang the test.
            assertTrue(
                "First generation should finish (got status ${firstStatus.get()})",
                firstFinished.await(120, TimeUnit.SECONDS),
            )
            assertTrue(
                "Second generation should finish (got status ${secondStatus.get()})",
                secondFinished.await(120, TimeUnit.SECONDS),
            )

            // :llama still alive proves we didn't double-drive the context.
            assertTrue(
                "Service should still respond after concurrent startGeneration calls",
                service!!.systemInfo().isNotBlank(),
            )
        } finally {
            service!!.destroySession(sessionId)
            service!!.unloadModel(modelId)
        }
    }

    @Test(timeout = 240_000)
    fun cancelGeneration_stopsLoop() {
        val modelFile = findModel()
        assumeNotNull("No model in $MODELS_PATH", modelFile)

        val progress = object : ILlamaProgressCallback.Stub() {
            override fun onProgress(p: Float) {}
        }
        val modelId = service!!.loadModel(modelFile!!.absolutePath, null, progress, false, null)
        val sessionId = service!!.createSession(modelId, DEFAULT_PARAMS)

        try {
            // Ask for a long-form answer so the loop is busy when we cancel
            service!!.addMessage(sessionId, "Tell me a very long story about a dragon.", false)

            val finished = CountDownLatch(1)
            val deltaCount = AtomicInteger(0)
            val statusCode = AtomicInteger(Int.MIN_VALUE)

            val cb = object : ILlamaGenerationCallback.Stub() {
                override fun onResponseDelta(delta: String) { deltaCount.incrementAndGet() }
                override fun onGenerationFinished(sc: Int) {
                    statusCode.set(sc)
                    finished.countDown()
                }
            }
            service!!.startGeneration(sessionId, cb)

            // Let a few tokens through, then cancel
            Thread.sleep(2_000)
            val sawSomeBeforeCancel = deltaCount.get() > 0
            service!!.cancelGeneration(sessionId)

            val finishedInTime = finished.await(15, TimeUnit.SECONDS)
            assertTrue("Generation should finish promptly after cancel", finishedInTime)
            assertTrue("Should have streamed at least one delta before cancel", sawSomeBeforeCancel)
            // statusCode should be -1 (our cancel sentinel) or 0 (natural stop right at cancel time)
            val sc = statusCode.get()
            assertTrue("Cancel status should be -1 or 0 (got $sc)", sc == -1 || sc == 0)
        } finally {
            service!!.destroySession(sessionId)
            service!!.unloadModel(modelId)
        }
    }
}
