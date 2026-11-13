// SPDX-License-Identifier: LGPL-3.0-or-later

#include "send_path.h"
#include "send_counter.h"
#include "vector_session.h"
#include "wire_encoders.h"
#include "wire_vectors.h"

#include <gtest/gtest.h>
#include <sodium.h>

#include <atomic>
#include <chrono>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <set>
#include <string>
#include <thread>
#include <utility>
#include <vector>

#include "fake_satellite.h"
#include "hotpath_latency.h"

using vector_session::Bytes;
using vector_session::bytesOf;
using vector_session::counterOf;

namespace {

class SodiumReady : public ::testing::Test {
  protected:
    static void SetUpTestSuite() { ASSERT_GE(sodium_init(), 0); }
};

// What the satellite reads out of a client datagram (contract §Crypto): the header's token and
// counter, the nonce direction 0 with the counter in its last four bytes, the token as the AAD.
// Empty when the datagram does not open under the vectors' session key.
Bytes openAsTheSatellite(const Bytes& datagram) {
    const vector_session::Key key = vector_session::vectorKey();
    uint8_t nonce[12] = {};
    nonce[0] = 0x00;
    memcpy(nonce + 8, datagram.data() + 4, 4);
    Bytes inner(datagram.size());
    unsigned long long innerLen = 0;
    const int opened = crypto_aead_chacha20poly1305_ietf_decrypt(
        inner.data(), &innerLen, nullptr, datagram.data() + dish_wire::OUTER_HEADER_BYTES,
        datagram.size() - dish_wire::OUTER_HEADER_BYTES, datagram.data(), 4, nonce, key.bytes);
    if (opened != 0) return Bytes();
    inner.resize((size_t)innerLen);
    return inner;
}

struct Pinned {
    const char* name;
    Bytes sealed;
    Bytes inner;
};

std::vector<Pinned> everyPinnedDatagram() {
    using namespace wire_vectors;
    return {
        {"usb gamepad", bytesOf(kUsbGamepad), bytesOf(kUsbGamepadInner)},
        {"usb motion", bytesOf(kUsbMotion), bytesOf(kUsbMotionInner)},
        {"usb touchpad v1", bytesOf(kUsbTouchpadV1), bytesOf(kUsbTouchpadV1Inner)},
        {"usb touchpad v2", bytesOf(kUsbTouchpadV2), bytesOf(kUsbTouchpadV2Inner)},
        {"framework gamepad", bytesOf(kFrameworkGamepad), bytesOf(kFrameworkGamepadInner)},
        {"heartbeat ping", bytesOf(kHeartbeatPing), bytesOf(kHeartbeatPingInner)},
        {"battery", bytesOf(kBattery), bytesOf(kBatteryInner)},
        {"sensor motion", bytesOf(kSensorMotion), bytesOf(kSensorMotionInner)},
        {"captured touchpad v2", bytesOf(kCapturedTouchpadV2), bytesOf(kCapturedTouchpadV2Inner)},
        {"screen gamepad", bytesOf(kScreenGamepad), bytesOf(kScreenGamepadInner)},
        {"mic audio", bytesOf(kMicAudio), bytesOf(kMicAudioInner)},
    };
}

} // namespace

using PinnedDatagrams = SodiumReady;

TEST_F(PinnedDatagrams, EachOpensAsTheSatelliteReadsItToTheInnerMessageItCarries) {
    for (const Pinned& pinned : everyPinnedDatagram()) {
        EXPECT_EQ(openAsTheSatellite(pinned.sealed), pinned.inner) << pinned.name;
    }
}

TEST_F(PinnedDatagrams, EachIsTheHeaderTheInnerMessageAndOneTag) {
    for (const Pinned& pinned : everyPinnedDatagram()) {
        EXPECT_EQ(pinned.sealed.size(),
                  dish_wire::OUTER_HEADER_BYTES + pinned.inner.size() + dish_wire::AEAD_TAG_BYTES)
            << pinned.name;
        EXPECT_EQ(Bytes(pinned.sealed.begin(), pinned.sealed.begin() + 4),
                  bytesOf(wire_vectors::kToken))
            << pinned.name;
    }
}

