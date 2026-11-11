// SPDX-License-Identifier: LGPL-3.0-or-later

#include "usb_output.h"

#include <gtest/gtest.h>

#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

namespace {

using Clock = std::chrono::steady_clock;

constexpr int32_t kPad = -1000;
constexpr int32_t kOtherPad = -1001;
constexpr auto kWaitLimit = std::chrono::seconds(5);
constexpr auto kQuiet = std::chrono::milliseconds(100);
constexpr int kPosts = 1000;
constexpr auto kPostBudget = std::chrono::milliseconds(50);
constexpr uint32_t kRefreshEveryMs = 20;
constexpr uint32_t kRefreshLongAfterMs = 60000;
constexpr size_t kRefreshes = 3;
constexpr auto kPromptWrite = std::chrono::seconds(1);

// Records every write in order, and can hold the worker inside its next rumble write until the
// test lets it go: an OUT transfer stuck for its whole timeout.
class FakeDevice final : public dish_usb::OutputDevice {
  public:
    void rumble(const uint16_t strong, const uint16_t weak) override {
        std::unique_lock<std::mutex> lock(mtx_);
        writes_.push_back("rumble " + std::to_string(strong) + "/" + std::to_string(weak));
        cv_.notify_all();
        if (!holdNextRumble_) return;
        holdNextRumble_ = false;
        holding_ = true;
        cv_.notify_all();
        cv_.wait(lock, [this] { return released_; });
    }
    void triggerRumble(const uint16_t left, const uint16_t right) override {
        record("trigger " + std::to_string(left) + "/" + std::to_string(right));
    }
    void lightbar(const uint8_t r, const uint8_t g, const uint8_t b) override {
        record("lightbar " + std::to_string(r) + "," + std::to_string(g) + "," + std::to_string(b));
    }
    void playerLeds(const uint8_t ledMask) override { record("leds " + std::to_string(ledMask)); }
    void triggerEffects(const uint8_t* left, const uint8_t* right) override {
        record("effects " + std::to_string(left[0]) + "/" + std::to_string(right[0]));
    }
    void micLed(const uint8_t state) override { record("mic " + std::to_string(state)); }
    void ack(const uint8_t* bytes, size_t) override { record("ack " + std::to_string(bytes[0])); }
    void reconnected() override { record("reconnected"); }

    uint32_t refresh(int64_t) override {
        std::lock_guard<std::mutex> lock(mtx_);
        refreshCalls_++;
        cv_.notify_all();
        return refreshEveryMs_;
    }

    void refreshEvery(const uint32_t ms) {
        std::lock_guard<std::mutex> lock(mtx_);
        refreshEveryMs_ = ms;
    }

    size_t refreshCalls() {
        std::lock_guard<std::mutex> lock(mtx_);
        return refreshCalls_;
    }

    bool waitForRefreshCalls(const size_t calls) {
        std::unique_lock<std::mutex> lock(mtx_);
        return cv_.wait_for(lock, kWaitLimit, [&] { return refreshCalls_ >= calls; });
    }

    void holdNextRumble() {
        std::lock_guard<std::mutex> lock(mtx_);
        holdNextRumble_ = true;
    }

    bool waitUntilHolding() {
        std::unique_lock<std::mutex> lock(mtx_);
        return cv_.wait_for(lock, kWaitLimit, [this] { return holding_; });
    }

    void letGo() {
        std::lock_guard<std::mutex> lock(mtx_);
        released_ = true;
        cv_.notify_all();
    }

    // Waits until the writes include `last`, then answers them all.
    std::vector<std::string> writesUpTo(const std::string& last) {
        std::unique_lock<std::mutex> lock(mtx_);
        cv_.wait_for(lock, kWaitLimit, [&] { return !writes_.empty() && writes_.back() == last; });
        return writes_;
    }

    std::vector<std::string> writes() {
        std::lock_guard<std::mutex> lock(mtx_);
        return writes_;
    }

  private:
    void record(std::string write) {
        std::lock_guard<std::mutex> lock(mtx_);
        writes_.push_back(std::move(write));
        cv_.notify_all();
    }

