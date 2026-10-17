package com.druk.llamacpp.jni

import com.druk.llamacpp.LlamaProgressCallback

/**
 * Direct JNI binding to llama.cpp. Loaded only inside the `:llama` process.
 *
 * Public app code should use `com.druk.llamacpp.LlamaCpp` (the binder proxy)
 * instead of constructing this class directly.
 */
class NativeLlamaCpp {

    companion object {
        init {
            System.loadLibrary("llamacpp")
        }
    }

    /**
     * @param stateDir an app-writable directory for the Vulkan-vision crash
     *   sentinel (see native-lib.cpp). Pass "" to disable it.
     */
    external fun init(nativeLibDir: String, stateDir: String): Int

    /** Convenience for callers that don't need the vision crash sentinel. */
    fun init(nativeLibDir: String): Int = init(nativeLibDir, "")

    external fun systemInfo(): String

    /**
     * Returns `null` when the native load failed — e.g. the file is not a
     * valid GGUF, the architecture isn't supported by this build of
     * llama.cpp, or the file couldn't be opened. The wrapper is only
     * constructed on success.
     */
    external fun loadModel(
        path: String,
        progressCallback: LlamaProgressCallback,
        disableRepack: Boolean,
        chatTemplateOverride: String
    ): NativeLlamaModel?

    /** Loads with the GGUF's own chat template. */
    fun loadModel(
        path: String,
        progressCallback: LlamaProgressCallback,
        disableRepack: Boolean
    ): NativeLlamaModel? = loadModel(path, progressCallback, disableRepack, "")

    external fun probeModelMetadata(path: String): Array<String>?
}
