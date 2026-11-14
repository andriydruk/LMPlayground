package com.druk.llamacpp.jni

import com.druk.llamacpp.LlamaGenerationCallback

class NativeLlamaSession {

    private var nativeHandle: Long = 0

    external fun generate(callback: LlamaGenerationCallback): Int

    /** Returns 0 on success, non-zero when the turn was rejected (e.g. template failure). */
    external fun addMessage(message: String, enableThinking: Boolean): Int

    // Vision: stage image bytes (resized JPEG/PNG) consumed by the next
    // addMessage()'s mtmd path. Must be set before generation starts.
    external fun setImageData(imageData: ByteArray)

    /** Interrupt an in-progress decode (prompt eval or generation) ASAP. */
    external fun requestAbort()

    external fun printReport()

    external fun getReport(): String

    external fun replayHistory(userMessages: Array<String>, assistantMessages: Array<String>)

    /**
     * Drop user turn [turnIndex] and everything after it, rolling the KV
     * cache back so only what follows is evaluated again. [expectedText]
     * (when non-empty) must occur in that turn. Returns the KV tokens kept,
     * or -1 when the turn isn't there — the session is then unchanged.
     */
    external fun rewindToTurn(turnIndex: Int, expectedText: String): Int

    /** User turns in the conversation; the next addMessage is this index. */
    external fun getTurnCount(): Int

    external fun setTools(toolsJson: String)

    external fun getToolCallsJson(): String

    external fun submitToolResults(resultsJson: String, enableThinking: Boolean): Int

    external fun renderPreambleString(enableThinking: Boolean): String

    external fun setPreambleCachePath(path: String, fingerprint: String)

    external fun destroy()
}
