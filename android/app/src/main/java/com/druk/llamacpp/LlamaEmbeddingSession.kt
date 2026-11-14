package com.druk.llamacpp

/**
 * AIDL-proxy view of an embeddings-enabled context (mean pooling,
 * L2-normalized output). Holds an opaque positive [embeddingSessionId];
 * the native pointer never leaves the service process.
 */
class LlamaEmbeddingSession internal constructor(
    private val client: InferenceClient,
    private val embeddingSessionId: Int,
) {
    fun getEmbeddingDim(): Int =
        client.withService { it.getEmbeddingDim(embeddingSessionId) }

    /**
     * Embed [texts], one vector per input. The IPC is chunked so each
     * transaction stays well under the ~1 MB binder cap. Returns null if
     * any batch fails (service death, decode failure).
     */
    fun embed(texts: List<String>): List<FloatArray>? {
        if (texts.isEmpty()) return emptyList()
        val dim = getEmbeddingDim()
        if (dim <= 0) return null
        val result = ArrayList<FloatArray>(texts.size)
        for (batch in texts.chunked(MAX_TEXTS_PER_CALL)) {
            val flat = client.withService {
                it.embedTexts(embeddingSessionId, batch.toTypedArray())
            } ?: return null
            if (flat.size != batch.size * dim) return null
            for (i in batch.indices) {
                result.add(flat.copyOfRange(i * dim, (i + 1) * dim))
            }
        }
        return result
    }

    fun destroy() {
        try {
            client.withService { it.destroyEmbeddingSession(embeddingSessionId) }
        } catch (_: InferenceUnavailableException) {
            // Service is gone anyway — the session is implicitly released.
        }
    }

    companion object {
        // 16 chunks ≈ tens of KB of text up + 16*dim*4 B of floats down
        // (48 KB at 768 dims) per transaction — far under the binder cap.
        private const val MAX_TEXTS_PER_CALL = 16
    }
}
