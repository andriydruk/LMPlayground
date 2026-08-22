package com.druk.lmplayground.models

import android.net.Uri
import com.druk.lmplayground.R
import java.time.LocalDate

object ModelInfoProvider {

    // Officially declared language support per model family (ISO 639-1 codes),
    // sourced from each model's HuggingFace card / publisher blog.
    private val MULTILINGUAL_BROAD = listOf(
        "en", "ar", "bg", "cs", "da", "de", "el", "es", "fi", "fr",
        "hi", "hu", "id", "it", "ja", "ko", "ms", "nl", "no", "pl",
        "pt", "ro", "ru", "sv", "th", "tr", "uk", "vi", "zh"
    )
    private val QWEN25_LANGS = listOf(
        "en", "zh", "fr", "es", "pt", "de", "it", "ru",
        "ja", "ko", "vi", "th", "ar"
    )
    private val LLAMA_LANGS = listOf("en", "de", "fr", "it", "pt", "hi", "es", "th")
    private val PHI_LANGS = listOf(
        "ar", "zh", "cs", "da", "nl", "en", "fi", "fr", "de", "he",
        "hu", "it", "ja", "ko", "no", "pl", "pt", "ru", "es", "sv",
        "th", "tr", "uk"
    )
    private val DEEPSEEK_LANGS = listOf("en", "zh")
    private val LFM_LANGS = listOf("en", "ar", "zh", "fr", "de", "ja", "ko", "es")
    // LFM2.5 2.6B declares a wider set than the earlier LFM2.5 sizes.
    private val LFM25_LANGS = listOf(
        "ar", "zh", "en", "fr", "de", "hi", "id", "it",
        "ja", "ko", "pl", "pt", "ru", "es", "th", "vi"
    )
    // The card's frontmatter also tags zh/ar/ru, but the model card text is
    // explicit that only these six are natively supported.
    private val SMOLLM3_LANGS = listOf("en", "fr", "es", "de", "it", "pt")
    private val MINICPM_LANGS = listOf("en", "zh")
    private val MISTRAL_LANGS = listOf(
        "en", "fr", "de", "es", "it", "pt", "nl", "zh", "ja", "ko", "ar"
    )
    private val GRANITE_LANGS = listOf(
        "en", "de", "es", "fr", "ja", "pt", "ar", "cs", "it", "ko", "nl", "zh"
    )
    private val ENGLISH_ONLY = listOf("en")
    // Parakeet TDT v3 transcribes 25 European languages and detects which one
    // it is hearing on its own.
    private val PARAKEET_LANGS = listOf(
        "bg", "cs", "da", "de", "el", "en", "es", "et", "fi", "fr",
        "hr", "hu", "it", "lt", "lv", "mt", "nl", "pl", "pt", "ro",
        "ru", "sk", "sl", "sv", "uk"
    )

