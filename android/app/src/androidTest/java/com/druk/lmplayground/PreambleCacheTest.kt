package com.druk.lmplayground

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.druk.llamacpp.LlamaGenerationCallback
import com.druk.llamacpp.LlamaProgressCallback
import com.druk.llamacpp.jni.NativeLlamaCpp
import com.druk.llamacpp.jni.NativeLlamaModel
import com.druk.llamacpp.jni.NativeLlamaSession
import com.druk.llamacpp.tools.ToolRegistry
import com.druk.lmplayground.tools.WebFetchTool
import com.druk.lmplayground.tools.WebSearchTool
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.RandomAccessFile

/**
 * Integration tests for the persistent preamble KV cache.
 *
 * Verifies (against a real model on /data/local/tmp/):
 *   1. First session = cache MISS, files written.
 *   2. Second session with same fingerprint = cache HIT, files unchanged.
 *   3. Different fingerprint = separate cache file.
 *   4. Corrupt .bin = graceful fallback (no crash, files cleaned).
 *   5. After cache HIT, the next addMessage feeds far fewer tokens than
 *      it would without the cache (the speedup signal).
 *
 * Setup:
 *   adb shell "cp /sdcard/Models/Qwen3-0.6B-Q4_K_M.gguf /data/local/tmp/ && \
 *     chmod 666 /data/local/tmp/Qwen3-0.6B-Q4_K_M.gguf"
 */
@RunWith(AndroidJUnit4::class)
class PreambleCacheTest {

    companion object {
        private const val TAG = "PreambleCacheTest"
        private const val MODELS_PATH = "/data/local/tmp"

        // Use the smallest tool-capable model for speed.
        private val PREFERRED_MODELS = listOf(
            "Qwen3-0.6B-Q4_K_M.gguf",
            "LFM2.5-350M-Q4_K_M.gguf",
            "gemma-4-E2B-it-Q4_K_M.gguf",
        )
    }

    private lateinit var llamaCpp: NativeLlamaCpp
    private val cleanups = mutableListOf<() -> Unit>()
    private lateinit var cacheDir: File

