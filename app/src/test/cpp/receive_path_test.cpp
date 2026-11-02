// SPDX-License-Identifier: LGPL-3.0-or-later

#include "receive_path.h"

#include <gtest/gtest.h>
#include <sodium.h>

#include <chrono>
#include <cstdint>
#include <cstring>
#include <memory>
#include <thread>
#include <vector>

#include "audio_codec.h"
#include "fake_satellite.h"
#include "recording_sinks.h"
#include "send_path.h"
#include "session_table.h"
#include "wire_encoders.h"

namespace {

using Bytes = std::vector<uint8_t>;

constexpr uint8_t kToken[4] = {0x10, 0x20, 0x30, 0x40};
constexpr uint8_t kForeignToken[4] = {0x10, 0x20, 0x30, 0x41};
constexpr int kUnknownHandle = 999;
constexpr int kReceiveTimeoutMs = 5000;
constexpr long long kReceiveTimeoutTickMs = 500;

struct Key {
    uint8_t bytes[32];
};

Key sessionKey() {
    Key key{};
    for (int i = 0; i < 32; i++) key.bytes[i] = (uint8_t)(0x80 + i);
    return key;
}

class ReceivePath : public ::testing::Test {
  protected:
    static void SetUpTestSuite() { ASSERT_GE(sodium_init(), 0); }

    void SetUp() override {
        handle = table.open("127.0.0.1", 9);
        ASSERT_GT(handle, 0);
        session = table.find(handle);
        dish_session::setConnectionParams(session, kToken, key.bytes, 3);
    }

    void TearDown() override { table.close(handle); }

    // A second session of the same token and key, aimed at a peer on loopback.
    dish_session::SessionRef sessionTo(const fake_satellite::Peer& peer) {
        const dish_session::SessionRef s = table.find(table.open("127.0.0.1", peer.port()));
        dish_session::setConnectionParams(s, kToken, key.bytes, 3);
        return s;
    }

    Bytes fromSatellite(const uint32_t counter, const uint16_t msgType, const Bytes& payload) {
        return fake_satellite::sealToClient(kToken, key.bytes, counter, msgType, payload.data(),
                                            payload.size());
    }

    int deliver(const Bytes& datagram) {
        return dish_receive::handleDatagram(session, datagram.data(), datagram.size(), sink);
    }

    const Key key = sessionKey();
    dish_session::SessionTable table;
    int handle = -1;
    dish_session::SessionRef session;
    recording::Feedback sink;
};

} // namespace

TEST_F(ReceivePath, AnEnrichedAckRevivesTheLinkAndRecordsTheSatellitesState) {
    session->missedAcks.store(4);
    session->connectionAlive.store(false);
    EXPECT_EQ(
        deliver(fromSatellite(1, dish_wire::MSG_HEARTBEAT_ACK, {1, 3, 0x01, 0x02, 0x00, 0x05})), 1);
    EXPECT_EQ(session->missedAcks.load(), 0);
    EXPECT_TRUE(session->connectionAlive.load());
    EXPECT_EQ(session->vigemAvailable.load(), 1);
    EXPECT_EQ(session->activeControllerCount.load(), 3);
    EXPECT_EQ(session->serverEpoch.load(), 0x0102);
    EXPECT_EQ(session->activeBitmap.load(), 0x0005);
    EXPECT_EQ(session->rtt.acks.load(), 1u);
    EXPECT_EQ(session->lastRxCounter.load(), 1u);
}

TEST_F(ReceivePath, APlainAckLeavesTheEnrichedStateUnseen) {
    EXPECT_EQ(deliver(fromSatellite(1, dish_wire::MSG_HEARTBEAT_ACK, {})), 1);
    EXPECT_EQ(session->serverEpoch.load(), -1);
    EXPECT_EQ(session->activeBitmap.load(), -1);
    EXPECT_EQ(session->vigemAvailable.load(), -1);
}

TEST_F(ReceivePath, ACloseNotifyEndsTheSessionWithItsReason) {
    EXPECT_EQ(deliver(fromSatellite(1, dish_wire::MSG_SESSION_CLOSE, {7})), 1);
    EXPECT_EQ(session->closeReason.load(), 7);
    EXPECT_FALSE(session->connectionAlive.load());
}

