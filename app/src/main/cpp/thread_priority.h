// SPDX-License-Identifier: LGPL-3.0-or-later

#ifndef DISH_THREAD_PRIORITY_H
#define DISH_THREAD_PRIORITY_H

#include <android/log.h>
#include <errno.h>
#include <string.h>
#include <sys/resource.h>

namespace dish {

// Raise the calling thread to URGENT_AUDIO niceness so input read/dispatch is not descheduled
// behind rendering or GC under load. Best-effort: a device that denies the nice value just keeps
// the default, and says so once, at thread start, since the latency it costs is worth a line.
inline void elevateCurrentThreadToInputPriority() {
    if (setpriority(PRIO_PROCESS, 0, -19) != 0) {
        __android_log_print(ANDROID_LOG_INFO, "dish-thread",
                            "input thread keeps the default priority: setpriority failed: %s",
                            strerror(errno));
    }
}

} // namespace dish

#endif // DISH_THREAD_PRIORITY_H