    std::mutex mtx_;
    std::condition_variable cv_;
    std::vector<std::string> writes_;
    bool holdNextRumble_ = false;
    bool holding_ = false;
    bool released_ = false;
    uint32_t refreshEveryMs_ = usbparsers::NO_REFRESH;
    size_t refreshCalls_ = 0;
};

const uint8_t kAckA[1] = {1};
const uint8_t kAckB[1] = {2};
const uint8_t kAckC[1] = {3};

} // namespace

TEST(OutputWorker, EachKindIsWrittenForThePadItWasPostedFor) {
    FakeDevice device;
    dish_usb::OutputWorker worker;
    worker.start(kPad, device);
    const uint8_t left[dish_usb::TRIGGER_EFFECT_BYTES] = {7};
    const uint8_t right[dish_usb::TRIGGER_EFFECT_BYTES] = {9};
    worker.postRumble(kPad, 100, 200);
    EXPECT_EQ(device.writesUpTo("rumble 100/200").back(), "rumble 100/200");
    worker.postTriggerRumble(kPad, 3, 4);
    EXPECT_EQ(device.writesUpTo("trigger 3/4").back(), "trigger 3/4");
    worker.postLightbar(kPad, 1, 2, 3);
    EXPECT_EQ(device.writesUpTo("lightbar 1,2,3").back(), "lightbar 1,2,3");
    worker.postPlayerLeds(kPad, 5);
    EXPECT_EQ(device.writesUpTo("leds 5").back(), "leds 5");
    worker.postTriggerEffects(kPad, left, right);
    EXPECT_EQ(device.writesUpTo("effects 7/9").back(), "effects 7/9");
    worker.postMicLed(kPad, 2);
    EXPECT_EQ(device.writesUpTo("mic 2").back(), "mic 2");
    worker.stop();
}

// The OUT endpoint stalls for the whole of a write: the producer still posts at once, and once the
// write returns the worker writes only the newest value of each kind.
TEST(OutputWorker, ASlowWriteNeverDelaysTheProducerAndTheNewestStateWins) {
    FakeDevice device;
    dish_usb::OutputWorker worker;
    worker.start(kPad, device);
    device.holdNextRumble();
    worker.postRumble(kPad, 1, 1);
    ASSERT_TRUE(device.waitUntilHolding());
    const Clock::time_point start = Clock::now();
    for (int i = 0; i < kPosts; i++) {
        worker.postRumble(kPad, (uint16_t)i, (uint16_t)i);
        worker.postLightbar(kPad, (uint8_t)i, 0, 0);
    }
    EXPECT_LT(Clock::now() - start, kPostBudget);
    device.letGo();
    const std::vector<std::string> writes = device.writesUpTo("lightbar 231,0,0");
    const std::vector<std::string> expected = {"rumble 1/1", "rumble 999/999", "lightbar 231,0,0"};
    EXPECT_EQ(writes, expected);
    worker.stop();
}

TEST(OutputWorker, AcksAreWrittenInOrderAndNeverCoalesced) {
    FakeDevice device;
    dish_usb::OutputWorker worker;
    worker.start(kPad, device);
    device.holdNextRumble();
    worker.postRumble(kPad, 1, 1);
    ASSERT_TRUE(device.waitUntilHolding());
    worker.postAck(kPad, kAckA, sizeof(kAckA));
    worker.postAck(kPad, kAckB, sizeof(kAckB));
    worker.postAck(kPad, kAckC, sizeof(kAckC));
    device.letGo();
    const std::vector<std::string> writes = device.writesUpTo("ack 3");
    EXPECT_EQ(writes, (std::vector<std::string>{"rumble 1/1", "ack 1", "ack 2", "ack 3"}));
    worker.stop();
}

