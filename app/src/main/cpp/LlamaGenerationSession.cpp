//
// Created by Andrew Druk on 22.01.2024.
//

#if defined(__ANDROID__)
#include <jni.h>
#endif
#include <string>

#include "LlamaCpp.h"

#include "common.h"
#include "chat.h"
#include "console.h"
#include "llama.h"
#include "log.h"
#include "reasoning-budget.h"
#include "nlohmann/json.hpp"

#include <algorithm>
#include <cassert>
#include <cinttypes>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <ctime>
#include <fstream>
#include <iostream>
#include <iomanip>
#include <sstream>
#include <string>
#include <utility>
#include <vector>
#include <mutex>

#include <unistd.h>
#if defined(__ANDROID__)
// Linux kernel UAPI header; absent on macOS. <fcntl.h> below already
// provides everything this file uses.
#include <asm-generic/fcntl.h>
#endif
#include <fcntl.h>

#define LMP_LOG_TAG "llama-android.cpp"
#include "lmp_log.h"

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
    destroyToolSampler();
}

void LlamaGenerationSession::destroyToolSampler() {
    if (gsmpl != nullptr) {
        common_sampler_free(gsmpl);
        gsmpl = nullptr;
    }
}

void LlamaGenerationSession::recreateToolSampler(const common_chat_params &render) {
    destroyToolSampler();
    if (render.grammar.empty()) {
        return;
    }

    common_params_sampling sp;
    // Mirror the hand-built chain so non-grammar behavior matches normal chat.
    sp.top_k             = sampler_params.top_k;
    sp.top_p             = sampler_params.top_p;
    sp.min_p             = sampler_params.min_p;
    sp.temp              = sampler_params.temperature;
    sp.penalty_repeat    = sampler_params.repetition_penalty;
    sp.penalty_last_n    = 256;
    sp.seed              = sampler_params.seed;
    sp.min_keep          = 1;

    // Tool-call grammar (lazy, with triggers) from the chat template — this is
    // what constrains the model to emit valid tool calls.
    sp.grammar           = common_grammar(COMMON_GRAMMAR_TYPE_TOOL_CALLS, render.grammar);
    sp.grammar_lazy      = render.grammar_lazy;
    sp.grammar_triggers  = render.grammar_triggers;
    sp.generation_prompt = render.generation_prompt;

    // Reasoning budget (matches tools/cli/cli.cpp). For lazy grammars this also
    // provides thinking-block suppression even when the budget is unlimited.
    if (!render.thinking_end_tags.empty()) {
        sp.reasoning_budget_tokens = sampler_params.thinking_budget;
        if (!render.thinking_start_tag.empty()) {
            sp.reasoning_budget_start = common_tokenize(vocab, render.thinking_start_tag, false, true);
        }
        for (const auto &tag : render.thinking_end_tags) {
            if (!tag.empty()) {
                sp.reasoning_budget_end.push_back(common_tokenize(vocab, tag, false, true));
            }
        }
        sp.reasoning_budget_forced = common_tokenize(vocab, render.thinking_end_tags.front(), false, true);
    }

    gsmpl = common_sampler_init(llama_get_model(ctx), sp);
    LOGi("Tool sampler created: grammar_len=%zu lazy=%d triggers=%zu",
         render.grammar.size(), (int)render.grammar_lazy, render.grammar_triggers.size());
}

void LlamaGenerationSession::setImageData(const unsigned char *data, size_t len) {
    std::lock_guard<std::mutex> lock(image_mutex);
    pending_image_data.assign(data, data + len);
}

void LlamaGenerationSession::init(llama_model *model, const struct common_chat_templates *tmpls,
                                   mtmd_context *mtmd, const SamplerParams &params) {
    mtmd_ctx = mtmd;

    vocab = llama_model_get_vocab(model);
    chat_tmpls = tmpls;

    int n_threads = std::max(1, std::min(kMaxGenerationThreads, (int) sysconf(_SC_NPROCESSORS_ONLN) - 2));
    LOGi("Using %d threads", n_threads);

    int n_ctx_train = llama_model_n_ctx_train(model);
    int n_ctx = std::min(params.n_ctx, n_ctx_train);
    LOGi("Model training context: %d, using: %d", n_ctx_train, n_ctx);

    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = n_ctx;
    ctx_params.n_batch = std::min(n_ctx, kMaxBatchSize);
    ctx_params.n_threads       = n_threads;
    ctx_params.n_threads_batch = n_threads;

    ctx = llama_init_from_model(model, ctx_params);
    if (!ctx) {
        LOGe("%s: error: failed to create the llama_context\n" , __func__);
        return;
    }

    // Abort callback: llama_decode polls this between compute steps, so setting
    // abort_requested lets Stop interrupt the prompt-eval phase (a single big
    // decode), not just the between-token loop. Returns true to abort.
    llama_set_abort_callback(
        ctx,
        [](void *data) -> bool {
            return static_cast<LlamaGenerationSession *>(data)->abort_requested.load();
        },
        this);

    sampler_params = params;

    if (!params.system_prompt.empty()) {
        common_chat_msg system_msg;
        system_msg.role = "system";
        system_msg.content = params.system_prompt;
        messages.push_back(system_msg);
    }

    rebuildSamplerChain();

    prev_len = 0;
}

void LlamaGenerationSession::rebuildSamplerChain(llama_sampler *budget_first) {
    if (smpl != nullptr) {
        llama_sampler_free(smpl);
    }
    auto smplParams = llama_sampler_chain_default_params();
    smplParams.no_perf = false;
    smpl = llama_sampler_chain_init(smplParams);

    // Budget sampler first (must override logits before other samplers filter)
    if (budget_first != nullptr) {
        llama_sampler_chain_add(smpl, budget_first);
    }

    // Repetition penalty (only if > 1.0)
    if (sampler_params.repetition_penalty > 1.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_penalties(llama_vocab_n_tokens(vocab), 256, sampler_params.repetition_penalty, 0.0f, 0.0f));
    }

    // Top-K (only if > 0)
    if (sampler_params.top_k > 0) {
        llama_sampler_chain_add(smpl, llama_sampler_init_top_k(sampler_params.top_k));
    }

    // Top-P (only if < 1.0)
    if (sampler_params.top_p < 1.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_top_p(sampler_params.top_p, 1));
    }

    // Min-P (only if > 0.0)
    if (sampler_params.min_p > 0.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_min_p(sampler_params.min_p, 1));
    }

    // Temperature: greedy if 0, otherwise temp + dist
    if (sampler_params.temperature == 0.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(sampler_params.temperature));
        llama_sampler_chain_add(smpl, llama_sampler_init_dist(sampler_params.seed));
    }
}

