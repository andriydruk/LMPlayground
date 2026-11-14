package com.druk.llamacpp

/**
 * An interface for receiving the full normalized response during text generation with llama.cpp.
 */
interface LlamaGenerationCallback {

    /**
     * Called on each generated token with the full accumulated response string.
     * Thinking content is normalized to `<think>...</think>` format regardless of model.
     *
     * @param response The full response string so far.
     */
    fun onFullResponse(response: String)
}
