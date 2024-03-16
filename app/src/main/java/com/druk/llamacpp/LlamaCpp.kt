package com.druk.llamacpp

class LlamaCpp {

    companion object {
        // Used to load the 'llamacpp' library on application startup.
        init {
            System.loadLibrary("llamacpp")
        }
    }

    external fun init(): Int

    external fun loadOpenCL(): Int

    external fun loadModel(path: String, progressCallback: LlamaProgressCallback): LlamaModel
}