    /**
     * Static list of all available models
     */
    private val rawModels: List<ModelInfo> = listOf(
        ModelInfo(
            name = "Qwen 3 0.6B",
            filename = "Qwen3-0.6B-Q4_K_M.gguf",
            remoteUri = Uri.parse("https://huggingface.co/lmstudio-community/Qwen3-0.6B-GGUF/resolve/main/Qwen3-0.6B-Q4_K_M.gguf"),
            releaseDate = LocalDate.parse("2025-04-29"),
            description = "Alibaba \u00B7 Lightweight chat model \u00B7 484Mb",
            logoRes = R.drawable.logo_qwen,
            supportedLanguages = MULTILINGUAL_BROAD
        ),
        ModelInfo(
            name = "Qwen 3 1.7B",
            filename = "Qwen3-1.7B-Q4_K_M.gguf",
            remoteUri = Uri.parse("https://huggingface.co/lmstudio-community/Qwen3-1.7B-GGUF/resolve/main/Qwen3-1.7B-Q4_K_M.gguf"),
            releaseDate = LocalDate.parse("2025-04-29"),
            description = "Alibaba \u00B7 General-purpose chat model \u00B7 1.28Gb",
            logoRes = R.drawable.logo_qwen,
            supportedLanguages = MULTILINGUAL_BROAD
        ),
        ModelInfo(
            name = "Qwen 3 4B",
            filename = "Qwen3-4B-Q4_K_M.gguf",
            remoteUri = Uri.parse("https://huggingface.co/lmstudio-community/Qwen3-4B-GGUF/resolve/main/Qwen3-4B-Q4_K_M.gguf"),
            releaseDate = LocalDate.parse("2025-04-29"),
            description = "Alibaba \u00B7 General-purpose chat model \u00B7 2.5Gb",
            logoRes = R.drawable.logo_qwen,
            supportedLanguages = MULTILINGUAL_BROAD
        ),
        ModelInfo(
            name = "Qwen 3.5 0.8B",
            filename = "Qwen_Qwen3.5-0.8B-IQ4_XS.gguf",
            remoteUri = Uri.parse("https://huggingface.co/bartowski/Qwen_Qwen3.5-0.8B-GGUF/resolve/main/Qwen_Qwen3.5-0.8B-IQ4_XS.gguf"),
            releaseDate = LocalDate.parse("2026-02-27"),
            description = "Alibaba \u00B7 Lightweight vision model \u00B7 499Mb + 207Mb mmproj",
            logoRes = R.drawable.logo_qwen,
            supportedLanguages = MULTILINGUAL_BROAD,
            mmprojFilename = "mmproj-Qwen_Qwen3.5-0.8B-f16.gguf",
            mmprojUri = Uri.parse("https://huggingface.co/bartowski/Qwen_Qwen3.5-0.8B-GGUF/resolve/main/mmproj-Qwen_Qwen3.5-0.8B-f16.gguf")
        ),
        ModelInfo(
            name = "Qwen 3.5 2B",
            filename = "Qwen_Qwen3.5-2B-IQ4_XS.gguf",
            remoteUri = Uri.parse("https://huggingface.co/bartowski/Qwen_Qwen3.5-2B-GGUF/resolve/main/Qwen_Qwen3.5-2B-IQ4_XS.gguf"),
            releaseDate = LocalDate.parse("2026-02-27"),
            description = "Alibaba \u00B7 General-purpose vision model \u00B7 1.17Gb + 207Mb mmproj",
            logoRes = R.drawable.logo_qwen,
            supportedLanguages = MULTILINGUAL_BROAD,
            mmprojFilename = "mmproj-Qwen_Qwen3.5-2B-f16.gguf",
            mmprojUri = Uri.parse("https://huggingface.co/bartowski/Qwen_Qwen3.5-2B-GGUF/resolve/main/mmproj-Qwen_Qwen3.5-2B-f16.gguf")
        ),
        ModelInfo(
            name = "Qwen 3.5 4B",
            filename = "Qwen_Qwen3.5-4B-IQ4_XS.gguf",
            remoteUri = Uri.parse("https://huggingface.co/bartowski/Qwen_Qwen3.5-4B-GGUF/resolve/main/Qwen_Qwen3.5-4B-IQ4_XS.gguf"),
            releaseDate = LocalDate.parse("2026-02-27"),
            description = "Alibaba \u00B7 General-purpose vision model \u00B7 2.49Gb + 207Mb mmproj",
            logoRes = R.drawable.logo_qwen,
            supportedLanguages = MULTILINGUAL_BROAD,
            mmprojFilename = "mmproj-Qwen_Qwen3.5-4B-f16.gguf",
            mmprojUri = Uri.parse("https://huggingface.co/bartowski/Qwen_Qwen3.5-4B-GGUF/resolve/main/mmproj-Qwen_Qwen3.5-4B-f16.gguf")
        ),
        ModelInfo(
            name = "Gemma 3 1B",
            filename = "gemma-3-1b-it-Q4_K_M.gguf",
            remoteUri = Uri.parse("https://huggingface.co/lmstudio-community/gemma-3-1b-it-GGUF/resolve/main/gemma-3-1b-it-Q4_K_M.gguf"),
            releaseDate = LocalDate.parse("2025-03-12"),
            description = "Google \u00B7 Lightweight chat model \u00B7 806Mb",
            logoRes = R.drawable.logo_google,
            supportedLanguages = MULTILINGUAL_BROAD
        ),
        ModelInfo(
            name = "Gemma 3 4B",
            filename = "gemma-3-4b-it-Q4_K_M.gguf",
            remoteUri = Uri.parse("https://huggingface.co/lmstudio-community/gemma-3-4b-it-GGUF/resolve/main/gemma-3-4b-it-Q4_K_M.gguf"),
            releaseDate = LocalDate.parse("2025-03-12"),
            description = "Google \u00B7 General-purpose chat model \u00B7 2.49Gb \u00B7 Supports vision with mmproj",
            logoRes = R.drawable.logo_google,
            supportedLanguages = MULTILINGUAL_BROAD,
            mmprojFilename = "gemma-3-4b-it-mmproj-f16.gguf",
            mmprojUri = Uri.parse("https://huggingface.co/lmstudio-community/gemma-3-4b-it-GGUF/resolve/main/gemma-3-4b-it-mmproj-f16.gguf")
        ),
        ModelInfo(
            name = "Llama 3.2 1B",
            filename = "Llama-3.2-1B-Instruct-Q4_K_M.gguf",
            remoteUri = Uri.parse("https://huggingface.co/lmstudio-community/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q4_K_M.gguf"),
            releaseDate = LocalDate.parse("2024-09-25"),
            description = "Meta \u00B7 Lightweight chat model \u00B7 808Mb",
            logoRes = R.drawable.logo_meta,
            supportedLanguages = LLAMA_LANGS
        ),
        ModelInfo(
            name = "Llama 3.2 3B",
            filename = "Llama-3.2-3B-Instruct-Q4_K_M.gguf",
            remoteUri = Uri.parse("https://huggingface.co/lmstudio-community/Llama-3.2-3B-Instruct-GGUF/resolve/main/Llama-3.2-3B-Instruct-Q4_K_M.gguf"),
            releaseDate = LocalDate.parse("2024-09-25"),
            description = "Meta \u00B7 General-purpose chat model \u00B7 2.02Gb",
            logoRes = R.drawable.logo_meta,
            supportedLanguages = LLAMA_LANGS
        ),
        ModelInfo(
            name = "Phi-4 mini",
            filename = "Phi-4-mini-instruct-Q4_K_M.gguf",
            remoteUri = Uri.parse("https://huggingface.co/lmstudio-community/Phi-4-mini-instruct-GGUF/resolve/main/Phi-4-mini-instruct-Q4_K_M.gguf"),
            releaseDate = LocalDate.parse("2025-01-15"),
            description = "Microsoft \u00B7 Compact reasoning model \u00B7 2.49Gb",
            logoRes = R.drawable.logo_microsoft,
            supportedLanguages = PHI_LANGS
        ),
        ModelInfo(
            name = "DeepSeek R1 Distill 1.5B",
            filename = "DeepSeek-R1-Distill-Qwen-1.5B-Q4_K_M.gguf",
            remoteUri = Uri.parse("https://huggingface.co/lmstudio-community/DeepSeek-R1-Distill-Qwen-1.5B-GGUF/resolve/main/DeepSeek-R1-Distill-Qwen-1.5B-Q4_K_M.gguf"),
            releaseDate = LocalDate.parse("2025-01-20"),
            description = "DeepSeek \u00B7 Compact reasoning model \u00B7 1.12Gb",
            logoRes = R.drawable.logo_deepseek,
            supportedLanguages = DEEPSEEK_LANGS
        ),
        ModelInfo(
            name = "DeepSeek R1 Distill 7B",
            filename = "DeepSeek-R1-Distill-Qwen-7B-Q4_K_M.gguf",
            remoteUri = Uri.parse("https://huggingface.co/lmstudio-community/DeepSeek-R1-Distill-Qwen-7B-GGUF/resolve/main/DeepSeek-R1-Distill-Qwen-7B-Q4_K_M.gguf"),
            releaseDate = LocalDate.parse("2025-01-20"),
            description = "DeepSeek \u00B7 Advanced reasoning model \u00B7 4.68Gb",
            logoRes = R.drawable.logo_deepseek,
            supportedLanguages = DEEPSEEK_LANGS
        ),
        ModelInfo(
            name = "LFM2.5 350M",
            filename = "LFM2.5-350M-Q4_K_M.gguf",
            remoteUri = Uri.parse("https://huggingface.co/LiquidAI/LFM2.5-350M-GGUF/resolve/main/LFM2.5-350M-Q4_K_M.gguf"),
            releaseDate = LocalDate.parse("2026-03-25"),
            description = "Liquid AI \u00B7 Ultra-lightweight chat model \u00B7 267Mb",
            logoRes = R.drawable.logo_liquid,
            supportedLanguages = LFM_LANGS
        ),
        ModelInfo(
            name = "LFM2.5 1.2B",
            filename = "LFM2.5-1.2B-Instruct-Q4_K_M.gguf",
            remoteUri = Uri.parse("https://huggingface.co/LiquidAI/LFM2.5-1.2B-Instruct-GGUF/resolve/main/LFM2.5-1.2B-Instruct-Q4_K_M.gguf"),
            releaseDate = LocalDate.parse("2026-08-19"),
            description = "Liquid AI \u00B7 Compact chat model \u00B7 697Mb",
            logoRes = R.drawable.logo_liquid,
            supportedLanguages = LFM25_LANGS
        ),
        // Superseded by the Instruct build above: same size class, but this one
        // reasons before every reply, which is the wrong default for a compact
        // chat model. Kept so an already-downloaded copy is still recognized.
        ModelInfo(
            name = "LFM2.5 1.2B Thinking",
            filename = "LFM2.5-1.2B-Thinking-Q4_K_M.gguf",
            remoteUri = Uri.parse("https://huggingface.co/lmstudio-community/LFM2.5-1.2B-Thinking-GGUF/resolve/main/LFM2.5-1.2B-Thinking-Q4_K_M.gguf"),
            releaseDate = LocalDate.parse("2025-01-09"),
            description = "Liquid AI \u00B7 Thinking model \u00B7 731Mb",
            logoRes = R.drawable.logo_liquid,
            deprecated = true,
            supportedLanguages = LFM_LANGS
        ),
        ModelInfo(
            name = "LFM2.5 2.6B",
            filename = "LFM2.5-2.6B-Q4_K_M.gguf",
            remoteUri = Uri.parse("https://huggingface.co/LiquidAI/LFM2.5-2.6B-GGUF/resolve/main/LFM2.5-2.6B-Q4_K_M.gguf"),
            releaseDate = LocalDate.parse("2026-07-28"),
            description = "Liquid AI · Reasoning model · 1.67Gb",
            logoRes = R.drawable.logo_liquid,
            supportedLanguages = LFM25_LANGS
        ),
        ModelInfo(
            name = "Ministral 3 3B Instruct",
            filename = "Ministral-3-3B-Instruct-2512-Q4_K_M.gguf",
            remoteUri = Uri.parse("https://huggingface.co/lmstudio-community/Ministral-3-3B-Instruct-2512-GGUF/resolve/main/Ministral-3-3B-Instruct-2512-Q4_K_M.gguf"),
            releaseDate = LocalDate.parse("2024-12-17"),
            description = "Mistral \u00B7 Lightweight vision model \u00B7 2.15Gb + mmproj",
            logoRes = R.drawable.logo_mistral,
            supportedLanguages = MISTRAL_LANGS,
            mmprojFilename = "mmproj-Ministral-3-3B-Instruct-2512-F16.gguf",
            mmprojUri = Uri.parse("https://huggingface.co/lmstudio-community/Ministral-3-3B-Instruct-2512-GGUF/resolve/main/mmproj-Ministral-3-3B-Instruct-2512-F16.gguf")
        ),
        ModelInfo(
            name = "Ministral 3 3B Reasoning",
            filename = "Ministral-3-3B-Reasoning-2512-Q4_K_M.gguf",
            remoteUri = Uri.parse("https://huggingface.co/lmstudio-community/Ministral-3-3B-Reasoning-2512-GGUF/resolve/main/Ministral-3-3B-Reasoning-2512-Q4_K_M.gguf"),
            releaseDate = LocalDate.parse("2024-12-17"),
            description = "Mistral \u00B7 Lightweight vision reasoning model \u00B7 2.15Gb + mmproj",
            logoRes = R.drawable.logo_mistral,
            supportedLanguages = MISTRAL_LANGS,
            mmprojFilename = "mmproj-Ministral-3-3B-Reasoning-2512-F16.gguf",
            mmprojUri = Uri.parse("https://huggingface.co/lmstudio-community/Ministral-3-3B-Reasoning-2512-GGUF/resolve/main/mmproj-Ministral-3-3B-Reasoning-2512-F16.gguf")
        ),
        ModelInfo(
            name = "Ministral 3 8B Instruct",
            filename = "Ministral-3-8B-Instruct-2512-Q4_K_M.gguf",
            remoteUri = Uri.parse("https://huggingface.co/lmstudio-community/Ministral-3-8B-Instruct-2512-GGUF/resolve/main/Ministral-3-8B-Instruct-2512-Q4_K_M.gguf"),
            releaseDate = LocalDate.parse("2024-12-17"),
            description = "Mistral \u00B7 General-purpose vision model \u00B7 5.2Gb + mmproj",
            logoRes = R.drawable.logo_mistral,
            supportedLanguages = MISTRAL_LANGS,
            mmprojFilename = "mmproj-Ministral-3-8B-Instruct-2512-F16.gguf",
            mmprojUri = Uri.parse("https://huggingface.co/lmstudio-community/Ministral-3-8B-Instruct-2512-GGUF/resolve/main/mmproj-Ministral-3-8B-Instruct-2512-F16.gguf")
        ),
        ModelInfo(
            name = "Ministral 3 8B Reasoning",
            filename = "Ministral-3-8B-Reasoning-2512-Q4_K_M.gguf",
            remoteUri = Uri.parse("https://huggingface.co/lmstudio-community/Ministral-3-8B-Reasoning-2512-GGUF/resolve/main/Ministral-3-8B-Reasoning-2512-Q4_K_M.gguf"),
            releaseDate = LocalDate.parse("2024-12-17"),
            description = "Mistral \u00B7 Advanced vision reasoning model \u00B7 5.2Gb + mmproj",
            logoRes = R.drawable.logo_mistral,
            supportedLanguages = MISTRAL_LANGS,
            mmprojFilename = "mmproj-Ministral-3-8B-Reasoning-2512-F16.gguf",
            mmprojUri = Uri.parse("https://huggingface.co/lmstudio-community/Ministral-3-8B-Reasoning-2512-GGUF/resolve/main/mmproj-Ministral-3-8B-Reasoning-2512-F16.gguf")
        ),
        ModelInfo(
            name = "Granite 4.0 Micro",
            filename = "granite-4.0-micro-Q4_K_M.gguf",
            remoteUri = Uri.parse("https://huggingface.co/lmstudio-community/granite-4.0-micro-GGUF/resolve/main/granite-4.0-micro-Q4_K_M.gguf"),
            releaseDate = LocalDate.parse("2025-02-26"),
            description = "IBM \u00B7 Enterprise chat model \u00B7 2.1Gb",
            logoRes = R.drawable.logo_ibm,
            supportedLanguages = GRANITE_LANGS
        ),
        ModelInfo(
            name = "Granite 4.0 H-Tiny",
            filename = "granite-4.0-h-tiny-Q4_K_M.gguf",
            remoteUri = Uri.parse("https://huggingface.co/lmstudio-community/granite-4.0-h-tiny-GGUF/resolve/main/granite-4.0-h-tiny-Q4_K_M.gguf"),
            releaseDate = LocalDate.parse("2025-02-26"),
            description = "IBM \u00B7 Hybrid enterprise model \u00B7 4.23Gb",
            logoRes = R.drawable.logo_ibm,
            supportedLanguages = GRANITE_LANGS
        ),
        ModelInfo(
            name = "Granite 4.1 3B",
            filename = "granite-4.1-3b-Q4_K_M.gguf",
            remoteUri = Uri.parse("https://huggingface.co/lmstudio-community/granite-4.1-3b-GGUF/resolve/main/granite-4.1-3b-Q4_K_M.gguf"),
            releaseDate = LocalDate.parse("2026-05-01"),
            description = "IBM \u00B7 Enterprise chat model \u00B7 2.10Gb",
            logoRes = R.drawable.logo_ibm,
            supportedLanguages = GRANITE_LANGS
        ),
        ModelInfo(
            name = "Granite 4.1 8B",
            filename = "granite-4.1-8b-Q4_K_M.gguf",
            remoteUri = Uri.parse("https://huggingface.co/lmstudio-community/granite-4.1-8b-GGUF/resolve/main/granite-4.1-8b-Q4_K_M.gguf"),
            releaseDate = LocalDate.parse("2026-05-01"),
            description = "IBM \u00B7 Advanced enterprise model \u00B7 5.35Gb",
            logoRes = R.drawable.logo_ibm,
            supportedLanguages = GRANITE_LANGS
        ),
        ModelInfo(
            name = "Nemotron 3 Nano 4B",
            filename = "NVIDIA-Nemotron3-Nano-4B-Q4_K_M.gguf",
            remoteUri = Uri.parse("https://huggingface.co/nvidia/NVIDIA-Nemotron-3-Nano-4B-GGUF/resolve/main/NVIDIA-Nemotron3-Nano-4B-Q4_K_M.gguf"),
            releaseDate = LocalDate.parse("2025-12-15"),
            description = "NVIDIA \u00B7 Hybrid reasoning model \u00B7 2.84Gb",
            logoRes = R.drawable.logo_nvidia,
            supportedLanguages = ENGLISH_ONLY
        ),
        ModelInfo(
            name = "SmolLM3 3B",
            filename = "HuggingFaceTB_SmolLM3-3B-Q4_K_M.gguf",
            remoteUri = Uri.parse("https://huggingface.co/bartowski/HuggingFaceTB_SmolLM3-3B-GGUF/resolve/main/HuggingFaceTB_SmolLM3-3B-Q4_K_M.gguf"),
            releaseDate = LocalDate.parse("2025-07-08"),
            description = "Hugging Face · Hybrid reasoning model · 1.92Gb",
            logoRes = R.drawable.logo_huggingface,
            supportedLanguages = SMOLLM3_LANGS
        ),
        ModelInfo(
            name = "MiniCPM5 1B",
            filename = "MiniCPM5-1B-Q4_K_M.gguf",
            remoteUri = Uri.parse("https://huggingface.co/openbmb/MiniCPM5-1B-GGUF/resolve/main/MiniCPM5-1B-Q4_K_M.gguf"),
            releaseDate = LocalDate.parse("2026-05-21"),
            description = "OpenBMB · Hybrid reasoning model · 688Mb",
            logoRes = R.drawable.logo_openbmb,
            supportedLanguages = MINICPM_LANGS
        ),
        ModelInfo(
            name = "Gemma 3n E2B",
            filename = "gemma-3n-E2B-it-Q4_K_M.gguf",
            remoteUri = Uri.parse("https://huggingface.co/lmstudio-community/gemma-3n-E2B-it-text-GGUF/resolve/main/gemma-3n-E2B-it-Q4_K_M.gguf"),
            releaseDate = LocalDate.parse("2025-05-14"),
            description = "Google \u00B7 Efficient on-device model \u00B7 2.79Gb",
            logoRes = R.drawable.logo_google,
            supportedLanguages = MULTILINGUAL_BROAD
        ),
        ModelInfo(
            name = "Gemma 3n E4B",
            filename = "gemma-3n-E4B-it-Q4_K_M.gguf",
            remoteUri = Uri.parse("https://huggingface.co/lmstudio-community/gemma-3n-E4B-it-text-GGUF/resolve/main/gemma-3n-E4B-it-Q4_K_M.gguf"),
            releaseDate = LocalDate.parse("2025-05-14"),
            description = "Google \u00B7 Efficient on-device model \u00B7 4.24Gb",
            logoRes = R.drawable.logo_google,
            supportedLanguages = MULTILINGUAL_BROAD
        ),
        ModelInfo(
            name = "Gemma 4 E2B",
            filename = "gemma-4-E2B_q4_0-it.gguf",
            remoteUri = Uri.parse("https://huggingface.co/google/gemma-4-E2B-it-qat-q4_0-gguf/resolve/main/gemma-4-E2B_q4_0-it.gguf"),
            releaseDate = LocalDate.parse("2026-03-25"),
            description = "Google \u00B7 QAT on-device vision model \u00B7 3.35Gb + 532Mb mmproj",
            logoRes = R.drawable.logo_google,
            supportedLanguages = MULTILINGUAL_BROAD,
            // Vision is quant-independent: the QAT q4_0 text model uses the same
            // BF16 mmproj as the Q4_K_M build (validated on-device).
            mmprojFilename = "mmproj-gemma-4-E2B-it-Q8_0.gguf",
            mmprojUri = Uri.parse("https://huggingface.co/ggml-org/gemma-4-E2B-it-GGUF/resolve/main/mmproj-gemma-4-E2B-it-Q8_0.gguf")
        ),
        ModelInfo(
            name = "Gemma 4 E4B",
            filename = "gemma-4-E4B_q4_0-it.gguf",
            remoteUri = Uri.parse("https://huggingface.co/google/gemma-4-E4B-it-qat-q4_0-gguf/resolve/main/gemma-4-E4B_q4_0-it.gguf"),
            releaseDate = LocalDate.parse("2026-03-25"),
            description = "Google \u00B7 QAT on-device vision model \u00B7 5.15Gb + 545Mb mmproj",
            logoRes = R.drawable.logo_google,
            supportedLanguages = MULTILINGUAL_BROAD,
            mmprojFilename = "mmproj-gemma-4-E4B-it-Q8_0.gguf",
            mmprojUri = Uri.parse("https://huggingface.co/ggml-org/gemma-4-E4B-it-GGUF/resolve/main/mmproj-gemma-4-E4B-it-Q8_0.gguf")
        ),
        ModelInfo(
            name = "Gemma 4 12B",
            filename = "gemma-4-12b-it-qat-q4_0.gguf",
            remoteUri = Uri.parse("https://huggingface.co/google/gemma-4-12B-it-qat-q4_0-gguf/resolve/main/gemma-4-12b-it-qat-q4_0.gguf"),
            releaseDate = LocalDate.parse("2026-06-03"),
            description = "Google \u00B7 QAT advanced chat model \u00B7 6.98Gb",
            logoRes = R.drawable.logo_google,
            supportedLanguages = MULTILINGUAL_BROAD
        ),
        // Legacy Q4_K_M builds, superseded by the official QAT Q4_0 above. Kept
        // so previously-downloaded files keep their identity; not offered for
        // download (hidden unless present on disk via the `deprecated` flag).
        ModelInfo(
            name = "Gemma 4 E2B (Q4_K_M)",
            filename = "gemma-4-E2B-it-Q4_K_M.gguf",
            remoteUri = Uri.parse("https://huggingface.co/lmstudio-community/gemma-4-E2B-it-GGUF/resolve/main/gemma-4-E2B-it-Q4_K_M.gguf"),
            releaseDate = LocalDate.parse("2026-03-25"),
            description = "Google \u00B7 Efficient vision model \u00B7 3.43Gb + 532Mb mmproj",
            logoRes = R.drawable.logo_google,
            supportedLanguages = MULTILINGUAL_BROAD,
            deprecated = true,
            mmprojFilename = "mmproj-gemma-4-E2B-it-Q8_0.gguf",
            mmprojUri = Uri.parse("https://huggingface.co/ggml-org/gemma-4-E2B-it-GGUF/resolve/main/mmproj-gemma-4-E2B-it-Q8_0.gguf")
        ),
        ModelInfo(
            name = "Gemma 4 E4B (Q4_K_M)",
            filename = "gemma-4-E4B-it-Q4_K_M.gguf",
            remoteUri = Uri.parse("https://huggingface.co/lmstudio-community/gemma-4-E4B-it-GGUF/resolve/main/gemma-4-E4B-it-Q4_K_M.gguf"),
            releaseDate = LocalDate.parse("2026-03-25"),
            description = "Google \u00B7 Efficient vision model \u00B7 5.34Gb + 545Mb mmproj",
            logoRes = R.drawable.logo_google,
            supportedLanguages = MULTILINGUAL_BROAD,
            deprecated = true,
            mmprojFilename = "mmproj-gemma-4-E4B-it-Q8_0.gguf",
            mmprojUri = Uri.parse("https://huggingface.co/ggml-org/gemma-4-E4B-it-GGUF/resolve/main/mmproj-gemma-4-E4B-it-Q8_0.gguf")
        ),
        ModelInfo(
            name = "Gemma 4 12B (Q4_K_M)",
            filename = "gemma-4-12B-it-Q4_K_M.gguf",
            remoteUri = Uri.parse("https://huggingface.co/ggml-org/gemma-4-12B-it-GGUF/resolve/main/gemma-4-12B-it-Q4_K_M.gguf"),
            releaseDate = LocalDate.parse("2026-06-03"),
            description = "Google \u00B7 Advanced chat model \u00B7 7.38Gb",
            logoRes = R.drawable.logo_google,
            supportedLanguages = MULTILINGUAL_BROAD,
            deprecated = true
        ),
        ModelInfo(
            name = "Qwen2.5 0.5B",
            filename = "Qwen2.5-0.5B-Instruct-Q4_K_M.gguf",
            remoteUri = Uri.parse("https://huggingface.co/lmstudio-community/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/Qwen2.5-0.5B-Instruct-Q4_K_M.gguf"),
            releaseDate = LocalDate.parse("2024-09-19"),
            description = "Alibaba \u00B7 Ultra-lightweight chat model \u00B7 398Mb",
            logoRes = R.drawable.logo_qwen,
            supportedLanguages = QWEN25_LANGS
        ),
        ModelInfo(
            name = "Qwen2.5 1.5B",
            filename = "Qwen2.5-1.5B-Instruct-Q4_K_M.gguf",
            remoteUri = Uri.parse("https://huggingface.co/lmstudio-community/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/Qwen2.5-1.5B-Instruct-Q4_K_M.gguf"),
            releaseDate = LocalDate.parse("2024-09-19"),
            description = "Alibaba \u00B7 Compact chat model \u00B7 986Mb",
            logoRes = R.drawable.logo_qwen,
            supportedLanguages = QWEN25_LANGS
        ),
        ModelInfo(
            name = "Phi3.5 mini",
            filename = "Phi-3.5-mini-instruct-Q4_K_M.gguf",
            remoteUri = Uri.parse("https://huggingface.co/bartowski/Phi-3.5-mini-instruct-GGUF/resolve/main/Phi-3.5-mini-instruct-Q4_K_M.gguf"),
            releaseDate = LocalDate.parse("2024-08-20"),
            description = "Microsoft \u00B7 Compact chat model \u00B7 2.2Gb",
            logoRes = R.drawable.logo_microsoft,
            supportedLanguages = PHI_LANGS
        ),
        ModelInfo(
            name = "Mistral 7B",
            filename = "Mistral-7B-Instruct-v0.3-Q4_K_M.gguf",
            remoteUri = Uri.parse("https://huggingface.co/lmstudio-community/Mistral-7B-Instruct-v0.3-GGUF/resolve/main/Mistral-7B-Instruct-v0.3-Q4_K_M.gguf"),
            releaseDate = LocalDate.parse("2024-05-22"),
            description = "Mistral \u00B7 General-purpose chat model \u00B7 4.37Gb",
            logoRes = R.drawable.logo_mistral,
            supportedLanguages = MISTRAL_LANGS
        ),
        ModelInfo(
            name = "Llama 3.1 8B",
            filename = "Meta-Llama-3.1-8B-Instruct-Q4_K_M.gguf",
            remoteUri = Uri.parse("https://huggingface.co/lmstudio-community/Meta-Llama-3.1-8B-Instruct-GGUF/resolve/main/Meta-Llama-3.1-8B-Instruct-Q4_K_M.gguf"),
            releaseDate = LocalDate.parse("2024-07-23"),
            description = "Meta \u00B7 General-purpose chat model \u00B7 4.92Gb",
            logoRes = R.drawable.logo_meta,
            supportedLanguages = LLAMA_LANGS
        ),
        ModelInfo(
            name = "Gemma2 9B",
            filename = "gemma-2-9b-it-Q4_K_M.gguf",
            remoteUri = Uri.parse("https://huggingface.co/bartowski/gemma-2-9b-it-GGUF/resolve/main/gemma-2-9b-it-Q4_K_M.gguf"),
            releaseDate = LocalDate.parse("2024-06-27"),
            description = "Google \u00B7 Advanced chat model \u00B7 5.44Gb",
            logoRes = R.drawable.logo_google,
            supportedLanguages = ENGLISH_ONLY
        ),
        ModelInfo(
            name = "GPT-OSS 20B",
            filename = "gpt-oss-20b-mxfp4.gguf",
            remoteUri = Uri.parse("https://huggingface.co/ggml-org/gpt-oss-20b-GGUF/resolve/main/gpt-oss-20b-mxfp4.gguf"),
            releaseDate = LocalDate.parse("2025-08-05"),
            description = "OpenAI \u00B7 Large reasoning MoE model \u00B7 12.11Gb",
            logoRes = R.drawable.logo_openai,
            supportedLanguages = MULTILINGUAL_BROAD
        )
    )

