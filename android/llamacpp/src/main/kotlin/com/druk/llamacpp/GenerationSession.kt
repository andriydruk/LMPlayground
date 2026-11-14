package com.druk.llamacpp

/**
 * The generation surface shared by every transport.
 *
 * :app talks to the engine across AIDL (`LlamaGenerationSession`); the test
 * harness and any desktop caller talk to it directly over JNI
 * (`DirectGenerationSession`). Both implement this, so logic written against
 * it — notably [com.druk.llamacpp.chat.ToolCallLoop] — is the same code in
 * production and under test.
 *
 * Return codes follow the AIDL proxy's contract, NOT the raw native one:
 *   0 — generation finished normally
 *   2 — the model emitted tool calls; drain [getToolCallsJson], reply with
 *       [submitToolResults], then generate again
 *   other — error or cancellation
 *
 * The native layer distinguishes "more tokens" (0) from "done" (1);
 * implementations must collapse that to this contract.
 */
interface GenerationSession {

    fun addMessage(message: String, enableThinking: Boolean)

    /** Stage image bytes for the next [addMessage]. Vision models only. */
    fun setImageData(imageData: ByteArray)

    /** OpenAI-format tool array, or "[]" to disable tool calling. */
    fun setTools(toolsJson: String)

    suspend fun generateAll(callback: LlamaGenerationCallback): Int

    fun getToolCallsJson(): String

    fun submitToolResults(resultsJson: String, enableThinking: Boolean)

    /**
     * Drop user turn [turnIndex] (0-based, over every user turn the session
     * has seen) and everything after it, so the next [addMessage] continues
     * from the end of the turn before. The KV cache is rolled back rather
     * than rebuilt, so only what follows the kept prefix is evaluated again.
     * [expectedText] must occur in that turn (empty skips the check).
     *
     * Returns the number of KV tokens kept, or -1 when the session can't
     * rewind there (the turn was compacted away, or the numbering doesn't
     * match). The session is then unchanged and the caller rebuilds it.
     */
    fun rewindToTurn(turnIndex: Int, expectedText: String): Int

    fun requestAbort()

    fun destroy()
}
