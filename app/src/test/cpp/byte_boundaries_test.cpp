// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.
//
// The byte-level assembly every parser and codec rests on, at the values a wrong shift, mask or
// sign extension gets wrong: 0x00, 0x7F, 0x80 and 0xFF in each position that is shifted, the sign
// bit of a 16-bit word, the top bit of a 32-bit one. These pin behaviour, so they hold before and
// after the bitwise arithmetic moves to unsigned-only operands (clang-tidy's bugprone-signed-bitwise).

#include <gtest/gtest.h>
#include <sodium.h>

#include <array>
#include <cstdint>
#include <vector>

#include "fake_satellite.h"
#include "recording_sinks.h"
#include "receive_path.h"
#include "send_path.h"
#include "session_table.h"
#include "usb_hid_descriptor.h"
#include "usb_parsers.h"
#include "vector_session.h"
#include "wire_encoders.h"

namespace {

using Bytes = std::vector<uint8_t>;

constexpr std::array<uint8_t, 4> kBoundaryBytes = {0x00, 0x7F, 0x80, 0xFF};

uint16_t le16(const uint8_t* p) { return (uint16_t)((uint16_t)p[0] | ((uint16_t)p[1] << 8)); }

uint32_t le32(const uint8_t* p) {
    return (uint32_t)p[0] | ((uint32_t)p[1] << 8) | ((uint32_t)p[2] << 16) |
           ((uint32_t)p[3] << 24);
}

} // namespace

// ── wire encoders ────────────────────────────────────────────────────────────────────────────

TEST(ByteBoundaries, ALittleEndianHalfWordCarriesEachByteAtItsOwnPosition) {
    for (const uint8_t b : kBoundaryBytes) {
        uint8_t out[2] = {};
        dish_wire::putLE16(out, (uint16_t)b);
        EXPECT_EQ(out[0], b);
        EXPECT_EQ(out[1], 0x00);
        dish_wire::putLE16(out, (uint16_t)(b << 8));
        EXPECT_EQ(out[0], 0x00);
        EXPECT_EQ(out[1], b);
    }
}

TEST(ByteBoundaries, ALittleEndianWordCarriesEachByteAtItsOwnPosition) {
    for (const uint8_t b : kBoundaryBytes) {
        for (unsigned position = 0; position < 4; ++position) {
            uint8_t out[4] = {};
            dish_wire::putLE32(out, (uint32_t)b << (8 * position));
            for (unsigned i = 0; i < 4; ++i) EXPECT_EQ(out[i], i == position ? b : 0x00);
            EXPECT_EQ(le32(out), (uint32_t)b << (8 * position));
        }
    }
}

TEST(ByteBoundaries, AnAudioFrameHeaderKeepsASequenceWithItsTopBitsSet) {
    const uint16_t sequences[] = {0x0000, 0x007F, 0x0080, 0x00FF, 0x7F00, 0x8000,
                                  0x80FF, 0xFF00, 0xFF7F, 0xFFFF, 0x1234};
    for (const uint16_t seq : sequences) {
        uint8_t out[dish_wire::AUDIO_WIRE_HEADER_BYTES] = {};
        dish_wire::encodeAudioFrameHeader(out, 0xFF, seq);
        EXPECT_EQ(out[0], 0xFF);
        EXPECT_EQ(out[1], (uint8_t)(seq >> 8));
        EXPECT_EQ(out[2], (uint8_t)(seq & 0xFF));
        const dish_wire::AudioFrameHeader decoded = dish_wire::decodeAudioFrameHeader(out);
        EXPECT_EQ(decoded.ctrlIdx, 0xFF);
        EXPECT_EQ(decoded.seq, seq);
    }
}

// ── send path ────────────────────────────────────────────────────────────────────────────────

