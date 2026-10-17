# 8. Milestones

There is one release, after M9, with full parity (D4). The engine and
device work comes first. Device checks run on the iPhone 16 Pro (the 8 GB
floor) and the M1 iPad (D9).
The sizes are rough ranges for one engineer working with Claude Code.

```
M0 restructure ─► M1 engine on Apple + device spike ─► M2 models ─► M3 chat ─► M4 vision
   (Android point release)                                                      │
                     M9 release ◄─ M8 settings/l10n/polish ◄─ M7 dictation ◄─ M6 RAG ◄─ M5 tools
```

## Status, 2026-10-17

The feature work for M1–M8 is in, on the branch, unpushed. What's left
needs the owner, a device, or both. The checkboxes in the sections below
are the original plan and aren't all ticked; this table is the current
state.

| Milestone | State | Evidence |
|-----------|-------|----------|
| M1 Engine | Done except the PLA-blocked entitlements re-measure | `1b49223c2`, `29d842978`; LlamaKit macOS tests 18/18; dSYM check passes on a Release archive (`verify-dsyms.sh`) |
| M2 Models | Done; catalog not yet decided (D6) | `61e688cea`, `787223c2e` |
| M3 Chat | Done, including Continue (`continueResponse()`) | `76b8dc971`, `f303753b6`, `98606048e`; chat self-test on the M1 iPad |
| M4 Vision | Done (no OCR mode: Android main has none) | `d70285295` |
| M5 Tools | Done | `bed3cb582` |
| M6 Documents | Done | `286b49c2d`; 100-page PDF indexed next to a 2B chat model (05 §5.1) |
| M7 Dictation | Done; live microphone not yet tried by a person | `beedea71c`; jfk.wav through the real controller (05 §5.1) |
| M8 Settings, l10n, polish | Done | `26f8e6536`, `40abcf877`, `281803ffd`, `5365e99a5` |
| M9 Release | Scripts, privacy manifest and English listing done | `49e213570`, `a168cfd5b` |

Automated checks: app unit tests 62/62 on the M1 iPad; chat self-test
(`-labChat <model>`) 17/17 plus the 100-page and dictation parts.

Waiting on the owner:

- Accept the Apple Program License Agreement, so the increased-memory and
  extended-virtual-addressing entitlements can be signed; then re-measure
  on the iPhone 16 Pro and decide the catalog (D6, `ios-catalog.json`).
- Turn on Settings › Developer › Enable UI Automation on both devices, so
  `LMPlaygroundUITests` (and screenshot automation) can run.
- Try dictation with the real microphone, and background/Continue by hand
  (R3).
- After merge: push the fork commit `8011897b0` (`vm_allocate` on Apple),
  copy the updated skills into the main checkout, update the memory notes.

Still to do once D6 is decided: translate the App Store listing (the
model list depends on the catalog), screenshots for iPhone 6.9" and iPad
13", and TestFlight.

## M0: Repository restructure and shared core, about 1 week

See [02-repo-restructure.md](02-repo-restructure.md).

- [x] Delete the prototype worktree and branch (D1).
- [x] Commit 1 (`2cc7ed466`): Android tree → `android/`, path fixes (Gradle,
      workflows, fastlane, `.gitignore`, README).
- [x] Commit 2 (`4255ac10f`): engine → `core/llamacore`, submodule →
      `core/llama.cpp`, `core/CMakeLists.txt`, chat templates →
      `core/resources/chat_templates`.
