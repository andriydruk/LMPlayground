# 3. C++ reuse plan

Goal: **one engine implementation** in `core/`, compiled into the Android
app, the macOS harness and the iOS app. Platform code is limited to (a) the
language bridge and (b) services the OS has to provide.

## 3.1 Rule for what goes into the shared core

A piece of logic belongs in `core/llamacore` when all three hold:

1. **It runs on the engine side of Android's process boundary.** On
   Android, anything in C++ runs in the `:llama` process. Logic that needs
   the main process (Room, OkHttp, the JS sandbox, `Context`) would have to
   call back across AIDL.
2. **It is synchronous and has no platform library dependency.** It may use
   llama.cpp, ggml, mtmd, parakeet, nlohmann/json and `vendor/hash`.
3. **It touches engine input or output directly.** Prompts, tokens, KV
   state, model files, audio samples.

Logic that fails the rule but is small and pure is **ported to Swift and
pinned by shared golden fixtures** (§3.5).

## 3.2 Reused as is (Tier 0)

| Component | Source (after M0) | Notes for iOS |
|-----------|-------------------|---------------|
| Generation session | `core/llamacore/LlamaGenerationSession.cpp` (1422 lines) | Prompt rendering through common chat templates, incremental prefix and KV reuse, context compaction, reasoning budget, tool-call grammar sampler, tool-result delta feeding, abort during prefill, vision turns, replay, preamble KV cache. **No changes.** |
| Model | `LlamaModel.cpp` | The non-Android branch is already the Metal path. Chat-template override works as is. |
| Embeddings | `LlamaEmbeddingSession.cpp` | Drop the stray `jni.h` include. |
| Logging | `lmp_log.h` | Add an `__APPLE__` branch → `os_log` (subsystem `com.druk.lmplayground`). |
| llama.cpp, common, ggml-metal, mtmd | `core/llama.cpp` | |
| parakeet | `core/llama.cpp/tools/parakeet` (fork) | Static linkage supported (`ggml_dl_compat.hpp`). |

## 3.3 Moved into the core first (Tier A)

These are extracted from `native-lib.cpp` / `AsrEngine.cpp` in M0 (the
third restructure commit). The JNI files become pure marshalling.

| New core unit | Extracted from | Content |
|---------------|----------------|---------|
| `EngineRuntime.{h,cpp}` | `NativeLlamaCpp_init`, `log_callback`, `systemInfo` | `engineInit(EngineConfig)`: log routing (llama, ggml, mtmd → `lmp_log`), backend loading (dynamic on Android and host, no-op for static Apple builds), device enumeration, `llama_backend_init`. Returns a `DeviceReport`. |
| `VisionDevicePolicy.{h,cpp}` | `clipVulkanIsKnownGood`, sentinel functions, `clipVisionDevice` | One interface. **Android policy:** the Vulkan allowlist + crash sentinel, unchanged. **Apple policy:** the Metal device, or CPU in the simulator. **Host policy:** first GPU, with the `LMP_MTMD_BACKEND` override. |
| `LoadSentinel.{h,cpp}` | generalised CLIP sentinel | A file-marker bracket around crash-prone steps (model load, mmproj init, first encode). On the next launch it reports "model X crashed while loading", which replaces the safety net Android gets from `:llama`. |
| `ModelProbe.{h,cpp}` | `probeModelMetadata` | GGUF header read: name, architecture, parameter count, `context_length`, layer/head dims (for the KV estimate), chat-template presence, and the file kind (LLM / mmproj / ASR). |
| `Utf8.h` | lambda in `NativeLlamaSession_generate` | Trims an incomplete trailing UTF-8 sequence. Needed by both bridges. |
| `AsrEngine.{h,cpp}` | `AsrEngine.cpp` | `AsrModel` / `AsrStream` RAII over the parakeet C API, plus thread config. The JNI part stays in `android/`. |
| `PreambleCache.{h,cpp}` | fingerprint + LRU prune from `PreambleCacheManager.kt` | `setPreambleCacheDir(dir, modelKey)`. SHA-1 via `vendor/hash`. The engine already owns these files. |
| `Threads.h` | `kMaxGenerationThreads`, `kMaxVisionThreads`, `kAsrThreads` | Per-platform defaults. On Apple, the performance-core count (`hw.perflevel0.physicalcpu`, 2 on iPhone). |

