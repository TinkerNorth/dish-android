// SPDX-License-Identifier: LGPL-3.0-or-later

#pragma once

#include <cstdarg>
#include <cstdio>

#ifdef __ANDROID__
#include <android/log.h>
#endif

// The session, send and receive units log through here: logcat under the JNI layer's tag on a
// device, stderr in a host build.
namespace dish_log {

inline constexpr const char* kTag = "SatelliteJNI";

#ifdef __ANDROID__
inline void write(const int priority, const char* fmt, va_list args) {
    __android_log_vprint(priority, kTag, fmt, args);
}
inline constexpr int kInfo = ANDROID_LOG_INFO;
inline constexpr int kError = ANDROID_LOG_ERROR;
#else
inline void write(const int, const char* fmt, va_list args) {
    std::vfprintf(stderr, fmt, args);
    std::fputc('\n', stderr);
}
inline constexpr int kInfo = 0;
inline constexpr int kError = 1;
#endif

__attribute__((format(printf, 1, 2))) inline void info(const char* fmt, ...) {
    va_list args;
    va_start(args, fmt);
    write(kInfo, fmt, args);
    va_end(args);
}

__attribute__((format(printf, 1, 2))) inline void error(const char* fmt, ...) {
    va_list args;
    va_start(args, fmt);
    write(kError, fmt, args);
    va_end(args);
}

} // namespace dish_log
