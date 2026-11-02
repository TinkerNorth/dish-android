// SPDX-License-Identifier: LGPL-3.0-or-later

#include "wire_encoders.h"
#include "wire_vectors.h"

#include <gtest/gtest.h>
#include <sodium.h>

#include <cstdint>
#include <cstring>
#include <vector>

namespace {

using Bytes = std::vector<uint8_t>;

template <size_t N> Bytes bytesOf(const uint8_t (&array)[N]) { return Bytes(array, array + N); }

class SodiumReady : public ::testing::Test {
  protected:
    static void SetUpTestSuite() { ASSERT_GE(sodium_init(), 0); }
};

uint32_t counterOf(const Bytes& datagram) {
    return (uint32_t)datagram[4] << 24 | (uint32_t)datagram[5] << 16 | (uint32_t)datagram[6] << 8 |
           (uint32_t)datagram[7];
}

// What the satellite reads out of a client datagram (contract §Crypto): the header's token and
// counter, the nonce direction 0 with the counter in its last four bytes, the token as the AAD.
// Empty when the datagram does not open under the vectors' session key.
Bytes openAsTheSatellite(const Bytes& datagram) {
    uint8_t key[32];
    for (int i = 0; i < 32; i++) key[i] = (uint8_t)(wire_vectors::kKeyBase + i);
    uint8_t nonce[12] = {};
    nonce[0] = 0x00;
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