Added later, still shared:

| Unit | When | Content |
|------|------|---------|
| `LlamaGenerationSession::continueResponse()` | M3 | Resume the last assistant turn after an abort. Today an aborted turn is committed by `finalizeResponse()` and can't be resumed. The new primitive reopens that partial message and keeps sampling from the existing KV prefix, with no re-prefill. iOS needs it because backgrounding aborts generation. Android gets a JNI binding for interrupted messages too. |

## 3.4 Ported to Swift (Tier B)

| Logic | Android source | Lines | Why not C++ |
|-------|----------------|------:|-------------|
| Tool-call turn loop | `llamacpp/.../chat/ToolCallLoop.kt` | 118 | Tools run in Android's main process. A C++ loop would sit in `:llama` and need AIDL callbacks for every call. |
| Tool registry, pairing, web-link refs | `ToolRegistry.kt`, `ToolCallPairing.kt`, `WebLinkStore.kt` | ~200 | Same reasons as the loop. Trivial with Swift `Codable`. |
| Thinking-separator cleanup | `ResponseProcessor.kt` | 27 | One regex. |
| Dictation transcript assembly | `asr/DictationTranscript.kt` | 68 | Main-process UI state. |
| mmproj pairing | `MmprojPairing.kt` | 68 | File-name heuristic. |
| Text chunker | `rag/TextChunker.kt` | 105 | ICU `BreakIterator` → `NLTokenizer(.sentence)` on iOS. |
| RAG prompt builder, top-K | `RagPromptBuilder.kt`, `RagRepository.kt` | ~100 | Vectors live in the app database. Top-K with `vDSP`. |
| HTML → Markdown | `tools/HtmlToMarkdown.kt` | 244 | DOM walk over jsoup → SwiftSoup. |

## 3.5 Shared data instead of shared code

| Asset | Location after M0 | Consumers |
|-------|-------------------|-----------|
| Chat-template overrides | `core/resources/chat_templates/*.jinja` + `overrides.json` (filename → template) | Android assets (extra `srcDir`), iOS bundle resources. |
| Golden fixtures | `core/testdata/*.json` (ResponseProcessor, ToolCallPairing, DictationTranscript, WER, WebLinkStore, TextChunker, ToolCallLoop transcripts) | Kotlin unit tests and Swift unit tests. A behaviour change must update the fixture, so the other platform's tests then fail until it follows. |
| Harness fixtures | `android/model-harness` prompts, audio, expectations → `core/testdata/harness/` | `:model-harness` and LlamaKit's macOS tests. |
| Model catalog | `ModelInfoProvider.kt` (source of truth) + `ios/catalog/ios-catalog.json` (the subset verified on device, with measured numbers) | `generate-catalog.py` merges them into Swift. See [06 §6.6](06-build-and-release.md#66-catalog-and-strings-generation). |
| Strings (351 × 28 locales) | `android/app/src/main/res/values*/strings.xml` | `generate-strings.py` → `ios/LMPlayground/Localizable.xcstrings`. |

## 3.6 Android-only (not built for iOS)

AIDL, `LlamaService`, binder payload caps (`InferenceLimits`), chunked replay,
`fd:N` paths, SAF, the Vulkan allowlist and sentinel policy,
`GGML_BACKEND_DL`, and KleidiAI. The last one could be enabled on Apple
arm64, but with full Metal offload on every supported device the CPU matmul
path only matters in the simulator.

## 3.7 Reuse summary

| Layer | Android | iOS | Shared |
|-------|---------|-----|--------|
| Inference engine + ASR | C++ | same C++ | **100%** |
| Engine-side policy (init, device, sentinel, probe, preamble cache, continue) | C++ | same C++ | **100%** |
| Language bridge | JNI | ObjC++ | none (by nature) |
| Turn loop, tools, RAG and dictation glue | Kotlin | Swift | behaviour pinned by fixtures |
| Catalog, templates, strings, fixtures | data | generated from the same data | **100% of the data** |
| UI, persistence, downloads, storage, audio | Kotlin/Compose | Swift/SwiftUI | none (native UX) |
