package com.druk.llamacpp

import com.druk.llamacpp.jni.NativeLlamaSession

/**
 * [GenerationSession] straight over JNI, with no service or binder in the way.
 *
 * Used by the macOS harness (and anything else running in-process) so that
 * engine-level logic can be exercised without an Android runtime. The app
 * keeps using the AIDL proxy, which isolates native crashes in their own
 * process — a property a test harness doesn't need.
 */
class DirectGenerationSession(
    private val native: NativeLlamaSession,
    private val maxTokens: Int = 4096,
    private val timeoutMs: Long = 300_000,
) : GenerationSession {

    /** Cumulative text of the last [generateAll], as the callback saw it. */
    @Volatile
    var lastResponse: String = ""
        private set

    /** Tokens sampled during the last [generateAll]. */
    @Volatile
    var lastTokenCount: Int = 0
        private set

    @Volatile
    private var abortRequested = false

    override fun addMessage(message: String, enableThinking: Boolean) {
        val rc = native.addMessage(message, enableThinking)
        check(rc == 0) { "addMessage rejected by the chat template (rc=$rc)" }
    }

    override fun setImageData(imageData: ByteArray) = native.setImageData(imageData)

    override fun setTools(toolsJson: String) = native.setTools(toolsJson)

    override suspend fun generateAll(callback: LlamaGenerationCallback): Int {
        abortRequested = false
        val started = System.currentTimeMillis()
        var text = ""
        val sink = object : LlamaGenerationCallback {
            override fun onFullResponse(response: String) {
                text = response
                callback.onFullResponse(response)
            }
        }
        var tokens = 0
        while (true) {
            val rc = native.generate(sink)
            if (rc != RC_MORE_TOKENS) {
                lastResponse = text
                lastTokenCount = tokens
                // Collapse native's "done" onto the proxy's contract so
                // callers see one set of return codes.
                return if (rc == RC_NATIVE_DONE) 0 else rc
            }
            tokens++
            if (abortRequested || tokens >= maxTokens ||
                System.currentTimeMillis() - started > timeoutMs
            ) {
                native.requestAbort()
                lastResponse = text
                lastTokenCount = tokens
                return 0
            }
        }
    }

    override fun getToolCallsJson(): String = native.getToolCallsJson()

    override fun submitToolResults(resultsJson: String, enableThinking: Boolean) {
        val rc = native.submitToolResults(resultsJson, enableThinking)
        check(rc == 0) { "submitToolResults failed (rc=$rc)" }
    }

    override fun requestAbort() {
        abortRequested = true
        native.requestAbort()
    }

    override fun destroy() = native.destroy()

    private companion object {
        const val RC_MORE_TOKENS = 0
        const val RC_NATIVE_DONE = 1
    }
}
