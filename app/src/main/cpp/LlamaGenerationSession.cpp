//
// Created by Andrew Druk on 22.01.2024.
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

    llama_dump_timing_info_yaml(logfile, ctx);
    fclose(logfile);
}

void LlamaGenerationSession::init(gpt_params params_arg) {
    this->params = params_arg;
    llama_sampling_params & sparams = params.sparams;

    auto cparams = llama_context_params_from_gpt_params(params);
    ctx = llama_new_context_with_model(model, cparams);
    if (ctx == NULL) {
        fprintf(stderr, "%s: error: failed to create context with model '%s'\n", __func__, params.model.c_str());
        llama_free_model(model);
        return;
    }

    if (params.ignore_eos) {
        params.sparams.logit_bias[llama_token_eos(model)] = -INFINITY;
    }

    {
        LOG("warming up the model with an empty run\n");
        std::vector<llama_token> tmp = { llama_token_bos(model), llama_token_eos(model), };
        llama_decode(ctx, llama_batch_get_one(tmp.data(), std::min(tmp.size(), (size_t) params.n_batch), 0, 0));
        llama_kv_cache_clear(ctx);
        llama_reset_timings(ctx);
    }

    if (sparams.cfg_scale > 1.f) {
        struct llama_context_params lparams = llama_context_params_from_gpt_params(params);
        ctx_guidance = llama_new_context_with_model(model, lparams);
    }

    if (model == NULL) {
        LOG_TEE("%s: error: unable to load model\n", __func__);
        return;
    }

    const int n_ctx_train = llama_n_ctx_train(model);
    n_ctx = llama_n_ctx(ctx);
    LOG("n_ctx: %d\n", n_ctx);

    if (n_ctx > n_ctx_train) {
        LOG_TEE("%s: warning: model was trained on only %d context tokens (%d specified)\n",
                __func__, n_ctx_train, n_ctx);
    }

    // print system information
    {
        LOG_TEE("\n");
        LOG_TEE("%s\n", gpt_params_get_system_info(params).c_str());
    }

    const bool add_bos = llama_should_add_bos_token(model);
    LOG("add_bos: %d\n", add_bos);

    if (params.interactive_first || !params.prompt.empty() || session_tokens.empty()) {
        LOG("tokenize the prompt\n");
        embd_inp = ::llama_tokenize(ctx, params.prompt, true, true);
    } else {
        LOG("use session tokens\n");
        embd_inp = session_tokens;
    }

    LOG("prompt: \"%s\"\n", log_tostr(params.prompt));
    LOG("tokens: %s\n", LOG_TOKENS_TOSTR_PRETTY(ctx, embd_inp).c_str());

    // Should not run without any tokens
    if (embd_inp.empty()) {
        embd_inp.push_back(llama_token_bos(model));
        LOG("embd_inp was considered empty and bos was added: %s\n", LOG_TOKENS_TOSTR_PRETTY(ctx, embd_inp).c_str());
    }

    // Tokenize negative prompt
    if (ctx_guidance) {
        LOG("cfg_negative_prompt: \"%s\"\n", log_tostr(sparams.cfg_negative_prompt));

        guidance_inp = ::llama_tokenize(ctx_guidance, sparams.cfg_negative_prompt, add_bos, true);
        LOG("guidance_inp tokenized: %s\n", LOG_TOKENS_TOSTR_PRETTY(ctx_guidance, guidance_inp).c_str());

        std::vector<llama_token> original_inp = ::llama_tokenize(ctx, params.prompt, add_bos, true);
        LOG("original_inp tokenized: %s\n", LOG_TOKENS_TOSTR_PRETTY(ctx, original_inp).c_str());

        original_prompt_len = original_inp.size();
        guidance_offset = (int)guidance_inp.size() - original_prompt_len;
        LOG("original_prompt_len: %s", log_tostr(original_prompt_len));
        LOG("guidance_offset:     %s", log_tostr(guidance_offset));
    }

    if ((int) embd_inp.size() > n_ctx - 4) {
        LOG_TEE("%s: error: prompt is too long (%d tokens, max %d)\n", __func__, (int) embd_inp.size(), n_ctx - 4);
        return;
    }

    // number of tokens to keep when resetting context
    if (params.n_keep < 0 || params.n_keep > (int) embd_inp.size()) {
        params.n_keep = (int)embd_inp.size();
    } else {
        params.n_keep += add_bos; // always keep the BOS token
    }

    // prefix & suffix for instruct mode
    const auto inp_pfx = ::llama_tokenize(ctx, "\n\n### Instruction:\n\n", add_bos, true);
    const auto inp_sfx = ::llama_tokenize(ctx, "\n\n### Response:\n\n",    false,   true);

    LOG("inp_pfx: %s\n", LOG_TOKENS_TOSTR_PRETTY(ctx, inp_pfx).c_str());
    LOG("inp_sfx: %s\n", LOG_TOKENS_TOSTR_PRETTY(ctx, inp_sfx).c_str());

    // chatml prefix & suffix
    const auto cml_pfx = ::llama_tokenize(ctx, "\n<|im_start|>user\n", add_bos, true);
    const auto cml_sfx = ::llama_tokenize(ctx, "<|im_end|>\n<|im_start|>assistant\n", false, true);

    LOG("cml_pfx: %s\n", LOG_TOKENS_TOSTR_PRETTY(ctx, cml_pfx).c_str());
    LOG("cml_sfx: %s\n", LOG_TOKENS_TOSTR_PRETTY(ctx, cml_sfx).c_str());

    // enable interactive mode if interactive start is specified
    if (params.interactive_first) {
        params.interactive = true;
    }

    if (params.verbose_prompt) {
        LOG_TEE("\n");
        LOG_TEE("%s: prompt: '%s'\n", __func__, params.prompt.c_str());
        LOG_TEE("%s: number of tokens in prompt = %zu\n", __func__, embd_inp.size());
        for (int i = 0; i < (int) embd_inp.size(); i++) {
            LOG_TEE("%6d -> '%s'\n", embd_inp[i], llama_token_to_piece(ctx, embd_inp[i]).c_str());
        }

        if (ctx_guidance) {
            LOG_TEE("\n");
            LOG_TEE("%s: negative prompt: '%s'\n", __func__, sparams.cfg_negative_prompt.c_str());
            LOG_TEE("%s: number of tokens in negative prompt = %zu\n", __func__, guidance_inp.size());
            for (int i = 0; i < (int) guidance_inp.size(); i++) {
                LOG_TEE("%6d -> '%s'\n", guidance_inp[i], llama_token_to_piece(ctx, guidance_inp[i]).c_str());
            }
        }

        if (params.n_keep > 0) {
            LOG_TEE("%s: static prompt based on n_keep: '", __func__);
            for (int i = 0; i < params.n_keep; i++) {
                LOG_TEE("%s", llama_token_to_piece(ctx, embd_inp[i]).c_str());
            }
            LOG_TEE("'\n");
        }
        LOG_TEE("\n");
    }

    LOG_TEE("sampling: \n%s\n", llama_sampling_print(sparams).c_str());
    LOG_TEE("sampling order: \n%s\n", llama_sampling_order_print(sparams).c_str());
    LOG_TEE("generate: n_ctx = %d, n_batch = %d, n_predict = %d, n_keep = %d\n", n_ctx, params.n_batch, params.n_predict, params.n_keep);
    LOG_TEE("\n\n");

    this->n_remain = params.n_predict;
    this->ctx_sampling = llama_sampling_init(sparams);
}

