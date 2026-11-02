// SPDX-License-Identifier: LGPL-3.0-or-later

#include "worker_wake.h"

#include <gtest/gtest.h>

#include <atomic>
#include <chrono>
#include <cstdint>
#include <thread>

#include "mpmc_ring.h"

namespace {

using Clock = std::chrono::steady_clock;

constexpr auto kStillAsleep = std::chrono::milliseconds(100);
constexpr auto kWakeLimit = std::chrono::seconds(5);
constexpr uint32_t kItems = 200000;

bool waitFor(const std::atomic<bool>& flag) {
    const auto deadline = Clock::now() + kWakeLimit;
    while (!flag.load()) {
        if (Clock::now() >= deadline) return false;
        std::this_thread::yield();
    }
    return true;
}

// After a failed expectation the worker may still be asleep: notifying until it is done frees it
// so the test ends instead of hanging in the join.
void freeAndJoin(dish_hot::WorkerWake& wake, const std::atomic<bool>& done, std::thread& worker) {
    while (!done.load()) {
        wake.notify();
        std::this_thread::yield();
    }
    worker.join();
}

} // namespace

TEST(WorkerWake, AWorkerWithNothingToDoSleepsUntilNotified) {
    dish_hot::WorkerWake wake;
    std::atomic<bool> work{false};
    std::atomic<bool> woke{false};
    std::thread worker([&] {
        wake.sleepUnless([&] { return work.load(std::memory_order_seq_cst); });
        woke = true;
    });
    std::this_thread::sleep_for(kStillAsleep);
    EXPECT_FALSE(woke.load());
    work.store(true, std::memory_order_seq_cst);
    wake.notify();
    EXPECT_TRUE(waitFor(woke));
    freeAndJoin(wake, woke, worker);
}

TEST(WorkerWake, WorkPublishedBeforeTheWorkerSleepsKeepsItAwake) {
    dish_hot::WorkerWake wake;
    std::atomic<bool> work{true};
    std::atomic<bool> woke{false};
    std::thread worker([&] {
        wake.sleepUnless([&] { return work.load(std::memory_order_seq_cst); });
        woke = true;
    });
    EXPECT_TRUE(waitFor(woke));
    worker.join();
}

// A producer that notifies after every push and a worker that sleeps whenever the ring is empty:
// every item is taken, so no notify was ever lost between the worker's check and its sleep.
TEST(WorkerWake, AStreamOfNotifiesNeverLeavesAnItemWaiting) {
    dish_hot::WorkerWake wake;
    dish_hot::MpmcRing<uint32_t, 16> ring;
    std::atomic<uint32_t> taken{0};
    std::atomic<bool> done{false};
    std::thread worker([&] {
        uint32_t item = 0;
        while (taken.load() < kItems) {
            wake.sleepUnless([&] { return !ring.empty(); });
            while (ring.tryPop(item)) taken++;
        }
        done = true;
    });
    for (uint32_t i = 0; i < kItems; i++) {
        ring.pushWaiting(i);
        wake.notify();
    }
    EXPECT_TRUE(waitFor(done)) << "a notify was lost";
    freeAndJoin(wake, done, worker);
    EXPECT_EQ(taken.load(), kItems);
}