TEST_F(PinnedDatagrams, CarryTheSessionCountersInTheOrderTheyWereSent) {
    const std::vector<Pinned> pinned = everyPinnedDatagram();
    for (size_t i = 0; i < pinned.size(); i++) {
        EXPECT_EQ(counterOf(pinned[i].sealed), wire_vectors::kFirstCounter + i) << pinned[i].name;
    }
}

TEST_F(PinnedDatagrams, TheLastWireCounterStillOpens) {
    const Bytes ping = bytesOf(wire_vectors::kLastCounterPing);
    EXPECT_EQ(counterOf(ping), wire_vectors::kLastCounter);
    EXPECT_EQ(openAsTheSatellite(ping), bytesOf(wire_vectors::kHeartbeatPingInner));
}

TEST_F(PinnedDatagrams, ADatagramWithOneFlippedBitDoesNotOpen) {
    Bytes tampered = bytesOf(wire_vectors::kUsbGamepad);
    tampered[dish_wire::OUTER_HEADER_BYTES] ^= 0x01;
    EXPECT_TRUE(openAsTheSatellite(tampered).empty());
}

namespace {

class SendPath : public vector_session::VectorSession {
  protected:
    // Starts the counter at the pinned datagram's own.
    void counterAt(const Bytes& pinned) { session->counter.store(counterOf(pinned)); }
};

// One 50 ms sleep slice between pings.
constexpr int kFastHeartbeatMs = 50;
constexpr auto kWaitLimit = std::chrono::seconds(5);
constexpr auto kBlockedSenderWait = std::chrono::milliseconds(200);
constexpr int kConcurrentSenders = 4;
constexpr int kDatagramsPerSender = 2000;
constexpr int kReceiveTimeoutMs = 5000;
constexpr int kReKeySenders = 3;
constexpr int kTornReads = 200000;

bool gateIsFreeFromAnotherThread(dish_session::SendGate& gate) {
    bool free = false;
    std::thread probe([&gate, &free] {
        free = gate.tryHold();
        if (free) gate.release();
    });
    probe.join();
    return free;
}

Bytes openWith(const uint8_t key[32], const Bytes& datagram) {
    uint8_t nonce[12] = {};
    memcpy(nonce + 8, datagram.data() + 4, 4);
    Bytes inner(datagram.size());
    unsigned long long innerLen = 0;
    const int opened = crypto_aead_chacha20poly1305_ietf_decrypt(
        inner.data(), &innerLen, nullptr, datagram.data() + dish_wire::OUTER_HEADER_BYTES,
        datagram.size() - dish_wire::OUTER_HEADER_BYTES, datagram.data(), 4, nonce, key);
    if (opened != 0) return Bytes();
    inner.resize((size_t)innerLen);
    return inner;
}

} // namespace

TEST_F(SendPath, AHeartbeatPingIsThePinnedBytes) {
    counterAt(bytesOf(wire_vectors::kHeartbeatPing));
    EXPECT_TRUE(dish_send::sendEncrypted(session, dish_wire::MSG_HEARTBEAT_PING, nullptr, 0));
    EXPECT_EQ(sentBytes(), std::vector<Bytes>{bytesOf(wire_vectors::kHeartbeatPing)});
}

TEST_F(SendPath, BatteryIsThePinnedBytes) {
    counterAt(bytesOf(wire_vectors::kBattery));
    dish_send::sendBattery(session, 2, 87, 1);
    EXPECT_EQ(sentBytes(), std::vector<Bytes>{bytesOf(wire_vectors::kBattery)});
}

TEST_F(SendPath, SensorMotionIsThePinnedBytesAndIsCountedForItsController) {
    counterAt(bytesOf(wire_vectors::kSensorMotion));
    const dish_send::MotionSample sample{{-1, 2, -3}, {4096, -8192, 16384}, 4000};
    dish_send::sendMotion(session, 4, sample);
    EXPECT_EQ(sentBytes(), std::vector<Bytes>{bytesOf(wire_vectors::kSensorMotion)});
    EXPECT_EQ(session->motionByCtrl[4].load(), 1u);
}