    // Best-effort capability flags by filename, assigned by model family. The
    // authoritative source is each GGUF's embedded chat template, which is only
    // readable after the model loads; once loaded, the detected capabilities are
    // cached and override these (see ModelInfo.resolveCapabilities). A wrong flag
    // here only mis-paints a list badge \u2014 it never affects whether tools actually
    // run, which is gated separately on the loaded model's real capability.
    //
    // Verified against every catalog GGUF's embedded template (jinja::caps) by
    // :model-harness. Llama 3.2 1B, Phi-4 mini and Mistral 7B were listed here
    // but their templates report supports_tools=false, so the badge promised a
    // capability the engine would never enable \u2014 removed.
    private val TOOL_CAPABLE = setOf(
        "Qwen3-0.6B-Q4_K_M.gguf",
        "Qwen3-1.7B-Q4_K_M.gguf",
        "Qwen3-4B-Q4_K_M.gguf",
        "Qwen_Qwen3.5-0.8B-IQ4_XS.gguf",
        "Qwen_Qwen3.5-2B-IQ4_XS.gguf",
        "Qwen_Qwen3.5-4B-IQ4_XS.gguf",
        "Llama-3.2-3B-Instruct-Q4_K_M.gguf",
        "LFM2.5-350M-Q4_K_M.gguf",
        "LFM2.5-1.2B-Instruct-Q4_K_M.gguf",
        "LFM2.5-1.2B-Thinking-Q4_K_M.gguf",
        "LFM2.5-2.6B-Q4_K_M.gguf",
        "HuggingFaceTB_SmolLM3-3B-Q4_K_M.gguf",
        "MiniCPM5-1B-Q4_K_M.gguf",
        "Ministral-3-3B-Instruct-2512-Q4_K_M.gguf",
        "Ministral-3-3B-Reasoning-2512-Q4_K_M.gguf",
        "Ministral-3-8B-Instruct-2512-Q4_K_M.gguf",
        "Ministral-3-8B-Reasoning-2512-Q4_K_M.gguf",
        "granite-4.0-micro-Q4_K_M.gguf",
        "granite-4.0-h-tiny-Q4_K_M.gguf",
        "granite-4.1-3b-Q4_K_M.gguf",
        "granite-4.1-8b-Q4_K_M.gguf",
        "NVIDIA-Nemotron3-Nano-4B-Q4_K_M.gguf",
        "gemma-4-E2B_q4_0-it.gguf",
        "gemma-4-E4B_q4_0-it.gguf",
        "gemma-4-12b-it-qat-q4_0.gguf",
        "gemma-4-E2B-it-Q4_K_M.gguf",
        "gemma-4-E4B-it-Q4_K_M.gguf",
        "gemma-4-12B-it-Q4_K_M.gguf",
        "Qwen2.5-0.5B-Instruct-Q4_K_M.gguf",
        "Qwen2.5-1.5B-Instruct-Q4_K_M.gguf",
        "Meta-Llama-3.1-8B-Instruct-Q4_K_M.gguf",
        "gpt-oss-20b-mxfp4.gguf",
    )
    private val THINKING_CAPABLE = setOf(
        "Qwen3-0.6B-Q4_K_M.gguf",
        "Qwen3-1.7B-Q4_K_M.gguf",
        "Qwen3-4B-Q4_K_M.gguf",
        "Qwen_Qwen3.5-0.8B-IQ4_XS.gguf",
        "Qwen_Qwen3.5-2B-IQ4_XS.gguf",
        "Qwen_Qwen3.5-4B-IQ4_XS.gguf",
        "DeepSeek-R1-Distill-Qwen-1.5B-Q4_K_M.gguf",
        "DeepSeek-R1-Distill-Qwen-7B-Q4_K_M.gguf",
        "LFM2.5-350M-Q4_K_M.gguf",
        "LFM2.5-1.2B-Instruct-Q4_K_M.gguf",
        "LFM2.5-1.2B-Thinking-Q4_K_M.gguf",
        "LFM2.5-2.6B-Q4_K_M.gguf",
        "HuggingFaceTB_SmolLM3-3B-Q4_K_M.gguf",
        "MiniCPM5-1B-Q4_K_M.gguf",
        "Ministral-3-3B-Reasoning-2512-Q4_K_M.gguf",
        "Ministral-3-8B-Reasoning-2512-Q4_K_M.gguf",
        "NVIDIA-Nemotron3-Nano-4B-Q4_K_M.gguf",
        "gemma-4-E2B_q4_0-it.gguf",
        "gemma-4-E4B_q4_0-it.gguf",
        "gemma-4-12b-it-qat-q4_0.gguf",
        "gemma-4-E2B-it-Q4_K_M.gguf",
        "gemma-4-E4B-it-Q4_K_M.gguf",
        "gemma-4-12B-it-Q4_K_M.gguf",
        "gpt-oss-20b-mxfp4.gguf",
    )

