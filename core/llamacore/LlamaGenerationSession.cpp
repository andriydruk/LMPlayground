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

    // common_sampler_init throws when the grammar llama.cpp derived from the
    // chat template does not parse (LFM2.5-VL's template yields empty rules).
    // Uncaught, that crosses JNI and aborts the process. Without the grammar
    // the regular sampler runs, and tool calls are still parsed from the
    // output — they are just no longer constrained while generated.
    try {
        gsmpl = common_sampler_init(llama_get_model(ctx), sp);
    } catch (const std::exception &e) {
        LOGe("Tool sampler: %s; continuing without the tool-call grammar", e.what());
        gsmpl = nullptr;
        return;
    }
    LOGi("Tool sampler created: grammar_len=%zu lazy=%d triggers=%zu",
         render.grammar.size(), (int)render.grammar_lazy, render.grammar_triggers.size());
}

void LlamaGenerationSession::setImageData(const unsigned char *data, size_t len) {
    std::lock_guard<std::mutex> lock(image_mutex);
    pending_image_data.assign(data, data + len);
}

void LlamaGenerationSession::init(llama_model *model, const struct common_chat_templates *tmpls,
                                   mtmd_context *mtmd, bool mtmdOnVulkan, const SamplerParams &params) {
    mtmd_ctx = mtmd;
    mtmd_on_vulkan = mtmdOnVulkan;

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

    // Recurrent and hybrid memories refuse a partial llama_memory_seq_rm, and
    // a sliding-window cache has already evicted the cells an older position
    // needs, so for these a rewind must also restore the saved state.
    memory_needs_state_snapshots = llama_model_is_recurrent(model) ||
                                   llama_model_is_hybrid(model) ||
                                   llama_model_n_swa(model) > 0;

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
    resetKvTokens();
    // Every snapshot described a KV prefix that is now gone.
    clearTurnSnapshots();
}

void LlamaGenerationSession::resetKvTokens() {
    kv_tokens.clear();
    kv_tokens_valid = true;
    kv_checkpoints.clear();
}

void LlamaGenerationSession::trackDecoded(const llama_token *tokens, int n) {
    if (kv_tokens_valid && tokens != nullptr && n > 0) {
        kv_tokens.insert(kv_tokens.end(), tokens, tokens + n);
    }
}

void LlamaGenerationSession::clearTurnSnapshots() {
    turn_snapshots.clear();
}

void LlamaGenerationSession::forgetKvPast(int kv_pos) {
    turn_snapshots.erase(
        std::remove_if(turn_snapshots.begin(), turn_snapshots.end(),
                       [&](const TurnSnapshot &s) { return s.kv_pos > kv_pos; }),
        turn_snapshots.end());
    kv_checkpoints.erase(
        std::remove_if(kv_checkpoints.begin(), kv_checkpoints.end(),
                       [&](const KvCheckpoint &c) { return c.kv_pos > kv_pos; }),
        kv_checkpoints.end());
}

int LlamaGenerationSession::userTurnsInMessages(size_t end) const {
    int n = 0;
    for (size_t i = 0; i < end && i < messages.size(); i++) {
        if (messages[i].role == "user") n++;
    }
    return n;
}

int LlamaGenerationSession::messageIndexOfTurn(int turn) const {
    int local = turn - dropped_user_turns;
    if (local < 0) return -1;
    int seen = 0;
    for (size_t i = 0; i < messages.size(); i++) {
        if (messages[i].role != "user") continue;
        if (seen == local) return (int)i;
        seen++;
    }
    return -1;
}

int LlamaGenerationSession::getTurnCount() const {
    return dropped_user_turns + userTurnsInMessages(messages.size());
}

