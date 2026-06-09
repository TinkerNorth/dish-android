// SPDX-License-Identifier: LGPL-3.0-or-later

#ifndef DISH_THREAD_PRIORITY_H
#define DISH_THREAD_PRIORITY_H

#include <sys/resource.h>

namespace dish {

// Raise the calling thread to URGENT_AUDIO niceness so input read/dispatch is not descheduled behind
// rendering or GC under load. Best-effort: a device that denies the nice value just keeps the default.
inline void elevateCurrentThreadToInputPriority() {
    (void)setpriority(PRIO_PROCESS, 0, -19);
}

}  // namespace dish

#endif  // DISH_THREAD_PRIORITY_H
