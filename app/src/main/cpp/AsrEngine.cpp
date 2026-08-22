// Voice dictation via parakeet.cpp (NVIDIA Parakeet TDT on ggml).
//
// The engine is deliberately independent of the chat model: a context is loaded
// on demand, holds one Parakeet GGUF, and transcribes 16 kHz mono f32 PCM that
// the app records itself. Callers must serialize transcribe() calls per context
// (parakeet's graph state is not re-entrant); LlamaService does that with a
// single-threaded executor.

#include <jni.h>

#include <android/log.h>
#include <cerrno>
#include <cstdlib>
#include <cstring>
#include <string>
#include <unistd.h>
#include <vector>

#include "parakeet_capi.h"

#define ASR_TAG "AsrEngine"

// Parakeet's own default is tuned for desktop core counts. Phones have ~4 big
// cores worth using; more threads just contend with the little cores.
static constexpr int kAsrThreads = 4;

// Mobile GPU drivers have repeatedly mishandled encoder graphs in this app (the
// CLIP vision denylist exists for exactly that reason), and parakeet's conformer
// uses op patterns those drivers have never seen. parakeet auto-selects a
// GPU/IGPU device when one is registered, so pin it to CPU before the first load.
static void asrForceCpuBackend() {
    static bool done = false;
    if (done) return;
    setenv("PARAKEET_DEVICE", "cpu", 1);
    parakeet_capi_set_num_threads(kAsrThreads);
    done = true;
}

// Reads the whole fd as little-endian f32 samples. The file is written by
// AudioRecorder into the app cache, so a plain read loop is enough (no SAF).
static bool readPcmF32(int fd, std::vector<float> &out) {
    constexpr size_t kChunkSamples = 16384;
    std::vector<float> chunk(kChunkSamples);
    while (true) {
        ssize_t n = read(fd, chunk.data(), chunk.size() * sizeof(float));
        if (n < 0) {
            if (errno == EINTR) continue;
            __android_log_print(ANDROID_LOG_ERROR, ASR_TAG, "read failed: %s", strerror(errno));
            return false;
        }
        if (n == 0) break;
        // A truncated tail sample (partial write / interrupted recording) is
        // dropped rather than fed to the encoder as garbage.
        size_t samples = static_cast<size_t>(n) / sizeof(float);
        out.insert(out.end(), chunk.begin(), chunk.begin() + samples);
    }
    return true;
}

extern "C"
JNIEXPORT jlong JNICALL
Java_com_druk_llamacpp_jni_NativeAsr_loadModel(JNIEnv *env, jobject thiz, jstring modelPath) {
    asrForceCpuBackend();

    const char *path = env->GetStringUTFChars(modelPath, nullptr);
    __android_log_print(ANDROID_LOG_INFO, ASR_TAG, "loading ASR model: %s", path);
    parakeet_ctx *ctx = parakeet_capi_load(path);
    env->ReleaseStringUTFChars(modelPath, path);

    if (ctx == nullptr) {
        __android_log_print(ANDROID_LOG_ERROR, ASR_TAG, "ASR model load failed");
        return 0;
    }
    __android_log_print(ANDROID_LOG_INFO, ASR_TAG, "ASR model loaded");
    return reinterpret_cast<jlong>(ctx);
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_druk_llamacpp_jni_NativeAsr_transcribe(JNIEnv *env, jobject thiz, jlong handle,
                                                jint pcmFd, jstring targetLang) {
    auto *ctx = reinterpret_cast<parakeet_ctx *>(handle);
    if (ctx == nullptr) return nullptr;

    std::vector<float> samples;
    if (!readPcmF32(static_cast<int>(pcmFd), samples) || samples.empty()) {
        __android_log_print(ANDROID_LOG_WARN, ASR_TAG, "no PCM samples to transcribe");
        return nullptr;
    }

    const char *lang = targetLang != nullptr ? env->GetStringUTFChars(targetLang, nullptr) : nullptr;
    // decoder 0 = the model's own head (TDT for the tdt-* checkpoints).
    char *text = parakeet_capi_transcribe_pcm_lang(ctx, samples.data(),
                                                   static_cast<int>(samples.size()),
                                                   16000, 0, lang);
    if (lang != nullptr) env->ReleaseStringUTFChars(targetLang, lang);

    if (text == nullptr) {
        __android_log_print(ANDROID_LOG_ERROR, ASR_TAG, "transcription failed: %s",
                            parakeet_capi_last_error(ctx));
        return nullptr;
    }

    jstring result = env->NewStringUTF(text);
    parakeet_capi_free_string(text);
    return result;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_druk_llamacpp_jni_NativeAsr_lastError(JNIEnv *env, jobject thiz, jlong handle) {
    auto *ctx = reinterpret_cast<parakeet_ctx *>(handle);
    return env->NewStringUTF(ctx != nullptr ? parakeet_capi_last_error(ctx) : "");
}

extern "C"
JNIEXPORT void JNICALL
Java_com_druk_llamacpp_jni_NativeAsr_freeModel(JNIEnv *env, jobject thiz, jlong handle) {
    auto *ctx = reinterpret_cast<parakeet_ctx *>(handle);
    if (ctx == nullptr) return;
    parakeet_capi_free(ctx);
    __android_log_print(ANDROID_LOG_INFO, ASR_TAG, "ASR model unloaded");
}
