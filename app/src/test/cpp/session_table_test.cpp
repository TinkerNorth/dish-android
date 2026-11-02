// SPDX-License-Identifier: LGPL-3.0-or-later

#include "session_table.h"

#include <gtest/gtest.h>

#include <chrono>
#include <cstdint>
#include <cstring>
#include <memory>
#include <thread>

namespace {

constexpr uint8_t kToken[4] = {9, 8, 7, 6};
constexpr int kSatellitePort = 9;
constexpr auto kHeartbeatSlice = std::chrono::milliseconds(10);

struct Key {
    uint8_t bytes[32];
};

Key keyOf(const uint8_t first) {
    Key key{};
    for (int i = 0; i < 32; i++) key.bytes[i] = (uint8_t)(first + i);
    return key;
}

} // namespace

TEST(SessionTable, OpensSessionsUnderRisingHandlesAndFindsThem) {
    dish_session::SessionTable table;
    const int first = table.open("127.0.0.1", kSatellitePort);
    const int second = table.open("127.0.0.1", kSatellitePort);
    ASSERT_GT(first, 0);
    EXPECT_GT(second, first);
    const std::shared_ptr<dish_session::Session> s = table.get(first);
    ASSERT_NE(s, nullptr);
    EXPECT_GE(s->udpSock, 0);
    EXPECT_EQ(s->dest.sin_port, htons(kSatellitePort));
    EXPECT_NE(table.get(second), s);
    table.close(first);
    table.close(second);
}

TEST(SessionTable, RefusesAnAddressThatIsNotAnIpv4Literal) {
    dish_session::SessionTable table;
    EXPECT_EQ(table.open("::1", kSatellitePort), -1);
    EXPECT_EQ(table.open("satellite.local", kSatellitePort), -1);
    EXPECT_EQ(table.get(1), nullptr);
}

TEST(SessionTable, ClosingASessionRetiresItForEveryoneStillHoldingIt) {
    dish_session::SessionTable table;
    const int handle = table.open("127.0.0.1", kSatellitePort);
    const std::shared_ptr<dish_session::Session> held = table.get(handle);
    held->audio[3];
    held->heartbeat.start([held] {
        while (held->heartbeat.running()) std::this_thread::sleep_for(kHeartbeatSlice);
    });
    table.close(handle);
    EXPECT_EQ(table.get(handle), nullptr);
    EXPECT_EQ(held->udpSock, -1);
    EXPECT_TRUE(held->closed.load());
    EXPECT_TRUE(held->audio.empty());
    EXPECT_FALSE(held->heartbeat.running());
    table.close(handle);
}

TEST(SessionTable, NewConnectionParamsRestartTheCountersTheGuardAndTheAudio) {
    dish_session::SessionTable table;
    const int handle = table.open("127.0.0.1", kSatellitePort);
    const std::shared_ptr<dish_session::Session> s = table.get(handle);
    s->counter.store(77);
    s->lastRxCounter.store(55);
    s->missedAcks.store(3);
    s->connectionAlive.store(false);
    s->serverEpoch.store(5);
    s->activeBitmap.store(6);
    s->closeReason.store(2);
    s->audio[1];

    const Key key = keyOf(0x30);
    dish_session::setConnectionParams(*s, kToken, key.bytes, 2);

    EXPECT_EQ(memcmp(s->token, kToken, 4), 0);
    EXPECT_EQ(memcmp(s->key, key.bytes, 32), 0);
    EXPECT_EQ(s->counter.load(), 1u);
    EXPECT_EQ(s->lastRxCounter.load(), 0u);
    EXPECT_EQ(s->missedAcks.load(), 0);
    EXPECT_TRUE(s->connectionAlive.load());
    EXPECT_EQ(s->serverEpoch.load(), -1);
    EXPECT_EQ(s->activeBitmap.load(), -1);
    EXPECT_EQ(s->closeReason.load(), -1);
    EXPECT_EQ(s->protocolVersion.load(), 2);
    EXPECT_TRUE(s->audio.empty());
    table.close(handle);
}

TEST(SessionTable, ClearingTheRttWindowsReachesEverySession) {
    dish_session::SessionTable table;
    const int a = table.open("127.0.0.1", kSatellitePort);
    const int b = table.open("127.0.0.1", kSatellitePort);
    hotpath::setEnabled(true);
    hotpath::addRttSample(&table.get(a)->rtt, 1000, 2000);
    hotpath::addRttSample(&table.get(b)->rtt, 1000, 2000);
    hotpath::setEnabled(false);
    ASSERT_EQ(table.get(a)->rtt.count, 1u);
    table.clearRtt();
    EXPECT_EQ(table.get(a)->rtt.count, 0u);
    EXPECT_EQ(table.get(b)->rtt.count, 0u);
    table.close(a);
    table.close(b);
}
