// SPDX-License-Identifier: LGPL-3.0-or-later

// Built twice: tracked_mutex_test as the app builds it, tracked_mutex_stats_test with
// DISH_LOCK_STATS, satellite's tests/test_tracked_mutex.cpp case for case.

#include "tracked_mutex.h"

#include <gtest/gtest.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <mutex>
#include <string>
#include <thread>
#include <type_traits>
#include <vector>

namespace {

using namespace std::chrono_literals;

TrackedMutex g_constantInitialized("test.global");

std::atomic<int> g_roleDecisions{0};

ThreadRole decideResend() {
    g_roleDecisions++;
    return ThreadRole::Resend;
}

} // namespace

#ifdef DISH_LOCK_STATS

namespace {

constexpr auto HOLD = std::chrono::milliseconds(100);
constexpr uint64_t HOLD_FLOOR_NS = 90'000'000;
constexpr uint64_t WAIT_FLOOR_NS = 50'000'000;
constexpr uint64_t UNCONTENDED_CEILING_NS = 20'000'000;

const lockstats::RoleStats& statsFor(const lockstats::Snapshot& snap, const char* lock,
                                     ThreadRole role) {
    static const lockstats::RoleStats none{};
    const lockstats::LockStats* found = snap.find(lock);
    return found != nullptr ? found->role(role) : none;
}

uint64_t histogramTotal(const lockstats::Histogram& histogram) {
    uint64_t total = 0;
    for (const uint64_t n : histogram) total += n;
    return total;
}

uint64_t pairCount(const std::vector<lockstats::NamePair>& pairs, const std::string& first,
                   const std::string& second) {
    for (const lockstats::NamePair& pair : pairs) {
        if (pair.first == first && pair.second == second) return pair.count;
    }
    return 0;
}

size_t pairsNaming(const std::vector<lockstats::NamePair>& pairs, const std::string& prefix) {
    return static_cast<size_t>(
        std::count_if(pairs.begin(), pairs.end(), [&](const lockstats::NamePair& pair) {
            return pair.first.rfind(prefix, 0) == 0 || pair.second.rfind(prefix, 0) == 0;
        }));
}

uint64_t regionHits(const lockstats::Snapshot& snap, const std::string& region) {
    for (const lockstats::RegionHit& hit : snap.regions) {
        if (hit.region == region) return hit.hits;
    }
    return 0;
}

} // namespace

TEST(LockStats, EveryAcquisitionIsCountedAgainstTheRoleOfTheThreadThatTookIt) {
    lockstats::reset();
    TrackedMutex m("test.counts");
    {
        ScopedThreadRole role(ThreadRole::UsbPoller);
        for (int i = 0; i < 5; ++i) { std::lock_guard<TrackedMutex> lk(m); }
        EXPECT_TRUE(m.try_lock());
        m.unlock();
        std::unique_lock<TrackedMutex> deferred(m, std::try_to_lock);
        EXPECT_TRUE(deferred.owns_lock());
    }
    std::thread other([&] {
        ScopedThreadRole role(ThreadRole::UiInput);
        std::lock_guard<TrackedMutex> lk(m);
    });
    other.join();

    const lockstats::Snapshot snap = lockstats::snapshot();
    const lockstats::RoleStats& poller = statsFor(snap, "test.counts", ThreadRole::UsbPoller);
    EXPECT_EQ(poller.acquisitions, 7u);
    EXPECT_EQ(poller.contended, 0u);
    EXPECT_EQ(poller.tryFailures, 0u);
    EXPECT_EQ(histogramTotal(poller.waitHistogram), 7u);
    EXPECT_EQ(histogramTotal(poller.holdHistogram), 7u);
    EXPECT_EQ(statsFor(snap, "test.counts", ThreadRole::UiInput).acquisitions, 1u);
    EXPECT_EQ(statsFor(snap, "test.counts", ThreadRole::Other).acquisitions, 0u);
    EXPECT_TRUE(statsFor(snap, "test.counts", ThreadRole::UsbPoller).touched());
    EXPECT_FALSE(statsFor(snap, "test.counts", ThreadRole::Receive).touched());
}

