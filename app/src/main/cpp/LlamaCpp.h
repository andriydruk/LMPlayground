//
// Created by Andrew Druk on 22.01.2024.
//

#ifndef LMPLAYGROUND_LLAMACPP_H
#define LMPLAYGROUND_LLAMACPP_H

#include "common.h"
#include "chat.h"
#include "sampling.h"
#include "mtmd.h"
#include "mtmd-helper.h"

#include <atomic>
#include <memory>
#include <mutex>

// Decode batch cap — bounds per-decode compute kernel launches on mobile.
constexpr int kMaxBatchSize = 512;
// Generation thread cap — leaves cores free for the UI process.
constexpr int kMaxGenerationThreads = 4;
// Vision (CLIP) encode thread cap — encode is short-lived, so use more cores.
constexpr int kMaxVisionThreads = 8;
// renderPreambleString fallback: how far back from the probe position to
// search for the opening '<' of a template's user-turn marker.
constexpr size_t kUserMarkerSearchWindow = 80;

// Vulkan CLIP crash sentinel (implemented in native-lib.cpp). A marker file is
// written right before each crash-prone Vulkan vision step and removed right
// after it returns; if it survives to the next process start, the attempt took
// the :llama process down, so Vulkan vision is disabled for this install and
// CLIP runs on CPU. clipSentinelInit() promotes a surviving marker to a
// permanent block; begin/end bracket the encoder init in LlamaModel and the
// encode in processImageTurn. clipSentinelBlockVulkan() blocks it outright,
// for a failure that was caught rather than crashed.
//
// clipVisionDevice() resolves MTMD_BACKEND_DEVICE (our own process-wide
// choice, set in init and flipped to "CPU" by a block) to the device to pass
// in mtmd_context_params; nullptr means CPU. clip.cpp no longer reads the env
// var itself (upstream #23255, since b11200), so it must be passed explicitly.
ggml_backend_dev_t clipVisionDevice();
void clipSentinelInit(const std::string &stateDir);
bool clipSentinelVulkanBlocked();
bool clipSentinelVulkanActive();
void clipSentinelBeginVulkanAttempt();
void clipSentinelEndVulkanAttempt();
void clipSentinelBlockVulkan();

struct SamplerParams {
    int n_ctx;
    float temperature;
    float top_p;
    float repetition_penalty;
    int top_k;
    float min_p;
    uint32_t seed;
    int32_t thinking_budget; // -1 = unlimited
    std::string system_prompt;
};

class LlamaGenerationSession {
public:
    using ResponseCallback = std::function<void(const std::string&)>;

    LlamaGenerationSession();

    ~LlamaGenerationSession();

    void init(llama_model *model, const struct common_chat_templates *chat_tmpls,
              mtmd_context *mtmd_ctx, const SamplerParams &params);

    void printReport();

    // Returns: 0 = more tokens, 1 = done, 2 = tool calls detected
    int generate(const ResponseCallback& callback);

    int addMessage(const char *string, bool enableThinking);

    // Stage image bytes (decoded/resized JPEG/PNG) for the next user turn.
    // Consumed by the next addMessage() via the mtmd vision path.
    void setImageData(const unsigned char *data, size_t len);

    // Request that any in-progress decode (prompt eval or token generation)
    // abort as soon as the engine next checks the abort callback. Thread-safe:
    // called from the cancel path while a worker thread is inside generate().
    void requestAbort();

    std::string getReport();

    void replayHistory(const std::vector<std::pair<std::string, std::string>>& history);

    // Tool calling
    void setTools(const char *toolsJson);
    std::string getToolCallsJson();
    int submitToolResults(const char *resultsJson, bool enableThinking);

    // Render the preamble (system prompt + tools description) by applying
    // the chat template with messages temporarily reduced to just the
    // system message and add_generation_prompt=false. Returns the rendered
    // preamble string, or empty on template failure / unsupported. Used by
    // the preamble KV-cache feature to compute a stable cache key and
    // boundary for the static prefix shared across all new sessions.
    std::string renderPreambleString(bool enableThinking);

