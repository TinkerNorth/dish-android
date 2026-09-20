// SPDX-License-Identifier: LGPL-3.0-or-later

#include <gtest/gtest.h>

#include <atomic>
#include <chrono>
#include <functional>
#include <thread>
#include <vector>

#include "heartbeat_thread.h"

namespace {

using dish::HeartbeatThread;

void spin(HeartbeatThread& hb, std::atomic<int>& exits) {
    while (hb.running()) std::this_thread::sleep_for(std::chrono::milliseconds(1));
    exits.fetch_add(1);
}

bool waitFor(const std::function<bool()>& cond, int timeoutMs = 5000) {
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(timeoutMs);
    while (!cond()) {
        if (std::chrono::steady_clock::now() > deadline) return false;
        std::this_thread::sleep_for(std::chrono::milliseconds(1));
    }
    return true;
}

TEST(HeartbeatThread, StopWithoutStartIsANoOp) {
    HeartbeatThread hb;
    EXPECT_FALSE(hb.running());
    hb.stop();
    hb.stop();
    EXPECT_FALSE(hb.running());
}

TEST(HeartbeatThread, StartRunsTheBodyUntilStop) {
    HeartbeatThread hb;
    std::atomic<int> exits{0};
    std::atomic<bool> entered{false};
    ASSERT_TRUE(hb.start([&] {
        entered.store(true);
        spin(hb, exits);
    }));
    EXPECT_TRUE(hb.running());
    ASSERT_TRUE(waitFor([&] { return entered.load(); }));
    EXPECT_EQ(0, exits.load());
    hb.stop();
    EXPECT_FALSE(hb.running());
    EXPECT_EQ(1, exits.load());
}

TEST(HeartbeatThread, SecondStartWhileRunningIsRefused) {
    HeartbeatThread hb;
    std::atomic<int> exits{0};
    std::atomic<int> starts{0};
    ASSERT_TRUE(hb.start([&] {
        starts.fetch_add(1);
        spin(hb, exits);
    }));
    EXPECT_FALSE(hb.start([&] {
        starts.fetch_add(1);
        spin(hb, exits);
    }));
    hb.stop();
    EXPECT_EQ(1, starts.load());
    EXPECT_EQ(1, exits.load());
}

TEST(HeartbeatThread, RestartAfterStopRunsAgain) {
    HeartbeatThread hb;
    std::atomic<int> exits{0};
    ASSERT_TRUE(hb.start([&] { spin(hb, exits); }));
    hb.stop();
    EXPECT_EQ(1, exits.load());
    ASSERT_TRUE(hb.start([&] { spin(hb, exits); }));
    EXPECT_TRUE(hb.running());
    hb.stop();
    EXPECT_EQ(2, exits.load());
}

TEST(HeartbeatThread, ConcurrentStopsJoinExactlyOnce) {
    for (int round = 0; round < 100; ++round) {
        HeartbeatThread hb;
        std::atomic<int> exits{0};
        ASSERT_TRUE(hb.start([&] { spin(hb, exits); }));
        std::vector<std::thread> stoppers;
        for (int i = 0; i < 8; ++i) stoppers.emplace_back([&] { hb.stop(); });
        for (auto& t : stoppers) t.join();
        EXPECT_FALSE(hb.running());
        EXPECT_EQ(1, exits.load());
    }
}

TEST(HeartbeatThread, StopAndCloseFromTwoOwnersNeverDoubleJoin) {
    for (int round = 0; round < 100; ++round) {
        HeartbeatThread hb;
        std::atomic<int> exits{0};
        ASSERT_TRUE(hb.start([&] { spin(hb, exits); }));
        std::thread disconnecting([&] { hb.stop(); });
        std::thread closing([&] { hb.stop(); });
        disconnecting.join();
        closing.join();
        EXPECT_EQ(1, exits.load());
    }
}

TEST(HeartbeatThread, StartsRacingStopsNeverThrow) {
    HeartbeatThread hb;
    std::atomic<int> exits{0};
    std::atomic<bool> go{false};
    std::vector<std::thread> workers;
    for (int i = 0; i < 8; ++i) {
        workers.emplace_back([&, i] {
            while (!go.load()) std::this_thread::yield();
            for (int n = 0; n < 200; ++n) {
                if ((n + i) % 2 == 0) {
                    hb.start([&] { spin(hb, exits); });
                } else {
                    hb.stop();
                }
            }
        });
    }
    go.store(true);
    for (auto& t : workers) t.join();
    hb.stop();
    EXPECT_FALSE(hb.running());
}

TEST(HeartbeatThread, StopFromInsideTheBodyDoesNotDeadlock) {
    HeartbeatThread hb;
    std::atomic<bool> done{false};
    ASSERT_TRUE(hb.start([&] {
        hb.stop();
        done.store(true);
    }));
    ASSERT_TRUE(waitFor([&] { return done.load(); }));
    EXPECT_FALSE(hb.running());
    hb.stop();
    ASSERT_TRUE(hb.start([&] {}));
    hb.stop();
}

TEST(HeartbeatThread, DestructorStopsARunningLoop) {
    std::atomic<int> exits{0};
    {
        HeartbeatThread hb;
        ASSERT_TRUE(hb.start([&] { spin(hb, exits); }));
    }
    EXPECT_EQ(1, exits.load());
}

TEST(HeartbeatThread, ABodyThatReturnsOnItsOwnCanBeRestartedAfterStop) {
    HeartbeatThread hb;
    std::atomic<int> runs{0};
    ASSERT_TRUE(hb.start([&] { runs.fetch_add(1); }));
    ASSERT_TRUE(waitFor([&] { return runs.load() == 1; }));
    hb.stop();
    ASSERT_TRUE(hb.start([&] { runs.fetch_add(1); }));
    ASSERT_TRUE(waitFor([&] { return runs.load() == 2; }));
    hb.stop();
}

} // namespace