TEST_F(SendPath, ACapturedTouchpadFrameIsThePinnedBytes) {
    counterAt(bytesOf(wire_vectors::kCapturedTouchpadV2));
    session->protocolVersion.store(2);
    gamepad::TouchpadState t;
    t.f0Active = true;
    t.clickDown = true;
    t.f0Id = (uint8_t)(300 & 0xFF);
    t.f0X = 111;
    t.f0Y = -222;
    dish_send::sendTouchpadFrame(session, 6, t, true, true, 0x3456789Au, -120);
    EXPECT_EQ(sentBytes(), std::vector<Bytes>{bytesOf(wire_vectors::kCapturedTouchpadV2)});
}

TEST_F(SendPath, AnOnScreenPadReportIsThePinnedBytesAndIsCountedForItsController) {
    counterAt(bytesOf(wire_vectors::kScreenGamepad));
    dish_send::XUSB_REPORT report{};
    report.wButtons = 0xF00F;
    report.bLeftTrigger = 255;
    report.bRightTrigger = 1;
    report.sThumbLX = 32767;
    report.sThumbLY = -32768;
    report.sThumbRX = 0;
    report.sThumbRY = -1;
    EXPECT_TRUE(dish_send::sendGamepadReport(session, 0, report, 0));
    EXPECT_EQ(sentBytes(), std::vector<Bytes>{bytesOf(wire_vectors::kScreenGamepad)});
    EXPECT_EQ(session->sentByCtrl[0].load(), 1u);
}

TEST_F(SendPath, AMicFrameIsFramedAsThePinnedBytes) {
    counterAt(bytesOf(wire_vectors::kMicAudio));
    uint8_t payload[dish_wire::AUDIO_WIRE_HEADER_BYTES + 5];
    dish_wire::encodeAudioFrameHeader(payload, 2, 0xFFFE);
    const uint8_t opus[5] = {0xFC, 0xFF, 0xFE, 0x01, 0x02};
    memcpy(payload + dish_wire::AUDIO_WIRE_HEADER_BYTES, opus, sizeof(opus));
    dish_send::sendEncrypted(session, dish_wire::MSG_MIC_AUDIO, payload, sizeof(payload));
    EXPECT_EQ(sentBytes(), std::vector<Bytes>{bytesOf(wire_vectors::kMicAudio)});
}

TEST_F(SendPath, TheHeartbeatThreadSendsThePinnedPingAndCountsItMissedUntilAcked) {
    counterAt(bytesOf(wire_vectors::kHeartbeatPing));
    const std::atomic<int> intervalMs{kFastHeartbeatMs};
    const dish_session::SessionRef s = session;
    s->heartbeat.start([s, &intervalMs] { dish_send::runHeartbeat(s, intervalMs); });
    const auto deadline = std::chrono::steady_clock::now() + kWaitLimit;
    std::vector<Bytes> sent;
    while (sent.empty() && std::chrono::steady_clock::now() < deadline) {
        for (Bytes& bytes : sentBytes()) sent.push_back(std::move(bytes));
        std::this_thread::yield();
    }
    s->heartbeat.stop();
    ASSERT_FALSE(sent.empty());
    EXPECT_EQ(sent.front(), bytesOf(wire_vectors::kHeartbeatPing));
    EXPECT_GE(s->rtt.pings.load(), 1u);
    EXPECT_GE(s->missedAcks.load(), 1);
}

TEST_F(SendPath, AHeartbeatWhoseSessionClosedEndsInsteadOfPingingTheNextOne) {
    const dish_session::SessionRef s = session;
    const std::atomic<int> intervalMs{kFastHeartbeatMs};
    table.close(handle);
    std::atomic<bool> ended{false};
    std::thread heartbeat([&] {
        s->heartbeat.start([] {});
        dish_send::runHeartbeat(s, intervalMs);
        ended = true;
    });
    heartbeat.join();
    s->heartbeat.stop();
    EXPECT_TRUE(ended.load());
    EXPECT_TRUE(sentBytes().empty());
}

