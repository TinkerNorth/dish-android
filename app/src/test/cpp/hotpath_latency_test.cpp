// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

// hotpath_latency (hotpath_latency.{h,cpp}): the opt-in bench that times the USB-direct hot path
// and the heartbeat round trip, and the ping policy the heartbeat thread asks. Its windows are
// process-wide, so every case arms it fresh; the URB clock is thread-local, so every case that
// touches it first parks this thread on a known device.
#include "hotpath_latency.h"

#include <gtest/gtest.h>

#include <chrono>
#include <cstdint>
#include <string>
#include <thread>

namespace {

using hotpath::RttStats;

constexpr int64_t kNsPerUs = 1000;
constexpr int64_t kNsPerSec = 1000000000;
// A ping unanswered past this is lost, not in flight (hotpath_latency.cpp kRttMaxNs).
constexpr int64_t kRttMaxNs = 5 * kNsPerSec;
constexpr int64_t kSentNs = 10 * kNsPerSec;
constexpr int32_t kDeviceA = 1;
constexpr int32_t kDeviceB = 2;
constexpr int32_t kNeverSeenDevice = 999;
constexpr size_t kSessionWindow = RttStats::kWindow;
constexpr size_t kPooledRttWindow = 64;
constexpr size_t kAccumulateCap = 262144;
// Comfortably past the 100 ms stream-pause ceiling on URB gaps.
constexpr int kPastTheStreamPauseCeilingMs = 120;

void armFresh() {
    hotpath::setEnabled(false);
    hotpath::setEnabled(true);
}

// Leaves this thread's URB clock pointing at `deviceId` with no send pending, then clears every
// window: what a poller thread looks like between two reports.
void primeThread(const int32_t deviceId) {
    hotpath::setEnabled(true);
    hotpath::markInputRead(deviceId);
    hotpath::markGamepadSent();
    armFresh();
}

void addRttMicros(RttStats* session, const int64_t us) {
    hotpath::addRttSample(session, kSentNs, kSentNs + us * kNsPerUs);
}

// The `"key":{...}` or `"key":[...]` value of a flat JSON object, brackets included.
std::string valueOf(const std::string& json, const std::string& key, const char open,
                    const char close) {
    const std::string needle = "\"" + key + "\":" + open;
    const size_t start = json.find(needle);
    if (start == std::string::npos) return "<missing " + key + ">";
    const size_t bodyStart = start + needle.size() - 1;
    const size_t end = json.find(close, bodyStart);
    if (end == std::string::npos) return "<unterminated " + key + ">";
    return json.substr(bodyStart, end - bodyStart + 1);
}

std::string objectOf(const std::string& json, const std::string& key) {
    return valueOf(json, key, '{', '}');
}

std::string arrayOf(const std::string& json, const std::string& key) {
    return valueOf(json, key, '[', ']');
}

int sampleCount(const std::string& json, const std::string& key) {
    const std::string object = objectOf(json, key);
    const std::string needle = "\"n\":";
    const size_t at = object.find(needle);
    if (at == std::string::npos) return -1;
    return std::stoi(object.substr(at + needle.size()));
}

std::string ascendingArray(const int first, const int last) {
    std::string out = "[";
    for (int v = first; v <= last; v++) {
        if (v != first) out += ",";
        out += std::to_string(v);
    }
    return out + "]";
}

} // namespace

// ---- stage 2: the ping policy ---------------------------------------------------------------

TEST(ShouldArmPing, NothingOutstandingArms) { EXPECT_TRUE(hotpath::shouldArmPing(0, kSentNs)); }

TEST(ShouldArmPing, APingInFlightInsideTheLossWindowHolds) {
    EXPECT_FALSE(hotpath::shouldArmPing(kSentNs, kSentNs));
    EXPECT_FALSE(hotpath::shouldArmPing(kSentNs, kSentNs + kRttMaxNs - 1));
}

TEST(ShouldArmPing, APingPastTheLossWindowIsReclaimed) {
    EXPECT_TRUE(hotpath::shouldArmPing(kSentNs, kSentNs + kRttMaxNs));
}

