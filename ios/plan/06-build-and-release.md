# 6. Build and release

There's no iOS CI (D10). Everything runs on the owner's Mac with Xcode and
the owner's Apple Developer account. Android CI keeps running from
`android/`.

## 6.1 One CMake superbuild in `core/`

`core/CMakeLists.txt` defines "llama.cpp + mtmd + parakeet as we configure
them" for every consumer:

| Option | Android | Host JVM (harness) | Apple xcframework |
|--------|---------|--------------------|-------------------|
| `BUILD_SHARED_LIBS` / `GGML_BACKEND_DL` | ON / ON | ON / ON | **OFF / OFF** (static) |
| `GGML_VULKAN` | ON (CLIP only) | OFF | OFF |
| `GGML_METAL` + `GGML_METAL_EMBED_LIBRARY` | — | ON | **ON** |
| `GGML_BLAS` (Accelerate) | — | default | ON |
| `GGML_CPU_KLEIDIAI` | ON (arm64) | OFF | OFF |
| `LLAMA_BUILD_COMMON` / `LLAMA_BUILD_MTMD` | ON | ON | ON |
| `LLAMA_CURL` / `LLAMA_OPENSSL` | OFF | OFF | OFF |
| parakeet | yes | yes | yes |
| `llamacore` | linked into the JNI `.so` | linked into the JNI `.dylib` | not here: compiled by the LlamaKit Xcode target for fast incremental builds |
| Debug info | NDK symbol table | — | **`-g` always** (needed for dSYMs, §6.5) |

