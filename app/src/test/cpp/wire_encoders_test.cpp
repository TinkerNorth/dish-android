// SPDX-License-Identifier: LGPL-3.0-or-later

#include "wire_encoders.h"

#include <gtest/gtest.h>

#include <cstdint>

namespace {

int16_t readLe16(const uint8_t* p) {
    return static_cast<int16_t>(static_cast<uint16_t>(p[0]) |
                                (static_cast<uint16_t>(p[1]) << 8));
}

uint32_t readLe32(const uint8_t* p) {
    return static_cast<uint32_t>(p[0]) | (static_cast<uint32_t>(p[1]) << 8) |
           (static_cast<uint32_t>(p[2]) << 16) | (static_cast<uint32_t>(p[3]) << 24);
}

}

TEST(EncodeMotionPayload, CtrlIdxAtByteZero) {
    uint8_t out[17]{};
    dish_wire::encodeMotionPayload(out, 7, 0, 0, 0, 0, 0, 0, 0);
    EXPECT_EQ(out[0], 7);
}

TEST(EncodeMotionPayload, GyroAndAccelAsLittleEndianInt16) {
    uint8_t out[17]{};
    dish_wire::encodeMotionPayload(out, 0, 0x0102, 0x0304, 0x0506,
                                   0x0708, 0x090A, 0x0B0C, 0);
    EXPECT_EQ(readLe16(&out[1]), 0x0102);
    EXPECT_EQ(readLe16(&out[3]), 0x0304);
    EXPECT_EQ(readLe16(&out[5]), 0x0506);
    EXPECT_EQ(readLe16(&out[7]), 0x0708);
    EXPECT_EQ(readLe16(&out[9]), 0x090A);
    EXPECT_EQ(readLe16(&out[11]), 0x0B0C);
}

TEST(EncodeMotionPayload, TimestampDeltaUsAsLittleEndianUint32) {
    uint8_t out[17]{};
    dish_wire::encodeMotionPayload(out, 0, 0, 0, 0, 0, 0, 0, 0xDEADBEEFU);
    EXPECT_EQ(readLe32(&out[13]), 0xDEADBEEFU);
}

TEST(EncodeMotionPayload, FullInt16RangeWithoutOverflow) {
    uint8_t out[17]{};
    dish_wire::encodeMotionPayload(out, 0xFF, -32768, 32767, 0,
                                   -32768, 32767, -1, 0);
    EXPECT_EQ(out[0], 0xFF);
    EXPECT_EQ(readLe16(&out[1]), -32768);
    EXPECT_EQ(readLe16(&out[3]), 32767);
    EXPECT_EQ(readLe16(&out[7]), -32768);
    EXPECT_EQ(readLe16(&out[9]), 32767);
    EXPECT_EQ(readLe16(&out[11]), -1);
}

TEST(EncodeMotionPayload, Uint32MaxDelta) {
    uint8_t out[17]{};
    dish_wire::encodeMotionPayload(out, 0, 0, 0, 0, 0, 0, 0, 0xFFFFFFFFU);
    EXPECT_EQ(readLe32(&out[13]), 0xFFFFFFFFU);
}

TEST(EncodeMotionPayload, ZeroDeltaIsFourZeroBytes) {
    // Receiver expects exactly 0 (not a sentinel) so the inter-arrival timer can detect session start.
    uint8_t out[17]{};
    dish_wire::encodeMotionPayload(out, 0, 0, 0, 0, 0, 0, 0, 0);
    EXPECT_EQ(out[13], 0);
    EXPECT_EQ(out[14], 0);
    EXPECT_EQ(out[15], 0);
    EXPECT_EQ(out[16], 0);
}

TEST(EncodeBatteryPayload, CtrlIdxLevelStatusInOrder) {
    uint8_t out[3]{};
    dish_wire::encodeBatteryPayload(out, 5, 77, 2);
    EXPECT_EQ(out[0], 5);
    EXPECT_EQ(out[1], 77);
    EXPECT_EQ(out[2], 2);
}

TEST(EncodeBatteryPayload, PreservesUnknownLevelSentinel) {
    uint8_t out[3]{};
    dish_wire::encodeBatteryPayload(out, 0, 0xFF, 0);
    EXPECT_EQ(out[1], 0xFF);
    EXPECT_EQ(out[2], 0);
}

