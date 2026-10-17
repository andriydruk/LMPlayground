package com.druk.llamacpp.jni

class NativeLlamaEmbeddingSession {

    private var nativeHandle: Long = 0

    external fun getEmbeddingDim(): Int

    /**
     * Embed each text with mean pooling and L2 normalization. Returns a
     * flattened [texts.size * getEmbeddingDim()] array, or null on failure.
     */
    external fun embedTexts(texts: Array<String>): FloatArray?

    external fun destroy()
}