    // Configure persistent preamble KV cache. Lazy: the actual load/save
    // happens inside the first addMessage() call. Pass empty path to
    // disable. Path is the cache prefix (we write <path>.bin and <path>.json).
    void setPreambleCachePath(const char* path, const char* fingerprint);

private:
    void finalizeResponse();
    common_chat_params renderTemplate(bool enableThinking, bool addGenerationPrompt = true);

    // First-message-only: try to load a saved preamble KV state from
    // [preamble_cache_path], or (on miss) prefill the preamble and save
    // it for next time. On either success path, [prev_rendered_prompt]
    // and [prev_len] are populated so the existing prefix-match logic in
    // addMessage feeds only the user-message delta to the model. Returns
    // true if the preamble was set up (cache hit OR miss-then-saved),
    // false if the preamble couldn't be derived or fed (caching disabled
    // for this turn — addMessage falls back to its normal full-prefill).
    bool tryPreambleCache(bool enableThinking);

    // (Re)create the tool-calling sampler — a common_sampler that applies the
    // chat template's tool-call grammar (lazy, with triggers) plus the
    // reasoning budget, exactly like llama.cpp's reference path. Used only while
    // tools are active; normal chat keeps using [smpl] unchanged.
    void recreateToolSampler(const common_chat_params &render);
    void destroyToolSampler();

    // (Re)build [smpl] from [sampler_params], freeing any existing chain.
    // [budget_first] (optional, ownership transferred) is placed at the
    // head of the chain — the reasoning-budget sampler must override
    // logits before the other samplers filter them.
    void rebuildSamplerChain(llama_sampler *budget_first = nullptr);

    // First thinking-enabled turn with a budget configured: rebuild the
    // sampler chain with the reasoning-budget sampler first, using the
    // model's actual thinking tags from the rendered template.
    void maybeAddBudgetSampler(const common_chat_params &result, bool enableThinking);

    // Vision turn: strip old media markers, re-render, clear the KV cache
    // and eval the full prompt together with [image_data] through mtmd.
    // Returns 0 on success, 1 on failure (addMessage returns it as-is).
    int processImageTurn(std::vector<unsigned char> &image_data, bool enableThinking);

    // Context-overflow compaction for addMessage's text path. Stage 1
    // strips thinking from older assistant turns; Stage 2 drops oldest
    // turns. Mutates the prompt-build state in place; on compaction the
    // KV cache is cleared and the full prompt re-fed. Returns 0 to
    // proceed, 1 on render failure (the just-added user message has been
    // rolled back).
    int compactContextIfNeeded(
        common_chat_params &result, std::string &full_prompt,
        std::string &prompt, bool &is_first,
        int &n_ctx_used, int &n_prompt_tokens, bool enableThinking);

    // Reset the incremental-prefix state and clear the KV cache — the next
    // prompt is fed from scratch.
    void clearKvCacheForFreshPrompt();

    const struct llama_vocab * vocab = nullptr;
    llama_context * ctx = nullptr;
    llama_sampler * smpl = nullptr;
    const struct common_chat_templates * chat_tmpls = nullptr;
    mtmd_context * mtmd_ctx = nullptr;
    bool prev_had_thinking = false;
    bool prev_enable_thinking = false;
    std::string prev_rendered_prompt;
    std::vector<common_chat_msg> messages;
    std::vector<std::string> additional_stops;
    int prev_len = 0;
    std::vector<llama_token> prompt_tokens;
    // Most recent full prompt rendered with addGenerationPrompt=true (the
    // string we fed to the model right before generation started). Saved
    // so submitToolResults can roll back to this point and reuse the KV
    // cache, feeding only the tool-result delta instead of re-tokenizing
    // the entire conversation. Empty until a turn has been fed.
    std::string last_full_prompt;
    // KV-cache position (in tokens) immediately after [last_full_prompt]
    // was fed. Used by the tool-call detection path to truncate the
    // model's generated tokens out of the cache while preserving the
    // prompt prefix.
    int last_prompt_end_pos = 0;
    llama_token last_token = 0;
    // Pending tokens for the next generate() decode. Zero-initialized and
    // re-invalidated at the start of every addMessage/submitToolResults so a
    // turn that fails (or never runs) leaves no batch behind — generate()
    // refuses an empty batch rather than feeding stale or uninitialized
    // pointers into llama_decode, which fails GGML_ASSERT(token || embd)
    // and aborts the process.
    llama_batch batch = {};
    std::string response;
    common_chat_parser_params parser_params;
    bool parser_initialized = false;
    SamplerParams sampler_params;
    bool budget_sampler_added = false;
    // Vision: image bytes staged by setImageData() for the next user turn,
    // and a one-shot flag so generate() skips the initial decode already
    // performed by mtmd_helper_eval_chunks during the vision addMessage path.
    // setImageData and addMessage are separate AIDL transactions and may land
    // on different binder threads, so the staged bytes are guarded by
    // [image_mutex] (addMessage swaps them out under the lock).
    std::vector<unsigned char> pending_image_data;
    std::mutex image_mutex;
    bool skip_first_decode = false;
    // Set by requestAbort() (cancel path), read by the llama abort callback
    // during decode so Stop can interrupt the prompt-eval phase, not just the
    // between-token loop. Cleared at the start of each addMessage/submitToolResults.
    std::atomic<bool> abort_requested{false};
    // Tool-calling sampler (grammar + reasoning budget + sampling), built from
    // the rendered template when tools are enabled. Null for normal chat, where
    // [smpl] is used instead. See recreateToolSampler.
    common_sampler * gsmpl = nullptr;

