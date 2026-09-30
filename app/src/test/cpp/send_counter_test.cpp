// SPDX-License-Identifier: LGPL-3.0-or-later

#include "send_counter.h"

#include <gtest/gtest.h>

#include <atomic>
#include <chrono>
#include <cstdint>
#include <mutex>
#include <thread>
#include <vector>

// The send path (stageDatagram) draws every wire counter through
// acquireSendCounter and returns without sending when it refuses, so these
// pins ARE the no-nonce-reuse guarantee: no wrapped counter can reach the
// wire because no wrapped counter is ever handed out.

TEST(AcquireSendCounter, DrawsSequentialValuesStartingAtSessionInitial) {
    std::atomic<uint64_t> counter{1};
    uint32_t ctr = 0;
    ASSERT_TRUE(dish_counter::acquireSendCounter(counter, &ctr));
    EXPECT_EQ(ctr, 1u);
    ASSERT_TRUE(dish_counter::acquireSendCounter(counter, &ctr));
    EXPECT_EQ(ctr, 2u);
    ASSERT_TRUE(dish_counter::acquireSendCounter(counter, &ctr));
    EXPECT_EQ(ctr, 3u);
}

TEST(AcquireSendCounter, UsesTheFullWireSpaceThenGoesSilent) {
    std::atomic<uint64_t> counter{0xFFFFFFFEull};
    uint32_t ctr = 0;
    ASSERT_TRUE(dish_counter::acquireSendCounter(counter, &ctr));
    EXPECT_EQ(ctr, 0xFFFFFFFEu);
    ASSERT_TRUE(dish_counter::acquireSendCounter(counter, &ctr));
    EXPECT_EQ(ctr, 0xFFFFFFFFu); // last usable wire value
    EXPECT_FALSE(dish_counter::acquireSendCounter(counter, &ctr));
}

TEST(AcquireSendCounter, NeverRepeatsAValueUnderOneKeyAcrossExhaustion) {
    std::atomic<uint64_t> counter{0xFFFFFFFDull};
    std::vector<uint32_t> drawn;
    for (int i = 0; i < 8; i++) {
        uint32_t ctr = 0;
        if (dish_counter::acquireSendCounter(counter, &ctr)) drawn.push_back(ctr);
    }
    // The unguarded u32 fetch_add would have kept drawing here: 0, 1, 2 …
    // reusing (key, nonce) pairs from the start of the session.
    ASSERT_EQ(drawn.size(), 3u);
    for (size_t i = 1; i < drawn.size(); i++) EXPECT_GT(drawn[i], drawn[i - 1]);
}

TEST(AcquireSendCounter, RefusalLeavesTheOutputUntouched) {
    std::atomic<uint64_t> counter{0x100000000ull};
    uint32_t ctr = 0xDEADBEEFu;
    EXPECT_FALSE(dish_counter::acquireSendCounter(counter, &ctr));
    EXPECT_EQ(ctr, 0xDEADBEEFu);
}

TEST(SendCounterView, ReportsTheLiveValueBelowExhaustion) {
    std::atomic<uint64_t> counter{5};
    EXPECT_EQ(dish_counter::sendCounterView(counter), 5u);
    counter.store(0xF0000000ull);
    EXPECT_EQ(dish_counter::sendCounterView(counter), 0xF0000000u);
}

TEST(SendCounterView, ClampsAtWireMaxPastExhaustionSoRekeyStaysDue) {
    std::atomic<uint64_t> counter{0xFFFFFFFFull};
    uint32_t ctr = 0;
    ASSERT_TRUE(dish_counter::acquireSendCounter(counter, &ctr));
    // Keep drawing past exhaustion: the view must clamp at the wire max, never
    // wrap back under the 0xF0000000 re-key threshold the Kotlin poll compares
    // against.
    for (int i = 0; i < 4; i++) {
        EXPECT_FALSE(dish_counter::acquireSendCounter(counter, &ctr));
        EXPECT_EQ(dish_counter::sendCounterView(counter), 0xFFFFFFFFu);
    }
}

