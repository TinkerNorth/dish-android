// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

// Host-side GoogleTest coverage for the pure dish-android wire encoders
// (dish_wire::encodeMotionPayload / encodeBatteryPayload). Mirrors
// test_satellite_client_motion.cpp on dish-windows / dish-linux.
//
// The wire layout is the contract with satellite/src/core/types.h —
// MotionReport is decoded via memcpy on the receiver, and every supported
// platform is little-endian, so we pin LE explicitly.

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

} // namespace

// ── encodeMotionPayload ─────────────────────────────────────────────────────

TEST(EncodeMotionPayload, CtrlIdxAtByteZero) {
    uint8_t out[17]{};
    dish_wire::encodeMotionPayload(out, /*ctrlIdx=*/7, 0, 0, 0, 0, 0, 0, 0);
    EXPECT_EQ(out[0], 7);
}

TEST(EncodeMotionPayload, GyroAndAccelAsLittleEndianInt16) {
    uint8_t out[17]{};
    dish_wire::encodeMotionPayload(out, 0, /*gx=*/0x0102, /*gy=*/0x0304, /*gz=*/0x0506,
                                   /*ax=*/0x0708, /*ay=*/0x090A, /*az=*/0x0B0C, /*dtUs=*/0);
    EXPECT_EQ(readLe16(&out[1]), 0x0102);
    EXPECT_EQ(readLe16(&out[3]), 0x0304);
    EXPECT_EQ(readLe16(&out[5]), 0x0506);
    EXPECT_EQ(readLe16(&out[7]), 0x0708);
    EXPECT_EQ(readLe16(&out[9]), 0x090A);
    EXPECT_EQ(readLe16(&out[11]), 0x0B0C);
}

TEST(EncodeMotionPayload, TimestampDeltaUsAsLittleEndianUint32) {
    uint8_t out[17]{};
    dish_wire::encodeMotionPayload(out, 0, 0, 0, 0, 0, 0, 0, /*dtUs=*/0xDEADBEEFU);
    EXPECT_EQ(readLe32(&out[13]), 0xDEADBEEFU);
}

TEST(EncodeMotionPayload, FullInt16RangeWithoutOverflow) {
    uint8_t out[17]{};
    dish_wire::encodeMotionPayload(out, 0xFF, /*gx=*/-32768, /*gy=*/32767, /*gz=*/0,
                                   /*ax=*/-32768, /*ay=*/32767, /*az=*/-1, /*dtUs=*/0);
    EXPECT_EQ(out[0], 0xFF);
    EXPECT_EQ(readLe16(&out[1]), -32768);
    EXPECT_EQ(readLe16(&out[3]), 32767);
    EXPECT_EQ(readLe16(&out[7]), -32768);
    EXPECT_EQ(readLe16(&out[9]), 32767);
    EXPECT_EQ(readLe16(&out[11]), -1);
}

TEST(EncodeMotionPayload, Uint32MaxDelta) {
    uint8_t out[17]{};
    dish_wire::encodeMotionPayload(out, 0, 0, 0, 0, 0, 0, 0, /*dtUs=*/0xFFFFFFFFU);
    EXPECT_EQ(readLe32(&out[13]), 0xFFFFFFFFU);
}

TEST(EncodeMotionPayload, ZeroDeltaIsFourZeroBytes) {
    // First-packet sentinel — receiver expects exactly 0 (not 0xFFFFFFFF
    // or any other "no value" marker) so the inter-arrival timer can
    // recognise the start of a session.
    uint8_t out[17]{};
    dish_wire::encodeMotionPayload(out, 0, 0, 0, 0, 0, 0, 0, 0);
    EXPECT_EQ(out[13], 0);
    EXPECT_EQ(out[14], 0);
    EXPECT_EQ(out[15], 0);
    EXPECT_EQ(out[16], 0);
}

// ── encodeBatteryPayload ────────────────────────────────────────────────────