    // Measured with :model-harness: thinking on must produce a <think> block and
    // thinking off must suppress it. Where a model disagrees with its own
    // template, this is what the UI follows — see ThinkingMode.
    private val THINKING_MODE = mapOf(
        // Templates advertise a thinking mode these models never actually use,
        // so the toggle would appear and do nothing.
        "Ministral-3-3B-Instruct-2512-Q4_K_M.gguf" to ThinkingMode.NONE,
        "LFM2.5-1.2B-Instruct-Q4_K_M.gguf" to ThinkingMode.NONE,
        "LFM2.5-350M-Q4_K_M.gguf" to ThinkingMode.NONE,

        // Reasoning-tuned: they keep thinking with the flag off, so offering
        // "off" is a promise the model will not keep.
        "DeepSeek-R1-Distill-Qwen-1.5B-Q4_K_M.gguf" to ThinkingMode.ALWAYS,
        "LFM2.5-1.2B-Thinking-Q4_K_M.gguf" to ThinkingMode.ALWAYS,
        "LFM2.5-2.6B-Q4_K_M.gguf" to ThinkingMode.ALWAYS,
        "Ministral-3-3B-Reasoning-2512-Q4_K_M.gguf" to ThinkingMode.ALWAYS,

        // Verified to honour the toggle in both directions.
        "Qwen_Qwen3.5-0.8B-IQ4_XS.gguf" to ThinkingMode.OPTIONAL,
        "Qwen_Qwen3.5-2B-IQ4_XS.gguf" to ThinkingMode.OPTIONAL,
        "Qwen_Qwen3.5-4B-IQ4_XS.gguf" to ThinkingMode.OPTIONAL,
        "gemma-4-E2B_q4_0-it.gguf" to ThinkingMode.OPTIONAL,
        "gemma-4-E4B_q4_0-it.gguf" to ThinkingMode.OPTIONAL,
        "HuggingFaceTB_SmolLM3-3B-Q4_K_M.gguf" to ThinkingMode.OPTIONAL,
        "MiniCPM5-1B-Q4_K_M.gguf" to ThinkingMode.OPTIONAL,
        "NVIDIA-Nemotron3-Nano-4B-Q4_K_M.gguf" to ThinkingMode.OPTIONAL,
    )

