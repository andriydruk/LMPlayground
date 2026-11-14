#include <jni.h>
#include <string>

#include "LlamaCpp.h"
#include "EngineRuntime.h"
#include "ModelProbe.h"
#include "Utf8.h"
#include "common.h"

#include "console.h"
#include "ggml.h"
#include "ggml-backend.h"
#include "gguf.h"
#include "llama.h"
#include "log.h"

#include <cassert>
#include <cinttypes>
#include <cmath>
#include <cstdio>
#include <cstring>
#if defined(__ANDROID__)
#include <sys/system_properties.h>
#endif
#include <ctime>
#include <fstream>
#include <iostream>
#include <sstream>
#include <string>
#include <vector>
#include <iostream>
#include <csignal>
#include <unistd.h>

#define LMP_LOG_TAG "llama-android.cpp"
#include "lmp_log.h"

#if defined(__ANDROID__)
class AndroidLogBuf : public std::streambuf {
protected:
    std::streamsize xsputn(const char* s, std::streamsize n) override {
        __android_log_print(ANDROID_LOG_INFO, "Llama", "%.*s", (int)n, s);
        return n;
    }

    int overflow(int c) override {
        if (c != EOF) {
            char c_as_char = static_cast<char>(c);
            __android_log_write(ANDROID_LOG_INFO, "Llama", &c_as_char);
        }
        return c;
    }
};
#endif

extern "C"
JNIEXPORT jobjectArray JNICALL
Java_com_druk_llamacpp_jni_NativeLlamaCpp_probeModelMetadata(JNIEnv *env, jobject thiz, jstring modelPath) {
    const char* path = env->GetStringUTFChars(modelPath, nullptr);
    GgufMetadata meta;
    const bool ok = probeGgufMetadata(path, meta);
    env->ReleaseStringUTFChars(modelPath, path);

    if (!ok) {
        return nullptr;
    }

    // Return String[] { name, hasChatTemplate }
    jclass stringClass = env->FindClass("java/lang/String");
    jobjectArray result = env->NewObjectArray(2, stringClass, nullptr);
    env->SetObjectArrayElement(result, 0, env->NewStringUTF(meta.name.c_str()));
    env->SetObjectArrayElement(result, 1, env->NewStringUTF(meta.hasChatTemplate ? "true" : "false"));

    return result;
}

