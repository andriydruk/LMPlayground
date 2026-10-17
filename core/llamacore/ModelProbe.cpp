#include "ModelProbe.h"

#include "gguf.h"

#include <algorithm>

// An unsigned value of any integer width, or the maximum of an integer array
// (some architectures declare head counts per layer). 0 if absent.
static uint32_t readCount(const gguf_context *ctx, const std::string &key) {
    int64_t id = gguf_find_key(ctx, key.c_str());
    if (id < 0) return 0;
    auto scalar = [](gguf_type type, const void *data, size_t i) -> uint64_t {
        switch (type) {
            case GGUF_TYPE_UINT8:  return static_cast<const uint8_t *>(data)[i];
            case GGUF_TYPE_INT8:   return std::max<int8_t>(0, static_cast<const int8_t *>(data)[i]);
            case GGUF_TYPE_UINT16: return static_cast<const uint16_t *>(data)[i];
            case GGUF_TYPE_INT16:  return std::max<int16_t>(0, static_cast<const int16_t *>(data)[i]);
            case GGUF_TYPE_UINT32: return static_cast<const uint32_t *>(data)[i];
            case GGUF_TYPE_INT32:  return std::max<int32_t>(0, static_cast<const int32_t *>(data)[i]);
            case GGUF_TYPE_UINT64: return static_cast<const uint64_t *>(data)[i];
            case GGUF_TYPE_INT64:  return std::max<int64_t>(0, static_cast<const int64_t *>(data)[i]);
            default: return 0;
        }
    };
    gguf_type type = gguf_get_kv_type(ctx, id);
    if (type == GGUF_TYPE_ARRAY) {
        gguf_type elem = gguf_get_arr_type(ctx, id);
        if (elem == GGUF_TYPE_STRING) return 0;
        const void *data = gguf_get_arr_data(ctx, id);
        uint64_t best = 0;
        for (size_t i = 0, n = gguf_get_arr_n(ctx, id); i < n; i++) {
            best = std::max(best, scalar(elem, data, i));
        }
        return static_cast<uint32_t>(best);
    }
    return static_cast<uint32_t>(scalar(type, gguf_get_val_data(ctx, id), 0));
}

uint64_t GgufMetadata::kvBytesPerToken() const {
    if (blockCount == 0 || headCount == 0) return 0;
    const uint64_t kvHeads = headCountKv > 0 ? headCountKv : headCount;
    const uint64_t k = keyLength > 0 ? keyLength : embeddingLength / headCount;
    const uint64_t v = valueLength > 0 ? valueLength : k;
    // K and V for every layer and KV head, 2 bytes each (f16 cache).
    return uint64_t(blockCount) * kvHeads * (k + v) * 2;
}

bool probeGgufMetadata(const std::string &path, GgufMetadata &out) {
    struct gguf_init_params params = { /*.no_alloc =*/ true, /*.ctx =*/ nullptr };
    struct gguf_context * gguf_ctx = gguf_init_from_file(path.c_str(), params);
    if (gguf_ctx == nullptr) {
        return false;
    }

    int64_t name_key = gguf_find_key(gguf_ctx, "general.name");
    out.name = name_key >= 0 ? gguf_get_val_str(gguf_ctx, name_key) : "";

    out.hasChatTemplate = gguf_find_key(gguf_ctx, "tokenizer.chat_template") >= 0;

    int64_t arch_key = gguf_find_key(gguf_ctx, "general.architecture");
    out.architecture = arch_key >= 0 && gguf_get_kv_type(gguf_ctx, arch_key) == GGUF_TYPE_STRING
        ? gguf_get_val_str(gguf_ctx, arch_key) : "";

    const std::string a = out.architecture + ".";
    out.contextLength   = readCount(gguf_ctx, a + "context_length");
    out.blockCount      = readCount(gguf_ctx, a + "block_count");
    out.embeddingLength = readCount(gguf_ctx, a + "embedding_length");
    out.headCount       = readCount(gguf_ctx, a + "attention.head_count");
    out.headCountKv     = readCount(gguf_ctx, a + "attention.head_count_kv");
    out.keyLength       = readCount(gguf_ctx, a + "attention.key_length");
    out.valueLength     = readCount(gguf_ctx, a + "attention.value_length");

    gguf_free(gguf_ctx);
    return true;
}