    val allModels: List<ModelInfo> = rawModels.map { model ->
        model.copy(
            supportsTools = model.filename in TOOL_CAPABLE,
            supportsThinking = model.filename in THINKING_CAPABLE,
            thinkingMode = THINKING_MODE[model.filename] ?: ThinkingMode.UNKNOWN,
        )
    }

    /**
     * Embedding model powering document (RAG) search. Deliberately NOT part
     * of [rawModels]/[allModels]: it can't chat, so it must never appear in
     * the model picker. Downloaded on demand through the regular download
     * pipeline when the user first attaches a document.
     */
    val embeddingModel: ModelInfo = ModelInfo(
        name = "EmbeddingGemma 300M",
        filename = "embeddinggemma-300m-Q4_0.gguf",
        remoteUri = Uri.parse("https://huggingface.co/unsloth/embeddinggemma-300m-GGUF/resolve/main/embeddinggemma-300m-Q4_0.gguf"),
        releaseDate = LocalDate.parse("2025-09-04"),
        description = "Google · Document embedding model · 278Mb",
        logoRes = R.drawable.logo_google,
    )

    /**
     * Speech-recognition model powering voice dictation. Like
     * [embeddingModel] it is deliberately NOT part of [rawModels]/[allModels]:
     * it can't chat, so it must never appear in the model picker. Downloaded on
     * demand through the regular download pipeline when the user first taps the
     * microphone.
     *
     * Runs on the vendored parakeet.cpp engine rather than llama.cpp — no
     * chat model is involved, so dictation works whatever is loaded. The q4_k
     * quant is the publisher's own recommendation for on-device use.
     */
    val dictationModel: ModelInfo = ModelInfo(
        name = "Parakeet TDT 0.6B v3",
        filename = "tdt-0.6b-v3-q4_k.gguf",
        remoteUri = Uri.parse("https://huggingface.co/mudler/parakeet-cpp-gguf/resolve/main/tdt-0.6b-v3-q4_k.gguf"),
        releaseDate = LocalDate.parse("2025-08-14"),
        description = "NVIDIA · Voice dictation · 675Mb",
        logoRes = R.drawable.logo_nvidia,
        supportedLanguages = PARAKEET_LANGS,
    )

