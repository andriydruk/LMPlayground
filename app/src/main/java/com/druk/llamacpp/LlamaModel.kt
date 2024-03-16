package com.druk.llamacpp

class LlamaModel {

    // Native handle
    private var nativeHandle: Long = 0

    external fun createSession(
        inputPrefix: String?,
        inputSuffix: String?
    ): LlamaGenerationSession

    external fun getModelSize(): Long

    external fun unloadModel()

}