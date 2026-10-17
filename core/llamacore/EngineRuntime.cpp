#include "EngineRuntime.h"

#include "ggml.h"
#include "ggml-backend.h"
#include "llama.h"
#include "mtmd.h"

#define LMP_LOG_TAG "llama-android.cpp"
#include "lmp_log.h"

static void log_callback(ggml_log_level level, const char * fmt, void * data) {
    // fmt is already formatted by llama.cpp — use %s to avoid interpreting % in the message
#if defined(__ANDROID__)
    if (level == GGML_LOG_LEVEL_ERROR)     __android_log_print(ANDROID_LOG_ERROR, LMP_LOG_TAG, "%s", fmt);
    else if (level == GGML_LOG_LEVEL_INFO) __android_log_print(ANDROID_LOG_INFO, LMP_LOG_TAG, "%s", fmt);
    else if (level == GGML_LOG_LEVEL_WARN) __android_log_print(ANDROID_LOG_WARN, LMP_LOG_TAG, "%s", fmt);
    else __android_log_print(ANDROID_LOG_DEFAULT, LMP_LOG_TAG, "%s", fmt);
#else
    if (level == GGML_LOG_LEVEL_ERROR)     LOGe("%s", fmt);
    else if (level == GGML_LOG_LEVEL_WARN) LOGw("%s", fmt);
    else                                   LOGi("%s", fmt);
#endif
}

void engineInit(const EngineConfig &config) {
    clipSentinelInit(config.stateDir);

    llama_log_set(log_callback, NULL);
    ggml_log_set(log_callback, NULL);
    // mtmd/clip use their own logger (default writes to C stderr -> /dev/null
    // on Android, so vision-encoder diagnostics like "CLIP using <backend>"
    // are otherwise lost). Route them to the platform log too.
    mtmd_log_set(log_callback, NULL);

    // With GGML_BACKEND_DL=ON the CPU and GPU backends live in separate
    // libggml-* libraries next to the engine. dlopen them so
    // llama_model_load_from_file (and the mtmd vision encoder) have backends
    // to bind tensors to.
    if (!config.backendDir.empty()) {
        ggml_backend_load_all_from_path(config.backendDir.c_str());
    } else {
        ggml_backend_load_all();
    }

    // Pick the CLIP vision-encoder device now that the GPU backend (if any) is
    // loaded and its devices are enumerable.
    chooseVisionDevice(config.visionPolicy, config.visionDeviceOverride);

    llama_backend_init();
}
