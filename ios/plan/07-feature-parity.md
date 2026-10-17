# 7. Feature parity map

**Every row below ships in 1.0** (D4: no release before full parity),
except the rows marked *Replaced* (an Android mechanism iOS can't offer,
with its iOS substitute) and the iOS-only extras in §7.9. "Engine" means the
logic already exists in shared C++ and only needs bridging. The milestone
column refers to [08-milestones.md](08-milestones.md).

## 7.1 Models and storage

| Android feature | iOS implementation | Engine | M |
|-----------------|--------------------|:------:|:-:|
| Curated catalog with logos, languages, badges, release dates | Generated from `ModelInfoProvider.kt`, filtered to models verified on the 16 Pro (D6) | — | M2 |
| Device-language filtering | `Locale.current.language` vs `supportedLanguages` | — | M2 |
| RAM gate + mmap fallback | `os_proc_available_memory` preflight with the measured formula. Catalog limited to verified models. | probe | M2 |
| Background downloads with progress, speed, ETA, resume, notifications | Background `URLSession`, resume data, cellular setting, local notification | — | M2 |
| SAF storage folder chooser | *Replaced:* `Documents/Models` visible in Files and Finder + `fileImporter` copy-import | — | M2 |
| Custom GGUF import with validation | `fileImporter` → `ModelProbe` → copy. Clear errors for non-GGUF, mmproj-only and ASR files. | probe | M2 |
| Optional vision module (mmproj) and pairing | "Add image module" flow, `MmprojPairing` port | — | M2 |
| Capability caching after first load | Stored in the database / preferences | engine | M2 |
| Measured `ThinkingMode` | From the catalog | — | M2 |
| Legacy entries and superseded-file cleanup | Port of `SupersededFileCleanup` | — | M2 |
| Chat-template overrides | `core/resources/chat_templates` bundle resources | engine | M1 |
| Delete model (and its paired projector) | One action, shows reclaimed size | — | M2 |

## 7.2 Chat

| Android feature | iOS implementation | Engine | M |
|-----------------|--------------------|:------:|:-:|
| Streaming with tok/s, token counts, report | `AsyncThrowingStream` deltas coalesced to 30 Hz | engine | M3 |
| Thinking section (collapsible, timer), toggle gated on real capability | `<think>` split as on Android | engine | M3 |
| Thinking budget | Params sheet | engine | M3 |
| Stop (interrupts prefill) | `Task` cancel → `abort()` | engine | M3 |
| Context overflow compaction | Shared C++ | engine | M3 |
| Generation params (temperature, top-p/k, min-p, repetition penalty, context, seed) | Sheet. A change recreates the session and replays history. | engine | M3 |
| GFM markdown, code blocks with copy | swift-markdown + SwiftUI renderer matching `MessageFormatter` | — | M3 |
| Copy, share | Buttons under the reply, `ShareLink` | — | M3 |
| Rating prompt | `requestReview` (StoreKit) at the same trigger points | — | M8 |
| Haptics per token, completion sound | Throttled `UIImpactFeedbackGenerator` (60 ms), `AVAudioPlayer` earcon, Sound & Haptics settings | — | M8 |
| Jump-to-bottom, auto-scroll | `scrollPosition` | — | M3 |
| Interrupted message + reload after an engine crash | Interrupted after backgrounding (Continue via `continueResponse()`) or after a sentinel-detected crash | engine | M3 |
| Ongoing-generation notification with quick actions | *Replaced:* no background GPU on iPhone. Interrupt + Continue instead. | — | — |
| Preamble KV cache | Shared C++ + `Caches/kv_preamble` | engine | M3 |

## 7.3 Sessions and prompts

| Android feature | iOS implementation | M |
|-----------------|--------------------|:-:|
| History: pin, rename, delete, resume | GRDB. iPhone: `NavigationStack`. iPad: `NavigationSplitView` sidebar. | M3 |
| Resume (history replay into a fresh context) | `replay(turns)` | M3 |
| Reusable system prompts with usage ordering | `system_prompts` table + picker row + editor | M3 |
| What's New | Sheet keyed by version | M8 |

