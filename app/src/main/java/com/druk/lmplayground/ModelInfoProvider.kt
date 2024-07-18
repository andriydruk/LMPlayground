package com.druk.lmplayground

import android.net.Uri
import android.os.Environment

object ModelInfoProvider {

    fun buildModelList(): List<ModelInfo> {
        val path = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val files = path.listFiles()
        return listOf(
            ModelInfo(
                name = "Qwen2 0.5B",
                file = files?.firstOrNull { it.name == "qwen2-0_5b-instruct-q4_k_m.gguf" },
                remoteUri = Uri.parse("https://huggingface.co/Qwen/Qwen2-0.5B-Instruct-GGUF/resolve/main/qwen2-0_5b-instruct-q4_k_m.gguf"),
                inputPrefix = "<|im_start|>user\n",
                inputSuffix = "<|im_end|>\n<|im_start|>assistant\n",
                antiPrompt = "<|im_end|>",
                description = "0.5 billion parameters language model"
            ),
            ModelInfo(
                name = "Phi 3.1",
                file = files?.firstOrNull { it.name == "Phi-3.1-mini-4k-instruct-Q4_K_M.gguf" },
                remoteUri = Uri.parse("https://huggingface.co/bartowski/Phi-3.1-mini-4k-instruct-GGUF/resolve/main/Phi-3.1-mini-4k-instruct-Q4_K_M.gguf"),
                inputPrefix = "<start_of_turn>user\n",
                inputSuffix = "<end_of_turn>\n<start_of_turn>model\n",
                antiPrompt = "<end_of_turn>",
                description = "3.8 billions parameters language model"
            ),
            ModelInfo(
                name = "Gemma2 9B",
                file = files?.firstOrNull { it.name == "gemma-2-9b-it-Q4_K_M.gguf" },
                remoteUri = Uri.parse("https://huggingface.co/bartowski/gemma-2-9b-it-GGUF/resolve/main/gemma-2-9b-it-Q4_K_M.gguf"),
                inputPrefix = "<start_of_turn>user\n",
                inputSuffix = "<end_of_turn>\n<start_of_turn>model\n",
                antiPrompt = "<end_of_turn>",
                description = "8.5 billions parameters language model"
            ),
            ModelInfo(
                name = "Gemma 1.1 2B",
                file = files?.firstOrNull { it.name == "gemma-1.1-2b-it-Q4_K_M.gguf" },
                remoteUri = Uri.parse("https://huggingface.co/lmstudio-community/gemma-1.1-2b-it-GGUF/resolve/main/gemma-1.1-2b-it-Q4_K_M.gguf"),
                inputPrefix = "<start_of_turn>user\n",
                inputSuffix = "<end_of_turn>\n<start_of_turn>model\n",
                antiPrompt = "<eos>",
                description = "2.5 billions parameters language model"
            ),
            ModelInfo(
                name = "Mistral 7B",
                file = files?.firstOrNull { it.name == "mistral-7b-instruct-v0.2.Q4_K_M.gguf" },
                remoteUri = Uri.parse("https://huggingface.co/TheBloke/Mistral-7B-Instruct-v0.2-GGUF/resolve/main/mistral-7b-instruct-v0.2.Q4_K_M.gguf"),
                inputPrefix = "[INST]",
                inputSuffix = "[/INST]",
                description = "7.3 billions parameter language model"
            ),
            ModelInfo(
                name = "Llama 3 8B",
                file = files?.firstOrNull { it.name == "Meta-Llama-3-8B-Instruct-Q4_K_M.gguf" },
                remoteUri = Uri.parse("https://huggingface.co/lmstudio-community/Meta-Llama-3-8B-Instruct-GGUF/resolve/main/Meta-Llama-3-8B-Instruct-Q4_K_M.gguf"),
                inputPrefix = "<|start_header_id|>user<|end_header_id|>\n\n",
                inputSuffix = "<|eot_id|><|start_header_id|>assistant<|end_header_id|>\n\n",
                antiPrompt = "<|eot_id|>",
                description = "8 billions parameters language model"
            )
        )
    }

}