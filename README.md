
<h1 align="center">LM Playground</h1>

<p align="center">
<img src="logo.png"/>
</p>

LM Playground is an Android application for running Large Language Models locally on-device. Download models, load them in one tap, and chat - all offline, all private. Powered by [llama.cpp](https://github.com/ggml-org/llama.cpp) with GGUF-format models from [Hugging Face](https://huggingface.co/).

![preview](docs/banner.png)

## Features

- **On-device inference** - no cloud, no API keys, fully offline
- **Document Q&A (RAG)** - attach PDF, Word, EPUB, HTML, Markdown, or plain-text files and ask about them; on-device semantic search with EmbeddingGemma feeds the most relevant passages to any chat model
- **Vision/image input** - attach a photo from your gallery or camera and ask vision-capable models about it (Gemma 4, Gemma 3, Qwen 3.5, Ministral 3)
- **Rich markdown** in chat responses - headers, code blocks, lists, and more
- **Reasoning model support** - thinking steps from models like GPT-OSS, DeepSeek R1, and Nemotron are displayed in a styled section
- **Tools** - capable models can search the web, fetch a page, and run JavaScript mid-reply; each tool is off by default and enabled per model
- **Reusable system prompts** - save a persona, tone, or output format once and apply it to any model
- **Chat history** - conversations are saved and organized; pin, rename, delete, and resume sessions from the sidebar
- **Custom GGUF models** - load your own model files from any source alongside the built-in catalog
- **Reliable background downloads** - custom download engine with OkHttp and WorkManager, progress notifications with speed and ETA, automatic resume on network interruptions
- **Storage management** - choose where to keep multi-GB model files with Android's Storage Access Framework
- **ARM optimized** - KleidiAI kernels and OpenMP for faster generation on arm64 devices
- **Large-screen ready** - tablets, foldables, and Chromebooks get a permanent sessions sidebar, list-detail Settings, and freeform window resize support

## Supported Models

| Family | Sizes | Provider |
|--------|-------|----------|
| GPT-OSS | 20B | OpenAI |
| Qwen 3.5 | 0.8B, 2B, 4B (all vision) | Alibaba |
| Qwen 3 | 0.6B, 1.7B, 4B | Alibaba |
| Gemma 4 | E2B, E4B (vision), 12B | Google |
| Gemma 3n | E2B, E4B | Google |
| Gemma 3 | 1B, 4B (vision) | Google |
| Nemotron 3 Nano | 4B | NVIDIA |
| Granite 4.2 | 3B | IBM |
| Granite 4.1 | 8B | IBM |
| Granite 4.0 | Micro, H-Tiny | IBM |
| DeepSeek R1 Distill | 1.5B, 7B | DeepSeek |
| Phi-4 mini | 3.8B | Microsoft |
| LFM2.5 | 350M, 1.2B Thinking, 2.6B | Liquid AI |
| SmolLM3 | 3B | Hugging Face |
| MiniCPM5 | 1B, 2B | OpenBMB |
| Spark-X2.5 | 4B | XHToken |
| Ministral 3 | 3B, 8B (Instruct & Reasoning, all vision) | Mistral |
| Llama 3.2 | 1B, 3B | Meta |
| Llama 3.1 | 8B | Meta |

<details>
<summary>Legacy models</summary>

| Family | Sizes | Provider |
|--------|-------|----------|
| Qwen 2.5 | 0.5B, 1.5B | Alibaba |
| Phi 3.5 mini | 3.8B | Microsoft |
| Mistral v0.3 | 7B | Mistral |
| Gemma 2 | 9B | Google |

</details>

Most models use Q4_K_M quantization; Qwen 3.5 uses Q3_K_M, and GPT-OSS ships in its native MXFP4 format. See [`ModelInfoProvider.kt`](app/src/main/java/com/druk/lmplayground/models/ModelInfoProvider.kt) for the full list.

## Install

If you're just looking to install LM Playground, you can find it on [Google Play](https://play.google.com/store/apps/details?id=com.druk.lmplayground). If you're a developer wanting to contribute, read on.

## Build Instructions

Prerequisites:
* Android Studio [2024.3.1+](https://developer.android.com/studio/releases)
* NDK 27.2.12479018
* CMake 3.31.6

1. Clone the repository with submodules:
```
git clone --recurse-submodules https://github.com/andriydruk/LMPlayground.git
```
2. Open the project in Android Studio: `File` > `Open` > Select the cloned repository.
3. Connect an Android device or start an emulator.
4. Run the application using `Run` > `Run 'app'` or the play button in Android Studio.

## License

This project is licensed under the [MIT License](LICENSE).

## Acknowledgments

This project is built on [llama.cpp](https://github.com/ggml-org/llama.cpp). Models are GGUF-format with Q4_K_M quantization sourced from [Hugging Face](https://huggingface.co/).

## Contact

If you have any questions, suggestions, or issues, feel free to open an issue or contact me directly at [me@andriydruk.com](mailto:me@andriydruk.com).