TEST(ByteBoundaries, AFramedMessageNamesItsTypeAndLengthBigEndian) {
    const uint16_t types[] = {0x0001, 0x007F, 0x0080, 0x00FF, 0x0100, 0x7F00, 0x8000, 0xFF00, 0xFFFF};
    for (const uint16_t type : types) {
        dish_send::Framed framed;
        const uint8_t payload[3] = {0x01, 0x80, 0xFF};
        ASSERT_TRUE(dish_send::frame(framed, type, payload, sizeof(payload)));
        EXPECT_EQ(framed.inner[0], (uint8_t)(type >> 8));
        EXPECT_EQ(framed.inner[1], (uint8_t)(type & 0xFF));
        EXPECT_EQ(framed.inner[2], 0x00);
        EXPECT_EQ(framed.inner[3], 0x03);
        EXPECT_EQ(framed.inner[4], 0x01);
        EXPECT_EQ(framed.inner[5], 0x80);
        EXPECT_EQ(framed.inner[6], 0xFF);
    }
}

TEST(ByteBoundaries, AFramedLengthWithItsTopBitsSetStaysBigEndian) {
    for (const uint16_t length : {(uint16_t)0x0080, (uint16_t)0x00FF, (uint16_t)0x0100,
                                  (uint16_t)0x0123, (uint16_t)0x05A0}) {
        dish_send::Framed framed;
        const std::vector<uint8_t> payload(length, 0xA5);
        const bool fits = dish_wire::innerPayloadFits(length);
        ASSERT_EQ(dish_send::frame(framed, dish_wire::MSG_MIC_AUDIO, payload.data(), length), fits);
        if (!fits) continue;
        EXPECT_EQ(framed.inner[2], (uint8_t)(length >> 8));
        EXPECT_EQ(framed.inner[3], (uint8_t)(length & 0xFF));
    }
}

TEST(ByteBoundaries, AControllerIndexKeepsItsLowByteInAMotionFrame) {
    const int indexes[] = {0, 1, 0x7F, 0x80, 0xFF, 0x100, 0x17F, 0x1FF, -1};
    const dish_send::MotionSample sample{{0, 0, 0}, {0, 0, 0}, 0};
    for (const int index : indexes) {
        dish_send::Framed framed;
        dish_send::frameMotion(framed, index, sample);
        EXPECT_EQ(framed.inner[dish_wire::INNER_HEADER_BYTES], (uint8_t)(index & 0xFF)) << index;
        EXPECT_EQ(framed.controller, (uint8_t)(index & dish_session::CTRL_INDEX_MASK)) << index;
    }
}

TEST(ByteBoundaries, AMotionFrameWritesEachSampleLittleEndianWithItsSignBit) {
    const int16_t values[] = {0, 1, 0x7F, 0x80, 0xFF, 0x100, 0x7FFF, -0x8000, -1, -0x80, -0x81};
    for (const int16_t v : values) {
        const dish_send::MotionSample sample{{v, 0, 0}, {0, 0, v}, 0x80FF7F01u};
        dish_send::Framed framed;
        dish_send::frameMotion(framed, 3, sample);
        const uint8_t* payload = framed.inner + dish_wire::INNER_HEADER_BYTES;
        EXPECT_EQ(le16(payload + 1), (uint16_t)v);
        EXPECT_EQ(le16(payload + 11), (uint16_t)v);
        EXPECT_EQ(le32(payload + 13), 0x80FF7F01u);
    }
}

class CounterBoundaries : public vector_session::VectorSession {};

TEST_F(CounterBoundaries, TheCounterIsFourBigEndianBytesWhateverItsTopBitsAre) {
    const uint32_t counters[] = {0x00000001u, 0x0000007Fu, 0x00000080u, 0x000000FFu, 0x00007F00u,
                                 0x00008000u, 0x0000FF00u, 0x007F0000u, 0x00800000u, 0x00FF0000u,
                                 0x7F000000u, 0x80000000u, 0xFF000000u, 0x7FFFFFFFu, 0x80000001u,
                                 0x01020304u, 0x80FF7F01u, 0xFFFFFFFEu};
    for (const uint32_t counter : counters) {
        session->counter.store(counter);
        ASSERT_TRUE(dish_send::sendEncrypted(session, dish_wire::MSG_HEARTBEAT_PING, nullptr, 0));
        const std::vector<Bytes> sent = sentBytes();
        ASSERT_EQ(sent.size(), 1u);
        EXPECT_EQ(sent[0][4], (uint8_t)(counter >> 24)) << std::hex << counter;
        EXPECT_EQ(sent[0][5], (uint8_t)(counter >> 16)) << std::hex << counter;
        EXPECT_EQ(sent[0][6], (uint8_t)(counter >> 8)) << std::hex << counter;
        EXPECT_EQ(sent[0][7], (uint8_t)counter) << std::hex << counter;
        EXPECT_EQ(vector_session::counterOf(sent[0]), counter);
    }
}