TEST(LockStats, AFailedTryLockIsCountedAsAFailureNotAsAnAcquisition) {
    lockstats::reset();
    TrackedMutex m("test.try");
    std::atomic<bool> held{false};
    std::atomic<bool> release{false};
    std::thread holder([&] {
        std::lock_guard<TrackedMutex> lk(m);
        held = true;
        while (!release) std::this_thread::yield();
    });
    while (!held) std::this_thread::yield();
    {
        ScopedThreadRole role(ThreadRole::Bridge);
        EXPECT_FALSE(m.try_lock());
        std::unique_lock<TrackedMutex> attempt(m, std::try_to_lock);
        EXPECT_FALSE(attempt.owns_lock());
    }
    release = true;
    holder.join();

    const lockstats::Snapshot snap = lockstats::snapshot();
    const lockstats::RoleStats& bridge = statsFor(snap, "test.try", ThreadRole::Bridge);
    EXPECT_EQ(bridge.tryFailures, 2u);
    EXPECT_EQ(bridge.acquisitions, 0u);
    EXPECT_TRUE(bridge.touched());
    EXPECT_EQ(statsFor(snap, "test.try", ThreadRole::Other).acquisitions, 1u);
}

TEST(LockStats, UnderContentionTheWaiterRecordsTheWaitAndTheHolderTheHold) {
    lockstats::reset();
    TrackedMutex m("test.contention");
    std::atomic<bool> held{false};
    std::atomic<bool> waiterReady{false};
    std::thread holder([&] {
        ScopedThreadRole role(ThreadRole::Heartbeat);
        std::lock_guard<TrackedMutex> lk(m);
        held = true;
        while (!waiterReady) std::this_thread::yield();
        std::this_thread::sleep_for(HOLD);
    });
    while (!held) std::this_thread::yield();
    {
        ScopedThreadRole role(ThreadRole::Receive);
        waiterReady = true;
        std::lock_guard<TrackedMutex> lk(m);
    }
    holder.join();

    const lockstats::Snapshot snap = lockstats::snapshot();
    const lockstats::RoleStats& waiter = statsFor(snap, "test.contention", ThreadRole::Receive);
    const lockstats::RoleStats& owner = statsFor(snap, "test.contention", ThreadRole::Heartbeat);
    EXPECT_EQ(waiter.acquisitions, 1u);
    EXPECT_EQ(waiter.contended, 1u);
    EXPECT_GE(waiter.waitMaxNs, WAIT_FLOOR_NS);
    EXPECT_EQ(waiter.waitTotalNs, waiter.waitMaxNs);
    EXPECT_LT(waiter.holdMaxNs, UNCONTENDED_CEILING_NS);
    EXPECT_GE(lockstats::histogramPercentileNs(waiter.waitHistogram, 0.99), waiter.waitMaxNs);
    EXPECT_EQ(owner.acquisitions, 1u);
    EXPECT_EQ(owner.contended, 0u);
    EXPECT_LT(owner.waitMaxNs, UNCONTENDED_CEILING_NS);
    EXPECT_GE(owner.holdMaxNs, HOLD_FLOOR_NS);
    EXPECT_EQ(owner.holdTotalNs, owner.holdMaxNs);
}

TEST(LockStats, ALockTakenUnderOthersRecordsAnEdgeFromEachHeldLock) {
    lockstats::reset();
    TrackedMutex a("test.nest.a");
    TrackedMutex b("test.nest.b");
    TrackedMutex c("test.nest.c");
    {
        std::lock_guard<TrackedMutex> la(a);
        std::lock_guard<TrackedMutex> lb(b);
        std::lock_guard<TrackedMutex> lc(c);
    }
    {
        std::lock_guard<TrackedMutex> lc(c);
        std::unique_lock<TrackedMutex> tried(a, std::try_to_lock);
        EXPECT_TRUE(tried.owns_lock());
    }
    { std::lock_guard<TrackedMutex> lb(b); }

    const lockstats::Snapshot snap = lockstats::snapshot();
    EXPECT_EQ(pairCount(snap.edges, "test.nest.a", "test.nest.b"), 1u);
    EXPECT_EQ(pairCount(snap.edges, "test.nest.a", "test.nest.c"), 1u);
    EXPECT_EQ(pairCount(snap.edges, "test.nest.b", "test.nest.c"), 1u);
    EXPECT_EQ(pairCount(snap.edges, "test.nest.c", "test.nest.a"), 0u);
    EXPECT_EQ(pairsNaming(snap.edges, "test.nest."), 3u);
}

TEST(LockStats, ReleasingAnOuterLockFirstLeavesTheInnerOneOnTheThreadsStack) {
    lockstats::reset();
    TrackedMutex outer("test.order.outer");
    TrackedMutex inner("test.order.inner");
    TrackedMutex later("test.order.later");
    outer.lock();
    inner.lock();
    outer.unlock();
    EXPECT_EQ(lockstats::heldByThisThread(), std::vector<std::string>{"test.order.inner"});
    later.lock();
    later.unlock();
    inner.unlock();
    EXPECT_TRUE(lockstats::heldByThisThread().empty());

    const lockstats::Snapshot snap = lockstats::snapshot();
    EXPECT_EQ(pairCount(snap.edges, "test.order.inner", "test.order.later"), 1u);
    EXPECT_EQ(pairCount(snap.edges, "test.order.outer", "test.order.later"), 0u);
}

