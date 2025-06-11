//
// Created by Andrew Druk on 22.01.2024.
//

#include <jni.h>
#include <string>

#include "LlamaCpp.h"

#include "common.h"
#include "console.h"
#include "llama.h"
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

#include <unistd.h>
#include <android/log.h>
#include <asm-generic/fcntl.h>
#include <fcntl.h>

#define TAG "llama-android.cpp"
#define LOGi(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGe(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

bool is_valid_utf8(const char * string) {
    if (!string) {
        return true;
    }

    const unsigned char *bytes = (const unsigned char *) string;
    int num;

    while (*bytes != 0x00) {
        if ((*bytes & 0x80) == 0x00) {
            // U+0000 to U+007F
            num = 1;
        } else if ((*bytes & 0xE0) == 0xC0) {
            // U+0080 to U+07FF
            num = 2;
        } else if ((*bytes & 0xF0) == 0xE0) {
            // U+0800 to U+FFFF
            num = 3;
        } else if ((*bytes & 0xF8) == 0xF0) {
            // U+10000 to U+10FFFF
            num = 4;
        } else {
            return false;
        }

        bytes += 1;
        for (int i = 1; i < num; ++i) {
            if ((*bytes & 0xC0) != 0x80) {
                return false;
            }
            bytes += 1;
        }
    }

    return true;
}

LlamaGenerationSession::LlamaGenerationSession() = default;

LlamaGenerationSession::~LlamaGenerationSession() {
    if (ctx != nullptr) {
        llama_free(ctx);
    }
    if (smpl != nullptr) {
        llama_sampler_free(smpl);
    }
    if (messages != nullptr) {
        delete messages;
    }
    if (formatted != nullptr) {
        delete formatted;
    }
}

void LlamaGenerationSession::init(llama_model *model,
                                  int32_t n_ctx,
                                  float   temperature,
                                  float   top_p,
                                  int32_t top_k) {

    vocab = llama_model_get_vocab(model);

    int n_threads = std::max(1, std::min(8, (int) sysconf(_SC_NPROCESSORS_ONLN) - 2));
    LOGi("Using %d threads", n_threads);

    // initialize the context
    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = n_ctx;
    ctx_params.n_batch = n_ctx;
    ctx_params.n_threads       = n_threads;
    ctx_params.n_threads_batch = n_threads;

    ctx = llama_init_from_model(model, ctx_params);
    if (!ctx) {
        LOGe("%s: error: failed to create the llama_context\n" , __func__);
        return;
    }

    auto smplParams = llama_sampler_chain_default_params();
    smplParams.no_perf = true; // disable performance tracking for the sampler

    // initialize the sampler
    smpl = llama_sampler_chain_init(smplParams);
    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(top_k));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(top_p, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    messages = new std::vector<llama_chat_message>();
    formatted = new std::vector<char>(ctx_params.n_ctx);
    tmpl = llama_model_chat_template(model, /* name */ nullptr);
    prev_len = 0;
}

int LlamaGenerationSession::addMessage(const char *string) {
    // add the user input to the message list and format it
    messages->push_back({"user", strdup(string)});
    int new_len = llama_chat_apply_template(tmpl, messages->data(), messages->size(), true, formatted->data(), formatted->size());
    if (new_len > (int)formatted->size()) {
        formatted->resize(new_len);
        new_len = llama_chat_apply_template(tmpl, messages->data(), messages->size(), true, formatted->data(), formatted->size());
    }
    if (new_len < 0) {
        LOGe("failed to apply the chat template");
        return 1;
    }

    // remove previous messages to obtain the prompt to generate the response
    std::string prompt(formatted->begin() + prev_len, formatted->begin() + new_len);

    response.clear();

    const bool is_first = llama_memory_seq_pos_max(llama_get_memory(ctx), 0) == 0;

    // tokenize the prompt
    const int n_prompt_tokens = -llama_tokenize(vocab, prompt.c_str(), prompt.size(), NULL, 0, is_first, true);
    prompt_tokens.resize(n_prompt_tokens);
    if (llama_tokenize(vocab, prompt.c_str(), prompt.size(), prompt_tokens.data(), prompt_tokens.size(), is_first, true) < 0) {
        LOGe("failed to tokenize the prompt");
        return 1;
    }

    // Print the prompt tokens for debugging
    for (const auto& token : prompt_tokens) {
        char buf[256];
        int n = llama_token_to_piece(vocab, token, buf, sizeof(buf), 0, true);
        if (n < 0) {
            LOGe("failed to convert token to piece");
            return 1;
        }
        std::string piece(buf, n);
    }

    batch = llama_batch_get_one(prompt_tokens.data(), prompt_tokens.size());

    return 0;
}

int LlamaGenerationSession::generate(const ResponseCallback& callback) {
    // check if we have enough space in the context to evaluate this batch
    int n_ctx = llama_n_ctx(ctx);
    int n_ctx_used = llama_memory_seq_pos_max(llama_get_memory(ctx), 0);
    if (n_ctx_used + batch.n_tokens > n_ctx) {
        LOGe("context size exceeded: n_ctx_used = %d, batch.n_tokens = %d, n_ctx = %d", n_ctx_used, batch.n_tokens, n_ctx);
        return 1; // context size exceeded
    }

    if (llama_decode(ctx, batch)) {
        LOGe("failed to decode the batch");
        return 1; // decoding failed
    }

    // sample the next token
    last_token = llama_sampler_sample(smpl, ctx, -1);

    // is it an end of generation?
    if (llama_vocab_is_eog(vocab, last_token)) {
        // add the response to the messages
        messages->push_back({"assistant", strdup(response.c_str())});
        prev_len = llama_chat_apply_template(tmpl, messages->data(), messages->size(), false, nullptr, 0);
        if (prev_len < 0) {
            LOGe("failed to apply the chat template");
        }
        return 1; // end of generation
    }

    // convert the token to a string, print it and add it to the response
    char buf[256];
    int n = llama_token_to_piece(vocab, last_token, buf, sizeof(buf), 0, true);
    if (n < 0) {
        LOGe("failed to convert token to piece");
        return 1; // conversion failed
    }
    std::string piece(buf, n);
    response += piece;

    callback(piece.c_str());

    // prepare the next batch with the sampled token
    batch = llama_batch_get_one(&last_token, 1);
    return 0;
}

void LlamaGenerationSession::printReport() {
    llama_perf_context_print(ctx);
}

std::string LlamaGenerationSession::getReport() {
    auto timings = llama_perf_context(ctx);
    std::ostringstream report;
    report << "load time = " << timings.t_load_ms << " ms\n\n";
    report << "prompt eval time = " << timings.t_p_eval_ms << " ms / " << timings.n_p_eval << " tokens\n";
    report << "(" << 1e3 / timings.t_p_eval_ms * timings.n_p_eval << " tokens per second)\n\n";
    report << "eval time = " << timings.t_eval_ms << " ms / " << timings.n_eval << " runs\n";
    report << "(" << 1e3 / timings.t_eval_ms * timings.n_eval << " tokens per second)\n\n";
    return report.str();
}
