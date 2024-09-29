//
// Created by Andrew Druk on 24.01.2024.
//

#include <jni.h>
#include <string>

#include "LlamaCpp.h"
#include "common.h"

#include "console.h"
#include "log.h"

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
#include <utility>
#include <vector>
#include <mutex>

#include <csignal>
#include <unistd.h>
#include <android/log.h>
#include <fcntl.h>

void LlamaModel::loadModel(const gpt_params& params_arg,
                           const std::string &modelPath,
                           std::string input_prefix,
                           std::string input_suffix,
                           std::vector<std::string> antiprompt,
                           int32_t n_ctx,
                           int32_t n_gpu_layers,
                           llama_progress_callback progress_callback,
                           void * progress_callback_user_data) {
    params = params_arg;
    params.interactive = true;
    params.n_ctx = 2048;
    params.interactive_first = true;
    params.input_prefix = std::move(input_prefix);
    params.input_suffix = std::move(input_suffix);
    params.conversation = true;
    params.model = modelPath;
    params.n_gpu_layers = n_gpu_layers;
    params.antiprompt = std::move(antiprompt);

    auto modelParams = llama_model_params_from_gpt_params(params);
    modelParams.progress_callback = progress_callback;
    modelParams.progress_callback_user_data = progress_callback_user_data;
    model = llama_load_model_from_file(params.model.c_str(), modelParams);
    if (model == nullptr) {
        LOG_ERR("%s: failed to load model '%s'\n", __func__, params.model.c_str());
    }
}

LlamaGenerationSession* LlamaModel::createGenerationSession() {
    auto *session = new LlamaGenerationSession();
    session->init(model, params);
    return session;
}

uint64_t LlamaModel::getModelSize() {
    if (this->model == nullptr) {
        return 0;
    }
    return llama_model_size(this->model);
}

void LlamaModel::unloadModel() {
    if (model != nullptr) {
        llama_free_model(model);
        model = nullptr;
    }
}
