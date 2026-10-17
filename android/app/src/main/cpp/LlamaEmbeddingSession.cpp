#if defined(__ANDROID__)
#include <jni.h>
#endif
#include <string>
#include <vector>

#include "LlamaCpp.h"

#include "common.h"
#include "llama.h"

#include <algorithm>
#include <unistd.h>

#define LMP_LOG_TAG "LlamaEmbeddingSession"
#include "lmp_log.h"

LlamaEmbeddingSession::~LlamaEmbeddingSession() {
    if (ctx != nullptr) {
        llama_free(ctx);
        ctx = nullptr;
    }
}

bool LlamaEmbeddingSession::init(llama_model *newModel, int nCtx) {
    model = newModel;
    vocab = llama_model_get_vocab(model);

    if (llama_model_has_encoder(model) && llama_model_has_decoder(model)) {
        LOGe("Encoder-decoder models are not supported for embeddings");
        return false;
    }

    int n_threads = std::max(1, std::min(kMaxGenerationThreads, (int) sysconf(_SC_NPROCESSORS_ONLN) - 2));

    int n_ctx_train = llama_model_n_ctx_train(model);
    int n_ctx = std::min(nCtx, n_ctx_train);
    LOGi("Embedding context: train %d, using %d, dim %d",
         n_ctx_train, n_ctx, llama_model_n_embd_out(model));

    llama_context_params ctx_params = llama_context_default_params();
    // Pooled sequence embeddings require the whole input in one ubatch, so
    // context, batch and ubatch share the same size (llama.cpp embedding
    // example does the same).
    ctx_params.n_ctx = n_ctx;
    ctx_params.n_batch = n_ctx;
    ctx_params.n_ubatch = n_ctx;
    ctx_params.n_threads = n_threads;
    ctx_params.n_threads_batch = n_threads;
    ctx_params.embeddings = true;
    ctx_params.pooling_type = LLAMA_POOLING_TYPE_MEAN;

    ctx = llama_init_from_model(model, ctx_params);
    if (ctx == nullptr) {
        LOGe("Failed to create embedding llama_context");
        return false;
    }
    return true;
}

int LlamaEmbeddingSession::embeddingDim() const {
    if (model == nullptr) {
        return 0;
    }
    return llama_model_n_embd_out(model);
}

int LlamaEmbeddingSession::embed(const std::string &text, float *out) {
    if (ctx == nullptr || text.empty()) {
        return 1;
    }

    std::vector<llama_token> tokens = common_tokenize(vocab, text, true, true);
    if (tokens.empty()) {
        return 1;
    }
    const auto n_batch = (size_t) llama_n_batch(ctx);
    if (tokens.size() > n_batch) {
        // A clipped chunk still embeds meaningfully; a failed decode doesn't.
        tokens.resize(n_batch);
    }

    // Previous sequence state is irrelevant for embeddings.
    llama_memory_clear(llama_get_memory(ctx), true);

    llama_batch batch = llama_batch_init((int32_t) tokens.size(), 0, 1);
    for (size_t i = 0; i < tokens.size(); i++) {
        batch.token[i]     = tokens[i];
        batch.pos[i]       = (llama_pos) i;
        batch.n_seq_id[i]  = 1;
        batch.seq_id[i][0] = 0;
        // logits=true on every token: mean pooling needs all outputs.
        batch.logits[i]    = true;
    }
    batch.n_tokens = (int32_t) tokens.size();

    int rc;
    if (llama_model_has_encoder(model) && !llama_model_has_decoder(model)) {
        rc = llama_encode(ctx, batch);
    } else {
        rc = llama_decode(ctx, batch);
    }
    if (rc < 0) {
        LOGe("Embedding decode failed: %d", rc);
        llama_batch_free(batch);
        return 1;
    }

    const float *embd = llama_get_embeddings_seq(ctx, 0);
    if (embd == nullptr) {
        LOGe("llama_get_embeddings_seq returned null");
        llama_batch_free(batch);
        return 1;
    }
    common_embd_normalize(embd, out, embeddingDim(), 2);
    llama_batch_free(batch);
    return 0;
}
