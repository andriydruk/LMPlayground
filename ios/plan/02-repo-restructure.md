# 2. Repository restructure

The Android app moves into `android/`, the shared engine into `core/`, and
the iOS app lives in `ios/`. This is the first thing done (milestone M0),
as **pure moves plus path fixes, with no behaviour change**, and it ships in
an Android point release before any iOS work depends on it.

## 2.1 Target layout

```
/
├── README.md               both platforms (Android section + iOS section)
├── LICENSE  logo.png
├── version.properties      shared marketing version (major.minor.patch)
├── .gitmodules             core/llama.cpp
├── .gitignore              Android + Xcode + core build ignores
├── .github/workflows/      Android CI only (paths updated to android/)
├── .claude/                repo skills (paths updated)
├── docs/                   website (GitHub Pages serves /docs, so it must stay at the root)
│
├── android/
│   ├── settings.gradle.kts  build.gradle.kts  gradle.properties
│   ├── gradlew  gradlew.bat  gradle/            (wrapper + libs.versions.toml)
│   ├── buildscripts/        toml-updater-config.gradle
│   ├── app/                 (incl. src/main/cpp: JNI shim + CMakeLists only)
│   ├── llamacpp/            plain-JVM module + native/CMakeLists.txt (host)
│   ├── model-harness/
│   ├── fastlane/  Gemfile   Play deployment (Android-only)
│   ├── debug.keystore
│   └── ARCHITECTURE.md      Android architecture (moved from the root)
│
├── core/
│   ├── CMakeLists.txt       superbuild: llama.cpp + mtmd + parakeet (+ llamacore)
│   ├── llamacore/           shared engine sources (from app/src/main/cpp)
│   ├── llama.cpp/           submodule (from app/src/main/cpp/llama.cpp)
│   ├── resources/           chat_templates/*.jinja (+ overrides.json), packaged by both apps
│   └── testdata/            golden fixtures shared by Kotlin and Swift tests
│
└── ios/
    ├── LMPlayground.xcodeproj
    ├── Config/              *.xcconfig (all build settings live here)
    ├── LlamaKit/            ObjC++ bridge + Swift API (framework, iOS + macOS)
    ├── LlamaKitTests/
    ├── LMPlayground/        the app
    ├── LMPlaygroundTests/  LMPlaygroundUITests/
    ├── scripts/             build-xcframework.sh, generate-catalog.py,
    │                        generate-strings.py, verify-dsyms.sh, bump-version.sh
    ├── catalog/             ios-catalog.json (models verified on device)
    ├── build-apple/         (gitignored) llama.xcframework
    └── plan/                this plan
```

`version.properties` stays at the root because both apps read it. Gradle
reads it via `rootProject.file("../version.properties")`, and the iOS
`bump-version.sh` writes it into `ios/Config/Version.xcconfig`.

## 2.2 Move map