TEST_F(SendPath, AMicFrameIsOneOpusPacketBehindItsControllerAndSequence) {
    const int16_t silence[dish_audio::AUDIO_FRAME_SAMPLES] = {};
    ASSERT_TRUE(dish_send::sendMicFrame(session, 2, silence));
    ASSERT_TRUE(dish_send::sendMicFrame(session, 2, silence));
    const std::vector<Bytes> sent = sentBytes();
    ASSERT_EQ(sent.size(), 2u);
    for (uint16_t seq = 0; seq < 2; seq++) {
        const Bytes inner = openAsTheSatellite(sent[seq]);
        ASSERT_GT(inner.size(), dish_wire::INNER_HEADER_BYTES + dish_wire::AUDIO_WIRE_HEADER_BYTES);
        EXPECT_EQ(inner[0] << 8 | inner[1], dish_wire::MSG_MIC_AUDIO);
        EXPECT_EQ((size_t)(inner[2] << 8 | inner[3]), inner.size() - dish_wire::INNER_HEADER_BYTES);
        const dish_wire::AudioFrameHeader h = dish_wire::decodeAudioFrameHeader(inner.data() + 4);
        EXPECT_EQ(h.ctrlIdx, 2);
        EXPECT_EQ(h.seq, seq);
    }
}

TEST_F(SendPath, ASendRefusedForBufferSpaceIsASoftDropThatSpendsItsCounter) {
    for (const int bufferFull : {EAGAIN, EWOULDBLOCK}) {
        const uint64_t before = session->counter.load();
        port.refuseWith(bufferFull);
        EXPECT_TRUE(dish_send::sendEncrypted(session, dish_wire::MSG_HEARTBEAT_PING, nullptr, 0));
        port.refuseWith(0);
        EXPECT_TRUE(sentBytes().empty());
        EXPECT_EQ(session->counter.load(), before + 1);
    }
    const uint64_t next = session->counter.load();
    EXPECT_TRUE(dish_send::sendEncrypted(session, dish_wire::MSG_HEARTBEAT_PING, nullptr, 0));
    const std::vector<Bytes> sent = sentBytes();
    ASSERT_EQ(sent.size(), 1u);
    EXPECT_EQ(counterOf(sent[0]), next);
}

TEST_F(SendPath, AnyOtherSendErrorFailsAndSpendsItsCounter) {
    const uint64_t before = session->counter.load();
    port.refuseWith(EIO);
    EXPECT_FALSE(dish_send::sendEncrypted(session, dish_wire::MSG_HEARTBEAT_PING, nullptr, 0));
    EXPECT_EQ(session->counter.load(), before + 1);
}

TEST_F(SendPath, AnExhaustedCounterGoesSilentAndGivesTheGateBack) {
    session->counter.store(wire_vectors::kLastCounter);
    EXPECT_TRUE(dish_send::sendEncrypted(session, dish_wire::MSG_HEARTBEAT_PING, nullptr, 0));
    EXPECT_EQ(sentBytes(), std::vector<Bytes>{bytesOf(wire_vectors::kLastCounterPing)});
    for (int i = 0; i < 3; i++) {
        EXPECT_FALSE(dish_send::sendEncrypted(session, dish_wire::MSG_HEARTBEAT_PING, nullptr, 0));
        EXPECT_TRUE(sentBytes().empty());
        EXPECT_TRUE(gateIsFreeFromAnotherThread(session->gate));
        EXPECT_EQ(dish_counter::sendCounterView(session->counter), 0xFFFFFFFFu);
    }
}

TEST_F(SendPath, AClosedSessionSendsNothingAndDrawsNoCounter) {
    const dish_session::SessionRef held = session;
    table.close(handle);
    const uint64_t before = held->counter.load();
    EXPECT_FALSE(dish_send::sendEncrypted(held, dish_wire::MSG_HEARTBEAT_PING, nullptr, 0));
    EXPECT_EQ(held->counter.load(), before);
    EXPECT_TRUE(gateIsFreeFromAnotherThread(held->gate));
    EXPECT_TRUE(sentBytes().empty());
}

TEST_F(SendPath, APayloadPastTheContractCeilingDrawsNoCounter) {
    const uint64_t before = session->counter.load();
    const std::vector<uint8_t> payload(dish_wire::MAX_INNER_PAYLOAD_BYTES + 1);
    EXPECT_FALSE(dish_send::sendEncrypted(session, dish_wire::MSG_MIC_AUDIO, payload.data(),
                                          (uint16_t)payload.size()));
    EXPECT_EQ(session->counter.load(), before);
    EXPECT_TRUE(sentBytes().empty());
}