bool LlamaGenerationSession::captureKvCheckpoint() {
    if (!memory_needs_state_snapshots) return true;
    const int kv_pos = (int)llama_memory_seq_pos_max(llama_get_memory(ctx), 0) + 1;
    if (kv_pos <= 0) return true;
    for (const auto &c : kv_checkpoints) {
        if (c.kv_pos == kv_pos) return true;
    }

    KvCheckpoint cp;
    cp.kv_pos = kv_pos;
    const size_t size = llama_state_seq_get_size_ext(ctx, 0, LLAMA_STATE_SEQ_FLAGS_PARTIAL_ONLY);
    if (size == 0 || size > kMaxKvCheckpointBytes) {
        LOGi("KV checkpoint at %d: state of %zu bytes not kept", kv_pos, size);
        return false;
    }
    cp.state.resize(size);
    const size_t written = llama_state_seq_get_data_ext(
            ctx, cp.state.data(), cp.state.size(), 0, LLAMA_STATE_SEQ_FLAGS_PARTIAL_ONLY);
    if (written == 0) {
        LOGi("KV checkpoint at %d: state copy failed", kv_pos);
        return false;
    }
    cp.state.resize(written);

    auto at = std::lower_bound(kv_checkpoints.begin(), kv_checkpoints.end(), kv_pos,
                               [](const KvCheckpoint &c, int pos) { return c.kv_pos < pos; });
    LOGi("KV checkpoint at %d: %zu KB", kv_pos, written / 1024);
    kv_checkpoints.insert(at, std::move(cp));

    size_t bytes = 0;
    for (const auto &c : kv_checkpoints) bytes += c.state.size();
    while (!kv_checkpoints.empty() &&
           (kv_checkpoints.size() > kMaxKvCheckpoints || bytes > kMaxKvCheckpointBytes)) {
        bytes -= kv_checkpoints.front().state.size();
        kv_checkpoints.erase(kv_checkpoints.begin());
    }
    return true;
}

bool LlamaGenerationSession::truncateKvTo(int kv_pos) {
    llama_memory_t mem = llama_get_memory(ctx);
    const int cur = (int)llama_memory_seq_pos_max(mem, 0) + 1;
    if (kv_pos < 0 || kv_pos > cur) return false;
    if (kv_pos == 0) {
        llama_memory_clear(mem, true);
        resetKvTokens();
        forgetKvPast(0);
        return true;
    }
    if (memory_needs_state_snapshots) {
        const KvCheckpoint *cp = nullptr;
        for (const auto &c : kv_checkpoints) {
            if (c.kv_pos == kv_pos) cp = &c;
        }
        if (cp == nullptr) return false;
        if (llama_state_seq_set_data_ext(ctx, cp->state.data(), cp->state.size(), 0,
                                         LLAMA_STATE_SEQ_FLAGS_PARTIAL_ONLY) == 0) {
            // A failed read leaves the stateful part cleared: nothing left
            // to keep.
            LOGi("KV checkpoint at %d failed to restore", kv_pos);
            llama_memory_clear(mem, true);
            resetKvTokens();
            clearTurnSnapshots();
            return false;
        }
    }
    if (!llama_memory_seq_rm(mem, 0, kv_pos, -1) ||
        llama_memory_seq_pos_max(mem, 0) + 1 != kv_pos) {
        LOGi("KV truncate to %d failed, clearing", kv_pos);
        llama_memory_clear(mem, true);
        resetKvTokens();
        clearTurnSnapshots();
        return false;
    }
    if (kv_tokens_valid && (int)kv_tokens.size() >= kv_pos) {
        kv_tokens.resize(kv_pos);
    } else {
        kv_tokens.clear();
        kv_tokens_valid = false;
    }
    forgetKvPast(kv_pos);
    return true;
}

int LlamaGenerationSession::truncateKvAtMost(int kv_pos) {
    const int cur = (int)llama_memory_seq_pos_max(llama_get_memory(ctx), 0) + 1;
    if (kv_pos >= cur) return cur;
    if (!memory_needs_state_snapshots) {
        return truncateKvTo(kv_pos) ? kv_pos : -1;
    }
    // Newest checkpoint at or before the target; position 0 always works.
    int best = 0;
    for (const auto &c : kv_checkpoints) {
        if (c.kv_pos <= kv_pos && c.kv_pos > best) best = c.kv_pos;
    }
    if (best == 0) return -1;
    return truncateKvTo(best) ? best : -1;
}