    /**
     * Get all known model filenames (including mmproj files for vision models,
     * the embedding model and the dictation model, so the storage scan never
     * surfaces those files as "custom models")
     */
    val knownFilenames: Set<String> = (allModels.flatMap { model ->
        listOfNotNull(model.filename, model.mmprojFilename)
    } + embeddingModel.filename + dictationModel.filename).toSet()

    /**
     * Get model by filename
     */
    fun getByFilename(filename: String): ModelInfo? =
        when (filename) {
            embeddingModel.filename -> embeddingModel
            dictationModel.filename -> dictationModel
            else -> allModels.find { it.filename == filename }
        }
    
    /**
     * Get display name for a filename
     */
    fun getDisplayName(filename: String): String = getByFilename(filename)?.name ?: filename.removeSuffix(".gguf")
    
    private fun formatFileSize(bytes: Long): String {
        val gb = bytes / 1_000_000_000.0
        return if (gb >= 1.0) "%.2fGb".format(gb) else "%dMb".format(bytes / 1_000_000)
    }

    /**
     * Create a ModelInfo for a custom (user-provided) GGUF file.
     */
    fun createCustomModelInfo(filename: String, name: String, sizeBytes: Long): ModelInfo {
        val sizeLabel = formatFileSize(sizeBytes)
        return ModelInfo(
            name = name.ifEmpty { filename.removeSuffix(".gguf") },
            filename = filename,
            remoteUri = null,
            releaseDate = null,
            description = "Custom model \u00B7 $sizeLabel",
            logoRes = R.drawable.penrose_triangle
        )
    }