TEST(NowMonotonicNs, NeverRunsBackwards) {
    const int64_t first = hotpath::nowMonotonicNs();
    const int64_t second = hotpath::nowMonotonicNs();
    EXPECT_GT(first, 0);
    EXPECT_GE(second, first);
}

// ---- stage 2: the round-trip samples --------------------------------------------------------

TEST(AddRttSample, DisabledRecordsNothing) {
    hotpath::setEnabled(false);
    RttStats session;
    addRttMicros(&session, 10);
    EXPECT_EQ(objectOf(hotpath::sessionStatsJson(session, 0), "rtt_us"), "{\"n\":0}");
}

TEST(AddRttSample, NegativeAndOverlongSamplesAreDropped) {
    armFresh();
    RttStats session;
    hotpath::addRttSample(&session, kSentNs, kSentNs - 1);
    hotpath::addRttSample(&session, kSentNs, kSentNs + kRttMaxNs);
    EXPECT_EQ(sampleCount(hotpath::sessionStatsJson(session, 0), "rtt_us"), 0);
    hotpath::addRttSample(&session, kSentNs, kSentNs + kRttMaxNs - kNsPerUs);
    EXPECT_EQ(sampleCount(hotpath::sessionStatsJson(session, 0), "rtt_us"), 1);
}

TEST(AddRttSample, ANullSessionStillFeedsThePooledWindow) {
    armFresh();
    hotpath::addRttSample(nullptr, kSentNs, kSentNs + 10 * kNsPerUs);
    EXPECT_EQ(sampleCount(hotpath::statsJson(false), "rtt_us"), 1);
}

TEST(AddRttSample, TheSessionRingWrapsAtSixtyFourAndKeepsTheNewest) {
    armFresh();
    RttStats session;
    for (size_t i = 1; i <= kSessionWindow + 1; i++) addRttMicros(&session, (int64_t)i);
    const std::string json = hotpath::sessionStatsJson(session, 0);
    EXPECT_EQ(objectOf(json, "rtt_us"), "{\"n\":64,\"min\":2.00,\"p50\":34.00,\"p90\":59.00,"
                                        "\"p99\":64.00,\"max\":65.00,\"mean\":33.50}");
    EXPECT_EQ(arrayOf(json, "rtt_recent_us"), ascendingArray(34, 65));
}

TEST(RttWindow, UnrollsOldestFirstBeforeAndAfterWrapping) {
    armFresh();
    RttStats session;
    addRttMicros(&session, 10);
    addRttMicros(&session, 30);
    addRttMicros(&session, 20);
    EXPECT_EQ(arrayOf(hotpath::sessionStatsJson(session, 0), "rtt_recent_us"), "[10,30,20]");

    for (size_t i = 0; i < kSessionWindow; i++) addRttMicros(&session, 100 + (int64_t)i);
    EXPECT_EQ(arrayOf(hotpath::sessionStatsJson(session, 0), "rtt_recent_us"),
              ascendingArray(132, 163));
}

TEST(SessionStatsJson, EmptyWindowReportsNZero) {
    RttStats session;
    EXPECT_EQ(hotpath::sessionStatsJson(session, 0),
              "{\"rtt_recent_us\":[],\"rtt_us\":{\"n\":0},\"pings\":0,\"acks\":0,\"missed\":0}");
}

TEST(SessionStatsJson, PingsAcksAndMissedRideAlong) {
    RttStats session;
    session.pings.store(7);
    session.acks.store(5);
    const std::string json = hotpath::sessionStatsJson(session, 2);
    EXPECT_NE(json.find("\"pings\":7,\"acks\":5,\"missed\":2}"), std::string::npos) << json;
}

