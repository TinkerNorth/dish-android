// SPDX-License-Identifier: LGPL-3.0-or-later

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

// MSG_MOTION 0x000A inner — 17B: ctrlIdx, gyro xyz i16 LE, accel xyz i16 LE, tsΔus u32 LE.
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

// MSG_BATTERY 0x000B inner — 3B: ctrlIdx, level u8 (0..100 or 0xFF unknown), status u8.
inline void encodeBatteryPayload(uint8_t out[3], uint8_t ctrlIdx, uint8_t level, uint8_t status) {
    out[0] = ctrlIdx;
    out[1] = level;
    out[2] = status;
}

// MSG_TOUCHPAD 0x000C inner — 16B: ctrlIdx, flags(f0|f1|btn), f0Id, f0xy i16 LE, f1Id, f1xy i16 LE,
// eventTimeMs u32 LE.
inline void encodeTouchpadPayload(uint8_t out[16], uint8_t ctrlIdx, bool f0Active, bool f1Active,
                                  bool buttonPressed, uint8_t f0Id, int16_t f0x, int16_t f0y,
                                  uint8_t f1Id, int16_t f1x, int16_t f1y, uint32_t eventTimeMs) {
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
    putLE32(out + 12, eventTimeMs);
}

// MSG_LIGHTBAR 0x000D inner — 4B: ctrlIdx, R, G, B. Decode-only on dish-android (no LED API).
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