TEST(DecodeLightbarPayload, CtrlIdxThenRgbInOrder) {
    const uint8_t in[4] = {3, 0x11, 0x22, 0x33};
    const dish_wire::LightbarPayload lb = dish_wire::decodeLightbarPayload(in);
    EXPECT_EQ(lb.ctrlIdx, 3);
    EXPECT_EQ(lb.r, 0x11);
    EXPECT_EQ(lb.g, 0x22);
    EXPECT_EQ(lb.b, 0x33);
}

TEST(DecodeLightbarPayload, FullByteRange) {
    const uint8_t in[4] = {0xFF, 0x00, 0xFF, 0x00};
    const dish_wire::LightbarPayload lb = dish_wire::decodeLightbarPayload(in);
    EXPECT_EQ(lb.ctrlIdx, 0xFF);
    EXPECT_EQ(lb.r, 0x00);
    EXPECT_EQ(lb.g, 0xFF);
    EXPECT_EQ(lb.b, 0x00);
}

TEST(DecodeLightbarPayload, DistinctChannelsAreNotAliased) {
    const uint8_t in[4] = {0, 1, 2, 3};
    const dish_wire::LightbarPayload lb = dish_wire::decodeLightbarPayload(in);
    EXPECT_EQ(lb.r, 1);
    EXPECT_EQ(lb.g, 2);
    EXPECT_EQ(lb.b, 3);
    EXPECT_NE(lb.r, lb.g);
    EXPECT_NE(lb.g, lb.b);
}

static uint32_t readLe32(const uint8_t* p) {
    return static_cast<uint32_t>(p[0]) | (static_cast<uint32_t>(p[1]) << 8) |
           (static_cast<uint32_t>(p[2]) << 16) | (static_cast<uint32_t>(p[3]) << 24);
}

TEST(EncodeTouchpadPayload, CtrlIdxAtByteZero) {
    uint8_t out[16]{};
    dish_wire::encodeTouchpadPayload(out, 9, false, false, false, 0, 0, 0, 0, 0, 0, 0);
    EXPECT_EQ(out[0], 9);
}

TEST(EncodeTouchpadPayload, FlagsBit0IsFinger0Active) {
    uint8_t out[16]{};
    dish_wire::encodeTouchpadPayload(out, 0, true, false,
                                     false, 0, 0, 0, 0, 0, 0, 0);
    EXPECT_EQ(out[1] & 0x01, 0x01);
    EXPECT_EQ(out[1] & 0x02, 0);
    EXPECT_EQ(out[1] & 0x04, 0);
}

TEST(EncodeTouchpadPayload, FlagsBit1IsFinger1Active) {
    uint8_t out[16]{};
    dish_wire::encodeTouchpadPayload(out, 0, false, true,
                                     false, 0, 0, 0, 0, 0, 0, 0);
    EXPECT_EQ(out[1] & 0x01, 0);
    EXPECT_EQ(out[1] & 0x02, 0x02);
    EXPECT_EQ(out[1] & 0x04, 0);
}

TEST(EncodeTouchpadPayload, FlagsBit2IsButtonPressed) {
    uint8_t out[16]{};
    dish_wire::encodeTouchpadPayload(out, 0, false, false,
                                     true, 0, 0, 0, 0, 0, 0, 0);
    EXPECT_EQ(out[1] & 0x04, 0x04);
}

TEST(EncodeTouchpadPayload, AllFlagsSetTogether) {
    uint8_t out[16]{};
    dish_wire::encodeTouchpadPayload(out, 0, true, true, true, 0, 0, 0, 0, 0, 0, 0);
    EXPECT_EQ(out[1], 0x07);
}

TEST(EncodeTouchpadPayload, Finger0TrackingIdAtByte2) {
    uint8_t out[16]{};
    dish_wire::encodeTouchpadPayload(out, 0, false, false, false,
                                     0x42, 0, 0, 0, 0, 0, 0);
    EXPECT_EQ(out[2], 0x42);
}

TEST(EncodeTouchpadPayload, Finger1TrackingIdAtByte7) {
    uint8_t out[16]{};
    dish_wire::encodeTouchpadPayload(out, 0, false, false, false,
                                     0, 0, 0, 0xAB, 0, 0, 0);
    EXPECT_EQ(out[7], 0xAB);
}

