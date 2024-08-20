//
// Created by Andrew Druk on 24.01.2024.
//

#include <jni.h>
#include <string>

#include "LlamaCpp.h"
#include "common.h"

#include "console.h"

#include <cassert>
#include <cinttypes>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <ctime>
#include <fstream>
#include <iostream>
#include <sstream>
#include <string>
#include <vector>
#include <mutex>

#include <csignal>
#include <unistd.h>
#include <android/log.h>
#include <fcntl.h>

int32_t generate_random_int32() {
    int32_t random_value;
    int fd = open("/dev/urandom", O_RDONLY);
    if (fd < 0) {
        __android_log_write(ANDROID_LOG_ERROR, "Llama", "Can't open /dev/urandom");
        return 0;
    }
    if (read(fd, &random_value, sizeof(random_value)) != sizeof(random_value)) {
        __android_log_write(ANDROID_LOG_ERROR, "Llama", "Can't read from /dev/urandom");
        return 0;
    }
    close(fd);
    __android_log_print(ANDROID_LOG_ERROR, "Llama", "Generated random seed %d", random_value);
    return random_value;
}

void LlamaModel::loadModel(const gpt_params& params,
                           const std::string &modelPath,
                           int32_t n_gpu_layers,
                           llama_progress_callback progress_callback,
                           void * progress_callback_user_data) {

    const llama_sampling_params & sparams = params.sparams;
    llama_context * ctx_guidance = nullptr;

    // load the model and apply lora adapter, if any
    LOG("%s: load the model and apply lora adapter, if any\n", __func__);

    auto model_params = llama_model_params_from_gpt_params(params);
    model_params.n_gpu_layers = n_gpu_layers;
    model_params.progress_callback = progress_callback;
    model_params.progress_callback_user_data = progress_callback_user_data;

    llama_model * model = llama_load_model_from_file(modelPath.c_str(), model_params);
    if (model == nullptr) {
        fprintf(stderr, "%s: error: failed to load model '%s'\n", __func__, params.model.c_str());
        return;
    }

    if (sparams.cfg_scale > 1.f) {
        struct llama_context_params lparams = llama_context_params_from_gpt_params(params);
        ctx_guidance = llama_new_context_with_model(model, lparams);
    }

    // print system information
    {
        LOG_TEE("\n");
        LOG_TEE("%s\n", gpt_params_get_system_info(params).c_str());
    }

    this->model_ = model;
    this->ctx_guidance_ = ctx_guidance;


}

uint64_t LlamaModel::getModelSize() {
    if (this->model_ == nullptr) {
        return 0;
    }
    return llama_model_size(this->model_);
}

LlamaGenerationSession* LlamaModel::createGenerationSession(std::string input_prefix,
                                                            std::string input_suffix,
                                                            std::vector<std::string> antiprompt) {
    auto *session = new LlamaGenerationSession(ctx_guidance_, model_);
    gpt_params localParams = g_params;
    localParams.interactive = true;
    localParams.interactive_first = true;
    localParams.input_prefix = input_prefix;
    localParams.input_suffix = input_suffix;
    localParams.sparams.seed = generate_random_int32();
    localParams.antiprompt = antiprompt;
    session->init(localParams);
    return session;
}

void LlamaModel::unloadModel() {
    if (ctx_guidance_ == nullptr) {
        llama_free(ctx_guidance_);
        ctx_guidance_ = nullptr;
    }
    if (model_ == nullptr) {
        llama_free_model(model_);
        model_ = nullptr;
    }
}