- [x] Commit 3 (`61cff161c`): Tier A extraction ([03 §3.3](03-cpp-reuse.md#33-moved-into-the-core-first-tier-a)).
- [ ] Copy the updated repo skills into the main checkout after merge
      (`.claude/` is globally gitignored, so skill edits don't travel with
      the branch).
- [ ] Update the memory notes for the new paths.
- [ ] Merge, then publish an Android point release.

Golden fixtures move to `core/testdata` per milestone, alongside each Swift
port ([03 §3.5](03-cpp-reuse.md#35-shared-data-instead-of-shared-code)).

**Exit:** the verification list in [02 §2.5](02-repo-restructure.md#25-verification-for-m0)
passes, and the Android point release is published.

## M1: Engine on Apple, plus the device spike, 1.5–2 weeks

- [ ] `build-xcframework.sh` (iOS device, iOS simulator, macOS; static;
      `RelWithDebInfo`; symbol checks; stamp).
- [ ] `VisionPolicy::AnyGpu` on Apple (CPU in the simulator), `Threads.h`,
      `LoadSentinel`, `lmp_log.h` `os_log` branch, extended `ModelProbe`.
- [ ] Xcode project skeleton: `Config/*.xcconfig`, `LlamaKit` (iOS + macOS),
      `LlamaKitTests`, empty app target, entitlements.
- [ ] LlamaKit ObjC++ bridge + Swift API (Engine, Model, GenerationSession,
      EmbeddingSession, AsrModel, AsrStream, sentinel, DeviceReport,
      `.contextLost`).
- [ ] `LlamaKitTests` on macOS: generation, thinking split, abort during
      prefill, tool round, context overflow, preamble cache hit, replay,
      vision, embedding, ASR WER, all ported from the harness probes.
- [ ] `verify-dsyms.sh` against a first test archive. This proves the
      symbol chain end to end before any feature work.
- [ ] **Device spike on the iPhone 16 Pro and the M1 iPad** (a minimal debug UI is enough):
      fill in the measurement table in [05 §5.1](05-platform-research.md#51-per-process-memory-limit).
      Test background during prefill and decode (R3). Time a vision encode.
      Measure Parakeet RTF (2 vs 4 threads, CPU vs Metal).
- [ ] On the iPad, check `supportedResources.contains(.gpu)` and whether
      the gaming-tier key turns on the Game Mode overlay (first TestFlight
      build, with the key already in `Info.plist`).
- [ ] **Decide D6** (the first `ios-catalog.json`) from the measurements.

**Exit:** macOS tests are green, the measurement table is filled in, the
memory formula is finalised, D6 is decided, and the dSYM check
passes on a test archive.

## M2: Models, about 1.5 weeks

- [ ] `generate-catalog.py` + `ios-catalog.json` → generated catalog,
      logos in the asset catalog.
- [ ] `ModelStore` (Documents/Models, exclude from backup, probe-validated
      copy import, delete with projector), `DownloadManager` (background
      session, resume, cellular setting, notifications).
- [ ] Model library UI (catalog, downloaded, custom, storage usage,
      language filter, vision module).
- [ ] `ModelRuntime`: load, unload, recreate, memory preflight, sentinel
      prompt.

**Exit:** on the 16 Pro and the M1 iPad, every catalog model downloads, loads and unloads. A
debug-forced crash during load produces the sentinel prompt on the next
launch.

## M3: Chat, about 3 weeks

- [ ] GRDB schema v1 (`DatabaseMigrator`) + `ChatStore` with `ValueObservation`.
- [ ] `ConversationModel` + `TurnRunner`: streaming, thinking, stop, stats,
      interrupted state.
- [ ] Shared C++ `continueResponse()`, with a harness probe and an Android
      JNI binding.
- [ ] Lifecycle: background abort + Continue, memory warnings, thermal banner.
- [ ] Markdown renderer, message actions.
- [ ] Params sheet, thinking budget, session recreate and replay.
- [ ] Sessions (iPhone stack, iPad split view), system prompts, preamble
      cache wiring.

**Exit:** a 30-minute scripted session on the 16 Pro, plus a shorter one on the iPad in split view (multi-turn, thinking
on and off, overflow, background ×5, kill and resume) with no crash and
history intact.

## M4–M8: Feature parity, about 7 weeks

| Milestone | Scope ([07](07-feature-parity.md)) | Size | Exit |
|-----------|-------------------------------------|------|------|
| **M4 Vision** | PhotosPicker + camera, downscale, persisted images, add-module flow, OCR mode | ~1 w | Image turns on every verified vision model on the 16 Pro. Vision and tools in the same session work (harness `ToolsWithVisionProbe` equivalent). |
| **M5 Tools** | ToolCallLoop port + fixtures, web_search, web_fetch + HtmlToMarkdown, run_javascript (headless WKWebView), per-model enablement, settings | ~1.5 w | Tool probe passes through LlamaKit. Tool transcripts match Android on 3 tool-capable models. |
| **M6 Documents** | Extractors, chunker, EmbeddingGemma session + idle unload, vDSP top-K, chips + QuickLook, RAG tables in migration v2 (tested against a v1 database) | ~2 w | A 100-page PDF is indexed on the 16 Pro alongside a loaded chat model without jetsam. Retrieval matches the Android top-K on a fixture document. |
| **M7 Dictation** | AVAudioEngine capture → AsrStream, DictationController port, transcript fixtures, earcons, voice ring | ~1.5 w | WER on the harness audio within 1 point of the Mac run. Realtime factor < 1 on the 16 Pro. |
| **M8 Settings, l10n, polish** | All settings screens, 28 locales via `generate-strings.py`, FAQ/privacy, haptics and sound, What's New, rating prompt, iPad keyboard shortcuts, accessibility pass (Dynamic Type, VoiceOver labels) | ~1 w | Every row in [07](07-feature-parity.md) is checked off. |

## M9: Release, about 1 week

- [ ] Final `ios-catalog.json` re-verified on the 16 Pro with the release
      build.
- [ ] `PrivacyInfo.xcprivacy`, App Privacy answers, age rating, export
      compliance, review notes.
- [ ] Store texts for 28 locales, screenshots (iPhone 6.9", iPad 13").
- [ ] Website: iOS section and App Store badge. README update.
- [ ] `release-check.sh` → Archive → upload → `verify-dsyms.sh` →
      TestFlight on the 16 Pro and the M1 iPad → submit for review.

**Exit:** approved and released. A test crash from the TestFlight build
(debug menu, internal builds only) shows up fully symbolicated in Organizer.

**Total:** about 15–18 weeks from the start of M0 to submission.
