#include "AsrEngine.h"

#include <cctype>
#include <cstdlib>

#define LMP_LOG_TAG "AsrEngine"
#include "lmp_log.h"

#include "parakeet_capi.h"

#include <atomic>

// Internal to the vendored parakeet (tools/parakeet/src/ggml_graph.hpp): frees
// the process-wide backend, which the C API never does.
namespace pk { void shutdown_backend(); }

// Parakeet's own default is tuned for desktop core counts. On a big.LITTLE
// phone every extra thread is a liability: ggml waits for the slowest worker at
// each graph node, so pulling in efficiency cores stalls the whole encoder.
// Measured on a Pixel 7 Pro (2x X1 + 2x A78 + 4x A55), streaming a fixed clip:
//   2 threads 1.48x realtime · 3: 1.67x · 4: 1.54x · 6: 2.83x · 8: 30.5x
// Two — the big cores alone — is both the fastest and the safest default.
static constexpr int kAsrThreads = 2;

// Mobile GPU drivers have repeatedly mishandled encoder graphs in this app (the
// CLIP vision allowlist exists for exactly that reason), and parakeet's conformer
// uses op patterns those drivers have never seen. parakeet auto-selects a
// GPU/IGPU device when one is registered, so pin it to CPU before the first load.
//
// Both settings honour an existing environment value rather than overwriting it,
// so the macOS harness can measure the Metal backend or a different thread count
// without a rebuild.
void asrConfigureBackend(int threadsOverride) {
    static bool done = false;
    if (done) return;
    setenv("PARAKEET_DEVICE", "cpu", 0);

    int n = threadsOverride;
    if (n <= 0) {
        const char *threads = getenv("LMP_ASR_THREADS");
        n = threads != nullptr ? atoi(threads) : 0;
    }
    parakeet_capi_set_num_threads(n > 0 ? n : kAsrThreads);
    LOGi("ASR threads: %d", n > 0 ? n : kAsrThreads);
    done = true;
}

static std::atomic<int> g_liveModels{0};

parakeet_ctx *asrLoadModel(const char *path) {
    parakeet_ctx *ctx = parakeet_capi_load(path);
    if (ctx != nullptr) g_liveModels++;
    return ctx;
}

void asrFreeModel(parakeet_ctx *ctx) {
    if (ctx == nullptr) return;
    parakeet_capi_free(ctx);
    if (--g_liveModels == 0) {
        pk::shutdown_backend();
        LOGi("ASR backend released");
    }
}

// The streaming decoder emits the locale it settled on as a literal token at
// utterance boundaries ("<en-US>"), which the offline path never does. Left in,
// those tags would appear verbatim in the user's dictated text.
std::string asrStripLocaleTags(const char *text) {
    std::string out(text);
    size_t at = 0;
    while ((at = out.find('<', at)) != std::string::npos) {
        const size_t close = out.find('>', at);
        if (close == std::string::npos) break;
        const std::string tag = out.substr(at + 1, close - at - 1);
        // Only "xx" / "xx-YY" locale tags — never angle brackets that were
        // actually dictated.
        bool locale = tag.size() >= 2 && tag.size() <= 7 &&
                      islower(static_cast<unsigned char>(tag[0])) &&
                      islower(static_cast<unsigned char>(tag[1]));
        for (size_t i = 2; locale && i < tag.size(); ++i) {
            const char c = tag[i];
            locale = (i == 2) ? c == '-' : (isalnum(static_cast<unsigned char>(c)) != 0);
        }
        if (!locale) { at = close + 1; continue; }
        out.erase(at, close - at + 1);
    }
    return out;
}
