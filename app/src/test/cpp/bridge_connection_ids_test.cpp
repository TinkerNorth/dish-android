// SPDX-License-Identifier: LGPL-3.0-or-later

#include "bridge_connection_ids.h"

#include <gtest/gtest.h>

#include <cstdint>
#include <map>
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

// Stands in for the slot table: each bind holds its id under a new connection number until it is
// unbound, and resolves only the numbers still bound.
struct FakeBinds {
    std::map<uint32_t, BridgeConnectionId> bound;
    uint32_t last = 0;

    uint32_t bind(const char* text) {
        bound[++last] = std::make_shared<const std::string>(text);
        return last;
    }

    void unbind(const uint32_t connection) { bound.erase(connection); }

    BridgeConnectionId operator()(const uint32_t connection) const {
        const auto it = bound.find(connection);
        return it == bound.end() ? nullptr : it->second;
    }
};

using Cache = dish_bridge::ConnectionRefCache<FakeRef, kCapacity>;

} // namespace

TEST(ConnectionRefCache, EveryReportOfOneBindSharesTheOneRefMadeForIt) {
    Cache cache;
    FakeRefs refs;
    FakeBinds binds;
    const uint32_t bound = binds.bind("moonlight:uid:0123456789abcdef");
    for (int i = 0; i < kReportsPerBind; i++) EXPECT_EQ(cache.refFor(bound, binds, refs), 1);
    EXPECT_EQ(refs.made, std::vector<std::string>{"moonlight:uid:0123456789abcdef"});
    EXPECT_TRUE(refs.released.empty());
}

TEST(ConnectionRefCache, TwoLiveBindsKeepOneRefEach) {
    Cache cache;
    FakeRefs refs;
    FakeBinds binds;
    const uint32_t first = binds.bind("bt:first");
    const uint32_t second = binds.bind("moonlight:second");
    for (int i = 0; i < kReportsPerBind; i++) {
        EXPECT_EQ(cache.refFor(first, binds, refs), 1);
        EXPECT_EQ(cache.refFor(second, binds, refs), 2);
    }
    EXPECT_EQ(refs.made.size(), 2u);
    EXPECT_TRUE(refs.released.empty());
}

// A rebind takes a new number even for the same text: the slot's old bind is gone.
TEST(ConnectionRefCache, ARebindReleasesTheOldBindsRefOnItsFirstReport) {
    Cache cache;
    FakeRefs refs;
    FakeBinds binds;
    const uint32_t old = binds.bind("moonlight:same");
    cache.refFor(old, binds, refs);
    binds.unbind(old);
    const uint32_t rebound = binds.bind("moonlight:same");
    EXPECT_EQ(cache.refFor(rebound, binds, refs), 2);
    EXPECT_EQ(refs.released, std::vector<FakeRef>{1});
}

TEST(ConnectionRefCache, AReportOfABindThatIsGoneResolvesToNothingOnceItsRefIsReleased) {
    Cache cache;
    FakeRefs refs;
    FakeBinds binds;
    const uint32_t old = binds.bind("moonlight:old");
    cache.refFor(old, binds, refs);
    binds.unbind(old);
    EXPECT_EQ(cache.refFor(old, binds, refs), 1);
    const uint32_t next = binds.bind("moonlight:new");
    cache.refFor(next, binds, refs);
    EXPECT_EQ(refs.released, std::vector<FakeRef>{1});
    EXPECT_EQ(cache.refFor(old, binds, refs), 0);
    EXPECT_EQ(refs.made.size(), 2u);
}

TEST(ConnectionRefCache, AnUnboundIdIsReleasedWhenTheNextNewIdArrives) {
    Cache cache;
    FakeRefs refs;
    FakeBinds binds;
    const uint32_t gone = binds.bind("bt:gone");
    cache.refFor(gone, binds, refs);
    binds.unbind(gone);
    EXPECT_TRUE(refs.released.empty());
    cache.refFor(binds.bind("bt:next"), binds, refs);
    EXPECT_EQ(refs.released, std::vector<FakeRef>{1});
}

TEST(ConnectionRefCache, WithEveryEntryBoundANewIdTakesTheEntriesInTurn) {
    Cache cache;
    FakeRefs refs;
    FakeBinds binds;
    const uint32_t first = binds.bind("a");
    const uint32_t second = binds.bind("b");
    const uint32_t third = binds.bind("c");
    const uint32_t fourth = binds.bind("d");
    cache.refFor(first, binds, refs);
    cache.refFor(second, binds, refs);
    EXPECT_EQ(cache.refFor(third, binds, refs), 3);
    EXPECT_EQ(refs.released, std::vector<FakeRef>{1});
    EXPECT_EQ(cache.refFor(fourth, binds, refs), 4);
    EXPECT_EQ(refs.released, (std::vector<FakeRef>{1, 2}));
    EXPECT_EQ(cache.refFor(first, binds, refs), 5);
    EXPECT_EQ(refs.released, (std::vector<FakeRef>{1, 2, 3}));
}

TEST(ConnectionRefCache, AStringThatCannotBeMadeIsTriedAgainOnTheNextReport) {
    Cache cache;
    FakeRefs refs;
    FakeBinds binds;
    const uint32_t bound = binds.bind("moonlight:retry");
    refs.failing = true;
    EXPECT_EQ(cache.refFor(bound, binds, refs), 0);
    refs.failing = false;
    EXPECT_EQ(cache.refFor(bound, binds, refs), 1);
    EXPECT_EQ(cache.refFor(bound, binds, refs), 1);
    EXPECT_EQ(refs.made.size(), 1u);
}

TEST(ConnectionRefCache, ConnectionZeroHasNoRefAndMakesNothing) {
    Cache cache;
    FakeRefs refs;
    FakeBinds binds;
    EXPECT_EQ(cache.refFor(0, binds, refs), 0);
    EXPECT_TRUE(refs.made.empty());
}

TEST(ConnectionRefCache, ConnectionZeroHasNoRefEvenWithEveryEntryBound) {
    Cache cache;
    FakeRefs refs;
    FakeBinds binds;
    cache.refFor(binds.bind("a"), binds, refs);
    cache.refFor(binds.bind("b"), binds, refs);
    EXPECT_EQ(cache.refFor(0, binds, refs), 0);
    EXPECT_EQ(refs.made.size(), 2u);
    EXPECT_TRUE(refs.released.empty());
}

TEST(ConnectionRefCache, StoppingReleasesEveryRefHeldAndOnlyThose) {
    Cache cache;
    FakeRefs refs;
    FakeBinds binds;
    const uint32_t first = binds.bind("a");
    cache.refFor(first, binds, refs);
    cache.releaseAll(refs);
    EXPECT_EQ(refs.released, std::vector<FakeRef>{1});
    cache.releaseAll(refs);
    EXPECT_EQ(refs.released, std::vector<FakeRef>{1});
    EXPECT_EQ(cache.refFor(first, binds, refs), 2);
}