TEST(LockStats, ABlockingRegionIsRecordedAgainstEveryLockTheThreadHolds) {
    lockstats::reset();
    TrackedMutex a("test.region.a");
    TrackedMutex b("test.region.b");
    blockingRegion("test.idle");
    {
        std::lock_guard<TrackedMutex> la(a);
        std::lock_guard<TrackedMutex> lb(b);
        blockingRegion("test.busy");
    }
    {
        std::lock_guard<TrackedMutex> la(a);
        blockingRegion("test.busy");
    }

    const lockstats::Snapshot snap = lockstats::snapshot();
    EXPECT_EQ(regionHits(snap, "test.idle"), 1u);
    EXPECT_EQ(regionHits(snap, "test.busy"), 2u);
    EXPECT_EQ(pairCount(snap.blocking, "test.busy", "test.region.a"), 2u);
    EXPECT_EQ(pairCount(snap.blocking, "test.busy", "test.region.b"), 1u);
    EXPECT_EQ(pairsNaming(snap.blocking, "test.idle"), 0u);
}

TEST(LockStats, AScopedRoleRestoresTheRoleInEffectBeforeIt) {
    EXPECT_EQ(currentThreadRole(), ThreadRole::Other);
    {
        ScopedThreadRole receive(ThreadRole::Receive);
        EXPECT_EQ(currentThreadRole(), ThreadRole::Receive);
        {
            ScopedThreadRole mic(ThreadRole::MicCapture);
            EXPECT_EQ(currentThreadRole(), ThreadRole::MicCapture);
        }
        EXPECT_EQ(currentThreadRole(), ThreadRole::Receive);
    }
    EXPECT_EQ(currentThreadRole(), ThreadRole::Other);

    lockstats::reset();
    TrackedMutex m("test.role");
    std::thread worker([&] {
        setThreadRole(ThreadRole::Battery);
        std::lock_guard<TrackedMutex> lk(m);
    });
    worker.join();
    const lockstats::Snapshot snap = lockstats::snapshot();
    EXPECT_EQ(statsFor(snap, "test.role", ThreadRole::Battery).acquisitions, 1u);
    EXPECT_EQ(std::string(threadRoleName(ThreadRole::AudioDispatch)), "AudioDispatch");
}

TEST(LockStats, ARoleDecidedByAFunctionIsTheOneItAnswers) {
    const int before = g_roleDecisions.load();
    {
        ScopedThreadRole role(&decideResend);
        EXPECT_EQ(currentThreadRole(), ThreadRole::Resend);
    }
    EXPECT_EQ(currentThreadRole(), ThreadRole::Other);
    EXPECT_EQ(g_roleDecisions.load(), before + 1);
}

TEST(LockStats, AConditionVariableAnyWaitReleasesTheTrackedLockAndTakesItBack) {
    lockstats::reset();
    TrackedMutex m("test.cv");
    std::condition_variable_any cv;
    bool ready = false;
    std::atomic<bool> waiting{false};
    std::vector<std::string> heldAfterWait;
    std::thread waiter([&] {
        ScopedThreadRole role(ThreadRole::AudioDispatch);
        std::unique_lock<TrackedMutex> lk(m);
        waiting = true;
        cv.wait(lk, [&] { return ready; });
        heldAfterWait = lockstats::heldByThisThread();
    });
    while (!waiting) std::this_thread::yield();
    std::this_thread::sleep_for(HOLD);
    {
        std::lock_guard<TrackedMutex> lk(m);
        ready = true;
    }
    cv.notify_all();
    waiter.join();

    const lockstats::Snapshot snap = lockstats::snapshot();
    const lockstats::RoleStats& audio = statsFor(snap, "test.cv", ThreadRole::AudioDispatch);
    EXPECT_GE(audio.acquisitions, 2u);
    EXPECT_LT(audio.holdMaxNs, HOLD_FLOOR_NS);
    EXPECT_EQ(heldAfterWait, std::vector<std::string>{"test.cv"});
}

