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

static void write_logfile(
        const llama_context * ctx, const gpt_params & params, const llama_model * model,
        const std::vector<llama_token> & input_tokens, const std::string & output,
        const std::vector<llama_token> & output_tokens
) {
    if (params.logdir.empty()) {
        return;
    }

    const std::string timestamp = string_get_sortable_timestamp();

    const bool success = fs_create_directory_with_parents(params.logdir);
    if (!success) {
        fprintf(stderr, "%s: warning: failed to create logdir %s, cannot write logfile\n",
                __func__, params.logdir.c_str());
        return;
    }

    const std::string logfile_path = params.logdir + timestamp + ".yml";
    FILE * logfile = fopen(logfile_path.c_str(), "w");

    if (logfile == NULL) {
        fprintf(stderr, "%s: failed to open logfile %s\n", __func__, logfile_path.c_str());
        return;
    }

    fprintf(logfile, "binary: main\n");
    char model_desc[128];
    llama_model_desc(model, model_desc, sizeof(model_desc));
    yaml_dump_non_result_info(logfile, params, ctx, timestamp, input_tokens, model_desc);

    fprintf(logfile, "\n");
    fprintf(logfile, "######################\n");
    fprintf(logfile, "# Generation Results #\n");
    fprintf(logfile, "######################\n");
    fprintf(logfile, "\n");

    yaml_dump_string_multiline(logfile, "output", output.c_str());
    yaml_dump_vector_int(logfile, "output_tokens", output_tokens);

    fclose(logfile);
}

static std::string chat_add_and_format(struct llama_model * model, std::vector<llama_chat_msg> & chat_msgs, const std::string & role, const std::string & content) {
    llama_chat_msg new_msg{role, content};
    auto formatted = llama_chat_format_single(model, g_params.chat_template, chat_msgs, new_msg, role == "user");
    chat_msgs.push_back({role, content});
    LOG_DBG("formatted: '%s'\n", formatted.c_str());
    return formatted;
}

