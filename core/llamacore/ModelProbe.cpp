#include "ModelProbe.h"

#include "gguf.h"

bool probeGgufMetadata(const std::string &path, GgufMetadata &out) {
    struct gguf_init_params params = { /*.no_alloc =*/ true, /*.ctx =*/ nullptr };
    struct gguf_context * gguf_ctx = gguf_init_from_file(path.c_str(), params);
    if (gguf_ctx == nullptr) {
        return false;
    }

    int64_t name_key = gguf_find_key(gguf_ctx, "general.name");
    out.name = name_key >= 0 ? gguf_get_val_str(gguf_ctx, name_key) : "";

    out.hasChatTemplate = gguf_find_key(gguf_ctx, "tokenizer.chat_template") >= 0;

    gguf_free(gguf_ctx);
    return true;
}
