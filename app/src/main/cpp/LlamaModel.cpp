//
// Created by Andrew Druk on 24.01.2024.
//

#if defined(__ANDROID__)
#include <jni.h>
#endif
#include <string>

#include "LlamaCpp.h"
#include "common.h"
#include "chat.h"

#include "console.h"
#include "ggml-backend.h"
#include "log.h"

#include <cassert>
#include <cinttypes>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <ctime>
#include <fstream>
#include <iomanip>
#include <iostream>
#include <sstream>
#include <string>
#include <utility>
#include <vector>
#include <mutex>

#include <csignal>
#include <unistd.h>
#include <fcntl.h>

#define LMP_LOG_TAG "llama-android.cpp"
#include "lmp_log.h"

void LlamaModel::loadModel(const std::string &modelPath,
                           int32_t n_gpu_layers,
                           llama_progress_callback progress_callback,
                           void * progress_callback_user_data,
                           bool disableRepack,
                           const std::string &chatTemplateOverride) {

    // initialize the model
    llama_model_params model_params = llama_model_default_params();
    // Keep the LLM entirely on CPU; Vulkan is reserved exclusively for the
    // mtmd/CLIP vision encoder (much faster there). n_gpu_layers=0 alone is
    // NOT enough: with a Vulkan backend loaded (GGML_BACKEND_DL), the
    // scheduler still reserves a Vulkan compute buffer and routes part of the
    // decode graph to Vulkan0 — and the image-conditioned decode for M-RoPE
    // vision models (Qwen3VL) then fails on the Mali Vulkan backend
    // (ggml graph compute status 1 -> garbage output). Pinning the model to a
    // CPU-only device list removes Vulkan from the text graph entirely. The
    // mtmd CLIP context gets its own device (clipVisionDevice()),
    // so vision encoding still runs on the GPU.
#if defined(__ANDROID__)
    model_params.n_gpu_layers = 0;
    static ggml_backend_dev_t cpu_only_devices[2] = { nullptr, nullptr };
    cpu_only_devices[0] = ggml_backend_dev_by_type(GGML_BACKEND_DEVICE_TYPE_CPU);
    if (cpu_only_devices[0] != nullptr) {
        model_params.devices = cpu_only_devices;
    }
#else
    // Host build (:model-harness on macOS): none of the above applies —
    // there is no Mali/Adreno Vulkan driver to route around, and Metal is
    // both correct and much faster. Honour the caller's n_gpu_layers (the
    // JNI layer passes -1 = offload everything); LMP_HOST_N_GPU_LAYERS=0
    // forces a CPU-only run for Android-numerics comparisons.
    int32_t host_n_gpu_layers = n_gpu_layers;
    if (const char * ngl_override = std::getenv("LMP_HOST_N_GPU_LAYERS")) {
        host_n_gpu_layers = atoi(ngl_override);
    }
    model_params.n_gpu_layers = host_n_gpu_layers;
#endif
    model_params.progress_callback = progress_callback;
    model_params.progress_callback_user_data = progress_callback_user_data;
    // Weight repacking (the CPU "extra" buffer types) copies quantized
    // weights into a freshly-allocated RAM buffer, which defeats mmap and
    // forces the whole model resident. For models that don't fit in RAM the
    // caller disables it so weights stay memory-mapped and load successfully
    // (slower matmuls, but they fit).
    model_params.use_extra_bufts = !disableRepack;
    model = llama_model_load_from_file(modelPath.c_str(), model_params);
    if (model == nullptr) {
        LOG_ERR("%s: failed to load model '%s'\n", __func__, modelPath.c_str());
        return;
    }
    // An override wins outright: common_chat_templates_init only reads the
    // GGUF's embedded template when this string is empty.
    if (!chatTemplateOverride.empty()) {
        LOGi("using chat template override (%zu bytes)", chatTemplateOverride.size());
    }
    chat_tmpls = common_chat_templates_init(model, chatTemplateOverride);
}

