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

bool isFreeFromAnotherThread(TrackedMutex& mtx) {
    bool free = false;
    std::thread probe([&mtx, &free] {
        free = mtx.try_lock();
        if (free) mtx.unlock();
    });
    probe.join();
    return free;
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
    dish_send::sendGamepadReport(session, 0, report);
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
    const std::shared_ptr<dish_session::Session> s = session;
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

TEST_F(SendPath, AnExhaustedCounterGoesSilentAndGivesTheTurnBack) {
    session->counter.store(wire_vectors::kLastCounter);
    EXPECT_TRUE(dish_send::sendEncrypted(session, dish_wire::MSG_HEARTBEAT_PING, nullptr, 0));
    EXPECT_EQ(sentBytes(), std::vector<Bytes>{bytesOf(wire_vectors::kLastCounterPing)});
    for (int i = 0; i < 3; i++) {
        EXPECT_FALSE(dish_send::sendEncrypted(session, dish_wire::MSG_HEARTBEAT_PING, nullptr, 0));
        EXPECT_TRUE(sentBytes().empty());
        EXPECT_TRUE(isFreeFromAnotherThread(session->sendTurnMtx));
        EXPECT_EQ(dish_counter::sendCounterView(session->counter), 0xFFFFFFFFu);
    }
}

TEST_F(SendPath, AClosedSessionStagesNothingButSpendsTheCounterItDrew) {
    const std::shared_ptr<dish_session::Session> held = session;
    table.close(handle);
    const uint64_t before = held->counter.load();
    dish_send::PendingDatagram datagram;
    EXPECT_FALSE(
        dish_send::stageDatagram(held, dish_wire::MSG_HEARTBEAT_PING, nullptr, 0, datagram));
    EXPECT_FALSE(datagram.turn.owns_lock());
    EXPECT_EQ(held->counter.load(), before + 1);
    EXPECT_FALSE(dish_send::transmitDatagram(datagram));
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

TEST_F(SendPath, ADatagramNeverStagedTransmitsNothing) {
    dish_send::PendingDatagram datagram;
    EXPECT_FALSE(dish_send::transmitDatagram(datagram));
    EXPECT_TRUE(sentBytes().empty());
}

TEST_F(SendPath, ASecondSenderDrawsNoCounterWhileTheFirstHasNotLeftTheWire) {
    dish_send::PendingDatagram first;
    ASSERT_TRUE(
        dish_send::stageDatagram(session, dish_wire::MSG_HEARTBEAT_PING, nullptr, 0, first));
    const uint64_t afterFirst = session->counter.load();
    std::atomic<bool> secondDone{false};
    std::thread second([this, &secondDone] {
        dish_send::sendEncrypted(session, dish_wire::MSG_HEARTBEAT_PING, nullptr, 0);
        secondDone = true;
    });
    std::this_thread::sleep_for(kBlockedSenderWait);
    EXPECT_FALSE(secondDone.load());
    EXPECT_EQ(session->counter.load(), afterFirst);
    EXPECT_TRUE(dish_send::transmitDatagram(first));
    second.join();
    const std::vector<Bytes> sent = sentBytes();
    ASSERT_EQ(sent.size(), 2u);
    EXPECT_EQ(counterOf(sent[1]), counterOf(sent[0]) + 1);
}

TEST_F(SendPath, ConcurrentSendersReachTheWireInTheOrderTheirCountersWereDrawn) {
    std::vector<std::thread> senders;
    for (int i = 0; i < kConcurrentSenders; i++) {
        senders.emplace_back([this] {
            const uint8_t payload[dish_send::GAMEPAD_PAYLOAD_BYTES] = {};
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

TEST_F(SendPath, OnlyAGamepadReportClosesTheStage1BenchWindow) {
    hotpath::setEnabled(true);
    hotpath::statsJson(true);
    hotpath::markInputRead(1);
    dish_send::PendingDatagram motion;
    const dish_send::MotionSample sample{{1, 2, 3}, {4, 5, 6}, 7};
    dish_send::stageMotion(session, 0, sample, motion);
    dish_send::transmitPublished(motion);
    const std::string afterMotion = hotpath::statsJson(false);
    dish_send::PendingDatagram gamepad;
    dish_send::stageGamepadReport(session, 0, dish_send::XUSB_REPORT{}, gamepad);
    dish_send::transmitPublished(gamepad);
    const std::string afterGamepad = hotpath::statsJson(true);
    hotpath::setEnabled(false);
    EXPECT_NE(afterMotion.find("\"stage1_hotpath_us\":{\"n\":0}"), std::string::npos)
        << afterMotion;
    EXPECT_NE(afterGamepad.find("\"stage1_hotpath_us\":{\"n\":1,"), std::string::npos)
        << afterGamepad;
}

TEST_F(SendPath, TheSocketPortPutsThePinnedBytesOnTheWire) {
    fake_satellite::Peer satellite;
    session->port = dish_net::DatagramPort{};
    ASSERT_TRUE(dish_net::ipv4Destination("127.0.0.1", satellite.port(), session->dest));
    counterAt(bytesOf(wire_vectors::kHeartbeatPing));
    EXPECT_TRUE(dish_send::sendEncrypted(session, dish_wire::MSG_HEARTBEAT_PING, nullptr, 0));
    EXPECT_EQ(satellite.receive(kReceiveTimeoutMs), bytesOf(wire_vectors::kHeartbeatPing));
}
