// SPDX-License-Identifier: LGPL-3.0-or-later

#pragma once

#include <android/log.h>
#include <errno.h>
#include <string.h>
#include <sys/resource.h>

namespace dish {

// Android's THREAD_PRIORITY_URGENT_AUDIO: the lowest nice value, so the highest priority, that
// android.os.Process names.
constexpr int kUrgentAudioNice = -19;
// setpriority's "who" that names the calling thread under PRIO_PROCESS.
constexpr id_t kCallingThread = 0;

// Raise the calling thread to URGENT_AUDIO niceness so input read/dispatch is not descheduled
// behind rendering or GC under load. Best-effort: a device that denies the nice value just keeps
// the default, and says so once, at thread start, since the latency it costs is worth a line.
inline void elevateCurrentThreadToInputPriority() {
    const bool raised = setpriority(PRIO_PROCESS, kCallingThread, kUrgentAudioNice) == 0;
    if (!raised) {
        __android_log_print(ANDROID_LOG_INFO, "dish-thread",
                            "input thread keeps the default priority: setpriority failed: %s",
                            strerror(errno));
    }
}

} // namespace dish
