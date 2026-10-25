# 10. Catalog sweep on iPhone 16 Pro (2026-10-24/25)

Every non-deprecated catalog model was downloaded onto the iPhone 16 Pro one
at a time, benchmarked in the Engine Lab, run through the chat self-test, and
deleted again. Results feed `ios/catalog/ios-catalog.json`.

**Device:** iPhone 16 Pro (A18 Pro, 8 GB), iOS 27.0, Debug build, full Metal
offload, 4K context. Built *without* the increased-memory entitlements (the
updated Program License Agreement still has to be accepted), so
`os_proc_available_memory` was about 3.3 GB. Metal's
`recommendedMaxWorkingSetSize` is 5461 MB.

## Summary

- **37 models work** and are now `verified`: every model up to 7B, plus
  Gemma 4 E4B and Granite 4.0 H Tiny.
- **7 models don't fit an 8 GB iPhone** and stay `candidate` (debug builds
  only) with a note: Llama 3.1 8B, Granite 4.1 8B, Gemma 2 9B, Ministral 3
  8B Instruct and Reasoning, Gemma 4 12B, GPT-OSS 20B.
- The limit for the 8B models is the **GPU working set, not RAM**. They load
  (weights are mmap'd and barely touch the footprint), then write one
  garbage token or nothing, or the system runs out of pages and kills the
  app. The app now checks the GPU working set before loading (below).
- No engine bugs were found in the sweep. Every self-test miss traces back
  to a model declared without tool support, or to model behaviour that the
  engine reproduces identically from a clean replay.

## Results

Load, TTFT and decode come from the lab: a 256-token answer, 4K context.
Peak is the process footprint during generation, including the vision
projector when the model has one. Self-test steps: load, answer, saved,
follow-up, reopen, replay, interrupt, interrupt saved, continue, after
interrupt, tool call, tool answer, and two vision steps when the model has a
projector.

| Model | File MB | Load s | TTFT s | tok/s | Peak MB | Vision | Self-test | Misses |
|---|---|---|---|---|---|---|---|---|
| LFM2.5 350M | 218 | 0.1 | 0.04 | 141.3 | 141 | | 10/12 | follow-up, replay ¹ |
| LFM2.5 VL 450M | 218 | 0.3 | 0.04 | 146.1 | 330 | yes | 14/14 | |
| Qwen2.5 0.5B | 379 | 0.5 | 0.16 | 90.2 | 183 | | 12/12 | |
| Qwen3 0.6B | 461 | 0.5 | 0.10 | 83.7 | 576 | | 11/12 | after interrupt ² |
| Qwen3.5 0.8B | 498 | 0.7 | 0.19 | 63.0 | 667 | yes | 14/14 | |
| MiniCPM5 1B | 656 | 0.7 | 0.13 | 70.7 | 202 | | 11/12 | follow-up ¹ |
| LFM2.5 1.2B Instruct | 697 | 0.7 | 0.08 | 63.0 | 144 | | 11/12 | replay ¹ |
| LFM2.5 VL 1.6B | 697 | 0.7 | 0.08 | 63.6 | 811 | yes | 14/14 | |
| Gemma 3 1B | 768 | 0.9 | 0.32 | 51.2 | 222 | | 11/12 | tool call ³ |
| Llama 3.2 1B | 770 | 0.9 | 0.06 | 57.4 | 254 | | 11/12 | tool call ³ |
| Qwen2.5 1.5B | 940 | 1.0 | 0.13 | 44.6 | 269 | | 12/12 | |
| DeepSeek R1 Distill 1.5B | 1065 | 1.1 | 0.07 | 45.1 | 241 | | 11/12 | tool call ³ |
| Qwen3.5 2B | 1196 | 1.3 | 0.33 | 34.8 | 1052 | yes | 14/14 | |
| Qwen3 1.7B | 1223 | 1.2 | 0.08 | 40.5 | 556 | | 12/12 | |
| MiniCPM5 2B | 1488 | 1.5 | 0.11 | 32.4 | 274 | | 12/12 | |
| LFM2.5 2.6B | 1596 | 1.6 | 0.13 | 28.9 | 185 | | 11/12 | after interrupt ² |
| LFM2.5 VL 3B | 1596 | 0.5 | 0.13 | 28.8 | 858 | yes | 14/14 | |
| SmolLM3 3B | 1826 | 1.9 | 0.48 | 24.9 | 432 | | 12/12 | |
| Llama 3.2 3B | 1925 | 2.3 | 0.25 | 23.7 | 591 | | 12/12 | |
| Granite 4.0 Micro | 2002 | 2.6 | 0.16 | 22.4 | 433 | | 12/12 | |
| Ministral 3 3B Reasoning | 2047 | 2.1 | 0.63 | 20.7 | 1843 | yes | 14/14 | |
| Ministral 3 3B Instruct | 2047 | 2.7 | 2.60 | 19.3 | 1857 | yes | 14/14 | |
| Granite 4.2 3B | 2140 | 2.8 | 0.16 | 22.2 | 431 | | 12/12 | |
| Phi-3.5 mini | 2282 | 2.9 | 0.33 | 15.4 | 1631 | | 11/12 | tool call ³ |
| Gemma 3 4B | 2374 | 4.2 | 0.41 | 18.4 | 679 | yes | 13/14 | tool call ³ |
| Phi-4 mini | 2376 | 3.0 | 0.19 | 18.7 | 659 | | 10/12 | after interrupt ², tool call ³ |
| Qwen3 4B | 2381 | 3.2 | 0.18 | 15.6 | 721 | | 12/12 | |
| Spark-X2.5 4B | 2479 | 3.3 | 0.50 | 14.5 | 706 | | 12/12 | |
| Qwen3.5 4B | 2547 | 3.5 | 0.40 | 13.0 | 1302 | yes | 14/14 | |
| Gemma 3n E2B | 2658 | 2.7 | 0.50 | 23.4 | 295 | | 11/12 | tool call ³ |
| Nemotron 3 Nano 4B | 2705 | 4.7 | 0.70 | 9.3 | 373 | | 12/12 | |
| Gemma 4 E2B QAT | 3194 | 3.3 | 0.54 | 29.6 | 957 | yes | 14/14 | |
| Granite 4.0 H Tiny | 4034 | 7.5 | 0.53 | 27.4 | 266 | | 12/12 | |
| Gemma 3n E4B | 4040 | 4.3 | 0.27 | 14.6 | 307 | | 11/12 | tool call ³ |
| Mistral 7B v0.3 | 4170 | 7.7 | 0.38 | 9.3 | 600 | | 11/12 | tool call ³ |
| DeepSeek R1 Distill 7B | 4466 | 8.4 | 0.25 | 9.4 | 352 | | 11/12 | tool call ³ |
| Gemma 4 E4B QAT | 4916 | 7.6 | 0.82 | 16.4 | 1053 ⁴ | yes | 14/14 | |

¹ Answer quality of a small model (e.g. "Germany" for the capital of
Germany), not a pipeline failure.
² The "Say OK." turn after a stopped and continued story. The engine is
consistent: a clean replay of the same conversation gives the identical
answer (new `EngineTests.continuedReplyKeepsTheCacheConsistent` cases for
LFM2.5 2.6B and Phi-4 mini). Qwen3 0.6B carries on with the story, Phi-4
mini answers "Sure, I'm ready to assist you", and LFM2.5 2.6B (which always
thinks) spends the turn thinking and writes no answer.
³ The model is declared without tool support in the catalog, so no tools are
offered; expected.
⁴ From a rerun. The first run's lab vision step failed because the lab
kept its text session alive next to the vision one, and two KV caches
overflowed the GPU. The app holds one session; the lab now does too.

### Full self-test

All parts with Qwen3.5 2B and its projector: **25/25**. Chat, reopen and
replay, Stop/Continue, tools, vision, document Q&A, a 100-page PDF, and
dictation through the real controller.

- 100-page PDF: 200 chunks indexed in 9.7 s next to the loaded model.
  Footprint 981 → 1003 MB peak, 2388 MB still available. This was the open
  iPhone half of the M6 exit check in 05 §5.1.
- Dictation (jfk.wav at real-time pace): first words at 1.7 s into the
  11.6 s clip, final text 0.16 s after the audio ends. Footprint 1005 →
  1748 MB while the ASR model is loaded, 1001 MB after the idle unload.

### Models that don't fit

| Model | GPU weights MB | What happens |
|---|---|---|
| Llama 3.1 8B | 4685 | Loads, writes one garbage token ("3"). Same with the template fix below. |
| Granite 4.1 8B | 5096 | Loads, writes one garbage token. |
| Gemma 2 9B | 5488 | Loads, writes nothing. |
| Ministral 3 8B Instruct | 4950 | The system kills the app (`vm-pageshortage`). |
| Ministral 3 8B Reasoning | — | Same size as Instruct; not run. |
| Gemma 4 12B QAT | — | Refused by the RAM check: needs 3472 MB, 3269 MB available. |
| GPT-OSS 20B | — | Fails to load. |

## GPU working-set check

The estimate is the weights the GPU holds (from the GGUF tensor table;
Gemma's `per_layer_token_embd` stays on the CPU and is excluded), plus the
f16 KV cache, plus 450 MB for compute buffers. To find the edge, the lab
reran Llama 3.1 8B at smaller contexts (`-labContext N`):

| Model | Weights MB | Context | KV MB | Estimate MB | Result |
|---|---|---|---|---|---|
| Mistral 7B | 4169 | 4096 | 512 | 5131 | works |
| DeepSeek R1 7B | 4460 | 4096 | 224 | 5134 | works |
| Llama 3.1 8B | 4685 | 1024 | 128 | 5263 | works, 9.0 tok/s |
| Llama 3.1 8B | 4685 | 2048 | 256 | 5391 | works, 8.9 tok/s |
| Llama 3.1 8B | 4685 | 3072 | 384 | 5519 | one garbage token |
| Llama 3.1 8B | 4685 | 4096 | 512 | 5647 | one garbage token |
| Gemma 4 E4B | 2696 | 4096 | — | well under | works |

The working set is 5461 MB. A 300 MB compute allowance didn't separate the
2K and 3K runs; 450 MB separates every measurement, with about 60 MB to
spare on each side. `MemoryBudget.check` returns `tooLarge` past the
working set, so the existing "This model needs about N MB…" alert and its
"Load anyway" button cover it. The check is skipped when "Run models on the
GPU" is off. `ModelProbe` reads `gpuWeightBytes` from the tensor table
without loading the model.

So the 8B models are not out of reach: Llama 3.1 8B runs at 2K context.
The catalog's unused `maxContext` field could ship it capped at 2K. That's
a product call; the models stay `candidate` for now.

In the app, loading Llama 3.1 8B at the default 4K context now stops at
the alert ("needs about 5 647 MB … only 5 461 MB is available") instead of
writing garbage.

## Fixed during the sweep

- `0bd87187a` Broken download links for GPT-OSS 20B and the Gemma 3 4B
  projector (also on Android, which shares the catalog).
- `d8d7be1f7` Llama 3.1's template rendered its tool preamble when no tools
  were passed, so every answer came out as a tool call. Template override,
  shared with Android.
- The Engine Lab now applies the app's template overrides, and the self-test
  records the conversation's error message on a failed step.
- The GPU working-set check above, plus `-labContext N` in the lab.
- The lab freed its text session only after the vision step.

## Open items

- **Accept the Program License Agreement** and re-measure with the
  increased-memory entitlements. They raise the RAM limit (Gemma 4 12B may
  then pass the RAM check) but not the GPU working set, so the 8B models
  will still not run on an 8 GB iPhone. Re-test them on a 16 GB iPad.
- **Edit, regenerate and branch** (and the message menus) from Android's
  latest release are still missing on iOS.
- `LlamaError.templateRenderFailed` is thrown for any `addMessage` failure,
  including an image that fails to encode, so the name misleads. Worth
  splitting before release.
- Deprecated variants were not swept (LFM2.5 1.2B Thinking, Granite 4.1 3B,
  Gemma 4 E2B/E4B/12B Q4_K_M). Release builds hide them anyway.
- Hands-on checks that need a person: the microphone permission and live
  dictation, and Continue after a real background switch.
- Push the fork commit `fc57a428d` (vm_allocate on Apple) before the branch
  merges.

## Raw data

Lab runs (`*.json`) and self-test results (`chat-*.json`) are in the
session scratchpad. They are not committed; the table above is the record.