extern "C" JNIEXPORT int
JNICALL
Java_com_druk_llamacpp_jni_NativeLlamaCpp_init(JNIEnv *env, jobject object, jstring nativeLibDir, jstring stateDir) {

#if defined(__ANDROID__)
    // Redirect std::cerr to logcat
    AndroidLogBuf androidLogBuf;
    std::cerr.rdbuf(&androidLogBuf);
#endif

    auto readString = [env](jstring s) {
        std::string out;
        if (s != nullptr) {
            const char *chars = env->GetStringUTFChars(s, nullptr);
            if (chars != nullptr) {
                out.assign(chars);
                env->ReleaseStringUTFChars(s, chars);
            }
        }
        return out;
    };

    EngineConfig config;
    config.stateDir = readString(stateDir);
    // With GGML_BACKEND_DL=ON the CPU and Vulkan backends live in separate
    // libggml-*.so files alongside libllamacpp.so.
    config.backendDir = readString(nativeLibDir);
#if defined(__ANDROID__)
    // Vulkan is used for CLIP only on allowlisted GPUs, and never after the
    // crash sentinel blocked it. Override with
    // `setprop debug.lmp.mtmd_backend <name>`.
    config.visionPolicy = VisionPolicy::VulkanAllowlist;
    char backend_override[PROP_VALUE_MAX] = {0};
    if (__system_property_get("debug.lmp.mtmd_backend", backend_override) > 0) {
        config.visionDeviceOverride = backend_override;
    }
#else
    // Host: the GPU here is Metal, which is exactly the CLIP backend we want,
    // so no per-driver allowlist is needed. LMP_MTMD_BACKEND=CPU to override.
    config.visionPolicy = VisionPolicy::AnyGpu;
    if (const char * backend_override = std::getenv("LMP_MTMD_BACKEND")) {
        config.visionDeviceOverride = backend_override;
    }
#endif
    engineInit(config);
    return 0;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_druk_llamacpp_jni_NativeLlamaCpp_systemInfo(JNIEnv *env, jobject object) {
    return env->NewStringUTF(llama_print_system_info());
}

extern "C" JNIEXPORT jobject
JNICALL
Java_com_druk_llamacpp_jni_NativeLlamaCpp_loadModel(JNIEnv *env,
                   jobject activity,
                   jstring modelPath,
                   jobject progressCallback,
                   jboolean disableRepack,
                   jstring chatTemplateOverride) {

    // Stack-allocated and passed as a raw pointer into the progress
    // callback: safe only because loadModel invokes the callback
    // synchronously on this thread — it must never outlive this frame.
    struct CallbackContext {
        JNIEnv *env;
        jobject progressCallback;
    };

    // unique_ptr guards the allocation until the handle is installed on
    // the Kotlin object; any early exit (failed load, JNI error) frees it.
    auto model = std::make_unique<LlamaModel>();
    CallbackContext ctx = {env, progressCallback};
    const char* utfModelPath = env->GetStringUTFChars(modelPath, nullptr);
    std::string templateOverride;
    if (chatTemplateOverride != nullptr) {
        const char *tpl = env->GetStringUTFChars(chatTemplateOverride, nullptr);
        if (tpl != nullptr) {
            templateOverride.assign(tpl);
            env->ReleaseStringUTFChars(chatTemplateOverride, tpl);
        }
    }
    model->loadModel(utfModelPath,
                     -1,
                     [](float progress, void *ctx) -> bool {
                            auto* context = static_cast<CallbackContext*>(ctx);
                            jclass clazz = context->env->GetObjectClass(context->progressCallback);
                            jmethodID methodId = context->env->GetMethodID(clazz, "onProgress", "(F)V");
                            context->env->CallVoidMethod(context->progressCallback, methodId, progress);
                            return true;
                     },
                     &ctx,
                     disableRepack == JNI_TRUE,
                     templateOverride
                     );
    env->ReleaseStringUTFChars(modelPath, utfModelPath);

    if (!model->isLoaded()) {
        return nullptr;
    }

    jclass clazz = env->FindClass("com/druk/llamacpp/jni/NativeLlamaModel");
    jmethodID constructor = env->GetMethodID(clazz, "<init>", "()V");
    jobject obj = env->NewObject(clazz, constructor);
    jfieldID fid = env->GetFieldID(clazz, "nativeHandle", "J");
    env->SetLongField(obj, fid, (long) model.release());
    return obj;
}

extern "C"
JNIEXPORT jlong JNICALL
Java_com_druk_llamacpp_jni_NativeLlamaModel_getModelSize(JNIEnv *env, jobject thiz) {
    jclass clazz = env->GetObjectClass(thiz);
    jfieldID fid = env->GetFieldID(clazz, "nativeHandle", "J");
    auto* model = (LlamaModel*) env->GetLongField(thiz, fid);
    if (model == nullptr) {
        return 0;
    }
    return model->getModelSize();
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_druk_llamacpp_jni_NativeLlamaModel_getModelReport(JNIEnv *env, jobject thiz) {
    jclass clazz = env->GetObjectClass(thiz);
    jfieldID fid = env->GetFieldID(clazz, "nativeHandle", "J");
    auto* model = (LlamaModel*) env->GetLongField(thiz, fid);
    if (model == nullptr) {
        return env->NewStringUTF("");
    }
    auto report = model->getModelReport();
    return env->NewStringUTF(report.c_str());
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_druk_llamacpp_jni_NativeLlamaModel_supportsThinking(JNIEnv *env, jobject thiz) {
    jclass clazz = env->GetObjectClass(thiz);
    jfieldID fid = env->GetFieldID(clazz, "nativeHandle", "J");
    auto* model = (LlamaModel*) env->GetLongField(thiz, fid);
    if (model == nullptr) {
        return JNI_FALSE;
    }
    return model->supportsThinking() ? JNI_TRUE : JNI_FALSE;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_druk_llamacpp_jni_NativeLlamaModel_loadMmprojModel(JNIEnv *env, jobject thiz, jstring mmprojPath) {
    jclass clazz = env->GetObjectClass(thiz);
    jfieldID fid = env->GetFieldID(clazz, "nativeHandle", "J");
    auto* model = (LlamaModel*) env->GetLongField(thiz, fid);
    if (model == nullptr) {
        return;
    }
    const char* utfPath = env->GetStringUTFChars(mmprojPath, nullptr);
    model->loadMmprojModel(utfPath);
    env->ReleaseStringUTFChars(mmprojPath, utfPath);
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_druk_llamacpp_jni_NativeLlamaModel_supportsVision(JNIEnv *env, jobject thiz) {
    jclass clazz = env->GetObjectClass(thiz);
    jfieldID fid = env->GetFieldID(clazz, "nativeHandle", "J");
    auto* model = (LlamaModel*) env->GetLongField(thiz, fid);
    if (model == nullptr) {
        return JNI_FALSE;
    }
    return model->supportsVision() ? JNI_TRUE : JNI_FALSE;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_druk_llamacpp_jni_NativeLlamaModel_unloadModel(JNIEnv *env, jobject thiz) {
    jclass clazz = env->GetObjectClass(thiz);
    jfieldID fid = env->GetFieldID(clazz, "nativeHandle", "J");
    auto* model = (LlamaModel*) env->GetLongField(thiz, fid);
    if (model == nullptr) {
        return;
    }
    env->SetLongField(thiz, fid, 0);
    model->unloadModel();
    delete model;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_druk_llamacpp_jni_NativeLlamaModel_getContextTrainSize(JNIEnv *env, jobject thiz) {
    jclass clazz = env->GetObjectClass(thiz);
    jfieldID fid = env->GetFieldID(clazz, "nativeHandle", "J");
    auto* model = (LlamaModel*) env->GetLongField(thiz, fid);
    if (model == nullptr) {
        return 0;
    }
    return model->getContextTrainSize();
}

extern "C"
JNIEXPORT jobject JNICALL
Java_com_druk_llamacpp_jni_NativeLlamaModel_createSession(JNIEnv *env, jobject thiz,
                                                  jint contextSize,
                                                  jfloat temperature,
                                                  jfloat topP,
                                                  jfloat repetitionPenalty,
                                                  jint topK,
                                                  jfloat minP,
                                                  jint seed,
                                                  jint thinkingBudget,
                                                  jstring systemPrompt) {

    jclass clazz1 = env->GetObjectClass(thiz);
    jfieldID fid1 = env->GetFieldID(clazz1, "nativeHandle", "J");
    auto* model = (LlamaModel*) env->GetLongField(thiz, fid1);
    if (model == nullptr) {
        return nullptr;
    }

    SamplerParams params;
    params.n_ctx = contextSize;
    params.temperature = temperature;
    params.top_p = topP;
    params.repetition_penalty = repetitionPenalty;
    params.top_k = topK;
    params.min_p = minP;
    params.seed = (seed < 0) ? LLAMA_DEFAULT_SEED : static_cast<uint32_t>(seed);
    params.thinking_budget = thinkingBudget;
    if (systemPrompt != nullptr) {
        const char* utfSystemPrompt = env->GetStringUTFChars(systemPrompt, nullptr);
        if (utfSystemPrompt != nullptr) {
            params.system_prompt = utfSystemPrompt;
            env->ReleaseStringUTFChars(systemPrompt, utfSystemPrompt);
        }
    }

    jclass clazz2 = env->FindClass("com/druk/llamacpp/jni/NativeLlamaSession");
    jmethodID constructor = env->GetMethodID(clazz2, "<init>", "()V");
    jobject obj = env->NewObject(clazz2, constructor);

    LlamaGenerationSession* session = model->createGenerationSession(params);
    if (session == nullptr) {
        return nullptr;
    }
    jclass clazz3 = env->GetObjectClass(obj);
    jfieldID fid3 = env->GetFieldID(clazz3, "nativeHandle", "J");
    env->SetLongField(obj, fid3, (long)session);

    return obj;
}

extern "C"
JNIEXPORT jobject JNICALL
Java_com_druk_llamacpp_jni_NativeLlamaModel_createEmbeddingSession(JNIEnv *env, jobject thiz,
                                                                   jint contextSize) {
    jclass clazz = env->GetObjectClass(thiz);
    jfieldID fid = env->GetFieldID(clazz, "nativeHandle", "J");
    auto* model = (LlamaModel*) env->GetLongField(thiz, fid);
    if (model == nullptr) {
        return nullptr;
    }

    LlamaEmbeddingSession* session = model->createEmbeddingSession(contextSize);
    if (session == nullptr) {
        return nullptr;
    }

    jclass sessionClass = env->FindClass("com/druk/llamacpp/jni/NativeLlamaEmbeddingSession");
    jmethodID constructor = env->GetMethodID(sessionClass, "<init>", "()V");
    jobject obj = env->NewObject(sessionClass, constructor);
    jfieldID sessionFid = env->GetFieldID(sessionClass, "nativeHandle", "J");
    env->SetLongField(obj, sessionFid, (jlong) session);
    return obj;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_druk_llamacpp_jni_NativeLlamaEmbeddingSession_getEmbeddingDim(JNIEnv *env, jobject thiz) {
    jclass clazz = env->GetObjectClass(thiz);
    jfieldID fid = env->GetFieldID(clazz, "nativeHandle", "J");
    auto* session = (LlamaEmbeddingSession*) env->GetLongField(thiz, fid);
    if (session == nullptr) {
        return 0;
    }
    return session->embeddingDim();
}

extern "C"
JNIEXPORT jfloatArray JNICALL
Java_com_druk_llamacpp_jni_NativeLlamaEmbeddingSession_embedTexts(JNIEnv *env, jobject thiz,
                                                                  jobjectArray texts) {
    jclass clazz = env->GetObjectClass(thiz);
    jfieldID fid = env->GetFieldID(clazz, "nativeHandle", "J");
    auto* session = (LlamaEmbeddingSession*) env->GetLongField(thiz, fid);
    if (session == nullptr) {
        return nullptr;
    }

    jsize count = env->GetArrayLength(texts);
    int dim = session->embeddingDim();
    if (count <= 0 || dim <= 0) {
        return nullptr;
    }

    jfloatArray result = env->NewFloatArray(count * dim);
    if (result == nullptr) {
        return nullptr;
    }
    std::vector<float> row(dim);
    for (jsize i = 0; i < count; i++) {
        auto jtext = (jstring) env->GetObjectArrayElement(texts, i);
        if (jtext == nullptr) {
            return nullptr;
        }
        const char* utf = env->GetStringUTFChars(jtext, nullptr);
        if (utf == nullptr) {
            return nullptr;
        }
        std::string text(utf);
        env->ReleaseStringUTFChars(jtext, utf);
        env->DeleteLocalRef(jtext);
        if (session->embed(text, row.data()) != 0) {
            return nullptr;
        }
        env->SetFloatArrayRegion(result, (jsize) i * dim, dim, row.data());
    }
    return result;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_druk_llamacpp_jni_NativeLlamaEmbeddingSession_destroy(JNIEnv *env, jobject thiz) {
    jclass clazz = env->GetObjectClass(thiz);
    jfieldID fid = env->GetFieldID(clazz, "nativeHandle", "J");
    auto* session = (LlamaEmbeddingSession*) env->GetLongField(thiz, fid);
    if (session == nullptr) {
        return;
    }
    env->SetLongField(thiz, fid, 0);
    delete session;
}

extern "C" JNIEXPORT jint JNICALL Java_com_druk_llamacpp_jni_NativeLlamaSession_generate
        (JNIEnv *env, jobject obj, jobject callback) {
    jclass clazz = env->GetObjectClass(obj);
    jfieldID fid = env->GetFieldID(clazz, "nativeHandle", "J");
    auto *session = (LlamaGenerationSession*)env->GetLongField(obj, fid);
    if (session == nullptr) {
        return 1;
    }

    jclass javaClass = env->FindClass("com/druk/llamacpp/LlamaGenerationCallback");
    jmethodID onFullResponseId = env->GetMethodID(javaClass, "onFullResponse", "(Ljava/lang/String;)V");

    return session->generate(
            [env, onFullResponseId, callback](const std::string &fullResponse) {
                // Strip trailing incomplete UTF-8 sequence to avoid
                // NewStringUTF crash on invalid Modified UTF-8.
                std::string safe = fullResponse;
                utf8TrimIncompleteTail(safe);
                jstring jResponse = env->NewStringUTF(safe.c_str());
                if (jResponse != nullptr) {
                    env->CallVoidMethod(callback, onFullResponseId, jResponse);
                    env->DeleteLocalRef(jResponse);
                }
            }
    );
}

extern "C"
JNIEXPORT void JNICALL
Java_com_druk_llamacpp_jni_NativeLlamaSession_setImageData(JNIEnv *env,
                                                            jobject thiz,
                                                            jbyteArray data) {
    jclass clazz = env->GetObjectClass(thiz);
    jfieldID fid = env->GetFieldID(clazz, "nativeHandle", "J");
    auto *session = (LlamaGenerationSession*)env->GetLongField(thiz, fid);
    if (session == nullptr) {
        return;
    }

    jsize len = env->GetArrayLength(data);
    jbyte* bytes = env->GetByteArrayElements(data, nullptr);
    session->setImageData(reinterpret_cast<const unsigned char*>(bytes), len);
    env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_druk_llamacpp_jni_NativeLlamaSession_addMessage(JNIEnv *env,
                                                         jobject thiz,
                                                         jstring message,
                                                         jboolean enableThinking) {
    jclass clazz = env->GetObjectClass(thiz);
    jfieldID fid = env->GetFieldID(clazz, "nativeHandle", "J");
    auto *session = (LlamaGenerationSession*)env->GetLongField(thiz, fid);
    if (session == nullptr) {
        return 1;
    }

    const char* utfMessage = env->GetStringUTFChars(message, nullptr);
    int rc = session->addMessage(utfMessage, enableThinking);
    env->ReleaseStringUTFChars(message, utfMessage);
    return rc;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_druk_llamacpp_jni_NativeLlamaSession_requestAbort(JNIEnv *env, jobject thiz) {
    jclass clazz = env->GetObjectClass(thiz);
    jfieldID fid = env->GetFieldID(clazz, "nativeHandle", "J");
    auto *session = (LlamaGenerationSession*)env->GetLongField(thiz, fid);
    if (session == nullptr) {
        return;
    }
    session->requestAbort();
}

extern "C"
JNIEXPORT void JNICALL
Java_com_druk_llamacpp_jni_NativeLlamaSession_printReport(JNIEnv *env, jobject thiz) {
    jclass clazz = env->GetObjectClass(thiz);
    jfieldID fid = env->GetFieldID(clazz, "nativeHandle", "J");
    auto *session = (LlamaGenerationSession*)env->GetLongField(thiz, fid);
    if (session == nullptr) {
        return;
    }
    session->printReport();
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_druk_llamacpp_jni_NativeLlamaSession_getReport(JNIEnv *env, jobject thiz) {
    jclass clazz = env->GetObjectClass(thiz);
    jfieldID fid = env->GetFieldID(clazz, "nativeHandle", "J");
    auto *session = (LlamaGenerationSession*)env->GetLongField(thiz, fid);
    if (session == nullptr) {
        return env->NewStringUTF("");
    }
    auto report = session->getReport();
    auto string = env->NewStringUTF(report.c_str());
    return string;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_druk_llamacpp_jni_NativeLlamaSession_replayHistory(JNIEnv *env,
                                                             jobject thiz,
                                                             jobjectArray userMessages,
                                                             jobjectArray assistantMessages) {
    jclass clazz = env->GetObjectClass(thiz);
    jfieldID fid = env->GetFieldID(clazz, "nativeHandle", "J");
    auto *session = (LlamaGenerationSession*)env->GetLongField(thiz, fid);
    if (session == nullptr) {
        return;
    }

    int len = env->GetArrayLength(userMessages);
    std::vector<std::pair<std::string, std::string>> history;
    history.reserve(len);

    for (int i = 0; i < len; i++) {
        auto jUser = (jstring) env->GetObjectArrayElement(userMessages, i);
        auto jAssistant = (jstring) env->GetObjectArrayElement(assistantMessages, i);
        const char* user = env->GetStringUTFChars(jUser, nullptr);
        const char* assistant = env->GetStringUTFChars(jAssistant, nullptr);
        history.emplace_back(user, assistant);
        env->ReleaseStringUTFChars(jUser, user);
        env->ReleaseStringUTFChars(jAssistant, assistant);
        env->DeleteLocalRef(jUser);
        env->DeleteLocalRef(jAssistant);
    }

    session->replayHistory(history);
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_druk_llamacpp_jni_NativeLlamaSession_rewindToTurn(JNIEnv *env,
                                                           jobject thiz,
                                                           jint turnIndex,
                                                           jstring expectedText) {
    jclass clazz = env->GetObjectClass(thiz);
    jfieldID fid = env->GetFieldID(clazz, "nativeHandle", "J");
    auto *session = (LlamaGenerationSession*)env->GetLongField(thiz, fid);
    if (session == nullptr) {
        return -1;
    }
    const char* utfText = expectedText != nullptr ? env->GetStringUTFChars(expectedText, nullptr) : nullptr;
    int kept = session->rewindToTurn(turnIndex, utfText);
    if (utfText != nullptr) env->ReleaseStringUTFChars(expectedText, utfText);
    return kept;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_druk_llamacpp_jni_NativeLlamaSession_getTurnCount(JNIEnv *env, jobject thiz) {
    jclass clazz = env->GetObjectClass(thiz);
    jfieldID fid = env->GetFieldID(clazz, "nativeHandle", "J");
    auto *session = (LlamaGenerationSession*)env->GetLongField(thiz, fid);
    if (session == nullptr) {
        return 0;
    }
    return session->getTurnCount();
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_druk_llamacpp_jni_NativeLlamaModel_supportsToolCalling(JNIEnv *env, jobject thiz) {
    jclass clazz = env->GetObjectClass(thiz);
    jfieldID fid = env->GetFieldID(clazz, "nativeHandle", "J");
    auto* model = (LlamaModel*) env->GetLongField(thiz, fid);
    if (model == nullptr) return JNI_FALSE;
    return model->supportsToolCalling() ? JNI_TRUE : JNI_FALSE;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_druk_llamacpp_jni_NativeLlamaSession_setTools(JNIEnv *env, jobject thiz, jstring toolsJson) {
    jclass clazz = env->GetObjectClass(thiz);
    jfieldID fid = env->GetFieldID(clazz, "nativeHandle", "J");
    auto *session = (LlamaGenerationSession*)env->GetLongField(thiz, fid);
    if (session == nullptr) return;
    const char* utfJson = env->GetStringUTFChars(toolsJson, nullptr);
    session->setTools(utfJson);
    env->ReleaseStringUTFChars(toolsJson, utfJson);
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_druk_llamacpp_jni_NativeLlamaSession_getToolCallsJson(JNIEnv *env, jobject thiz) {
    jclass clazz = env->GetObjectClass(thiz);
    jfieldID fid = env->GetFieldID(clazz, "nativeHandle", "J");
    auto *session = (LlamaGenerationSession*)env->GetLongField(thiz, fid);
    if (session == nullptr) return env->NewStringUTF("[]");
    auto json = session->getToolCallsJson();
    return env->NewStringUTF(json.c_str());
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_druk_llamacpp_jni_NativeLlamaSession_submitToolResults(JNIEnv *env, jobject thiz,
                                                                 jstring resultsJson,
                                                                 jboolean enableThinking) {
    jclass clazz = env->GetObjectClass(thiz);
    jfieldID fid = env->GetFieldID(clazz, "nativeHandle", "J");
    auto *session = (LlamaGenerationSession*)env->GetLongField(thiz, fid);
    if (session == nullptr) return 1;
    const char* utfJson = env->GetStringUTFChars(resultsJson, nullptr);
    int result = session->submitToolResults(utfJson, enableThinking);
    env->ReleaseStringUTFChars(resultsJson, utfJson);
    return result;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_druk_llamacpp_jni_NativeLlamaSession_renderPreambleString(JNIEnv *env, jobject thiz,
                                                                    jboolean enableThinking) {
    jclass clazz = env->GetObjectClass(thiz);
    jfieldID fid = env->GetFieldID(clazz, "nativeHandle", "J");
    auto *session = (LlamaGenerationSession*)env->GetLongField(thiz, fid);
    if (session == nullptr) return env->NewStringUTF("");
    auto preamble = session->renderPreambleString(enableThinking);
    return env->NewStringUTF(preamble.c_str());
}

extern "C"
JNIEXPORT void JNICALL
Java_com_druk_llamacpp_jni_NativeLlamaSession_setPreambleCachePath(JNIEnv *env, jobject thiz,
                                                                    jstring path,
                                                                    jstring fingerprint) {
    jclass clazz = env->GetObjectClass(thiz);
    jfieldID fid = env->GetFieldID(clazz, "nativeHandle", "J");
    auto *session = (LlamaGenerationSession*)env->GetLongField(thiz, fid);
    if (session == nullptr) return;
    const char* utfPath = path != nullptr ? env->GetStringUTFChars(path, nullptr) : nullptr;
    const char* utfFp   = fingerprint != nullptr ? env->GetStringUTFChars(fingerprint, nullptr) : nullptr;
    session->setPreambleCachePath(utfPath, utfFp);
    if (utfPath != nullptr) env->ReleaseStringUTFChars(path, utfPath);
    if (utfFp   != nullptr) env->ReleaseStringUTFChars(fingerprint, utfFp);
}

extern "C" JNIEXPORT void JNICALL Java_com_druk_llamacpp_jni_NativeLlamaSession_destroy
        (JNIEnv *env, jobject obj) {
    jclass clazz = env->GetObjectClass(obj);
    jfieldID fid = env->GetFieldID(clazz, "nativeHandle", "J");
    auto *session = (LlamaGenerationSession*)env->GetLongField(obj, fid);

    if (session != nullptr) {
        env->SetLongField(obj, fid, (long)nullptr);
        delete session;
        LMP_LOGT(ANDROID_LOG_DEBUG, "Llama", "Destroy");
    }
}
