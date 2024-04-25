//
// Created by Andrew Druk on 24.01.2024.
//

#include <jni.h>
#include <string>

#include "LlamaCpp.h"
#include "common.h"

#include "console.h"
#include "llama.h"

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

#include <signal.h>
#include <unistd.h>
#include <android/log.h>

void LlamaModel::loadModel(gpt_params params,
                           const std::string &modelPath,
                           int32_t n_gpu_layers,
                           llama_progress_callback progress_callback,
                           void * progress_callback_user_data) {

    //params.n_gpu_layers = 33;
    // params.n_threads = 32;
    llama_sampling_params & sparams = params.sparams;
    llama_context * ctx_guidance = NULL;

    // load the model and apply lora adapter, if any
    LOG("%s: load the model and apply lora adapter, if any\n", __func__);

    auto model_params = llama_model_params_from_gpt_params(params);
    model_params.n_gpu_layers = n_gpu_layers;
    model_params.progress_callback = progress_callback;
    model_params.progress_callback_user_data = progress_callback_user_data;

    llama_model * model = llama_load_model_from_file(modelPath.c_str(), model_params);
    if (model == NULL) {
        fprintf(stderr, "%s: error: failed to load model '%s'\n", __func__, params.model.c_str());
        return;
    }

    for (unsigned int i = 0; i < params.lora_adapter.size(); ++i) {
        const std::string& lora_adapter = std::get<0>(params.lora_adapter[i]);
        float lora_scale = std::get<1>(params.lora_adapter[i]);
        int err = llama_model_apply_lora_from_file(model,
                                                   lora_adapter.c_str(),
                                                   lora_scale,
                                                   ((i > 0) || params.lora_base.empty())
                                                   ? NULL
                                                   : params.lora_base.c_str(),
                                                   params.n_threads);
        if (err != 0) {
            fprintf(stderr, "%s: error: failed to apply lora adapter\n", __func__);
            llama_free_model(model);
            return;
        }
    }

    if (sparams.cfg_scale > 1.f) {
        struct llama_context_params lparams = llama_context_params_from_gpt_params(params);
        ctx_guidance = llama_new_context_with_model(model, lparams);
    }

    // print system information
    {
        LOG_TEE("\n");
        LOG_TEE("%s\n", get_system_info(params).c_str());
    }

    this->model_ = model;
    this->ctx_guidance_ = ctx_guidance;


}

uint64_t LlamaModel::getModelSize() {
    return llama_model_size(this->model_);
}

LlamaGenerationSession* LlamaModel::createGenerationSession(const char* input_prefix,
                                                            const char* input_suffix,
                                                            const char* antiprompt) {
    LlamaGenerationSession *session = new LlamaGenerationSession(ctx_guidance_, model_);
    gpt_params localParams = g_params;
    localParams.interactive = true;
    localParams.interactive_first = true;
    localParams.input_prefix = std::string(input_prefix);
    localParams.input_suffix = std::string(input_suffix);
    if (antiprompt != NULL) {
        std::vector<std::string> antiprompt_vector = std::vector<std::string>();
        antiprompt_vector.push_back(std::string(antiprompt));
        localParams.antiprompt = antiprompt_vector;
    }
    session->init(localParams);
    return session;
}

void LlamaModel::unloadModel() {
    if (ctx_guidance_ == NULL) {
        llama_free(ctx_guidance_);
        ctx_guidance_ = NULL;
    }
    if (model_ == NULL) {
        llama_free_model(model_);
        model_ = NULL;
    }
}
