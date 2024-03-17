package com.druk.lmplayground.conversation

import android.net.Uri
import android.os.Environment

object ModelInfoProvider {

    fun buildModelList(): List<ModelInfo> {
        val path = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val files = path.listFiles()
        return listOf(
            ModelInfo(
                name = "Gemma 2B",
                file = files?.firstOrNull { it.name == "gemma-2b-it-q4_k_m.gguf" },
                remoteUri = Uri.parse("https://huggingface.co/lmstudio-ai/gemma-2b-it-GGUF/resolve/main/gemma-2b-it-q4_k_m.gguf"),
                inputPrefix = "<start_of_turn>user\n",
                inputSuffix = "<end_of_turn>\n<start_of_turn>model\n",
                description = "2.5 billion parameters language model"
            ),
            ModelInfo(
                name = "Phi 2B",
                file = files?.firstOrNull { it.name == "phi-2.Q4_K_M.gguf" },
                remoteUri = Uri.parse("https://huggingface.co/TheBloke/phi-2-GGUF/resolve/main/phi-2.Q4_K_M.gguf"),
                inputPrefix = "Instruct: ",
                inputSuffix = "\nOutput:",
                description = "2.7 billion parameter language model"
            ),
            ModelInfo(
                name = "Gemma 7B",
                file = files?.firstOrNull { it.name == "gemma-7b-it.Q4_K_M.gguf" },
                remoteUri = Uri.parse("https://huggingface.co/mlabonne/gemma-7b-it-GGUF/resolve/main/gemma-7b-it.Q4_K_M.gguf"),
                inputPrefix = "<start_of_turn>user\n",
                inputSuffix = "<end_of_turn>\n<start_of_turn>model\n",
                description = "8.5 billion parameters language model"
            ),
            ModelInfo(
                name = "Mistral 7B",
                file = files?.firstOrNull { it.name == "mistral-7b-instruct-v0.2.Q4_K_M.gguf" },
                remoteUri = Uri.parse("https://huggingface.co/TheBloke/Mistral-7B-Instruct-v0.2-GGUF/resolve/main/mistral-7b-instruct-v0.2.Q4_K_M.gguf"),
                inputPrefix = "[INST]",
                inputSuffix = "[/INST]",
                description = "7.3 billion parameter language model"
            ),
            ModelInfo(
                name = "Llama 2 7B",
                file = files?.firstOrNull { it.name == "llama-2-7b-chat.Q4_K_M.gguf" },
                remoteUri = Uri.parse("https://huggingface.co/TheBloke/Llama-2-7B-Chat-GGUF/resolve/main/llama-2-7b-chat.Q4_K_M.gguf"),
                inputPrefix = "[INST]",
                inputSuffix = "[/INST]",
                description = "7 billion parameter language model"
            ),
        )
    }

}