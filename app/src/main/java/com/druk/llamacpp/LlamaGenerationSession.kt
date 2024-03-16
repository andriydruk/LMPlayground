package com.druk.llamacpp

class LlamaGenerationSession {

    // Native handle
    private var nativeHandle: Long = 0

    external fun generate(callback: LlamaGenerationCallback): Int
    external fun addMessage(message: String)
    external fun printReport()
    external fun getReport(): String
    external fun destroy()
}