TEST(OutputWorker, AFullAckRingDropsItsOldestAck) {
    FakeDevice device;
    dish_usb::OutputWorker worker;
    worker.start(kPad, device);
    device.holdNextRumble();
    worker.postRumble(kPad, 1, 1);
    ASSERT_TRUE(device.waitUntilHolding());
    for (size_t i = 0; i < dish_usb::OutputWorker::ACKS + 2; i++) {
        const uint8_t ack[1] = {(uint8_t)i};
        worker.postAck(kPad, ack, sizeof(ack));
    }
    EXPECT_EQ(worker.droppedAcks(), 2u);
    device.letGo();
    const std::string last = "ack " + std::to_string(dish_usb::OutputWorker::ACKS + 1);
    const std::vector<std::string> writes = device.writesUpTo(last);
    ASSERT_EQ(writes.size(), 1 + dish_usb::OutputWorker::ACKS);
    EXPECT_EQ(writes[1], "ack 2");
    worker.stop();
}

TEST(OutputWorker, AValuePostedForAnotherPadIsNotWritten) {
    FakeDevice device;
    dish_usb::OutputWorker worker;
    worker.start(kPad, device);
    worker.postRumble(kOtherPad, 5, 5);
    worker.postAck(kOtherPad, kAckA, sizeof(kAckA));
    worker.postMicLed(kPad, 1);
    EXPECT_EQ(device.writesUpTo("mic 1"), std::vector<std::string>{"mic 1"});
    worker.stop();
}

TEST(OutputWorker, NothingPostedAfterStopIsWrittenAndARestartServesTheNewPadOnly) {
    FakeDevice device;
    dish_usb::OutputWorker worker;
    worker.start(kPad, device);
    worker.stop();
    worker.postRumble(kPad, 9, 9);
    std::this_thread::sleep_for(kQuiet);
    EXPECT_TRUE(device.writes().empty());
    worker.start(kOtherPad, device);
    worker.postLightbar(kOtherPad, 4, 5, 6);
    EXPECT_EQ(device.writesUpTo("lightbar 4,5,6"), std::vector<std::string>{"lightbar 4,5,6"});
    worker.stop();
}

TEST(OutputWorker, AReconnectIsWrittenForThePadItWasPostedFor) {
    FakeDevice device;
    dish_usb::OutputWorker worker;
    worker.start(kPad, device);
    worker.postReconnected(kOtherPad);
    worker.postReconnected(kPad);
    EXPECT_EQ(device.writesUpTo("reconnected"), std::vector<std::string>{"reconnected"});
    worker.stop();
}

// The Steam Controller (2026) forgets lizard mode and its rumble unless they are re-sent, so a
// device that asks is refreshed on its own cadence with nothing posted.
TEST(OutputWorker, ADeviceThatAsksIsRefreshedOnItsCadenceWithNothingPosted) {
    FakeDevice device;
    device.refreshEvery(kRefreshEveryMs);
    dish_usb::OutputWorker worker;
    worker.start(kPad, device);
    EXPECT_TRUE(device.waitForRefreshCalls(kRefreshes));
    worker.stop();
}

// Every other family wants no refresh, and its worker sleeps until something is posted: asked
// once when it starts and once after each write, never on a timer.
TEST(OutputWorker, ADeviceThatWantsNoRefreshIsNeverWokenForOne) {
    FakeDevice device;
    dish_usb::OutputWorker worker;
    worker.start(kPad, device);
    ASSERT_TRUE(device.waitForRefreshCalls(1));
    std::this_thread::sleep_for(kQuiet);
    EXPECT_EQ(1u, device.refreshCalls());
    worker.postMicLed(kPad, 1);
    EXPECT_TRUE(device.waitForRefreshCalls(2));
    std::this_thread::sleep_for(kQuiet);
    EXPECT_EQ(2u, device.refreshCalls());
    EXPECT_EQ(device.writes(), std::vector<std::string>{"mic 1"});
    worker.stop();
}

TEST(OutputWorker, APostIsWrittenAtOnceWhileARefreshIsFarOff) {
    FakeDevice device;
    device.refreshEvery(kRefreshLongAfterMs);
    dish_usb::OutputWorker worker;
    worker.start(kPad, device);
    ASSERT_TRUE(device.waitForRefreshCalls(1));
    const Clock::time_point posted = Clock::now();
    worker.postRumble(kPad, 7, 8);
    EXPECT_EQ(device.writesUpTo("rumble 7/8"), std::vector<std::string>{"rumble 7/8"});
    EXPECT_LT(Clock::now() - posted, kPromptWrite);
    worker.stop();
}