void LlamaGenerationSession::init(llama_model *model_arg, gpt_params params_arg) {
    // load the model and apply lora adapter, if any
    LOG_INF("%s: load the model and apply lora adapter, if any\n", __func__);
    params = std::move(params_arg);
    model = model_arg;

    llama_init_result iparams;
    auto cparams = llama_context_params_from_gpt_params(params);

    llama_context * lctx = llama_new_context_with_model(model, cparams);
    if (lctx == NULL) {
        LOG_ERR("%s: failed to create context with model '%s'\n", __func__, params.model.c_str());
        return;
    }

    if (!params.control_vectors.empty()) {
        if (params.control_vector_layer_start <= 0) params.control_vector_layer_start = 1;
        if (params.control_vector_layer_end   <= 0) params.control_vector_layer_end   = llama_n_layer(model);

        const auto cvec = llama_control_vector_load(params.control_vectors);
        if (cvec.n_embd == -1) {
            llama_free(lctx);
            return;
        }

        int err = llama_control_vector_apply(lctx,
                                             cvec.data.data(),
                                             cvec.data.size(),
                                             cvec.n_embd,
                                             params.control_vector_layer_start,
                                             params.control_vector_layer_end);
        if (err) {
            llama_free(lctx);
            return;
        }
    }

    // load and optionally apply lora adapters
    for (auto & la : params.lora_adapters) {
        llama_lora_adapter_container loaded_la;
        loaded_la.path = la.path;
        loaded_la.scale = la.scale;
        loaded_la.adapter = llama_lora_adapter_init(model, la.path.c_str());
        if (loaded_la.adapter == nullptr) {
            LOG_ERR("%s: failed to apply lora adapter '%s'\n", __func__, la.path.c_str());
            llama_free(lctx);
            return;
        }
        iparams.lora_adapters.push_back(loaded_la); // copy to list of loaded adapters
    }
    if (!params.lora_init_without_apply) {
        llama_lora_adapters_apply(lctx, iparams.lora_adapters);
    }

    if (params.sparams.ignore_eos && llama_token_eos(model) == -1) {
        LOG_WRN("%s: warning: model does not have an EOS token, ignoring --ignore-eos\n", __func__);
        params.sparams.ignore_eos = false;
    }

    if (params.warmup) {
        LOG_WRN("%s: warming up the model with an empty run - please wait ... (--no-warmup to disable)\n", __func__);

        std::vector<llama_token> tmp;
        llama_token bos = llama_token_bos(model);
        llama_token eos = llama_token_eos(model);
        // some models (e.g. T5) don't have a BOS token
        if (bos != LLAMA_TOKEN_NULL) {
            tmp.push_back(bos);
        }
        if (eos != LLAMA_TOKEN_NULL) {
            tmp.push_back(eos);
        }
        if (tmp.empty()) {
            tmp.push_back(0);
        }

        if (llama_model_has_encoder(model)) {
            llama_encode(lctx, llama_batch_get_one(tmp.data(), tmp.size(), 0, 0));
            llama_token decoder_start_token_id = llama_model_decoder_start_token(model);
            if (decoder_start_token_id == -1) {
                decoder_start_token_id = bos;
            }
            tmp.clear();
            tmp.push_back(decoder_start_token_id);
        }
        if (llama_model_has_decoder(model)) {
            llama_decode(lctx, llama_batch_get_one(tmp.data(), std::min(tmp.size(), (size_t) params.n_batch), 0, 0));
        }
        llama_kv_cache_clear(lctx);
        llama_synchronize(lctx);
        llama_perf_context_reset(lctx);
    }

    iparams.model   = model;
    iparams.context = lctx;

    ctx = lctx;
    auto & sparams = params.sparams;
    sparams.seed = generate_random_int32();

    LOG_INF("%s: llama threadpool init, n_threads = %d\n", __func__, (int) params.cpuparams.n_threads);

    struct ggml_threadpool_params tpp_batch =
            ggml_threadpool_params_from_cpu_params(params.cpuparams_batch);
    struct ggml_threadpool_params tpp =
            ggml_threadpool_params_from_cpu_params(params.cpuparams);

    set_process_priority(params.cpuparams.priority);

    if (!ggml_threadpool_params_match(&tpp, &tpp_batch)) {
        threadpool_batch = ggml_threadpool_new(&tpp_batch);
        if (!threadpool_batch) {
            LOG_ERR("%s: batch threadpool create failed : n_threads %d\n", __func__, tpp_batch.n_threads);
            return;
        }

        // Start the non-batch threadpool in the paused state
        tpp.paused = true;
    }

    threadpool = ggml_threadpool_new(&tpp);
    if (!threadpool) {
        LOG_ERR("%s: threadpool create failed : n_threads %d\n", __func__, tpp.n_threads);
        return;
    }

    llama_attach_threadpool(ctx, threadpool, threadpool_batch);

    n_ctx_train = llama_n_ctx_train(model);
    n_ctx = llama_n_ctx(ctx);

    smpl = gpt_sampler_init(model, sparams);
    if (!smpl) {
        LOG_ERR("%s: failed to initialize sampling subsystem\n", __func__);
        return;
    }

    if (n_ctx > n_ctx_train) {
        LOG_WRN("%s: model was trained on only %d context tokens (%d specified)\n", __func__, n_ctx_train, n_ctx);
    }

    // print chat template example in conversation mode
    if (params.conversation) {
        if (params.enable_chat_template) {
            LOG_INF("%s: chat template example:\n%s\n", __func__, llama_chat_format_example(model, params.chat_template).c_str());
        } else {
            LOG_INF("%s: in-suffix/prefix is specified, chat template will be disabled\n", __func__);
        }
    }

    // print system information
    {
        LOG_INF("\n");
        LOG_INF("%s\n", gpt_params_get_system_info(params).c_str());
        LOG_INF("\n");
    }

    const bool add_bos = llama_add_bos_token(model);
    if (!llama_model_has_encoder(model)) {
        GGML_ASSERT(!llama_add_eos_token(model));
    }

    LOG_DBG("n_ctx: %d, add_bos: %d\n", n_ctx, add_bos);
    {
        auto prompt = (params.conversation && params.enable_chat_template && !params.prompt.empty())
                      ? chat_add_and_format(model, chat_msgs, "system", params.prompt) // format the system prompt in conversation mode
                      : params.prompt;
        if (params.interactive_first || !params.prompt.empty() || session_tokens.empty()) {
            LOG_DBG("tokenize the prompt\n");
            embd_inp = ::llama_tokenize(ctx, prompt, true, true);
        } else {
            LOG_DBG("use session tokens\n");
            embd_inp = session_tokens;
        }

        LOG_DBG("prompt: \"%s\"\n", prompt.c_str());
        LOG_DBG("tokens: %s\n", string_from(ctx, embd_inp).c_str());
    }

    is_antiprompt        = false;
    input_echo           = true;
    display              = true;

    n_past             = 1;
    n_remain           = params.n_predict;
    n_consumed         = 0;
    n_session_consumed = 0;

    LOG_INF("sampler seed: %u\n",     gpt_sampler_get_seed(smpl));
    LOG_INF("sampler params: \n%s\n", sparams.print().c_str());
    LOG_INF("sampler chain: %s\n",    gpt_sampler_print(smpl).c_str());

    LOG_INF("generate: n_ctx = %d, n_batch = %d, n_predict = %d, n_keep = %d\n", n_ctx, params.n_batch, params.n_predict, params.n_keep);

    // group-attention state
    // number of grouped KV tokens so far (used only if params.grp_attn_n > 1)
    ga_i = 0;

    ga_n = params.grp_attn_n;
    ga_w = params.grp_attn_w;
}

