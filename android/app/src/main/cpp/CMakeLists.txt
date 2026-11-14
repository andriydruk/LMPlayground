# For more information about using CMake with Android Studio, read the
# documentation: https://d.android.com/studio/projects/add-native-code.html.
# For more examples on how to use CMake, see https://github.com/android/ndk-samples.

# Sets the minimum CMake version required for this project.
cmake_minimum_required(VERSION 3.31.6)

# Declares the project name first
project("llamacpp")

# Per-ABI performance flags (must be set before adding the core).
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

# The shared engine core: llama.cpp (andriydruk/llama.cpp-android fork, with
# "fd:N" file-descriptor paths baked into ggml_fopen / llama-mmap), mtmd,
# parakeet, and our engine sources. See core/CMakeLists.txt.
# The build uses BUILD_SHARED_LIBS=ON + GGML_BACKEND_DL (set in build.gradle.kts):
# ggml is a single shared libggml-base.so, and CPU/Vulkan backends are loaded at
# runtime via ggml_backend_load_all_from_path. libmtmd.so links the same shared
# ggml/llama, so it shares the one backend registry (no separate-registry problem).
add_subdirectory(${CMAKE_CURRENT_LIST_DIR}/../../../../../core ${CMAKE_BINARY_DIR}/core)

add_library(${CMAKE_PROJECT_NAME} SHARED
        native-lib.cpp
        AsrJni.cpp)

target_link_libraries(${CMAKE_PROJECT_NAME}
        llamacore
        android
        log)