bool LlamaGenerationSession::reuseKvPrefix(const std::string &full_prompt,
                                           std::vector<llama_token> &tail) {
    const int cur = (int)llama_memory_seq_pos_max(llama_get_memory(ctx), 0) + 1;
    if (!kv_tokens_valid || cur <= 0 || (int)kv_tokens.size() != cur) return false;

    std::vector<llama_token> full = common_tokenize(vocab, full_prompt, /*add_special=*/true,
                                                    /*parse_special=*/true);
    size_t n = 0;
    while (n < full.size() && n < kv_tokens.size() && full[n] == kv_tokens[n]) n++;
    // At least one token must be decoded to get logits for sampling.
    if (n >= full.size()) n = full.size() - 1;
    if (n == 0) return false;

    const int kept = truncateKvAtMost((int)n);
    if (kept <= 0) return false;
    tail.assign(full.begin() + kept, full.end());
    LOGi("Prompt reuse by tokens: %d of %zu tokens kept (common prefix %zu, cache had %d)",
         kept, full.size(), n, cur);
    return true;
}

int LlamaGenerationSession::generationPromptSplit(const common_chat_params &result,
                                                  const std::string &full_prompt) {
    if (!memory_needs_state_snapshots || result.generation_prompt.empty() ||
        prompt_tokens.empty()) {
        return -1;
    }
    // The generation prompt (e.g. "<|im_start|>assistant\n") is where the
    // reply starts; some templates render a reply differently once it stops
    // being the latest one, so the next turn's prompt can diverge right
    // after it. A checkpoint there lets that turn keep everything before.
    const size_t at = full_prompt.rfind(result.generation_prompt);
    if (at == std::string::npos) return -1;
    const int n_gp = (int)common_tokenize(vocab, full_prompt.substr(at), false, true).size();
    const int split = (int)prompt_tokens.size() - n_gp;
    return split > 0 ? split : -1;
}

void LlamaGenerationSession::captureTurnSnapshot(int kv_pos) {
    if (messages.empty() || messages.back().role != "user") return;

    TurnSnapshot snap;
    snap.messages_size = messages.size() - 1;
    snap.turn = dropped_user_turns + userTurnsInMessages(snap.messages_size);
    snap.kv_pos = std::max(0, kv_pos);

    // A snapshot for this turn or a later one belongs to a branch that
    // this turn replaces.
    turn_snapshots.erase(
        std::remove_if(turn_snapshots.begin(), turn_snapshots.end(),
                       [&](const TurnSnapshot &s) { return s.turn >= snap.turn; }),
        turn_snapshots.end());

    if (snap.kv_pos > 0) {
        // Restoring needs either the string prefix state or a token mirror
        // to match the next prompt against, and (stateful memories) a
        // checkpoint at exactly this position.
        if (prev_len <= 0 && !kv_tokens_valid) return;
        if (!captureKvCheckpoint()) return;
        if (prev_len > 0) {
            snap.prev_rendered_prompt = prev_rendered_prompt;
            snap.prev_len = prev_len;
        }
        snap.prev_had_thinking = prev_had_thinking;
        snap.prev_enable_thinking = prev_enable_thinking;
    }

    turn_snapshots.push_back(std::move(snap));
    while (turn_snapshots.size() > kMaxTurnSnapshots) {
        turn_snapshots.erase(turn_snapshots.begin());
    }
}