TEST(ClearRtt, DropsTheSamplesButNotTheTallies) {
    armFresh();
    RttStats session;
    session.pings.store(3);
    addRttMicros(&session, 10);
    hotpath::clearRtt(session);
    const std::string json = hotpath::sessionStatsJson(session, 0);
    EXPECT_EQ(sampleCount(json, "rtt_us"), 0);
    EXPECT_EQ(arrayOf(json, "rtt_recent_us"), "[]");
    EXPECT_NE(json.find("\"pings\":3"), std::string::npos) << json;
}

// ---- percentiles ----------------------------------------------------------------------------

TEST(Percentiles, P50OfAnOddCountIsTheMiddle) {
    armFresh();
    RttStats session;
    addRttMicros(&session, 30);
    addRttMicros(&session, 10);
    addRttMicros(&session, 20);
    EXPECT_EQ(objectOf(hotpath::sessionStatsJson(session, 0), "rtt_us"),
              "{\"n\":3,\"min\":10.00,\"p50\":20.00,\"p90\":30.00,\"p99\":30.00,\"max\":30.00,"
              "\"mean\":20.00}");
}

TEST(Percentiles, P99OfOneSampleIsThatSample) {
    armFresh();
    RttStats session;
    addRttMicros(&session, 42);
    EXPECT_EQ(objectOf(hotpath::sessionStatsJson(session, 0), "rtt_us"),
              "{\"n\":1,\"min\":42.00,\"p50\":42.00,\"p90\":42.00,\"p99\":42.00,\"max\":42.00,"
              "\"mean\":42.00}");
}

TEST(Percentiles, TheRankRoundsHalfUp) {
    armFresh();
    RttStats session;
    addRttMicros(&session, 10);
    addRttMicros(&session, 20);
    EXPECT_EQ(objectOf(hotpath::sessionStatsJson(session, 0), "rtt_us"),
              "{\"n\":2,\"min\":10.00,\"p50\":20.00,\"p90\":20.00,\"p99\":20.00,\"max\":20.00,"
              "\"mean\":15.00}");
}

// ---- the pooled readout ---------------------------------------------------------------------

TEST(StatsJson, DisabledReportsEnabledFalse) {
    hotpath::setEnabled(false);
    EXPECT_EQ(hotpath::statsJson(false).rfind("{\"enabled\":false,", 0), 0u);
}

TEST(StatsJson, ArmedListsTheThreeEmptyWindows) {
    armFresh();
    EXPECT_EQ(hotpath::statsJson(false), "{\"enabled\":true,\"stage1_hotpath_us\":{\"n\":0},"
                                         "\"urb_gap_us\":{\"n\":0},\"rtt_recent_us\":[],"
                                         "\"rtt_us\":{\"n\":0}}");
}

TEST(StatsJson, ResetClearsAfterReporting) {
    armFresh();
    hotpath::addRttSample(nullptr, kSentNs, kSentNs + 10 * kNsPerUs);
    EXPECT_EQ(sampleCount(hotpath::statsJson(true), "rtt_us"), 1);
    EXPECT_EQ(sampleCount(hotpath::statsJson(false), "rtt_us"), 0);
}

TEST(StatsJson, TheRecentSamplesAreTakenBeforeTheResetClearsThem) {
    armFresh();
    hotpath::addRttSample(nullptr, kSentNs, kSentNs + 10 * kNsPerUs);
    hotpath::addRttSample(nullptr, kSentNs, kSentNs + 20 * kNsPerUs);
    const std::string json = hotpath::statsJson(true);
    EXPECT_EQ(arrayOf(json, "rtt_recent_us"), "[10,20]");
    EXPECT_EQ(sampleCount(json, "rtt_us"), 2);
}

TEST(ResetRttWindow, ClearsOnlyThePooledRttRing) {
    primeThread(kDeviceA);
    hotpath::markInputRead(kDeviceA);
    hotpath::markGamepadSent();
    hotpath::addRttSample(nullptr, kSentNs, kSentNs + 10 * kNsPerUs);
    hotpath::resetRttWindow();
    const std::string json = hotpath::statsJson(false);
    EXPECT_EQ(sampleCount(json, "rtt_us"), 0);
    EXPECT_EQ(sampleCount(json, "stage1_hotpath_us"), 1);
}

