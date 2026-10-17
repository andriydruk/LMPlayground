# 9. Decisions and risks

## 9.1 Decision log

| # | Topic | Decision | Status |
|---|-------|----------|--------|
| D0 | Repo layout | `android/`, `core/`, `ios/` side by side. `docs/` (website) stays at the root for GitHub Pages. | **Decided** (owner) |
| D1 | August prototype | No rebase. Write new code, delete the branch and worktree at the start of M0. | **Decided** (owner) |
| D2 | Submodule location | `core/llama.cpp` | **Decided** (owner) |
| D3 | Minimum OS | iOS 26 | **Decided** (owner) |
| D4 | Release scope | Full feature parity before the first release | **Decided** (owner) |
| D5 | Database | GRDB, mirroring the Room schema. Blackbird evaluated and rejected: automatic migrations risk chat data, no FK cascades ([04 §4.8](04-architecture.md#48-persistence-grdb-mirroring-room)) | **Decided** (owner) |
| D6 | Catalog | iOS shows only models verified on the iPhone 16 Pro (`ios/catalog/ios-catalog.json`) | **Decided**. The list is set in M1 from measurements. |
| D7 | Identity | `com.druk.lmplayground`, "LM Playground", free | **Decided** (owner). Check name availability in App Store Connect early. |
| D8 | Platforms | iPhone + iPad + "Designed for iPad" on Mac | **Decided** (owner) |
| D9 | Test devices | iPhone 16 Pro + iPad with M1 | **Decided** (owner) |
| D10 | CI / publishing | No iOS CI. Archive and upload from the owner's Xcode. | **Decided** (owner) |
| D11 | Install restriction `iphone-performance-gaming-tier` (iPhone 15 Pro+, M1+ iPads, all ≥ 8 GB) | **Yes for 1.0.** In `Info.plist` from the first upload. It can be relaxed later, never tightened ([05 §5.3](05-platform-research.md#53-device-support-and-the-performance-tier-option)). | **Decided** (owner) |
| D12 | iOS version numbers | Shared root `version.properties` with Android. The first iOS release carries the current Android version (e.g. 1.12.0). The build number is iOS-only (`Version.xcconfig`). | **Decided** (owner) |

## 9.2 Risks

| # | Risk | Mitigation | Owner note |
|---|------|------------|------------|
| R1 | The memory cap limits the catalog more than on Android | Measure on the 16 Pro (M1), verified-only catalog (D6), runtime preflight for imports, D11 device tier | Test on the 16 Pro (tier floor) and the M1 iPad |
| R2 | A native crash kills the whole app (no `:llama` process) | Vocab-warn patch, Metal sticky errors, `LoadSentinel`, memory preflight. **dSYMs verified on every archive** (`verify-dsyms.sh`, [06 §6.5](06-build-and-release.md#65-dsyms-every-build-no-exceptions)) | dSYMs must be uploaded 100% of the time |
| R3 | No background generation on iPhone | Abort on background + shared `continueResponse()` (no re-prefill) | The owner checks how it feels during M1 and M3 |
| R4 | Metal-specific kernel bugs, cost of llama.cpp bumps | Stable llama.cpp releases only. Rely on upstream's Apple CI. Our macOS LlamaKit tests plus a manual check on the 16 Pro. | Agreed |
| R5 | Devices in the tier that we don't own (15 Pro, 16 / 17 family, M2+ iPads) behave differently | All have ≥ 8 GB and Apple7+ GPUs. The 15 Pro (A17 Pro) is the only GPU older than the 16 Pro's, and it is still in the same Metal code path. Runtime preflight + sentinel cover the rest. | Mostly resolved by D11 |
| R6 | M0 regresses Android | Pure-move commits, symbol and disassembly diffs, harness sweep, separate Android point release | — |
| R7 | Kotlin and Swift ports of pure logic drift | Golden fixtures in `core/testdata` run by both test suites | — |
| R8 | App Review pushback (2.5.2, JS tool, generative content) | Engine in the binary, curated catalog, sandboxed JS toggle, review notes, age rating | — |
| R9 | No CI, so checks are skipped by mistake | `release-check.sh` gates every archive. The xcframework stamp check fails the build when stale. The `release-ios` skill walks the checklist. | — |
| R10 | Gaming-tier key may trigger the iPad Game Mode overlay | Check on the M1 iPad with the first TestFlight build. Removing the key later is allowed if it's unacceptable. | Check in M1 |
| R11 | Long runway to a first release (D4) | Internal TestFlight builds after each milestone on the 16 Pro and the M1 iPad, so problems surface early | — |
