# LM Playground for iOS — Plan

Status: **approved direction, revision 2**, 2026-10-12. Based on `main` at
`3e730e5eb` (1.10.1, llama.cpp fork `android-0.6.0`). Decisions from the
owner review are recorded in
[09-decisions-and-risks.md](09-decisions-and-risks.md).

## The short version

LM Playground's engine is already a portable C++ library behind a thin JNI
shim, and a macOS build of it already runs real inference with Metal
(`:model-harness`). The iOS app is a **new native SwiftUI app around that
same engine**:

- **Shared C++:** generation, chat templates, KV-cache reuse, context
  compaction, tool-call grammar, vision, embeddings, preamble cache and
  Parakeet dictation are shared as is. About 250 lines of platform-neutral
  logic move out of the Android JNI shim into the shared core.
- **New Swift code:** UI, persistence, downloads, tools, document
  extraction and audio capture.

The repository becomes three sibling trees:

```
android/   the existing Android app, unchanged except for paths
core/      shared C++ engine + llama.cpp submodule + shared data
ios/       the new iOS app
```

## Decisions in effect

| Topic | Decision |
|-------|----------|
| Prototype branch | Not rebased. New code is written from scratch, and the branch and its worktree are deleted at the start of M0 |
| Layout | `android/`, `core/` (including the `core/llama.cpp` submodule), `ios/` |
| Minimum OS | **iOS 26** (iPhone + iPad, plus "Designed for iPad" on Mac) |
| Release scope | **Full feature parity before the first release.** No MVP release. |
| Database | **GRDB (SQLite)**, mirroring the Room schema. Blackbird was evaluated and rejected. See [04 §4.8](04-architecture.md#48-persistence-grdb-mirroring-room) |
| Catalog | iOS catalog = the models verified on the iPhone 16 Pro (the 8 GB floor of the supported tier). Decided after the device spike. |
| Identity | `com.druk.lmplayground`, "LM Playground", free |
| Test devices | iPhone 16 Pro (A18 Pro, 8 GB) and iPad with M1 |
| Supported devices | **`iphone-performance-gaming-tier` only:** iPhone 15 Pro and later, M1 and later iPads, all ≥ 8 GB with Apple7+ GPUs. Can be relaxed in a later update, never tightened. |
| Version numbers | **Shared** root `version.properties` with Android |
| CI / publishing | No iOS CI. Archive and upload from Xcode on the owner's Mac. **dSYMs uploaded for every build** |
| llama.cpp | Bumped only to stable releases. We rely on upstream's own Apple testing, plus our macOS engine tests. |

All decisions are settled. See the [decision log](09-decisions-and-risks.md#91-decision-log).

## Documents

| # | File | What it answers |
|---|------|-----------------|
| 1 | [01-current-state.md](01-current-state.md) | What exists today; what the August prototype taught us |
| 2 | [02-repo-restructure.md](02-repo-restructure.md) | Moving Android into `android/`, the engine into `core/`; every path that changes |
| 3 | [03-cpp-reuse.md](03-cpp-reuse.md) | What is shared C++, what moves into the core, what is rewritten in Swift |
| 4 | [04-architecture.md](04-architecture.md) | iOS app architecture: layers, bridge API, threading, lifecycle, memory, storage, database |
| 5 | [05-platform-research.md](05-platform-research.md) | iOS constraints: memory limits, background GPU, device tier, App Store rules |
| 6 | [06-build-and-release.md](06-build-and-release.md) | Building llama.cpp for Apple, the Xcode project, local release from Xcode, dSYMs, llama.cpp bumps |
| 7 | [07-feature-parity.md](07-feature-parity.md) | Every Android feature and its iOS implementation (all required for 1.0) |
| 8 | [08-milestones.md](08-milestones.md) | Milestones M0–M9 with exit criteria |
| 9 | [09-decisions-and-risks.md](09-decisions-and-risks.md) | Decision log, open decisions, risks |
| 10 | [10-device-sweep-2026-10.md](10-device-sweep-2026-10.md) | Every catalog model on the iPhone 16 Pro: speed, memory, self-test, what does not fit |
