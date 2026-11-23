// SPDX-License-Identifier: LGPL-3.0-or-later

#pragma once

#include <cstdio>

#ifdef __ANDROID__
#include <android/log.h>
#endif

// The session, send and receive units log through here: logcat under the JNI layer's tag on a
// device, stderr in a host build.
namespace dish_log {

inline constexpr const char* kTag = "SatelliteJNI";

// A message with arguments is a printf format and its arguments, forwarded as a parameter pack
// (cert-dcl50-cpp: no C-style variadic definitions). A message with none goes through "%s", so a
// literal that happens to hold a percent sign is never read as a format.
#ifdef __ANDROID__
template <typename... Args> void write(const int priority, const char* fmt, const Args&... args) {
    __android_log_print(priority, kTag, fmt, args...);
}
inline constexpr int kInfo = ANDROID_LOG_INFO;
inline constexpr int kError = ANDROID_LOG_ERROR;
#else
template <typename... Args> void write(const int, const char* fmt, const Args&... args) {
    std::fprintf(stderr, fmt, args...);
    std::fputc('\n', stderr);
}
inline constexpr int kInfo = 0;
inline constexpr int kError = 1;
#endif

inline void info(const char* message) { write(kInfo, "%s", message); }
template <typename... Args> void info(const char* fmt, const Args&... args) {
    write(kInfo, fmt, args...);
}

inline void error(const char* message) { write(kError, "%s", message); }
template <typename... Args> void error(const char* fmt, const Args&... args) {
    write(kError, fmt, args...);
}

} // namespace dish_log
