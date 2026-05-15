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

} // namespace dish_wire