void LlamaModel::loadMmprojModel(const std::string &mmprojPath) {
    if (model == nullptr) {
        LOG_ERR("%s: text model not loaded yet\n", __func__);
        return;
    }

    // Idempotent. Assigning mtmd_ctx without freeing the previous context
    // leaked it and aborted at teardown, and live generation sessions hold
    // this pointer, so replacing it underneath them would dangle. Attaching
    // the projector that is already attached is therefore a no-op; only an
    // actual change of projector frees and reloads.
    if (mtmd_ctx != nullptr) {
        if (mmproj_path == mmprojPath) {
            LOGi("loadMmprojModel: %s already attached, nothing to do", mmprojPath.c_str());
            return;
        }
        LOGi("loadMmprojModel: replacing projector %s with %s",
             mmproj_path.c_str(), mmprojPath.c_str());
        mtmd_free(mtmd_ctx);
        mtmd_ctx = nullptr;
        mmproj_path.clear();
    }

    mtmd_context_params params = mtmd_context_params_default();
    params.n_threads = std::max(1, std::min(kMaxVisionThreads, (int) sysconf(_SC_NPROCESSORS_ONLN) - 2));
    params.warmup = false;
    params.print_timings = true;
    // With device=nullptr and use_gpu=true, clip.cpp takes the first GPU —
    // Vulkan on every Android device, bypassing the allowlist and the
    // sentinel's CPU fallback. Always pass the device we chose.
    params.device = clipVisionDevice();
    params.use_gpu = params.device != nullptr;
    // Use model defaults for image tokens (-1). Some models like Gemma 4
    // have high minimum pixel requirements that reject low token caps.
    // LMP_IMAGE_MAX_TOKENS overrides it for measurement: CLIP encode time
    // scales with image tokens, and that encode is what users perceive as a
    // hang on CPU-only devices. Never set in production.
    if (const char *max_tok = std::getenv("LMP_IMAGE_MAX_TOKENS")) {
        params.image_max_tokens = atoi(max_tok);
    }

    LOGi("loadMmprojModel: loading %s (use_gpu=%d, n_threads=%d, image_max_tokens=%d)",
         mmprojPath.c_str(), params.use_gpu, params.n_threads, params.image_max_tokens);

    // The Vulkan CLIP init can SIGSEGV inside the GPU driver on some devices —
    // an uncatchable crash, not a C++ exception. Bracket it with the sentinel so
    // a crash here is detected on the next launch and Vulkan vision is disabled.
    // CPU encodes never hit this, so only mark the Vulkan path.
    bool clip_on_vulkan = params.use_gpu;
    if (clip_on_vulkan) clipSentinelBeginVulkanAttempt();

    // mtmd_init_from_file catches exceptions internally and returns null,
    // but its LOG_ERR may not reach Android logcat reliably. Wrap again to be safe.
    try {
        mtmd_ctx = mtmd_init_from_file(mmprojPath.c_str(), model, params);
    } catch (const std::exception &e) {
        LOGe("loadMmprojModel EXCEPTION: %s", e.what());
        mtmd_ctx = nullptr;
    } catch (...) {
        LOGe("loadMmprojModel UNKNOWN EXCEPTION");
        mtmd_ctx = nullptr;
    }

    // Reached only if mtmd_init returned (success or caught failure) without
    // crashing — clear the marker so this load isn't counted as a crash.
    if (clip_on_vulkan) clipSentinelEndVulkanAttempt();

    // Also check n_embd match manually for better diagnostics
    if (mtmd_ctx == nullptr) {
        int n_embd_text = llama_model_n_embd(model);
        LOGe("loadMmprojModel: FAILED (text model n_embd=%d). "
             "The mmproj may be incompatible with this text model.", n_embd_text);
    }

    if (mtmd_ctx == nullptr) {
        LOGe("loadMmprojModel: FAILED to load mmproj model '%s'", mmprojPath.c_str());
    } else {
        mmproj_path = mmprojPath;
        LOGi("loadMmprojModel: OK, vision=%d", mtmd_support_vision(mtmd_ctx));
    }
}

bool LlamaModel::supportsToolCalling() {
    if (!chat_tmpls) {
        return false;
    }
    auto caps = common_chat_templates_get_caps(chat_tmpls.get());
    auto it = caps.find("supports_tools");
    return it != caps.end() && it->second;
}

bool LlamaModel::supportsVision() {
    return mtmd_ctx != nullptr && mtmd_support_vision(mtmd_ctx);
}

LlamaGenerationSession* LlamaModel::createGenerationSession(const SamplerParams &params) {
    if (model == nullptr) {
        return nullptr;
    }
    // unique_ptr guards the allocation through init(); released to the
    // caller (the JNI layer owns it via the Kotlin object's handle).
    auto session = std::make_unique<LlamaGenerationSession>();
    session->init(model, chat_tmpls.get(), mtmd_ctx, params);
    return session.release();
}

LlamaEmbeddingSession* LlamaModel::createEmbeddingSession(int nCtx) {
    if (model == nullptr) {
        return nullptr;
    }
    // unique_ptr guards the allocation through init(); released to the
    // caller (the JNI layer owns it via the Kotlin object's handle).
    auto session = std::make_unique<LlamaEmbeddingSession>();
    if (!session->init(model, nCtx)) {
        return nullptr;
    }
    return session.release();
}

int LlamaModel::getContextTrainSize() {
    if (model == nullptr) {
        return 0;
    }
    return llama_model_n_ctx_train(model);
}

uint64_t LlamaModel::getModelSize() {
    if (this->model == nullptr) {
        return 0;
    }
    return llama_model_size(this->model);
}

bool LlamaModel::supportsThinking() {
    if (!chat_tmpls) {
        return false;
    }
    return common_chat_templates_support_enable_thinking(chat_tmpls.get());
}

std::string LlamaModel::getModelReport() {
    if (model == nullptr) {
        return "";
    }

    char desc[256];
    llama_model_desc(model, desc, sizeof(desc));

    uint64_t n_params = llama_model_n_params(model);
    int n_ctx_train = llama_model_n_ctx_train(model);

    std::ostringstream report;
    report << "Model\n";
    report << "  Architecture: " << desc << "\n";

    if (n_params >= 1000000000ULL) {
        report << "  Parameters: " << std::fixed << std::setprecision(2)
               << (n_params / 1e9) << "B\n";
    } else {
        report << "  Parameters: " << std::fixed << std::setprecision(0)
               << (n_params / 1e6) << "M\n";
    }

    report << "  Training context: " << n_ctx_train << "\n";

    return report.str();
}

void LlamaModel::unloadModel() {
    if (mtmd_ctx != nullptr) {
        mtmd_free(mtmd_ctx);
        mtmd_ctx = nullptr;
    }
    mmproj_path.clear();
    chat_tmpls.reset();
    if (model != nullptr) {
        llama_model_free(model);
        model = nullptr;
    }
}
