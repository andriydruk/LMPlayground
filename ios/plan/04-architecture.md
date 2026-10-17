# 4. iOS architecture

Platform: **iOS 26+**, iPhone and iPad, plus "Designed for iPad" on
Apple-silicon Macs. Installs are limited to the
`iphone-performance-gaming-tier` devices (iPhone 15 Pro+, M1+ iPads; D11). Swift 6 language mode, SwiftUI with Observation. The
Liquid Glass design comes from the iOS 26 SDK without custom styling.

## 4.1 Layers

```
┌───────────────────────────────────────────────────────────────┐
│ SwiftUI views: Conversation, Sessions, Models, Settings,      │
│ Prompts, Tools, Documents, Dictation                          │
├───────────────────────────────────────────────────────────────┤
│ @Observable @MainActor view models                            │
├───────────────────────────────────────────────────────────────┤
│ App services (Swift)                                          │
│  ModelRuntime     load/recreate/teardown, memory preflight,   │
│                   lifecycle (background, memory warning)      │
│  TurnRunner       one turn: image, RAG wire text, tool loop   │
│  ChatStore        GRDB persistence + observation              │
│  ModelStore       files, catalog, pairing, capability cache   │
│  DownloadManager  background URLSession, resume               │
│  ToolRegistry     web_search, web_fetch, run_javascript       │
│  RagIndex         extract → chunk → embed → store → top-K     │
│  Dictation        AVAudioEngine 16 kHz → AsrStream            │
├───────────────────────────────────────────────────────────────┤
│ LlamaKit.framework (iOS + macOS)                              │
│  Swift API: actor Engine, AsyncThrowingStream events          │
│  ObjC++ bridge: LMKEngine LMKModel LMKGenerationSession       │
│                 LMKEmbeddingSession LMKAsrModel LMKAsrStream  │
│  compiles core/llamacore/*.cpp                                │
├───────────────────────────────────────────────────────────────┤
│ llama.xcframework (static): llama, common, ggml-cpu,          │
│ ggml-metal (embedded metallib), ggml-blas, mtmd, parakeet     │
└───────────────────────────────────────────────────────────────┘
```

The engine runs **in-process**. iOS apps can't spawn helper processes, and
extensions can't host general compute. Crash safety comes from:

1. the vocab-warn fork patch,
2. `LoadSentinel` (a "crashed while loading model X" prompt on the next
   launch instead of a crash loop),
3. Metal sticky errors (a failed command buffer returns an error instead of
   aborting),
