package com.druk.llamacpp

/**
 * AIDL-proxy view of a loaded model. Holds an opaque positive [modelId]
 * issued by the service; the native pointer never leaves the service
 * process.
 */
class LlamaModel internal constructor(
    private val client: InferenceClient,
    internal val modelId: Int,
) {
    fun getModelSize(): Long = client.withService { it.getModelSize(modelId) }

    fun getModelReport(): String = client.withService { it.getModelReport(modelId) }

    fun getContextTrainSize(): Int = client.withService { it.getContextTrainSize(modelId) }

    fun supportsThinking(): Boolean = client.withService { it.supportsThinking(modelId) }

    fun supportsToolCalling(): Boolean = client.requireConnected().supportsToolCalling(modelId)

    fun unloadModel() {
        try {
            client.withService { it.unloadModel(modelId) }
        } catch (_: InferenceUnavailableException) {
            // Service is gone anyway — the model handle is implicitly
            // released. Don't bubble this up; callers just want the
            // unload to be cleaned up.
        }
    }

    fun createSession(
        contextSize: Int,
        temperature: Float,
        topP: Float,
        repetitionPenalty: Float,
        topK: Int,
        minP: Float,
        seed: Int,
        thinkingBudget: Int,
        systemPrompt: String,
    ): LlamaGenerationSession? {
        InferenceLimits.requireWithinBudget(systemPrompt, "system prompt")
        val params = SamplerParams(
            contextSize = contextSize,
            temperature = temperature,
            topP = topP,
            repetitionPenalty = repetitionPenalty,
            topK = topK,
            minP = minP,
            seed = seed,
            thinkingBudget = thinkingBudget,
            systemPrompt = systemPrompt,
        )
        val sessionId = client.withService { it.createSession(modelId, params) }
        if (sessionId == 0) return null
        return LlamaGenerationSession(client, sessionId)
    }

    /**
     * Embeddings: create a pooling context over this model (for embedding
     * models like EmbeddingGemma). Independent of generation sessions.
     * Returns null on failure.
     */
    fun createEmbeddingSession(contextSize: Int): LlamaEmbeddingSession? {
        val id = client.withService { it.createEmbeddingSession(modelId, contextSize) }
        if (id == 0) return null
        return LlamaEmbeddingSession(client, id)
    }

    /**
     * Vision: load a multimodal projector (mmproj) so this model can accept
     * image input. [mmprojPath] is a filesystem path readable by the service
     * process (the UI copies the projector into app storage first). Returns
     * true if the projector loaded and the model now supports vision.
     */
    fun loadMmprojModel(mmprojPath: String): Boolean =
        client.withService { it.loadMmprojModel(modelId, mmprojPath, null) }

    /** Whether a vision projector is loaded and usable for this model. */
    fun supportsVision(): Boolean = client.withService { it.supportsVision(modelId) }
}