void LlamaGenerationSession::clearKvCacheForFreshPrompt() {
    llama_memory_clear(llama_get_memory(ctx), true);
    prev_len = 0;
    last_full_prompt.clear();
    last_prompt_end_pos = 0;
}

void LlamaGenerationSession::requestAbort() {
    abort_requested.store(true);
}

int LlamaGenerationSession::addMessage(const char *string, bool enableThinking) {
    if (chat_tmpls == nullptr || ctx == nullptr) {
        LOGe("addMessage called on uninitialized session");
        return 1;
    }
    // Fresh turn — clear any abort left set by a previous cancel, and drop
    // any batch left over from the previous turn so every early-error return
    // below leaves the session with nothing pending for generate().
    abort_requested.store(false);
    batch = {};

    // Lazy preamble KV-cache: on the first addMessage of a session, try
    // to load (or just-save) the static system+tools prefix so the user
    // delta can re-use the prefix-match fast path. Subsequent calls skip
    // this — preamble_attempted is set after one go so we don't repeat
    // the work after compaction-induced KV clears.
    if (!preamble_attempted && prev_len == 0 && !preamble_cache_path.empty()) {
        tryPreambleCache(enableThinking);
    }

    // Take ownership of any staged image bytes for this turn. Swapping under
    // the lock keeps the critical section tiny and leaves the staging slot
    // empty for the next setImageData.
    std::vector<unsigned char> image_data;
    if (mtmd_ctx != nullptr) {
        std::lock_guard<std::mutex> lock(image_mutex);
        std::swap(image_data, pending_image_data);
    }
    bool has_image = !image_data.empty();

    common_chat_msg user_msg;
    user_msg.role = "user";
    if (has_image) {
        // Prepend media marker so the Jinja template places it correctly
        user_msg.content = std::string(mtmd_default_marker()) + "\n" + string;
    } else {
        user_msg.content = string;
    }
    messages.push_back(user_msg);

    prev_enable_thinking = enableThinking;

    common_chat_params result;
    try {
        result = renderTemplate(enableThinking);
    } catch (const std::exception &e) {
        LOGe("Failed to render chat template: %s", e.what());
        messages.pop_back();
        return 1;
    } catch (...) {
        LOGe("Failed to render chat template: unknown error");
        messages.pop_back();
        return 1;
    }
    std::string full_prompt = result.prompt;
    additional_stops = result.additional_stops;

    // Initialize the PEG parser from the freshly-rendered template so
    // `common_chat_parse` is usable for the rest of this turn — including
    // the strip-thinking pass below. The parser is template-derived, so
    // re-rendering after compaction yields the same parser; no need to
    // redo this setup later in the function.
    if (!result.parser.empty()) {
        parser_params = common_chat_parser_params(result);
        // Always extract reasoning into reasoning_content, regardless of the
        // thinking toggle. Passing NONE makes the gpt-oss/harmony parser leave
        // the raw "<|channel|>analysis<|message|>...<|end|>" markup in the
        // content — gpt-oss emits an analysis channel even at minimal reasoning
        // effort, so "thinking off" must still parse the channels, not dump
        // them. The UI already detects <think> blocks even when the toggle is
        // off (some models always think) and renders them as a collapsible
        // section, so extracting always keeps content clean without losing the
        // reasoning. Models that emit no reasoning when thinking is off yield an
        // empty reasoning_content here, so their output is unchanged.
        parser_params.reasoning_format = COMMON_REASONING_FORMAT_DEEPSEEK;
        // Tool-call parsing follows the session's tools state (set via
        // setTools / a non-empty tools_enabled). When tools are disabled the
        // parser ignores tool_call grammars in the model output.
        parser_params.parse_tool_calls = tools_enabled;
        parser_params.parser.load(result.parser);
        parser_initialized = true;
    } else {
        parser_initialized = false;
    }

    // Build a fresh tool-calling sampler (grammar + budget) for this turn when
    // tools are active; otherwise tear it down so normal chat uses [smpl].
    if (tools_enabled && !result.grammar.empty()) {
        recreateToolSampler(result);
    } else {
        destroyToolSampler();
    }

    response.clear();
    skip_first_decode = false;

    // Vision path: tokenize and eval the full prompt together with the image
    // through mtmd. A vision turn always clears the KV cache and re-evals the
    // whole prompt, so it bypasses the text-only prefix-match / context-
    // compaction / reasoning-budget logic below and returns early. (Tools and
    // vision are mutually exclusive in the UI, so the tool sampler above is a
    // no-op here.)
    if (has_image) {
        return processImageTurn(image_data, enableThinking);
    }

    // Text-only path
    // Check if the rendered prompt prefix matches what finalizeResponse computed.
    if (prev_len > 0) {
        bool prefix_match = (int)full_prompt.size() >= prev_len &&
                            full_prompt.compare(0, prev_len, prev_rendered_prompt) == 0;
        if (!prefix_match) {
            LOGi("Prompt prefix mismatch, clearing KV cache");
            clearKvCacheForFreshPrompt();
        }
    }

    // prev_len == 0 means the whole prompt is about to be re-sent, which is
    // only correct against an empty cache. Several paths zero prev_len without
    // clearing it — a failed re-render in finalizeResponse, replayHistory into
    // a session that has already been used — and stale entries there are doubly
    // harmful: the conversation gets duplicated in context, and the compaction
    // check below believes the window is empty, so it skips compaction and
    // generate() then rejects the batch and returns an empty reply.
    if (prev_len == 0) {
        int stale = (int)llama_memory_seq_pos_max(llama_get_memory(ctx), 0);
        if (stale > 0) {
            LOGi("Re-sending full prompt with %d stale tokens in the KV cache, clearing", stale);
            clearKvCacheForFreshPrompt();
        }
    }

    std::string prompt = full_prompt.substr(prev_len);

    bool is_first = (prev_len == 0);
    int n_ctx = llama_n_ctx(ctx);
    // seq_pos_max returns -1 on an empty cache; clamp so the compaction check
    // sees the same occupancy generate() will.
    int n_ctx_used = std::max(0, (int)llama_memory_seq_pos_max(llama_get_memory(ctx), 0));

    int n_prompt_tokens = -llama_tokenize(vocab, prompt.c_str(), prompt.size(), NULL, 0, is_first, true);

    if (compactContextIfNeeded(result, full_prompt, prompt, is_first,
                               n_ctx_used, n_prompt_tokens, enableThinking) != 0) {
        return 1;
    }

    prompt_tokens.resize(n_prompt_tokens);
    if (llama_tokenize(vocab, prompt.c_str(), prompt.size(), prompt_tokens.data(), prompt_tokens.size(), is_first, true) < 0) {
        LOGe("failed to tokenize the prompt");
        return 1;
    }

    batch = llama_batch_get_one(prompt_tokens.data(), prompt_tokens.size());

    // Save the rendered string we're about to feed so the tool-call
    // path in generate() can roll back to this point on rc=2 and
    // submitToolResults can string-prefix-match against it. Token
    // count gets captured at the end of the first generate() call,
    // after llama_decode has actually committed these tokens to the
    // KV cache.
    last_full_prompt = full_prompt;


    maybeAddBudgetSampler(result, enableThinking);

    return 0;
}