TEST(LockStats, ResetClearsEveryCountButKeepsTheRegisteredNames) {
    TrackedMutex a("test.reset.a");
    TrackedMutex b("test.reset.b");
    {
        std::lock_guard<TrackedMutex> la(a);
        std::lock_guard<TrackedMutex> lb(b);
        blockingRegion("test.reset.region");
    }
    lockstats::reset();

    const lockstats::Snapshot snap = lockstats::snapshot();
    const lockstats::LockStats* kept = snap.find("test.reset.a");
    ASSERT_NE(kept, nullptr);
    for (const lockstats::RoleStats& role : kept->roles) {
        EXPECT_EQ(role.acquisitions, 0u);
        EXPECT_EQ(histogramTotal(role.holdHistogram), 0u);
    }
    EXPECT_TRUE(snap.edges.empty());
    EXPECT_TRUE(snap.blocking.empty());
    EXPECT_TRUE(snap.regions.empty());
    EXPECT_EQ(snap.overflows, 0u);
}

TEST(LockStats, TwoLocksWithOneNameAreCountedAsOneLock) {
    lockstats::reset();
    TrackedMutex first("test.shared");
    TrackedMutex second("test.shared");
    { std::lock_guard<TrackedMutex> lk(first); }
    { std::lock_guard<TrackedMutex> lk(second); }
    { std::lock_guard<TrackedMutex> lk(g_constantInitialized); }
    const lockstats::Snapshot snap = lockstats::snapshot();
    EXPECT_EQ(statsFor(snap, "test.shared", ThreadRole::Other).acquisitions, 2u);
    EXPECT_EQ(statsFor(snap, "test.global", ThreadRole::Other).acquisitions, 1u);
    size_t named = 0;
    for (const lockstats::LockStats& lock : snap.locks) named += lock.name == "test.shared" ? 1 : 0;
    EXPECT_EQ(named, 1u);
}

TEST(LockStats, AHistogramPercentileIsTheUpperEdgeOfTheBucketThatHoldsIt) {
    lockstats::Histogram histogram{};
    EXPECT_EQ(lockstats::histogramPercentileNs(histogram, 0.99), 0u);
    histogram[10] = 99;
    histogram[20] = 1;
    EXPECT_EQ(lockstats::histogramPercentileNs(histogram, 0.5), lockstats::bucketUpperNs(10));
    EXPECT_EQ(lockstats::histogramPercentileNs(histogram, 0.99), lockstats::bucketUpperNs(10));
    EXPECT_EQ(lockstats::histogramPercentileNs(histogram, 0.999), lockstats::bucketUpperNs(20));
    EXPECT_EQ(lockstats::bucketUpperNs(10), 1023u);
    EXPECT_EQ(lockstats::bucketUpperNs(0), 0u);
}

TEST(LockStats, ANanosecondCountFallsInTheBucketOfItsBitWidth) {
    EXPECT_EQ(lockstats::detail::bucketFor(0), 0u);
    EXPECT_EQ(lockstats::detail::bucketFor(1), 1u);
    EXPECT_EQ(lockstats::detail::bucketFor(1023), 10u);
    EXPECT_EQ(lockstats::detail::bucketFor(1024), 11u);
    EXPECT_EQ(lockstats::detail::bucketFor(UINT64_MAX), lockstats::HISTOGRAM_BUCKETS - 1);
}

#else

static_assert(sizeof(TrackedMutex) == sizeof(std::mutex) + sizeof(const char*),
              "without lock statistics a tracked mutex is a mutex and a name pointer");
static_assert(std::is_empty_v<ScopedThreadRole>,
              "without lock statistics a thread role carries no state");

TEST(PlainTrackedMutex, WorksWithTheStandardLockTypes) {
    TrackedMutex m("test.plain");
    EXPECT_EQ(std::string(m.name()), "test.plain");
    {
        std::lock_guard<TrackedMutex> lk(m);
        std::thread probe([&m] {
            std::unique_lock<TrackedMutex> second(m, std::try_to_lock);
            EXPECT_FALSE(second.owns_lock());
        });
        probe.join();
    }
    std::unique_lock<TrackedMutex> lk(m, std::try_to_lock);
    EXPECT_TRUE(lk.owns_lock());
    std::condition_variable_any cv;
    EXPECT_FALSE(cv.wait_for(lk, 1ms, [] { return false; }));
    EXPECT_TRUE(lk.owns_lock());
}

TEST(PlainTrackedMutex, RolesAndRegionsDoNothingAndARoleFunctionIsNeverCalled) {
    const int before = g_roleDecisions.load();
    ScopedThreadRole role(ThreadRole::Receive);
    ScopedThreadRole decided(&decideResend);
    setThreadRole(ThreadRole::UiInput);
    blockingRegion("test.plain.region");
    std::lock_guard<TrackedMutex> lk(g_constantInitialized);
    EXPECT_EQ(std::string(g_constantInitialized.name()), "test.global");
    EXPECT_EQ(g_roleDecisions.load(), before);
}

#endif
