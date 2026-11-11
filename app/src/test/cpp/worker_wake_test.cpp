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
constexpr uint32_t kShortSleepMs = 60;
constexpr uint32_t kLongSleepMs = 60000;

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

// The output worker's refresh cadence: with nothing posted, a timed sleep still ends once its time
// is up.
TEST(WorkerWake, ATimedSleepWithNothingPostedEndsWhenItsTimeIsUp) {
    dish_hot::WorkerWake wake;
    std::atomic<bool> woke{false};
    std::atomic<int64_t> sleptMs{0};
    std::thread worker([&] {
        const Clock::time_point start = Clock::now();
        wake.sleepUnlessFor([] { return false; }, kShortSleepMs);
        const auto slept =
            std::chrono::duration_cast<std::chrono::milliseconds>(Clock::now() - start);
        sleptMs = slept.count();
        woke = true;
    });
    EXPECT_TRUE(waitFor(woke));
    freeAndJoin(wake, woke, worker);
    EXPECT_GE(sleptMs.load(), (int64_t)kShortSleepMs / 2);
}

TEST(WorkerWake, ANotifyEndsATimedSleepBeforeItsTimeIsUp) {
    dish_hot::WorkerWake wake;
    std::atomic<bool> work{false};
    std::atomic<bool> woke{false};
    std::thread worker([&] {
        wake.sleepUnlessFor([&] { return work.load(std::memory_order_seq_cst); }, kLongSleepMs);
        woke = true;
    });
    std::this_thread::sleep_for(kStillAsleep);
    EXPECT_FALSE(woke.load());
    work.store(true, std::memory_order_seq_cst);
    wake.notify();
    EXPECT_TRUE(waitFor(woke));
    freeAndJoin(wake, woke, worker);
}

TEST(WorkerWake, WorkPublishedBeforeATimedSleepKeepsTheWorkerAwake) {
    dish_hot::WorkerWake wake;
    std::atomic<bool> woke{false};
    std::thread worker([&] {
        wake.sleepUnlessFor([] { return true; }, kLongSleepMs);
        woke = true;
    });
    EXPECT_TRUE(waitFor(woke));
    worker.join();
}