TEST_F(SendPath, AFullCeilingPayloadIsExactlyOneDatagramCeiling) {
    const std::vector<uint8_t> payload(dish_wire::MAX_INNER_PAYLOAD_BYTES, 0x5A);
    EXPECT_TRUE(dish_send::sendEncrypted(session, dish_wire::MSG_MIC_AUDIO, payload.data(),
                                         (uint16_t)payload.size()));
    const std::vector<Bytes> sent = sentBytes();
    ASSERT_EQ(sent.size(), 1u);
    EXPECT_EQ(sent[0].size(), dish_wire::MAX_DATAGRAM_BYTES);
}

TEST_F(SendPath, AnEmptyBatchSendsNothing) {
    EXPECT_EQ(dish_send::sendFramed(session, nullptr, 0), 0u);
    EXPECT_EQ(dish_send::sendFramed(dish_session::SessionRef{}, nullptr, 1), 0u);
    EXPECT_TRUE(sentBytes().empty());
}

TEST_F(SendPath, ABatchGoesOutUnderConsecutiveCountersInTheOrderItWasFramed) {
    dish_send::Framed batch[3];
    dish_send::frameGamepadReport(batch[0], 1, 1, dish_send::XUSB_REPORT{}, 0);
    dish_send::frameMotion(batch[1], 1, dish_send::MotionSample{{1, 2, 3}, {4, 5, 6}, 7});
    ASSERT_TRUE(dish_send::frame(batch[2], dish_wire::MSG_HEARTBEAT_PING, nullptr, 0));
    const uint64_t first = session->counter.load();
    EXPECT_EQ(dish_send::sendFramed(session, batch, 3), 3u);
    const std::vector<Bytes> sent = sentBytes();
    ASSERT_EQ(sent.size(), 3u);
    const uint16_t types[3] = {dish_wire::MSG_GAMEPAD_DATA, dish_wire::MSG_MOTION,
                               dish_wire::MSG_HEARTBEAT_PING};
    for (size_t i = 0; i < 3; i++) {
        EXPECT_EQ(counterOf(sent[i]), first + i);
        const Bytes inner = openAsTheSatellite(sent[i]);
        ASSERT_GE(inner.size(), 2u);
        EXPECT_EQ(inner[0] << 8 | inner[1], types[i]);
    }
    EXPECT_EQ(session->sentByCtrl[1].load(), 1u);
    EXPECT_EQ(session->motionByCtrl[1].load(), 1u);
}

TEST_F(SendPath, ASecondSenderDrawsNoCounterWhileTheFirstHoldsTheGate) {
    recording::HeldPort held;
    held.attachTo(*session);
    held.holdNext();
    std::thread first(
        [this] { dish_send::sendEncrypted(session, dish_wire::MSG_HEARTBEAT_PING, nullptr, 0); });
    ASSERT_TRUE(held.waitUntilHolding());
    const uint64_t afterFirst = session->counter.load();
    std::atomic<bool> secondDone{false};
    std::thread second([this, &secondDone] {
        dish_send::sendEncrypted(session, dish_wire::MSG_HEARTBEAT_PING, nullptr, 0);
        secondDone = true;
    });
    std::this_thread::sleep_for(kBlockedSenderWait);
    EXPECT_FALSE(secondDone.load());
    EXPECT_EQ(session->counter.load(), afterFirst);
    held.letGo();
    first.join();
    second.join();
    const std::vector<Bytes> sent = held.taken();
    ASSERT_EQ(sent.size(), 2u);
    EXPECT_EQ(counterOf(sent[1]), counterOf(sent[0]) + 1);
}

TEST_F(SendPath, ConcurrentSendersReachTheWireInTheOrderTheirCountersWereDrawn) {
    std::vector<std::thread> senders;
    for (int i = 0; i < kConcurrentSenders; i++) {
        senders.emplace_back([this] {
            const uint8_t payload[dish_wire::GAMEPAD_PAYLOAD_BYTES] = {};
            for (int n = 0; n < kDatagramsPerSender; n++) {
                dish_send::sendEncrypted(session, dish_wire::MSG_GAMEPAD_DATA, payload,
                                         sizeof(payload));
            }
        });
    }
    for (std::thread& sender : senders) sender.join();
    const std::vector<recording::Sent> sent = port.taken();
    ASSERT_EQ(sent.size(), (size_t)(kConcurrentSenders * kDatagramsPerSender));
    std::set<std::thread::id> threads;
    for (size_t i = 0; i < sent.size(); i++) {
        threads.insert(sent[i].thread);
        ASSERT_EQ(counterOf(sent[i].bytes), wire_vectors::kFirstCounter + i) << "at " << i;
    }
    EXPECT_EQ(threads.size(), (size_t)kConcurrentSenders);
}

