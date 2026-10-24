package com.druk.lmplayground.harness

import com.druk.llamacpp.jni.NativeLlamaModel
import com.druk.llamacpp.jni.NativeLlamaSession
import com.druk.lmplayground.harness.Engine.generateToEnd
import com.druk.lmplayground.harness.Engine.newGreedySession
import java.io.File

/**
 * Shared real-engine plumbing for the JUnit suites in this module.
 *
 * These tests run the actual llama.cpp build against GGUFs on disk, so they
 * catch what a llama.cpp bump can silently break (KV-cache APIs, memory
 * types, template rendering). Models come from `$LMP_MODELS_DIR` (default
 * `~/.cache/lmplayground/models`); `-Dlmp.testModels=a.gguf,b.gguf` narrows
 * the set. With no chat model on disk the suites are skipped, not failed.
 */
object EngineFixture {

    private val NON_CHAT = listOf("mmproj", "embedding", "asr")

    fun modelsDir(): File = File(
        System.getenv("LMP_MODELS_DIR")
            ?: File(System.getProperty("user.home"), ".cache/lmplayground/models").path
    )

    /** Chat GGUFs to run against, smallest first so failures surface fast. */
    fun chatModels(): List<File> {
        val only = System.getProperty("lmp.testModels").orEmpty()
            .split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val files = modelsDir().listFiles { f -> f.isFile && f.name.endsWith(".gguf") }.orEmpty()
            .filter { f -> NON_CHAT.none { f.name.contains(it, ignoreCase = true) } }
            .filter { only.isEmpty() || it.name in only }
        return files.sortedBy { it.length() }
    }

    private val loaded = mutableMapOf<String, NativeLlamaModel>()

    init {
        // Free the weights before the process exits: ggml-metal asserts in
        // its static teardown if a model's buffers are still resident.
        Runtime.getRuntime().addShutdownHook(Thread {
            synchronized(this) {
                loaded.values.forEach { runCatching { it.unloadModel() } }
                loaded.clear()
            }
        })
    }

    /** Models stay loaded for the JVM's lifetime: loading dominates runtime. */
    @Synchronized
    fun model(file: File): NativeLlamaModel =
        loaded.getOrPut(file.absolutePath) { Engine.loadModel(Engine.init(), file) }

    fun NativeLlamaModel.session(systemPrompt: String = "", nCtx: Int = 4096): NativeLlamaSession =
        newGreedySession(systemPrompt = systemPrompt, nCtx = nCtx)

    /** One complete user turn; returns the reply. */
    fun NativeLlamaSession.turn(text: String, maxTokens: Int = 160): String {
        check(addMessage(text, false) == 0) { "addMessage rejected: $text" }
        return generateToEnd(maxTokens = maxTokens).text
    }

    private val PROMPT_TOKENS = Regex("""Prompt tokens:\s*(\d+)""")

    /** Prompt tokens this session has evaluated so far (cumulative). */
    fun NativeLlamaSession.promptTokens(): Int =
        PROMPT_TOKENS.find(getReport())?.groupValues?.get(1)?.toInt() ?: -1

    /** Prompt tokens evaluated while [block] runs. */
    inline fun <T> NativeLlamaSession.countingPrompt(block: () -> T): Pair<T, Int> {
        val before = promptTokens()
        val result = block()
        return result to (promptTokens() - before)
    }
}
