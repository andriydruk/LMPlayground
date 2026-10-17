// Voice dictation via parakeet.cpp (NVIDIA Parakeet TDT on ggml): JNI bindings.
//
// The engine is deliberately independent of the chat model: a context is loaded
// on demand, holds one Parakeet GGUF, and transcribes 16 kHz mono f32 PCM that
// the app records itself. Callers must serialize transcribe() calls per context
// (parakeet's graph state is not re-entrant); LlamaService does that with a
// single-threaded executor. Backend setup and output cleanup are shared with the
// iOS app in core/llamacore/AsrEngine.

#include <jni.h>

#include <cerrno>
#include <fcntl.h>
#include <cstdlib>
#include <cstring>
#include <string>
#include <unistd.h>
#include <vector>

#if defined(__ANDROID__)
#include <sys/system_properties.h>
#endif

#define LMP_LOG_TAG "AsrEngine"
#include "lmp_log.h"

#include "parakeet_capi.h"
#include "AsrEngine.h"

// Thread count override: `setprop debug.lmp.asr_threads N` (same convention as
// debug.lmp.mtmd_backend). The host build reads $LMP_ASR_THREADS instead, in
// asrConfigureBackend itself.
static int asrThreadsProperty() {
#if defined(__ANDROID__)
    char prop[PROP_VALUE_MAX] = {0};
    if (__system_property_get("debug.lmp.asr_threads", prop) > 0) return atoi(prop);
#endif
    return 0;
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
            LOGe("read failed: %s", strerror(errno));
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
    asrConfigureBackend(asrThreadsProperty());

    const char *path = env->GetStringUTFChars(modelPath, nullptr);
    LOGi("loading ASR model: %s", path);
    parakeet_ctx *ctx = parakeet_capi_load(path);
    env->ReleaseStringUTFChars(modelPath, path);

    if (ctx == nullptr) {
        LOGe("ASR model load failed");
        return 0;
    }
    LOGi("ASR model loaded");
    return reinterpret_cast<jlong>(ctx);
}