// Closing waits for the send in flight, which completes, and every send after it is refused.
TEST_F(SendPath, ClosingASessionLetsTheSendInFlightFinishThenRefusesTheNext) {
    recording::HeldPort held;
    held.attachTo(*session);
    held.holdNext();
    const dish_session::SessionRef s = session;
    std::thread sender([s] {
        EXPECT_TRUE(dish_send::sendEncrypted(s, dish_wire::MSG_HEARTBEAT_PING, nullptr, 0));
    });
    ASSERT_TRUE(held.waitUntilHolding());
    std::atomic<bool> closed{false};
    std::thread closer([this, &closed] {
        table.close(handle);
        closed = true;
    });
    std::this_thread::sleep_for(kBlockedSenderWait);
    EXPECT_FALSE(closed.load());
    held.letGo();
    sender.join();
    closer.join();
    EXPECT_EQ(held.taken().size(), 1u);
    EXPECT_FALSE(dish_send::sendEncrypted(s, dish_wire::MSG_HEARTBEAT_PING, nullptr, 0));
    EXPECT_TRUE(held.taken().empty());
}

// Senders stream while the session is re-keyed: every datagram opens under exactly one of the two
// keys, the old key's all reach the wire before the new key's first, and the new key's counters
// start over at 1. So no counter the new key restarts is ever sealed under the old one.
TEST_F(SendPath, AReKeyMidStreamNeverSealsARestartedCounterUnderTheOldKey) {
    const vector_session::Key oldKey = vector_session::vectorKey();
    vector_session::Key newKey{};
    for (int i = 0; i < 32; i++) newKey.bytes[i] = (uint8_t)(0xE0 + i);
    std::atomic<bool> stop{false};
    std::vector<std::thread> senders;
    for (int i = 0; i < kReKeySenders; i++) {
        senders.emplace_back([this, &stop] {
            const uint8_t payload[dish_wire::GAMEPAD_PAYLOAD_BYTES] = {};
            while (!stop.load()) {
                dish_send::sendEncrypted(session, dish_wire::MSG_GAMEPAD_DATA, payload,
                                         sizeof(payload));
            }
        });
    }
    std::this_thread::sleep_for(kBlockedSenderWait);
    dish_session::setConnectionParams(session, wire_vectors::kToken, newKey.bytes, 1);
    std::this_thread::sleep_for(kBlockedSenderWait);
    stop = true;
    for (std::thread& sender : senders) sender.join();

    const std::vector<Bytes> sent = sentBytes();
    size_t oldCount = 0;
    size_t newCount = 0;
    uint32_t lastOld = 0;
    uint32_t lastNew = 0;
    for (const Bytes& datagram : sent) {
        const bool opensOld = !openWith(oldKey.bytes, datagram).empty();
        const bool opensNew = !openWith(newKey.bytes, datagram).empty();
        ASSERT_NE(opensOld, opensNew) << "a datagram must open under exactly one key";
        const uint32_t counter = counterOf(datagram);
        if (opensOld) {
            ASSERT_EQ(newCount, 0u) << "an old-key datagram after a new-key one";
            ASSERT_GT(counter, lastOld);
            lastOld = counter;
            oldCount++;
            continue;
        }
        ASSERT_EQ(counter, newCount == 0 ? 1u : lastNew + 1);
        lastNew = counter;
        newCount++;
    }
    EXPECT_GT(oldCount, 0u);
    EXPECT_GT(newCount, 0u);
}