void LlamaGenerationSession::maybeAddBudgetSampler(const common_chat_params &result, bool enableThinking) {
    // Add reasoning budget sampler on first thinking-enabled turn, using
    // the model's actual thinking tags from the template (not hardcoded).
    // Must be first in chain (before top-k/top-p/temp) so it can override logits,
    // so we rebuild the entire sampler chain.
    if (!(sampler_params.thinking_budget >= 0 && enableThinking && !budget_sampler_added && result.supports_thinking && gsmpl == nullptr)) {
        return;
    }

    auto tokenize_str = [&](const std::string &text) -> std::vector<llama_token> {
        int n = -llama_tokenize(vocab, text.c_str(), text.size(), nullptr, 0, false, true);
        std::vector<llama_token> tokens(n);
        llama_tokenize(vocab, text.c_str(), text.size(), tokens.data(), tokens.size(), false, true);
        return tokens;
    };

    std::string start_tag = result.thinking_start_tag;
    std::vector<std::string> end_tags = result.thinking_end_tags;

    // For gpt-oss (Gemma 4) and similar models that use channel-based thinking,
    // thinking_start_tag/end_tags may be empty — detect from preserved tokens
    if (start_tag.empty() && !result.preserved_tokens.empty()) {
        for (const auto &tok : result.preserved_tokens) {
            if (tok.find("channel") != std::string::npos) {
                start_tag = "<|channel|>analysis<|message|>";
                end_tags = {"<|end|>"};
                break;
            }
        }
    }

    if (!start_tag.empty() && !end_tags.empty()) {
        auto start_tokens = tokenize_str(start_tag);
        std::vector<llama_tokens> end_seqs;
        for (const auto &tag : end_tags) {
            if (!tag.empty()) {
                end_seqs.push_back(tokenize_str(tag));
            }
        }
        if (end_seqs.empty()) {
            return;
        }
        auto forced_tokens = end_seqs.front();
        rebuildSamplerChain(common_reasoning_budget_init(
                vocab, {start_tokens}, end_seqs, forced_tokens, sampler_params.thinking_budget));

        budget_sampler_added = true;
        LOGi("Reasoning budget sampler added: budget=%d, start='%s', end='%s'",
             sampler_params.thinking_budget, start_tag.c_str(), end_tags.front().c_str());
    }
}

int LlamaGenerationSession::compactContextIfNeeded(
        common_chat_params &result, std::string &full_prompt,
        std::string &prompt, bool &is_first,
        int &n_ctx_used, int &n_prompt_tokens, bool enableThinking) {
    int n_ctx = llama_n_ctx(ctx);
    bool compacted = false;

    // Stage 1: strip thinking content from older assistant messages
    if (n_ctx_used + n_prompt_tokens > n_ctx) {
        LOGi("Context would overflow (%d + %d > %d), stripping thinking from older turns",
             n_ctx_used, n_prompt_tokens, n_ctx);

        bool stripped_any = false;
        // If the template has no parser, there's no thinking format to
        // strip — the loop is a no-op and we fall through to Stage 2.
        if (parser_initialized) {
            for (size_t i = 0; i + 1 < messages.size(); i++) {
                if (messages[i].role != "assistant") continue;
                try {
                    auto parsed = common_chat_parse(messages[i].content, false, parser_params);
                    if (!parsed.reasoning_content.empty()) {
                        messages[i].content = parsed.content;
                        messages[i].reasoning_content.clear();
                        stripped_any = true;
                    }
                } catch (const std::exception &e) {
                    LOGe("PEG parse failed while stripping older turn: %s", e.what());
                } catch (...) {
                    LOGe("PEG parse failed while stripping older turn: unknown");
                }
            }
        }

        if (stripped_any) {
            try {
                result = renderTemplate(enableThinking);
            } catch (const std::exception &e) {
                LOGe("Failed to render chat template after stripping: %s", e.what());
                messages.pop_back();
                return 1;
            } catch (...) {
                LOGe("Failed to render chat template after stripping: unknown error");
                messages.pop_back();
                return 1;
            }
            full_prompt = result.prompt;
            additional_stops = result.additional_stops;
            prompt = full_prompt;
            is_first = true;
            n_ctx_used = 0;
            n_prompt_tokens = -llama_tokenize(vocab, prompt.c_str(), prompt.size(), NULL, 0, true, true);
            compacted = true;
        }
    }

    // Stage 2: drop oldest user+assistant pairs (prefer dropping image turns first)
    while (n_ctx_used + n_prompt_tokens > n_ctx && messages.size() > 1) {
        LOGi("Still overflowing (%d + %d > %d), dropping oldest turn (%zu messages remain)",
             n_ctx_used, n_prompt_tokens, n_ctx, messages.size());

        auto it = messages.begin();
        if (it->role == "system") ++it;
        if (it == messages.end()) break;
        messages.erase(it);

        it = messages.begin();
        if (it->role == "system") ++it;
        if (it != messages.end() && it->role == "assistant") {
            messages.erase(it);
        }

        try {
            result = renderTemplate(enableThinking);
        } catch (const std::exception &e) {
            LOGe("Failed to render chat template after dropping turns: %s", e.what());
            messages.pop_back();
            return 1;
        } catch (...) {
            LOGe("Failed to render chat template after dropping turns: unknown error");
            messages.pop_back();
            return 1;
        }
        full_prompt = result.prompt;
        additional_stops = result.additional_stops;
        prompt = full_prompt;
        is_first = true;
        n_ctx_used = 0;
        n_prompt_tokens = -llama_tokenize(vocab, prompt.c_str(), prompt.size(), NULL, 0, true, true);
        compacted = true;
    }

    if (compacted) {
        LOGi("Context compacted, clearing KV cache and reprocessing (%d tokens)", n_prompt_tokens);
        clearKvCacheForFreshPrompt();
        is_first = true;
    }
    return 0;
}

