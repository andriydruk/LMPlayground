//
// Reads a GGUF header without loading tensors. Cheap enough to run over a
// folder of sideloaded models.
//

#ifndef LMPLAYGROUND_MODEL_PROBE_H
#define LMPLAYGROUND_MODEL_PROBE_H

#include <string>

struct GgufMetadata {
    std::string name;          // general.name, empty if absent
    bool hasChatTemplate = false;
};

// Returns false if [path] is not a readable GGUF file.
bool probeGgufMetadata(const std::string &path, GgufMetadata &out);

#endif // LMPLAYGROUND_MODEL_PROBE_H