TEST_F(ReceivePath, RumbleIsHandedOnDecoded) {
    EXPECT_EQ(
        deliver(fromSatellite(1, dish_wire::MSG_RUMBLE, {2, 0xFF, 0xEE, 0x01, 0x02, 0x03, 0xE8})),
        1);
    ASSERT_EQ(sink.rumbles.size(), 1u);
    EXPECT_EQ(sink.rumbles[0].handle, handle);
    EXPECT_EQ(sink.rumbles[0].ctrlIdx, 2);
    EXPECT_EQ(sink.rumbles[0].strong, 0xFFEE);
    EXPECT_EQ(sink.rumbles[0].weak, 0x0102);
    EXPECT_EQ(sink.rumbles[0].durationMs, 1000);
}

TEST_F(ReceivePath, AFeedbackMessageOfTheWrongLengthIsOpenedButNotHandedOn) {
    EXPECT_EQ(deliver(fromSatellite(1, dish_wire::MSG_RUMBLE, {2, 0xFF, 0xEE, 0x01, 0x02, 0x03})),
              1);
    EXPECT_EQ(deliver(fromSatellite(2, dish_wire::MSG_LIGHTBAR, {1, 2, 3, 4, 5})), 1);
    EXPECT_EQ(deliver(fromSatellite(3, dish_wire::MSG_PLAYER_LEDS, {1})), 1);
    EXPECT_EQ(deliver(fromSatellite(4, dish_wire::MSG_SPEAKER_AUDIO, {1, 0, 0})), 1);
    EXPECT_EQ(sink.calls(), 0u);
    EXPECT_EQ(session->lastRxCounter.load(), 4u);
}

TEST_F(ReceivePath, LightbarTriggerEffectsAndPlayerLedsAreHandedOn) {
    EXPECT_EQ(deliver(fromSatellite(1, dish_wire::MSG_LIGHTBAR, {1, 0x10, 0x20, 0x30})), 1);
    Bytes effects{3};
    for (int i = 0; i < dish_wire::TRIGGER_EFFECTS_PAYLOAD_BYTES; i++) {
        effects.push_back((uint8_t)i);
    }
    EXPECT_EQ(deliver(fromSatellite(2, dish_wire::MSG_TRIGGER_EFFECTS, effects)), 1);
    EXPECT_EQ(deliver(fromSatellite(3, dish_wire::MSG_PLAYER_LEDS, {0, 0x15})), 1);
    ASSERT_EQ(sink.lightbars.size(), 1u);
    EXPECT_EQ(sink.lightbars[0].ctrlIdx, 1);
    EXPECT_EQ(sink.lightbars[0].b, 0x30);
    ASSERT_EQ(sink.triggerEffectsSeen.size(), 1u);
    EXPECT_EQ(sink.triggerEffectsSeen[0], effects);
    ASSERT_EQ(sink.playerLedsSeen.size(), 1u);
    EXPECT_EQ(sink.playerLedsSeen[0].ledMask, 0x15);
}

TEST_F(ReceivePath, AMicLedIsHandedOnOnlyInAStateTheContractNames) {
    EXPECT_EQ(
        deliver(fromSatellite(1, dish_wire::MSG_MIC_LED, {1, dish_wire::MIC_LED_STATE_PULSE})), 1);
    EXPECT_EQ(
        deliver(fromSatellite(2, dish_wire::MSG_MIC_LED, {1, dish_wire::MIC_LED_STATE_COUNT})), 1);
    ASSERT_EQ(sink.micLeds.size(), 1u);
    EXPECT_EQ(sink.micLeds[0].state, dish_wire::MIC_LED_STATE_PULSE);
}

TEST_F(ReceivePath, SpeakerAndHapticAudioAreHandedOnWithTheirLane) {
    EXPECT_EQ(deliver(fromSatellite(1, dish_wire::MSG_SPEAKER_AUDIO, {4, 0x01, 0x02, 0xAA, 0xBB})),
              1);
    EXPECT_EQ(deliver(fromSatellite(2, dish_wire::MSG_HAPTIC_AUDIO, {5, 0x00, 0x09, 0xCC})), 1);
    ASSERT_EQ(sink.audioSeen.size(), 2u);
    EXPECT_EQ(sink.audioSeen[0].lane, dish_receive::AUDIO_LANE_SPEAKER);
    EXPECT_EQ(sink.audioSeen[0].ctrlIdx, 4);
    EXPECT_EQ(sink.audioSeen[0].seq, 0x0102);
    EXPECT_EQ(sink.audioSeen[0].opus, (Bytes{0xAA, 0xBB}));
    EXPECT_EQ(sink.audioSeen[1].lane, dish_receive::AUDIO_LANE_HAPTICS);
    EXPECT_EQ(sink.audioSeen[1].seq, 9);
    EXPECT_EQ(sink.audioSeen[1].opus, (Bytes{0xCC}));
}