## 7.4 Vision

| Android feature | iOS implementation | Engine | M |
|-----------------|--------------------|:------:|:-:|
| Attach photo (gallery, camera) | `PhotosPicker` + camera; ImageIO downscale to the same max edge | engine | M4 |
| Image persisted with the message | `Application Support/ChatImages/` | — | M4 |
| OCR models, image-only chat mode | Same catalog flag and mode | engine | M4 |
| Vulkan allowlist and sentinel | *Replaced:* Metal (always on supported devices); CPU in the simulator | — | — |

## 7.5 Tools

| Android feature | iOS implementation | M |
|-----------------|--------------------|:-:|
| Tool loop (max 5 rounds, thinking forced on after tools) | Swift port of `ToolCallLoop`, pinned by fixtures | M5 |
| `web_search` (DuckDuckGo HTML) | `URLSession` + SwiftSoup, same parsing and result shape | M5 |
| `web_fetch` + `HtmlToMarkdown` + `WebLinkStore` refs | SwiftSoup port | M5 |
| `run_javascript` (sandbox, 16 MB heap, 10 s timeout) | Headless `WKWebView`: `.nonPersistent()` store, a `WKContentRuleList` blocking every load, `callAsyncJavaScript` with a 10 s timeout, then teardown. It runs out of process like Android's sandbox, so a runaway loop can't hang the app. A plain `JSContext` was rejected because public API can't interrupt it. | M5 |
| Per-model tool enablement, Tools settings | Same | M5 |

## 7.6 Documents (RAG)

| Android feature | iOS implementation | Engine | M |
|-----------------|--------------------|:------:|:-:|
| Attach PDF, DOCX, EPUB, HTML, MD, TXT | `fileImporter` + bookmark. PDFKit, ZIPFoundation + `XMLParser`, SwiftSoup. | — | M6 |
| Chunking | `NLTokenizer(.sentence)` port, pinned by fixtures | — | M6 |
| EmbeddingGemma, warm for 60 s then unload | `EmbeddingSession`. Also unloads on memory warning. | engine | M6 |
| Cosine top-K, prompt wrapping, wire-only injection | `vDSP`, `RagPromptBuilder` port | — | M6 |
| Document chips, reopen original | Bookmark → `QLPreviewController` | — | M6 |

## 7.7 Voice dictation

| Android feature | iOS implementation | Engine | M |
|-----------------|--------------------|:------:|:-:|
| Parakeet TDT / Nemotron ASR models | Catalog entries of type ASR (if verified on the 16 Pro) | engine | M7 |
| Tap-to-dictate, live voice ring, realtime deltas | `AVAudioEngine` → 16 kHz Float32 → `AsrStream.feed`. Ring driven by RMS. | engine | M7 |
| Utterance queueing, stop on edit or send, earcons | Port of the `DictationController` state machine + `DictationTranscript` (fixtures) | — | M7 |

## 7.8 Settings, localization, misc

| Android feature | iOS implementation | M |
|-----------------|--------------------|:-:|
| 28 locales | Generated `Localizable.xcstrings`. Per-app language is set from iOS Settings. | M8 |
| Light / dark | System + Liquid Glass | M3 |
| Advanced | Context default, GPU offload toggle (debug/escape hatch), debug "crash engine" for testing the sentinel | M8 |
| FAQ, privacy policy | Adapted for iOS (no SAF, generation pauses in the background) | M8 |
| Large-screen layouts | iPad split view, keyboard shortcuts (⌘↩ send, ⌘. stop) | M8 |
| Play crash symbolication | App Store Connect / Organizer + verified dSYMs | M9 |

## 7.9 iOS-only extras (after 1.0)

- Share extension: "Ask LM Playground about this text or image".
- App Intents and Shortcuts.
- `BGContinuedProcessingTask` long generations on iPads that support it
  (whether the M1 iPad reports `.gpu` support is checked in the M1 spike).
- Apple Foundation Models as an extra "system model".
