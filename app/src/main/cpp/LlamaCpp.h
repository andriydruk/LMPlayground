//
// Created by Andrew Druk on 22.01.2024.
//

#ifndef LMPLAYGROUND_LLAMACPP_H
#define LMPLAYGROUND_LLAMACPP_H

#include "common.h"

extern gpt_params g_params;

class LlamaGenerationSession {
public:
    using ResponseCallback = std::function<void(const std::string&)>;

    LlamaGenerationSession(llama_context *g_ctx_guidance, llama_model *g_model);

    ~LlamaGenerationSession();

    void init(gpt_params params);

    void printReport();

    int generate(ResponseCallback callback);

    void addMessage(const char *string);

    std::string getReport();

private:
    llama_context *ctx_guidance;
    llama_model *model;

    gpt_params params;

    bool is_antiprompt        = false;

    int n_past             = 0;
    int n_remain           = 0;
    int n_consumed         = 0;
    int n_session_consumed = 0;
    int n_past_guidance    = 0;

    int n_ctx = 0;
    int guidance_offset = 0;
    int original_prompt_len = 0;
    bool display = false;
    bool is_interacting = true;
    bool input_echo = true;

    std::vector<int>   input_tokens;
    std::vector<int>   output_tokens;
    std::ostringstream output_ss;

    std::vector<llama_token> session_tokens;
    std::vector<llama_token> embd;
    std::vector<llama_token> embd_guidance;
    std::vector<llama_token> guidance_inp;
    std::vector<llama_token> embd_inp;

    llama_context * ctx;
    llama_sampling_context * ctx_sampling;
};

class LlamaModel {
public:
    LlamaModel() = default;
    ~LlamaModel() = default;

    LlamaGenerationSession* createGenerationSession(std::string input_prefix,
                                                    std::string input_suffix,
                                                    std::vector<std::string> antiprompt);
    void loadModel(const gpt_params& params,
                   const std::string &modelPath,
                   int32_t n_gpu_layers,
                   llama_progress_callback progress_callback,
                   void* progress_callback_user_data);

    uint64_t getModelSize();

    void unloadModel();

private:
    // Private members for the model, like the model data, etc.
    llama_model *model_ = nullptr;
    llama_context *ctx_guidance_ = nullptr;
};

#endif //LMPLAYGROUND_LLAMACPP_H
