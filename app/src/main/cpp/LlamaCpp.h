//
// Created by Andrew Druk on 22.01.2024.
//

#ifndef LMPLAYGROUND_LLAMACPP_H
#define LMPLAYGROUND_LLAMACPP_H

#include "common.h"
#include "sampling.h"

extern gpt_params g_params;

class LlamaGenerationSession {
public:
    using ResponseCallback = std::function<void(const std::string&)>;

    LlamaGenerationSession();

    ~LlamaGenerationSession();

    void init(llama_model *model, gpt_params params);

    void printReport();

    int generate(const ResponseCallback& callback);

    void addMessage(const char *string);

    std::string getReport();

private:
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    gpt_sampler *smpl = nullptr;
    gpt_params params;

    ggml_threadpool * threadpool = nullptr;
    ggml_threadpool * threadpool_batch = nullptr;

    bool is_antiprompt        = false;

    int n_past             = 0;
    int n_remain           = 0;
    int n_consumed         = 0;
    int n_session_consumed = 0;

    int n_ctx_train = 0;
    uint32_t n_ctx = 0;
    bool display = false;
    bool is_interacting = true;
    bool input_echo = true;
    bool need_insert_eot = false;

    // group-attention state
    // number of grouped KV tokens so far (used only if params.grp_attn_n > 1)
    int ga_i = 0;
    int ga_n = 0;
    int ga_w = 0;

    std::vector<int>   input_tokens;
    std::vector<int>   output_tokens;
    std::ostringstream output_ss;
    std::vector<std::vector<llama_token>> antiprompt_ids;
    std::vector<llama_chat_msg> chat_msgs;
    std::ostringstream assistant_ss;

    std::vector<llama_token> session_tokens;
    std::vector<llama_token> embd;
    std::vector<llama_token> embd_guidance;
    std::vector<llama_token> guidance_inp;
    std::vector<llama_token> embd_inp;
};

class LlamaModel {
public:
    LlamaModel() = default;
    ~LlamaModel() = default;

    LlamaGenerationSession* createGenerationSession();
    void loadModel(const gpt_params& params,
                   const std::string &modelPath,
                   std::string input_prefix,
                   std::string input_suffix,
                   std::vector<std::string> antiprompt,
                   int32_t n_gpu_layers,
                   llama_progress_callback progress_callback,
                   void* progress_callback_user_data);

    uint64_t getModelSize();

    void unloadModel();

private:
    // Private members for the model, like the model data, etc.
    llama_model *model = nullptr;
    gpt_params params;
};

#endif //LMPLAYGROUND_LLAMACPP_H