    /**
     * Resolve a model's effective mmproj against the files actually present on
     * disk ([onDiskFilenames]). A catalog model keeps its declared projector
     * while that exact file is present; when the declared one is missing (or
     * the model declares none — e.g. a custom GGUF), we fall back to a
     * projector in the folder whose name matches by convention. This is what
     * lets sideloaded/custom models and manually-placed projectors light up
     * vision. The declared [ModelInfo.mmprojUri] (download source) is preserved
     * so the normal download path still works.
     */
    fun resolveMmproj(model: ModelInfo, onDiskFilenames: Set<String>): ModelInfo {
        val declared = model.mmprojFilename
        if (declared != null && declared in onDiskFilenames) return model
        val paired = MmprojPairing.findMmprojFor(model.filename, onDiskFilenames)
        return if (paired == null || paired == declared) model
        else model.copy(mmprojFilename = paired)
    }

    /**
     * Get models with their download status.
     */
    fun getModelsWithStatus(
        downloadedFilenames: Set<String>,
        customModels: List<ModelInfo> = emptyList()
    ): List<ModelWithStatus> {
        fun statusFor(model: ModelInfo, isDownloaded: Boolean): ModelWithStatus {
            val resolved = resolveMmproj(model, downloadedFilenames)
            return ModelWithStatus(
                model = resolved,
                isDownloaded = isDownloaded,
                isMmprojDownloaded = resolved.mmprojFilename != null &&
                    resolved.mmprojFilename in downloadedFilenames,
            )
        }
        val knownModels = allModels
            .sortedByDescending { it.releaseDate }
            // Deprecated entries are recognized but never offered for download:
            // only surface them when the file is actually present on disk.
            .filter { !it.deprecated || it.filename in downloadedFilenames }
            .map { model -> statusFor(model, isDownloaded = model.filename in downloadedFilenames) }
        val customWithStatus = customModels.map { model -> statusFor(model, isDownloaded = true) }
        return customWithStatus + knownModels
    }
}
