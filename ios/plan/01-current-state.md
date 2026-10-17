# 1. Current state

## 1.1 Android app (main, 1.10.1)

```
app/  (Kotlin, ~20.9k lines)          UI (Compose + Fragments), Room, downloads,
                                      SAF storage, tools, RAG, dictation UI
  └─ LlamaService (:llama process)    AIDL service owning all native state
llamacpp/  (plain-JVM Kotlin, ~0.9k)  JNI stubs, GenerationSession, ToolCallLoop,
                                      ResponseProcessor, ToolRegistry,
                                      DictationTranscript, WER
app/src/main/cpp/  (C++, ~3.5k)       the engine (see below)
  └─ llama.cpp  (submodule)           fork andriydruk/llama.cpp-android,
                                      branch android-0.6.0, 3 carried patches
model-harness/  (Kotlin)              macOS capability harness driving the
                                      real engine over JNI with Metal
```

### Native layer, by portability

| File | Lines | Platform coupling |
|------|------:|-------------------|
| `LlamaGenerationSession.cpp` | 1422 | None. Pure llama.cpp/common/mtmd. Logging via `lmp_log.h`. |
| `LlamaModel.cpp` | 279 | One `#if __ANDROID__` block: CPU-only device list on Android, honour `n_gpu_layers` elsewhere (already the Metal path for the harness). |
| `LlamaEmbeddingSession.cpp` | 118 | None (a stray `#include <jni.h>` under `__ANDROID__`). |
| `LlamaCpp.h` | 301 | Declares the vision-device and Vulkan-sentinel hooks implemented in `native-lib.cpp`. |
| `lmp_log.h` | 56 | Already portable: logcat on Android, stderr elsewhere. |
| `AsrEngine.cpp` | 299 | JNI entry points mixed with portable helpers (thread config, PCM read). |
| `native-lib.cpp` | 824 | JNI shim, **plus about 250 lines of portable logic**: log routing, GGUF metadata probe, backend loading, GPU enumeration and vision-device choice, crash sentinel, UTF-8 trimming of streamed text. |

The macOS host build (`llamacpp/native/CMakeLists.txt`) already compiles
these exact files with Metal on, which is strong evidence that the core is
portable to Apple platforms. What doesn't port is the JNI glue and the parts
of `native-lib.cpp` that assume the Android process model.

### Fork patches carried on `android-0.6.0`

1. `fd:N` paths in `ggml_fopen` / `llama-mmap`, for SAF descriptors.
   **Not needed on iOS**: files have real paths there. It's harmless to
   carry.
2. Vocab duplicate tokens warn instead of aborting. **Load-bearing on iOS**:
   the engine runs in-process, so an abort kills the app, not a service.
3. `tools/parakeet` vendored (parakeet.cpp v0.4.0) linking the in-tree ggml.
   It works with both static and `GGML_BACKEND_DL` linkage
   (`ggml_dl_compat.hpp`).

