package com.druk.llamacpp

/**
 * The `LlamaModel` class represents a loaded large language model (LLM) in the llama.cpp library.
 *
 * It provides methods for interacting with the model, such as creating generation sessions
 * and querying model properties.
 */
class LlamaModel {

    /**
     * The native handle to the model in the llama.cpp library.
     * This field is private and should not be modified directly.
     */
    private var nativeHandle: Long = 0

    /**
     * Creates a new generation session for the loaded model.
     *
     * @return A `LlamaGenerationSession` object for managing text generation.
     */
    external fun createSession(): LlamaGenerationSession

    /**
     * Gets the size of the model in bytes.
     *
     * @return The size of the model.
     */
    external fun getModelSize(): Long

    /**
     * Unloads the model from memory and releases associated resources.
     */
    external fun unloadModel()

}