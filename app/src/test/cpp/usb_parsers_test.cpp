// SPDX-License-Identifier: LGPL-3.0-or-later

#include "usb_parsers.h"

#include <gtest/gtest.h>

#include <cstdint>
#include <vector>

using gamepad::DeviceState;
using gamepad::XUSB_A;
using gamepad::XUSB_B;
using gamepad::XUSB_GUIDE;
using usbparsers::buildRumbleReport;
using usbparsers::decodeReport;
using usbparsers::Parser;
using usbparsers::parserHasRumble;
using usbparsers::ParserState;

namespace {

std::vector<uint8_t> gipMain(uint8_t face = 0, int16_t lx = 0) {
    std::vector<uint8_t> r(18, 0);
    r[0] = 0x20;
    r[4] = face;
    r[10] = (uint8_t)(lx & 0xFF);
    r[11] = (uint8_t)((lx >> 8) & 0xFF);
    return r;
}

std::vector<uint8_t> gipGuide(bool pressed) {
    std::vector<uint8_t> r(5, 0);
    r[0] = 0x07;
    r[4] = pressed ? 0x01 : 0x00;
    return r;
}

bool decodeXbox(const std::vector<uint8_t>& buf, DeviceState& s, ParserState& st) {
    return decodeReport(Parser::XBOX_ONE_GIP, buf.data(), buf.size(), s, &st);
}

} // namespace

TEST(XboxGip, MainReportDecodesButtonsAndStick) {
    ParserState st;
    DeviceState s;
    ASSERT_TRUE(decodeXbox(gipMain(/*A*/ 0x10, /*lx*/ 1000), s, st));
    EXPECT_TRUE(s.wButtons & XUSB_A);
    EXPECT_EQ(1000, s.sLX);
    EXPECT_FALSE(s.wButtons & XUSB_GUIDE);
}

TEST(XboxGip, GuidePressSetsBitAndPreservesMainState) {
    ParserState st;
    DeviceState s;
    ASSERT_TRUE(decodeXbox(gipMain(0x10, 1000), s, st));
    // Guide arrives in its own report; the prior stick/button state must survive.
    ASSERT_TRUE(decodeXbox(gipGuide(true), s, st));
    EXPECT_TRUE(s.wButtons & XUSB_GUIDE);
    EXPECT_TRUE(s.wButtons & XUSB_A);
    EXPECT_EQ(1000, s.sLX);
}

TEST(XboxGip, GuideReleaseClearsBit) {
    ParserState st;
    DeviceState s;
    ASSERT_TRUE(decodeXbox(gipMain(0x10, 1000), s, st));
    ASSERT_TRUE(decodeXbox(gipGuide(true), s, st));
    ASSERT_TRUE(decodeXbox(gipGuide(false), s, st));
    EXPECT_FALSE(s.wButtons & XUSB_GUIDE);
    EXPECT_TRUE(s.wButtons & XUSB_A);
    EXPECT_EQ(1000, s.sLX);
}

TEST(XboxGip, HeldGuideSurvivesNextMainReport) {
    ParserState st;
    DeviceState s;
    ASSERT_TRUE(decodeXbox(gipMain(0x10, 1000), s, st));
    ASSERT_TRUE(decodeXbox(gipGuide(true), s, st));
    // A fresh main report with a different button/stick must keep the still-held guide bit.
    ASSERT_TRUE(decodeXbox(gipMain(/*B*/ 0x20, /*lx*/ 2000), s, st));
    EXPECT_TRUE(s.wButtons & XUSB_GUIDE);
    EXPECT_TRUE(s.wButtons & XUSB_B);
    EXPECT_FALSE(s.wButtons & XUSB_A);
    EXPECT_EQ(2000, s.sLX);
}

TEST(XboxGip, GuideBeforeAnyMainReportPublishesGuideOnRest) {
    ParserState st;
    DeviceState s;
    ASSERT_TRUE(decodeXbox(gipGuide(true), s, st));
    EXPECT_EQ(XUSB_GUIDE, s.wButtons);
    EXPECT_EQ(0, s.sLX);
}

TEST(XboxGip, NullParserStateRejectsReport) {
    DeviceState s;
    auto buf = gipMain(0x10, 0);
    EXPECT_FALSE(decodeReport(Parser::XBOX_ONE_GIP, buf.data(), buf.size(), s, nullptr));
}

TEST(XboxGip, ShortGuideReportIsRejected) {
    ParserState st;
    DeviceState s;
    std::vector<uint8_t> tooShort = {0x07, 0x20, 0x00, 0x02};
    EXPECT_FALSE(decodeXbox(tooShort, s, st));
}

TEST(Rumble, Xbox360Bytes) {
    uint8_t out[64];
    size_t n = buildRumbleReport(Parser::XINPUT_360, 0xFF00, 0x8000, 5, out, sizeof(out));
    ASSERT_EQ(8u, n);
    const uint8_t want[8] = {0x00, 0x08, 0x00, 0xFF, 0x80, 0x00, 0x00, 0x00};
    for (size_t i = 0; i < n; i++) EXPECT_EQ(want[i], out[i]) << "byte " << i;
}