4. a memory preflight before every load,
5. full dSYM coverage, so every crash in App Store Connect is symbolicated
   ([06 §6.5](06-build-and-release.md#65-dsyms-every-build-no-exceptions)).

## 4.2 Bridge: ObjC++ framework built for iOS and macOS

- **Why ObjC++:** the engine API uses `std::function` callbacks, STL types,
  and headers that pull in `common.h` (nlohmann/json, minja). Swift's C++
  importer handles that poorly, and the interop mode would spread to every
  target that touches it. A small ObjC++ layer keeps C++ out of the module's
  public headers.
- **Why also macOS:** `LlamaKitTests` run on the Mac with Metal. That's
  fast, needs no simulator, and uses the same Apple GPU family as the phone.
  The iOS simulator can only run these graphs on the CPU. The macOS slice also
  serves "Designed for iPad" debugging.
- **Why not a Swift package:** SwiftPM can't compile sources or use header
  paths outside the package directory, and the shared C++ lives in `../core`.

### Swift API (sketch)

```swift
public actor Engine {
    public static let shared: Engine
    public nonisolated var deviceReport: DeviceReport { get }
    public func probe(_ url: URL) -> ModelMetadata?
    public func loadModel(at url: URL, options: LoadOptions,
                          progress: @Sendable (Double) -> Void) async throws -> Model
    public func lastLaunchCrash() -> SentinelReport?
}

public final class Model: Sendable {
    public let metadata: ModelMetadata            // size, ctxTrain, kv bytes/token, report
    public var capabilities: Capabilities { get } // thinking, tools, vision
    public func attachProjector(at url: URL) async throws
    public func makeSession(_ p: SamplerParams) async throws -> GenerationSession
    public func makeEmbeddingSession(contextSize: Int) async throws -> EmbeddingSession
}

public final class GenerationSession: Sendable {
    public func send(_ text: String, image: Data?, thinking: Bool)
        -> AsyncThrowingStream<GenerationEvent, Error>  // .delta, .toolCalls, .done(report), .contextLost
    public func submitToolResults(_ json: String, thinking: Bool)
        -> AsyncThrowingStream<GenerationEvent, Error>
    public func continueResponse() -> AsyncThrowingStream<GenerationEvent, Error>
    public func setTools(_ json: String)
    public func replay(_ turns: [(user: String, assistant: String)]) async throws
    public func setPreambleCache(dir: URL, modelKey: String)
    public nonisolated func abort()                   // any thread; interrupts prefill too
}

public final class AsrModel: Sendable {
    public func transcribe(_ pcm: [Float]) async throws -> String
    public func makeStream() throws -> AsrStream      // feed(_:) -> delta, finish() -> tail
}
```

- C++ `generate()` reports the *full* processed response on every callback.
  The bridge diffs it against the previous one, trims it with `Utf8.h`,
  emits deltas, and coalesces them to about 30 Hz before they reach the main
  actor.
- Native return codes (0 more / 1 done / 2 tool calls / other error) are
  mapped to events, the same contract as Android's `GenerationSession`.
- Cancelling the consuming `Task` calls `abort()`.

## 4.3 Threading

| Work | Where |
|------|-------|
| Engine calls for chat and embedding (load, addMessage, generate, embed) | One serial `DispatchQueue` (QoS `.userInitiated`), like Android's single `GenerationWorker` |
| ASR feed/finalize | Its own serial queue (parakeet state isn't re-entrant) |
| `abort()` | Any thread (atomic flag read by the llama abort callback) |
| Image downscale | `Task.detached` with ImageIO thumbnailing |
| RAG extraction and chunking | Background `Task`, writing through GRDB's `DatabasePool` |
| UI | `@MainActor` view models fed by `AsyncThrowingStream` and GRDB `ValueObservation` |

## 4.4 App lifecycle

| Event | Behaviour |
|-------|-----------|
| `scenePhase → .background` during generation | `abort()`, let the engine queue drain inside `beginBackgroundTask`, and save the partial answer as *interrupted*. On return, **Continue** calls `continueResponse()`, which resumes from the KV cache without re-prefill. No GPU work is submitted while in the background. |
| Metal error surfaced after a lifecycle change | `.contextLost` → `ModelRuntime` recreates the `llama_context` (the model stays loaded) and replays history. If the model itself errors, it reloads. |
| `didReceiveMemoryWarning` while idle | Unload the embedding model, then the CLIP context, then the chat model if the warning repeats. |
| Thermal `.serious` / `.critical` | Show a banner suggesting a smaller model. Never throttle silently. |
| Launch after a crash | `LoadSentinel` report → "Load anyway / Choose another model / Delete model". |

## 4.5 Memory policy

The per-process footprint limit, not physical RAM, decides what fits.

```
budget = os_proc_available_memory()     // re-read right before load
need   = weightsResident + kvBytesPerToken × nCtx + computeBuffers + headroom
```

- On the iPhone 16 Pro (8 GB, the tier floor) and the M1 iPad, measure in M1 how much of the mmap'd,
  Metal-wrapped weight file counts toward `phys_footprint`, along with the
  compute-buffer size, for one model per size class. Those numbers set
  `weightsResident` and `headroom`.
- The **catalog** shows only models verified on the 16 Pro
  (`ios/catalog/ios-catalog.json`, D6). The runtime preflight is the
  safety net for custom imports and for devices with a tighter limit.
- The context slider shows the KV cost (`kvBytesPerToken` from
  `ModelProbe`) and caps itself at what fits.

## 4.6 Storage

| Data | Location |
|------|----------|
| Downloaded and imported models | `Documents/Models/`, with `isExcludedFromBackup` set on each file. The folder is visible in Files and Finder (`UIFileSharingEnabled`, `LSSupportsOpeningDocumentsInPlace`), so GGUFs can be dropped in directly, much like Android's chosen folder. |
| Import from Files | `fileImporter`, then probe, then **copy** into `Models/` (with a free-space preflight). Don't mmap through bookmarks: iCloud files can be evicted. |
| Preamble KV caches | `Library/Caches/kv_preamble/` (rebuildable, purgeable) |
| Chat images, sentinel markers, database | `Library/Application Support/` |
| RAG source documents | Not copied. A security-scoped bookmark is stored to reopen the original (the iOS form of Android's persisted SAF grant). |

## 4.7 Project structure

- A committed `ios/LMPlayground.xcodeproj` using **folder-synchronised
  groups**, so new Swift files need no project edits. Build settings live in
  `ios/Config/*.xcconfig` (Base, Debug, Release, Version, LlamaKit), which
  keeps the project-file diffs small and settings reviewable.
- Targets: `LlamaKit` (framework, iOS + macOS), `LlamaKitTests` (macOS
  primary), `LMPlayground` (app), `LMPlaygroundTests`, `LMPlaygroundUITests`.
- Swift packages: **GRDB** (+ GRDBQuery), **SwiftSoup**, **ZIPFoundation**,
  **swift-markdown** (parser; we render it ourselves). Everything else is
  system frameworks (PDFKit, NaturalLanguage, Accelerate, AVFoundation,
  WebKit, PhotosUI, QuickLook).

## 4.8 Persistence: GRDB, mirroring Room

**Decision (D5): [GRDB](https://github.com/groue/GRDB.swift)** (SQLite).
Room v8 is SQLite too, so the iOS tables mirror Android's table for table.

Why GRDB:

1. **Explicit migrations.** Every schema change is a registered
   `DatabaseMigrator` step that can be tested against a copy of the previous
   database. User chat history can't be lost by accident. Android is on Room
   schema v8, so this schema will evolve too.
2. **Foreign keys with `ON DELETE CASCADE`.** Deleting a session reliably
   removes its messages, documents and chunks at the SQLite level.
3. **`DatabasePool` (WAL, concurrent readers).** RAG indexing writes in the
   background while the UI keeps reading.
4. **SwiftUI observation.** `ValueObservation` (with the GRDBQuery companion
   package for `@Query`-style views) reliably delivers background writes to
   the UI. SwiftData has documented problems here: `@ModelActor` writes not
   reaching `@Query`, and a related deadlock fixed only in the 27 betas.
5. **Maturity.** Years of production use, stable API, Swift 6
   concurrency support, FTS5, in-memory databases for tests.
6. **Embedding blobs.** Stored as `Data`, and read as one contiguous buffer
   per session for vDSP top-K, with no object-graph overhead.

### Schema v1 (mirrors Room v8)

| Table | Columns (as on Android) |
|-------|-------------------------|
| `chat_sessions` | `id` TEXT PK, `title`, `modelFilename`, `modelName`, `createdAt`, `updatedAt`, `pinned`, `contextSize`, `temperature`, `topP`, `repetitionPenalty`, `topK`, `minP`, `seed`, `thinkingBudget`, `systemPrompt`, `metadata` (JSON: tool states, web-link snapshot). Index: `updatedAt`. |
| `chat_messages` | `id` INTEGER PK, `sessionId` FK → sessions (cascade), `author`, `content`, `thinkingDurationSeconds`, `thinkingTokens`, `responseTokens`, `responseDurationSeconds`, `timestamp`, `imagePath`. iOS adds `interrupted` BOOL. Index: `sessionId, timestamp`. |
| `system_prompts` | `id` TEXT PK, `text`, `createdAt`, `updatedAt` (+ usage columns as `PromptUsage`) |
| `rag_documents` | `id` TEXT PK, `sessionId` FK → sessions (cascade), `displayName`, `mimeType`, `bookmark` BLOB (instead of `sourceUri`), `sizeBytes`, `status`, `chunkCount`, `embeddingDim`, `embeddingModel`, `createdAt` |
| `rag_chunks` | `id` INTEGER PK, `documentId` FK → documents (cascade), `ordinal`, `text`, `embedding` BLOB (L2-normalised float32). Index: `documentId, ordinal`. |

Chat image files on disk are deleted by `ChatStore` after a session delete
commits, because SQLite cascades don't reach the file system.

### Alternative considered: Blackbird

[Blackbird](https://github.com/marcoarment/Blackbird) was checked against
its source on 2026-10-13. It covers every column type we need (including
`Data` blobs), has SwiftUI live queries, FTS5 and in-memory databases.
Rejected for this app because:

- Migrations are automatic from the model struct, so renaming a property
  silently drops that column's data. That is a user-data-loss risk on chat
  history.
- No foreign keys or cascades, so integrity would depend on our own delete
  code.
- One serialised connection, so long writes delay UI reads.
- Beta status, single maintainer, and breaking changes are possible.

It would have worked with mitigations (manual cascades, schema-change rules,
batched writes, a pinned revision). Storage stays behind the `ChatStore` /
`RagStore` protocols either way.
