# Architecture

LM Playground runs GGUF models on-device through [llama.cpp](https://github.com/andriydruk/llama.cpp-android)
(a fork carrying one patch — see [fd:N paths](#fdn-file-descriptor-paths)).
This document maps the moving parts and the contracts between them.

## Process model

The app runs in two processes:

- **Main process** — UI (Compose + Fragments), Room persistence, downloads,
  storage. Everything the user touches.
- **`:llama` process** (release builds) — hosts `LlamaService`, the AIDL
  service that owns all native llama.cpp state. If native code crashes
  (bad GGUF, GPU driver bug, OOM), only this process dies; the app stays
  up, marks the in-flight message as interrupted, and offers a reload.
  Debug builds run the service in-process for easier debugging.

`App.onCreate` checks `ProcessUtils.isLlamaProcess()` and skips Room and
repository initialization in the `:llama` process.

## Engine access path

```
ConversationViewModel        UI state (LiveData) + listeners
  ├── ModelRuntime           owns native handles: model, session, file
  │                          descriptor, generation job; load / recreate /
  │                          crash-recovery / teardown transitions
  ├── GenerationCoordinator  one generation turn: tool hydration, preamble
  │                          cache, addMessage → generateAll loop with
  │                          tool rounds, guaranteed cleanup
  ├── ChatSessionStore       persistence facade over ChatRepository +
  │                          SystemPromptRepository (Room)
  ├── ChatImageStore         chat image copies + vision downscaling
  ├── PreambleCacheManager   persistent system-prompt/tools KV-cache files
  └── InferenceNotificationUpdater   foreground-service notification lines

com.druk.llamacpp            AIDL proxy layer (public API of the engine)
  ├── InferenceClient        service binding, binder-death → Crashed state
  ├── LlamaCpp / LlamaModel / LlamaGenerationSession   typed proxies
  ├── LlamaEmbeddingSession  typed proxy for pooled-embedding contexts
  └── jni/Native*            thin `external fun` JNI stubs

LlamaService (:llama)        binds JNI ↔ AIDL; GenerationWorker thread
app/src/main/cpp             C++ session: prompt build, KV-cache reuse,
                             sampling, vision (mtmd), tool-call grammar
```

## Document Q&A (RAG)

Attaching a document to a chat runs extract → chunk → embed → store, then
every user turn in that chat retrieves the most relevant chunks and
prepends them to the *wire copy* of the message (UI and Room keep the
original; history replay resends originals since retrieval re-runs per
turn — see `ConversationViewModel.buildWireContent`).

- `com.druk.lmplayground.rag` — `DocumentTextExtractors` (PDF via
  pdfbox-android, DOCX via ZIP+XmlPullParser, EPUB/HTML via jsoup, plain
  text), `TextChunker` (paragraph/sentence-aware, ~1000 chars + overlap),
  `RagRepository` (indexing job on an application-scoped coroutine so it
  survives navigation; cosine top-K in Kotlin over the session's vectors),
  `EmbeddingModelManager` (EmbeddingGemma task prefixes).
- Vectors live in Room (`rag_documents`, `rag_chunks`; embeddings are
  L2-normalized float32 BLOBs) keyed by session — brute-force dot product
  is milliseconds at on-device scale, so no vector index. The original
  file is never copied; `rag_documents.sourceUri` plus a persisted SAF
  read grant lets the chat's document chip reopen it (graceful toast when
  the file or grant is gone).
- The embedding model (EmbeddingGemma 300M, downloaded on demand like any
  catalog model but hidden from the chat picker) loads through the normal
  `loadModel` path; `createEmbeddingSession` makes a separate
  embeddings-enabled context (mean pooling) in `LlamaService`, independent
  of generation sessions. It stays warm for 60 s after the last embed
  call, then unloads (`EmbeddingModelManager`) so ~300 MB doesn't sit
  next to a multi-GB chat model.

## Threading contracts

- **`InferenceClient.requireConnected()` must not run on the main
  thread** — it can block up to 10 s racing the service bind. Debug
  builds enforce this with a `check()`. Coroutine callers use
  `awaitConnected()` instead.
- Generation runs on a dedicated thread in the service
  (`GenerationWorker`); streamed tokens arrive on binder threads. All
  `ModelRuntime.Listener` / `GenerationCoordinator.Listener` callbacks may
  fire from background dispatchers — the ViewModel only uses `postValue`
  and `Snapshot.withMutableSnapshot` there.
- `ModelRuntime` mutators are called from the main dispatcher; they
  capture-and-null handles on the caller's thread before hopping to
  `Dispatchers.Default` for blocking native work.
- `setImageData` and `addMessage` are separate AIDL transactions on
  different binder threads; the staged image bytes are guarded by a mutex
  in the native session.

## fd:N file descriptor paths

Models live in a user-chosen SAF folder, which has no filesystem path.
The app opens a `ParcelFileDescriptor` and sends it over AIDL; the
service dups it and builds an `fd:N` pseudo-path that the fork's
`ggml_fopen` / `llama-mmap` understand. This is the only patch carried
on the llama.cpp fork. The app-side PFD is kept alive in `ModelRuntime`
for the model's lifetime (the mmap dies with the descriptor).

## AIDL payload budget

Binder transactions cap at ~1 MB. `InferenceLimits.MAX_PAYLOAD_BYTES`
gates every string crossing the boundary (messages, system prompts,
replayed history) — see `HistoryReplay.validateReplaySize` and the
pre-flight checks in the ViewModel. Session replay is chunked.

## Vulkan policy

The LLM always decodes on CPU (KleidiAI kernels on arm64). Vulkan is
reserved for the CLIP vision encoder, with two safety nets in
`native-lib.cpp`: a static GPU denylist, and a crash-sentinel file
written around the risky init — if it survives a process restart, Vulkan
vision is permanently disabled for that install and CLIP runs on CPU.

## Crash visibility

The release AAB packages native debug symbols
(`ndk.debugSymbolLevel = SYMBOL_TABLE` in `app/build.gradle.kts`), so
Google Play Console symbolicates `:llama` native crashes. This is
deliberate: no crash-reporting SDK, matching the app's fully-offline,
privacy-first positioning. Kotlin crashes deobfuscate via the R8 mapping
file that the AAB already carries.

## Test infrastructure

- **Unit tests** (`app/src/test`, JVM + Robolectric): pure logic
  (models, tools, history replay, tool-call mapping), Room-backed stores
  (in-memory DB), WorkManager enqueue policy (`WorkManagerTestInitHelper`).
  CI runs them with `-PskipScreenshots` — Paparazzi goldens are not
  git-tracked.
- **Paparazzi screenshots** (`app/src/test/.../screenshots`): Play Store
  screenshots, 28 locales; `recordPaparazziDebug` auto-organizes them
  into `fastlane/`.
- **Instrumented tests** (`app/src/androidTest`): real model loads and
  generation (`ModelGenerationTest` — needs GGUFs in `/data/local/tmp`),
  service/proxy lifecycle, ViewModel tool-call turns. CI runs
  `app:mvdApi35Check` on a managed emulator.
- Warning: `connectedAndroidTest` wipes the app's SAF folder grant on a
  real device; re-pick the folder before manual testing (`installDebug`).
