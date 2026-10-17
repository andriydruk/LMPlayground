//
// Process-wide engine setup: log routing, backend loading, the vision device
// choice and llama_backend_init. Called once, before any model is loaded.
//

#ifndef LMPLAYGROUND_ENGINE_RUNTIME_H
#define LMPLAYGROUND_ENGINE_RUNTIME_H

#include "VisionDevice.h"

#include <string>

struct EngineConfig {
    // Directory for the vision crash-sentinel markers. Empty disables them.
    std::string stateDir;
    // GGML_BACKEND_DL builds: directory holding the libggml-* backend
    // libraries. Empty searches ggml's default locations, and is harmless for
    // static builds, whose backends are linked in.
    std::string backendDir;
    VisionPolicy visionPolicy = VisionPolicy::AnyGpu;
    // Vision device name (or "CPU") forced over the policy, e.g. from a debug
    // property. Empty lets the policy decide.
    std::string visionDeviceOverride;
};

void engineInit(const EngineConfig &config);

#endif // LMPLAYGROUND_ENGINE_RUNTIME_H
