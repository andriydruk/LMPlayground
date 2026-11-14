//
// Which device runs the CLIP vision encoder, and the crash sentinel that
// takes a misbehaving GPU out of that role.
//

#ifndef LMPLAYGROUND_VISION_DEVICE_H
#define LMPLAYGROUND_VISION_DEVICE_H

#include "ggml-backend.h"

#include <string>

// How the vision encoder's device is chosen at engine init.
enum class VisionPolicy {
    // Android: Vulkan only on GPUs whose driver is known to handle the CLIP
    // graph (see visionGpuIsKnownGood), and never after the sentinel blocked it.
    VulkanAllowlist,
    // Desktop / Apple: the GPU is Metal, which is exactly the backend we want.
    AnyGpu,
};

// Vulkan CLIP crash sentinel. A marker file is written right before each
// crash-prone Vulkan vision step and removed right after it returns; if it
// survives to the next process start, the attempt took the process down, so
// Vulkan vision is disabled for this install and CLIP runs on CPU.
// clipSentinelInit() promotes a surviving marker to a permanent block;
// begin/end bracket the encoder init in LlamaModel and the encode in
// processImageTurn. clipSentinelBlockVulkan() blocks it outright, for a
// failure that was caught rather than crashed. An empty stateDir disables
// the sentinel (tests).
//
// clipVulkanFailed() reports such a caught failure in this process. The GPU
// is then gone: a projector on it must not be used or freed again (freeing
// runs Vulkan cleanup, which can crash in the driver too).
//
// clipVisionDevice() resolves MTMD_BACKEND_DEVICE (our own process-wide
// choice, set by chooseVisionDevice and flipped to "CPU" by a block) to the
// device to pass in mtmd_context_params; nullptr means CPU. clip.cpp no
// longer reads the env var itself (upstream #23255, since b11200), so it must
// be passed explicitly.
ggml_backend_dev_t clipVisionDevice();
void clipSentinelInit(const std::string &stateDir);
bool clipSentinelVulkanBlocked();
bool clipVulkanFailed();
void clipSentinelBeginVulkanAttempt();
void clipSentinelEndVulkanAttempt();
void clipSentinelBlockVulkan();

// True for GPUs whose Vulkan driver is trusted with the CLIP graph, matched
// as a substring of the ggml device description.
bool visionGpuIsKnownGood(const char *gpuDescription);

// Pick the CLIP device once backends are loaded and record it in
// MTMD_BACKEND_DEVICE. [override] (a device name, or "CPU") wins when
// non-empty. Returns the chosen device name.
std::string chooseVisionDevice(VisionPolicy policy, const std::string &override);

#endif // LMPLAYGROUND_VISION_DEVICE_H