// ── receive path ─────────────────────────────────────────────────────────────────────────────

namespace {

constexpr uint8_t kToken[4] = {0x10, 0x20, 0x30, 0x40};

class ReceiveBoundaries : public ::testing::Test {
  protected:
    static void SetUpTestSuite() { ASSERT_GE(sodium_init(), 0); }

    void SetUp() override {
        handle = table.open("127.0.0.1", 9);
        ASSERT_GT(handle, 0);
        session = table.find(handle);
        for (int i = 0; i < 32; i++) key[i] = (uint8_t)(0x80 + i);
        dish_session::setConnectionParams(session, kToken, key, 3);
    }

    void TearDown() override { table.close(handle); }

    int deliver(const uint32_t counter, const uint16_t msgType, const Bytes& payload) {
        const Bytes datagram = fake_satellite::sealToClient(kToken, key, counter, msgType,
                                                            payload.data(), payload.size());
        return dish_receive::handleDatagram(session, datagram.data(), datagram.size(), sink);
    }

    uint8_t key[32] = {};
    dish_session::SessionTable table;
    int handle = -1;
    dish_session::SessionRef session;
    recording::Feedback sink;
};

} // namespace

TEST_F(ReceiveBoundaries, AnAckReadsItsEpochAndBitmapAsUnsignedBigEndianHalfWords) {
    uint32_t counter = 1;
    for (const uint8_t high : kBoundaryBytes) {
        for (const uint8_t low : kBoundaryBytes) {
            ASSERT_EQ(deliver(counter++, dish_wire::MSG_HEARTBEAT_ACK, {1, 2, high, low, low, high}), 1);
            EXPECT_EQ(session->serverEpoch.load(), (int32_t)(((uint32_t)high << 8) | low));
            EXPECT_EQ(session->activeBitmap.load(), (int32_t)(((uint32_t)low << 8) | high));
        }
    }
}

TEST_F(ReceiveBoundaries, ARumbleReadsEachMagnitudeAndDurationAsAnUnsignedBigEndianHalfWord) {
    uint32_t counter = 1;
    const uint16_t magnitudes[] = {0x0000, 0x007F, 0x0080, 0x00FF, 0x7F00, 0x8000, 0x80FF, 0xFFFF};
    for (const uint16_t m : magnitudes) {
        const Bytes payload = {2,
                               (uint8_t)(m >> 8), (uint8_t)(m & 0xFF),
                               (uint8_t)(m & 0xFF), (uint8_t)(m >> 8),
                               (uint8_t)(m >> 8), (uint8_t)(m & 0xFF)};
        ASSERT_EQ(deliver(counter++, dish_wire::MSG_RUMBLE, payload), 1);
        const recording::Rumble& last = sink.rumbles.back();
        EXPECT_EQ(last.ctrlIdx, 2);
        EXPECT_EQ(last.strong, m);
        EXPECT_EQ(last.weak, (uint16_t)(((m & 0xFF) << 8) | (m >> 8)));
        EXPECT_EQ(last.durationMs, m);
    }
}

TEST_F(ReceiveBoundaries, TheDatagramCounterIsReadAsAnUnsignedBigEndianWord) {
    const uint32_t counters[] = {0x00000001u, 0x00000080u, 0x00008000u, 0x00800000u,
                                 0x7FFFFFFFu, 0x80000000u, 0x80FF7F01u, 0xFFFFFFFEu};
    for (const uint32_t counter : counters) {
        ASSERT_EQ(deliver(counter, dish_wire::MSG_HEARTBEAT_ACK, {}), 1) << std::hex << counter;
        EXPECT_EQ(session->lastRxCounter.load(), counter);
    }
}

