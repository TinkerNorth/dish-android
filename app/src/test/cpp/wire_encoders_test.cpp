// SPDX-License-Identifier: LGPL-3.0-or-later

#include "wire_encoders.h"

#include <gtest/gtest.h>

#include <cstdint>

namespace {

int16_t readLe16(const uint8_t* p) {
    return static_cast<int16_t>(static_cast<uint16_t>(p[0]) | (static_cast<uint16_t>(p[1]) << 8));
}

uint32_t readLe32(const uint8_t* p) {
    return static_cast<uint32_t>(p[0]) | (static_cast<uint32_t>(p[1]) << 8) |
           (static_cast<uint32_t>(p[2]) << 16) | (static_cast<uint32_t>(p[3]) << 24);
}

} // namespace

TEST(EncodeMotionPayload, CtrlIdxAtByteZero) {
    uint8_t out[17]{};
    dish_wire::encodeMotionPayload(out, 7, 0, 0, 0, 0, 0, 0, 0);
    EXPECT_EQ(out[0], 7);
}

TEST(EncodeMotionPayload, GyroAndAccelAsLittleEndianInt16) {
    uint8_t out[17]{};
    dish_wire::encodeMotionPayload(out, 0, 0x0102, 0x0304, 0x0506, 0x0708, 0x090A, 0x0B0C, 0);
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
    dish_wire::encodeMotionPayload(out, 0xFF, -32768, 32767, 0, -32768, 32767, -1, 0);
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
    // Receiver expects exactly 0 (not a sentinel) so the inter-arrival timer can detect session
    // start.
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

TEST(EncodeTouchpadPayloadV1, CtrlIdxAtByteZero) {
    uint8_t out[16]{};
    dish_wire::encodeTouchpadPayloadV1(out, 9, false, false, false, 0, 0, 0, 0, 0, 0, 0);
    EXPECT_EQ(out[0], 9);
}

TEST(EncodeTouchpadPayloadV1, FlagBitsAreFinger0Finger1Button) {
    uint8_t out[16]{};
    dish_wire::encodeTouchpadPayloadV1(out, 0, true, false, false, 0, 0, 0, 0, 0, 0, 0);
    EXPECT_EQ(out[1], 0x01);
    dish_wire::encodeTouchpadPayloadV1(out, 0, false, true, false, 0, 0, 0, 0, 0, 0, 0);
    EXPECT_EQ(out[1], 0x02);
    dish_wire::encodeTouchpadPayloadV1(out, 0, false, false, true, 0, 0, 0, 0, 0, 0, 0);
    EXPECT_EQ(out[1], 0x04);
    dish_wire::encodeTouchpadPayloadV1(out, 0, true, true, true, 0, 0, 0, 0, 0, 0, 0);
    EXPECT_EQ(out[1], 0x07);
}

TEST(EncodeTouchpadPayloadV1, FingerSlotsAndLittleEndianCoords) {
    uint8_t out[16]{};
    dish_wire::encodeTouchpadPayloadV1(out, 0, true, true, false, 0x10, 100, 200, 0x20, 300, 400,
                                       0);
    EXPECT_EQ(out[2], 0x10);
    EXPECT_EQ(readLe16(out + 3), 100);
    EXPECT_EQ(readLe16(out + 5), 200);
    EXPECT_EQ(out[7], 0x20);
    EXPECT_EQ(readLe16(out + 8), 300);
    EXPECT_EQ(readLe16(out + 10), 400);
}

TEST(EncodeTouchpadPayloadV1, CoordExtrema) {
    uint8_t out[16]{};
    dish_wire::encodeTouchpadPayloadV1(out, 0, true, true, false, 0, INT16_MIN, INT16_MAX, 0,
                                       INT16_MAX, INT16_MIN, 0);
    EXPECT_EQ(readLe16(out + 3), INT16_MIN);
    EXPECT_EQ(readLe16(out + 5), INT16_MAX);
    EXPECT_EQ(readLe16(out + 8), INT16_MAX);
    EXPECT_EQ(readLe16(out + 10), INT16_MIN);
}

TEST(EncodeTouchpadPayloadV1, EmptyStateProducesAllZeroFlagsAndZeroCoords) {
    // Receiver uses the all-zero shape to detect clean lift-off rather than a smear.
    uint8_t out[16];
    for (int i = 0; i < 16; ++i) out[i] = 0xCC;
    dish_wire::encodeTouchpadPayloadV1(out, 5, false, false, false, 0, 0, 0, 0, 0, 0, 0);
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

TEST(EncodeTouchpadPayloadV1, EventTimeMsAtBytes12to15LittleEndian) {
    uint8_t out[16]{};
    dish_wire::encodeTouchpadPayloadV1(out, 0, true, false, false, 0, 0, 0, 0, 0, 0, 0x12345678u);
    EXPECT_EQ(out[12], 0x78);
    EXPECT_EQ(out[13], 0x56);
    EXPECT_EQ(out[14], 0x34);
    EXPECT_EQ(out[15], 0x12);
    EXPECT_EQ(readLe32(out + 12), 0x12345678u);
    dish_wire::encodeTouchpadPayloadV1(out, 0, true, false, false, 0, 0, 0, 0, 0, 0, UINT32_MAX);
    EXPECT_EQ(readLe32(out + 12), UINT32_MAX);
}

TEST(EncodeTouchpadPayloadV2, CtrlIdxAtByteZero) {
    uint8_t out[19]{};
    dish_wire::encodeTouchpadPayloadV2(out, 9, false, false, false, false, false, 0, 0, 0, 0, 0, 0,
                                       0, 0);
    EXPECT_EQ(out[0], 9);
}

TEST(EncodeTouchpadPayloadV2, FingerFlagsCarryOnlyFingers) {
    uint8_t out[19]{};
    dish_wire::encodeTouchpadPayloadV2(out, 0, true, false, true, true, true, 0, 0, 0, 0, 0, 0, 0,
                                       0);
    EXPECT_EQ(out[1], 0x01);
    dish_wire::encodeTouchpadPayloadV2(out, 0, false, true, true, true, true, 0, 0, 0, 0, 0, 0, 0,
                                       0);
    EXPECT_EQ(out[1], 0x02);
    dish_wire::encodeTouchpadPayloadV2(out, 0, true, true, false, false, false, 0, 0, 0, 0, 0, 0, 0,
                                       0);
    EXPECT_EQ(out[1], 0x03);
}

TEST(EncodeTouchpadPayloadV2, ButtonsByteIsLeftRightMiddle) {
    uint8_t out[19]{};
    dish_wire::encodeTouchpadPayloadV2(out, 0, false, false, true, false, false, 0, 0, 0, 0, 0, 0,
                                       0, 0);
    EXPECT_EQ(out[2], 0x01);
    dish_wire::encodeTouchpadPayloadV2(out, 0, false, false, false, true, false, 0, 0, 0, 0, 0, 0,
                                       0, 0);
    EXPECT_EQ(out[2], 0x02);
    dish_wire::encodeTouchpadPayloadV2(out, 0, false, false, false, false, true, 0, 0, 0, 0, 0, 0,
                                       0, 0);
    EXPECT_EQ(out[2], 0x04);
    dish_wire::encodeTouchpadPayloadV2(out, 0, false, false, true, true, true, 0, 0, 0, 0, 0, 0, 0,
                                       0);
    EXPECT_EQ(out[2], 0x07);
    EXPECT_EQ(out[1], 0x00);
}

TEST(EncodeTouchpadPayloadV2, FingerSlotsAndLittleEndianCoords) {
    uint8_t out[19]{};
    dish_wire::encodeTouchpadPayloadV2(out, 0, true, true, false, false, false, 0x10, 100, 200,
                                       0x20, 300, 400, 0, 0);
    EXPECT_EQ(out[3], 0x10);
    EXPECT_EQ(readLe16(out + 4), 100);
    EXPECT_EQ(readLe16(out + 6), 200);
    EXPECT_EQ(out[8], 0x20);
    EXPECT_EQ(readLe16(out + 9), 300);
    EXPECT_EQ(readLe16(out + 11), 400);
}

TEST(EncodeTouchpadPayloadV2, CoordExtrema) {
    uint8_t out[19]{};
    dish_wire::encodeTouchpadPayloadV2(out, 0, true, true, false, false, false, 0, INT16_MIN,
                                       INT16_MAX, 0, INT16_MAX, INT16_MIN, 0, 0);
    EXPECT_EQ(readLe16(out + 4), INT16_MIN);
    EXPECT_EQ(readLe16(out + 6), INT16_MAX);
    EXPECT_EQ(readLe16(out + 9), INT16_MAX);
    EXPECT_EQ(readLe16(out + 11), INT16_MIN);
}

TEST(EncodeTouchpadPayloadV2, EmptyStateProducesAllZeroBytes) {
    uint8_t out[19];
    for (int i = 0; i < 19; ++i) out[i] = 0xCC;
    dish_wire::encodeTouchpadPayloadV2(out, 5, false, false, false, false, false, 0, 0, 0, 0, 0, 0,
                                       0, 0);
    EXPECT_EQ(out[0], 5);
    for (int i = 1; i < 19; ++i) EXPECT_EQ(out[i], 0) << "byte " << i;
}

TEST(EncodeTouchpadPayloadV2, EventTimeMsAtBytes13to16LittleEndian) {
    uint8_t out[19]{};
    dish_wire::encodeTouchpadPayloadV2(out, 0, true, false, false, false, false, 0, 0, 0, 0, 0, 0,
                                       0x12345678u, 0);
    EXPECT_EQ(out[13], 0x78);
    EXPECT_EQ(out[14], 0x56);
    EXPECT_EQ(out[15], 0x34);
    EXPECT_EQ(out[16], 0x12);
    EXPECT_EQ(readLe32(out + 13), 0x12345678u);
    dish_wire::encodeTouchpadPayloadV2(out, 0, true, false, false, false, false, 0, 0, 0, 0, 0, 0,
                                       UINT32_MAX, 0);
    EXPECT_EQ(readLe32(out + 13), UINT32_MAX);
}

TEST(EncodeTouchpadPayloadV2, ScrollAtBytes17to18LittleEndianSigned) {
    uint8_t out[19]{};
    dish_wire::encodeTouchpadPayloadV2(out, 0, false, false, false, false, false, 0, 0, 0, 0, 0, 0,
                                       0, 0x0178);
    EXPECT_EQ(out[17], 0x78);
    EXPECT_EQ(out[18], 0x01);
    EXPECT_EQ(readLe16(out + 17), 0x0178);
    dish_wire::encodeTouchpadPayloadV2(out, 0, false, false, false, false, false, 0, 0, 0, 0, 0, 0,
                                       0, static_cast<int16_t>(-120));
    EXPECT_EQ(readLe16(out + 17), static_cast<int16_t>(-120));
    dish_wire::encodeTouchpadPayloadV2(out, 0, false, false, false, false, false, 0, 0, 0, 0, 0, 0,
                                       0, INT16_MIN);
    EXPECT_EQ(readLe16(out + 17), INT16_MIN);
    dish_wire::encodeTouchpadPayloadV2(out, 0, false, false, false, false, false, 0, 0, 0, 0, 0, 0,
                                       0, INT16_MAX);
    EXPECT_EQ(readLe16(out + 17), INT16_MAX);
}

TEST(EncodeTouchpadPayloadV2, ButtonsRideOnFingerlessFrames) {
    // A click with no finger down is a valid mouse frame.
    uint8_t out[19]{};
    dish_wire::encodeTouchpadPayloadV2(out, 0, false, false, true, true, true, 0, 0, 0, 0, 0, 0, 0,
                                       0);
    EXPECT_EQ(out[1], 0x00);
    EXPECT_EQ(out[2], 0x07);
}

// ---- datagram ceilings (contract §Packet format) ---------------------------

TEST(DatagramCeilings, InnerPayloadCeilingIsTheMtuMinusTheFraming) {
    // 1500 is the whole datagram, not the payload: the two headers and the AEAD
    // tag come out of it first. Getting this wrong by even 4 bytes would put a
    // VBR spike on the wire that the satellite truncates on read and then fails
    // to authenticate, which reads as a crypto bug rather than a size bug.
    EXPECT_EQ(dish_wire::MAX_DATAGRAM_BYTES, 1500u);
    EXPECT_EQ(dish_wire::OUTER_HEADER_BYTES, 8u);
    EXPECT_EQ(dish_wire::INNER_HEADER_BYTES, 4u);
    EXPECT_EQ(dish_wire::AEAD_TAG_BYTES, 16u);
    EXPECT_EQ(dish_wire::MAX_INNER_PAYLOAD_BYTES, 1472u);
}

TEST(DatagramCeilings, PayloadsUpToTheCeilingFitAndOneMoreDoesNot) {
    // The send path's whole size guard, exercised without a socket.
    EXPECT_TRUE(dish_wire::innerPayloadFits(0));
    EXPECT_TRUE(dish_wire::innerPayloadFits(13)); // MSG_GAMEPAD_DATA
    EXPECT_TRUE(dish_wire::innerPayloadFits(dish_wire::MAX_INNER_PAYLOAD_BYTES - 1));
    EXPECT_TRUE(dish_wire::innerPayloadFits(dish_wire::MAX_INNER_PAYLOAD_BYTES));
    EXPECT_FALSE(dish_wire::innerPayloadFits(dish_wire::MAX_INNER_PAYLOAD_BYTES + 1));
    EXPECT_FALSE(dish_wire::innerPayloadFits(dish_wire::MAX_DATAGRAM_BYTES));
}

TEST(DatagramCeilings, AFullSizeInnerMessageIsExactlyOneDatagram) {
    // The property the buffers in the JNI send path are sized from: header plus
    // a maximal payload plus the tag lands exactly on the MTU, so nothing this
    // client emits can fragment.
    EXPECT_EQ(dish_wire::OUTER_HEADER_BYTES + dish_wire::INNER_HEADER_BYTES +
                  dish_wire::MAX_INNER_PAYLOAD_BYTES + dish_wire::AEAD_TAG_BYTES,
              dish_wire::MAX_DATAGRAM_BYTES);
}

// ---- controller audio: MSG_MIC_AUDIO / MSG_SPEAKER_AUDIO -------------------

TEST(AudioFrameHeader, CtrlIdxThenBigEndianSeq) {
    // Big-endian, unlike every other payload in this file: the audio header
    // mirrors satellite's encodeAudioFrameHeader, not an XUSB struct.
    uint8_t out[3]{};
    dish_wire::encodeAudioFrameHeader(out, 7, 0x1234);
    EXPECT_EQ(out[0], 7);
    EXPECT_EQ(out[1], 0x12);
    EXPECT_EQ(out[2], 0x34);
}

TEST(AudioFrameHeader, SeqUsesTheWholeU16Range) {
    uint8_t out[3]{};
    dish_wire::encodeAudioFrameHeader(out, 0, 0);
    EXPECT_EQ(out[1], 0x00);
    EXPECT_EQ(out[2], 0x00);
    dish_wire::encodeAudioFrameHeader(out, 0, 0x00FF);
    EXPECT_EQ(out[1], 0x00);
    EXPECT_EQ(out[2], 0xFF);
    dish_wire::encodeAudioFrameHeader(out, 0, 0xFF00);
    EXPECT_EQ(out[1], 0xFF);
    EXPECT_EQ(out[2], 0x00);
    dish_wire::encodeAudioFrameHeader(out, 0xFF, 0xFFFF);
    EXPECT_EQ(out[0], 0xFF);
    EXPECT_EQ(out[1], 0xFF);
    EXPECT_EQ(out[2], 0xFF);
}

TEST(AudioFrameHeader, DecodeReadsExactlyWhatEncodeWrote) {
    const uint8_t in[3] = {0x05, 0xBE, 0xEF};
    const dish_wire::AudioFrameHeader h = dish_wire::decodeAudioFrameHeader(in);
    EXPECT_EQ(h.ctrlIdx, 0x05);
    EXPECT_EQ(h.seq, 0xBEEF);
}

TEST(AudioFrameHeader, RoundTripsEverySeqBoundaryAndTheWrap) {
    const uint16_t seqs[] = {0, 1, 0x00FF, 0x0100, 0x7FFF, 0x8000, 0xFFFE, 0xFFFF};
    for (uint16_t seq : seqs) {
        uint8_t buf[3]{};
        dish_wire::encodeAudioFrameHeader(buf, 3, seq);
        const dish_wire::AudioFrameHeader h = dish_wire::decodeAudioFrameHeader(buf);
        EXPECT_EQ(h.ctrlIdx, 3);
        EXPECT_EQ(h.seq, seq);
    }
}

TEST(AudioFrameHeader, DecodeIgnoresWhateverFollowsTheHeader) {
    // The Opus packet starts at byte 3 and is not the header's business; a
    // decoder that read past its three bytes would corrupt the seq.
    const uint8_t frame[8] = {0x02, 0x00, 0x2A, 0xFC, 0xFF, 0xFF, 0xFF, 0xFF};
    const dish_wire::AudioFrameHeader h = dish_wire::decodeAudioFrameHeader(frame);
    EXPECT_EQ(h.ctrlIdx, 0x02);
    EXPECT_EQ(h.seq, 42);
}

TEST(AudioFrameHeader, PayloadBoundsLeaveRoomForExactlyOneOpusPacket) {
    EXPECT_EQ(dish_wire::AUDIO_WIRE_HEADER_BYTES, 3u);
    // A 1-byte Opus packet is a legal DTX silence frame, so the smallest
    // dispatchable payload is the header plus one byte; a bare header is
    // malformed, not silence.
    EXPECT_EQ(dish_wire::AUDIO_WIRE_MIN_PAYLOAD_BYTES, 4u);
    EXPECT_EQ(dish_wire::AUDIO_WIRE_MAX_OPUS_BYTES, 1469u);
    EXPECT_TRUE(dish_wire::innerPayloadFits(dish_wire::AUDIO_WIRE_HEADER_BYTES +
                                            dish_wire::AUDIO_WIRE_MAX_OPUS_BYTES));
    EXPECT_FALSE(dish_wire::innerPayloadFits(dish_wire::AUDIO_WIRE_HEADER_BYTES +
                                             dish_wire::AUDIO_WIRE_MAX_OPUS_BYTES + 1));
}

// ---- controller audio: MSG_MIC_LED ----------------------------------------

TEST(DecodeMicLedPayload, CtrlIdxThenState) {
    const uint8_t in[2] = {4, dish_wire::MIC_LED_STATE_PULSE};
    const dish_wire::MicLedPayload led = dish_wire::decodeMicLedPayload(in);
    EXPECT_EQ(led.ctrlIdx, 4);
    EXPECT_EQ(led.state, 2);
    EXPECT_EQ(dish_wire::MIC_LED_PAYLOAD_BYTES, 2u);
}

TEST(DecodeMicLedPayload, TheThreeStatesAreOffOnPulseInThatOrder) {
    // Wire values, mirrored from satellite core/types.h: the host sources them
    // from the game's own DS5 output report, so renumbering them here would
    // silently light the wrong lamp.
    EXPECT_EQ(dish_wire::MIC_LED_STATE_OFF, 0);
    EXPECT_EQ(dish_wire::MIC_LED_STATE_ON, 1);
    EXPECT_EQ(dish_wire::MIC_LED_STATE_PULSE, 2);
    EXPECT_EQ(dish_wire::MIC_LED_STATE_COUNT, 3);
}

TEST(DecodeMicLedPayload, OnlyTheThreeKnownStatesAreValid) {
    // A state we do not know can only come from a host speaking something
    // newer. Rendering it as a guess would be worse than rendering nothing, so
    // the receive arm drops it rather than clamping.
    EXPECT_TRUE(dish_wire::micLedStateValid(dish_wire::MIC_LED_STATE_OFF));
    EXPECT_TRUE(dish_wire::micLedStateValid(dish_wire::MIC_LED_STATE_ON));
    EXPECT_TRUE(dish_wire::micLedStateValid(dish_wire::MIC_LED_STATE_PULSE));
    EXPECT_FALSE(dish_wire::micLedStateValid(3));
    EXPECT_FALSE(dish_wire::micLedStateValid(0xFF));
}