namespace {

// Long enough for a sender that is not blocked to have drawn its counter many times over.
constexpr std::chrono::milliseconds kBlockedSenderWait{200};
constexpr int kConcurrentSenders = 4;
constexpr int kDatagramsPerSender = 5000;

// A sender that takes its turn and counter, puts the counter on the wire and gives the turn back.
void sendInTurn(std::mutex& turnMtx, std::atomic<uint64_t>& counter, std::vector<uint32_t>& wire) {
    for (int i = 0; i < kDatagramsPerSender; i++) {
        std::unique_lock<std::mutex> turn;
        uint32_t ctr = 0;
        if (!dish_counter::takeTurnAndCounter(turnMtx, counter, turn, &ctr)) return;
        wire.push_back(ctr);
    }
}

bool isFreeFromAnotherThread(std::mutex& turnMtx) {
    bool free = false;
    std::thread probe([&turnMtx, &free] {
        free = turnMtx.try_lock();
        if (free) turnMtx.unlock();
    });
    probe.join();
    return free;
}

} // namespace

TEST(TakeTurnAndCounter, HoldsTheTurnWithTheCounterItDrew) {
    std::mutex turnMtx;
    std::atomic<uint64_t> counter{1};
    std::unique_lock<std::mutex> turn;
    uint32_t ctr = 0;
    ASSERT_TRUE(dish_counter::takeTurnAndCounter(turnMtx, counter, turn, &ctr));
    EXPECT_EQ(ctr, 1u);
    EXPECT_TRUE(turn.owns_lock());
    EXPECT_FALSE(isFreeFromAnotherThread(turnMtx));
    turn.unlock();
    EXPECT_TRUE(isFreeFromAnotherThread(turnMtx));
}

TEST(TakeTurnAndCounter, ASecondSenderDrawsNoCounterUntilTheFirstGivesItsTurnBack) {
    std::mutex turnMtx;
    std::atomic<uint64_t> counter{1};
    std::unique_lock<std::mutex> first;
    uint32_t firstCtr = 0;
    ASSERT_TRUE(dish_counter::takeTurnAndCounter(turnMtx, counter, first, &firstCtr));

    uint32_t secondCtr = 0;
    std::thread second([&turnMtx, &counter, &secondCtr] {
        std::unique_lock<std::mutex> turn;
        dish_counter::takeTurnAndCounter(turnMtx, counter, turn, &secondCtr);
    });
    std::this_thread::sleep_for(kBlockedSenderWait);
    EXPECT_EQ(counter.load(), 2u);
    first.unlock();
    second.join();
    EXPECT_EQ(firstCtr, 1u);
    EXPECT_EQ(secondCtr, 2u);
}

TEST(TakeTurnAndCounter, AnExhaustedCounterGivesTheTurnBack) {
    std::mutex turnMtx;
    std::atomic<uint64_t> counter{dish_counter::kCounterMaxWire + 1};
    std::unique_lock<std::mutex> turn;
    uint32_t ctr = 0xDEADBEEFu;
    EXPECT_FALSE(dish_counter::takeTurnAndCounter(turnMtx, counter, turn, &ctr));
    EXPECT_EQ(ctr, 0xDEADBEEFu);
    EXPECT_FALSE(turn.owns_lock());
    EXPECT_TRUE(isFreeFromAnotherThread(turnMtx));
}

TEST(TakeTurnAndCounter, ConcurrentSendersPutTheirCountersOnTheWireInOrder) {
    // The satellite drops a datagram whose counter is not above the last it accepted from the
    // session, so a counter the wire carries out of order is a lost report.
    std::mutex turnMtx;
    std::atomic<uint64_t> counter{1};
    std::vector<uint32_t> wire;
    wire.reserve(kConcurrentSenders * kDatagramsPerSender);
    std::vector<std::thread> senders;
    for (int i = 0; i < kConcurrentSenders; i++) {
        senders.emplace_back(sendInTurn, std::ref(turnMtx), std::ref(counter), std::ref(wire));
    }
    for (std::thread& sender : senders) sender.join();
    ASSERT_EQ(wire.size(), (size_t)(kConcurrentSenders * kDatagramsPerSender));
    for (size_t i = 1; i < wire.size(); i++) ASSERT_EQ(wire[i], wire[i - 1] + 1) << "at " << i;
}

TEST(SendCounterView, RestartsAtOneAfterARekeyReset) {
    std::atomic<uint64_t> counter{0x100000007ull};
    counter.store(1); // setConnectionParams: counters restart per (token, key)
    EXPECT_EQ(dish_counter::sendCounterView(counter), 1u);
    uint32_t ctr = 0;
    EXPECT_TRUE(dish_counter::acquireSendCounter(counter, &ctr));
    EXPECT_EQ(ctr, 1u);
}
