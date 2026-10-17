package com.druk.lmplayground.harness

import com.druk.llamacpp.LlamaGenerationCallback
import com.druk.llamacpp.LlamaProgressCallback
import com.druk.llamacpp.jni.NativeLlamaCpp
import com.druk.llamacpp.jni.NativeLlamaModel
import com.druk.llamacpp.jni.NativeLlamaSession
import java.io.File

/**
 * Host-side engine access: the same direct-JNI path the instrumented tests
 * use on device, minus the AIDL/binder layer (which is Android-only and adds
 * nothing a harness needs).
 */
object Engine {

    /** Native generate() contract. The AIDL proxy remaps 1 -> 0; we don't. */
    const val RC_MORE_TOKENS = 0
    const val RC_DONE = 1
    const val RC_TOOL_CALLS = 2

    private var initialized = false

    fun init(): NativeLlamaCpp {
        val llama = NativeLlamaCpp()
        if (!initialized) {
            // Same directory holds libllamacpp.dylib and the ggml backend
            // modules, so this is both java.library.path and the path
            // ggml_backend_load_all_from_path scans.
            val libDir = System.getProperty("lmp.nativeLibDir").orEmpty()
            val stateDir = File(System.getProperty("java.io.tmpdir"), "lmp-harness-state")
            stateDir.mkdirs()
            llama.init(libDir, stateDir.absolutePath)
            initialized = true
        }
        return llama
    }

    fun loadModel(
        llama: NativeLlamaCpp,
        file: File,
        chatTemplateOverride: String = "",
    ): NativeLlamaModel {
        var lastPct = -1
        val cb = object : LlamaProgressCallback {
            override fun onProgress(progress: Float) {
                val pct = (progress * 100).toInt()
                if (pct >= lastPct + 25) {
                    lastPct = pct
                    System.err.println("  loading ${file.name}: $pct%")
                }
            }
        }
        return llama.loadModel(file.absolutePath, cb, false, chatTemplateOverride)
            ?: error("loadModel returned null for ${file.absolutePath}")
    }

    /**
     * Greedy, fixed-seed session. Structural probes must be reproducible;
     * note seed is uint32 on the native side, so -1 (used throughout the
     * instrumented tests) means RANDOM, not "default".
     */
    fun NativeLlamaModel.newGreedySession(
        systemPrompt: String = "",
        nCtx: Int = 4096,
        seed: Int = 1234,
    ): NativeLlamaSession = createSession(
        nCtx, 0.0f, 1.0f, 1.0f, 1, 0.0f, seed, -1, systemPrompt,
    ) ?: error("createSession returned null")

    /** Production sampler tuple, but with an explicit seed. */
    fun NativeLlamaModel.newProductionSession(
        seed: Int,
        systemPrompt: String = "",
        nCtx: Int = 4096,
    ): NativeLlamaSession = createSession(
        nCtx, 0.6f, 0.95f, 1.0f, 40, 0.05f, seed, -1, systemPrompt,
    ) ?: error("createSession returned null")

    data class GenOutcome(
        val text: String,
        val rc: Int,
        val tokens: Int,
        val ms: Long,
        val hitTokenCap: Boolean,
        val hitTimeout: Boolean,
    )

    /**
     * Drive generate() to completion. Replaces the loop copy-pasted across
     * eight androidTest classes.
     */
    fun NativeLlamaSession.generateToEnd(
        maxTokens: Int = 512,
        timeoutMs: Long = 180_000,
    ): GenOutcome {
        val started = System.currentTimeMillis()
        var text = ""
        // Native hands back the cumulative, already-normalized response each
        // call (reasoning folded into a <think> envelope), not a delta.
        val cb = object : LlamaGenerationCallback {
            override fun onFullResponse(response: String) { text = response }
        }
        var tokens = 0
        var rc = RC_MORE_TOKENS
        var hitTokenCap = false
        var hitTimeout = false
        while (true) {
            rc = generate(cb)
            if (rc != RC_MORE_TOKENS) break
            tokens++
            if (tokens >= maxTokens) { hitTokenCap = true; break }
            if (System.currentTimeMillis() - started > timeoutMs) { hitTimeout = true; break }
        }
        return GenOutcome(
            text = text,
            rc = rc,
            tokens = tokens,
            ms = System.currentTimeMillis() - started,
            hitTokenCap = hitTokenCap,
            hitTimeout = hitTimeout,
        )
    }
}