int LlamaGenerationSession::processImageTurn(std::vector<unsigned char> &image_data, bool enableThinking) {
    LOGi("Vision path: processing image (%zu bytes) with prompt", image_data.size());

    // Strip media markers from older user messages — we can't re-encode old images,
    // and mtmd_tokenize requires marker count == bitmap count
    std::string marker = mtmd_default_marker();
    for (size_t i = 0; i + 1 < messages.size(); i++) {
        if (messages[i].role == "user") {
            size_t pos;
            while ((pos = messages[i].content.find(marker)) != std::string::npos) {
                // Remove marker and trailing newline
                size_t end = pos + marker.size();
                if (end < messages[i].content.size() && messages[i].content[end] == '\n') end++;
                messages[i].content.erase(pos, end - pos);
            }
        }
    }

    // Re-render prompt after stripping old markers
    common_chat_params result = renderTemplate(enableThinking);
    std::string full_prompt = result.prompt;
    additional_stops = result.additional_stops;

    // Always clear KV cache for vision — we re-eval the full prompt
    llama_memory_clear(llama_get_memory(ctx), true);
    prev_len = 0;

    // Create bitmap from image data.
    // The helper returns a mtmd_helper_bitmap_wrapper and takes a
    // `placeholder` flag; pass false for real image data (not a token-counting
    // placeholder), and unwrap .bitmap (video_ctx is unused for still images).
    // The options only configure video decoding, so the defaults are fine.
    mtmd_bitmap *bitmap = mtmd_helper_bitmap_init_from_buf(
        mtmd_ctx, image_data.data(), image_data.size(),
        /*placeholder=*/false, mtmd_helper_init_opt_default()).bitmap;
    if (bitmap == nullptr) {
        LOGe("Failed to create bitmap from image data");
        return 1;
    }

    // Tokenize prompt with image
    mtmd_input_chunks *chunks = mtmd_input_chunks_init();
    // Zero-init: mtmd_input_text also carries text_len, which mtmd_tokenize
    // reads as the length for its internal std::string assign. Leaving it
    // uninitialized meant every vision turn assigned from a garbage length —
    // undefined behaviour that shows up as a memmove crash inside
    // mtmd_tokenizer's constructor.
    mtmd_input_text text{};
    text.text = full_prompt.c_str();
    text.text_len = full_prompt.size();
    text.add_special = true;
    text.parse_special = true;
    const mtmd_bitmap *bitmaps[] = { bitmap };

    int32_t tokenize_result = mtmd_tokenize(mtmd_ctx, chunks, &text, bitmaps, 1);
    mtmd_bitmap_free(bitmap);

    if (tokenize_result != 0) {
        LOGe("Failed to tokenize vision prompt (error: %d)", tokenize_result);
        mtmd_input_chunks_free(chunks);
        return 1;
    }

    // Log how the prompt split into text vs image tokens. Image token count
    // is the single most useful vision diagnostic: too few means the encoder
    // got a starved representation (blurry/na answers), and it is also what
    // drives CLIP encode time, which users experience as a hang.
    {
        size_t n_chunks = mtmd_input_chunks_size(chunks);
        size_t n_text = 0, n_image = 0;
        for (size_t i = 0; i < n_chunks; i++) {
            const mtmd_input_chunk *chunk = mtmd_input_chunks_get(chunks, i);
            size_t n = mtmd_input_chunk_get_n_tokens(chunk);
            if (mtmd_input_chunk_get_type(chunk) == MTMD_INPUT_CHUNK_TYPE_TEXT) n_text += n;
            else n_image += n;
        }
        LOGi("Vision tokenize: %zu chunks, %zu text tokens, %zu image tokens",
             n_chunks, n_text, n_image);
    }

    // Eval all chunks (text + image)
    int n_batch = llama_n_batch(ctx);
    llama_pos new_n_past = 0;
    // The encode is the other crash-prone Vulkan step. On Adreno 7xx/8xx the
    // GPU can be lost mid-encode, and ggml's fault report then segfaults in
    // the driver's vkGetDeviceFaultInfoEXT. Bracket it with the sentinel like
    // the encoder init, so that costs one crash rather than one per image.
    bool clip_on_vulkan = clipSentinelVulkanActive();
    if (clip_on_vulkan) clipSentinelBeginVulkanAttempt();
    int32_t eval_result;
    try {
        eval_result = mtmd_helper_eval_chunks(
            mtmd_ctx, ctx, chunks, 0, 0, n_batch, true, &new_n_past);
    } catch (const std::exception &e) {
        // A lost GPU surfaces as vk::DeviceLostError; uncaught, it crosses
        // JNI and aborts the process.
        LOGe("Vision eval threw: %s", e.what());
        eval_result = -1;
        if (clip_on_vulkan) clipSentinelBlockVulkan();
    } catch (...) {
        LOGe("Vision eval threw: unknown exception");
        eval_result = -1;
        if (clip_on_vulkan) clipSentinelBlockVulkan();
    }
    if (clip_on_vulkan) clipSentinelEndVulkanAttempt();

    mtmd_input_chunks_free(chunks);

    if (eval_result != 0) {
        LOGe("Failed to eval vision chunks (error: %d)", eval_result);
        return 1;
    }

    LOGi("Vision eval complete, n_past = %d", (int)new_n_past);

    // After mtmd eval, logits are ready — skip first decode in generate()
    skip_first_decode = true;
    return 0;
}

void LlamaGenerationSession::finalizeResponse() {
    common_chat_msg assistant_msg;
    assistant_msg.role = "assistant";
    assistant_msg.content = response;
    messages.push_back(assistant_msg);

    if (parser_initialized) {
        try {
            auto parsed = common_chat_parse(response, /*is_partial=*/false, parser_params);
            prev_had_thinking = !parsed.reasoning_content.empty();
        } catch (const std::exception &e) {
            LOGe("PEG parse failed in finalizeResponse: %s", e.what());
            prev_had_thinking = response.find("</think>") != std::string::npos;
        } catch (...) {
            LOGe("PEG parse failed in finalizeResponse: unknown");
            prev_had_thinking = response.find("</think>") != std::string::npos;
        }
    } else {
        prev_had_thinking = response.find("</think>") != std::string::npos;
    }

    try {
        auto result = renderTemplate(prev_enable_thinking, /*addGenerationPrompt=*/false);
        prev_rendered_prompt = result.prompt;
        prev_len = (int)prev_rendered_prompt.size();
    } catch (const std::exception &e) {
        LOGe("Failed to render chat template in finalizeResponse: %s", e.what());
        prev_rendered_prompt.clear();
        prev_len = 0;
    } catch (...) {
        LOGe("Failed to render chat template in finalizeResponse: unknown error");
        prev_rendered_prompt.clear();
        prev_len = 0;
    }
}

