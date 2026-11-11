// SPDX-License-Identifier: LGPL-3.0-or-later

#pragma once

#include <errno.h>
#include <semaphore.h>
#include <time.h>

#include <atomic>
#include <cstdint>

namespace dish_hot {

// A worker thread's sleep. A producer posts the semaphore only after it sees the worker asleep, so
// a busy stream costs the producer one load per item and no system call; the worker re-checks for
// work after it has said it is going to sleep, so no item is left waiting for the next one.
class WorkerWake {
  public:
    WorkerWake() noexcept { sem_init(&wake_, 0, 0); }
    ~WorkerWake() { sem_destroy(&wake_); }
    WorkerWake(const WorkerWake&) = delete;
    WorkerWake& operator=(const WorkerWake&) = delete;

    void notify() noexcept {
        if (sleeping_.load(std::memory_order_seq_cst) &&
            sleeping_.exchange(false, std::memory_order_seq_cst)) {
            sem_post(&wake_);
        }
    }

    // hasWork reads, with seq_cst loads, whatever the producers publish before they notify.
    template <typename HasWork> void sleepUnless(HasWork&& hasWork) {
        sleeping_.store(true, std::memory_order_seq_cst);
        if (!hasWork()) {
            while (sem_wait(&wake_) != 0 && errno == EINTR) {}
        }
        sleeping_.store(false, std::memory_order_seq_cst);
    }

    // As sleepUnless, for no longer than timeoutMs. sem_timedwait takes its deadline on the
    // realtime clock, the one clock it takes on every platform this builds for, so a wall-clock
    // step stretches or cuts that one wait; the caller reads its own clock again on every wake.
    template <typename HasWork> void sleepUnlessFor(HasWork&& hasWork, const uint32_t timeoutMs) {
        sleeping_.store(true, std::memory_order_seq_cst);
        if (!hasWork()) {
            const timespec deadline = realtimeAfter(timeoutMs);
            while (sem_timedwait(&wake_, &deadline) != 0 && errno == EINTR) {}
        }
        sleeping_.store(false, std::memory_order_seq_cst);
    }

  private:
    static timespec realtimeAfter(const uint32_t ms) {
        constexpr int64_t kMsPerSecond = 1000;
        constexpr int64_t kNsPerMs = 1000000;
        constexpr int64_t kNsPerSecond = 1000000000;
        timespec now{};
        clock_gettime(CLOCK_REALTIME, &now);
        const int64_t ns = (int64_t)now.tv_nsec + (int64_t)(ms % kMsPerSecond) * kNsPerMs;
        timespec deadline{};
        deadline.tv_sec = now.tv_sec + (time_t)(ms / kMsPerSecond) + (time_t)(ns / kNsPerSecond);
        deadline.tv_nsec = (long)(ns % kNsPerSecond);
        return deadline;
    }

    sem_t wake_;
    std::atomic<bool> sleeping_{false};
};

} // namespace dish_hot
