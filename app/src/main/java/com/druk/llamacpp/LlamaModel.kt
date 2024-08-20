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
     * @param inputPrefix (Optional) A string to prefix to the generated text.
     * @param inputSuffix (Optional) A string to suffix to the generated text.
     * @param antiPrompt (Optional) A string to stops the generation.
     * @return A `LlamaGenerationSession` object for managing text generation.
     */
    external fun createSession(
        inputPrefix: String?,
        inputSuffix: String?,
        antiPrompt: Array<String>,
    ): LlamaGenerationSession

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