TEST(Ring, TheSlidingRttWindowKeepsTheLastSixtyFour) {
    armFresh();
    for (size_t i = 1; i <= kPooledRttWindow + 6; i++) {
        hotpath::addRttSample(nullptr, kSentNs, kSentNs + (int64_t)i * kNsPerUs);
    }
    const std::string json = hotpath::statsJson(false);
    EXPECT_EQ(objectOf(json, "rtt_us"), "{\"n\":64,\"min\":7.00,\"p50\":39.00,\"p90\":64.00,"
                                        "\"p99\":69.00,\"max\":70.00,\"mean\":38.50}");
    EXPECT_EQ(arrayOf(json, "rtt_recent_us"), ascendingArray(39, 70));
}

// ---- stage 1: the URB clock -----------------------------------------------------------------

TEST(MarkGamepadSent, WithoutAPriorInputReadRecordsNothing) {
    primeThread(kDeviceA);
    hotpath::markGamepadSent();
    EXPECT_EQ(sampleCount(hotpath::statsJson(false), "stage1_hotpath_us"), 0);
    EXPECT_EQ(sampleCount(hotpath::deviceLatencyJson(kDeviceA), "stage1_hotpath_us"), 0);
}

TEST(MarkGamepadSent, AfterAnInputReadRecordsOneStageOneSample) {
    primeThread(kDeviceA);
    hotpath::markInputRead(kDeviceA);
    hotpath::markGamepadSent();
    EXPECT_EQ(sampleCount(hotpath::statsJson(false), "stage1_hotpath_us"), 1);
    EXPECT_EQ(sampleCount(hotpath::deviceLatencyJson(kDeviceA), "stage1_hotpath_us"), 1);
}

TEST(MarkGamepadSent, ASecondSendForTheSameReadRecordsNothing) {
    primeThread(kDeviceA);
    hotpath::markInputRead(kDeviceA);
    hotpath::markGamepadSent();
    hotpath::markGamepadSent();
    EXPECT_EQ(sampleCount(hotpath::statsJson(false), "stage1_hotpath_us"), 1);
}

TEST(MarkGamepadSent, DisabledRecordsNothing) {
    primeThread(kDeviceA);
    hotpath::setEnabled(false);
    hotpath::markInputRead(kDeviceA);
    hotpath::markGamepadSent();
    EXPECT_EQ(sampleCount(hotpath::statsJson(false), "stage1_hotpath_us"), 0);
}

TEST(MarkInputRead, EachConsecutiveReadOnTheSameDeviceRecordsAGap) {
    primeThread(kDeviceA);
    hotpath::markInputRead(kDeviceA);
    EXPECT_EQ(sampleCount(hotpath::statsJson(false), "urb_gap_us"), 1);
    hotpath::markInputRead(kDeviceA);
    EXPECT_EQ(sampleCount(hotpath::statsJson(false), "urb_gap_us"), 2);
    EXPECT_EQ(sampleCount(hotpath::deviceLatencyJson(kDeviceA), "urb_gap_us"), 2);
}

TEST(MarkInputRead, ADeviceChangeDoesNotRecordAGap) {
    primeThread(kDeviceA);
    hotpath::markInputRead(kDeviceB);
    EXPECT_EQ(sampleCount(hotpath::statsJson(false), "urb_gap_us"), 0);
    hotpath::markInputRead(kDeviceB);
    EXPECT_EQ(sampleCount(hotpath::statsJson(false), "urb_gap_us"), 1);
    EXPECT_EQ(sampleCount(hotpath::deviceLatencyJson(kDeviceB), "urb_gap_us"), 1);
    EXPECT_EQ(sampleCount(hotpath::deviceLatencyJson(kDeviceA), "urb_gap_us"), 0);
}