TEST(EncodeBatteryPayload, CtrlIdxLevelStatusInOrder) {
    uint8_t out[3]{};
    dish_wire::encodeBatteryPayload(out, /*ctrlIdx=*/5, /*level=*/77, /*status=*/2);
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

// ── decodeLightbarPayload ───────────────────────────────────────────────────
//
// MSG_LIGHTBAR (0x000D) is satellite → sender, so dish-android decodes (not
// encodes) it. Android has no controller-LED API: receiveAck just logs the
// decoded value and drops the packet. These tests pin the [ctrlIdx][R][G][B]
// byte order the JNI dispatch branch relies on for that log line.

TEST(DecodeLightbarPayload, CtrlIdxThenRgbInOrder) {
    const uint8_t in[4] = {3, 0x11, 0x22, 0x33};
    const dish_wire::LightbarPayload lb = dish_wire::decodeLightbarPayload(in);
    EXPECT_EQ(lb.ctrlIdx, 3);
    EXPECT_EQ(lb.r, 0x11);
    EXPECT_EQ(lb.g, 0x22);
    EXPECT_EQ(lb.b, 0x33);
}

TEST(DecodeLightbarPayload, FullByteRange) {
    // 0x00 and 0xFF must survive the round-trip on every field — no field is
    // sign-extended or masked.
    const uint8_t in[4] = {0xFF, 0x00, 0xFF, 0x00};
    const dish_wire::LightbarPayload lb = dish_wire::decodeLightbarPayload(in);
    EXPECT_EQ(lb.ctrlIdx, 0xFF);
    EXPECT_EQ(lb.r, 0x00);
    EXPECT_EQ(lb.g, 0xFF);
    EXPECT_EQ(lb.b, 0x00);
}

TEST(DecodeLightbarPayload, DistinctChannelsAreNotAliased) {
    // Guards against a copy-paste bug reading the same offset for two
    // channels — each of R/G/B must come from its own byte.
    const uint8_t in[4] = {0, 1, 2, 3};
    const dish_wire::LightbarPayload lb = dish_wire::decodeLightbarPayload(in);
    EXPECT_EQ(lb.r, 1);
    EXPECT_EQ(lb.g, 2);
    EXPECT_EQ(lb.b, 3);
    EXPECT_NE(lb.r, lb.g);
    EXPECT_NE(lb.g, lb.b);
}

// ── encodeTouchpadPayload ───────────────────────────────────────────────────
//
// MSG_TOUCHPAD (0x000C) inner payload — 16 bytes total, layout pinned in
// satellite/src/core/types.h::decodeTouchpadReport. We re-verify the byte
// layout here so a layout drift on the sender side is caught at the
// dish-android unit-test boundary (the receiver decode tests guard the
// other direction).

// Helper reader for the u32 LE eventTimeMs field at the tail of the payload.
static uint32_t readLe32(const uint8_t* p) {
    return static_cast<uint32_t>(p[0]) | (static_cast<uint32_t>(p[1]) << 8) |
           (static_cast<uint32_t>(p[2]) << 16) | (static_cast<uint32_t>(p[3]) << 24);
}

TEST(EncodeTouchpadPayload, CtrlIdxAtByteZero) {
    uint8_t out[16]{};
    dish_wire::encodeTouchpadPayload(out, /*ctrlIdx=*/9, false, false, false, 0, 0, 0, 0, 0, 0, 0);
    EXPECT_EQ(out[0], 9);
}

TEST(EncodeTouchpadPayload, FlagsBit0IsFinger0Active) {
    uint8_t out[16]{};
    dish_wire::encodeTouchpadPayload(out, 0, /*f0Active=*/true, /*f1Active=*/false,
                                     /*buttonPressed=*/false, 0, 0, 0, 0, 0, 0, 0);
    EXPECT_EQ(out[1] & 0x01, 0x01);
    EXPECT_EQ(out[1] & 0x02, 0);
    EXPECT_EQ(out[1] & 0x04, 0);
}

TEST(EncodeTouchpadPayload, FlagsBit1IsFinger1Active) {
    uint8_t out[16]{};
    dish_wire::encodeTouchpadPayload(out, 0, /*f0Active=*/false, /*f1Active=*/true,
                                     /*buttonPressed=*/false, 0, 0, 0, 0, 0, 0, 0);
    EXPECT_EQ(out[1] & 0x01, 0);
    EXPECT_EQ(out[1] & 0x02, 0x02);
    EXPECT_EQ(out[1] & 0x04, 0);
}

TEST(EncodeTouchpadPayload, FlagsBit2IsButtonPressed) {
    uint8_t out[16]{};
    dish_wire::encodeTouchpadPayload(out, 0, /*f0Active=*/false, /*f1Active=*/false,
                                     /*buttonPressed=*/true, 0, 0, 0, 0, 0, 0, 0);
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
                                     /*f0Id=*/0x42, 0, 0, /*f1Id=*/0, 0, 0, 0);
    EXPECT_EQ(out[2], 0x42);
}

TEST(EncodeTouchpadPayload, Finger1TrackingIdAtByte7) {
    uint8_t out[16]{};
    dish_wire::encodeTouchpadPayload(out, 0, false, false, false,
                                     /*f0Id=*/0, 0, 0, /*f1Id=*/0xAB, 0, 0, 0);
    EXPECT_EQ(out[7], 0xAB);
}

TEST(EncodeTouchpadPayload, Finger0CoordsAreLittleEndianInt16) {
    uint8_t out[16]{};
    dish_wire::encodeTouchpadPayload(out, 0, true, false, false, 0,
                                     /*f0x=*/0x1234, /*f0y=*/static_cast<int16_t>(0xFEDC),
                                     0, 0, 0, 0);
    EXPECT_EQ(readLe16(out + 3), 0x1234);
    EXPECT_EQ(readLe16(out + 5), static_cast<int16_t>(0xFEDC));
}

