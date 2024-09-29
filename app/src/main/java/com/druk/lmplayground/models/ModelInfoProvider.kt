package com.druk.lmplayground.models

import android.net.Uri
import android.os.Environment

object ModelInfoProvider {

    fun buildModelList(): List<ModelInfo> {
        val path = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val files = path.listFiles()
        return listOf(
            ModelInfo(
                name = "Qwen2.5 0.5B",
                file = files?.firstOrNull { it.name == "Qwen2.5-0.5B-Instruct-Q4_K_M.gguf" },
                remoteUri = Uri.parse("https://huggingface.co/lmstudio-community/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/Qwen2.5-0.5B-Instruct-Q4_K_M.gguf?download=true"),
                inputPrefix = "<|im_start|>user\n",
                inputSuffix = "<|im_end|>\n<|im_start|>assistant\n",
                antiPrompt = arrayOf("<|im_end|>"),
                description = "0.5 billion parameters language model"
            ),
            ModelInfo(
                name = "Qwen2.5 1.5B",
                file = files?.firstOrNull { it.name == "Qwen2.5-1.5B-Instruct-Q4_K_M.gguf" },
                remoteUri = Uri.parse("https://huggingface.co/lmstudio-community/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/Qwen2.5-1.5B-Instruct-Q4_K_M.gguf?download=true"),
                inputPrefix = "<|im_start|>user\n",
                inputSuffix = "<|im_end|>\n<|im_start|>assistant\n",
                antiPrompt = arrayOf("<|im_end|>"),
                description = "1.5 billion parameters language model"
            ),
            ModelInfo(
                name = "Llama3.2 1B",
                file = files?.firstOrNull { it.name == "Llama-3.2-1B-Instruct-Q4_K_M.gguf" },
                remoteUri = Uri.parse("https://huggingface.co/lmstudio-community/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q4_K_M.gguf?download=true"),
                inputPrefix = "<|start_header_id|>user<|end_header_id|>\n\n",
                inputSuffix = "<|eot_id|><|start_header_id|>assistant<|end_header_id|>\n\n",
                antiPrompt = arrayOf("<|eot_id|>"),
                description = "1 billions parameters language model"
            ),
            ModelInfo(
                name = "Llama3.2 3B",
                file = files?.firstOrNull { it.name == "Llama-3.2-3B-Instruct-Q4_K_M.gguf" },
                remoteUri = Uri.parse("https://huggingface.co/lmstudio-community/Llama-3.2-3B-Instruct-GGUF/resolve/main/Llama-3.2-3B-Instruct-Q4_K_M.gguf?download=true"),
                inputPrefix = "<|start_header_id|>user<|end_header_id|>\n\n",
                inputSuffix = "<|eot_id|><|start_header_id|>assistant<|end_header_id|>\n\n",
                antiPrompt = arrayOf("<|eot_id|>"),
                description = "3 billions parameters language model"
            ),
            ModelInfo(
                name = "Gemma2 2B",
                file = files?.firstOrNull { it.name == "gemma-2b-it-q4_k_m.gguf" },
                remoteUri = Uri.parse("https://huggingface.co/lmstudio-ai/gemma-2b-it-GGUF/resolve/main/gemma-2b-it-q4_k_m.gguf"),
                inputPrefix = "<start_of_turn>user\n",
                inputSuffix = "<end_of_turn>\n<start_of_turn>model\n",
                antiPrompt = arrayOf("<start_of_turn>user", "<start_of_turn>model", "<end_of_turn>", "<eos>"),
                description = "2 billions parameters language model"
            ),
            ModelInfo(
                name = "Phi3.5 mini",
                file = files?.firstOrNull { it.name == "Phi-3.5-mini-instruct-Q4_K_M.gguf" },
                remoteUri = Uri.parse("https://huggingface.co/bartowski/Phi-3.5-mini-instruct-GGUF/resolve/main/Phi-3.5-mini-instruct-Q4_K_M.gguf?download=true"),
                inputPrefix = "<|user|>\n",
                inputSuffix = "<|end|>\n<|assistant|>\n",
                antiPrompt = arrayOf("<|end|>", "<|assistant|>"),
                description = "3.8 billions parameters language model"
            ),
            ModelInfo(
                name = "Mistral 7B",
                file = files?.firstOrNull { it.name == "Mistral-7B-Instruct-v0.3-Q4_K_M.gguf" },
                remoteUri = Uri.parse("https://huggingface.co/lmstudio-community/Mistral-7B-Instruct-v0.3-GGUF/resolve/main/Mistral-7B-Instruct-v0.3-Q4_K_M.gguf?download=true"),
                inputPrefix = "[INST]",
                inputSuffix = "[/INST]",
                description = "7.3 billions parameter language model"
            ),
            ModelInfo(
                name = "Llama3.1 8B",
                file = files?.firstOrNull { it.name == "Meta-Llama-3.1-8B-Instruct-Q4_K_M.gguf" },
                remoteUri = Uri.parse("https://huggingface.co/lmstudio-community/Meta-Llama-3.1-8B-Instruct-GGUF/resolve/main/Meta-Llama-3.1-8B-Instruct-Q4_K_M.gguf?download=true"),
                inputPrefix = "<|start_header_id|>user<|end_header_id|>\n\n",
                inputSuffix = "<|eot_id|><|start_header_id|>assistant<|end_header_id|>\n\n",
                antiPrompt = arrayOf("<|eot_id|>"),
                description = "8 billions parameters language model"
            ),
            ModelInfo(
                name = "Gemma2 9B",
                file = files?.firstOrNull { it.name == "gemma-2-9b-it-Q4_K_M.gguf" },
                remoteUri = Uri.parse("https://huggingface.co/bartowski/gemma-2-9b-it-GGUF/resolve/main/gemma-2-9b-it-Q4_K_M.gguf"),
                inputPrefix = "<start_of_turn>user\n",
                inputSuffix = "<end_of_turn>\n<start_of_turn>model\n",
                antiPrompt = arrayOf("<start_of_turn>user", "<start_of_turn>model", "<end_of_turn>"),
                description = "8.5 billions parameters language model"
            )
        )
    }

}