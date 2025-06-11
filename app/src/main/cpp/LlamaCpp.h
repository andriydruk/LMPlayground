//
// Created by Andrew Druk on 22.01.2024.
//

#ifndef LMPLAYGROUND_LLAMACPP_H
#define LMPLAYGROUND_LLAMACPP_H

#include "common.h"
#include "sampling.h"

class LlamaGenerationSession {
public:
    using ResponseCallback = std::function<void(const std::string&)>;

    LlamaGenerationSession();

    ~LlamaGenerationSession();

    void init(llama_model *model,
              int32_t n_ctx,
              float   temperature,
              float   top_p,
              int32_t top_k);

    void printReport();

    int generate(const ResponseCallback& callback);

    int addMessage(const char *string);

    std::string getReport();

private:
    const struct llama_vocab * vocab = nullptr;
    llama_context * ctx = nullptr;
    llama_sampler * smpl = nullptr;
    std::vector<llama_chat_message>* messages = nullptr;
    std::vector<char>* formatted = nullptr;
    const char * tmpl = nullptr;
    int prev_len = 0;
    std::vector<llama_token> prompt_tokens;
    llama_token last_token;
    llama_batch batch;
    std::string response;
};

class LlamaModel {
public:
    LlamaModel() = default;
    ~LlamaModel() = default;

    LlamaGenerationSession* createGenerationSession(int32_t n_ctx,
                                                    float   temperature,
                                                    float   top_p,
                                                    int32_t top_k);
    void loadModel(const std::string &modelPath,
                   int32_t n_gpu_layers,
                   llama_progress_callback progress_callback,
                   void* progress_callback_user_data);

    uint64_t getModelSize();

    void unloadModel();

private:
    // Private members for the model, like the model data, etc.
    llama_model *model = nullptr;
};

#endif //LMPLAYGROUND_LLAMACPP_H
