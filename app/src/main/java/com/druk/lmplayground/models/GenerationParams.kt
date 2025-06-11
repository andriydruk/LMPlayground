package com.druk.lmplayground.models

/**
 * Parameters controlling llama.cpp generation.
 */
data class GenerationParams(
    val contextSize: Int = 2048,
    val temperature: Float = 0.8f,
    val topP: Float = 0.95f,
    val topK: Int = 40
)
