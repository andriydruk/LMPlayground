# 5. iOS platform research

These are the constraints that shape the design. Items marked **measure**
are checked on the iPhone 16 Pro and the M1 iPad during the M1 device spike, and the results
are written back into this file.

## 5.1 Per-process memory limit

**Finding.** iOS kills (jetsam) a foreground app whose *physical footprint*
crosses a per-device limit well below physical RAM.
`com.apple.developer.kernel.increased-memory-limit` raises it on some
devices. Apple's documentation for the entitlement tells apps to read
`os_proc_available_memory()` at runtime rather than assume a number, and it
says the value changes over time. One developer report measured about 6 GB
available with the entitlement on both an 8 GB iPhone 16 Pro Max and a
12 GB iPhone 17 Pro Max (anecdotal). The entitlement is enabled on the App
ID in the developer portal; it isn't in Xcode's capability editor.
`com.apple.developer.kernel.extended-virtual-addressing` lets multi-GB
files be mmap'd where the address space is small.

**Plan.**
- Ship with both entitlements.
- **Measure** on the iPhone 16 Pro (the 8 GB floor of the supported tier),
  with both entitlements, full Metal offload and 4K context. Repeat the
  rows that pass on the M1 iPad, and also the ones that fail if the iPad
  has 16 GB. iPads get a higher limit, which may allow an iPad-only
  `"minRam"` tier in the catalog later:

| Model (Q4_K_M unless noted) | File | `os_proc_available_memory` before | Footprint after load | Decode tok/s | TTFT 1k prompt | Verdict |
|---|---|---|---|---|---|---|
| Qwen 3 0.6B | 484 MB | | | | | |
| Qwen 3 1.7B | 1.28 GB | | | | | |
| Gemma 4 E2B (+ vision) | | | | | | |
| Qwen 3 4B / Qwen 3.5 4B | | | | | | |
| Gemma 4 E4B (+ vision) | | | | | | |
| 8B class (Llama 3.1 8B / Granite 4.1 8B / Ministral 8B) | | | | | | |
| GPT-OSS 20B, Gemma 4 12B | | — expected not to fit — | | | | |

The iPhone verdicts become `ios/catalog/ios-catalog.json` (D6). Every
supported device has at least the 16 Pro's 8 GB, so a model verified there
is safe on the whole tier.

## 5.2 Background execution and the GPU

**Finding.** iPhone apps can't use the GPU in the background. A command
buffer that's submitted or in flight when the app backgrounds fails
("Insufficient Permission" / access revoked). iOS 26's
`BGContinuedProcessingTask` GPU entitlement
(`com.apple.developer.background-tasks.continued-processing.gpu`) reports
`supportedResources.contains(.gpu) == false` on every iPhone developers
tested; it appears to be iPad-only. On iOS 26.2, whisper.cpp reported that
ggml-metal *aborted the app* here (`GGML_ABORT` in `ggml_metal_synchronize`).

**Verified in our tree:** `android-0.6.0` already has the sticky-error fix
(`has_error` flag, then `GGML_STATUS_FAILED` from the next compute).