int LlamaGenerationSession::rewindToTurn(int turnIndex, const char *expectedText) {
    if (chat_tmpls == nullptr || ctx == nullptr) return -1;

    const int idx = messageIndexOfTurn(turnIndex);
    if (idx < 0) {
        LOGi("rewindToTurn(%d): turn not in the session (%d turns, %d dropped)",
             turnIndex, getTurnCount(), dropped_user_turns);
        return -1;
    }
    if (expectedText != nullptr && expectedText[0] != '\0' &&
        messages[idx].content.find(expectedText) == std::string::npos) {
        LOGi("rewindToTurn(%d): turn text doesn't match the caller's", turnIndex);
        return -1;
    }

    // Newest snapshot at or before the target that can still be restored.
    // One for the target itself is exact; an earlier one keeps a shorter
    // prefix and the next addMessage feeds the turns in between again.
    // Copies: truncateKvTo drops snapshots past the position it restores.
    std::vector<TurnSnapshot> candidates;
    for (const auto &s : turn_snapshots) {
        if (s.turn <= turnIndex && s.messages_size <= (size_t)idx) candidates.push_back(s);
    }
    std::sort(candidates.begin(), candidates.end(),
              [](const TurnSnapshot &a, const TurnSnapshot &b) { return a.turn > b.turn; });

    TurnSnapshot restored;
    bool have = false;
    for (auto &candidate : candidates) {
        if (truncateKvTo(candidate.kv_pos)) {
            restored = std::move(candidate);
            have = true;
            break;
        }
    }

    int kept = 0;
    if (have && restored.kv_pos > 0) {
        kept = restored.kv_pos;
        prev_rendered_prompt = restored.prev_rendered_prompt;
        prev_len = restored.prev_len;
        prev_had_thinking = restored.prev_had_thinking;
        prev_enable_thinking = restored.prev_enable_thinking;
        turn_snapshots.erase(
            std::remove_if(turn_snapshots.begin(), turn_snapshots.end(),
                           [&](const TurnSnapshot &s) { return s.turn >= turnIndex; }),
            turn_snapshots.end());
    } else {
        llama_memory_clear(llama_get_memory(ctx), true);
        resetKvTokens();
        clearTurnSnapshots();
        prev_rendered_prompt.clear();
        prev_len = 0;
        // From an empty cache the persistent preamble is the best reuse left.
        preamble_attempted = false;
    }

    messages.resize(idx);
    batch = {};
    prompt_tokens.clear();
    response.clear();
    pending_tool_calls.clear();
    skip_first_decode = false;
    checkpoint_after = -1;
    last_full_prompt.clear();
    last_prompt_end_pos = 0;
    abort_requested.store(false);
    turn_open = false;

    LOGi("rewindToTurn(%d): kept %d KV tokens (snapshot turn %d), %zu messages remain",
         turnIndex, kept, have ? restored.turn : -1, messages.size());
    return kept;
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
    std::string full_prompt = result.prompt.str();
    additional_stops = result.additional_stops;

    // Initialize the PEG parser from the freshly-rendered template so
    // `common_chat_parse` is usable for the rest of this turn — including
    // the strip-thinking pass below. The parser is template-derived, so
    // re-rendering after compaction yields the same parser; no need to
    // redo this setup later in the function.
    if (!result.parser.empty()) {
        // The parser is built at render time: it extracts reasoning because
        // renderTemplate always passes reasoning_format=DEEPSEEK, and parses
        // tool calls only when renderTemplate passed tools.
        parser_params = common_chat_parser_params(result);
        parser_params.parser = result.parser;
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
        int rc = processImageTurn(image_data, enableThinking);
        // Drop the failed turn, or the next one follows an unanswered user
        // message (and its media marker).
        if (rc != 0) messages.pop_back();
        return rc;
    }

    // Text-only path
    // Fast path: the rendered prompt extends what finalizeResponse rendered
    // for the cache, so only the new text is tokenized and fed.
    if (prev_len > 0) {
        bool prefix_match = (int)full_prompt.size() >= prev_len &&
                            full_prompt.compare(0, prev_len, prev_rendered_prompt) == 0;
        if (!prefix_match) {
            size_t at = 0;
            while (at < full_prompt.size() && at < prev_rendered_prompt.size() &&
                   full_prompt[at] == prev_rendered_prompt[at]) at++;
            size_t from = at > 40 ? at - 40 : 0;
            LOGi("Prompt prefix mismatch at byte %zu of %d\n  was: %s\n  now: %s",
                 at, prev_len,
                 prev_rendered_prompt.substr(from, 100).c_str(),
                 full_prompt.substr(from, 100).c_str());
            prev_len = 0;
        }
    }

    // Otherwise the whole prompt is due. Some templates re-render earlier
    // turns (Qwen3 drops the latest reply's empty think block once a new user
    // turn follows), so keep the longest token prefix the cache still shares
    // with it rather than starting over. Anything the cache can't match is
    // cleared: stale entries would duplicate the conversation in context and
    // make the compaction check below misjudge the window.
    std::vector<llama_token> reuse_tail;
    bool token_reuse = false;
    if (prev_len == 0 && llama_memory_seq_pos_max(llama_get_memory(ctx), 0) >= 0) {
        token_reuse = reuseKvPrefix(full_prompt, reuse_tail);
        if (!token_reuse) {
            LOGi("Re-sending the full prompt, clearing the KV cache");
            clearKvCacheForFreshPrompt();
        }
    }

    std::string prompt = token_reuse ? std::string() : full_prompt.substr(prev_len);

    bool is_first = !token_reuse && prev_len == 0;
    int n_ctx = llama_n_ctx(ctx);
    // seq_pos_max returns -1 on an empty cache; clamp so the compaction check
    // sees the same occupancy generate() will.
    int n_ctx_used = std::max(0, (int)llama_memory_seq_pos_max(llama_get_memory(ctx), 0));

    int n_prompt_tokens = token_reuse
        ? (int)reuse_tail.size()
        : -llama_tokenize(vocab, prompt.c_str(), prompt.size(), NULL, 0, is_first, true);

    if (compactContextIfNeeded(result, full_prompt, prompt, is_first,
                               n_ctx_used, n_prompt_tokens, enableThinking) != 0) {
        return 1;
    }
    // Compaction re-renders and clears the cache: the reused prefix is gone.
    if (token_reuse && is_first) token_reuse = false;

    if (token_reuse) {
        prompt_tokens = std::move(reuse_tail);
    } else {
        prompt_tokens.resize(n_prompt_tokens);
        if (llama_tokenize(vocab, prompt.c_str(), prompt.size(), prompt_tokens.data(), prompt_tokens.size(), is_first, true) < 0) {
            LOGe("failed to tokenize the prompt");
            return 1;
        }
    }

    // Nothing of this turn is in the cache yet: remember how to get back
    // here, for edit / regenerate (rewindToTurn).
    captureTurnSnapshot((int)llama_memory_seq_pos_max(llama_get_memory(ctx), 0) + 1);
    checkpoint_after = generationPromptSplit(result, full_prompt);

    batch = llama_batch_get_one(prompt_tokens.data(), prompt_tokens.size());

    // Save the rendered string we're about to feed so the tool-call
    // path in generate() can roll back to this point on rc=2 and
    // submitToolResults can string-prefix-match against it. Token
    // count gets captured at the end of the first generate() call,
    // after llama_decode has actually committed these tokens to the
    // KV cache.
    last_full_prompt = full_prompt;
    turn_open = true;

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
                    auto parsed = common_chat_parse(common_chat_input(messages[i].content), false, parser_params);
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
            full_prompt = result.prompt.str();
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
        if (it->role == "user") dropped_user_turns++;
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
        full_prompt = result.prompt.str();
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

    // An earlier encode in this process lost the GPU this projector runs on.
    // New sessions get a CPU projector (see createGenerationSession).
    if (mtmd_on_vulkan && clipVulkanFailed()) {
        LOGe("Vision unavailable in this session: its Vulkan device was lost");
        return 1;
    }

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
    std::string full_prompt = result.prompt.str();
    additional_stops = result.additional_stops;

    // Always clear KV cache for vision — we re-eval the full prompt
    llama_memory_clear(llama_get_memory(ctx), true);
    prev_len = 0;
    clearTurnSnapshots();
    resetKvTokens();
    // Image embeddings have no token ids: the cache can't be matched by
    // tokens until the next clear.
    kv_tokens_valid = false;

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
    // GPU can be lost mid-encode. ggml's fault report used to segfault in the
    // driver's vkGetDeviceFaultInfoEXT; the fork no longer queries it there,
    // so the loss arrives as vk::DeviceLostError below. Still bracket it with
    // the sentinel like the encoder init: a crash elsewhere in a driver then
    // costs one crash rather than one per image.
    bool clip_on_vulkan = mtmd_on_vulkan;
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

    // The turn started from an empty cache, so rewinding to it is a clear.
    captureTurnSnapshot(0);

    // After mtmd eval, logits are ready — skip first decode in generate()
    skip_first_decode = true;
    turn_open = true;
    return 0;
}

