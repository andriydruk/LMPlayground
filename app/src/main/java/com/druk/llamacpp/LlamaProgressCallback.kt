package com.druk.llamacpp

interface LlamaProgressCallback {
    fun onProgress(progress: Float)
}