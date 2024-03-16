#include <jni.h>
#include <string>

#include "LlamaCpp.h"
#include "common.h"

#include "console.h"
#include "llama.h"

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

#include <signal.h>
#include <unistd.h>
#include <android/log.h>

#include <android/log.h>
#include <iostream>

#include "clblast/include/opencl_wrapper.h"

class AndroidLogBuf : public std::streambuf {
protected:
    virtual std::streamsize xsputn(const char* s, std::streamsize n) override {
        __android_log_print(ANDROID_LOG_INFO, "Llama", "%.*s", n, s);
        return n;
    }

    virtual int overflow(int c = EOF) override {
        if (c != EOF) {
            char c_as_char = static_cast<char>(c);
            __android_log_write(ANDROID_LOG_INFO, "Llama", &c_as_char);
        }
        return c;
    }
};

gpt_params g_params;
//llama_context *g_ctx_guidance = NULL;
//llama_model *g_model = NULL;

static void llama_log_callback_logTee(ggml_log_level level, const char * text, void * user_data) {
    (void) level;
    (void) user_data;
    __android_log_print(ANDROID_LOG_INFO, "Llama", "%s", text);
}

gpt_params initLlamaCpp();
void load_model(gpt_params params, std::string modelPath, llama_model **inout_model, llama_context **inout_ctx_guidance);
int generate(gpt_params params,
             llama_model *model,
             llama_context * ctx_guidance,
             JNIEnv *env,
             jobject activity);

extern "C" JNIEXPORT int
JNICALL
Java_com_druk_llamacpp_LlamaCpp_loadOpenCL(JNIEnv *env, jobject activity) {
#ifdef LLAMA_CLBLAST
    return clblast::LoadOpenCL();
#else
    return 0;
#endif
}

extern "C" JNIEXPORT int
JNICALL
Java_com_druk_llamacpp_LlamaCpp_init(JNIEnv *env, jobject activity) {

    // Redirect std::cerr to logcat
    AndroidLogBuf androidLogBuf;
    std::cerr.rdbuf(&androidLogBuf);

    // Now, std::cerr outputs to logcat
    // std::cerr << "This error message goes to logcat." << std::endl;

    g_params = initLlamaCpp();
    return 0;
}