**Plan.**
- Abort on background, save the answer as *interrupted*, and resume it with
  `continueResponse()` ([04 §4.4](04-architecture.md#44-app-lifecycle)).
- Recover from a stale failed backend by recreating the context and
  replaying history.
- **Measure on the M1 iPad:** does `BGTaskScheduler.shared.supportedResources`
  contain `.gpu`? If it does, generation could continue in the background
  there via `BGContinuedProcessingTask` (post-1.0 extra, [07 §7.9](07-feature-parity.md#79-ios-only-extras-after-10)).
- **Measure** (owner, per R3): background mid-prefill and mid-decode
  repeatedly. Check for no crash, a kept partial answer, and that Continue
  works.

## 5.3 Device support and the performance-tier option

**Findings.**
- iOS 26 dropped the A12 iPhones. iOS 27 kept every iOS 26 iPhone. An
  iOS 26 deployment target therefore installs on **iPhone 11 / SE 2 (A13)
  and newer**, including 4 GB phones.
- ggml-metal enables simdgroup reduction and matmul only on
  `MTLGPUFamilyApple7`+ (A14+). A13 devices would run CPU-only.
- Test devices: iPhone 16 Pro (8 GB, A18 Pro) and an M1 iPad. Both are in
  the tier below.
- `UIRequiredDeviceCapabilities` key **`iphone-performance-gaming-tier`**
  limits installs to iPhone 15 Pro and later, and to M1 and later iPads.
  App Store Connect accepts it, according to developer reports. Every device
  in that tier has at least 8 GB and an Apple7+ GPU.
- Apple only lets updates **keep or relax** required capabilities. Adding
  one later is rejected (ITMS-90109). So the choice must be made before the
  first upload.
- Reported side effect: on iPad, an app with this key may be treated as a
  game (Game Mode overlay). **Check it on the M1 iPad** with the first
  TestFlight build in M1. If the overlay appears, decide whether it's
  acceptable. Removing the key later is allowed.

**Decision (D11): ship 1.0 with `iphone-performance-gaming-tier`.** The
supported devices match the devices we can test: the iPhone 16 Pro is the
8 GB floor, and the M1 iPad is the oldest supported iPad. There's no CPU-only
code path to support on device, and users on 4–6 GB phones don't get a
disappointing experience. If demand appears, the requirement can be relaxed
in an update (relaxing is allowed). It goes into `Info.plist` before the
**first** upload, including TestFlight, because the first uploaded build
sets the baseline.

## 5.4 Threads on iPhone

iPhones have 2 performance and 4 efficiency cores. With full Metal offload,
CPU threads barely matter for generation. They do matter for Parakeet
(CPU by default) and the simulator. `Threads.h` uses the P-core count
(2; the M1 has 4). **Measure** Parakeet realtime factor on the 16 Pro and the M1 iPad: 2 vs 4 threads,
CPU vs Metal (`PARAKEET_DEVICE`). Run it first on the Mac with the
harness's ASR probe.

## 5.5 App Store review

| Topic | Finding | Plan |
|-------|---------|------|
| 2.5.2 (no downloaded code) | Weights are data for an engine compiled into the binary. On-device LLM apps that download weights (PocketPal AI, Private LLM) are on the App Store. No GGUF-specific Apple statement was found. | Engine fully in the binary, curated catalog plus user imports. The review notes explain this. |
| `run_javascript` | Runs in system WebKit (headless `WKWebView`, all loads blocked). | Low risk. Mention it in the review notes. It's off by default with a Settings toggle, as on Android. |
| Generated content | Unfiltered model output | Answer the age-rating questionnaire accordingly, show a first-run disclaimer, and provide a feedback path. |
| Privacy | Offline except model downloads and the opt-in web tools | "Data Not Collected". `PrivacyInfo.xcprivacy` with required-reason APIs (file timestamp, disk space, UserDefaults, system boot time). Audit with Xcode's privacy report over llama.cpp and ggml symbols. |
| Export compliance | HTTPS + system crypto only | `ITSAppUsesNonExemptEncryption = NO` |

## 5.6 Downloads

Use a background `URLSession`: Hugging Face `resolve/` redirects to a CDN,
and resume works via `resumeData`. Other points:

- The simulator has no `nsurlsessiond`, so use `.default` there.
- Add a "Download over cellular" setting
  (`allowsExpensiveNetworkAccess`).
- Preflight free space with `volumeAvailableCapacityForImportantUsage`.
- Send a local notification when a download finishes in the background.

## 5.7 Audio

`AVAudioSession` (`.record`, or `.playAndRecord` with earcons) and
`NSMicrophoneUsageDescription`. An `AVAudioEngine` input tap feeds
`AVAudioConverter`, producing 16 kHz mono Float32, which goes straight to
`parakeet_capi_stream_feed`. There's no temp file, unlike Android.

## 5.8 Alternatives considered

| Option | Verdict |
|--------|---------|
| MLX Swift / Core ML | Would rewrite the engine and need a second model format. This fails the reuse goal. |
| Apple Foundation Models (iOS 26) | One fixed system model on Apple Intelligence devices. Possible later as an extra "system model" entry. |
| SpeechAnalyzer (iOS 26) | Available with an iOS 26 floor, but it would break "same engine, same WER" parity with Android and the harness. Parakeet stays. |
| Swift C++ interop | See [04 §4.2](04-architecture.md#42-bridge-objc-framework-built-for-ios-and-macos). |
| Kotlin Multiplatform | The shareable Kotlin is about 1k lines. Not worth adding a second toolchain. |

## Sources

- [Apple: increased-memory-limit entitlement](https://developer.apple.com/tutorials/data/documentation/bundleresources/entitlements/com.apple.developer.kernel.increased-memory-limit.md)
- [Apple forums: iPhone app memory limit capped to 6GB](https://developer.apple.com/forums/thread/834805)
- [Apple forums: memory limit after entitlement change](https://developer.apple.com/forums/thread/740831)
- [Apple forums: extended-virtual-addressing](https://developer.apple.com/forums/thread/695885)
- [Apple forums: BGContinuedProcessingTask GPU — no iPhone support](https://developer.apple.com/forums/thread/816774)
- [Apple forums: continued-processing GPU entitlement](https://developer.apple.com/forums/thread/797538)
- [whisper.cpp #3531: Metal abort on background](https://github.com/ggml-org/whisper.cpp/issues/3531)
- [Apple forums: iphone-performance-gaming-tier and Apple Intelligence devices](https://developer.apple.com/forums/thread/797023)
- [Apple forums: iphone-performance-gaming-tier rollout](https://developer.apple.com/forums/thread/737946)
- [UIRequiredDeviceCapabilities cannot be tightened in updates](https://oleb.net/blog/2011/12/uirequireddevicecapabilities-cannot-be-changed-in-app-updates/)
- [Apple forums: ITMS-90109](https://developer.apple.com/forums/thread/747311)
- [9to5Mac: iOS 26 drops XS, XS Max, XR](https://9to5mac.com/2025/07/24/ios-26-supports-these-recent-iphones-but-drops-three-models/)
- [Tom's Guide: iOS 27 supported devices](https://www.tomsguide.com/phones/iphones/ios-27-supported-devices-see-which-iphones-did-and-didnt-make-the-cut)
- [Apple forums: SwiftData background ModelActor not updating @Query](https://developer.apple.com/forums/thread/759364?page=2)
- [Michael Tsai: SwiftData in appleOS 27](https://mjtsai.com/blog/2026/06/23/swiftdata-in-appleos-27/)