int LlamaGenerationSession::generate(const LlamaGenerationSession::ResponseCallback& callback) {
    // predict
    if (!embd.empty()) {
        // Note: (n_ctx - 4) here is to match the logic for commandline prompt handling via
        // --prompt or --file which uses the same value.
        int max_embd_size = n_ctx - 4;

        // Ensure the input doesn't exceed the context size by truncating embd if necessary.
        if ((int) embd.size() > max_embd_size) {
            const int skipped_tokens = (int) embd.size() - max_embd_size;
            embd.resize(max_embd_size);

            console::set_display(console::error);
            LOG_WRN("<<input too long: skipped %d token%s>>", skipped_tokens, skipped_tokens != 1 ? "s" : "");
            console::set_display(console::reset);
        }

        if (ga_n == 1) {
            // infinite text generation via context shifting
            // if we run out of context:
            // - take the n_keep first tokens from the original prompt (via n_past)
            // - take half of the last (n_ctx - n_keep) tokens and recompute the logits in batches

            if (n_past + (int) embd.size() >= n_ctx) {
                if (!params.ctx_shift){
                    LOG_DBG("\n\n%s: context full and context shift is disabled => stopping\n", __func__);
                    return 1;
                } else {
                    if (params.n_predict == -2) {
                        LOG_DBG("\n\n%s: context full and n_predict == -%d => stopping\n", __func__, params.n_predict);
                        return 1;
                    }

                    const int n_left    = n_past - params.n_keep;
                    const int n_discard = n_left/2;

                    LOG_DBG("context full, swapping: n_past = %d, n_left = %d, n_ctx = %d, n_keep = %d, n_discard = %d\n",
                            n_past, n_left, n_ctx, params.n_keep, n_discard);

                    llama_kv_cache_seq_rm (ctx, 0, params.n_keep            , params.n_keep + n_discard);
                    llama_kv_cache_seq_add(ctx, 0, params.n_keep + n_discard, n_past, -n_discard);

                    n_past -= n_discard;

                    LOG_DBG("after swap: n_past = %d\n", n_past);

                    LOG_DBG("embd: %s\n", string_from(ctx, embd).c_str());
                }
            }
        } else {
            // context extension via Self-Extend
            while (n_past >= ga_i + ga_w) {
                const int ib = (ga_n*ga_i)/ga_w;
                const int bd = (ga_w/ga_n)*(ga_n - 1);
                const int dd = (ga_w/ga_n) - ib*bd - ga_w;

                LOG_DBG("\n");
                LOG_DBG("shift: [%6d, %6d] + %6d -> [%6d, %6d]\n", ga_i, n_past, ib*bd, ga_i + ib*bd, n_past + ib*bd);
                LOG_DBG("div:   [%6d, %6d] / %6d -> [%6d, %6d]\n", ga_i + ib*bd, ga_i + ib*bd + ga_w, ga_n, (ga_i + ib*bd)/ga_n, (ga_i + ib*bd + ga_w)/ga_n);
                LOG_DBG("shift: [%6d, %6d] + %6d -> [%6d, %6d]\n", ga_i + ib*bd + ga_w, n_past + ib*bd, dd, ga_i + ib*bd + ga_w + dd, n_past + ib*bd + dd);

                llama_kv_cache_seq_add(ctx, 0, ga_i,                n_past,              ib*bd);
                llama_kv_cache_seq_div(ctx, 0, ga_i + ib*bd,        ga_i + ib*bd + ga_w, ga_n);
                llama_kv_cache_seq_add(ctx, 0, ga_i + ib*bd + ga_w, n_past + ib*bd,      dd);

                n_past -= bd;

                ga_i += ga_w/ga_n;

                LOG_DBG("\nn_past_old = %d, n_past = %d, ga_i = %d\n\n", n_past + bd, n_past, ga_i);
            }
        }

        // try to reuse a matching prefix from the loaded session instead of re-eval (via n_past)
        if (n_session_consumed < (int) session_tokens.size()) {
            size_t i = 0;
            for ( ; i < embd.size(); i++) {
                if (embd[i] != session_tokens[n_session_consumed]) {
                    session_tokens.resize(n_session_consumed);
                    break;
                }

                n_past++;
                n_session_consumed++;

                if (n_session_consumed >= (int) session_tokens.size()) {
                    ++i;
                    break;
                }
            }
            if (i > 0) {
                embd.erase(embd.begin(), embd.begin() + i);
            }
        }

        for (int i = 0; i < (int) embd.size(); i += params.n_batch) {
            int n_eval = (int) embd.size() - i;
            if (n_eval > params.n_batch) {
                n_eval = params.n_batch;
            }

            LOG_DBG("eval: %s\n", string_from(ctx, embd).c_str());

            if (llama_decode(ctx, llama_batch_get_one(&embd[i], n_eval, n_past, 0))) {
                LOG_ERR("%s : failed to eval\n", __func__);
                return 1;
            }

            n_past += n_eval;

            LOG_DBG("n_past = %d\n", n_past);
            // Display total tokens alongside total time
            if (params.n_print > 0 && n_past % params.n_print == 0) {
                LOG_DBG("\n\033[31mTokens consumed so far = %d / %d \033[0m\n", n_past, n_ctx);
            }
        }
    }

    embd.clear();

    if ((int) embd_inp.size() <= n_consumed && !is_interacting) {
        const llama_token id = gpt_sampler_sample(smpl, ctx, -1);

        gpt_sampler_accept(smpl, id, /* accept_grammar= */ true);

        //LOG_DBG("last: %s\n", string_from(ctx, smpl->prev.to_vector()).c_str());

        embd.push_back(id);

        // echo this to console
        input_echo = true;

        // decrement remaining sampling budget
        --n_remain;

        LOG_DBG("n_remain: %d\n", n_remain);
    } else {
        // some user input remains from prompt or interaction, forward it to processing
        LOG_DBG("embd_inp.size(): %d, n_consumed: %d\n", (int) embd_inp.size(), n_consumed);
        while ((int) embd_inp.size() > n_consumed) {
            embd.push_back(embd_inp[n_consumed]);

            // push the prompt in the sampling context in order to apply repetition penalties later
            // for the prompt, we don't apply grammar rules
            gpt_sampler_accept(smpl, embd_inp[n_consumed], /* accept_grammar= */ false);

            ++n_consumed;
            if ((int) embd.size() >= params.n_batch) {
                break;
            }
        }
    }

    // display text
    if (input_echo && display) {
        for (auto id : embd) {
            const std::string token_str = llama_token_to_piece(ctx, id, params.special);

            // Console/Stream Output
            LOG("%s", token_str.c_str());
            if (callback != nullptr) {
                callback(token_str);
            }

            // Record Displayed Tokens To Log
            // Note: Generated tokens are created one by one hence this check
            if (embd.size() > 1) {
                // Incoming Requested Tokens
                input_tokens.push_back(id);
            } else {
                // Outgoing Generated Tokens
                output_tokens.push_back(id);
                output_ss << token_str;
            }
        }
    }

    // reset color to default if there is no pending user input
    if (input_echo && (int) embd_inp.size() == n_consumed) {
        console::set_display(console::reset);
        display = true;
    }

    // if not currently processing queued inputs;
    if ((int) embd_inp.size() <= n_consumed) {
        // check for reverse prompt in the last n_prev tokens
        if (!params.antiprompt.empty()) {
            const int n_prev = 32;
            const std::string last_output = gpt_sampler_prev_str(smpl, ctx, n_prev);

            is_antiprompt = false;
            // Check if each of the reverse prompts appears at the end of the output.
            // If we're not running interactively, the reverse prompt might be tokenized with some following characters
            // so we'll compensate for that by widening the search window a bit.
            for (std::string & antiprompt : params.antiprompt) {
                size_t extra_padding = params.interactive ? 0 : 2;
                size_t search_start_pos = last_output.length() > static_cast<size_t>(antiprompt.length() + extra_padding)
                                          ? last_output.length() - static_cast<size_t>(antiprompt.length() + extra_padding)
                                          : 0;

                if (last_output.find(antiprompt, search_start_pos) != std::string::npos) {
                    if (params.interactive) {
                        is_interacting = true;
                    }
                    is_antiprompt = true;
                    break;
                }
            }

            // check for reverse prompt using special tokens
            llama_token last_token = gpt_sampler_last(smpl);
            for (std::vector<llama_token> ids: antiprompt_ids) {
                if (ids.size() == 1 && last_token == ids[0]) {
                    if (params.interactive) {
                        is_interacting = true;
                    }
                    is_antiprompt = true;
                    break;
                }
            }

            if (is_antiprompt) {
                LOG_DBG("found antiprompt: %s\n", last_output.c_str());
            }
        }

        // deal with end of generation tokens in interactive mode
        if (llama_token_is_eog(model, gpt_sampler_last(smpl))) {
            LOG_DBG("found an EOG token\n");

            if (params.interactive) {
                if (!params.antiprompt.empty()) {
                    // tokenize and inject first reverse prompt
                    const auto first_antiprompt = ::llama_tokenize(ctx, params.antiprompt.front(), false, true);
                    embd_inp.insert(embd_inp.end(), first_antiprompt.begin(), first_antiprompt.end());
                    is_antiprompt = true;
                }

                if (params.enable_chat_template) {
                    chat_add_and_format(model, chat_msgs, "assistant", assistant_ss.str());
                }
                is_interacting = true;
                LOG("\n");
            }
        }

        // if current token is not EOG, we add it to current assistant message
        if (params.conversation) {
            const auto id = gpt_sampler_last(smpl);
            assistant_ss << llama_token_to_piece(ctx, id, false);
        }

        if (n_past > 0) {
            if (is_interacting) {
                gpt_sampler_reset(smpl);
            }
            is_interacting = false;
        }
    }

    // end of text token
    if (!embd.empty() && embd.back() == llama_token_eos(model) && !(params.interactive)) {
        LOG(" [end of text]\n");
        return 5;
    }

    // In interactive mode, respect the maximum number of tokens and drop back to user input when reached.
    // We skip this logic when n_predict == -1 (infinite) or -2 (stop at context size).
    if (params.interactive && n_remain <= 0 && params.n_predict >= 0) {
        n_remain = params.n_predict;
        is_interacting = true;
    }

    return (n_remain != 0 && !is_antiprompt) ? 0 : 1;
}