int LlamaGenerationSession::generate(const ResponseCallback& callback) {
    if (ctx == nullptr || smpl == nullptr) {
        LOGe("generate called on uninitialized session");
        return 1;
    }

    // After vision eval, logits are already computed — skip to sampling
    if (skip_first_decode) {
        skip_first_decode = false;
    } else {
        // No pending batch: addMessage/submitToolResults failed (or was never
        // called) this turn, or the prompt delta tokenized to zero tokens.
        // llama_decode aborts the process on an empty batch
        // (GGML_ASSERT(batch.token || batch.embd)), so end the turn instead.
        // No finalizeResponse: a failed turn was already rolled back.
        if (batch.n_tokens <= 0 || batch.token == nullptr) {
            LOGe("generate called with no pending batch");
            return 1;
        }
        int n_ctx = llama_n_ctx(ctx);
        int n_ctx_used = llama_memory_seq_pos_max(llama_get_memory(ctx), 0);
        if (n_ctx_used + batch.n_tokens > n_ctx) {
            LOGe("context size exceeded: n_ctx_used = %d, batch.n_tokens = %d, n_ctx = %d", n_ctx_used, batch.n_tokens, n_ctx);
            finalizeResponse();
            return 1;
        }

        // Process prompt in chunks of n_batch to avoid exceeding the batch limit.
        // After replayHistory or context compaction the prompt can be much larger
        // than n_batch since the entire conversation is re-tokenized.
        int n_batch_limit = llama_n_batch(ctx);
        while (batch.n_tokens > n_batch_limit) {
            llama_batch chunk = llama_batch_get_one(batch.token, n_batch_limit);
            if (llama_decode(ctx, chunk)) {
                LOGe("failed to decode prompt chunk");
                finalizeResponse();
                return 1;
            }
            batch = llama_batch_get_one(batch.token + n_batch_limit, batch.n_tokens - n_batch_limit);
        }

        if (llama_decode(ctx, batch)) {
            LOGe("failed to decode the batch");
            finalizeResponse();
            return 1;
        }
    }

    // Reset sampler and feed prompt tokens so the reasoning budget sampler
    // can detect <think> prefill from chat templates (e.g. Qwen3).
    // Only on the first call per turn (prompt_tokens is non-empty).
    if (!prompt_tokens.empty()) {
        if (gsmpl != nullptr) {
            // Tool path: feed prompt tokens as non-generated (penalty/history
            // context only). The grammar + reasoning-budget prefill is handled
            // at sampler-init time from the template's generation_prompt.
            common_sampler_reset(gsmpl);
            for (const auto &token : prompt_tokens) {
                common_sampler_accept(gsmpl, token, /*is_generated=*/false);
            }
        } else {
            llama_sampler_reset(smpl);
            for (const auto &token : prompt_tokens) {
                llama_sampler_accept(smpl, token);
            }
        }
        prompt_tokens.clear();
        // KV cache now contains the full prompt (everything llama_decode
        // just committed). Snapshot this position — if the model emits
        // a tool_call, we'll discard everything past it and feed only
        // the tool-result delta instead of re-tokenizing the world.
        last_prompt_end_pos = llama_memory_seq_pos_max(llama_get_memory(ctx), 0) + 1;
    }

    if (gsmpl != nullptr) {
        // Mac-parity tool sampling: grammar-constrained sample-then-validate,
        // with controlled accept (common_sampler_sample does not auto-accept).
        last_token = common_sampler_sample(gsmpl, ctx, -1);
        common_sampler_accept(gsmpl, last_token, /*is_generated=*/true);
    } else {
        last_token = llama_sampler_sample(smpl, ctx, -1);
    }

    bool is_eog = llama_vocab_is_eog(vocab, last_token);

    if (!is_eog) {
        char buf[256];
        int n = llama_token_to_piece(vocab, last_token, buf, sizeof(buf), 0, true);
        if (n < 0) {
            LOGe("failed to convert token to piece");
            finalizeResponse();
            return 1;
        }
        std::string piece(buf, n);
        response += piece;

        for (const auto& stop : additional_stops) {
            if (response.size() >= stop.size() &&
                response.compare(response.size() - stop.size(), stop.size(), stop) == 0) {
                response.erase(response.size() - stop.size());
                is_eog = true;
                break;
            }
        }

        if (!is_eog) {
            // Use PEG parser to normalize thinking format for the UI
            if (parser_initialized) {
                try {
                    auto parsed = common_chat_parse(response, /*is_partial=*/true, parser_params);
                    std::string normalized;
                    if (!parsed.reasoning_content.empty()) {
                        normalized = "<think>" + parsed.reasoning_content;
                        if (!parsed.content.empty()) {
                            normalized += "</think>" + parsed.content;
                        }
                    } else {
                        // Emit only the parsed content — NOT the raw response.
                        // During the early channel-header phase (e.g. gpt-oss
                        // streaming "<|channel|>analysis<|message|>") the parser
                        // has nothing to surface yet, so this is empty. Emitting
                        // the raw response here would (a) leak control-token
                        // markup into the UI and (b) make the streamed string
                        // non-monotonic when it later flips to "<think>...",
                        // which corrupts the service's append-only delta
                        // accumulation (GenerationWorker computes
                        // response.substring(sentLength), assuming the string
                        // only ever grows). Keeping it monotonic — "" then
                        // "<think>..." then "<think>...</think>content" — keeps
                        // the deltas correct.
                        normalized = parsed.content;
                    }
                    callback(normalized);
                } catch (const std::exception &e) {
                    LOGe("PEG parse failed in generate (partial): %s", e.what());
                    callback(response);
                } catch (...) {
                    LOGe("PEG parse failed in generate (partial): unknown");
                    callback(response);
                }
            } else {
                callback(response);
            }
            batch = llama_batch_get_one(&last_token, 1);
            return 0;
        }
    }

    // Check for tool calls before finalizing
    if (tools_enabled && parser_initialized) {
        common_chat_msg parsed;
        bool parsed_ok = false;
        try {
            parsed = common_chat_parse(response, /*is_partial=*/false, parser_params);
            parsed_ok = true;
        } catch (const std::exception &e) {
            // Some templates' tool-call parsers throw on their own model's
            // output (e.g. LFM2.5 350M's "<|tool_call_end|>" format). A parse
            // failure must never crash the inference process — fall back to
            // treating the output as a plain-text response.
            LOGe("Tool-call parse failed, treating as plain response: %s", e.what());
        } catch (...) {
            LOGe("Tool-call parse failed (unknown), treating as plain response");
        }
        if (parsed_ok && !parsed.tool_calls.empty()) {
            std::vector<std::string> ids_cache;
            auto gen_id = [this]() -> std::string {
                return "call_" + std::to_string(tool_call_counter++);
            };

            common_chat_msg assistant_msg;
            assistant_msg.role = "assistant";
            assistant_msg.content = parsed.content;
            assistant_msg.reasoning_content = parsed.reasoning_content;
            assistant_msg.tool_calls = parsed.tool_calls;
            assistant_msg.set_tool_call_ids(ids_cache, gen_id);
            pending_tool_calls = assistant_msg.tool_calls;
            messages.push_back(assistant_msg);

            // KV cache reuse for the upcoming submitToolResults: the
            // cache currently contains [last_full_prompt tokens] +
            // [model's tool_call output tokens]. The model's output
            // tokens will be re-rendered differently by the chat
            // template in round 2 (template-specific tool_call
            // formatting != raw bytes the model emitted), so they're
            // not safe to keep. But the prompt prefix IS safe — roll
            // back to that position so submitToolResults can feed only
            // the tool-result delta. Saves a full prompt-eval pass on
            // every tool round (huge on phone CPU where prompt-eval
            // dominates wall time).
            if (last_prompt_end_pos > 0 && !last_full_prompt.empty()) {
                llama_memory_seq_rm(
                    llama_get_memory(ctx), 0, last_prompt_end_pos, -1);
                prev_rendered_prompt = last_full_prompt;
                prev_len = (int)last_full_prompt.size();
            }

            LOGi("Tool calls detected: %zu calls (KV preserved at pos %d)",
                 pending_tool_calls.size(), last_prompt_end_pos);
            response.clear();
            return 2;
        }
    }

    finalizeResponse();
    return 1;
}

