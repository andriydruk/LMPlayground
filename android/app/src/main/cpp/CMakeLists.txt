# For more information about using CMake with Android Studio, read the
# documentation: https://d.android.com/studio/projects/add-native-code.html.
# For more examples on how to use CMake, see https://github.com/android/ndk-samples.

# Sets the minimum CMake version required for this project.
cmake_minimum_required(VERSION 3.31.6)

# Declares the project name first
project("llamacpp")

# Per-ABI performance flags (must be set before add_subdirectory).
# OpenMP is OFF on both ABIs: it's the upstream-recommended Android path
# (see llama.cpp docs/android.md) and ggml's own pthread threadpool replaces
# it with comparable performance. Disabling OpenMP also produces cleaner
# native crash stacks (no libomp.so frames) and shrinks the APK by ~500 KB
# (no libomp.so packaged).
if(DEFINED ANDROID_ABI)
    if(ANDROID_ABI STREQUAL "arm64-v8a")
        set(GGML_CPU_KLEIDIAI ON)
        set(GGML_OPENMP OFF)
    elseif(ANDROID_ABI STREQUAL "x86_64")
        set(GGML_CPU_KLEIDIAI OFF)
        set(GGML_OPENMP OFF)
    endif()
endif()

# ggml-vulkan.cpp includes <spirv/unified1/spirv.hpp>, but the upstream
# ggml-vulkan target wires SPIRV-Headers only to the host shader generator
# (it assumes the Vulkan SDK bundles the SPIR-V headers in the Vulkan include
# dir). When SPIRV-Headers come from a separate prefix (e.g. Homebrew), inject
# the include dir globally before adding llama.cpp so ggml-vulkan can compile.
if(DEFINED SPIRV_HEADERS_INCLUDE_DIR)
    include_directories(${SPIRV_HEADERS_INCLUDE_DIR})
endif()

# Include llama.cpp subdirectory.
# The submodule points at the andriydruk/llama.cpp-android fork (android-b9496),
# which bakes "fd:N" file-descriptor handling into ggml_fopen / llama-mmap, so the
# old ggml_fopen_override.c static-lib + --whole-archive hack is no longer needed.
# The build uses BUILD_SHARED_LIBS=ON + GGML_BACKEND_DL (set in build.gradle.kts):
# ggml is a single shared libggml-base.so, and CPU/Vulkan backends are loaded at
# runtime via ggml_backend_load_all_from_path. libmtmd.so links the same shared
# ggml/llama, so it shares the one backend registry (no separate-registry problem).
add_subdirectory(llama.cpp)

# Include mtmd (multimodal) library for vision support.
# Set LLAMA_INSTALL_VERSION if not already set (needed by mtmd's CMakeLists).
if(NOT DEFINED LLAMA_INSTALL_VERSION)
    set(LLAMA_INSTALL_VERSION "0.0.0")
endif()
add_subdirectory(llama.cpp/tools/mtmd)

# Include parakeet (NVIDIA Parakeet ASR) for voice dictation. It is vendored into
# the fork rather than carried as a second submodule so it links the same in-tree
# ggml: one backend registry, one set of GGML_BACKEND_DL CPU variants, and the
# "fd:N" handling in ggml_fopen covers Parakeet GGUFs loaded from SAF storage.
add_subdirectory(llama.cpp/tools/parakeet)

add_library(${CMAKE_PROJECT_NAME} SHARED
        native-lib.cpp
        LlamaModel.cpp
        LlamaGenerationSession.cpp
        LlamaEmbeddingSession.cpp
        AsrEngine.cpp)

# mtmd public headers (mtmd.h, mtmd-helper.h) for the vision integration.
target_include_directories(${CMAKE_PROJECT_NAME} PRIVATE llama.cpp/tools/mtmd)

target_link_libraries(${CMAKE_PROJECT_NAME}
        llama-common
        llama
        mtmd
        parakeet
        android
        log)
