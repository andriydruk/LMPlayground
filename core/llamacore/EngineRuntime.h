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
    // GGML_BACKEND_DL builds (Android, host): the backends live in separate
    // libggml-* libraries that must be loaded at init. Static builds (Apple)
    // link them in and set this to false.
    bool loadBackends = true;
    // Directory holding those libraries. Empty searches ggml's default
    // locations.
    std::string backendDir;
    VisionPolicy visionPolicy = VisionPolicy::AnyGpu;
    // Vision device name (or "CPU") forced over the policy, e.g. from a debug
    // property. Empty lets the policy decide.
    std::string visionDeviceOverride;
};

void engineInit(const EngineConfig &config);

#endif // LMPLAYGROUND_ENGINE_RUNTIME_H