    // Tool calling state
    std::vector<common_chat_tool> tools;
    bool tools_enabled = false;
    std::vector<common_chat_tool_call> pending_tool_calls;
    int tool_call_counter = 0;

    // Persistent preamble cache. Set by setPreambleCachePath(). Consulted
    // exactly once per session (the first time addMessage runs with
    // prev_len == 0). preamble_attempted prevents retrying after a
    // graceful skip — keeps subsequent addMessage calls fast and
    // predictable.
    std::string preamble_cache_path;
    std::string preamble_cache_fingerprint;
    bool preamble_attempted = false;
};

// Embeddings-enabled context over a loaded model (mean pooling, L2-normalized
// output). Independent of LlamaGenerationSession: no sampler, no chat template,
// no KV-cache reuse — each embed() clears the memory and feeds one sequence.
class LlamaEmbeddingSession {
public:
    LlamaEmbeddingSession() = default;
    ~LlamaEmbeddingSession();

    // Create the context. n_ctx = n_batch = n_ubatch = min(nCtx, n_ctx_train):
    // pooled sequence embeddings require the whole input in a single ubatch.
    bool init(llama_model *model, int nCtx);

    // Output dimension (llama_model_n_embd_out), 0 before init.
    int embeddingDim() const;

    // Embed one text into [out] (embeddingDim() floats, L2-normalized).
    // Inputs longer than the batch size are truncated. Returns 0 on success.
    int embed(const std::string &text, float *out);

private:
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    const struct llama_vocab *vocab = nullptr;
};

class LlamaModel {
public:
    LlamaModel() = default;
    ~LlamaModel() = default;

    LlamaGenerationSession* createGenerationSession(const SamplerParams &params);

    LlamaEmbeddingSession* createEmbeddingSession(int nCtx);
    int getContextTrainSize();
    // chatTemplateOverride: when non-empty, replaces the GGUF's embedded
    // chat template. Used for models whose published template is defective
    // (e.g. SmolLM3 never renders message.tool_calls, so llama.cpp cannot
    // derive a tool-call grammar and tool calls leak to the UI as raw text).
    void loadModel(const std::string &modelPath,
                   int32_t n_gpu_layers,
                   llama_progress_callback progress_callback,
                   void* progress_callback_user_data,
                   bool disableRepack = false,
                   const std::string &chatTemplateOverride = "");

    void loadMmprojModel(const std::string &mmprojPath);

    uint64_t getModelSize();

    std::string getModelReport();

    bool supportsThinking();

    bool supportsVision();

    bool supportsToolCalling();

    bool isLoaded() const { return model != nullptr; }

    void unloadModel();

private:
    llama_model *model = nullptr;
    common_chat_templates_ptr chat_tmpls;
    mtmd_context *mtmd_ctx = nullptr;
    // Path backing mtmd_ctx, so re-attaching the same projector is a no-op.
    std::string mmproj_path;
};

#endif //LMPLAYGROUND_LLAMACPP_H