void LlamaGenerationSession::finalizeResponse() {
    turn_open = false;
    common_chat_msg assistant_msg;
    assistant_msg.role = "assistant";
    assistant_msg.content = response;
    messages.push_back(assistant_msg);

    if (parser_initialized) {
        try {
            auto parsed = common_chat_parse(common_chat_input(response), /*is_partial=*/false, parser_params);
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
        if (kv_tokens_valid) {
            // Match the next turn by tokens against exactly what is cached:
            // the cache lacks the end-of-turn markers the template renders
            // after the reply (the token that ended generation is never
            // decoded) and, after a Stop, the last sampled token. A string
            // prefix match would skip them; the token match feeds them.
            prev_rendered_prompt.clear();
            prev_len = 0;
        } else {
            // Image embeddings have no token ids: match by string.
            prev_rendered_prompt = result.prompt.str();
            prev_len = (int)prev_rendered_prompt.size();
        }
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

    // Stopped between tokens: end the turn here, like a natural stop, so the
    // partial reply is committed to the history the next turn renders. The
    // sampled token waiting in [batch] is not decoded. Callers that stop
    // between tokens call generate() once more to get here.
    if (abort_requested.load()) {
        if (turn_open) finalizeResponse();
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
        // than n_batch since the entire conversation is re-tokenized. A stateful
        // memory also stops at [checkpoint_after] (where the reply starts) to
        // checkpoint the state there for the next turn's prefix reuse.
        const int n_batch_limit = llama_n_batch(ctx);
        const int split = (checkpoint_after > 0 && checkpoint_after < batch.n_tokens &&
                           batch.token == prompt_tokens.data()) ? checkpoint_after : -1;
        checkpoint_after = -1;
        const llama_token *tok = batch.token;
        int remaining = batch.n_tokens;
        int done = 0;
        while (remaining > 0) {
            int n = std::min(remaining, n_batch_limit);
            if (split > 0 && done < split && done + n > split) n = split - done;
            if (llama_decode(ctx, llama_batch_get_one(const_cast<llama_token *>(tok), n))) {
                LOGe("failed to decode the batch");
                // Keep the token mirror honest about whatever part of the
                // chunk stayed in the cache.
                const int pos = (int)llama_memory_seq_pos_max(llama_get_memory(ctx), 0) + 1;
                const int extra = pos - (int)kv_tokens.size();
                if (kv_tokens_valid && extra >= 0 && extra <= n) trackDecoded(tok, extra);
                else kv_tokens_valid = false;
                finalizeResponse();
                return 1;
            }
            trackDecoded(tok, n);
            tok += n;
            remaining -= n;
            done += n;
            if (done == split) captureKvCheckpoint();
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
                    auto parsed = common_chat_parse(common_chat_input(response), /*is_partial=*/true, parser_params);
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
            parsed = common_chat_parse(common_chat_input(response), /*is_partial=*/false, parser_params);
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
            // Recurrent / hybrid memories can't drop a suffix (n_rs_seq is 0),
            // and llama_memory_seq_rm then changes nothing: the tool-call
            // tokens stay. Fall back to matching by tokens, which restores
            // the checkpoint taken where the reply started.
            prev_rendered_prompt.clear();
            prev_len = 0;
            if (last_prompt_end_pos > 0 && !last_full_prompt.empty() &&
                truncateKvTo(last_prompt_end_pos)) {
                prev_rendered_prompt = last_full_prompt;
                prev_len = (int)last_full_prompt.size();
            }

            LOGi("Tool calls detected: %zu calls (KV preserved at pos %d)",
                 pending_tool_calls.size(), last_prompt_end_pos);
            response.clear();
            turn_open = false;  // reopened by submitToolResults
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
    dropped_user_turns = 0;
    clearTurnSnapshots();
    resetKvTokens();
    checkpoint_after = -1;
    turn_open = false;
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
        preamble = result.prompt.str();
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
            const std::string rendered = result.prompt.str();
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
                    resetKvTokens();
                    kv_tokens.assign(tokens.begin(), tokens.begin() + (long)n_loaded);
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
        // A load that failed part-way can leave the KV cache half-written, and
        // the prefill below would decode on top of it — a ggml_abort inside
        // llama_decode in 1.9.0/1.9.1 Vitals. The cache is meant to be empty
        // here (prev_len == 0), so start the prefill from a clean one.
        llama_memory_clear(llama_get_memory(ctx), true);
        resetKvTokens();
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
            resetKvTokens();
            return false;
        }
    }

    // KV is now populated. Wire prefix-match state so addMessage's existing
    // logic feeds only the user-message delta.
    prev_rendered_prompt = preamble;
    prev_len = (int)preamble.size();
    resetKvTokens();
    kv_tokens = tokens;

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

int LlamaGenerationSession::continueResponse(bool enableThinking) {
    if (chat_tmpls == nullptr || ctx == nullptr) {
        LOGe("continueResponse called on uninitialized session");
        return 1;
    }
    abort_requested.store(false);
    batch = {};
    if (turn_open || messages.empty() || messages.back().role != "assistant" ||
        !messages.back().tool_calls.empty()) {
        LOGe("continueResponse: no assistant reply to continue");
        return 1;
    }

    common_chat_msg partial = messages.back();
    messages.pop_back();
    prev_enable_thinking = enableThinking;

    common_chat_params result;
    try {
        result = renderTemplate(enableThinking);
    } catch (...) {
        LOGe("continueResponse: failed to render the chat template");
        messages.push_back(partial);
        return 1;
    }
    // The generation prompt followed by the reply so far: generating from
    // here continues the reply rather than starting a new one.
    const std::string full_prompt = result.prompt.str() + partial.content;

    // Keep what the cache already holds of it; after a Stop that is all but
    // the token sampled last.
    std::vector<llama_token> tail;
    const bool reuse = llama_memory_seq_pos_max(llama_get_memory(ctx), 0) >= 0 &&
                       reuseKvPrefix(full_prompt, tail);
    if (!reuse) {
        // An image turn can't be re-fed as text: its embeddings came from mtmd.
        for (const auto &msg : messages) {
            if (msg.content.find(mtmd_default_marker()) != std::string::npos) {
                LOGe("continueResponse: image turn is no longer cached");
                messages.push_back(partial);
                return 1;
            }
        }
        clearKvCacheForFreshPrompt();
        tail = common_tokenize(vocab, full_prompt, /*add_special=*/true, /*parse_special=*/true);
    }

    const int n_ctx = llama_n_ctx(ctx);
    const int n_ctx_used = (int)llama_memory_seq_pos_max(llama_get_memory(ctx), 0) + 1;
    if (tail.empty() || n_ctx_used + (int)tail.size() >= n_ctx) {
        LOGe("continueResponse: can't feed %zu tokens with %d of %d used", tail.size(), n_ctx_used, n_ctx);
        messages.push_back(partial);
        prev_rendered_prompt.clear();
        prev_len = 0;
        return 1;
    }
    prompt_tokens = std::move(tail);
    batch = llama_batch_get_one(prompt_tokens.data(), prompt_tokens.size());
    checkpoint_after = -1;

    additional_stops = result.additional_stops;
    if (!result.parser.empty()) {
        parser_params = common_chat_parser_params(result);
        parser_params.parser = result.parser;
        parser_initialized = true;
    } else {
        parser_initialized = false;
    }
    if (tools_enabled && !result.grammar.empty()) {
        recreateToolSampler(result);
    } else {
        destroyToolSampler();
    }

    // The reply keeps growing from the partial text.
    response = partial.content;
    last_full_prompt = full_prompt;
    skip_first_decode = false;
    turn_open = true;
    maybeAddBudgetSampler(result, enableThinking);

    LOGi("continueResponse: %s, feeding %zu tokens", reuse ? "cache reused" : "re-fed",
         prompt_tokens.size());
    return 0;
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

    std::string full_prompt = result.prompt.str();
    additional_stops = result.additional_stops;
    response.clear();

    // Same prefix-reuse strategy as addMessage: if the new full prompt
    // starts with [prev_rendered_prompt] (which generate()'s tool-call
    // path set to last_full_prompt and rolled the KV cache back to),
    // we feed only the delta and skip re-tokenizing the conversation.
    // Otherwise — the template re-rendered earlier turns (enableThinking
    // flipped, reasoning inlined), or a hybrid memory couldn't drop the
    // tool-call tokens — keep the longest token prefix the cache shares.
    bool prefix_match = false;
    if (prev_len > 0) {
        prefix_match = (int)full_prompt.size() >= prev_len &&
                       full_prompt.compare(0, prev_len, prev_rendered_prompt) == 0;
        if (!prefix_match) {
            LOGi("submitToolResults: prefix mismatch");
            prev_len = 0;
        }
    }
    std::vector<llama_token> reuse_tail;
    bool token_reuse = false;
    if (prev_len == 0 && llama_memory_seq_pos_max(llama_get_memory(ctx), 0) >= 0) {
        token_reuse = reuseKvPrefix(full_prompt, reuse_tail);
        if (!token_reuse) clearKvCacheForFreshPrompt();
    }

    std::string prompt = token_reuse ? std::string() : full_prompt.substr(prev_len);
    bool is_first = !token_reuse && prev_len == 0;

    int n_ctx = llama_n_ctx(ctx);
    int n_ctx_used = is_first
        ? 0
        : (int)llama_memory_seq_pos_max(llama_get_memory(ctx), 0);
    int n_prompt_tokens = token_reuse
        ? (int)reuse_tail.size()
        : -llama_tokenize(vocab, prompt.c_str(), prompt.size(), NULL, 0, is_first, true);

    if (n_ctx_used + n_prompt_tokens > n_ctx) {
        LOGe("Context overflow in submitToolResults: %d + %d > %d",
             n_ctx_used, n_prompt_tokens, n_ctx);
        return 1;
    }

    if (token_reuse) {
        prompt_tokens = std::move(reuse_tail);
    } else {
        prompt_tokens.resize(n_prompt_tokens);
        if (llama_tokenize(vocab, prompt.c_str(), prompt.size(),
                           prompt_tokens.data(), prompt_tokens.size(),
                           is_first, true) < 0) {
            LOGe("failed to tokenize prompt after tool results");
            return 1;
        }
    }
    checkpoint_after = generationPromptSplit(result, full_prompt);

    batch = llama_batch_get_one(prompt_tokens.data(), prompt_tokens.size());

    // Track the new full prompt for the next round's tool-call rollback.
    last_full_prompt = full_prompt;
    turn_open = true;

    if (!result.parser.empty()) {
        parser_params = common_chat_parser_params(result);
        parser_params.parser = result.parser;
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