TEST(EncodeTouchpadPayload, Finger0CoordsAreLittleEndianInt16) {
    uint8_t out[16]{};
    dish_wire::encodeTouchpadPayload(out, 0, true, false, false, 0,
                                     0x1234, static_cast<int16_t>(0xFEDC),
                                     0, 0, 0, 0);
    EXPECT_EQ(readLe16(out + 3), 0x1234);
    EXPECT_EQ(readLe16(out + 5), static_cast<int16_t>(0xFEDC));
}

TEST(EncodeTouchpadPayload, Finger1CoordsAreLittleEndianInt16) {
    uint8_t out[16]{};
    dish_wire::encodeTouchpadPayload(out, 0, false, true, false, 0, 0, 0,
                                     0, 0x4321, static_cast<int16_t>(0x8765), 0);
    EXPECT_EQ(readLe16(out + 8), 0x4321);
    EXPECT_EQ(readLe16(out + 10), static_cast<int16_t>(0x8765));
}

TEST(EncodeTouchpadPayload, CoordExtrema) {
    uint8_t out[16]{};
    dish_wire::encodeTouchpadPayload(out, 0, true, true, false, 0,
                                     INT16_MIN, INT16_MAX,
                                     0, INT16_MAX, INT16_MIN, 0);
    EXPECT_EQ(readLe16(out + 3), INT16_MIN);
    EXPECT_EQ(readLe16(out + 5), INT16_MAX);
    EXPECT_EQ(readLe16(out + 8), INT16_MAX);
    EXPECT_EQ(readLe16(out + 10), INT16_MIN);
}

TEST(EncodeTouchpadPayload, EmptyStateProducesAllZeroFlagsAndZeroCoords) {
    // Receiver uses the all-zero shape to detect clean lift-off rather than a smear.
    uint8_t out[16];
    for (int i = 0; i < 16; ++i) out[i] = 0xCC;
    dish_wire::encodeTouchpadPayload(out, 5, false, false, false,
                                     0, 0, 0, 0, 0, 0, 0);
    EXPECT_EQ(out[0], 5);
    EXPECT_EQ(out[1], 0);
    EXPECT_EQ(out[2], 0);
    EXPECT_EQ(readLe16(out + 3), 0);
    EXPECT_EQ(readLe16(out + 5), 0);
    EXPECT_EQ(out[7], 0);
    EXPECT_EQ(readLe16(out + 8), 0);
    EXPECT_EQ(readLe16(out + 10), 0);
    EXPECT_EQ(readLe32(out + 12), 0u);
}

TEST(EncodeTouchpadPayload, IndependentFingerSlots) {
    uint8_t out[16]{};
    dish_wire::encodeTouchpadPayload(out, 0, true, true, false,
                                     0x10, 100, 200,
                                     0x20, 300, 400, 0);
    EXPECT_EQ(out[2], 0x10);
    EXPECT_EQ(readLe16(out + 3), 100);
    EXPECT_EQ(readLe16(out + 5), 200);
    EXPECT_EQ(out[7], 0x20);
    EXPECT_EQ(readLe16(out + 8), 300);
    EXPECT_EQ(readLe16(out + 10), 400);
}

TEST(EncodeTouchpadPayload, EventTimeMsAtBytes12to15LittleEndian) {
    uint8_t out[16]{};
    dish_wire::encodeTouchpadPayload(out, 0, true, false, false, 0, 0, 0, 0, 0, 0,
                                     0x12345678u);
    EXPECT_EQ(out[12], 0x78);
    EXPECT_EQ(out[13], 0x56);
    EXPECT_EQ(out[14], 0x34);
    EXPECT_EQ(out[15], 0x12);
    EXPECT_EQ(readLe32(out + 12), 0x12345678u);
}

TEST(EncodeTouchpadPayload, EventTimeMsExtrema) {
    uint8_t out[16]{};
    dish_wire::encodeTouchpadPayload(out, 0, true, false, false, 0, 0, 0, 0, 0, 0,
                                     UINT32_MAX);
    EXPECT_EQ(readLe32(out + 12), UINT32_MAX);
    dish_wire::encodeTouchpadPayload(out, 0, true, false, false, 0, 0, 0, 0, 0, 0,
                                     0u);
    EXPECT_EQ(readLe32(out + 12), 0u);
}
