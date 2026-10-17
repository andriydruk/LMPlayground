package com.druk.llamacpp.jni

class NativeLlamaModel {

    private var nativeHandle: Long = 0

    external fun createSession(
        contextSize: Int,
        temperature: Float,
        topP: Float,
        repetitionPenalty: Float,
        topK: Int,
        minP: Float,
        seed: Int,
        thinkingBudget: Int,
        systemPrompt: String
    ): NativeLlamaSession?

    // Embeddings: create a pooling context over this model. Independent of
    // generation sessions; used for models like EmbeddingGemma.
    external fun createEmbeddingSession(contextSize: Int): NativeLlamaEmbeddingSession?

    external fun getContextTrainSize(): Int

    external fun getModelSize(): Long

    external fun getModelReport(): String

    external fun supportsThinking(): Boolean

    external fun supportsToolCalling(): Boolean

    // Vision: load a multimodal projector (mmproj) so this model can accept
    // image input. Must be called after the text model is loaded.
    external fun loadMmprojModel(mmprojPath: String)

    external fun supportsVision(): Boolean

    external fun unloadModel()
}