extern "C" JNIEXPORT jobject
JNICALL
Java_com_druk_llamacpp_LlamaCpp_loadModel(JNIEnv *env,
                   jobject activity,
                   jstring modelPath,
                   jobject progressCallback) {

    // Struct to hold multiple pointers
    struct CallbackContext {
        JNIEnv *env;
        jobject progressCallback;
    };

    LlamaModel* model = new LlamaModel();
    CallbackContext ctx = {env, progressCallback};
    model->loadModel(g_params,
                     env->GetStringUTFChars(modelPath, 0),
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
    LlamaModel* model = (LlamaModel*) env->GetLongField(thiz, fid);
    return model->getModelSize();
}

extern "C"
JNIEXPORT void JNICALL
Java_com_druk_llamacpp_LlamaModel_unloadModel(JNIEnv *env, jobject thiz) {
    jclass clazz = env->GetObjectClass(thiz);
    jfieldID fid = env->GetFieldID(clazz, "nativeHandle", "J");
    LlamaModel* model = (LlamaModel*) env->GetLongField(thiz, fid);
    model->unloadModel();
    delete model;
}

extern "C"
JNIEXPORT jobject JNICALL
Java_com_druk_llamacpp_LlamaModel_createSession(JNIEnv *env,
                                                jobject thiz,
                                                jstring inputPrefix,
                                                jstring inputSuffix) {

    jclass clazz1 = env->GetObjectClass(thiz);
    jfieldID fid1 = env->GetFieldID(clazz1, "nativeHandle", "J");
    LlamaModel* model = (LlamaModel*) env->GetLongField(thiz, fid1);

    jclass clazz2 = env->FindClass("com/druk/llamacpp/LlamaGenerationSession");
    jmethodID constructor = env->GetMethodID(clazz2, "<init>", "()V");
    jobject obj = env->NewObject(clazz2, constructor);

    const char *inputPrefixCStr = env->GetStringUTFChars(inputPrefix, nullptr);
    const char *inputSuffixCStr = env->GetStringUTFChars(inputSuffix, nullptr);
    LlamaGenerationSession* session = model->createGenerationSession(inputPrefixCStr,
                                                                     inputSuffixCStr);
    jclass clazz3 = env->GetObjectClass(obj);
    jfieldID fid3 = env->GetFieldID(clazz3, "nativeHandle", "J");
    env->SetLongField(obj, fid3, (long)session);

    return obj;
}

extern "C" JNIEXPORT jint JNICALL Java_com_druk_llamacpp_LlamaGenerationSession_generate
        (JNIEnv *env, jobject obj, jobject callback) {
    jclass clazz = env->GetObjectClass(obj);
    jfieldID fid = env->GetFieldID(clazz, "nativeHandle", "J");
    LlamaGenerationSession *session = (LlamaGenerationSession*)env->GetLongField(obj, fid);

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
    LlamaGenerationSession *session = (LlamaGenerationSession*)env->GetLongField(thiz, fid);

    session->addMessage(env->GetStringUTFChars(message, nullptr));
}

extern "C"
JNIEXPORT void JNICALL
Java_com_druk_llamacpp_LlamaGenerationSession_printReport(JNIEnv *env, jobject thiz) {
    jclass clazz = env->GetObjectClass(thiz);
    jfieldID fid = env->GetFieldID(clazz, "nativeHandle", "J");
    LlamaGenerationSession *session = (LlamaGenerationSession*)env->GetLongField(thiz, fid);
    session->printReport();
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_druk_llamacpp_LlamaGenerationSession_getReport(JNIEnv *env, jobject thiz) {
    jclass clazz = env->GetObjectClass(thiz);
    jfieldID fid = env->GetFieldID(clazz, "nativeHandle", "J");
    LlamaGenerationSession *session = (LlamaGenerationSession*)env->GetLongField(thiz, fid);
    auto report = session->getReport();
    auto string = env->NewStringUTF(report.c_str());
    return string;
}

extern "C" JNIEXPORT void JNICALL Java_com_druk_llamacpp_LlamaGenerationSession_destroy
        (JNIEnv *env, jobject obj) {
    jclass clazz = env->GetObjectClass(obj);
    jfieldID fid = env->GetFieldID(clazz, "nativeHandle", "J");
    LlamaGenerationSession *session = (LlamaGenerationSession*)env->GetLongField(obj, fid);

    if (session != nullptr) {
        delete session;
        env->SetLongField(obj, fid, (long)nullptr);
        __android_log_print(ANDROID_LOG_DEBUG, "Llama", "Destroy");
    }
}

gpt_params initLlamaCpp() {
    gpt_params params;
    // params.n_gpu_layers = 32;

#ifndef LOG_DISABLE_LOGS
    log_set_target(log_filename_generator("main", "log"));
    LOG_TEE("Log start\n");
    llama_log_set(llama_log_callback_logTee, nullptr);
#endif // LOG_DISABLE_LOGS

    // save choice to use color for later
    // (note for later: this is a slightly awkward choice)
    console::init(params.simple_io, params.use_color);
    atexit([]() { console::cleanup(); });

    if (params.logits_all) {
        printf("\n************\n");
        printf("%s: please use the 'perplexity' tool for perplexity calculations\n", __func__);
        printf("************\n\n");
    }

    if (params.embedding) {
        printf("\n************\n");
        printf("%s: please use the 'embedding' tool for embedding calculations\n", __func__);
        printf("************\n\n");
    }

    if (params.n_ctx != 0 && params.n_ctx < 8) {
        LOG_TEE("%s: warning: minimum context size is 8, using minimum size.\n", __func__);
        params.n_ctx = 8;
    }

    if (params.rope_freq_base != 0.0) {
        LOG_TEE("%s: warning: changing RoPE frequency base to %g.\n", __func__, params.rope_freq_base);
    }

    if (params.rope_freq_scale != 0.0) {
        LOG_TEE("%s: warning: scaling RoPE frequency by %g.\n", __func__, params.rope_freq_scale);
    }

    LOG_TEE("%s: build = %d (%s)\n",      __func__, LLAMA_BUILD_NUMBER, LLAMA_COMMIT);
    LOG_TEE("%s: built with %s for %s\n", __func__, LLAMA_COMPILER, LLAMA_BUILD_TARGET);

    if (params.seed == LLAMA_DEFAULT_SEED) {
        params.seed = time(NULL);
    }

    LOG_TEE("%s: seed  = %u\n", __func__, params.seed);

    std::mt19937 rng(params.seed);
    if (params.random_prompt) {
        params.prompt = gpt_random_prompt(rng);
    }

    LOG("%s: llama backend init\n", __func__);
    llama_backend_init();

    return params;
}
