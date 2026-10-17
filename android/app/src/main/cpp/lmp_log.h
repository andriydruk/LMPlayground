//
// Portable logging shim.
//
// On Android these macros expand to exactly the __android_log_print calls
// the engine sources used before this header existed, so the shipped
// libllamacpp.so is unchanged. Off Android (the macOS host build used by
// :model-harness) they go to stderr, where the harness captures them per
// model and folds them into the capability report.
//
// Define LMP_LOG_TAG before including to override the default tag.
//

#ifndef LMP_LOG_H
#define LMP_LOG_H

#ifndef LMP_LOG_TAG
#define LMP_LOG_TAG "llama-android.cpp"
#endif

#if defined(__ANDROID__)

#include <android/log.h>

// LMP_LOGT logs with an explicit tag and Android severity; used by the few
// sites that log under a tag other than LMP_LOG_TAG.
#define LMP_LOGT(sev, tag, ...) __android_log_print(sev, tag, __VA_ARGS__)

#define LOGi(...) __android_log_print(ANDROID_LOG_INFO,  LMP_LOG_TAG, __VA_ARGS__)
#define LOGe(...) __android_log_print(ANDROID_LOG_ERROR, LMP_LOG_TAG, __VA_ARGS__)
#define LOGw(...) __android_log_print(ANDROID_LOG_WARN,  LMP_LOG_TAG, __VA_ARGS__)
#define LOGd(...) __android_log_print(ANDROID_LOG_DEBUG, LMP_LOG_TAG, __VA_ARGS__)

#else

#include <cstdio>

#define LMP_LOG_IMPL(sev, tag, ...)             \
    do {                                        \
        fprintf(stderr, "[%s][%s] ", tag, sev); \
        fprintf(stderr, __VA_ARGS__);           \
        fputc('\n', stderr);                    \
        fflush(stderr);                         \
    } while (0)

// Host builds have no Android severity constants; the severity argument is
// ignored and everything lands on stderr.
#define LMP_LOGT(sev, tag, ...) LMP_LOG_IMPL("I", tag, __VA_ARGS__)

#define LOGi(...) LMP_LOG_IMPL("I", LMP_LOG_TAG, __VA_ARGS__)
#define LOGe(...) LMP_LOG_IMPL("E", LMP_LOG_TAG, __VA_ARGS__)
#define LOGw(...) LMP_LOG_IMPL("W", LMP_LOG_TAG, __VA_ARGS__)
#define LOGd(...) LMP_LOG_IMPL("D", LMP_LOG_TAG, __VA_ARGS__)

#endif // __ANDROID__

#endif // LMP_LOG_H