void LlamaGenerationSession::printReport() {
    llama_perf_context_print(ctx);
}

void LlamaGenerationSession::replayHistory(const std::vector<std::pair<std::string, std::string>>& history) {
    messages.clear();
    if (!sampler_params.system_prompt.empty()) {
        common_chat_msg system_msg;
        system_msg.role = "system";
        system_msg.content = sampler_params.system_prompt;
        messages.push_back(system_msg);
    }
    for (const auto& pair : history) {
        common_chat_msg user_msg;
        user_msg.role = "user";
        user_msg.content = pair.first;
        messages.push_back(user_msg);

        common_chat_msg assistant_msg;
        assistant_msg.role = "assistant";
        assistant_msg.content = pair.second;
        messages.push_back(assistant_msg);
    }
    prev_len = 0;
    prev_rendered_prompt.clear();
    prev_had_thinking = false;
    prev_enable_thinking = false;
    response.clear();
    last_full_prompt.clear();
    last_prompt_end_pos = 0;
    if (ctx != nullptr) {
        llama_memory_clear(llama_get_memory(ctx), true);
    }
    LOGi("Replayed %zu turns of history", history.size());
}

common_chat_params LlamaGenerationSession::renderTemplate(bool enableThinking, bool addGenerationPrompt) {
    common_chat_templates_inputs inputs;
    inputs.messages = messages;
    inputs.add_generation_prompt = addGenerationPrompt;
    inputs.use_jinja = true;
    inputs.enable_thinking = enableThinking;
    // Always extract reasoning. For channel-based formats (gpt-oss / harmony)
    // the extract-vs-raw decision is baked into the parser grammar at apply
    // time from this field. Leaving it NONE when thinking is off would make
    // the parser dump literal "<|channel|>analysis<|message|>...<|end|>"
    // markup into content — gpt-oss emits the analysis channel even at
    // minimal effort. enable_thinking (above) independently drives the
    // prompt's reasoning effort, so this doesn't force the model to think.
    inputs.reasoning_format = COMMON_REASONING_FORMAT_DEEPSEEK;
    if (tools_enabled) {
        inputs.tools = tools;
        inputs.tool_choice = COMMON_CHAT_TOOL_CHOICE_AUTO;
    }
    return common_chat_templates_apply(chat_tmpls, inputs);
}

std::string LlamaGenerationSession::renderPreambleString(bool enableThinking) {
    if (chat_tmpls == nullptr) return "";

    // Swap messages aside so we can render in isolation, then restore.
    auto saved = std::move(messages);
    messages.clear();

    // Strategy A: render with [system] alone, no generation prompt. Works
    // for Gemma, Qwen3, LFM2.5. Some templates (e.g. Qwen3.5 multimodal)
    // throw because they require at least one user turn — fall back to B.
    std::string preamble;
    if (!sampler_params.system_prompt.empty()) {
        common_chat_msg sys;
        sys.role = "system";
        sys.content = sampler_params.system_prompt;
        messages.push_back(sys);
    }
    try {
        auto result = renderTemplate(enableThinking, /*addGenerationPrompt=*/false);
        preamble = result.prompt;
    } catch (const std::exception &e) {
        LOGi("renderPreambleString A failed (%s), trying fallback", e.what());
    } catch (...) {
        LOGi("renderPreambleString A failed (unknown), trying fallback");
    }

    if (preamble.empty()) {
        // Strategy B: render with [system?, user="<<__PREAMBLE_PROBE__>>"],
        // find the unique probe in the rendered output, return everything
        // up to and including the user-marker prefix that precedes it.
        // We isolate the byte offset of the probe content; the preamble is
        // [0, probe_start_in_output), but we want to KEEP the user-role
        // marker that opens the user turn since it's part of the static
        // structure that follows the preamble. Actually no — we DON'T want
        // the user-marker, because the user turn marker varies (or could
        // be reused). So preamble = [0, probe_start) — everything BEFORE
        // the user-marker content. This gives us the static prefix.
        //
        // The fallback only succeeds if the probe sentinel ends up in the
        // rendered output verbatim (it should, since templates pass user
        // content through unchanged). If not, return empty.
        messages.clear();
        if (!sampler_params.system_prompt.empty()) {
            common_chat_msg sys;
            sys.role = "system";
            sys.content = sampler_params.system_prompt;
            messages.push_back(sys);
        }
        const std::string probe = "__PREAMBLE_PROBE_5MhEU3xQ__";
        common_chat_msg user;
        user.role = "user";
        user.content = probe;
        messages.push_back(user);
        try {
            auto result = renderTemplate(enableThinking, /*addGenerationPrompt=*/false);
            const auto &rendered = result.prompt;
            auto pos = rendered.find(probe);
            if (pos == std::string::npos) {
                LOGi("renderPreambleString fallback: probe not found in rendered output");
            } else {
                // Walk back through any user-role markers immediately
                // preceding the probe so the preamble doesn't include the
                // open-user-turn marker. Those markers are template-
                // specific (e.g. "<|im_start|>user\n", "<start_of_turn>user\n"),
                // so we just trim trailing newlines + a small heuristic
                // window. Simpler & safe: report position right BEFORE
                // the user marker (find the last "<" before pos).
                auto marker_start = rendered.rfind('<', pos);
                if (marker_start == std::string::npos || marker_start < pos - kUserMarkerSearchWindow) {
                    // Couldn't locate — fall back to "everything before the probe content".
                    preamble = rendered.substr(0, pos);
                } else {
                    preamble = rendered.substr(0, marker_start);
                }
                LOGi("renderPreambleString fallback B succeeded: %zu bytes", preamble.size());
            }
        } catch (const std::exception &e) {
            LOGe("renderPreambleString fallback failed: %s", e.what());
        } catch (...) {
            LOGe("renderPreambleString fallback failed: unknown");
        }
    }

    messages = std::move(saved);
    return preamble;
}

