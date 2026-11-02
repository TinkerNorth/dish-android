// SPDX-License-Identifier: LGPL-3.0-or-later

#include "bridge_queue.h"

#include <gtest/gtest.h>

#include <atomic>
#include <chrono>
#include <cstdint>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

namespace {

using Clock = std::chrono::steady_clock;

constexpr auto kStillWaiting = std::chrono::milliseconds(100);
constexpr auto kWaitLimit = std::chrono::seconds(5);
constexpr int kProducers = 3;
constexpr uint16_t kReportsPerProducer = 5000;

dish_slots::BridgeReport reportOf(const int32_t controller, const uint16_t buttons) {
    dish_slots::BridgeReport r;
    r.kind = dish_slots::SLOT_MOONLIGHT;
    r.connection = 7;
    r.controllerNumber = controller;
    r.wButtons = buttons;
    return r;
}

// What the bridge thread was handed, in order: "m<device>" for a mute edge, "r<buttons>" for a
// report.
class Recording final : public dish_bridge::BridgeConsumer {
  public:
    void report(const dish_slots::BridgeReport& r) override {
        std::lock_guard<std::mutex> lock(mtx_);
        reports.push_back(r);
        order.push_back("r" + std::to_string(r.wButtons));
    }
    void micMute(const int32_t deviceId, const bool muted) override {
        std::lock_guard<std::mutex> lock(mtx_);
        order.push_back("m" + std::to_string(deviceId) + (muted ? "+" : "-"));
    }

    size_t reportCount() {
        std::lock_guard<std::mutex> lock(mtx_);
        return reports.size();
    }

    std::vector<dish_slots::BridgeReport> reports;
    std::vector<std::string> order;

  private:
    std::mutex mtx_;
};

} // namespace

TEST(BridgeQueue, AMuteEdgeIsHandedOverBeforeTheReportsQueuedWithIt) {
    dish_bridge::BridgeQueue queue;
    Recording consumer;
    queue.push(reportOf(0, 1));
    queue.pushMicMute(-1000, true);
    queue.push(reportOf(0, 2));
    EXPECT_EQ(queue.drain(consumer), 3u);
    EXPECT_EQ(consumer.order, (std::vector<std::string>{"m-1000+", "r1", "r2"}));
}

TEST(BridgeQueue, AFullQueueDropsItsOldestReportAndCountsIt) {
    dish_bridge::BridgeQueue queue;
    Recording consumer;
    for (uint16_t i = 0; i < dish_bridge::BridgeQueue::REPORTS + 2; i++) queue.push(reportOf(0, i));
    EXPECT_EQ(queue.droppedReports(), 2u);
    queue.drain(consumer);
    ASSERT_EQ(consumer.reports.size(), dish_bridge::BridgeQueue::REPORTS);
    EXPECT_EQ(consumer.reports.front().wButtons, 2);
    EXPECT_EQ(consumer.reports.back().wButtons, dish_bridge::BridgeQueue::REPORTS + 1);
}

TEST(BridgeQueue, AMuteEdgeIsNeverDroppedItsPresserWaitsForRoom) {
    dish_bridge::BridgeQueue queue;
    Recording consumer;
    for (int32_t i = 0; i < (int32_t)dish_bridge::BridgeQueue::MIC_MUTE_EDGES; i++) {
        queue.pushMicMute(i, true);
    }
    std::atomic<bool> pushed{false};
    std::thread presser([&] {
        queue.pushMicMute(99, false);
        pushed = true;
    });
    std::this_thread::sleep_for(kStillWaiting);
    EXPECT_FALSE(pushed.load());
    queue.drain(consumer);
    presser.join();
    queue.drain(consumer);
    ASSERT_EQ(consumer.order.size(), dish_bridge::BridgeQueue::MIC_MUTE_EDGES + 1);
    EXPECT_EQ(consumer.order.back(), "m99-");
}

TEST(BridgeQueue, AStoppedQueueStillHandsOverWhatItHoldsThenEnds) {
    dish_bridge::BridgeQueue queue;
    Recording consumer;
    queue.push(reportOf(0, 5));
    queue.stop();
    EXPECT_TRUE(queue.waitForWork());
    queue.drain(consumer);
    EXPECT_FALSE(queue.waitForWork());
    EXPECT_EQ(consumer.order, std::vector<std::string>{"r5"});
}

// The bridge thread as the JNI layer runs it, fed by several input threads at once: every report
// arrives, each producer's in the order it pushed them.
TEST(BridgeQueue, TheBridgeThreadGetsEveryProducersReportsInTheirOrder) {
    dish_bridge::BridgeQueue queue;
    Recording consumer;
    std::thread bridge([&] {
        while (queue.waitForWork()) queue.drain(consumer);
    });
    std::vector<std::thread> producers;
    for (int p = 0; p < kProducers; p++) {
        producers.emplace_back([&queue, p] {
            for (uint16_t i = 0; i < kReportsPerProducer; i++) {
                queue.push(reportOf(p, i));
                if (i % 64 == 63) std::this_thread::yield();
            }
        });
    }
    for (std::thread& producer : producers) producer.join();
    const auto deadline = Clock::now() + kWaitLimit;
    const uint64_t expected = (uint64_t)kProducers * kReportsPerProducer - queue.droppedReports();
    while (consumer.reportCount() < expected && Clock::now() < deadline) std::this_thread::yield();
    queue.stop();
    bridge.join();
    EXPECT_EQ(consumer.reports.size(), expected);
    std::vector<int> last(kProducers, -1);
    for (const dish_slots::BridgeReport& r : consumer.reports) {
        ASSERT_GT((int)r.wButtons, last[r.controllerNumber]) << "producer " << r.controllerNumber;
        last[r.controllerNumber] = r.wButtons;
    }
}
