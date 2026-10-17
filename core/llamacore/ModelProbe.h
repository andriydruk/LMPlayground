//
// Reads a GGUF header without loading tensors. Cheap enough to run over a
// folder of sideloaded models.
//

#ifndef LMPLAYGROUND_MODEL_PROBE_H
#define LMPLAYGROUND_MODEL_PROBE_H

#include <cstdint>
#include <string>

struct GgufMetadata {
    std::string name;          // general.name, empty if absent
    bool hasChatTemplate = false;
    std::string architecture;  // general.architecture ("qwen35", "clip", ...)

    // Attention shape, for estimating the KV cache before loading. Zero when
    // the file doesn't declare it. Per-layer arrays report their maximum.
    uint32_t contextLength = 0;
    uint32_t blockCount = 0;
    uint32_t embeddingLength = 0;
    uint32_t headCount = 0;
    uint32_t headCountKv = 0;
    uint32_t keyLength = 0;    // per head; defaults to embeddingLength / headCount
    uint32_t valueLength = 0;

    // A multimodal projector (mmproj) rather than a language model.
    bool isProjector() const { return architecture == "clip"; }

    // Upper bound on f16 KV-cache bytes per context token.
    uint64_t kvBytesPerToken() const;
};

// Returns false if [path] is not a readable GGUF file.
bool probeGgufMetadata(const std::string &path, GgufMetadata &out);

#endif // LMPLAYGROUND_MODEL_PROBE_H
