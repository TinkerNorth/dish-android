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
using usbparsers::parsePsCalibration;
using usbparsers::Parser;
using usbparsers::parserHasImu;
using usbparsers::parserHasRumble;
using usbparsers::ParserState;
using usbparsers::PsImuCalib;

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

void setLe16(std::vector<uint8_t>& b, size_t off, int16_t v) {
    b[off] = (uint8_t)(v & 0xFF);
    b[off + 1] = (uint8_t)((v >> 8) & 0xFF);
}

std::vector<uint8_t> switchReport(size_t len) {
    std::vector<uint8_t> r(len, 0);
    r[0] = 0x30;
    return r;
}

// Symmetric calibration: gyro maps raw -> raw*1024 calibrated, accel maps raw -> raw (8192/g).
std::vector<uint8_t> psCalibReport() {
    std::vector<uint8_t> r(35, 0);
    setLe16(r, 7, 1000);
    setLe16(r, 9, -1000);
    setLe16(r, 11, 1000);
    setLe16(r, 13, -1000);
    setLe16(r, 15, 1000);
    setLe16(r, 17, -1000);
    setLe16(r, 19, 1000);
    setLe16(r, 21, 1000);
    setLe16(r, 23, 8192);
    setLe16(r, 25, -8192);
    setLe16(r, 27, 8192);
    setLe16(r, 29, -8192);
    setLe16(r, 31, 8192);
    setLe16(r, 33, -8192);
    return r;
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
    EXPECT_EQ(0u,
              buildRumbleReport(Parser::GENERIC_HID_GAMEPAD, 0xFFFF, 0xFFFF, 0, out, sizeof(out)));
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

TEST(KnownDevices, VerifiedModelIsRecognizedAndFastLane) {
    const usbparsers::KnownDevice* k = usbparsers::lookupKnown(0x045E, 0x028E);
    ASSERT_NE(nullptr, k);
    EXPECT_EQ(Parser::XINPUT_360, k->parser);
    EXPECT_TRUE(usbparsers::isVerifiedFastLane(0x045E, 0x028E));
}

TEST(KnownDevices, ImportedModelIsRecognizedButNotFastLane) {
    // PowerA Xbox Series X EnWired: imported from SDL, routed to the GIP parser, but not
    // hardware-verified, so it must not auto-claim Direct.
    const usbparsers::KnownDevice* k = usbparsers::lookupKnown(0x20D6, 0x2001);
    ASSERT_NE(nullptr, k);
    EXPECT_EQ(Parser::XBOX_ONE_GIP, k->parser);
    EXPECT_FALSE(usbparsers::isVerifiedFastLane(0x20D6, 0x2001));
}

TEST(KnownDevices, ImportedPs4StickRoutesToDualShock4) {
    const usbparsers::KnownDevice* k = usbparsers::lookupKnown(0x2C22, 0x2000);
    ASSERT_NE(nullptr, k);
    EXPECT_EQ(Parser::DUALSHOCK4, k->parser);
    EXPECT_FALSE(usbparsers::isVerifiedFastLane(0x2C22, 0x2000));
}

TEST(KnownDevices, UnknownModelIsNeitherRecognizedNorFastLane) {
    EXPECT_EQ(nullptr, usbparsers::lookupKnown(0x0000, 0x0000));
    EXPECT_FALSE(usbparsers::isVerifiedFastLane(0x0000, 0x0000));
}

TEST(Decode, SwitchProAveragesImuSubframes) {
    // accelX 300/600/900 across the three subframes averages to 600: a single-subframe report of
    // 600 must match, and the first subframe alone (300) must not.
    auto three = switchReport(49);
    setLe16(three, 13, 300);
    setLe16(three, 25, 600);
    setLe16(three, 37, 900);
    auto avg = switchReport(25);
    setLe16(avg, 13, 600);
    auto first = switchReport(25);
    setLe16(first, 13, 300);

    DeviceState sThree, sAvg, sFirst;
    ParserState p1, p2, p3;
    ASSERT_TRUE(decodeReport(Parser::SWITCH_PRO_USB, three.data(), three.size(), sThree, &p1));
    ASSERT_TRUE(decodeReport(Parser::SWITCH_PRO_USB, avg.data(), avg.size(), sAvg, &p2));
    ASSERT_TRUE(decodeReport(Parser::SWITCH_PRO_USB, first.data(), first.size(), sFirst, &p3));
    EXPECT_TRUE(sThree.motionValid);
    EXPECT_EQ(sAvg.accelX, sThree.accelX);
    EXPECT_NE(sFirst.accelX, sThree.accelX);
}

TEST(Decode, SwitchProShortReportHasNoImu) {
    auto r = switchReport(12);
    DeviceState s;
    ParserState p;
    ASSERT_TRUE(decodeReport(Parser::SWITCH_PRO_USB, r.data(), r.size(), s, &p));
    EXPECT_FALSE(s.motionValid);
}

TEST(Decode, Xbox360WirelessDecodesLikeWired) {
    std::vector<uint8_t> r(14, 0);
    r[0] = 0x00;
    r[3] = 0x10;          // A
    setLe16(r, 6, 10000); // sLX
    DeviceState wired, wireless;
    ParserState p1, p2;
    ASSERT_TRUE(decodeReport(Parser::XINPUT_360, r.data(), r.size(), wired, &p1));
    ASSERT_TRUE(decodeReport(Parser::XINPUT_360_WIRELESS, r.data(), r.size(), wireless, &p2));
    EXPECT_TRUE(wireless.wButtons & XUSB_A);
    EXPECT_EQ(wired.wButtons, wireless.wButtons);
    EXPECT_EQ(wired.sLX, wireless.sLX);
}

TEST(Rumble, Xbox360WirelessWrapsFrame) {
    uint8_t out[64];
    size_t n = buildRumbleReport(Parser::XINPUT_360_WIRELESS, 0xFF00, 0x8000, 7, out, sizeof(out));
    ASSERT_EQ(12u, n);
    const uint8_t want[12] = {0x00, 0x01, 0x0F, 0xC0, 0x00, 0xFF,
                              0x80, 0x00, 0x00, 0x00, 0x00, 0x00};
    for (size_t i = 0; i < n; i++) EXPECT_EQ(want[i], out[i]) << "byte " << i;
}

TEST(Rumble, Xbox360WirelessTooSmallBufferReturnsZero) {
    uint8_t out[11];
    EXPECT_EQ(0u,
              buildRumbleReport(Parser::XINPUT_360_WIRELESS, 0xFFFF, 0xFFFF, 0, out, sizeof(out)));
}

TEST(RumbleCapability, WirelessXbox360ReportsTrue) {
    EXPECT_TRUE(parserHasRumble(Parser::XINPUT_360_WIRELESS));
}

TEST(PsCalibration, ParsesGyroAndAccelFactors) {
    auto r = psCalibReport();
    PsImuCalib c;
    ASSERT_TRUE(parsePsCalibration(r.data(), r.size(), c));
    EXPECT_TRUE(c.valid);
    EXPECT_EQ(2048000, c.gyroNumer[0]); // speed2x(2000) * 1024
    EXPECT_EQ(2000, c.gyroDenom[0]);    // |plus| + |minus|
    EXPECT_EQ(0, c.accelBias[0]);       // plus - range/2
    EXPECT_EQ(16384, c.accelNumer[0]);  // 2 * 8192
    EXPECT_EQ(16384, c.accelDenom[0]);  // plus - minus
}

TEST(PsCalibration, RejectsShortReport) {
    std::vector<uint8_t> r(20, 0);
    PsImuCalib c;
    EXPECT_FALSE(parsePsCalibration(r.data(), r.size(), c));
    EXPECT_FALSE(c.valid);
}

TEST(PsCalibration, RejectsZeroDenominator) {
    std::vector<uint8_t> r(35, 0); // all-zero plus/minus -> gyro denom 0
    PsImuCalib c;
    EXPECT_FALSE(parsePsCalibration(r.data(), r.size(), c));
}

TEST(ImuCapability, PlayStationParsersHaveImu) {
    EXPECT_TRUE(parserHasImu(Parser::DUALSHOCK4));
    EXPECT_TRUE(parserHasImu(Parser::DUALSENSE));
    EXPECT_TRUE(parserHasImu(Parser::SWITCH_PRO_USB));
    EXPECT_FALSE(parserHasImu(Parser::XINPUT_360));
}

TEST(Decode, DualShock4DecodesCalibratedImu) {
    auto calib = psCalibReport();
    ParserState st;
    ASSERT_TRUE(parsePsCalibration(calib.data(), calib.size(), st.psImu));

    std::vector<uint8_t> r(25, 0);
    r[0] = 0x01;
    r[1] = r[2] = r[3] = r[4] = 128; // sticks centered
    setLe16(r, 13, 2000);            // gyro X raw -> full positive
    setLe16(r, 19, 8192);            // accel X raw -> ~+1g

    DeviceState s;
    ASSERT_TRUE(decodeReport(Parser::DUALSHOCK4, r.data(), r.size(), s, &st));
    EXPECT_TRUE(s.motionValid);
    EXPECT_GT(s.gyroX, 30000);
    EXPECT_GT(s.accelX, 7000);
    EXPECT_EQ(0, s.gyroY);
    EXPECT_EQ(0, s.accelY);
}

TEST(Decode, DualShock4NoImuWithoutCalibration) {
    std::vector<uint8_t> r(25, 0);
    r[0] = 0x01;
    setLe16(r, 13, 2000);
    DeviceState s;
    ParserState st; // psImu invalid by default
    ASSERT_TRUE(decodeReport(Parser::DUALSHOCK4, r.data(), r.size(), s, &st));
    EXPECT_FALSE(s.motionValid);
}

TEST(Decode, DualSenseDecodesCalibratedImu) {
    auto calib = psCalibReport();
    ParserState st;
    ASSERT_TRUE(parsePsCalibration(calib.data(), calib.size(), st.psImu));

    std::vector<uint8_t> r(28, 0);
    r[0] = 0x01;
    r[1] = r[2] = r[3] = r[4] = 128;
    setLe16(r, 16, 2000); // gyro X
    setLe16(r, 22, 8192); // accel X

    DeviceState s;
    ASSERT_TRUE(decodeReport(Parser::DUALSENSE, r.data(), r.size(), s, &st));
    EXPECT_TRUE(s.motionValid);
    EXPECT_GT(s.gyroX, 30000);
    EXPECT_GT(s.accelX, 7000);
}