TEST_F(ReceivePath, ADatagramForAnotherTokenIsDropped) {
    const Bytes payload{0, 0, 0, 0, 0, 0, 0};
    const Bytes foreign = fake_satellite::sealToClient(
        kForeignToken, key.bytes, 1, dish_wire::MSG_RUMBLE, payload.data(), payload.size());
    EXPECT_EQ(deliver(foreign), 0);
    EXPECT_EQ(sink.calls(), 0u);
    EXPECT_EQ(session->lastRxCounter.load(), 0u);
}

TEST_F(ReceivePath, TheFirstDatagramMayCarryAnyCounterAndLaterOnesMustRise) {
    EXPECT_EQ(deliver(fromSatellite(5, dish_wire::MSG_HEARTBEAT_ACK, {})), 1);
    EXPECT_EQ(deliver(fromSatellite(5, dish_wire::MSG_HEARTBEAT_ACK, {})), 0);
    EXPECT_EQ(deliver(fromSatellite(4, dish_wire::MSG_HEARTBEAT_ACK, {})), 0);
    EXPECT_EQ(deliver(fromSatellite(6, dish_wire::MSG_HEARTBEAT_ACK, {})), 1);
    EXPECT_EQ(session->rtt.acks.load(), 2u);
    EXPECT_EQ(session->lastRxCounter.load(), 6u);
}

TEST_F(ReceivePath, AForgedDatagramIsDroppedWithoutMovingTheReplayGuard) {
    Bytes forged = fromSatellite(9, dish_wire::MSG_RUMBLE, {2, 0, 1, 0, 1, 0, 1});
    forged[dish_wire::OUTER_HEADER_BYTES + 1] ^= 0x80;
    EXPECT_EQ(deliver(forged), 0);
    EXPECT_EQ(session->lastRxCounter.load(), 0u);
    EXPECT_EQ(deliver(fromSatellite(9, dish_wire::MSG_RUMBLE, {2, 0, 1, 0, 1, 0, 1})), 1);
    EXPECT_EQ(sink.rumbles.size(), 1u);
}

TEST_F(ReceivePath, TheClientsOwnDirectionDoesNotOpen) {
    fake_satellite::Peer echo;
    const dish_session::SessionRef toEcho = sessionTo(echo);
    ASSERT_TRUE(dish_send::sendEncrypted(toEcho, dish_wire::MSG_HEARTBEAT_ACK, nullptr, 0));
    const Bytes ownDirection = echo.receive(kReceiveTimeoutMs);
    ASSERT_FALSE(ownDirection.empty());
    EXPECT_EQ(deliver(ownDirection), 0);
}

TEST_F(ReceivePath, ARuntAnOversizeDatagramAndAHeaderlessMessageAreDropped) {
    EXPECT_EQ(deliver(Bytes(kToken, kToken + 4)), 0);
    EXPECT_EQ(deliver(Bytes(dish_wire::MAX_DATAGRAM_BYTES + 1, 0)), 0);
    EXPECT_EQ(session->lastRxCounter.load(), 0u);

    const uint8_t shortInner[2] = {0x00, 0x03};
    Bytes headerless(dish_wire::OUTER_HEADER_BYTES + sizeof(shortInner) +
                     dish_wire::AEAD_TAG_BYTES);
    memcpy(headerless.data(), kToken, 4);
    headerless[7] = 3;
    uint8_t nonce[12] = {};
    nonce[0] = dish_wire::CRYPTO_DIR_SERVER_TO_CLIENT;
    nonce[11] = 3;
    unsigned long long sealedLen = 0;
    crypto_aead_chacha20poly1305_ietf_encrypt(headerless.data() + dish_wire::OUTER_HEADER_BYTES,
                                              &sealedLen, shortInner, sizeof(shortInner), kToken, 4,
                                              nullptr, nonce, key.bytes);
    EXPECT_EQ(deliver(headerless), 0);
    EXPECT_EQ(session->lastRxCounter.load(), 3u);
}

TEST_F(ReceivePath, TheReceiveLoopReadsWhatTheSatelliteSendsBack) {
    fake_satellite::Peer satellite;
    const dish_session::SessionRef toSatellite = sessionTo(satellite);
    ASSERT_TRUE(dish_send::sendEncrypted(toSatellite, dish_wire::MSG_HEARTBEAT_PING, nullptr, 0));
    ASSERT_FALSE(satellite.receive(kReceiveTimeoutMs).empty());
    ASSERT_TRUE(satellite.reply(fromSatellite(1, dish_wire::MSG_RUMBLE, {1, 0, 9, 0, 8, 0, 7})));
    EXPECT_EQ(dish_receive::receiveDatagram(table, toSatellite.handle, sink), 1);
    ASSERT_EQ(sink.rumbles.size(), 1u);
    EXPECT_EQ(sink.rumbles[0].strong, 9);
}