// Shared tail of both transcribe entry points: run the decoder over samples
// already in memory and hand the transcript back as a Java string.
static jstring transcribeSamples(JNIEnv *env, parakeet_ctx *ctx,
                                 const std::vector<float> &samples, jstring targetLang) {
    if (samples.empty()) {
        LOGw("no PCM samples to transcribe");
        return nullptr;
    }

    const char *lang = targetLang != nullptr ? env->GetStringUTFChars(targetLang, nullptr) : nullptr;
    // decoder 0 = the model's own head (TDT for the tdt-* checkpoints).
    char *text = parakeet_capi_transcribe_pcm_lang(ctx, samples.data(),
                                                   static_cast<int>(samples.size()),
                                                   16000, 0, lang);
    if (lang != nullptr) env->ReleaseStringUTFChars(targetLang, lang);

    if (text == nullptr) {
        LOGe("transcription failed: %s", parakeet_capi_last_error(ctx));
        return nullptr;
    }

    jstring result = env->NewStringUTF(text);
    parakeet_capi_free_string(text);
    return result;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_druk_llamacpp_jni_NativeAsr_transcribe(JNIEnv *env, jobject thiz, jlong handle,
                                                jint pcmFd, jstring targetLang) {
    auto *ctx = reinterpret_cast<parakeet_ctx *>(handle);
    if (ctx == nullptr) return nullptr;

    std::vector<float> samples;
    if (!readPcmF32(static_cast<int>(pcmFd), samples)) return nullptr;
    return transcribeSamples(env, ctx, samples, targetLang);
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_druk_llamacpp_jni_NativeAsr_transcribePath(JNIEnv *env, jobject thiz, jlong handle,
                                                    jstring pcmPath, jstring targetLang) {
    auto *ctx = reinterpret_cast<parakeet_ctx *>(handle);
    if (ctx == nullptr) return nullptr;

    const char *path = env->GetStringUTFChars(pcmPath, nullptr);
    int fd = open(path, O_RDONLY);
    if (fd < 0) LOGe("cannot open %s: %s", path, strerror(errno));
    env->ReleaseStringUTFChars(pcmPath, path);
    if (fd < 0) return nullptr;

    std::vector<float> samples;
    const bool ok = readPcmF32(fd, samples);
    close(fd);
    if (!ok) return nullptr;
    return transcribeSamples(env, ctx, samples, targetLang);
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_druk_llamacpp_jni_NativeAsr_transcribeSamples(JNIEnv *env, jobject thiz, jlong handle,
                                                       jfloatArray pcm, jint nSamples,
                                                       jstring targetLang) {
    auto *ctx = reinterpret_cast<parakeet_ctx *>(handle);
    if (ctx == nullptr || pcm == nullptr || nSamples <= 0) return nullptr;

    // Live dictation transcribes a few seconds at a time, so the audio comes
    // straight across as an array rather than through a file — a slice is a
    // couple of hundred KB, well under the binder cap.
    jfloat *samples = env->GetFloatArrayElements(pcm, nullptr);
    if (samples == nullptr) return nullptr;

    const char *lang = targetLang != nullptr ? env->GetStringUTFChars(targetLang, nullptr) : nullptr;
    char *text = parakeet_capi_transcribe_pcm_lang(ctx, samples, nSamples, 16000, 0, lang);
    if (lang != nullptr) env->ReleaseStringUTFChars(targetLang, lang);
    env->ReleaseFloatArrayElements(pcm, samples, JNI_ABORT);  // read-only

    if (text == nullptr) {
        LOGe("chunk transcription failed: %s", parakeet_capi_last_error(ctx));
        return nullptr;
    }
    jstring result = env->NewStringUTF(text);
    parakeet_capi_free_string(text);
    return result;
}

// ---------------------------------------------------------------------------
// Streaming. A stream is begun from a loaded context and fed PCM as it is
// recorded; each feed returns the text finalized *since the last feed*, so the
// caller accumulates rather than replaces. Only cache-aware streaming
// checkpoints support this — streamBegin returns 0 for an offline model.
// ---------------------------------------------------------------------------

extern "C"
JNIEXPORT jlong JNICALL
Java_com_druk_llamacpp_jni_NativeAsr_streamBegin(JNIEnv *env, jobject thiz, jlong handle,
                                                 jstring targetLang) {
    auto *ctx = reinterpret_cast<parakeet_ctx *>(handle);
    if (ctx == nullptr) return 0;

    const char *lang = targetLang != nullptr ? env->GetStringUTFChars(targetLang, nullptr) : nullptr;
    parakeet_stream *stream = parakeet_capi_stream_begin_lang(ctx, lang);
    if (lang != nullptr) env->ReleaseStringUTFChars(targetLang, lang);

    if (stream == nullptr) {
        LOGe("stream begin failed (not a streaming model?): %s", parakeet_capi_last_error(ctx));
        return 0;
    }
    LOGi("dictation stream opened");
    return reinterpret_cast<jlong>(stream);
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_druk_llamacpp_jni_NativeAsr_streamFeed(JNIEnv *env, jobject thiz, jlong streamHandle,
                                                jfloatArray pcm, jint nSamples) {
    auto *stream = reinterpret_cast<parakeet_stream *>(streamHandle);
    if (stream == nullptr || pcm == nullptr) return nullptr;

    jfloat *samples = env->GetFloatArrayElements(pcm, nullptr);
    if (samples == nullptr) return nullptr;
    // Events are ignored: end-of-utterance is a voice-agent turn-taking signal,
    // and dictation ends when the user lets go of the button.
    char *text = parakeet_capi_stream_feed(stream, samples, nSamples, nullptr);
    env->ReleaseFloatArrayElements(pcm, samples, JNI_ABORT);  // read-only

    if (text == nullptr) return nullptr;
    jstring result = env->NewStringUTF(asrStripLocaleTags(text).c_str());
    parakeet_capi_free_string(text);
    return result;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_druk_llamacpp_jni_NativeAsr_streamFinalize(JNIEnv *env, jobject thiz, jlong streamHandle) {
    auto *stream = reinterpret_cast<parakeet_stream *>(streamHandle);
    if (stream == nullptr) return nullptr;

    char *text = parakeet_capi_stream_finalize(stream);
    if (text == nullptr) return nullptr;
    jstring result = env->NewStringUTF(asrStripLocaleTags(text).c_str());
    parakeet_capi_free_string(text);
    return result;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_druk_llamacpp_jni_NativeAsr_streamFree(JNIEnv *env, jobject thiz, jlong streamHandle) {
    auto *stream = reinterpret_cast<parakeet_stream *>(streamHandle);
    if (stream == nullptr) return;
    parakeet_capi_stream_free(stream);
    LOGi("dictation stream closed");
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
    LOGi("ASR model unloaded");
}