TEST(Rumble, XboxOneGipBytesAndSeq) {
    uint8_t out[64];
    size_t n = buildRumbleReport(Parser::XBOX_ONE_GIP, 0xFFFF, 0x0000, 0x2A, out, sizeof(out));
    ASSERT_EQ(13u, n);
    const uint8_t want[13] = {0x09, 0x00, 0x2A, 0x09, 0x00, 0x0F, 0x00,
                              0x00, 0x7F, 0x00, 0xFF, 0x00, 0xFF};
    for (size_t i = 0; i < n; i++) EXPECT_EQ(want[i], out[i]) << "byte " << i;
}

TEST(Rumble, DualShock4Bytes) {
    uint8_t out[64];
    size_t n = buildRumbleReport(Parser::DUALSHOCK4, 0xAA00, 0x5500, 0, out, sizeof(out));
    ASSERT_EQ(32u, n);
    EXPECT_EQ(0x05, out[0]);
    EXPECT_EQ(0x01, out[1]);
    EXPECT_EQ(0x55, out[4]);
    EXPECT_EQ(0xAA, out[5]);
    EXPECT_EQ(0x00, out[6]);
}

TEST(Rumble, DualSenseBytes) {
    uint8_t out[64];
    size_t n = buildRumbleReport(Parser::DUALSENSE, 0xAA00, 0x5500, 0, out, sizeof(out));
    ASSERT_EQ(63u, n);
    EXPECT_EQ(0x02, out[0]);
    EXPECT_EQ(0x01, out[1]);
    EXPECT_EQ(0x55, out[3]);
    EXPECT_EQ(0xAA, out[4]);
}

TEST(Rumble, SwitchProNeutralWhenZero) {
    uint8_t out[64];
    size_t n = buildRumbleReport(Parser::SWITCH_PRO_USB, 0, 0, 3, out, sizeof(out));
    ASSERT_EQ(10u, n);
    EXPECT_EQ(0x10, out[0]);
    EXPECT_EQ(0x03, out[1]);
    const uint8_t neutral[4] = {0x00, 0x01, 0x40, 0x40};
    for (int i = 0; i < 4; i++) {
        EXPECT_EQ(neutral[i], out[2 + i]) << "left byte " << i;
        EXPECT_EQ(neutral[i], out[6 + i]) << "right byte " << i;
    }
}

TEST(Rumble, SwitchProMaxStrongEncodesTopAmplitude) {
    uint8_t out[64];
    size_t n = buildRumbleReport(Parser::SWITCH_PRO_USB, 0xFFFF, 0, 0, out, sizeof(out));
    ASSERT_EQ(10u, n);
    EXPECT_EQ(0x00, out[2]);
    EXPECT_EQ(0xC9, out[3]);
    EXPECT_EQ(0x40, out[4]);
    EXPECT_EQ(0x72, out[5]);
    const uint8_t neutral[4] = {0x00, 0x01, 0x40, 0x40};
    for (int i = 0; i < 4; i++) EXPECT_EQ(neutral[i], out[6 + i]) << "right byte " << i;
}

TEST(Rumble, UnsupportedParsersReturnZero) {
    uint8_t out[64];
    EXPECT_EQ(0u, buildRumbleReport(Parser::STADIA, 0xFFFF, 0xFFFF, 0, out, sizeof(out)));
    EXPECT_EQ(0u, buildRumbleReport(Parser::GENERIC_HID_GAMEPAD, 0xFFFF, 0xFFFF, 0, out, sizeof(out)));
    EXPECT_EQ(0u, buildRumbleReport(Parser::NONE, 0xFFFF, 0xFFFF, 0, out, sizeof(out)));
}

TEST(Rumble, TooSmallBufferReturnsZero) {
    uint8_t out[10];
    EXPECT_EQ(0u, buildRumbleReport(Parser::DUALSENSE, 0xFFFF, 0xFFFF, 0, out, sizeof(out)));
}

// parserHasRumble must agree with which families buildRumbleReport actually emits.
TEST(RumbleCapability, FamiliesWithBuildersReportTrue) {
    EXPECT_TRUE(parserHasRumble(Parser::XINPUT_360));
    EXPECT_TRUE(parserHasRumble(Parser::XBOX_ONE_GIP));
    EXPECT_TRUE(parserHasRumble(Parser::DUALSHOCK4));
    EXPECT_TRUE(parserHasRumble(Parser::DUALSENSE));
    EXPECT_TRUE(parserHasRumble(Parser::SWITCH_PRO_USB));
}

TEST(RumbleCapability, FamiliesWithoutBuildersReportFalse) {
    EXPECT_FALSE(parserHasRumble(Parser::STADIA));
    EXPECT_FALSE(parserHasRumble(Parser::GENERIC_HID_GAMEPAD));
    EXPECT_FALSE(parserHasRumble(Parser::NONE));
}

// Guards the refactor that moved I/O out: a non-Xbox decoder still works through decodeReport.
TEST(Decode, DualShock4StillDecodesAfterRefactor) {
    std::vector<uint8_t> r(10, 0);
    r[0] = 0x01;
    r[1] = 128;
    r[2] = 128;
    r[3] = 128;
    r[4] = 128;
    r[5] = 0x20;
    DeviceState s;
    ParserState st;
    ASSERT_TRUE(decodeReport(Parser::DUALSHOCK4, r.data(), r.size(), s, &st));
    EXPECT_TRUE(s.wButtons & XUSB_A);
    EXPECT_EQ(0, s.sLX);
    EXPECT_EQ(0, s.sLY);
}