void LlamaGenerationSession::setPreambleCachePath(const char* path, const char* fingerprint) {
    preamble_cache_path = (path != nullptr) ? path : "";
    preamble_cache_fingerprint = (fingerprint != nullptr) ? fingerprint : "";
    preamble_attempted = false;
}

bool LlamaGenerationSession::tryPreambleCache(bool enableThinking) {
    preamble_attempted = true; // never retry per session, success or failure

    if (ctx == nullptr || preamble_cache_path.empty()) {
        return false;
    }

    // 1. Derive the preamble string. Returns "" if the template can't render in isolation.
    const std::string preamble = renderPreambleString(enableThinking);
    if (preamble.size() < 32) {
        // Tiny preamble (probably system-only with no tools) — saving is more
        // overhead than benefit. Disk write of an empty cache also burns one
        // LRU slot for nothing. Skip.
        LOGi("Preamble cache: preamble %zu B — too small, skipping", preamble.size());
        return false;
    }

    const std::string bin_path  = preamble_cache_path + ".bin";
    const std::string json_path = preamble_cache_path + ".json";

    auto file_readable = [](const std::string &p) -> bool {
        std::ifstream f(p);
        return f.good();
    };

    // 2. Try cache hit
    if (file_readable(bin_path) && file_readable(json_path)) {
        try {
            std::ifstream jf(json_path);
            nlohmann::json manifest;
            jf >> manifest;

            const bool fp_ok      = manifest.value("fingerprint", "") == preamble_cache_fingerprint;
            const bool ver_ok     = manifest.value("version", 0) == 1;
            const bool ctx_ok     = manifest.value("n_ctx", -1) == llama_n_ctx(ctx);
            const bool prelude_ok = manifest.value("preamble", "") == preamble;

            if (fp_ok && ver_ok && ctx_ok && prelude_ok) {
                std::vector<llama_token> tokens(llama_n_ctx(ctx));
                size_t n_loaded = 0;
                bool ok = llama_state_seq_load_file(ctx, bin_path.c_str(),
                                                    /*dest_seq_id=*/0,
                                                    tokens.data(), tokens.size(),
                                                    &n_loaded);
                if (ok && n_loaded > 0) {
                    prev_rendered_prompt = preamble;
                    prev_len = (int)preamble.size();
                    LOGi("Preamble cache HIT: loaded %zu tokens from %s",
                         n_loaded, bin_path.c_str());
                    return true;
                }
                LOGi("Preamble cache: load returned ok=%d n_loaded=%zu, falling through to miss path",
                     (int)ok, n_loaded);
            } else {
                LOGi("Preamble cache: manifest mismatch (fp=%d ver=%d ctx=%d preamble=%d), regenerating",
                     (int)fp_ok, (int)ver_ok, (int)ctx_ok, (int)prelude_ok);
            }
        } catch (const std::exception &e) {
            LOGi("Preamble cache: manifest read failed (%s), regenerating", e.what());
        } catch (...) {
            LOGi("Preamble cache: manifest read failed, regenerating");
        }
        // Stale or unreadable: clean up so we don't keep retrying.
        unlink(bin_path.c_str());
        unlink(json_path.c_str());
    }

    // 3. Cache miss: prefill the preamble alone, then save.
    int n_ctx = llama_n_ctx(ctx);
    int n_prompt_tokens = -llama_tokenize(vocab, preamble.c_str(), preamble.size(),
                                           nullptr, 0, /*add_special=*/true, /*parse_special=*/true);
    if (n_prompt_tokens <= 0 || n_prompt_tokens >= n_ctx) {
        LOGi("Preamble cache: %d tokens too large for n_ctx=%d, skipping", n_prompt_tokens, n_ctx);
        return false;
    }

    std::vector<llama_token> tokens(n_prompt_tokens);
    if (llama_tokenize(vocab, preamble.c_str(), preamble.size(),
                       tokens.data(), tokens.size(),
                       /*add_special=*/true, /*parse_special=*/true) < 0) {
        LOGi("Preamble cache: tokenize failed");
        return false;
    }

    // Decode in batches matching n_batch from init().
    const int n_batch = std::min(n_ctx, kMaxBatchSize);
    for (int i = 0; i < (int)tokens.size(); i += n_batch) {
        int chunk = std::min(n_batch, (int)tokens.size() - i);
        llama_batch b = llama_batch_get_one(tokens.data() + i, chunk);
        if (llama_decode(ctx, b) != 0) {
            LOGi("Preamble cache: decode failed at chunk %d, clearing KV", i);
            llama_memory_clear(llama_get_memory(ctx), true);
            return false;
        }
    }

    // KV is now populated. Wire prefix-match state so addMessage's existing
    // logic feeds only the user-message delta.
    prev_rendered_prompt = preamble;
    prev_len = (int)preamble.size();

    // Save to disk. Best-effort: a save failure means no cache for next time
    // but the in-memory state is correct, so the current turn benefits anyway.
    if (!llama_state_seq_save_file(ctx, bin_path.c_str(),
                                    /*seq_id=*/0,
                                    tokens.data(), tokens.size())) {
        LOGi("Preamble cache: save_file failed (continuing without cache)");
        unlink(bin_path.c_str());
        return true;
    }

    try {
        nlohmann::ordered_json manifest;
        manifest["version"]     = 1;
        manifest["fingerprint"] = preamble_cache_fingerprint;
        manifest["n_ctx"]       = n_ctx;
        manifest["preamble"]    = preamble;
        manifest["n_tokens"]    = (int)tokens.size();
        std::ofstream out(json_path);
        out << manifest.dump();
        if (!out) {
            LOGi("Preamble cache: manifest write failed, removing .bin to keep state consistent");
            unlink(bin_path.c_str());
        } else {
            LOGi("Preamble cache MISS: prefilled & saved %zu tokens to %s",
                 tokens.size(), bin_path.c_str());
        }
    } catch (const std::exception &e) {
        LOGi("Preamble cache: manifest serialize failed (%s)", e.what());
        unlink(bin_path.c_str());
    }

    return true;
}

void LlamaGenerationSession::setTools(const char *toolsJson) {
    pending_tool_calls.clear();
    if (toolsJson == nullptr || strlen(toolsJson) == 0 || strcmp(toolsJson, "[]") == 0) {
        tools.clear();
        tools_enabled = false;
        return;
    }
    try {
        tools = common_chat_tools_parse_oaicompat(common_json::parse(toolsJson));
        tools_enabled = !tools.empty();
        LOGi("Tools set: %zu tools, enabled: %d", tools.size(), (int)tools_enabled);
    } catch (const std::exception &e) {
        LOGe("Failed to parse tools JSON: %s", e.what());
        tools.clear();
        tools_enabled = false;
    }
}

