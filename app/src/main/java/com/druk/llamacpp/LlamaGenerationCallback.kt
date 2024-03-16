package com.druk.llamacpp

interface LlamaGenerationCallback {
    fun newTokens(newTokens: ByteArray)
}