int LlamaGenerationSession::generate(LlamaGenerationSession::ResponseCallback callback) {
    // predict
    if (!embd.empty()) {
        // Note: n_ctx - 4 here is to match the logic for commandline prompt handling via
        // --prompt or --file which uses the same value.
        int max_embd_size = n_ctx - 4;

        // Ensure the input doesn't exceed the context size by truncating embd if necessary.
        if ((int) embd.size() > max_embd_size) {
            const int skipped_tokens = (int) embd.size() - max_embd_size;
            embd.resize(max_embd_size);

            console::set_display(console::error);
            printf("<<input too long: skipped %d token%s>>", skipped_tokens, skipped_tokens != 1 ? "s" : "");
            console::set_display(console::reset);
            fflush(stdout);
        }

        // infinite text generation via context swapping
        // if we run out of context:
        // - take the n_keep first tokens from the original prompt (via n_past)
        // - take half of the last (n_ctx - n_keep) tokens and recompute the logits in batches
        if (n_past + (int) embd.size() + std::max<int>(0, guidance_offset) > n_ctx) {
            if (params.n_predict == -2) {
                LOG_TEE("\n\n%s: context full and n_predict == -%d => stopping\n", __func__, params.n_predict);
                return 2;
            }

            const int n_left    = n_past - params.n_keep - 1;
            const int n_discard = n_left/2;

            LOG("context full, swapping: n_past = %d, n_left = %d, n_ctx = %d, n_keep = %d, n_discard = %d\n",
                n_past, n_left, n_ctx, params.n_keep, n_discard);

            llama_kv_cache_seq_rm   (ctx, 0, params.n_keep + 1            , params.n_keep + n_discard + 1);

            n_past -= n_discard;

            if (ctx_guidance) {
                n_past_guidance -= n_discard;
            }

            LOG("after swap: n_past = %d, n_past_guidance = %d\n", n_past, n_past_guidance);

            LOG("embd: %s\n", LOG_TOKENS_TOSTR_PRETTY(ctx, embd).c_str());
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

        // evaluate tokens in batches
        // embd is typically prepared beforehand to fit within a batch, but not always
        if (ctx_guidance) {
            int input_size = 0;
            llama_token * input_buf = NULL;

            if (n_past_guidance < (int) guidance_inp.size()) {
                // Guidance context should have the same data with these modifications:
                //
                // * Replace the initial prompt
                // * Shift everything by guidance_offset
                embd_guidance = guidance_inp;
                if (embd.begin() + original_prompt_len < embd.end()) {
                    embd_guidance.insert(
                            embd_guidance.end(),
                            embd.begin() + original_prompt_len,
                            embd.end()
                    );
                }

                input_buf  = embd_guidance.data();
                input_size = embd_guidance.size();

                LOG("guidance context: %s\n", LOG_TOKENS_TOSTR_PRETTY(ctx, embd_guidance).c_str());
            } else {
                input_buf  = embd.data();
                input_size = embd.size();
            }

            for (int i = 0; i < input_size; i += params.n_batch) {
                int n_eval = std::min(input_size - i, params.n_batch);
                if (llama_decode(ctx_guidance, llama_batch_get_one(input_buf + i, n_eval, n_past_guidance, 0))) {
                    LOG_TEE("%s : failed to eval\n", __func__);
                    return 3;
                }

                n_past_guidance += n_eval;
            }
        }

        for (int i = 0; i < (int) embd.size(); i += params.n_batch) {
            int n_eval = (int) embd.size() - i;
            if (n_eval > params.n_batch) {
                n_eval = params.n_batch;
            }

            LOG("eval: %s\n", LOG_TOKENS_TOSTR_PRETTY(ctx, embd).c_str());

            if (llama_decode(ctx, llama_batch_get_one(&embd[i], n_eval, n_past, 0))) {
                LOG_TEE("%s : failed to eval\n", __func__);
                return 4;
            }

            n_past += n_eval;

            LOG("n_past = %d\n", n_past);
        }
    }

    embd.clear();
    embd_guidance.clear();

    if ((int) embd_inp.size() <= n_consumed && !is_interacting) {
        const llama_token id = llama_sampling_sample(ctx_sampling, ctx, ctx_guidance);

        llama_sampling_accept(ctx_sampling, ctx, id, true);

        LOG("last: %s\n", LOG_TOKENS_TOSTR_PRETTY(ctx, ctx_sampling->prev).c_str());

        embd.push_back(id);

        // echo this to console
        input_echo = true;

        // decrement remaining sampling budget
        --n_remain;

        LOG("n_remain: %d\n", n_remain);
    } else {
        // some user input remains from prompt or interaction, forward it to processing
        LOG("embd_inp.size(): %d, n_consumed: %d\n", (int) embd_inp.size(), n_consumed);
        while ((int) embd_inp.size() > n_consumed) {
            embd.push_back(embd_inp[n_consumed]);

            // push the prompt in the sampling context in order to apply repetition penalties later
            // for the prompt, we don't apply grammar rules
            llama_sampling_accept(ctx_sampling, ctx, embd_inp[n_consumed], false);

            ++n_consumed;
            if ((int) embd.size() >= params.n_batch) {
                break;
            }
        }
    }

    // display text
    if (input_echo && display) {
        for (auto id: embd) {
            const std::string token_str = llama_token_to_piece(ctx, id);
            callback(token_str);

            if (embd.size() > 1) {
                input_tokens.push_back(id);
            } else {
                output_tokens.push_back(id);
                output_ss << token_str;
            }
        }
        fflush(stdout);
    }

    // reset color to default if there is no pending user input
    if ((int) embd_inp.size() == n_consumed) {
        display = true;
    }

    // if not currently processing queued inputs;
    if ((int) embd_inp.size() <= n_consumed) {
        // check for reverse prompt in the last n_prev tokens
        if (!params.antiprompt.empty()) {
            const int n_prev = 32;
            const std::string last_output = llama_sampling_prev_str(ctx_sampling, ctx, n_prev);

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

            if (is_antiprompt) {
                LOG("found antiprompt: %s\n", last_output.c_str());
            }
        }

        // deal with end of text token in interactive mode
        if (llama_sampling_last(ctx_sampling) == llama_token_eos(model)) {
            LOG("found EOS token\n");
            if (params.interactive) {
                if (!params.antiprompt.empty()) {
                    // tokenize and inject first reverse prompt
                    const auto first_antiprompt = ::llama_tokenize(ctx, params.antiprompt.front(), false, true);
                    embd_inp.insert(embd_inp.end(), first_antiprompt.begin(), first_antiprompt.end());
                    is_antiprompt = true;
                }

                is_interacting = true;
                printf("\n");
            }
        }

        if (n_past > 0 && is_interacting) {
            LOG("waiting for user input\n");
            return -1;
        }

        if (n_past > 0) {
            if (is_interacting) {
                llama_sampling_reset(ctx_sampling);
            }
            is_interacting = false;
        }
    }

    // end of text token
    if (!embd.empty() && embd.back() == llama_token_eos(model) && !(params.interactive)) {
        LOG_TEE(" [end of text]\n");
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
    if (n_past > 0 && is_interacting) {
        LOG("waiting for user input\n");

        if (params.input_prefix_bos) {
            LOG("adding input prefix BOS token\n");
            embd_inp.push_back(llama_token_bos(model));
        }

        if (!params.input_prefix.empty()) {
            LOG("appending input prefix: '%s'\n", params.input_prefix.c_str());
            printf("%s", params.input_prefix.c_str());
        }

        // Add tokens to embd only if the input buffer is non-empty
        // Entering a empty line lets the user pass control back
        auto buffer = std::string(string);
        if (buffer.length() > 1) {
            // append input suffix if any
            if (!params.input_suffix.empty()) {
                LOG("appending input suffix: '%s'\n", params.input_suffix.c_str());
                printf("%s", params.input_suffix.c_str());
            }

            LOG("buffer: '%s'\n", buffer.c_str());

            const size_t original_size = embd_inp.size();

            if (params.escape) {
                string_process_escapes(buffer);
            }

            const auto line_pfx = ::llama_tokenize(ctx, params.input_prefix, false, true);
            const auto line_inp = ::llama_tokenize(ctx, buffer,              false, false);
            const auto line_sfx = ::llama_tokenize(ctx, params.input_suffix, false, true);
            LOG("input tokens: %s\n", LOG_TOKENS_TOSTR_PRETTY(ctx, line_inp).c_str());

            embd_inp.insert(embd_inp.end(), line_pfx.begin(), line_pfx.end());
            embd_inp.insert(embd_inp.end(), line_inp.begin(), line_inp.end());
            embd_inp.insert(embd_inp.end(), line_sfx.begin(), line_sfx.end());

            // instruct mode: insert response suffix
            /*if (params.instruct) {
                LOG("inserting instruction suffix\n");
                embd_inp.insert(embd_inp.end(), inp_sfx.begin(), inp_sfx.end());
            }
            // chatml mode: insert assistant chat suffix
            if (params.chatml) {
                LOG("inserting chatml suffix\n");
                embd_inp.insert(embd_inp.end(), cml_sfx.begin(), cml_sfx.end());
            }*/

            for (size_t i = original_size; i < embd_inp.size(); ++i) {
                const llama_token token = embd_inp[i];
                output_tokens.push_back(token);
                output_ss << llama_token_to_piece(ctx, token);
            }

            n_remain -= line_inp.size();
            LOG("n_remain: %d\n", n_remain);
        } else {
            LOG("empty line, passing control back\n");
        }

        input_echo = false; // do not echo this again
    }

    if (n_past > 0) {
        if (is_interacting) {
            llama_sampling_reset(ctx_sampling);
        }
        is_interacting = false;
    }

    // end of text token
    if (!embd.empty() && embd.back() == llama_token_eos(model) && !params.interactive) {
        LOG_TEE(" [end of text]\n");
        return;
    }

    // In interactive mode, respect the maximum number of tokens and drop back to user input when reached.
    // We skip this logic when n_predict == -1 (infinite) or -2 (stop at context size).
    if (params.interactive && n_remain <= 0 && params.n_predict >= 0) {
        n_remain = params.n_predict;
        is_interacting = true;
    }
}

LlamaGenerationSession::LlamaGenerationSession(llama_context *g_ctx_guidance,
                                               llama_model *g_model) {
    this->ctx_guidance = g_ctx_guidance;
    this->model = g_model;
}

LlamaGenerationSession::~LlamaGenerationSession() {
    if (ctx != nullptr) {
        llama_free(ctx);
    }
    if (ctx_sampling != nullptr) {
        llama_sampling_free(ctx_sampling);
    }
}

void LlamaGenerationSession::printReport() {
    llama_print_timings(ctx);
    write_logfile(ctx, params, model, input_tokens, output_ss.str(), output_tokens);
}

std::string LlamaGenerationSession::getReport() {
    auto timings = llama_get_timings(ctx);
    std::ostringstream report;
    report << "load time = " << timings.t_load_ms << " ms\n\n";
    report << "sample time = " << timings.t_sample_ms << " ms / " << timings.n_sample << " runs\n";
    report << "(" << 1e3 / timings.t_sample_ms * timings.n_sample << " tokens per second)\n\n";
    report << "prompt eval time = " << timings.t_p_eval_ms << " ms / " << timings.n_p_eval << " tokens\n";
    report << "(" << 1e3 / timings.t_p_eval_ms * timings.n_p_eval << " tokens per second)\n\n";
    report << "eval time = " << timings.t_eval_ms << " ms / " << timings.n_eval << " runs\n";
    report << "(" << 1e3 / timings.t_eval_ms * timings.n_eval << " tokens per second)\n\n";
    report << "total time = " << (timings.t_end_ms - timings.t_start_ms) << " ms / " << (timings.n_p_eval + timings.n_eval) << " tokens\n";
    return report.str();
}