TEST(MarkInputRead, AGapAboveTheStreamPauseCeilingIsNotRecorded) {
    primeThread(kDeviceA);
    std::this_thread::sleep_for(std::chrono::milliseconds(kPastTheStreamPauseCeilingMs));
    hotpath::markInputRead(kDeviceA);
    EXPECT_EQ(sampleCount(hotpath::statsJson(false), "urb_gap_us"), 0);
}

TEST(Ring, TheAccumulatingWindowStopsAtItsCap) {
    primeThread(kDeviceA);
    for (size_t i = 0; i < kAccumulateCap + 1; i++) {
        hotpath::markInputRead(kDeviceA);
        hotpath::markGamepadSent();
    }
    EXPECT_EQ(sampleCount(hotpath::statsJson(false), "stage1_hotpath_us"), (int)kAccumulateCap);
}

// ---- arming and the per-device rings --------------------------------------------------------

TEST(SetEnabled, ArmingClearsEveryWindowSoAnIdleStretchSinceLaunchIsNotBlendedIn) {
    primeThread(kDeviceA);
    hotpath::markInputRead(kDeviceA);
    hotpath::markGamepadSent();
    hotpath::markInputRead(kDeviceA);
    hotpath::addRttSample(nullptr, kSentNs, kSentNs + 10 * kNsPerUs);
    const std::string before = hotpath::statsJson(false);
    ASSERT_EQ(sampleCount(before, "stage1_hotpath_us"), 1);
    ASSERT_EQ(sampleCount(before, "urb_gap_us"), 2);
    ASSERT_EQ(sampleCount(before, "rtt_us"), 1);
    ASSERT_EQ(sampleCount(hotpath::deviceLatencyJson(kDeviceA), "urb_gap_us"), 2);

    hotpath::setEnabled(false);
    hotpath::setEnabled(true);

    const std::string after = hotpath::statsJson(false);
    EXPECT_EQ(sampleCount(after, "stage1_hotpath_us"), 0);
    EXPECT_EQ(sampleCount(after, "urb_gap_us"), 0);
    EXPECT_EQ(sampleCount(after, "rtt_us"), 0);
    EXPECT_EQ(hotpath::deviceLatencyJson(kDeviceA),
              "{\"stage1_hotpath_us\":{\"n\":0},\"urb_gap_us\":{\"n\":0}}");
}

TEST(SetEnabled, ArmingWhileAlreadyArmedKeepsTheWindow) {
    armFresh();
    hotpath::addRttSample(nullptr, kSentNs, kSentNs + 10 * kNsPerUs);
    hotpath::setEnabled(true);
    EXPECT_EQ(sampleCount(hotpath::statsJson(false), "rtt_us"), 1);
}

TEST(SetEnabled, DisarmingKeepsTheWindowForTheDump) {
    armFresh();
    hotpath::addRttSample(nullptr, kSentNs, kSentNs + 10 * kNsPerUs);
    hotpath::setEnabled(false);
    const std::string json = hotpath::statsJson(false);
    EXPECT_EQ(json.rfind("{\"enabled\":false,", 0), 0u);
    EXPECT_EQ(sampleCount(json, "rtt_us"), 1);
}

TEST(DeviceLatencyJson, AnUnknownDeviceReadsAsNoSamples) {
    armFresh();
    EXPECT_EQ(hotpath::deviceLatencyJson(kNeverSeenDevice),
              "{\"stage1_hotpath_us\":{\"n\":0},\"urb_gap_us\":{\"n\":0}}");
}

TEST(ForgetDevice, DropsOnlyThatDevicesRings) {
    primeThread(kDeviceA);
    hotpath::markInputRead(kDeviceA);
    hotpath::markGamepadSent();
    hotpath::markInputRead(kDeviceB);
    hotpath::markGamepadSent();
    hotpath::forgetDevice(kDeviceA);
    EXPECT_EQ(sampleCount(hotpath::deviceLatencyJson(kDeviceA), "stage1_hotpath_us"), 0);
    EXPECT_EQ(sampleCount(hotpath::deviceLatencyJson(kDeviceB), "stage1_hotpath_us"), 1);
}