void LlamaGenerationSession::addMessage(const char *string) {
    is_interacting = true;

    if (n_past > 0) {
        LOG_DBG("waiting for user input\n");

        if (params.conversation) {
            LOG("\n> ");
        }

        if (params.input_prefix_bos) {
            LOG_DBG("adding input prefix BOS token\n");
            embd_inp.push_back(llama_token_bos(model));
        }

        if (!params.input_prefix.empty() && !params.conversation) {
            LOG_DBG("appending input prefix: '%s'\n", params.input_prefix.c_str());
            LOG("%s", params.input_prefix.c_str());
        }

        // Add tokens to embd only if the input buffer is non-empty
        // Entering a empty line lets the user pass control back
        auto buffer = std::string(string);
        if (buffer.length() > 1) {
            // append input suffix if any
            if (!params.input_suffix.empty() && !params.conversation) {
                LOG_DBG("appending input suffix: '%s'\n", params.input_suffix.c_str());
                LOG("%s", params.input_suffix.c_str());
            }

            LOG_DBG("buffer: '%s'\n", buffer.c_str());

            const size_t original_size = embd_inp.size();

            if (params.escape) {
                string_process_escapes(buffer);
            }

            bool format_chat = params.conversation && params.enable_chat_template;
            std::string user_inp = format_chat
                                   ? chat_add_and_format(model, chat_msgs, "user", buffer)
                                   : std::move(buffer);
            // TODO: one inconvenient of current chat template implementation is that we can't distinguish between user input and special tokens (prefix/postfix)
            const auto line_pfx = ::llama_tokenize(ctx, params.input_prefix, false, true);
            const auto line_inp = ::llama_tokenize(ctx, user_inp,            false, format_chat);
            const auto line_sfx = ::llama_tokenize(ctx, params.input_suffix, false, true);

            LOG_DBG("input tokens: %s\n", string_from(ctx, line_inp).c_str());

            // if user stop generation mid-way, we must add EOT to finish model's last response
            if (need_insert_eot && format_chat) {
                llama_token eot = llama_token_eot(model);
                embd_inp.push_back(eot == -1 ? llama_token_eos(model) : eot);
                need_insert_eot = false;
            }

            embd_inp.insert(embd_inp.end(), line_pfx.begin(), line_pfx.end());
            embd_inp.insert(embd_inp.end(), line_inp.begin(), line_inp.end());
            embd_inp.insert(embd_inp.end(), line_sfx.begin(), line_sfx.end());

            for (size_t i = original_size; i < embd_inp.size(); ++i) {
                const llama_token token = embd_inp[i];
                output_tokens.push_back(token);
                output_ss << llama_token_to_piece(ctx, token);
            }

            // reset assistant message
            assistant_ss.str("");

            n_remain -= line_inp.size();
            LOG_DBG("n_remain: %d\n", n_remain);
        } else {
            LOG_DBG("empty line, passing control back\n");
        }

        input_echo = false; // do not echo this again
    }

    if (n_past > 0) {
        gpt_sampler_reset(smpl);
        is_interacting = false;
    }

    // end of generation
    if (!embd.empty() && llama_token_is_eog(model, embd.back()) && !(params.interactive)) {
        LOG(" [end of text]\n");
        return;
    }

    // In interactive mode, respect the maximum number of tokens and drop back to user input when reached.
    // We skip this logic when n_predict == -1 (infinite) or -2 (stop at context size).
    if (params.interactive && n_remain <= 0 && params.n_predict >= 0) {
        n_remain = params.n_predict;
        is_interacting = true;
    }
}

LlamaGenerationSession::LlamaGenerationSession() = default;

LlamaGenerationSession::~LlamaGenerationSession() {
    gpt_sampler_free(smpl);
    llama_free(ctx);
    llama_backend_free();

    ggml_threadpool_free(threadpool);
    ggml_threadpool_free(threadpool_batch);
}

void LlamaGenerationSession::printReport() {
    LOG("\n\n");
    gpt_perf_print(ctx, smpl);
    write_logfile(ctx, params, model, input_tokens, output_ss.str(), output_tokens);
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
