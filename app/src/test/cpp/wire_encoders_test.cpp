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
