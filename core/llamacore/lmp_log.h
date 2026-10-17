//
// Portable logging shim.
//
// On Android these macros expand to exactly the __android_log_print calls
// the engine sources used before this header existed, so the shipped
// libllamacpp.so is unchanged. The iOS app (LMP_LOG_OSLOG) logs to os_log.
// Everywhere else (the macOS host build used by :model-harness, LlamaKit's
// macOS tests) they go to stderr, where the harness captures them per model
// and folds them into the capability report.
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

#elif defined(LMP_LOG_OSLOG)

// iOS: the unified log (Console.app, sysdiagnose). Defined by the LlamaKit
// build for iOS SDKs only; macOS tests keep stderr. os_log needs a constant
// format string, so messages are formatted first and logged as one public
// string, prefixed with the tag.

#include <cstdarg>
#include <cstdio>
#include <os/log.h>

static inline os_log_t lmp_os_log_handle() {
    static os_log_t handle = os_log_create("com.druk.lmplayground", "engine");
    return handle;
}

__attribute__((format(printf, 3, 4)))
static inline void lmp_os_log(os_log_type_t type, const char *tag, const char *fmt, ...) {
    char buf[1024];
    va_list args;
    va_start(args, fmt);
    vsnprintf(buf, sizeof(buf), fmt, args);
    va_end(args);
    os_log_with_type(lmp_os_log_handle(), type, "[%{public}s] %{public}s", tag, buf);
}

// The severity argument is an Android constant; everything else logs at default.
#define LMP_LOGT(sev, tag, ...) lmp_os_log(OS_LOG_TYPE_DEFAULT, tag, __VA_ARGS__)

#define LOGi(...) lmp_os_log(OS_LOG_TYPE_INFO,    LMP_LOG_TAG, __VA_ARGS__)
#define LOGe(...) lmp_os_log(OS_LOG_TYPE_ERROR,   LMP_LOG_TAG, __VA_ARGS__)
#define LOGw(...) lmp_os_log(OS_LOG_TYPE_DEFAULT, LMP_LOG_TAG, __VA_ARGS__)
#define LOGd(...) lmp_os_log(OS_LOG_TYPE_DEBUG,   LMP_LOG_TAG, __VA_ARGS__)

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