| From | To | Done with |
|------|----|-----------|
| `app/`, `llamacpp/`, `model-harness/` | `android/…` | `git mv` |
| `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `gradlew*`, `gradle/`, `buildscripts/` | `android/` | `git mv` |
| `fastlane/`, `Gemfile` | `android/` | `git mv` |
| `debug.keystore` | `android/` | `git mv` |
| `ARCHITECTURE.md` | `android/ARCHITECTURE.md` | `git mv`, then add a short root section to README pointing to both architectures |
| `app/src/main/cpp/{LlamaCpp.h,LlamaModel.cpp,LlamaGenerationSession.cpp,LlamaEmbeddingSession.cpp,lmp_log.h}` | `core/llamacore/` | `git mv` |
| `app/src/main/cpp/AsrEngine.cpp` | split: portable part → `core/llamacore/AsrEngine.{h,cpp}`, JNI part stays as `android/app/src/main/cpp/AsrJni.cpp` | move + edit (M0 step 3) |
| `app/src/main/cpp/llama.cpp` (submodule) | `core/llama.cpp` | `git mv` (updates `.gitmodules` and the gitlink) |
| `app/src/main/assets/chat_templates/` | `core/resources/chat_templates/` | `git mv`. Gradle adds `../../core/resources` as an extra assets source dir |

Do these as **separate commits** so each one is reviewable and bisectable:

1. `git mv` the Android tree into `android/` and fix the paths. The build
   is green, with no source edits beyond paths.
2. `git mv` the engine and submodule into `core/` and fix the CMake paths.
   The build is green.
3. Extract the Tier A units ([03 §3.3](03-cpp-reuse.md#33-moved-into-the-core-first-tier-a)).
   This is the only commit that touches engine code.

## 2.3 Every path that has to change

| Where | Change |
|-------|--------|
| `android/app/build.gradle.kts` | `version.properties` → `../version.properties`. CMake path unchanged (`src/main/cpp/CMakeLists.txt`). Add `../../core/resources` as an extra assets source dir. |
| `android/app/src/main/cpp/CMakeLists.txt` | `add_subdirectory(${CMAKE_CURRENT_LIST_DIR}/../../../../../core ${CMAKE_BINARY_DIR}/core)`. The library target keeps the name `llamacpp` (Kotlin loads `System.loadLibrary("llamacpp")`). |
| `android/llamacpp/native/CMakeLists.txt` | Point `LMP_LLAMA_DIR` / sources at `core/` (or `add_subdirectory(core)`). |
| `android/model-harness/build.gradle.kts` | No change (uses `rootProject`, which is now `android/`). |
| `.github/workflows/*.yml` | `defaults: run: working-directory: android`. `update-listing.yml` path filters → `android/fastlane/metadata/android/**`. `deploy-internal.yml` reads `version.properties` from the repo root. Checkout keeps `submodules: recursive`. |
| `android/fastlane/Fastfile` | Gradle task paths are relative to `android/`, so there's no change if fastlane runs from `android/`. Check `aab` / mapping output paths. |
| `.gitmodules` | `path = core/llama.cpp`. |
| `.gitignore` | Prefix the Android entries with `android/` where they were rooted (`android/app/release`, `android/app/src/main/cpp/clblast`). Add the Xcode ignores (`ios/build-apple/`, `xcuserdata/`, `*.xcarchive`, `DerivedData/`) and `core/build*/`. |
| `.claude/skills/*` (globally gitignored, so copied to the main checkout by hand after merge) | 53 path references across 8 skills (`update-llama-cpp` 14, `fix-crashes` 10, `record-screenshots` 8, `release` 7, `translate-strings` 5, `model-testing` 4, `model-scout` 3 + `scout.py` 2). Update `./gradlew` → `cd android && ./gradlew`, `app/src/...` → `android/app/src/...`, submodule → `core/llama.cpp`. |
| `.claude/launch.json` | Re-check any paths. |
| `README.md` | Build instructions for both platforms. |
| Memory notes | `project_llama_submodule_remotes`, `project_fork_carried_patches`, `project_worktree_submodule_init`, `project_native_crash_symbolication` (`.cxx` path is now `android/app/.cxx`), `project_ios_port`. |

Android Studio now opens `android/` as the project root. Nothing changes for
Play Console: the package name, signing and version codes stay the same.

## 2.4 Prototype cleanup (D1)

Before M0 starts:

```bash
git worktree remove --force /Users/admin/projects/LMPlayground/.claude/worktrees/lmplayground-ios-plan-293f4d
git branch -D claude/lmplayground-ios-plan-293f4d
```

The worktree has an untracked `core/llama.cpp` checkout and a built
`ios/build-apple/llama.xcframework`. Both are disposable. The branch was
never pushed (no `origin/` counterpart), so deleting it is final. Its
lessons are already in [01 §1.2](01-current-state.md#12-the-august-ios-prototype)
and the `project_ios_port` memory note.

## 2.5 Verification for M0

- `cd android && ./gradlew app:testDebugUnitTest app:assembleRelease` passes.
  The release AAB's `libllamacpp.so` exports the same JNI symbols as before
  (`nm -D` diff is empty).
- The per-function disassembly of the engine sources before and after the
  move differs only where code was intentionally moved (Tier A commit).
- `ModelGenerationTest` on the Android test device and the
  `:model-harness` capability sweep (one text, one vision, one ASR model)
  both pass.
- The Android CI workflows pass from the new paths (pull-request check on
  the M0 PR).
- Ship the result as an Android point release before iOS work depends on it.