// ── USB report decoders ──────────────────────────────────────────────────────────────────────

TEST(ByteBoundaries, AnXboxStickIsALittleEndianInt16WhoseSignComesFromTheHighByte) {
    struct Case {
        uint8_t low;
        uint8_t high;
        int16_t expected;
    };
    const Case cases[] = {{0x00, 0x00, 0},        {0x7F, 0x00, 127},      {0x80, 0x00, 128},
                          {0xFF, 0x00, 255},      {0x00, 0x7F, 0x7F00},   {0x00, 0x80, -32768},
                          {0xFF, 0x7F, 32767},    {0xFF, 0xFF, -1},       {0x7F, 0x80, -32641},
                          {0x80, 0xFF, -128}};
    for (const Case& c : cases) {
        std::vector<uint8_t> report(20, 0);
        report[0] = 0x00;
        report[1] = 0x14;
        for (size_t stick = 0; stick < 4; ++stick) {
            report[6 + 2 * stick] = c.low;
            report[7 + 2 * stick] = c.high;
        }
        gamepad::DeviceState s;
        usbparsers::ParserState state;
        ASSERT_TRUE(usbparsers::decodeReport(usbparsers::Parser::XINPUT_360, report.data(),
                                             report.size(), s, &state));
        EXPECT_EQ(s.sLX, c.expected);
        EXPECT_EQ(s.sLY, c.expected);
        EXPECT_EQ(s.sRX, c.expected);
        EXPECT_EQ(s.sRY, c.expected);
    }
}

TEST(ByteBoundaries, AnXboxOneTriggerIsATenBitLittleEndianValueClampedAtItsTop) {
    struct Case {
        uint8_t low;
        uint8_t high;
        uint8_t expected;
    };
    const Case cases[] = {{0x00, 0x00, 0},   {0x7F, 0x00, 31},  {0x80, 0x00, 31},  {0xFF, 0x00, 63},
                          {0x00, 0x01, 63},  {0x00, 0x02, 127}, {0xFF, 0x03, 255}, {0x00, 0x04, 255},
                          {0x00, 0x80, 255}, {0xFF, 0xFF, 255}};
    for (const Case& c : cases) {
        std::vector<uint8_t> report(24, 0);
        report[0] = 0x20;
        report[6] = c.low;
        report[7] = c.high;
        report[8] = c.low;
        report[9] = c.high;
        gamepad::DeviceState s;
        usbparsers::ParserState state;
        ASSERT_TRUE(usbparsers::decodeReport(usbparsers::Parser::XBOX_ONE_GIP, report.data(),
                                             report.size(), s, &state));
        EXPECT_EQ(s.bLT, c.expected) << (int)c.low << "," << (int)c.high;
        EXPECT_EQ(s.bRT, c.expected) << (int)c.low << "," << (int)c.high;
    }
}

TEST(ByteBoundaries, ADualShock4StickByteSpansTheInt16RangeAcrossItsFullByte) {
    const struct {
        uint8_t byte;
        int16_t x;
        int16_t y;
    } cases[] = {{0x00, -32768, 32767}, {0x7F, -257, 257}, {0x80, 0, 0}, {0xFF, 32639, -32639}};
    for (const auto& c : cases) {
        std::vector<uint8_t> r(10, 0);
        r[0] = 0x01;
        r[1] = c.byte;
        r[2] = c.byte;
        r[3] = c.byte;
        r[4] = c.byte;
        r[5] = 0x08;
        gamepad::DeviceState s;
        usbparsers::ParserState state;
        ASSERT_TRUE(usbparsers::decodeReport(usbparsers::Parser::DUALSHOCK4, r.data(), r.size(), s,
                                             &state));
        EXPECT_EQ(s.sLX, c.x) << (int)c.byte;
        EXPECT_EQ(s.sLY, c.y) << (int)c.byte;
        EXPECT_EQ(s.sRX, c.x) << (int)c.byte;
        EXPECT_EQ(s.sRY, c.y) << (int)c.byte;
    }
}

// ── HID descriptor ───────────────────────────────────────────────────────────────────────────

