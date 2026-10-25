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
- Gate loads on `os_proc_available_memory()` read immediately before load,
  using the formula in [04 §4.5](04-architecture.md#45-memory-policy).

**Measured 2026-10-17** (Engine Lab, Debug build, full Metal offload, 4K
context, 256-token answer, iOS 27.0). *Without* the memory entitlements:
Apple's updated Program License Agreement must be accepted before they can
be provisioned. Re-measure once it is.

| Model | Device | Load | TTFT | Decode | Footprint after load | Peak (incl. vision) | Vision encode |
|---|---|---|---|---|---|---|---|
| Qwen 3.5 0.8B IQ4_XS (498 MB) | iPhone 16 Pro | 0.62 s | 0.05 s | 61–63 tok/s | +35 MB | 620 MB | 0.7 s |
| | iPad Pro M1 | 1.1 s | 0.05 s | 64 tok/s | +35 MB | 610 MB | 0.7 s |
| MiniCPM-V 4.7 1B Q4_K_M (640 MB) | iPhone 16 Pro | 0.78 s | 0.05 s | 61 tok/s | +16 MB | 220 MB | — |
| | iPad Pro M1 | 1.5 s | 0.05 s | 63 tok/s | +14 MB | 196 MB | — |
| LFM2.5 VL 1.6B Q4_K_M (697 MB) + Q8 mmproj (556 MB) | iPhone 16 Pro | 0.74 s | 0.06 s | 63 tok/s | +2 MB | 984 MB | 1.3 s |
| | iPad Pro M1 | 1.2 s | 0.05 s | 64 tok/s | +1 MB | 798 MB | 1.6 s |
| EmbeddingGemma 300M Q4_0 (264 MB) | iPhone 16 Pro | 0.40 s | — | 73–111 passages/s | +48 MB | 153 MB | — |
| Nemotron 3.5 ASR 0.6B q4_k (684 MB) | iPhone 16 Pro | 0.42 s | — | 4.6–5.3× realtime | +685 MB | 905 MB | — |
| | iPad Pro M1 | 0.45 s | — | 4.8× realtime | +685 MB | 886 MB | — |

`os_proc_available_memory` at launch without the entitlements: about
3.3 GB on the iPhone 16 Pro and 5.0 GB on the iPad Pro M1.

Document indexing (M6 exit check, `-labChatParts ragbig`): a generated
100-page PDF, 200 chunks, indexed in 9.9 s on the iPad Pro M1 next to a
loaded Qwen 3.5 2B. The footprint went from 830 MB to a 1183 MB peak with
4.0 GB still available. On the iPhone 16 Pro (2026-10-25): 9.7 s, 981 →
1003 MB peak, 2.4 GB still available.

Dictation (M7, `-labChatParts dictation`, jfk.wav fed through the real
controller at real-time pace on the iPad Pro M1): first words 2.2 s into
the 11.5 s clip (model load included), final text 0.2 s after the audio
ends. Footprint 827 → 1521 MB while Parakeet is loaded, 799 MB after the
idle unload. The live microphone path (`MicrophoneCapture`) still needs a
hands-on check, since the permission prompt needs a person.

Findings:

- **Model weights barely count.** llama.cpp maps the GGUF and Metal wraps
  those pages without copying, so they are clean, file-backed memory. A
  500–700 MB model adds 2–35 MB to the footprint. What counts is the KV
  cache, compute buffers and the vision projector (which mtmd loads into
  RAM). This makes much larger models feasible than the file size suggests,
  up to the GPU limit below.
- **The GPU working set caps model size** (catalog sweep, 2026-10-25,
  [10](10-device-sweep-2026-10.md)). Metal's `recommendedMaxWorkingSetSize`
  is 5461 MB on the iPhone 16 Pro, and weights do count against it. Past
  it, a model loads and then writes one garbage token or nothing, or the
  system runs out of pages and kills the app. Llama 3.1 8B (4685 MB of
  weights) works with a 2K context and fails with 3K. Gemma's
  `per_layer_token_embd` stays on the CPU, which is why Gemma 4 E4B fits.
  The memory entitlements don't raise this limit.
- **Parakeet loads its weights into RAM** (+685 MB for the 684 MB model).
  Dictation and a loaded chat model share the budget.
- **Unloads must return memory immediately.** On iOS, ggml's
  `posix_memalign` fallback left freed blocks in the footprint until the
  kernel reclaimed them lazily; `malloc_zone_pressure_relief` didn't help.
  Fixed by a fork patch that uses `vm_allocate` on all Apple platforms, plus
  releasing Parakeet's global backend. After the fix: ASR unload goes
  886 → 89 MB and LFM-VL with its projector 798 → 125 MB.
- Vision answers were correct on both devices ("A kitten…").

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
