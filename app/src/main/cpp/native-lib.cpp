#include <jni.h>
#include <string>

#include "LlamaCpp.h"
#include "common.h"

#include "console.h"
#include "ggml.h"
#include "llama.h"
#include "log.h"

#include <cassert>
#include <cinttypes>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <ctime>
#include <fstream>
#include <iostream>
#include <sstream>
#include <string>
#include <vector>
#include <iostream>
#include <csignal>
#include <unistd.h>
#include <android/log.h>

class AndroidLogBuf : public std::streambuf {
protected:
    std::streamsize xsputn(const char* s, std::streamsize n) override {
        __android_log_print(ANDROID_LOG_INFO, "Llama", "%.*s", n, s);
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

#define TAG "llama-android.cpp"
static void log_callback(ggml_log_level level, const char * fmt, void * data) {
    if (level == GGML_LOG_LEVEL_ERROR)     __android_log_print(ANDROID_LOG_ERROR, TAG, fmt, data);
    else if (level == GGML_LOG_LEVEL_INFO) __android_log_print(ANDROID_LOG_INFO, TAG, fmt, data);
    else if (level == GGML_LOG_LEVEL_WARN) __android_log_print(ANDROID_LOG_WARN, TAG, fmt, data);
    else __android_log_print(ANDROID_LOG_DEFAULT, TAG, fmt, data);
}

extern "C" JNIEXPORT int
JNICALL
Java_com_druk_llamacpp_LlamaCpp_init(JNIEnv *env, jobject object) {

    // Redirect std::cerr to logcat
    AndroidLogBuf androidLogBuf;
    std::cerr.rdbuf(&androidLogBuf);

    llama_log_set(log_callback, NULL);
    llama_backend_init();
    return 0;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_druk_llamacpp_LlamaCpp_systemInfo(JNIEnv *env, jobject object) {
    return env->NewStringUTF(llama_print_system_info());
}

extern "C" JNIEXPORT jobject
JNICALL
Java_com_druk_llamacpp_LlamaCpp_loadModel(JNIEnv *env,
                   jobject activity,
                   jstring modelPath,
                   jstring inputPrefix,
                   jstring inputSuffix,
                   jobjectArray aniPrompt,
                   jobject progressCallback) {

    // Struct to hold multiple pointers
    struct CallbackContext {
        JNIEnv *env;
        jobject progressCallback;
    };

    auto* model = new LlamaModel();
    CallbackContext ctx = {env, progressCallback};
    const char* utfModelPath = env->GetStringUTFChars(modelPath, nullptr);
    model->loadModel(utfModelPath,
                     -1,
                     [](float progress, void *ctx) -> bool {
                            auto* context = static_cast<CallbackContext*>(ctx);
                            jclass clazz = context->env->GetObjectClass(context->progressCallback);
                            jmethodID methodId = context->env->GetMethodID(clazz, "onProgress", "(F)V");
                            context->env->CallVoidMethod(context->progressCallback, methodId, progress);
                            return true;
                     },
                     &ctx
                     );
    env->ReleaseStringUTFChars(modelPath, utfModelPath);
    jclass clazz = env->FindClass("com/druk/llamacpp/LlamaModel");
    jmethodID constructor = env->GetMethodID(clazz, "<init>", "()V");
    jobject obj = env->NewObject(clazz, constructor);
    jfieldID fid = env->GetFieldID(clazz, "nativeHandle", "J");
    env->SetLongField(obj, fid, (long) model);
    return obj;
}

extern "C"
JNIEXPORT jlong JNICALL
Java_com_druk_llamacpp_LlamaModel_getModelSize(JNIEnv *env, jobject thiz) {
    jclass clazz = env->GetObjectClass(thiz);
    jfieldID fid = env->GetFieldID(clazz, "nativeHandle", "J");
    auto* model = (LlamaModel*) env->GetLongField(thiz, fid);
    return model->getModelSize();
}

extern "C"
JNIEXPORT void JNICALL
Java_com_druk_llamacpp_LlamaModel_unloadModel(JNIEnv *env, jobject thiz) {
    jclass clazz = env->GetObjectClass(thiz);
    jfieldID fid = env->GetFieldID(clazz, "nativeHandle", "J");
    auto* model = (LlamaModel*) env->GetLongField(thiz, fid);
    model->unloadModel();
    delete model;
}

extern "C"
JNIEXPORT jobject JNICALL
Java_com_druk_llamacpp_LlamaModel_createSession(JNIEnv *env, jobject thiz,
                                                jint n_ctx,
                                                jfloat temperature,
                                                jfloat top_p,
                                                jint top_k) {

    jclass clazz1 = env->GetObjectClass(thiz);
    jfieldID fid1 = env->GetFieldID(clazz1, "nativeHandle", "J");
    auto* model = (LlamaModel*) env->GetLongField(thiz, fid1);

    jclass clazz2 = env->FindClass("com/druk/llamacpp/LlamaGenerationSession");
    jmethodID constructor = env->GetMethodID(clazz2, "<init>", "()V");
    jobject obj = env->NewObject(clazz2, constructor);

    LlamaGenerationSession* session = model->createGenerationSession(n_ctx,
                                                                     temperature,
                                                                     top_p,
                                                                     top_k);
    jclass clazz3 = env->GetObjectClass(obj);
    jfieldID fid3 = env->GetFieldID(clazz3, "nativeHandle", "J");
    env->SetLongField(obj, fid3, (long)session);

    return obj;
}

extern "C" JNIEXPORT jint JNICALL Java_com_druk_llamacpp_LlamaGenerationSession_generate
        (JNIEnv *env, jobject obj, jobject callback) {
    jclass clazz = env->GetObjectClass(obj);
    jfieldID fid = env->GetFieldID(clazz, "nativeHandle", "J");
    auto *session = (LlamaGenerationSession*)env->GetLongField(obj, fid);

    jclass javaClass = env->FindClass("com/druk/llamacpp/LlamaGenerationCallback");
    jmethodID newTokensMethodId = env->GetMethodID(javaClass, "newTokens", "([B)V");

    return session->generate(
            [env, newTokensMethodId, callback](const std::string &response) {
                const char *cStr = response.c_str();
                jsize len = strlen(cStr);
                jbyteArray result = env->NewByteArray(len);
                env->SetByteArrayRegion(result, 0, len, (jbyte *) cStr);
                env->CallVoidMethod(callback, newTokensMethodId, result);
                env->DeleteLocalRef(result);
            }
    );
}

extern "C"
JNIEXPORT void JNICALL
Java_com_druk_llamacpp_LlamaGenerationSession_addMessage(JNIEnv *env,
                                                         jobject thiz,
                                                         jstring message) {
    jclass clazz = env->GetObjectClass(thiz);
    jfieldID fid = env->GetFieldID(clazz, "nativeHandle", "J");
    auto *session = (LlamaGenerationSession*)env->GetLongField(thiz, fid);

    const char* utfMessage = env->GetStringUTFChars(message, nullptr);
    session->addMessage(utfMessage);
    env->ReleaseStringUTFChars(message, utfMessage);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_druk_llamacpp_LlamaGenerationSession_printReport(JNIEnv *env, jobject thiz) {
    jclass clazz = env->GetObjectClass(thiz);
    jfieldID fid = env->GetFieldID(clazz, "nativeHandle", "J");
    auto *session = (LlamaGenerationSession*)env->GetLongField(thiz, fid);
    session->printReport();
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_druk_llamacpp_LlamaGenerationSession_getReport(JNIEnv *env, jobject thiz) {
    jclass clazz = env->GetObjectClass(thiz);
    jfieldID fid = env->GetFieldID(clazz, "nativeHandle", "J");
    auto *session = (LlamaGenerationSession*)env->GetLongField(thiz, fid);
    auto report = session->getReport();
    auto string = env->NewStringUTF(report.c_str());
    return string;
}

extern "C" JNIEXPORT void JNICALL Java_com_druk_llamacpp_LlamaGenerationSession_destroy
        (JNIEnv *env, jobject obj) {
    jclass clazz = env->GetObjectClass(obj);
    jfieldID fid = env->GetFieldID(clazz, "nativeHandle", "J");
    auto *session = (LlamaGenerationSession*)env->GetLongField(obj, fid);

    if (session != nullptr) {
        delete session;
        env->SetLongField(obj, fid, (long)nullptr);
        __android_log_print(ANDROID_LOG_DEBUG, "Llama", "Destroy");
    }
}