The pinned ggml already includes the Metal "sticky error" behaviour.
`ggml_metal_synchronize` sets `has_error` instead of aborting, and the next
`graph_compute` returns `GGML_STATUS_FAILED`. This matters for backgrounding
(see [05](05-platform-research.md#52-background-execution-and-the-gpu)).

## 1.2 The August iOS prototype

Branch `claude/lmplayground-ios-plan-293f4d`, 5 commits on top of 1.9.1
(`e6b2b8058`), +4.8k lines across 49 files:

| Commit | Content |
|--------|---------|
| `0dd381010` | Moves the C++ to `core/llamacore/`, the submodule to `core/llama.cpp`, adds `llamacore_log.h`, `llamacore_utf8.h` and `LlamaModelProbe.*`, and slims `native-lib.cpp` |
| `862b2569e` | `ios/` app: XcodeGen `project.yml`, LlamaKit ObjC++ bridge (`LMKEngine`, `LMKModel`, `LMKGenerationSession`, `LMKEmbeddingSession`), SwiftUI shell, `build-xcframework.sh`, iOS CI workflow |
| `f79c8bfd5` | Generated model catalog (`generate-catalog.py` reads `ModelInfoProvider.kt`), background `URLSession` downloads, model library UI |
| `ead037831` | Message input and library usability fixes |
| `cb9a64962` | Capability caching, import validation, storage detail |

**Verified then:** the full loop in the simulator (catalog → 484 MB download →
load → streaming chat with thinking split and tok/s), 25 tests (7 bridge tests
with real generation, 18 catalog, pairing and memory-gate tests), and a device
arm64 build that links and signs with embedded Metal shaders.
**Not verified:** Metal generation on a physical device.

**Decision D1:** the branch isn't rebased or salvaged. Its worktree and branch
are deleted at the start of M0, and the iOS code is written fresh. Its
lessons are kept here and in the memory notes:

- The common archives are `libllama-common.a` + `libllama-common-base.a`.
  `libcpp-httplib.a` must also be linked, because `common/download.cpp`
  references httplib even though we never call it.
- Upstream's xcframework combine step runs `libtool` with stderr sent to
  `/dev/null`, so a wrong archive path silently produces a broken framework.
  Verify inputs and exported symbols explicitly.
- Never pipe `nm` into `grep -q` under `pipefail`.
- A dynamic `llama.framework` must be embedded by the **app**, not by another
  framework. iOS forbids nested frameworks.
- The simulator has no `nsurlsessiond`. Background `URLSession` fails with
  -1, so fall back to a default session there.
- C++ lambdas can't capture `__block` variables in `.mm` files.
- Test env vars need the `TEST_RUNNER_` prefix.
- SwiftUI: a `TextField` with padding after `.plain` style is only
  hit-testable on its glyphs. It needs `.contentShape` and `@FocusState`.

## 1.3 What changed on `main` since the prototype (47 commits)

Engine-relevant changes the prototype does not have:

| Change | Impact on iOS |
|--------|---------------|
| llama.cpp `b11200` → `0.6.0` | Rebuild the xcframework. The script's sed anchors must be re-checked (upstream added `libvendor-hash.a` to the combine list). |
| Host JVM build + `lmp_log.h` + chat-template override parameter | Duplicates the prototype's `llamacore_log.h`. Use `lmp_log.h` and add an `os_log` branch. |
| Parakeet dictation (`AsrEngine.cpp`, `tools/parakeet`) | New shared C++ the prototype never built for iOS. |
| `clipVisionDevice()`: mtmd now takes the device explicitly | The vision-device policy must have an Apple implementation (Metal device, CPU in the simulator). |
| Tool-grammar parse failure no longer aborts; KV cache cleared after a failed preamble load; `mtmd_input_text` zero-init; idempotent `loadMmprojModel` | Free on iOS once the core is shared. |
| `ToolCallLoop` / `GenerationSession` moved to the plain-JVM `:llamacpp` module | Defines the turn loop that Swift has to mirror (see [03](03-cpp-reuse.md)). |
| `ChatTemplateOverrides` (SmolLM3, LFM2.5-VL 450M) | The `.jinja` assets must be shared, not copied. |
| Catalog grew (MiniCPM5, Granite 4.2, Spark-X2.5, LFM2.5 VL, Parakeet/Nemotron ASR) | The generated catalog must be regenerated, and the generator must understand the new fields. |

**Conclusion:** cherry-picking `0dd381010` onto `main` would conflict in every
engine file, and parts of it are now redundant. The core move is redone on
`main` as part of the larger `android/` / `core/` / `ios/` restructure (see
[02-repo-restructure.md](02-repo-restructure.md)).

## 1.4 Toolchain and devices (checked 2026-10-12)

- Xcode 27.0 (27A266a). The only installed simulator runtime is iOS 18.5.
  **Install the iOS 26 runtime** (deployment target) and the iOS 27 runtime
  (current OS) before UI work.
- CMake 4.4.4 and XcodeGen are installed (Homebrew).
- Test devices: an **iPhone 16 Pro** (A18 Pro, 8 GB, Apple GPU family 9) and
  an **iPad with M1** (record its RAM size in the M1 spike). Both must be
  paired with Xcode for the M1 device spike.
