#include "VisionDevice.h"

#include <atomic>
#include <cstdio>
#include <cstdlib>
#include <cstring>

#define LMP_LOG_TAG "llama-android.cpp"
#include "lmp_log.h"

// GPUs whose Vulkan driver is trusted with the CLIP vision graph. The encoder
// prefers Vulkan (much faster than CPU); everything not matched here runs the
// encoder on CPU. Matched as a substring of the ggml device description (the
// Vulkan deviceName). This used to be a denylist, but 1.8.x/1.9.0 Vitals kept
// growing it one cohort per release — PowerVR, Mali-G52, then the whole
// Adreno 6xx family and 710, then Mali-G57/G68/G72 (Galaxy A24/A53, Dimensity
// 700), Adreno 725 (POCO F5) and the Galaxy S22 family (Adreno 730 or Xclipse
// 920) — each costing every affected user one startup crash. Only families
// with zero vision crashes across three releases of Vitals stay on Vulkan; the
// crash sentinel below still catches an allowlisted part that misbehaves.
bool visionGpuIsKnownGood(const char *gpuDescription) {
    if (gpuDescription == nullptr) return false;
    static const char *kAllow[] = {
        "Adreno (TM) 74",  // Snapdragon 8 Gen 2 flagships
        "Adreno (TM) 75",  // Snapdragon 8 Gen 3 flagships
        "Adreno (TM) 8",   // Snapdragon 8 Elite family
        "Immortalis",      // Dimensity 9xxx flagships
        "Xclipse 94",      // Exynos 2400 (Galaxy S24)
        "Xclipse 95",      // Exynos 2500
        // Tensor G2/G3 Valhall parts, verified on Pixel 7 Pro. Deliberately
        // NOT the bare "Mali-G71" prefix — that would also match the 2016
        // Bifrost Mali-G71 (Galaxy S8 era).
        "Mali-G710",
    };
    for (const char *needle : kAllow) {
        if (strstr(gpuDescription, needle) != nullptr) return true;
    }
    return false;
}

// --- Vulkan CLIP crash sentinel ---------------------------------------------
// Catches an allowlisted GPU that misbehaves after a single crash. The inflight
// marker brackets the Vulkan vision-encoder init and each encode (the SIGSEGV
// happens inside the GPU driver and can't be caught); if it's still present at the next
// process start, that attempt crashed -> promote to a permanent block so CLIP
// runs on CPU from then on. An empty stateDir disables the sentinel (tests).
static std::string g_clipStateDir;
static std::string clipInflightPath() { return g_clipStateDir + "/vulkan_clip.inflight"; }
static std::string clipBlockedPath()  { return g_clipStateDir + "/vulkan_clip.blocked"; }

static bool fileExists(const std::string &path) {
    FILE *f = fopen(path.c_str(), "r");
    if (f != nullptr) { fclose(f); return true; }
    return false;
}
static void touchFile(const std::string &path) {
    FILE *f = fopen(path.c_str(), "w");
    if (f != nullptr) fclose(f);
}

void clipSentinelInit(const std::string &stateDir) {
    g_clipStateDir = stateDir;
    if (g_clipStateDir.empty()) return;
    // A marker that survived a process restart means the last Vulkan vision
    // attempt took the process down. Make the block permanent.
    if (fileExists(clipInflightPath())) {
        touchFile(clipBlockedPath());
        remove(clipInflightPath().c_str());
        LOGw("previous Vulkan CLIP attempt crashed; disabling Vulkan vision");
    }
}

bool clipSentinelVulkanBlocked() {
    return !g_clipStateDir.empty() && fileExists(clipBlockedPath());
}
ggml_backend_dev_t clipVisionDevice() {
    const char *backend = std::getenv("MTMD_BACKEND_DEVICE");
    if (backend == nullptr || strcmp(backend, "CPU") == 0) return nullptr;
    ggml_backend_dev_t dev = ggml_backend_dev_by_name(backend);
    if (dev == nullptr) LOGw("vision backend %s not found; using CPU", backend);
    return dev;
}
static std::atomic<bool> g_clipVulkanFailed{false};
bool clipVulkanFailed() {
    return g_clipVulkanFailed.load();
}
void clipSentinelBeginVulkanAttempt() {
    if (!g_clipStateDir.empty()) touchFile(clipInflightPath());
}
void clipSentinelEndVulkanAttempt() {
    if (!g_clipStateDir.empty()) remove(clipInflightPath().c_str());
}
void clipSentinelBlockVulkan() {
    if (!g_clipStateDir.empty()) touchFile(clipBlockedPath());
    g_clipVulkanFailed = true;
    // The next projector load in this process goes to CPU too, not only the
    // next launch: the Vulkan device is gone.
    setenv("MTMD_BACKEND_DEVICE", "CPU", 1);
    LOGw("Vulkan CLIP failed; disabling Vulkan vision");
}

std::string chooseVisionDevice(VisionPolicy policy, const std::string &override) {
    // Mobile GPUs register as IGPU (not GPU), so dev_by_type(GPU) misses them;
    // enumerate instead. Reading the device name/description is safe (no
    // buffer allocation, which is where the Vulkan crash happens).
    std::string backend = "CPU";
    const char *gpu_name = nullptr;
    const char *gpu_desc = nullptr;
    size_t dev_count = ggml_backend_dev_count();
    for (size_t i = 0; i < dev_count; i++) {
        ggml_backend_dev_t dev = ggml_backend_dev_get(i);
        if (dev == nullptr) continue;
        enum ggml_backend_dev_type type = ggml_backend_dev_type(dev);
        const char *name = ggml_backend_dev_name(dev);
        const char *desc = ggml_backend_dev_description(dev);
        LOGi("ggml device %zu: name=%s type=%d desc=%s",
             i, name ? name : "?", (int) type, desc ? desc : "?");
        if (type == GGML_BACKEND_DEVICE_TYPE_GPU || type == GGML_BACKEND_DEVICE_TYPE_IGPU) {
            gpu_name = name;
            gpu_desc = desc;
        }
    }
    switch (policy) {
        case VisionPolicy::VulkanAllowlist:
            if (gpu_name != nullptr && visionGpuIsKnownGood(gpu_desc)
                    && !clipSentinelVulkanBlocked()) {
                backend = gpu_name;
            }
            break;
        case VisionPolicy::AnyGpu:
            if (gpu_name != nullptr) {
                backend = gpu_name;
            }
            break;
    }
    if (!override.empty()) {
        backend = override;
    }
    setenv("MTMD_BACKEND_DEVICE", backend.c_str(), 1);
    LOGi("vision backend: %s (gpu=%s)",
         backend.c_str(), gpu_desc ? gpu_desc : "none");
    return backend;
}
