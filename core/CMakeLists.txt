#
# Shared engine core: llama.cpp + mtmd + parakeet, plus our engine sources.
#
# Every native consumer adds this directory and links `llamacore`:
#   android/app/src/main/cpp         the Android JNI library
#   android/llamacpp/native          the host JVM library (:model-harness)
#
# Consumers set their llama.cpp / ggml options (BUILD_SHARED_LIBS,
# GGML_BACKEND_DL, GGML_VULKAN, KleidiAI, ...) before add_subdirectory().
#
# `llamacore` is an INTERFACE library: its sources compile inside the
# consumer's own target, with that target's flags, exactly as if they were
# listed there. Nothing about a consumer's binary changes by going through it.
#

cmake_minimum_required(VERSION 3.31.6)

set(LMP_CORE_DIR  "${CMAKE_CURRENT_LIST_DIR}")
set(LMP_LLAMA_DIR "${LMP_CORE_DIR}/llama.cpp")

if(NOT EXISTS "${LMP_LLAMA_DIR}/CMakeLists.txt")
    message(FATAL_ERROR
        "llama.cpp submodule missing at ${LMP_LLAMA_DIR}. "
        "Run: git submodule update --init core/llama.cpp")
endif()

# ggml-vulkan.cpp includes <spirv/unified1/spirv.hpp>, but the upstream
# ggml-vulkan target wires SPIRV-Headers only to the host shader generator
# (it assumes the Vulkan SDK bundles the SPIR-V headers in the Vulkan include
# dir). When SPIRV-Headers come from a separate prefix (e.g. Homebrew), inject
# the include dir globally before adding llama.cpp so ggml-vulkan can compile.
if(DEFINED SPIRV_HEADERS_INCLUDE_DIR)
    include_directories(${SPIRV_HEADERS_INCLUDE_DIR})
endif()

# mtmd's CMakeLists needs LLAMA_INSTALL_VERSION.
if(NOT DEFINED LLAMA_INSTALL_VERSION)
    set(LLAMA_INSTALL_VERSION "0.0.0")
endif()

# The llama.cpp submodule points at the andriydruk/llama.cpp-android fork,
# which bakes "fd:N" file-descriptor handling into ggml_fopen / llama-mmap.
add_subdirectory(${LMP_LLAMA_DIR} ${CMAKE_CURRENT_BINARY_DIR}/llama.cpp)

# Multimodal (vision) support. llama.cpp adds tools/mtmd itself only when
# LLAMA_BUILD_MTMD=ON; the Android build leaves it at its OFF default.
if(NOT TARGET mtmd)
    add_subdirectory(${LMP_LLAMA_DIR}/tools/mtmd ${CMAKE_CURRENT_BINARY_DIR}/mtmd)
endif()

# Parakeet ASR for voice dictation, vendored into the fork rather than carried
# as a second submodule so it links the same in-tree ggml: one backend
# registry, one set of GGML_BACKEND_DL CPU variants, and the "fd:N" handling
# in ggml_fopen covers Parakeet GGUFs loaded from SAF storage. llama.cpp never
# adds it itself.
add_subdirectory(${LMP_LLAMA_DIR}/tools/parakeet ${CMAKE_CURRENT_BINARY_DIR}/parakeet)

add_library(llamacore INTERFACE)

target_sources(llamacore INTERFACE
        ${LMP_CORE_DIR}/llamacore/LlamaModel.cpp
        ${LMP_CORE_DIR}/llamacore/LlamaGenerationSession.cpp
        ${LMP_CORE_DIR}/llamacore/LlamaEmbeddingSession.cpp
        ${LMP_CORE_DIR}/llamacore/EngineRuntime.cpp
        ${LMP_CORE_DIR}/llamacore/VisionDevice.cpp
        ${LMP_CORE_DIR}/llamacore/ModelProbe.cpp
        ${LMP_CORE_DIR}/llamacore/AsrEngine.cpp)

target_include_directories(llamacore INTERFACE
        ${LMP_CORE_DIR}/llamacore
        # mtmd public headers (mtmd.h, mtmd-helper.h) for the vision integration.
        ${LMP_LLAMA_DIR}/tools/mtmd)

target_link_libraries(llamacore INTERFACE
        llama-common
        llama
        mtmd
        parakeet)
