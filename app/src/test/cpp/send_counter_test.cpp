// SPDX-License-Identifier: LGPL-3.0-or-later

#include "send_counter.h"

#include <gtest/gtest.h>

#include <atomic>
#include <cstdint>
#include <vector>

// The send path (sendEncrypted) draws every wire counter through
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

TEST(SendCounterView, RestartsAtOneAfterARekeyReset) {
    std::atomic<uint64_t> counter{0x100000007ull};
    counter.store(1); // setConnectionParams: counters restart per (token, key)
    EXPECT_EQ(dish_counter::sendCounterView(counter), 1u);
    uint32_t ctr = 0;
    EXPECT_TRUE(dish_counter::acquireSendCounter(counter, &ctr));
    EXPECT_EQ(ctr, 1u);
}
