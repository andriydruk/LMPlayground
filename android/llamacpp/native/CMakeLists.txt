#
# Host (macOS / desktop JVM) build of libllamacpp.
#
# Compiles the SAME sources as the Android build (the shared engine in core/
# plus the JNI shim in android/app/src/main/cpp), but against the host JDK's
# jni.h instead of the NDK, so :model-harness can drive the real engine on a
# Mac without a device or emulator.
#
# This file is intentionally separate from app/src/main/cpp/CMakeLists.txt:
# the Android configure must never change.
#

cmake_minimum_required(VERSION 3.31.6)
project("llamacpp-host" CXX C)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(LMP_CPP_DIR  "${CMAKE_CURRENT_LIST_DIR}/../../app/src/main/cpp")
set(LMP_CORE_DIR "${CMAKE_CURRENT_LIST_DIR}/../../../core")

# Mirror the Android flags that matter (app/build.gradle.kts), minus the
# Vulkan / KleidiAI / APK-size ones.
set(LLAMA_BUILD_COMMON   ON  CACHE BOOL "" FORCE)
set(LLAMA_BUILD_MTMD     ON  CACHE BOOL "" FORCE)  # vision (CLIP) support
set(LLAMA_BUILD_TOOLS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP      OFF CACHE BOOL "" FORCE)
# Without these the vendored cpp-httplib fails on 'openssl/err.h'.
set(LLAMA_CURL           OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL        OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE       OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS    ON  CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL      ON  CACHE BOOL "" FORCE)

# llama.cpp + mtmd (added by llama.cpp itself, LLAMA_BUILD_MTMD=ON above) +
# parakeet + the llamacore engine sources. See core/CMakeLists.txt.
add_subdirectory(${LMP_CORE_DIR} ${CMAKE_BINARY_DIR}/core)

# llama.cpp puts its own shared libs (libggml-*.so backends, libllama.dylib,
# libmtmd.dylib) in <build>/bin. Put libllamacpp.dylib there too so that one
# directory works as BOTH java.library.path and the nativeLibDir that
# ggml_backend_load_all_from_path scans.
set(CMAKE_LIBRARY_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/bin")
set(CMAKE_RUNTIME_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/bin")

find_package(JNI REQUIRED)

# Target name is load-bearing: Kotlin calls System.loadLibrary("llamacpp").
add_library(llamacpp SHARED
        ${LMP_CPP_DIR}/native-lib.cpp
        ${LMP_CPP_DIR}/AsrJni.cpp)

target_include_directories(llamacpp PRIVATE ${JNI_INCLUDE_DIRS})

# No `android`, no `log` — those are NDK-only.
target_link_libraries(llamacpp llamacore)

# All backend dylibs land next to libllamacpp.dylib, so the harness can pass
# that one directory as both java.library.path and nativeLibDir (which
# ggml_backend_load_all_from_path scans).
set_target_properties(llamacpp PROPERTIES
        BUILD_WITH_INSTALL_RPATH FALSE
        BUILD_RPATH "@loader_path")