The Android `.so` must not change because of this. M0 verifies that with the
symbol and disassembly diff ([02 §2.5](02-repo-restructure.md#25-verification-for-m0)).

## 6.2 `ios/scripts/build-xcframework.sh`

1. For each slice (`iphoneos` arm64, `iphonesimulator` arm64, `macosx`
   arm64): configure `core/` with Ninja, `-DCMAKE_SYSTEM_NAME=iOS|Darwin`,
   the SDK sysroot, the deployment target (iOS 26.0 / macOS 26.0),
   `-DLMP_TARGET=apple` and `-DCMAKE_BUILD_TYPE=RelWithDebInfo`. Then build.
2. Merge the static archives (`libllama`, `libllama-common`,
   `libllama-common-base`, `libcpp-httplib`, `libvendor-hash`, `libggml*`,
   `libmtmd`, `libparakeet`) into `libllamacpp-apple.a` per slice with
   `libtool -static`. **Check that each input exists** and keep libtool's
   stderr visible.
3. `xcodebuild -create-xcframework` → `ios/build-apple/llama.xcframework`
   (gitignored).
4. Check exported symbols with `nm` captured into a variable, never piped to
   `grep -q`: `common_chat_templates_apply`, `common_sampler_sample`,
   `mtmd_init_from_file`, `parakeet_capi_stream_begin`,
   `llama_model_load_from_file`, `ggml_backend_metal_init`.
5. Write a stamp (submodule SHA + script hash). The Xcode build phase
   **fails with a clear message** if the stamp doesn't match the current
   submodule, so a stale framework can't be archived.

The build takes about 15–25 minutes from scratch. `LMP_REUSE_BUILD=1`
reuses the CMake build directories.

## 6.3 Xcode project

- A committed `ios/LMPlayground.xcodeproj` with folder-synchronised groups.
  Settings live in `ios/Config/*.xcconfig`.
- `LlamaKit` header search paths: `core/llamacore`,
  `core/llama.cpp/{include,common,ggml/include,vendor,tools/mtmd,tools/parakeet/include}`.
- Signing: automatic, with the owner's team. The App ID has *Increased
  Memory Limit* and *Extended Virtual Addressing* enabled in the developer
  portal. `Info.plist` `UIRequiredDeviceCapabilities` = `[arm64,
  iphone-performance-gaming-tier]` (D11). It is present from the very first
  upload, and `release-check.sh` fails if it's missing.
- Local test commands:

```bash
xcodebuild test -project ios/LMPlayground.xcodeproj -scheme LlamaKit -destination 'platform=macOS'
```

```bash
xcodebuild test -project ios/LMPlayground.xcodeproj -scheme LMPlayground -destination 'platform=iOS Simulator,name=iPhone 17 Pro'
```

`LlamaKitTests` read models from `$LMP_MODELS_DIR` (default
`~/.cache/lmplayground/models`, shared with the harness) and skip when a
model is missing.

## 6.4 Release from Xcode

An `ios/scripts/release-check.sh` runs before every archive. It fails on
any problem:

1. The xcframework stamp matches the submodule.
2. `generate-catalog.py --check` and `generate-strings.py --check` pass (no
   drift from Android).
3. The `LlamaKit` macOS tests and the app unit tests pass.
4. `bump-version.sh` has written `Version.xcconfig`. `MARKETING_VERSION`
   comes from the root `version.properties`, and the build number increments.

Then in Xcode: **Product → Archive → Distribute App → App Store Connect →
Upload**, with "Upload your app's symbols" left on. After that:

```bash
ios/scripts/verify-dsyms.sh ~/Library/Developer/Xcode/Archives/<date>/<archive>.xcarchive
```

A `release-ios` skill wraps this checklist. It also writes the changelog to
`ios/store/<locale>/release_notes.txt` (generated from the Android
changelog text when the versions match).

## 6.5 dSYMs: every build, no exceptions

Requirement R2: every build uploaded to App Store Connect must have complete
symbols, including the C++ engine.

| Layer | How symbols get into the dSYM |
|-------|-------------------------------|
| App target | `DEBUG_INFORMATION_FORMAT = dwarf-with-dsym` in `Release.xcconfig` |
| LlamaKit (ObjC++ bridge + `core/llamacore`) | Same setting. The engine sources are compiled by this target, so they're in `LlamaKit.framework.dSYM`. |
| llama.cpp, ggml, mtmd, parakeet (static xcframework) | Built `RelWithDebInfo` (`-g`). Static archives carry DWARF in their object files. When they link into `LlamaKit.framework`, `dsymutil` gathers it into the same dSYM. No separate dSYM is needed. |

`verify-dsyms.sh <xcarchive>` fails unless:

1. Every Mach-O in `Products/Applications/LMPlayground.app` (the app binary
   and each `Frameworks/*.framework` binary) has a dSYM in `dSYMs/` with a
   **matching UUID** (`dwarfdump --uuid` on both sides).
2. `LlamaKit.framework.dSYM` has DWARF compile units for
   `LlamaGenerationSession.cpp`, `ggml-metal-context.m`, `llama-model.cpp`
   and `parakeet.cpp`. The output is captured to a variable and searched in
   bash (no `grep -q` pipeline).
3. `atos` resolves a known engine function's address back to its file and
   line. This proves the dSYM actually symbolicates.

Archives (`~/Library/Developer/Xcode/Archives`) are kept and backed up.
Crashes then appear symbolicated in Xcode Organizer → Crashes and in App
Store Connect. For anything Organizer can't resolve, the procedure in the
`project_native_crash_symbolication` memory note (llvm-objdump on the
matching binary) also works with the archive's binaries.

## 6.6 Catalog and strings generation

- `ios/scripts/generate-catalog.py` reads `android/.../ModelInfoProvider.kt`
  (source of truth for names, URLs, languages, capabilities, mmproj and ASR
  pairing). It merges `ios/catalog/ios-catalog.json`, which lists **only
  the models verified on the iPhone 16 Pro**, with the measured footprint,
  tok/s and an optional `"maxContext"`. The output is
  `ModelCatalog+Generated.swift`. Models missing from the iOS file aren't
  shown. `--check` fails if the output is stale or an iOS entry names a model
  Android no longer has.
- `ios/scripts/generate-strings.py` converts `strings.xml` in 28 locales to
  `Localizable.xcstrings`. It maps `%1$s` → `%1$@` and `<plurals>` → plural
  variations, and keeps iOS-only keys in a separate `ios-strings.xml`
  merged in, so Android files aren't polluted. The `translate-strings`
  skill runs it afterwards.
- Logos: export the Android vector drawables to PDF in the asset catalog
  once (script), then keep them in sync with the catalog entries.

## 6.7 Store listing and screenshots

- Listing text: `ios/scripts/store-text.py` derives
  `ios/store/<locale>/{name,subtitle,description,keywords,promotional_text}.txt`
  from the Play texts in `android/fastlane/metadata/android/*`. It enforces
  the App Store limits (subtitle 30, keywords 100, promotional text 170
  characters). The owner pastes the text into App Store Connect, or runs
  `fastlane deliver` locally if that turns out to be easier for 28 locales
  (optional, no CI).
- Screenshots: `LMPlaygroundUITests` drives a canned conversation with a
  fake engine. `ios/scripts/screenshots.sh` runs it per locale on the iPhone
  6.9" and iPad 13" simulators.

## 6.8 llama.cpp bumps (R4)

Only **stable** llama.cpp releases are picked up. Upstream's own Apple CI
covers Metal kernels, so our checks focus on our integration:

1. Existing Android flow (`update-llama-cpp` skill, now with
   `core/llama.cpp`). Carry the same fork patches (`fd:N`, vocab-warn,
   parakeet).
2. `build-xcframework.sh`. Its symbol checks catch renamed or added
   upstream archives.
3. `LlamaKitTests` on macOS (Metal), plus a short manual chat on the iPhone
   16 Pro and the M1 iPad, with one text model and one vision model.
4. Re-check C structs that we zero-initialise (the lesson from the
   `mtmd_input_text` bug) and env-var contracts (the
   `MTMD_BACKEND_DEVICE` lesson).
