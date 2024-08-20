package com.druk.llamacpp

/**
 * Represents a generation session with a loaded language model in the llama.cpp library.
 *
 * This class provides methods for generating text, adding messages to the context,
 * and obtaining reports about the generation process.
 */
class LlamaGenerationSession {

    /**
     * The native handle to the generation session in the llama.cpp library.
     * This field is private and should not be modified directly.
     */
    private var nativeHandle: Long = 0

    /**
     * Generates text based on the current context of the session and calls back with generated tokens.
     *
     * @param callback An implementation of the `LlamaGenerationCallback` interface to receive
     *                 generated tokens and control the generation process.
     * @return A status code indicating the outcome of the generation.
     */
    external fun generate(callback: LlamaGenerationCallback): Int

    /**
     * Adds a message to the current context of the session.
     *
     * @param message The message to add to the context.
     */
    external fun addMessage(message: String)

    /**
     * Prints a report about the current state of the generation session to the console.
     */
    external fun printReport()

    /**
     * Gets a report about the current state of the generation session as a string.
     *
     * @return A string containing the report.
     */
    external fun getReport(): String

    /**
     * Destroys the generation session and releases associated resources.
     */
    external fun destroy()
}