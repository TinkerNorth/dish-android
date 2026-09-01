// SPDX-License-Identifier: LGPL-3.0-or-later

#pragma once

#include <cstddef>
#include <cstdint>

namespace dish_wire {

// Datagram ceilings (satellite docs/contract.md §Packet format). One Ethernet
// MTU in either direction, so a full audio frame crosses a LAN without
// fragmenting (a fragmented Opus packet would be an all-or-nothing loss
// anyway). What the crypto framing leaves behind is the ceiling every sender
// has to stay under; satellite pins the same number as MAX_INNER_PAYLOAD_BYTES
// and truncates anything larger on read, which then fails the AEAD.
inline constexpr size_t MAX_DATAGRAM_BYTES = 1500;
inline constexpr size_t OUTER_HEADER_BYTES = 8; // token(4 BE) + counter(4 BE)
inline constexpr size_t INNER_HEADER_BYTES = 4; // msgType(2 BE) + msgLen(2 BE)
inline constexpr size_t AEAD_TAG_BYTES = 16;    // ChaCha20-Poly1305-IETF
inline constexpr size_t MAX_INNER_PAYLOAD_BYTES =
    MAX_DATAGRAM_BYTES - OUTER_HEADER_BYTES - INNER_HEADER_BYTES - AEAD_TAG_BYTES;

// The send path's own guard, factored out so the ceiling is testable without a
// socket: everything but audio sits under 30 bytes, so this exists to absorb a
// VBR spike rather than to be approached.
inline constexpr bool innerPayloadFits(size_t payloadLen) {
    return payloadLen <= MAX_INNER_PAYLOAD_BYTES;
}

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

// MSG_MOTION 0x000A inner, 17B: ctrlIdx, gyro xyz i16 LE, accel xyz i16 LE, tsΔus u32 LE.
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

// MSG_BATTERY 0x000B inner, 3B: ctrlIdx, level u8 (0..100 or 0xFF unknown), status u8.
inline void encodeBatteryPayload(uint8_t out[3], uint8_t ctrlIdx, uint8_t level, uint8_t status) {
    out[0] = ctrlIdx;
    out[1] = level;
    out[2] = status;
}

// MSG_TOUCHPAD 0x000C inner, protocol v1, 16B: ctrlIdx, flags(f0|f1|btn), f0Id, f0xy i16 LE,
// f1Id, f1xy i16 LE, eventTimeMs u32 LE. Sent to protocolVersion-1 satellites only; the
// mouse buttons and the wheel do not exist in this frame.
inline void encodeTouchpadPayloadV1(uint8_t out[16], uint8_t ctrlIdx, bool f0Active, bool f1Active,
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

// MSG_TOUCHPAD 0x000C inner, protocol v2, 19B: ctrlIdx, fingerFlags(f0|f1),
// buttons(left|right|middle), f0Id, f0xy i16 LE, f1Id, f1xy i16 LE, eventTimeMs u32 LE,
// scrollV i16 LE (120 = one wheel notch). The click moved out of the finger flags into
// its own buttons byte; a v2 satellite decodes exactly this shape and nothing else.
inline void encodeTouchpadPayloadV2(uint8_t out[19], uint8_t ctrlIdx, bool f0Active, bool f1Active,
                                    bool leftPressed, bool rightPressed, bool middlePressed,
                                    uint8_t f0Id, int16_t f0x, int16_t f0y, uint8_t f1Id,
                                    int16_t f1x, int16_t f1y, uint32_t eventTimeMs,
                                    int16_t scrollV) {
    out[0] = ctrlIdx;
    uint8_t fingers = 0;
    if (f0Active) fingers |= 0x01;
    if (f1Active) fingers |= 0x02;
    out[1] = fingers;
    uint8_t buttons = 0;
    if (leftPressed) buttons |= 0x01;
    if (rightPressed) buttons |= 0x02;
    if (middlePressed) buttons |= 0x04;
    out[2] = buttons;
    out[3] = f0Id;
    putLE16(out + 4, static_cast<uint16_t>(f0x));
    putLE16(out + 6, static_cast<uint16_t>(f0y));
    out[8] = f1Id;
    putLE16(out + 9, static_cast<uint16_t>(f1x));
    putLE16(out + 11, static_cast<uint16_t>(f1y));
    putLE32(out + 13, eventTimeMs);
    putLE16(out + 17, static_cast<uint16_t>(scrollV));
}

// MSG_TRIGGER_EFFECTS 0x0010 inner, 23B: ctrlIdx, left block (11B mode+params), right block
// (11B). The blocks are raw DualSense output-report fields, replayed verbatim into a captured
// physical DualSense.
inline constexpr int TRIGGER_EFFECTS_PAYLOAD_BYTES = 22;

// MSG_PLAYER_LEDS 0x0011 inner, 2B: ctrlIdx, ledMask (bit 0 = leftmost LED).

// MSG_LIGHTBAR 0x000D inner, 4B: ctrlIdx, R, G, B.
struct LightbarPayload {
    uint8_t ctrlIdx;
    uint8_t r;
    uint8_t g;
    uint8_t b;
};

inline LightbarPayload decodeLightbarPayload(const uint8_t in[4]) {
    return LightbarPayload{in[0], in[1], in[2], in[3]};
}

// MSG_MIC_AUDIO 0x0012 (up) and MSG_SPEAKER_AUDIO 0x0013 (down) inner:
// ctrlIdx(1) + seq(u16 BE) + exactly one 20 ms Opus packet. Opus packets are
// self-delimiting, so the message length IS the packet length. `seq` wraps and
// buys exactly two things: which frames never arrived (conceal them) and which
// arrived too late to matter (drop them). See audio_jitter.h.
inline constexpr size_t AUDIO_WIRE_HEADER_BYTES = 3;
// Header plus at least one Opus byte. A 1-byte packet is legal (a DTX silence
// frame); a header with nothing behind it is malformed.
inline constexpr size_t AUDIO_WIRE_MIN_PAYLOAD_BYTES = AUDIO_WIRE_HEADER_BYTES + 1;
// Largest Opus packet one datagram can carry. Two orders of magnitude above a
// real 20 ms packet (~80 bytes mic, ~240 speaker); it is a bound, not a target.
inline constexpr size_t AUDIO_WIRE_MAX_OPUS_BYTES =
    MAX_INNER_PAYLOAD_BYTES - AUDIO_WIRE_HEADER_BYTES;

struct AudioFrameHeader {
    uint8_t ctrlIdx;
    uint16_t seq;
};

// Big-endian, unlike the motion/touchpad payloads above: those mirror XUSB
// struct layouts, this one mirrors satellite's own encodeAudioFrameHeader
// (core/types.h). Explicit shifts keep it host-byte-order-independent. `in`
// must have AUDIO_WIRE_HEADER_BYTES readable; callers guard on that first.
inline void encodeAudioFrameHeader(uint8_t* out, uint8_t ctrlIdx, uint16_t seq) {
    out[0] = ctrlIdx;
    out[1] = static_cast<uint8_t>(seq >> 8);
    out[2] = static_cast<uint8_t>(seq);
}

inline AudioFrameHeader decodeAudioFrameHeader(const uint8_t* in) {
    AudioFrameHeader h;
    h.ctrlIdx = in[0];
    h.seq =
        static_cast<uint16_t>((static_cast<uint16_t>(in[1]) << 8) | static_cast<uint16_t>(in[2]));
    return h;
}

// MSG_MIC_LED 0x0014 inner, 2B: ctrlIdx, state. Pulse is the DualSense's own
// breathing pattern, which the pad renders itself; the host only forwards which
// mode the game asked for. Anything else is malformed and dropped rather than
// guessed at (satellite clamps at its decoder too, so an unknown state can only
// come from a host speaking something we do not).
inline constexpr uint8_t MIC_LED_STATE_OFF = 0;
inline constexpr uint8_t MIC_LED_STATE_ON = 1;
inline constexpr uint8_t MIC_LED_STATE_PULSE = 2;
inline constexpr uint8_t MIC_LED_STATE_COUNT = 3;
inline constexpr size_t MIC_LED_PAYLOAD_BYTES = 2;

struct MicLedPayload {
    uint8_t ctrlIdx;
    uint8_t state;
};

inline MicLedPayload decodeMicLedPayload(const uint8_t in[2]) {
    return MicLedPayload{in[0], in[1]};
}

inline bool micLedStateValid(uint8_t state) { return state < MIC_LED_STATE_COUNT; }

} // namespace dish_wire