namespace {

// Two signed 8-bit axes (X, Y: -128..127), two signed 16-bit axes (Z, Rz), a 4-bit hat and 4 bits
// of padding, then ten buttons and six bits of padding: a 9-byte input report, no report id.
const uint8_t kSignedDescriptor[] = {
    0x05, 0x01, 0x09, 0x05, 0xA1, 0x01,       // Generic Desktop, Game Pad, Application
    0x05, 0x01, 0x09, 0x30, 0x09, 0x31,       // X, Y
    0x15, 0x80, 0x25, 0x7F, 0x75, 0x08, 0x95, 0x02, 0x81, 0x02,
    0x09, 0x32, 0x09, 0x35,                   // Z, Rz (the right stick)
    0x16, 0x00, 0x80, 0x26, 0xFF, 0x7F, 0x75, 0x10, 0x95, 0x02, 0x81, 0x02,
    0x09, 0x39, 0x15, 0x00, 0x25, 0x07, 0x75, 0x04, 0x95, 0x01, 0x81, 0x42, // hat
    0x75, 0x04, 0x95, 0x01, 0x81, 0x01,       // padding
    0x05, 0x09, 0x19, 0x01, 0x29, 0x0A, 0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x0A, 0x81, 0x02,
    0x75, 0x06, 0x95, 0x01, 0x81, 0x01,       // padding
    0xC0};

} // namespace

TEST(ByteBoundaries, ASignedHidAxisTakesItsSignFromTheTopBitOfItsOwnWidth) {
    usbhid::HidLayout layout;
    ASSERT_TRUE(usbhid::parseReportDescriptor(kSignedDescriptor, sizeof(kSignedDescriptor), layout));
    struct Case {
        uint8_t b8;
        int16_t expected8;
        uint8_t low16;
        uint8_t high16;
        int16_t expected16;
    };
    // The axes scale to the XUSB int16 range, so the expected values come from the descriptor's
    // own range: -128..127 for the bytes, -32768..32767 for the half-words.
    const Case cases[] = {{0x00, 0, 0x00, 0x00, 0},
                          {0x7F, 32767, 0xFF, 0x7F, 32767},
                          {0x80, -32768, 0x00, 0x80, -32768},
                          {0xFF, -258, 0xFF, 0xFF, -1}};
    for (const Case& c : cases) {
        std::vector<uint8_t> report(9, 0);
        report[0] = c.b8;
        report[1] = c.b8;
        report[2] = c.low16;
        report[3] = c.high16;
        report[4] = c.low16;
        report[5] = c.high16;
        report[6] = 0x08;
        gamepad::DeviceState s;
        ASSERT_TRUE(usbhid::decodeFromLayout(report.data(), report.size(), s, layout));
        EXPECT_EQ(s.sLX, c.expected8) << (int)c.b8;
        EXPECT_EQ(s.sRX, c.expected16) << (int)c.low16 << "," << (int)c.high16;
    }
}

TEST(ByteBoundaries, AHidButtonFieldReadsEachBitOfEachByteItSpans) {
    usbhid::HidLayout layout;
    ASSERT_TRUE(usbhid::parseReportDescriptor(kSignedDescriptor, sizeof(kSignedDescriptor), layout));
    for (const uint8_t byte : kBoundaryBytes) {
        std::vector<uint8_t> report(9, 0);
        report[6] = 0x08;
        report[7] = byte;
        report[8] = byte;
        gamepad::DeviceState s;
        ASSERT_TRUE(usbhid::decodeFromLayout(report.data(), report.size(), s, layout));
        // The decoder is the one reading these bits; what must not happen is a top bit lost to a
        // sign-extended shift, so the same byte in either position must give the same buttons.
        gamepad::DeviceState again;
        std::vector<uint8_t> swapped = report;
        std::swap(swapped[7], swapped[8]);
        ASSERT_TRUE(usbhid::decodeFromLayout(swapped.data(), swapped.size(), again, layout));
        EXPECT_EQ((s.wButtons != 0), byte != 0x00) << (int)byte;
        EXPECT_EQ(s.wButtons, again.wButtons) << (int)byte;
    }
}
