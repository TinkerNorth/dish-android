// SPDX-License-Identifier: LGPL-3.0-or-later

#pragma once

#include <errno.h>
#include <semaphore.h>

#include <atomic>

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

  private:
    sem_t wake_;
    std::atomic<bool> sleeping_{false};
};

} // namespace dish_hot
