// SPDX-License-Identifier: LGPL-3.0-or-later

#include "session_table.h"

#include <gtest/gtest.h>
#include <sodium.h>

#include <chrono>
#include <cstdint>
#include <cstring>
#include <thread>
#include <vector>

#include "send_path.h"
#include "wire_encoders.h"

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

class SodiumReady : public ::testing::Test {
  protected:
    static void SetUpTestSuite() { ASSERT_GE(sodium_init(), 0); }
};

using SessionTable = SodiumReady;

} // namespace

TEST_F(SessionTable, OpensSessionsUnderRisingHandlesAndFindsThem) {
    dish_session::SessionTable table;
    const int first = table.open("127.0.0.1", kSatellitePort);
    const int second = table.open("127.0.0.1", kSatellitePort);
    ASSERT_GT(first, 0);
    EXPECT_GT(second, first);
    const dish_session::SessionRef s = table.find(first);
    ASSERT_TRUE(s);
    const dish_session::Material m = dish_session::readMaterial(*s);
    EXPECT_EQ(m.handle, first);
    EXPECT_GE(m.sock, 0);
    EXPECT_EQ(m.dest.sin_port, htons(kSatellitePort));
    EXPECT_NE(table.find(second).entry, s.entry);
    EXPECT_TRUE(table.stillOpen(s));
}

TEST_F(SessionTable, RefusesAnAddressThatIsNotAnIpv4Literal) {
    dish_session::SessionTable table;
    EXPECT_EQ(table.open("::1", kSatellitePort), -1);
    EXPECT_EQ(table.open("satellite.local", kSatellitePort), -1);
    EXPECT_FALSE(table.find(1));
}

TEST_F(SessionTable, ClosingASessionRetiresItForEveryoneStillHoldingIt) {
    dish_session::SessionTable table;
    const int handle = table.open("127.0.0.1", kSatellitePort);
    const dish_session::SessionRef held = table.find(handle);
    held->audio[3];
    held->heartbeat.start([held] {
        while (held->heartbeat.running()) std::this_thread::sleep_for(kHeartbeatSlice);
    });
    table.close(handle);
    EXPECT_FALSE(table.find(handle));
    EXPECT_FALSE(table.stillOpen(held));
    const dish_session::Material m = dish_session::readMaterial(*held);
    EXPECT_EQ(m.handle, 0);
    EXPECT_EQ(m.sock, -1);
    EXPECT_EQ(held->audioHandle, 0);
    EXPECT_TRUE(held->audio.empty());
    EXPECT_FALSE(held->heartbeat.running());
    EXPECT_FALSE(dish_send::sendEncrypted(held, dish_wire::MSG_HEARTBEAT_PING, nullptr, 0));
    table.close(handle);
}

TEST_F(SessionTable, NewConnectionParamsRestartTheCountersTheGuardAndTheAudio) {
    dish_session::SessionTable table;
    const int handle = table.open("127.0.0.1", kSatellitePort);
    const dish_session::SessionRef s = table.find(handle);
    s->counter.store(77);
    s->lastRxCounter.store(55);
    s->missedAcks.store(3);
    s->connectionAlive.store(false);
    s->serverEpoch.store(5);
    s->activeBitmap.store(6);
    s->closeReason.store(2);
    s->audio[1];

    const Key key = keyOf(0x30);
    dish_session::setConnectionParams(s, kToken, key.bytes, 2);

    const dish_session::Material m = dish_session::readMaterial(*s);
    EXPECT_EQ(memcmp(m.token, kToken, 4), 0);
    EXPECT_EQ(memcmp(m.key, key.bytes, 32), 0);
    EXPECT_EQ(m.handle, handle);
    EXPECT_EQ(s->counter.load(), 1u);
    EXPECT_EQ(s->lastRxCounter.load(), 0u);
    EXPECT_EQ(s->missedAcks.load(), 0);
    EXPECT_TRUE(s->connectionAlive.load());
    EXPECT_EQ(s->serverEpoch.load(), -1);
    EXPECT_EQ(s->activeBitmap.load(), -1);
    EXPECT_EQ(s->closeReason.load(), -1);
    EXPECT_EQ(s->protocolVersion.load(), 2);
    EXPECT_TRUE(s->audio.empty());
}

TEST_F(SessionTable, ParamsForAClosedSessionChangeNothing) {
    dish_session::SessionTable table;
    const int handle = table.open("127.0.0.1", kSatellitePort);
    const dish_session::SessionRef stale = table.find(handle);
    table.close(handle);
    const Key key = keyOf(0x40);
    dish_session::setConnectionParams(stale, kToken, key.bytes, 2);
    const dish_session::Material m = dish_session::readMaterial(*stale);
    EXPECT_EQ(m.handle, 0);
    EXPECT_NE(memcmp(m.key, key.bytes, 32), 0);
}

TEST_F(SessionTable, ClearingTheRttWindowsReachesEverySession) {
    dish_session::SessionTable table;
    const int a = table.open("127.0.0.1", kSatellitePort);
    const int b = table.open("127.0.0.1", kSatellitePort);
    hotpath::setEnabled(true);
    hotpath::addRttSample(&table.find(a)->rtt, 1000, 2000);
    hotpath::addRttSample(&table.find(b)->rtt, 1000, 2000);
    hotpath::setEnabled(false);
    ASSERT_EQ(table.find(a)->rtt.count, 1u);
    table.clearRtt();
    EXPECT_EQ(table.find(a)->rtt.count, 0u);
    EXPECT_EQ(table.find(b)->rtt.count, 0u);
}

TEST_F(SessionTable, AFullTableRefusesTheNextSessionUntilOneCloses) {
    dish_session::SessionTable table;
    std::vector<int> handles;
    for (size_t i = 0; i < dish_session::MAX_SESSIONS; i++) {
        handles.push_back(table.open("127.0.0.1", kSatellitePort));
        ASSERT_GT(handles.back(), 0);
    }
    EXPECT_EQ(table.open("127.0.0.1", kSatellitePort), -1);
    const dish_session::SessionRef closing = table.find(handles[3]);
    table.close(handles[3]);
    const int next = table.open("127.0.0.1", kSatellitePort);
    ASSERT_GT(next, handles.back());
    EXPECT_EQ(table.find(next).entry, closing.entry);
    EXPECT_FALSE(table.find(handles[3]));
    EXPECT_FALSE(table.stillOpen(closing));
}

// A session in an entry an earlier one used starts from nothing the earlier one left behind.
TEST_F(SessionTable, ASessionInAReusedEntryStartsFresh) {
    dish_session::SessionTable table;
    const int first = table.open("127.0.0.1", kSatellitePort);
    const dish_session::SessionRef old = table.find(first);
    old->counter.store(900);
    old->missedAcks.store(4);
    old->connectionAlive.store(false);
    old->closeReason.store(1);
    old->sentByCtrl[2].store(11);
    old->protocolVersion.store(3);
    old->audio[0];
    table.close(first);
    const int second = table.open("127.0.0.1", kSatellitePort);
    const dish_session::SessionRef s = table.find(second);
    ASSERT_EQ(s.entry, old.entry);
    EXPECT_EQ(s->counter.load(), 1u);
    EXPECT_EQ(s->missedAcks.load(), 0);
    EXPECT_TRUE(s->connectionAlive.load());
    EXPECT_EQ(s->closeReason.load(), -1);
    EXPECT_EQ(s->sentByCtrl[2].load(), 0u);
    EXPECT_EQ(s->protocolVersion.load(), 1);
    EXPECT_EQ(s->audioHandle, second);
    EXPECT_TRUE(s->audio.empty());
}
