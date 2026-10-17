package com.druk.llamacpp

/**
 * An interface for receiving progress updates during model loading operations in the llama.cpp library.
 */
interface LlamaProgressCallback {

    /**
     * Called periodically to report the progress of a model loading operation.
     *
     * @param progress A floating-point value between 0.0 (start) and 1.0 (complete)
     *                 indicating the progress of the loading operation.
     */
    fun onProgress(progress: Float)
}