TEST(EncodeTouchpadPayload, Finger1CoordsAreLittleEndianInt16) {
    uint8_t out[16]{};
    dish_wire::encodeTouchpadPayload(out, 0, false, true, false, 0, 0, 0,
                                     0, /*f1x=*/0x4321, /*f1y=*/static_cast<int16_t>(0x8765), 0);
    EXPECT_EQ(readLe16(out + 8), 0x4321);
    EXPECT_EQ(readLe16(out + 10), static_cast<int16_t>(0x8765));
}

TEST(EncodeTouchpadPayload, CoordExtrema) {
    // INT16_MIN / INT16_MAX must survive the wire round-trip without
    // sign-extension or clamping — the receiver carries signed int16
    // through to the DS4 surface and the mouse-mode delta calc.
    uint8_t out[16]{};
    dish_wire::encodeTouchpadPayload(out, 0, true, true, false, 0,
                                     /*f0x=*/INT16_MIN, /*f0y=*/INT16_MAX,
                                     0, /*f1x=*/INT16_MAX, /*f1y=*/INT16_MIN, 0);
    EXPECT_EQ(readLe16(out + 3), INT16_MIN);
    EXPECT_EQ(readLe16(out + 5), INT16_MAX);
    EXPECT_EQ(readLe16(out + 8), INT16_MAX);
    EXPECT_EQ(readLe16(out + 10), INT16_MIN);
}

TEST(EncodeTouchpadPayload, EmptyStateProducesAllZeroFlagsAndZeroCoords) {
    // A "no fingers, no button" frame must encode as flag=0 with both
    // tracking-ids and both coordinate pairs at zero. The receiver uses
    // this shape to detect a clean lift-off rather than a smear.
    uint8_t out[16];
    for (int i = 0; i < 16; ++i) out[i] = 0xCC; // poison
    dish_wire::encodeTouchpadPayload(out, /*ctrlIdx=*/5, false, false, false,
                                     0, 0, 0, 0, 0, 0, 0);
    EXPECT_EQ(out[0], 5);
    EXPECT_EQ(out[1], 0); // all flag bits clear
    EXPECT_EQ(out[2], 0);
    EXPECT_EQ(readLe16(out + 3), 0);
    EXPECT_EQ(readLe16(out + 5), 0);
    EXPECT_EQ(out[7], 0);
    EXPECT_EQ(readLe16(out + 8), 0);
    EXPECT_EQ(readLe16(out + 10), 0);
    EXPECT_EQ(readLe32(out + 12), 0u);
}

TEST(EncodeTouchpadPayload, IndependentFingerSlots) {
    // Guard against a copy-paste bug where one slot's id/coords overwrites
    // the other's: explicit distinct values per slot and per axis.
    uint8_t out[16]{};
    dish_wire::encodeTouchpadPayload(out, 0, true, true, false,
                                     /*f0Id=*/0x10, /*f0x=*/100, /*f0y=*/200,
                                     /*f1Id=*/0x20, /*f1x=*/300, /*f1y=*/400, 0);
    EXPECT_EQ(out[2], 0x10);
    EXPECT_EQ(readLe16(out + 3), 100);
    EXPECT_EQ(readLe16(out + 5), 200);
    EXPECT_EQ(out[7], 0x20);
    EXPECT_EQ(readLe16(out + 8), 300);
    EXPECT_EQ(readLe16(out + 10), 400);
}

TEST(EncodeTouchpadPayload, EventTimeMsAtBytes12to15LittleEndian) {
    // The satellite reads u32 LE at offset 12 and uses it as the per-sample
    // timestamp for mouse-mode time-scaling (fixes the first-touch jump).
    // A wrong endianness here would scramble dt completely and the cursor
    // would behave wildly. Pin the layout.
    uint8_t out[16]{};
    dish_wire::encodeTouchpadPayload(out, 0, true, false, false, 0, 0, 0, 0, 0, 0,
                                     /*eventTimeMs=*/0x12345678u);
    EXPECT_EQ(out[12], 0x78);
    EXPECT_EQ(out[13], 0x56);
    EXPECT_EQ(out[14], 0x34);
    EXPECT_EQ(out[15], 0x12);
    EXPECT_EQ(readLe32(out + 12), 0x12345678u);
}

TEST(EncodeTouchpadPayload, EventTimeMsExtrema) {
    // Both ends of the u32 range survive without sign-extension shenanigans.
    // UINT32_MAX is what happens when Android's MotionEvent.getEventTime()
    // (a Long uptime in ms) wraps past 49.7 days; the satellite handles the
    // wrap with int32_t dt arithmetic.
    uint8_t out[16]{};
    dish_wire::encodeTouchpadPayload(out, 0, true, false, false, 0, 0, 0, 0, 0, 0,
                                     /*eventTimeMs=*/UINT32_MAX);
    EXPECT_EQ(readLe32(out + 12), UINT32_MAX);
    dish_wire::encodeTouchpadPayload(out, 0, true, false, false, 0, 0, 0, 0, 0, 0,
                                     /*eventTimeMs=*/0u);
    EXPECT_EQ(readLe32(out + 12), 0u);
}
