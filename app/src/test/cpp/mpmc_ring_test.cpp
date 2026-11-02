// SPDX-License-Identifier: LGPL-3.0-or-later

#include "mpmc_ring.h"

#include <gtest/gtest.h>

#include <atomic>
#include <cstdint>
#include <thread>
#include <vector>

namespace {

struct Item {
    uint32_t producer = 0;
    uint32_t sequence = 0;
};

constexpr size_t kCapacity = 8;
constexpr int kProducers = 4;
constexpr uint32_t kItemsPerProducer = 20000;

using Ring = dish_hot::MpmcRing<Item, kCapacity>;

// What a consumer saw: per producer, the sequences in the order they arrived.
struct Seen {
    std::vector<std::vector<uint32_t>> byProducer = std::vector<std::vector<uint32_t>>(kProducers);
    uint64_t total = 0;
};

void expectEachProducersOrderKept(const Seen& seen) {
    for (int p = 0; p < kProducers; p++) {
        const std::vector<uint32_t>& sequences = seen.byProducer[p];
        for (size_t i = 1; i < sequences.size(); i++) {
            ASSERT_LT(sequences[i - 1], sequences[i]) << "producer " << p << " at " << i;
        }
    }
}

} // namespace

TEST(MpmcRing, HandsItemsOutInTheOrderTheyWentIn) {
    Ring ring;
    for (uint32_t i = 0; i < 3; i++) EXPECT_TRUE(ring.tryPush(Item{0, i}));
    Item out;
    for (uint32_t i = 0; i < 3; i++) {
        ASSERT_TRUE(ring.tryPop(out));
        EXPECT_EQ(out.sequence, i);
    }
    EXPECT_FALSE(ring.tryPop(out));
    EXPECT_TRUE(ring.empty());
}

TEST(MpmcRing, RefusesAPushWhenFullAndKeepsWorkingAcrossTheWrap) {
    Ring ring;
    for (uint32_t round = 0; round < 5; round++) {
        for (uint32_t i = 0; i < kCapacity; i++)
            ASSERT_TRUE(ring.tryPush(Item{0, round * 100 + i}));
        EXPECT_FALSE(ring.tryPush(Item{0, 999}));
        EXPECT_EQ(ring.depth(), kCapacity);
        Item out;
        for (uint32_t i = 0; i < kCapacity; i++) {
            ASSERT_TRUE(ring.tryPop(out));
            EXPECT_EQ(out.sequence, round * 100 + i);
        }
    }
    EXPECT_EQ(ring.pushed(), 5 * kCapacity);
}

TEST(MpmcRing, AFullRingDropsExactlyTheOldestToTakeANewItem) {
    Ring ring;
    for (uint32_t i = 0; i < kCapacity; i++) ASSERT_TRUE(ring.tryPush(Item{0, i}));
    EXPECT_EQ(ring.pushDroppingOldest(Item{0, 100}), 1u);
    EXPECT_EQ(ring.pushDroppingOldest(Item{0, 101}), 1u);
    Item out;
    std::vector<uint32_t> left;
    while (ring.tryPop(out)) left.push_back(out.sequence);
    const std::vector<uint32_t> expected = {2, 3, 4, 5, 6, 7, 100, 101};
    EXPECT_EQ(left, expected);
    EXPECT_EQ(ring.pushDroppingOldest(Item{0, 102}), 0u);
}

TEST(MpmcRing, ManyProducersAndOneConsumerLoseNothingAndKeepEachProducersOrder) {
    Ring ring;
    std::atomic<int> producing{kProducers};
    std::vector<std::thread> producers;
    for (int p = 0; p < kProducers; p++) {
        producers.emplace_back([&ring, &producing, p] {
            for (uint32_t i = 0; i < kItemsPerProducer; i++) {
                ring.pushWaiting(Item{static_cast<uint32_t>(p), i});
            }
            producing--;
        });
    }
    Seen seen;
    Item out;
    while (producing.load() > 0 || !ring.empty()) {
        if (!ring.tryPop(out)) {
            std::this_thread::yield();
            continue;
        }
        seen.byProducer[out.producer].push_back(out.sequence);
        seen.total++;
    }
    for (std::thread& producer : producers) producer.join();
    EXPECT_EQ(seen.total, (uint64_t)kProducers * kItemsPerProducer);
    for (int p = 0; p < kProducers; p++) {
        EXPECT_EQ(seen.byProducer[p].size(), kItemsPerProducer) << "producer " << p;
    }
    expectEachProducersOrderKept(seen);
}

TEST(MpmcRing, ProducersDroppingTheOldestRaceTheConsumerWithoutHandingAnythingOutTwice) {
    Ring ring;
    std::atomic<uint64_t> dropped{0};
    std::atomic<int> producing{kProducers};
    std::vector<std::thread> producers;
    for (int p = 0; p < kProducers; p++) {
        producers.emplace_back([&ring, &dropped, &producing, p] {
            for (uint32_t i = 0; i < kItemsPerProducer; i++) {
                dropped += ring.pushDroppingOldest(Item{static_cast<uint32_t>(p), i});
            }
            producing--;
        });
    }
    Seen seen;
    Item out;
    while (producing.load() > 0 || !ring.empty()) {
        if (ring.tryPop(out)) {
            seen.byProducer[out.producer].push_back(out.sequence);
            seen.total++;
        }
    }
    for (std::thread& producer : producers) producer.join();
    EXPECT_EQ(seen.total + dropped.load(), (uint64_t)kProducers * kItemsPerProducer);
    EXPECT_EQ(ring.pushed(), (uint64_t)kProducers * kItemsPerProducer);
    expectEachProducersOrderKept(seen);
}