// The receive thread reads the material without the gate: under a cold path that keeps
// re-keying, every read is one whole (token, key) pair, never half of one and half of the other.
TEST_F(SendPath, AMaterialReadRacingAReKeyIsNeverTorn) {
    const uint8_t tokenA[4] = {0xA1, 0xA2, 0xA3, 0xA4};
    const uint8_t tokenB[4] = {0xB1, 0xB2, 0xB3, 0xB4};
    uint8_t keyA[32];
    uint8_t keyB[32];
    memset(keyA, 0xAA, sizeof(keyA));
    memset(keyB, 0xBB, sizeof(keyB));
    std::atomic<bool> stop{false};
    std::thread rekeyer([&] {
        for (bool a = true; !stop.load(); a = !a) {
            dish_session::setConnectionParams(session, a ? tokenA : tokenB, a ? keyA : keyB, 1);
        }
    });
    size_t torn = 0;
    for (int i = 0; i < kTornReads; i++) {
        const dish_session::Material m = dish_session::readMaterial(*session);
        const bool isA = memcmp(m.token, tokenA, 4) == 0 && memcmp(m.key, keyA, 32) == 0;
        const bool isB = memcmp(m.token, tokenB, 4) == 0 && memcmp(m.key, keyB, 32) == 0;
        const bool isFirst = memcmp(m.token, wire_vectors::kToken, 4) == 0;
        if (!isA && !isB && !isFirst) torn++;
    }
    stop = true;
    rekeyer.join();
    EXPECT_EQ(torn, 0u);
}

TEST_F(SendPath, TheSocketPortPutsThePinnedBytesOnTheWire) {
    fake_satellite::Peer satellite;
    const int toSatellite = table.open("127.0.0.1", satellite.port());
    const dish_session::SessionRef s = table.find(toSatellite);
    const vector_session::Key key = vector_session::vectorKey();
    dish_session::setConnectionParams(s, wire_vectors::kToken, key.bytes, 1);
    s->counter.store(counterOf(bytesOf(wire_vectors::kHeartbeatPing)));
    EXPECT_TRUE(dish_send::sendEncrypted(s, dish_wire::MSG_HEARTBEAT_PING, nullptr, 0));
    EXPECT_EQ(satellite.receive(kReceiveTimeoutMs), bytesOf(wire_vectors::kHeartbeatPing));
    table.close(toSatellite);
}

namespace {

dish_send::XUSB_REPORT pinnedScreenReport() {
    dish_send::XUSB_REPORT report{};
    report.wButtons = 0xF00F;
    report.bLeftTrigger = 255;
    report.bRightTrigger = 1;
    report.sThumbLX = 32767;
    report.sThumbLY = -32768;
    report.sThumbRX = 0;
    report.sThumbRY = -1;
    return report;
}

// The pinned inner message with the protocol 4 tail: msgLen 17 and the word behind the report.
Bytes pinnedScreenInnerWithTail(const uint32_t extButtons) {
    Bytes expected = bytesOf(wire_vectors::kScreenGamepadInner);
    expected[3] = 0x11;
    expected.push_back((uint8_t)extButtons);
    expected.push_back((uint8_t)(extButtons >> 8));
    expected.push_back((uint8_t)(extButtons >> 16));
    expected.push_back((uint8_t)(extButtons >> 24));
    return expected;
}

} // namespace

TEST_F(SendPath, AReportOnAProtocol4SessionCarriesTheExtendedButtonsWordBehindTheReport) {
    session->protocolVersion.store(4);
    EXPECT_TRUE(dish_send::sendGamepadReport(session, 0, pinnedScreenReport(), 0x00001003u));
    const std::vector<Bytes> sent = sentBytes();
    ASSERT_EQ(sent.size(), 1u);
    EXPECT_EQ(openAsTheSatellite(sent[0]), pinnedScreenInnerWithTail(0x00001003u));
    EXPECT_EQ(session->sentByCtrl[0].load(), 1u);
}

TEST_F(SendPath, AReportOnASessionBeforeProtocol4IsThePinnedThirteenByteFrameWhateverTheWord) {
    for (int32_t version = 1; version < 4; version++) {
        session->protocolVersion.store(version);
        counterAt(bytesOf(wire_vectors::kScreenGamepad));
        EXPECT_TRUE(dish_send::sendGamepadReport(session, 0, pinnedScreenReport(), 0x00001FFFu));
        EXPECT_EQ(sentBytes(), std::vector<Bytes>{bytesOf(wire_vectors::kScreenGamepad)})
            << "protocol " << version;
    }
}

TEST_F(SendPath, AReportForASessionTheTableDoesNotKnowGoesNowhere) {
    EXPECT_FALSE(dish_send::sendGamepadReport(table.find(handle + 1), 0, pinnedScreenReport(), 1));
    EXPECT_TRUE(sentBytes().empty());
}