TEST_F(ReceivePath, AQuietSocketIsAReceiveTimeoutNotAnEnd) {
    const auto start = std::chrono::steady_clock::now();
    EXPECT_EQ(dish_receive::receiveDatagram(table, handle, sink), 0);
    const auto waited = std::chrono::steady_clock::now() - start;
    EXPECT_GE(std::chrono::duration_cast<std::chrono::milliseconds>(waited).count(),
              kReceiveTimeoutTickMs / 2);
}

TEST_F(ReceivePath, AnUnknownOrClosedSessionEndsTheLoop) {
    EXPECT_EQ(dish_receive::receiveDatagram(table, kUnknownHandle, sink), -1);
    table.close(handle);
    EXPECT_EQ(dish_receive::receiveDatagram(table, handle, sink), -1);
}

TEST_F(ReceivePath, SpeakerFramesDecodeInOrderOnTheirOwnLane) {
    const std::unique_ptr<dish_audio::OpusStreamEncoder> encoder =
        dish_audio::OpusStreamEncoder::create(dish_audio::Stream::Speaker);
    ASSERT_NE(encoder, nullptr);
    const std::vector<int16_t> stereo(dish_audio::AUDIO_SPEAKER_FRAME_SAMPLES, 0);
    std::vector<int16_t> pcm(static_cast<size_t>(dish_audio::AUDIO_JITTER_MAX_EVENTS_PER_PUSH) *
                             dish_audio::AUDIO_SPEAKER_FRAME_SAMPLES);
    bool concealed[dish_audio::AUDIO_JITTER_MAX_EVENTS_PER_PUSH] = {};
    int produced = 0;
    for (uint16_t seq = 0; seq < 3; seq++) {
        uint8_t packet[512];
        const size_t len =
            encoder->encode(stereo.data(), dish_audio::AUDIO_FRAME_SAMPLES, packet, sizeof(packet));
        ASSERT_GT(len, 0u);
        const int frames = dish_receive::decodeSpeakerAudio(
            session, 1, dish_receive::AUDIO_LANE_HAPTICS, seq, packet, len, pcm, concealed);
        for (int i = 0; i < frames; i++) EXPECT_FALSE(concealed[i]);
        produced += frames;
    }
    EXPECT_EQ(produced, 3);
    EXPECT_NE(session->audio[1].hapticDecoder, nullptr);
    EXPECT_EQ(session->audio[1].speakerDecoder, nullptr);
}

// The session's receive thread as the JNI layer starts it: it hands on what the satellite sends,
// updates the session from an ack, and closing the session stops it before the socket goes.
TEST_F(ReceivePath, TheReceiveThreadHandsOnWhatArrivesAndEndsWithItsSession) {
    fake_satellite::Peer satellite;
    const dish_session::SessionRef s = sessionTo(satellite);
    s->receiver.start([this, s] { dish_receive::runReceiveLoop(table, s, sink); });
    ASSERT_TRUE(dish_send::sendEncrypted(s, dish_wire::MSG_HEARTBEAT_PING, nullptr, 0));
    ASSERT_FALSE(satellite.receive(kReceiveTimeoutMs).empty());
    ASSERT_TRUE(
        satellite.reply(fromSatellite(1, dish_wire::MSG_HEARTBEAT_ACK, {1, 3, 0, 9, 0, 5})));
    ASSERT_TRUE(satellite.reply(fromSatellite(2, dish_wire::MSG_RUMBLE, {1, 0, 9, 0, 8, 0, 7})));
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(5);
    while (sink.calls() == 0 && std::chrono::steady_clock::now() < deadline) {
        std::this_thread::yield();
    }
    EXPECT_EQ(s->serverEpoch.load(), 9);
    EXPECT_EQ(s->activeBitmap.load(), 5);

    const auto closing = std::chrono::steady_clock::now();
    table.close(s.handle);
    const auto closeTook = std::chrono::steady_clock::now() - closing;
    EXPECT_FALSE(s->receiver.running());
    EXPECT_LT(closeTook, std::chrono::seconds(2));
    ASSERT_EQ(sink.rumbles.size(), 1u);
    EXPECT_EQ(sink.rumbles[0].strong, 9);
}
