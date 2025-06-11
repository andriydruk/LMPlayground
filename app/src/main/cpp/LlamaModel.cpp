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

void LlamaModel::loadModel(const std::string &modelPath,
                           int32_t n_gpu_layers,
                           llama_progress_callback progress_callback,
                           void * progress_callback_user_data) {

    // initialize the model
    llama_model_params model_params = llama_model_default_params();
    // model_params.n_gpu_layers = n_gpu_layers;
    model_params.progress_callback = progress_callback;
    model_params.progress_callback_user_data = progress_callback_user_data;
    model = llama_model_load_from_file(modelPath.c_str(), model_params);
    if (model == nullptr) {
        LOG_ERR("%s: failed to load model '%s'\n", __func__, modelPath.c_str());
    }
}

LlamaGenerationSession* LlamaModel::createGenerationSession(int32_t n_ctx,
                                                           float   temperature,
                                                           float   top_p,
                                                           int32_t top_k) {
    auto *session = new LlamaGenerationSession();
    session->init(model, n_ctx, temperature, top_p, top_k);
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
        llama_model_free(model);
        model = nullptr;
    }
}
