// SPDX-License-Identifier: LGPL-3.0-or-later

#include "bridge_connection_ids.h"

#include <gtest/gtest.h>

#include <memory>
#include <string>
#include <vector>

using dish_bridge::BridgeConnectionId;

namespace {

// A ref is a positive number; 0 is none.
using FakeRef = int;

constexpr std::size_t kCapacity = 2;
constexpr int kReportsPerBind = 250;

// Stands in for the JVM: counts every string made, names each by the order it was made in, and
// records every release.
struct FakeRefs {
    std::vector<std::string> made;
    std::vector<FakeRef> released;
    bool failing = false;

    FakeRef make(const std::string& id) {
        if (failing) return 0;
        made.push_back(id);
        return static_cast<FakeRef>(made.size());
    }

    void release(const FakeRef ref) { released.push_back(ref); }
};

using Cache = dish_bridge::ConnectionRefCache<FakeRef, kCapacity>;

BridgeConnectionId idOf(const char* text) { return std::make_shared<const std::string>(text); }

} // namespace

TEST(ConnectionRefCache, EveryReportOfOneBindSharesTheOneRefMadeForIt) {
    Cache cache;
    FakeRefs refs;
    const BridgeConnectionId bound = idOf("moonlight:uid:0123456789abcdef");
    for (int i = 0; i < kReportsPerBind; i++) { EXPECT_EQ(cache.refFor(bound, refs), 1); }
    EXPECT_EQ(refs.made, std::vector<std::string>{"moonlight:uid:0123456789abcdef"});
    EXPECT_TRUE(refs.released.empty());
}

TEST(ConnectionRefCache, TwoLiveBindsKeepOneRefEach) {
    Cache cache;
    FakeRefs refs;
    const BridgeConnectionId first = idOf("bt:first");
    const BridgeConnectionId second = idOf("moonlight:second");
    for (int i = 0; i < kReportsPerBind; i++) {
        EXPECT_EQ(cache.refFor(first, refs), 1);
        EXPECT_EQ(cache.refFor(second, refs), 2);
    }
    EXPECT_EQ(refs.made.size(), 2u);
    EXPECT_TRUE(refs.released.empty());
}

// A rebind builds a new id object even for the same text: the slot's old id is gone.
TEST(ConnectionRefCache, ARebindReleasesTheOldBindsRefOnItsFirstReport) {
    Cache cache;
    FakeRefs refs;
    BridgeConnectionId slot = idOf("moonlight:same");
    cache.refFor(slot, refs);
    slot = idOf("moonlight:same");
    EXPECT_EQ(cache.refFor(slot, refs), 2);
    EXPECT_EQ(refs.released, std::vector<FakeRef>{1});
}

TEST(ConnectionRefCache, ABindWithAReportStillQueuedKeepsItsRef) {
    Cache cache;
    FakeRefs refs;
    BridgeConnectionId slot = idOf("moonlight:old");
    cache.refFor(slot, refs);
    const BridgeConnectionId queuedReport = slot;
    slot = idOf("moonlight:new");
    cache.refFor(slot, refs);
    EXPECT_TRUE(refs.released.empty());
    EXPECT_EQ(cache.refFor(queuedReport, refs), 1);
}

TEST(ConnectionRefCache, AnUnboundIdIsReleasedWhenTheNextNewIdArrives) {
    Cache cache;
    FakeRefs refs;
    BridgeConnectionId unbound = idOf("bt:gone");
    cache.refFor(unbound, refs);
    unbound.reset();
    EXPECT_TRUE(refs.released.empty());
    const BridgeConnectionId next = idOf("bt:next");
    cache.refFor(next, refs);
    EXPECT_EQ(refs.released, std::vector<FakeRef>{1});
}

TEST(ConnectionRefCache, WithEveryEntryBoundANewIdTakesTheEntriesInTurn) {
    Cache cache;
    FakeRefs refs;
    const BridgeConnectionId first = idOf("a");
    const BridgeConnectionId second = idOf("b");
    const BridgeConnectionId third = idOf("c");
    const BridgeConnectionId fourth = idOf("d");
    cache.refFor(first, refs);
    cache.refFor(second, refs);
    EXPECT_EQ(cache.refFor(third, refs), 3);
    EXPECT_EQ(refs.released, std::vector<FakeRef>{1});
    EXPECT_EQ(cache.refFor(fourth, refs), 4);
    EXPECT_EQ(refs.released, (std::vector<FakeRef>{1, 2}));
    EXPECT_EQ(cache.refFor(first, refs), 5);
    EXPECT_EQ(refs.released, (std::vector<FakeRef>{1, 2, 3}));
}

TEST(ConnectionRefCache, AStringThatCannotBeMadeIsTriedAgainOnTheNextReport) {
    Cache cache;
    FakeRefs refs;
    const BridgeConnectionId bound = idOf("moonlight:retry");
    refs.failing = true;
    EXPECT_EQ(cache.refFor(bound, refs), 0);
    refs.failing = false;
    EXPECT_EQ(cache.refFor(bound, refs), 1);
    EXPECT_EQ(cache.refFor(bound, refs), 1);
    EXPECT_EQ(refs.made.size(), 1u);
}

TEST(ConnectionRefCache, ANullIdHasNoRefAndMakesNothing) {
    Cache cache;
    FakeRefs refs;
    EXPECT_EQ(cache.refFor(nullptr, refs), 0);
    EXPECT_TRUE(refs.made.empty());
}

TEST(ConnectionRefCache, ANullIdHasNoRefEvenWithEveryEntryBound) {
    Cache cache;
    FakeRefs refs;
    const BridgeConnectionId first = idOf("a");
    const BridgeConnectionId second = idOf("b");
    cache.refFor(first, refs);
    cache.refFor(second, refs);
    EXPECT_EQ(cache.refFor(nullptr, refs), 0);
    EXPECT_EQ(refs.made.size(), 2u);
    EXPECT_TRUE(refs.released.empty());
}

TEST(ConnectionRefCache, StoppingReleasesEveryRefHeldAndOnlyThose) {
    Cache cache;
    FakeRefs refs;
    const BridgeConnectionId first = idOf("a");
    cache.refFor(first, refs);
    cache.releaseAll(refs);
    EXPECT_EQ(refs.released, std::vector<FakeRef>{1});
    cache.releaseAll(refs);
    EXPECT_EQ(refs.released, std::vector<FakeRef>{1});
    EXPECT_EQ(cache.refFor(first, refs), 2);
}
