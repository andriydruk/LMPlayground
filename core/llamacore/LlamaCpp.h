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

#include "VisionDevice.h"

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
// Turn snapshots kept for rewindToTurn: a KV position and a string each,
// so the cap is generous.
constexpr size_t kMaxTurnSnapshots = 64;
// KV checkpoints: copies of the state llama_memory_seq_rm can't truncate
// (recurrent / hybrid / SWA memories), taken at turn starts and right before
// each generation prompt. Bounded by count and bytes, lowest position evicted
// first — edits, regenerates and prefix reuse all target recent positions.
constexpr size_t kMaxKvCheckpoints = 12;
constexpr size_t kMaxKvCheckpointBytes = 96u * 1024u * 1024u;

// Vision device choice and the Vulkan CLIP crash sentinel (clipVisionDevice,
// clipSentinel*, clipVulkanFailed) are declared in VisionDevice.h.

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
              mtmd_context *mtmd_ctx, bool mtmd_on_vulkan, const SamplerParams &params);

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

    // Drop user turn [turnIndex] (0-based, counting every user turn of the
    // conversation, replayed ones included) and everything after it, so the
    // next addMessage continues from the end of turn [turnIndex - 1]. Used
    // by edit (re-send a changed message) and regenerate (re-send the same
    // one).
    //
    // The KV cache is rolled back to the newest snapshot taken at or before
    // that turn, so only what follows the snapshot is evaluated again.
    // [expectedText], when non-empty, must occur in the native copy of that
    // user turn; it guards against the caller's numbering having drifted
    // from ours (a turn the template rejected, a turn compaction dropped).
    //
    // Returns the number of tokens kept in the KV cache (0 = the next turn
    // re-evaluates the whole prompt), or -1 when the turn can't be found or
    // doesn't match — the session is then untouched and the caller should
    // rebuild it with replayHistory.
    int rewindToTurn(int turnIndex, const char *expectedText);

    // Number of user turns in the conversation, including ones dropped by
    // context compaction. The next addMessage becomes turn getTurnCount().
    int getTurnCount() const;

    // Reopen the last assistant turn (a reply cut short by Stop or
    // backgrounding, or the last turn of a replayed history) and keep
    // generating it. The prompt and the partial reply are matched against
    // the KV cache by tokens, so after a Stop only the token sampled before
    // it is fed. Then call generate() as usual: the reply it reports
    // includes the partial text. Returns 0 on success, 1 when there's no
    // reply to continue, the context is full, or the turn had an image whose
    // embeddings are no longer cached.
    int continueResponse(bool enableThinking);

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

    // State of the session right before a user turn was fed: the KV cache
    // holds exactly [kv_pos] positions, and when [prev_len] > 0 they
    // correspond to [prev_rendered_prompt] (the incremental-prefix state
    // addMessage matches against; 0 means "match by tokens"). Restoring one
    // and re-sending a message feeds the same tokens at the same positions
    // as the original turn.
    struct TurnSnapshot {
        int turn = 0;
        size_t messages_size = 0;   // messages.size() before the user turn
        int kv_pos = 0;
        std::string prev_rendered_prompt;
        int prev_len = 0;
        bool prev_had_thinking = false;
        bool prev_enable_thinking = false;
    };

    // State a stateful memory needs to resume from [kv_pos], read with
    // LLAMA_STATE_SEQ_FLAGS_PARTIAL_ONLY.
    struct KvCheckpoint {
        int kv_pos = 0;
        std::vector<uint8_t> state;
    };

    // Record a snapshot for the user turn just pushed onto [messages],
    // before its tokens are decoded. [kv_pos] is the cache occupancy now.
    void captureTurnSnapshot(int kv_pos);
    void clearTurnSnapshots();
    // Index in [messages] of user turn [turn], or -1 when it isn't there
    // (dropped by compaction, or past the end).
    int messageIndexOfTurn(int turn) const;
    int userTurnsInMessages(size_t end) const;

    // Checkpoint the current cache state (a no-op for memories that
    // llama_memory_seq_rm can truncate). False when it couldn't be kept.
    bool captureKvCheckpoint();
    // Cut the cache back to exactly [kv_pos] positions. False (cache
    // untouched) when that isn't possible.
    bool truncateKvTo(int kv_pos);
    // Cut the cache back to at most [kv_pos] positions — the longest
    // restorable prefix. Returns the positions kept, or -1 when nothing
    // could be kept (the cache is then untouched).
    int truncateKvAtMost(int kv_pos);
    // Drop snapshots and checkpoints past [kv_pos]: their cache is gone.
    void forgetKvPast(int kv_pos);
    // Text path of addMessage / submitToolResults when the string prefix
    // doesn't match: reuse the longest token prefix of [full_prompt] the
    // cache already holds. On success [tail] holds the tokens still to feed.
    bool reuseKvPrefix(const std::string &full_prompt, std::vector<llama_token> &tail);
    // Tokens of [prompt_tokens] to decode before checkpointing (the start of
    // the generation prompt), or -1.
    int generationPromptSplit(const common_chat_params &result, const std::string &full_prompt);
    // Append decoded tokens to [kv_tokens].
    void trackDecoded(const llama_token *tokens, int n);
    void resetKvTokens();

    std::vector<TurnSnapshot> turn_snapshots;
    std::vector<KvCheckpoint> kv_checkpoints;
    // Exactly the tokens in KV sequence 0, in position order, so a prompt
    // can be matched against the cache token by token. Invalid after a
    // vision turn (image embeddings have no token ids) until the next clear.
    std::vector<llama_token> kv_tokens;
    bool kv_tokens_valid = true;
    // Checkpoint after this many tokens of the pending prompt batch (-1 none).
    int checkpoint_after = -1;
    // User turns compaction has dropped from the front of [messages]; turn
    // numbers stay stable across compaction by adding this back.
    int dropped_user_turns = 0;
    // The memory has parts llama_memory_seq_rm can't truncate (recurrent,
    // hybrid, sliding-window attention), so rollback needs checkpoints.
    bool memory_needs_state_snapshots = false;

    const struct llama_vocab * vocab = nullptr;
    llama_context * ctx = nullptr;
    llama_sampler * smpl = nullptr;
    const struct common_chat_templates * chat_tmpls = nullptr;
    mtmd_context * mtmd_ctx = nullptr;
    // mtmd_ctx encodes on Vulkan.
    bool mtmd_on_vulkan = false;
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
    // A turn is being generated: set when its prompt is prepared, cleared when
    // finalizeResponse commits the reply (or a tool round hands control back).
    // Keeps the stop-between-tokens path from committing a reply twice.
    bool turn_open = false;
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
    bool mtmd_on_vulkan = false;

    // The projector's Vulkan device was lost by an encode in this process.
    bool mmprojLost() const { return mtmd_on_vulkan && clipVulkanFailed(); }
    // Free mtmd_ctx, or abandon it when mmprojLost(): sessions may still hold
    // the pointer, and freeing runs Vulkan cleanup on the lost device.
    void releaseMmproj();
};

#endif //LMPLAYGROUND_LLAMACPP_H
