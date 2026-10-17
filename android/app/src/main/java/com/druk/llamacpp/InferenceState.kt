package com.druk.llamacpp

/**
 * Connection state of the inference engine, observable on
 * [InferenceClient.state]. The proxy classes refuse to operate when the
 * state is not [Connected].
 *
 * Step 5 will surface [Crashed] when the `:llama` process dies — at which
 * point the UI can show "Inference engine crashed — reload model" and
 * the next loadModel call gets a fresh service binding automatically.
 */
sealed interface InferenceState {
    object Connecting : InferenceState
    data class Connected(val service: ILlamaService) : InferenceState
    data class Crashed(val reason: String) : InferenceState
}

/** Thrown when the proxy is invoked while the service is not [InferenceState.Connected]. */
class InferenceUnavailableException(message: String) : IllegalStateException(message)
