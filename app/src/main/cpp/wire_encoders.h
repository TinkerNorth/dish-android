// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

// Pure (no-JNI, no-Android-headers) encoders for the satellite UDP wire
// payloads that dish-android emits. Split out of satellite_jni.cpp so the
// host-side GoogleTest target (app/src/test/cpp/) can pin the byte layout
// without dragging in the JNI / GameActivity headers — same architectural
// pattern the desktop senders use (parseRumbleMessage / encodeMotionPayload
// as public statics on SatelliteClient).
//
// Wire-format references live in satellite/docs/protocol.md. Bumping a
// layout here silently would break every paired receiver.
#pragma once

#include <cstdint>

namespace dish_wire {

inline void putLE16(uint8_t* dst, uint16_t v) {
    dst[0] = static_cast<uint8_t>(v);
    dst[1] = static_cast<uint8_t>(v >> 8);
}

inline void putLE32(uint8_t* dst, uint32_t v) {
    dst[0] = static_cast<uint8_t>(v);
    dst[1] = static_cast<uint8_t>(v >> 8);
    dst[2] = static_cast<uint8_t>(v >> 16);
    dst[3] = static_cast<uint8_t>(v >> 24);
}

// MSG_MOTION (0x000A) inner payload — 17 bytes.
//
// Layout (matches satellite/src/core/types.h::MotionReport via memcpy on the
// receiver):
//   [0]      ctrlIdx (u8)
//   [1..2]   gyroX (i16 LE)    LSB = 2000 / 32767 deg/s
//   [3..4]   gyroY (i16 LE)
//   [5..6]   gyroZ (i16 LE)
//   [7..8]   accelX (i16 LE)   LSB = 4 / 32767 g
//   [9..10]  accelY (i16 LE)
//   [11..12] accelZ (i16 LE)
//   [13..16] timestampDeltaUs (u32 LE)
inline void encodeMotionPayload(uint8_t out[17], uint8_t ctrlIdx, int16_t gx, int16_t gy,
                                int16_t gz, int16_t ax, int16_t ay, int16_t az,
                                uint32_t timestampDeltaUs) {
    out[0] = ctrlIdx;
    putLE16(out + 1, static_cast<uint16_t>(gx));
    putLE16(out + 3, static_cast<uint16_t>(gy));
    putLE16(out + 5, static_cast<uint16_t>(gz));
    putLE16(out + 7, static_cast<uint16_t>(ax));
    putLE16(out + 9, static_cast<uint16_t>(ay));
    putLE16(out + 11, static_cast<uint16_t>(az));
    putLE32(out + 13, timestampDeltaUs);
}

// MSG_BATTERY (0x000B) inner payload — 3 bytes.
//
//   [0] ctrlIdx (u8)
//   [1] level (u8)  — 0..100, or 0xFF (BATTERY_LEVEL_UNKNOWN)
//   [2] status (u8) — BATTERY_STATUS_* (0..4)
inline void encodeBatteryPayload(uint8_t out[3], uint8_t ctrlIdx, uint8_t level, uint8_t status) {
    out[0] = ctrlIdx;
    out[1] = level;
    out[2] = status;
}

// MSG_TOUCHPAD (0x000C) inner payload — 12 bytes total (after the 2-byte
// outer msgType/msgLen header; this encoder fills the inner bytes that ride
// inside MSG_TOUCHPAD).
//
// Layout — must round-trip with satellite/src/core/types.h::decodeTouchpadReport:
//   [0]      ctrlIdx (u8)
//   [1]      flags (u8): bit0=finger0_active, bit1=finger1_active,
//                        bit2=button_pressed (the clicky DS4/DualSense trackpad
//                        button — independent of touches)
//   [2]      finger0_trackingId (u8)   monotonic per-finger id, wraps freely
//   [3..4]   finger0_x (i16 LE)        -32768..32767, resolution-independent
//   [5..6]   finger0_y (i16 LE)        +y is DOWN (matches the wire convention)
//   [7]      finger1_trackingId (u8)
//   [8..9]   finger1_x (i16 LE)
//   [10..11] finger1_y (i16 LE)
inline void encodeTouchpadPayload(uint8_t out[12], uint8_t ctrlIdx, bool f0Active, bool f1Active,
                                  bool buttonPressed, uint8_t f0Id, int16_t f0x, int16_t f0y,
                                  uint8_t f1Id, int16_t f1x, int16_t f1y) {
    out[0] = ctrlIdx;
    uint8_t flags = 0;
    if (f0Active) flags |= 0x01;
    if (f1Active) flags |= 0x02;
    if (buttonPressed) flags |= 0x04;
    out[1] = flags;
    out[2] = f0Id;
    putLE16(out + 3, static_cast<uint16_t>(f0x));
    putLE16(out + 5, static_cast<uint16_t>(f0y));
    out[7] = f1Id;
    putLE16(out + 8, static_cast<uint16_t>(f1x));
    putLE16(out + 10, static_cast<uint16_t>(f1y));
}

// MSG_LIGHTBAR (0x000D) inner payload — 4 bytes, satellite → sender.
//
//   [0] ctrlIdx (u8)
//   [1] R (u8)
//   [2] G (u8)
//   [3] B (u8)
//
// This is the only satellite → sender message dish-android decodes for the
// lightbar path. Android exposes no controller-LED API, so the JNI receive
// loop logs the decoded value and drops it (see satellite_jni.cpp::receiveAck);
// dish-android also does not advertise CAP_LIGHTBAR. The decoder is split out
// here, with the encoders, purely so the byte layout can be pinned by the
// host-side GoogleTest target without dragging in the JNI / Android headers.
struct LightbarPayload {
    uint8_t ctrlIdx;
    uint8_t r;
    uint8_t g;
    uint8_t b;
};

inline LightbarPayload decodeLightbarPayload(const uint8_t in[4]) {
    return LightbarPayload{in[0], in[1], in[2], in[3]};
}

} // namespace dish_wire