std::string LlamaGenerationSession::getToolCallsJson() {
    nlohmann::ordered_json arr = nlohmann::ordered_json::array();
    for (const auto &tc : pending_tool_calls) {
        nlohmann::ordered_json obj;
        obj["id"] = tc.id;
        obj["name"] = tc.name;
        obj["arguments"] = tc.arguments;
        arr.push_back(obj);
    }
    return arr.dump();
}

int LlamaGenerationSession::submitToolResults(const char *resultsJson, bool enableThinking) {
    if (chat_tmpls == nullptr || ctx == nullptr) {
        LOGe("submitToolResults called on uninitialized session");
        return 1;
    }
    // New decode phase — clear any abort left set by a previous cancel, and
    // drop the previous turn's batch so error returns leave nothing pending.
    abort_requested.store(false);
    batch = {};

    try {
        auto results = nlohmann::ordered_json::parse(resultsJson);
        for (const auto &result : results) {
            common_chat_msg tool_msg;
            tool_msg.role = "tool";
            tool_msg.content = result["content"].get<std::string>();
            tool_msg.tool_call_id = result["id"].get<std::string>();
            tool_msg.tool_name = result["name"].get<std::string>();
            messages.push_back(tool_msg);
        }
    } catch (const std::exception &e) {
        LOGe("Failed to parse tool results JSON: %s", e.what());
        return 1;
    }

    pending_tool_calls.clear();
    prev_enable_thinking = enableThinking;

    common_chat_params result;
    try {
        result = renderTemplate(enableThinking);
    } catch (const std::exception &e) {
        LOGe("Failed to render template after tool results: %s", e.what());
        return 1;
    } catch (...) {
        LOGe("Failed to render template after tool results: unknown error");
        return 1;
    }

    std::string full_prompt = result.prompt;
    additional_stops = result.additional_stops;
    response.clear();

    // Same prefix-reuse strategy as addMessage: if the new full prompt
    // starts with [prev_rendered_prompt] (which generate()'s tool-call
    // path set to last_full_prompt and rolled the KV cache back to),
    // we feed only the delta and skip re-tokenizing the conversation.
    // Mismatch can happen when the chat template re-renders earlier
    // turns differently for round 2 (e.g. enableThinking flipped, or
    // the template inlines reasoning content into the assistant turn).
    // Fall back to clearing the cache in that case — correctness over
    // speed.
    bool prefix_match = false;
    if (prev_len > 0) {
        prefix_match = (int)full_prompt.size() >= prev_len &&
                       full_prompt.compare(0, prev_len, prev_rendered_prompt) == 0;
        if (!prefix_match) {
            LOGi("submitToolResults: prefix mismatch, clearing KV cache");
            clearKvCacheForFreshPrompt();
        }
    } else {
        // No prefix to reuse (e.g. tool_call detection rolled back
        // before any prompt was fed, or the session was truncated).
        // Clear to be safe and feed everything from scratch.
        clearKvCacheForFreshPrompt();
    }

    std::string prompt = full_prompt.substr(prev_len);
    bool is_first = (prev_len == 0);

    int n_ctx = llama_n_ctx(ctx);
    int n_ctx_used = is_first
        ? 0
        : (int)llama_memory_seq_pos_max(llama_get_memory(ctx), 0);
    int n_prompt_tokens = -llama_tokenize(
        vocab, prompt.c_str(), prompt.size(), NULL, 0, is_first, true);

    if (n_ctx_used + n_prompt_tokens > n_ctx) {
        LOGe("Context overflow in submitToolResults: %d + %d > %d",
             n_ctx_used, n_prompt_tokens, n_ctx);
        return 1;
    }

    prompt_tokens.resize(n_prompt_tokens);
    if (llama_tokenize(vocab, prompt.c_str(), prompt.size(),
                       prompt_tokens.data(), prompt_tokens.size(),
                       is_first, true) < 0) {
        LOGe("failed to tokenize prompt after tool results");
        return 1;
    }

    batch = llama_batch_get_one(prompt_tokens.data(), prompt_tokens.size());

    // Track the new full prompt for the next round's tool-call rollback.
    last_full_prompt = full_prompt;

    if (!result.parser.empty()) {
        parser_params = common_chat_parser_params(result);
        // Same rationale as in addMessage: always extract reasoning so
        // channel markers from gpt-oss/harmony parsers don't leak into
        // content even when thinking is off.
        parser_params.reasoning_format = COMMON_REASONING_FORMAT_DEEPSEEK;
        parser_params.parse_tool_calls = tools_enabled;
        parser_params.parser.load(result.parser);
        parser_initialized = true;
    }

    // Refresh the tool sampler for the response phase: a fresh lazy grammar
    // that allows free text and re-arms for any follow-up tool call.
    if (tools_enabled && !result.grammar.empty()) {
        recreateToolSampler(result);
    } else {
        destroyToolSampler();
    }

    LOGi("Tool results submitted: prefix_match=%d, fed %d delta tokens "
         "(prompt now %zu bytes, %d tokens already in KV)",
         prefix_match ? 1 : 0, n_prompt_tokens, full_prompt.size(), n_ctx_used);
    return 0;
}

std::string LlamaGenerationSession::getReport() {
    auto timings = llama_perf_context(ctx);
    auto sampler_timings = llama_perf_sampler(smpl);

    int n_ctx_total = llama_n_ctx(ctx);
    int n_ctx_used = (int)llama_memory_seq_pos_max(llama_get_memory(ctx), 0);

    std::ostringstream report;

    report << "Session\n";
    report << "  Context: " << n_ctx_used << " / " << n_ctx_total << " tokens\n";
    report << "  Prompt tokens: " << timings.n_p_eval << "\n";
    report << "  Generated tokens: " << timings.n_eval << "\n";
    report << "\n";

    report << "Performance\n";
    report << "  Load time: " << std::fixed << std::setprecision(0) << timings.t_load_ms << " ms\n";
    if (timings.n_p_eval > 0 && timings.t_p_eval_ms > 0) {
        report << "  Prompt eval: " << timings.n_p_eval << " tokens, "
               << std::setprecision(1) << (1e3 / timings.t_p_eval_ms * timings.n_p_eval) << " t/s\n";
    }
    if (timings.n_eval > 0 && timings.t_eval_ms > 0) {
        report << "  Generation: " << timings.n_eval << " tokens, "
               << std::setprecision(1) << (1e3 / timings.t_eval_ms * timings.n_eval) << " t/s\n";
    }
    if (sampler_timings.n_sample > 0 && sampler_timings.t_sample_ms > 0) {
        report << "  Sampling: " << sampler_timings.n_sample << " tokens, "
               << std::setprecision(1) << (1e3 / sampler_timings.t_sample_ms * sampler_timings.n_sample) << " t/s\n";
    }

    return report.str();
}