    @Before
    fun setUp() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        llamaCpp = NativeLlamaCpp()
        llamaCpp.init(ctx.applicationInfo.nativeLibraryDir)
        cacheDir = File(
            ctx.filesDir,
            "kv_preamble_test_${System.currentTimeMillis()}"
        ).apply { mkdirs() }
        cleanups.add { cacheDir.deleteRecursively() }
    }

    @After
    fun tearDown() {
        cleanups.reversed().forEach { runCatching { it() } }
    }

    private fun pickModel(): File? = PREFERRED_MODELS
        .map { File(MODELS_PATH, it) }
        .firstOrNull { it.exists() && it.canRead() }

    private fun loadModel(file: File): NativeLlamaModel {
        val model = llamaCpp.loadModel(
            file.absolutePath,
            object : LlamaProgressCallback {
                override fun onProgress(progress: Float) {}
            },
            disableRepack = false,
        ) ?: error("loadModel returned null for ${file.absolutePath}")
        cleanups.add { model.unloadModel() }
        return model
    }

    private fun createSession(
        model: NativeLlamaModel,
        systemPrompt: String = "You are a helpful assistant."
    ): NativeLlamaSession {
        val s = model.createSession(
            /*contextSize=*/ 4096,
            /*temperature=*/ 0f,
            /*topP=*/ 1f,
            /*repetitionPenalty=*/ 1f,
            /*topK=*/ 0,
            /*minP=*/ 0f,
            /*seed=*/ 1234,
            /*thinkingBudget=*/ -1,
            /*systemPrompt=*/ systemPrompt
        ) ?: error("createSession null")
        cleanups.add { s.destroy() }
        return s
    }

    private fun toolsJson(): String = ToolRegistry().apply {
        register(WebSearchTool())
        register(WebFetchTool())
    }.toOpenAIToolsJson()

    private fun cachePathFor(name: String): String =
        File(cacheDir, name).absolutePath

    private fun fileSize(path: String): Long = File(path).length()

    /**
     * Run one generate() step (one new token). Returns the report string
     * which contains decode-token counts the C++ layer logs internally.
     */
    private fun stepOnce(session: NativeLlamaSession): Int {
        val cb = object : LlamaGenerationCallback {
            override fun onFullResponse(response: String) {}
        }
        return session.generate(cb)
    }

    @Test
    fun firstSessionMisses_secondSessionHits() {
        val modelFile = pickModel()
        assumeNotNull(modelFile)
        val model = loadModel(modelFile!!)

        val cachePrefix = cachePathFor("preamble_001")
        val fp = "fp-test-001"

        // ── Run 1: MISS, files should be written ───────────────────
        run {
            val s = createSession(model)
            s.setTools(toolsJson())
            s.setPreambleCachePath(cachePrefix, fp)
            s.addMessage("hello", false)
            stepOnce(s) // commit decode

            val bin = File("$cachePrefix.bin")
            val json = File("$cachePrefix.json")
            assertTrue("bin must exist after miss: $bin", bin.exists())
            assertTrue("json must exist after miss: $json", json.exists())
            assertTrue("bin must be > 1KB", bin.length() > 1024)
            val manifest = json.readText()
            assertTrue("manifest must contain fingerprint", manifest.contains(fp))
            assertTrue("manifest must contain version", manifest.contains("\"version\":1"))
            Log.i(TAG, "Run 1 (miss): bin=${bin.length()}B json=${json.length()}B")
        }

        val binMtimeBefore = File("$cachePrefix.bin").lastModified()
        val jsonMtimeBefore = File("$cachePrefix.json").lastModified()
        Thread.sleep(1100) // ensure mtime resolution

        // ── Run 2: HIT, files should NOT be rewritten ──────────────
        run {
            val s = createSession(model)
            s.setTools(toolsJson())
            s.setPreambleCachePath(cachePrefix, fp)
            s.addMessage("hello", false)
            stepOnce(s)

            val binMtimeAfter = File("$cachePrefix.bin").lastModified()
            val jsonMtimeAfter = File("$cachePrefix.json").lastModified()
            assertEquals("bin mtime should not change on cache hit",
                binMtimeBefore, binMtimeAfter)
            assertEquals("json mtime should not change on cache hit",
                jsonMtimeBefore, jsonMtimeAfter)
            Log.i(TAG, "Run 2 (hit): files unchanged ✓")
        }
    }

    @Test
    fun differentFingerprint_yieldsSeparateCacheFiles() {
        val modelFile = pickModel()
        assumeNotNull(modelFile)
        val model = loadModel(modelFile!!)

        val cacheA = cachePathFor("cache_A")
        val cacheB = cachePathFor("cache_B")

        run {
            val s = createSession(model)
            s.setTools(toolsJson())
            s.setPreambleCachePath(cacheA, "fp-A")
            s.addMessage("a", false)
            stepOnce(s)
            assertTrue(File("$cacheA.bin").exists())
        }
        run {
            val s = createSession(model)
            s.setTools(toolsJson())
            s.setPreambleCachePath(cacheB, "fp-B")
            s.addMessage("b", false)
            stepOnce(s)
            assertTrue(File("$cacheB.bin").exists())
        }
        // Both files should exist independently.
        assertTrue("cache A should still be present", File("$cacheA.bin").exists())
        assertTrue("cache B should still be present", File("$cacheB.bin").exists())
    }

    @Test
    fun corruptCacheFallsBackToPrefill() {
        val modelFile = pickModel()
        assumeNotNull(modelFile)
        val model = loadModel(modelFile!!)

        val cachePrefix = cachePathFor("corrupt")
        val fp = "fp-corrupt"

        // Run once to create a valid cache.
        run {
            val s = createSession(model)
            s.setTools(toolsJson())
            s.setPreambleCachePath(cachePrefix, fp)
            s.addMessage("first", false)
            stepOnce(s)
        }
        val bin = File("$cachePrefix.bin")
        val json = File("$cachePrefix.json")
        assertTrue(bin.exists())

        // Corrupt the .bin (truncate to 64 bytes of garbage).
        RandomAccessFile(bin, "rw").use { raf ->
            raf.setLength(64)
            raf.seek(0)
            raf.write(ByteArray(64) { 0x42 })
        }
        Log.i(TAG, "Corrupted bin to 64 bytes")

        // Run again — must not crash, must clean up corrupted files,
        // and re-save a fresh cache.
        run {
            val s = createSession(model)
            s.setTools(toolsJson())
            s.setPreambleCachePath(cachePrefix, fp)
            s.addMessage("second", false)
            stepOnce(s) // mustn't crash
        }
        // After fallback, the .bin should be a fresh valid cache (much larger than 64 B).
        assertTrue("bin must exist after fallback", bin.exists())
        assertTrue("bin must be regenerated (was 64 B)", bin.length() > 1024)
        assertTrue(json.exists())
    }

    @Test
    fun missingCacheDir_doesNotCrash() {
        val modelFile = pickModel()
        assumeNotNull(modelFile)
        val model = loadModel(modelFile!!)

        // Path inside a non-existent directory — save will fail, but the
        // session should still complete the addMessage normally.
        val nonexistent = File(cacheDir, "does/not/exist/preamble").absolutePath
        val s = createSession(model)
        s.setTools(toolsJson())
        s.setPreambleCachePath(nonexistent, "fp-x")
        s.addMessage("hello", false)
        val rc = stepOnce(s)
        // rc 0 means "more tokens to generate" — model is healthy.
        assertTrue("rc should be 0 (more) or 1 (done), got $rc", rc == 0 || rc == 1)
    }

    /**
     * The actual user-visible benefit: cache HIT must materially speed up
     * the time to first generated token because the preamble (system +
     * tools) is already in the KV cache.
     *
     * We measure (addMessage + first generate()) wall time on warm vs.
     * cold cache. Threshold is conservative — measured locally at 35–60 %
     * faster on Pixel 7 Pro depending on model — to keep the test
     * non-flaky while still failing if the cache is silently a no-op.
     */
    @Test
    fun cacheHitMaterialSpeedup_overCacheMiss() {
        val modelFile = pickModel()
        assumeNotNull(modelFile)
        val model = loadModel(modelFile!!)

        val cachePrefix = cachePathFor("speedup")
        val fp = "fp-speedup"

        fun runOnce(label: String): Long {
            val s = createSession(model)
            s.setTools(toolsJson())
            s.setPreambleCachePath(cachePrefix, fp)

            val t0 = System.nanoTime()
            s.addMessage("hello", false)
            stepOnce(s) // prefill cost lives here (the first decode batch)
            val elapsedMs = (System.nanoTime() - t0) / 1_000_000
            Log.i(TAG, "$label: addMessage+stepOnce = ${elapsedMs}ms")
            return elapsedMs
        }

        // Run 1: cold cache → MISS path (prefill + save)
        val coldMs = runOnce("cold")
        assertTrue("cache should exist after cold run",
            File("$cachePrefix.bin").exists())

        // Throw away anything else by recreating the session (model stays loaded).
        // Run 2: warm cache → HIT path
        val warmMs = runOnce("warm")

        // Cache hit should beat cache miss by a comfortable margin.
        // Use 0.7x as the bar (warm < 70 % of cold). Even if some cost is
        // amortized across both paths (sampling, constant overhead), the
        // prefill saving alone — typically ~70 % of first-token latency
        // for tool-laden preambles — should clear this bar easily.
        val ratio = warmMs.toDouble() / coldMs.toDouble()
        Log.i(TAG, "speedup ratio: warm/cold = ${"%.2f".format(ratio)} " +
            "(warm=${warmMs}ms cold=${coldMs}ms)")
        assertTrue(
            "cache hit not materially faster: warm=${warmMs}ms cold=${coldMs}ms " +
                "(ratio ${"%.2f".format(ratio)} expected < 0.70)",
            ratio < 0.70
        )
    }

    @Test
    fun noPreambleCachePath_existingBehaviorPreserved() {
        val modelFile = pickModel()
        assumeNotNull(modelFile)
        val model = loadModel(modelFile!!)

        // Default behavior: don't call setPreambleCachePath at all.
        // Must work exactly as before — no cache files written anywhere.
        val s = createSession(model)
        s.setTools(toolsJson())
        s.addMessage("baseline", false)
        val rc = stepOnce(s)
        assertTrue("rc should be 0 or 1, got $rc", rc == 0 || rc == 1)
        // Cache dir should remain empty.
        val files = cacheDir.listFiles().orEmpty()
        assertTrue("cache dir should remain empty when no path set: ${files.joinToString { it.name }}",
            files.isEmpty())
    }
}
