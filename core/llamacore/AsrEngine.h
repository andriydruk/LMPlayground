//
// Voice dictation via parakeet.cpp (NVIDIA Parakeet TDT on ggml): the
// platform-neutral pieces. Bridges call the parakeet C API (parakeet_capi.h)
// directly and use these for setup and output cleanup.
//

#ifndef LMPLAYGROUND_ASR_ENGINE_H
#define LMPLAYGROUND_ASR_ENGINE_H

#include <string>

// Pin parakeet to CPU and set its thread count, once per process, before the
// first model load. [threadsOverride] > 0 wins; otherwise $LMP_ASR_THREADS,
// otherwise the measured default. An existing PARAKEET_DEVICE is honoured.
void asrConfigureBackend(int threadsOverride);

// Remove the "<en-US>"-style locale tags the streaming decoder emits at
// utterance boundaries; angle brackets that aren't a locale tag are kept.
std::string asrStripLocaleTags(const char *text);

#endif // LMPLAYGROUND_ASR_ENGINE_H
