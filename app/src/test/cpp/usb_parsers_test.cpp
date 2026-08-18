// SPDX-License-Identifier: LGPL-3.0-or-later

#include "usb_parsers.h"

#include <gtest/gtest.h>

#include <cstdint>
#include <vector>

using gamepad::DeviceState;
using gamepad::XUSB_A;
using gamepad::XUSB_B;
using gamepad::XUSB_BACK;
using gamepad::XUSB_DPAD_DOWN;
using gamepad::XUSB_DPAD_LEFT;
using gamepad::XUSB_DPAD_RIGHT;
using gamepad::XUSB_DPAD_UP;
using gamepad::XUSB_GUIDE;
using gamepad::XUSB_LB;
using gamepad::XUSB_RB;
using gamepad::XUSB_START;
using gamepad::XUSB_THUMB_L;
using gamepad::XUSB_THUMB_R;
using gamepad::XUSB_X;
using gamepad::XUSB_Y;
using usbparsers::buildGipInitPacket;
using usbparsers::buildRumbleReport;
using usbparsers::buildSteamConfigPacket;
using usbparsers::ButtonOrder;
using usbparsers::checkWirelessEvent;
using usbparsers::decodeReport;
using usbparsers::InitKind;
using usbparsers::isVerifiedFastLane;
using usbparsers::modelExpectsFrameworkGamepad;
using usbparsers::parsePsCalibration;
using usbparsers::Parser;
using usbparsers::parserFrameworkRumbleUnreliable;
using usbparsers::parserHasImu;
using usbparsers::parserHasRumble;
using usbparsers::parserHasTouchpad;
using usbparsers::ParserState;
using usbparsers::ProbeOutcome;
using usbparsers::probePermitsClaim;
using usbparsers::PsImuCalib;
using usbparsers::SteamConfig;
using usbparsers::WirelessEvent;

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

TEST(FrameworkRumble, SwitchProProtocolIsUnreliable) {
    EXPECT_TRUE(parserFrameworkRumbleUnreliable(Parser::SWITCH_PRO_USB));
}

TEST(FrameworkRumble, OtherRumbleFamiliesAreTrusted) {
    EXPECT_FALSE(parserFrameworkRumbleUnreliable(Parser::XINPUT_360));
    EXPECT_FALSE(parserFrameworkRumbleUnreliable(Parser::XBOX_ONE_GIP));
    EXPECT_FALSE(parserFrameworkRumbleUnreliable(Parser::DUALSHOCK4));
    EXPECT_FALSE(parserFrameworkRumbleUnreliable(Parser::DUALSENSE));
    EXPECT_FALSE(parserFrameworkRumbleUnreliable(Parser::NONE));
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

TEST(ProbeRule, DecodedReportClaimsAtAnyTrustLevel) {
    EXPECT_TRUE(probePermitsClaim(ProbeOutcome::DECODED, true));
    EXPECT_TRUE(probePermitsClaim(ProbeOutcome::DECODED, false));
}

TEST(ProbeRule, SilenceClaimsOnlyVerifiedModels) {
    EXPECT_TRUE(probePermitsClaim(ProbeOutcome::SILENT, true));
    EXPECT_FALSE(probePermitsClaim(ProbeOutcome::SILENT, false));
}

TEST(ProbeRule, UndecodedTrafficNeverClaims) {
    EXPECT_FALSE(probePermitsClaim(ProbeOutcome::UNDECODED, true));
    EXPECT_FALSE(probePermitsClaim(ProbeOutcome::UNDECODED, false));
}

TEST(Decode, SwitchProAveragesImuSubframes) {
    // Raw accel X (byte 13) 300/600/900 across the three subframes averages to 600, and the axis
    // rotation lands it on wire accel Z: a single-subframe report of 600 must match, and the first
    // subframe alone (300) must not.
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
    EXPECT_EQ(sAvg.accelZ, sThree.accelZ);
    EXPECT_NE(sFirst.accelZ, sThree.accelZ);
}

TEST(Decode, SwitchProRotatesImuOntoDs4AxisConvention) {
    // Each raw IMU axis is excited alone so any cross-wiring is visible. Subframe layout: accel
    // x/y/z at 13/15/17, gyro x/y/z at 19/21/23. The Switch's pitch/yaw/roll land on raw gyro
    // Y/Z/X and map onto wire gyro X/Y/Z; pitch and roll are negated to match the DS4 sign
    // convention (verified on hardware: nose up aims up, steering tracks). Accel rotates to match.
    auto decodeAxis = [](size_t off) {
        auto r = switchReport(25);
        setLe16(r, off, 4000);
        DeviceState s;
        ParserState p;
        EXPECT_TRUE(decodeReport(Parser::SWITCH_PRO_USB, r.data(), r.size(), s, &p));
        return s;
    };

    DeviceState pitch = decodeAxis(21); // raw gyro Y -> wire gyro X (pitch, negated)
    EXPECT_LT(pitch.gyroX, 0);
    EXPECT_EQ(0, pitch.gyroY);
    EXPECT_EQ(0, pitch.gyroZ);

    DeviceState yaw = decodeAxis(23); // raw gyro Z -> wire gyro Y (yaw, upright)
    EXPECT_EQ(0, yaw.gyroX);
    EXPECT_GT(yaw.gyroY, 0);
    EXPECT_EQ(0, yaw.gyroZ);

    DeviceState roll = decodeAxis(19); // raw gyro X -> wire gyro Z (roll, negated)
    EXPECT_EQ(0, roll.gyroX);
    EXPECT_EQ(0, roll.gyroY);
    EXPECT_LT(roll.gyroZ, 0);

    DeviceState accelRawX = decodeAxis(13); // raw accel X -> wire accel Z
    EXPECT_EQ(0, accelRawX.accelX);
    EXPECT_EQ(0, accelRawX.accelY);
    EXPECT_NE(0, accelRawX.accelZ);

    DeviceState accelRawY = decodeAxis(15); // raw accel Y -> wire accel X
    EXPECT_NE(0, accelRawY.accelX);
    EXPECT_EQ(0, accelRawY.accelY);
    EXPECT_EQ(0, accelRawY.accelZ);

    DeviceState accelRawZ = decodeAxis(17); // raw accel Z -> wire accel Y
    EXPECT_EQ(0, accelRawZ.accelX);
    EXPECT_NE(0, accelRawZ.accelY);
    EXPECT_EQ(0, accelRawZ.accelZ);
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

TEST(Decode, Xbox360WiredGuideButtonSetsGuideBit) {
    std::vector<uint8_t> r(14, 0);
    r[0] = 0x00;
    r[3] = 0x04; // Guide (same bit the Amazon Luna Controller's center button reports)
    DeviceState s;
    ParserState p;
    ASSERT_TRUE(decodeReport(Parser::XINPUT_360, r.data(), r.size(), s, &p));
    EXPECT_TRUE(s.wButtons & XUSB_GUIDE);
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

TEST(GipInit, PowerOnSequenceIsThreePackets) {
    uint8_t out[16];
    EXPECT_EQ(5u, buildGipInitPacket(InitKind::XBOX_ONE_POWERON, 0, 0, out, sizeof(out)));
    EXPECT_EQ(0x05, out[0]);
    EXPECT_EQ(0x01, out[3]); // power-on
    EXPECT_EQ(7u, buildGipInitPacket(InitKind::XBOX_ONE_POWERON, 1, 1, out, sizeof(out)));
    EXPECT_EQ(0x0A, out[0]); // led-on
    EXPECT_EQ(6u, buildGipInitPacket(InitKind::XBOX_ONE_POWERON, 2, 2, out, sizeof(out)));
    EXPECT_EQ(0x06, out[0]); // auth-done
    EXPECT_EQ(0u, buildGipInitPacket(InitKind::XBOX_ONE_POWERON, 3, 3, out, sizeof(out)));
}

TEST(GipInit, SSequenceInsertsSetModeWithSequenceByte) {
    uint8_t out[16];
    EXPECT_EQ(5u, buildGipInitPacket(InitKind::XBOX_ONE_S, 0, 0, out, sizeof(out))); // power-on
    EXPECT_EQ(5u, buildGipInitPacket(InitKind::XBOX_ONE_S, 1, 1, out, sizeof(out))); // S set-mode
    const uint8_t sInit[5] = {0x05, 0x20, 0x01, 0x0F, 0x06}; // byte 2 = seq 1
    for (int i = 0; i < 5; i++) EXPECT_EQ(sInit[i], out[i]) << "byte " << i;
    EXPECT_EQ(6u, buildGipInitPacket(InitKind::XBOX_ONE_S, 3, 3, out, sizeof(out))); // auth-done
    EXPECT_EQ(0u, buildGipInitPacket(InitKind::XBOX_ONE_S, 4, 4, out, sizeof(out)));
}

TEST(GipInit, NonXboxInitReturnsZero) {
    uint8_t out[16];
    EXPECT_EQ(0u, buildGipInitPacket(InitKind::NONE, 0, 0, out, sizeof(out)));
    EXPECT_EQ(0u, buildGipInitPacket(InitKind::SWITCH_PRO_HANDSHAKE, 0, 0, out, sizeof(out)));
}

TEST(GipInit, TooSmallBufferReturnsZero) {
    uint8_t out[4];
    EXPECT_EQ(0u, buildGipInitPacket(InitKind::XBOX_ONE_POWERON, 0, 0, out, sizeof(out)));
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

// ── DS4 / DualSense touchpad decode ─────────────────────────────────────────

namespace {

void writeTouchPoint(uint8_t* p, bool active, uint8_t id, uint16_t x, uint16_t y) {
    p[0] = (uint8_t)((active ? 0x00 : 0x80) | (id & 0x7F));
    p[1] = (uint8_t)(x & 0xFF);
    p[2] = (uint8_t)(((x >> 8) & 0x0F) | ((y & 0x0F) << 4));
    p[3] = (uint8_t)(y >> 4);
}

std::vector<uint8_t> ds4Report() {
    std::vector<uint8_t> r(64, 0);
    r[0] = 0x01;
    r[1] = r[2] = r[3] = r[4] = 128; // centered sticks
    // No bundled touch frame by default (r[33] = 0) and both points inactive.
    writeTouchPoint(r.data() + 35, false, 0, 0, 0);
    writeTouchPoint(r.data() + 39, false, 0, 0, 0);
    return r;
}

std::vector<uint8_t> dualSenseReport() {
    std::vector<uint8_t> r(64, 0);
    r[0] = 0x01;
    r[1] = r[2] = r[3] = r[4] = 128;
    writeTouchPoint(r.data() + 33, false, 0, 0, 0);
    writeTouchPoint(r.data() + 37, false, 0, 0, 0);
    return r;
}

} // namespace

TEST(TouchDecode, Ds4CornersNormalizeToFullRangeInt16) {
    auto r = ds4Report();
    r[33] = 1;
    writeTouchPoint(r.data() + 35, true, 3, 0, 0);
    writeTouchPoint(r.data() + 39, true, 4, 1919, 941);
    DeviceState s{};
    ASSERT_TRUE(decodeReport(Parser::DUALSHOCK4, r.data(), r.size(), s, nullptr));
    ASSERT_TRUE(s.touchValid);
    EXPECT_TRUE(s.touch0Active);
    EXPECT_EQ(3, s.touch0Id);
    EXPECT_EQ(-32768, s.touch0X);
    EXPECT_EQ(-32768, s.touch0Y);
    EXPECT_TRUE(s.touch1Active);
    EXPECT_EQ(4, s.touch1Id);
    EXPECT_EQ(32767, s.touch1X);
    EXPECT_EQ(32767, s.touch1Y);
    EXPECT_FALSE(s.touchClick);
}

TEST(TouchDecode, Ds4ClickRidesButtonByteSeven) {
    auto r = ds4Report();
    r[33] = 1;
    writeTouchPoint(r.data() + 35, true, 1, 100, 100);
    r[7] |= 0x02;
    DeviceState s{};
    ASSERT_TRUE(decodeReport(Parser::DUALSHOCK4, r.data(), r.size(), s, nullptr));
    ASSERT_TRUE(s.touchValid);
    EXPECT_TRUE(s.touchClick);
}

TEST(TouchDecode, Ds4ZeroFramesMeansNoUpdateNotALift) {
    auto r = ds4Report();
    DeviceState s{};
    ASSERT_TRUE(decodeReport(Parser::DUALSHOCK4, r.data(), r.size(), s, nullptr));
    EXPECT_FALSE(s.touchValid);
}

TEST(TouchDecode, Ds4UsesTheNewestBundledFrame) {
    auto r = ds4Report();
    r[33] = 2;
    // Frame 0 (older) at 34, frame 1 (newest) at 43.
    writeTouchPoint(r.data() + 35, true, 1, 100, 100);
    writeTouchPoint(r.data() + 39, false, 0, 0, 0);
    writeTouchPoint(r.data() + 44, true, 2, 1919, 0);
    writeTouchPoint(r.data() + 48, false, 0, 0, 0);
    DeviceState s{};
    ASSERT_TRUE(decodeReport(Parser::DUALSHOCK4, r.data(), r.size(), s, nullptr));
    ASSERT_TRUE(s.touchValid);
    EXPECT_EQ(2, s.touch0Id);
    EXPECT_EQ(32767, s.touch0X);
}

TEST(TouchDecode, Ds4ShortReportStillDecodesGamepadWithoutTouch) {
    auto r = ds4Report();
    r[33] = 1;
    DeviceState s{};
    ASSERT_TRUE(decodeReport(Parser::DUALSHOCK4, r.data(), 20, s, nullptr));
    EXPECT_FALSE(s.touchValid);
}

TEST(TouchDecode, Ds4InactivePointZeroesCoordinates) {
    auto r = ds4Report();
    r[33] = 1;
    writeTouchPoint(r.data() + 35, false, 5, 1919, 941);
    DeviceState s{};
    ASSERT_TRUE(decodeReport(Parser::DUALSHOCK4, r.data(), r.size(), s, nullptr));
    ASSERT_TRUE(s.touchValid);
    EXPECT_FALSE(s.touch0Active);
    EXPECT_EQ(5, s.touch0Id);
    EXPECT_EQ(0, s.touch0X);
    EXPECT_EQ(0, s.touch0Y);
}

TEST(TouchDecode, DualSensePointsAndClickDecode) {
    auto r = dualSenseReport();
    writeTouchPoint(r.data() + 33, true, 7, 0, 1079);
    writeTouchPoint(r.data() + 37, true, 8, 1919, 0);
    r[10] |= 0x02;
    DeviceState s{};
    ASSERT_TRUE(decodeReport(Parser::DUALSENSE, r.data(), r.size(), s, nullptr));
    ASSERT_TRUE(s.touchValid);
    EXPECT_TRUE(s.touch0Active);
    EXPECT_EQ(7, s.touch0Id);
    EXPECT_EQ(-32768, s.touch0X);
    EXPECT_EQ(32767, s.touch0Y);
    EXPECT_TRUE(s.touch1Active);
    EXPECT_EQ(8, s.touch1Id);
    EXPECT_EQ(32767, s.touch1X);
    EXPECT_EQ(-32768, s.touch1Y);
    EXPECT_TRUE(s.touchClick);
}

TEST(TouchDecode, DualSenseIdleSurfaceIsAValidAllLiftedUpdate) {
    auto r = dualSenseReport();
    DeviceState s{};
    ASSERT_TRUE(decodeReport(Parser::DUALSENSE, r.data(), r.size(), s, nullptr));
    ASSERT_TRUE(s.touchValid);
    EXPECT_FALSE(s.touch0Active);
    EXPECT_FALSE(s.touch1Active);
    EXPECT_FALSE(s.touchClick);
}

TEST(TouchpadCapability, PlayStationParsersHaveTouchpads) {
    EXPECT_TRUE(usbparsers::parserHasTouchpad(Parser::DUALSHOCK4));
    EXPECT_TRUE(usbparsers::parserHasTouchpad(Parser::DUALSENSE));
    EXPECT_FALSE(usbparsers::parserHasTouchpad(Parser::XINPUT_360));
    EXPECT_FALSE(usbparsers::parserHasTouchpad(Parser::XBOX_ONE_GIP));
    EXPECT_FALSE(usbparsers::parserHasTouchpad(Parser::SWITCH_PRO_USB));
    EXPECT_FALSE(usbparsers::parserHasTouchpad(Parser::GENERIC_HID_GAMEPAD));
}

TEST(ClassifyDevice, KnownDeviceWinsOverDescriptorTriple) {
    auto c = usbparsers::classifyDevice(0x045E, 0x028E, 0x00, 0x00, 0x00);
    EXPECT_EQ(c.parser, Parser::XINPUT_360);
    EXPECT_NE(c.name, nullptr);
}

TEST(ClassifyDevice, WiredXInputInterfaceClassifiesWithoutTableEntry) {
    auto c = usbparsers::classifyDevice(0x1234, 0x5678, 0xFF, 0x5D, 0x01);
    EXPECT_EQ(c.parser, Parser::XINPUT_360);
    EXPECT_EQ(c.init, InitKind::NONE);
    EXPECT_EQ(c.name, nullptr);
}

TEST(ClassifyDevice, EightBitDoDongleTripleClassifiesAsXInput) {
    // 0xFFFF stands in for the unlisted 2.4g dongle PID: only the descriptor can classify it.
    auto c = usbparsers::classifyDevice(0x2DC8, 0xFFFF, 0xFF, 0x5D, 0x01);
    EXPECT_EQ(c.parser, Parser::XINPUT_360);
    EXPECT_EQ(c.init, InitKind::NONE);
}

TEST(ClassifyDevice, GipInterfaceClassifiesAsXboxOneWithPowerOn) {
    auto c = usbparsers::classifyDevice(0x1234, 0x5678, 0xFF, 0x47, 0xD0);
    EXPECT_EQ(c.parser, Parser::XBOX_ONE_GIP);
    EXPECT_EQ(c.init, InitKind::XBOX_ONE_POWERON);
}

TEST(ClassifyDevice, HidInterfaceClassifiesAsGenericHid) {
    auto c = usbparsers::classifyDevice(0x1234, 0x5678, 0x03, 0x00, 0x00);
    EXPECT_EQ(c.parser, Parser::GENERIC_HID_GAMEPAD);
    EXPECT_EQ(c.init, InitKind::NONE);
}

TEST(ClassifyDevice, UnknownVendorInterfaceFallsBackToGeneric) {
    auto c = usbparsers::classifyDevice(0x1234, 0x5678, 0xFF, 0x99, 0x99);
    EXPECT_EQ(c.parser, Parser::GENERIC_HID_GAMEPAD);
}

TEST(ClassifyDevice, PdpFaceoffIsGenericHidWithSwitchOrder) {
    auto c = usbparsers::classifyDevice(0x0E6F, 0x0180, 0x03, 0x00, 0x00);
    EXPECT_EQ(c.parser, Parser::GENERIC_HID_GAMEPAD);
    EXPECT_EQ(c.init, InitKind::NONE);
    EXPECT_EQ(c.order, ButtonOrder::SWITCH);
    EXPECT_STREQ("PDP Faceoff Wired Pro Controller (Switch)", c.name);
}

TEST(ClassifyDevice, EveryPdpWiredSwitchPadCarriesSwitchOrder) {
    const uint16_t pids[] = {0x0180, 0x0181, 0x0184, 0x0185, 0x0187};
    for (uint16_t pid : pids) {
        auto c = usbparsers::classifyDevice(0x0E6F, pid, 0x03, 0x00, 0x00);
        EXPECT_EQ(c.parser, Parser::GENERIC_HID_GAMEPAD) << pid;
        EXPECT_EQ(c.order, ButtonOrder::SWITCH) << pid;
        EXPECT_NE(c.name, nullptr) << pid;
    }
}

TEST(ClassifyDevice, PdpAfterglowWirelessIsNotSwitchOrder) {
    // 0e6f:0186 speaks the Switch Pro protocol and its USB port is charge-only (SDL's
    // controller list); it must not be remapped like the wired Switch-order pads.
    auto c = usbparsers::classifyDevice(0x0E6F, 0x0186, 0x03, 0x00, 0x00);
    EXPECT_EQ(c.parser, Parser::GENERIC_HID_GAMEPAD);
    EXPECT_EQ(c.order, ButtonOrder::WESTERN);
}

TEST(ClassifyDevice, PdpXboxPadsKeepWesternOrder) {
    auto x360 = usbparsers::classifyDevice(0x0E6F, 0x0501, 0x00, 0x00, 0x00);
    EXPECT_EQ(x360.parser, Parser::XINPUT_360);
    EXPECT_EQ(x360.order, ButtonOrder::WESTERN);

    auto gip = usbparsers::classifyDevice(0x0E6F, 0x013B, 0x00, 0x00, 0x00);
    EXPECT_EQ(gip.parser, Parser::XBOX_ONE_GIP);
    EXPECT_EQ(gip.order, ButtonOrder::WESTERN);
}

TEST(ClassifyDevice, TableAndDescriptorFallbacksAreWesternOrder) {
    EXPECT_EQ(usbparsers::classifyDevice(0x045E, 0x028E, 0x00, 0x00, 0x00).order,
              ButtonOrder::WESTERN);
    EXPECT_EQ(usbparsers::classifyDevice(0x1234, 0x5678, 0x03, 0x00, 0x00).order,
              ButtonOrder::WESTERN);
    EXPECT_EQ(usbparsers::classifyDevice(0x1234, 0x5678, 0xFF, 0x5D, 0x01).order,
              ButtonOrder::WESTERN);
}

TEST(ClassifyDevice, PdpSwitchPadsAreNotVerifiedFastLane) {
    EXPECT_FALSE(isVerifiedFastLane(0x0E6F, 0x0180));
    EXPECT_FALSE(isVerifiedFastLane(0x0E6F, 0x0185));
}

TEST(GenericHidDecode, SwitchOrderLayoutFlowsThroughDecodeReport) {
    const uint8_t desc[] = {0x05, 0x01, 0x09, 0x05, 0xA1, 0x01, 0x15, 0x00, 0x25,
                            0x01, 0x75, 0x01, 0x95, 0x0E, 0x05, 0x09, 0x19, 0x01,
                            0x29, 0x0E, 0x81, 0x02, 0x95, 0x02, 0x81, 0x01, 0xC0};
    ParserState st;
    ASSERT_TRUE(usbhid::parseReportDescriptor(desc, sizeof(desc), st.hidLayout));
    st.hidLayout.switchOrderButtons = true;

    std::vector<uint8_t> physicalA = {0x04, 0x00};
    DeviceState s;
    ASSERT_TRUE(
        decodeReport(Parser::GENERIC_HID_GAMEPAD, physicalA.data(), physicalA.size(), s, &st));
    EXPECT_EQ(XUSB_B, s.wButtons);

    std::vector<uint8_t> zl = {0x40, 0x00};
    DeviceState s2;
    ASSERT_TRUE(decodeReport(Parser::GENERIC_HID_GAMEPAD, zl.data(), zl.size(), s2, &st));
    EXPECT_EQ(0, s2.wButtons);
    EXPECT_EQ(255, s2.bLT);
}

namespace {

constexpr uint32_t kBtnRightBumper = 0x000004;
constexpr uint32_t kBtnLeftBumper = 0x000008;
constexpr uint32_t kBtnNorth = 0x000010;
constexpr uint32_t kBtnEast = 0x000020;
constexpr uint32_t kBtnWest = 0x000040;
constexpr uint32_t kBtnSouth = 0x000080;
constexpr uint32_t kBtnDpadUp = 0x000100;
constexpr uint32_t kBtnDpadRight = 0x000200;
constexpr uint32_t kBtnMenu = 0x001000;
constexpr uint32_t kBtnGuide = 0x002000;
constexpr uint32_t kBtnEscape = 0x004000;
constexpr uint32_t kBtnLeftPadClicked = 0x020000;
constexpr uint32_t kBtnRightPadClicked = 0x040000;
constexpr uint32_t kBtnLeftPadFinger = 0x080000;
constexpr uint32_t kBtnRightPadFinger = 0x100000;
constexpr uint32_t kBtnStickButton = 0x400000;
constexpr uint32_t kBtnLeftPadAndStick = 0x800000;

std::vector<uint8_t> steamState(uint32_t buttons = 0) {
    std::vector<uint8_t> r(64, 0);
    r[0] = 0x01; // report version, u16 LE
    r[1] = 0x00;
    r[2] = 0x01; // ID_CONTROLLER_STATE
    r[3] = 44;   // payload length
    r[8] = (uint8_t)(buttons & 0xFF);
    r[9] = (uint8_t)((buttons >> 8) & 0xFF);
    r[10] = (uint8_t)((buttons >> 16) & 0xFF);
    return r;
}

bool decodeSteam(const std::vector<uint8_t>& buf, DeviceState& s, ParserState& st) {
    return decodeReport(Parser::STEAM_CONTROLLER, buf.data(), buf.size(), s, &st);
}

} // namespace

TEST(SteamClassify, WiredAndDongleResolveToQuietInit) {
    auto wired = usbparsers::classifyDevice(0x28DE, 0x1102, 0x03, 0x00, 0x00);
    EXPECT_EQ(Parser::STEAM_CONTROLLER, wired.parser);
    EXPECT_EQ(InitKind::STEAM_QUIET, wired.init);
    EXPECT_STREQ("Valve Steam Controller", wired.name);

    auto dongle = usbparsers::classifyDevice(0x28DE, 0x1142, 0x03, 0x00, 0x00);
    EXPECT_EQ(Parser::STEAM_CONTROLLER, dongle.parser);
    EXPECT_EQ(InitKind::STEAM_QUIET, dongle.init);
}

TEST(SteamClassify, IsNotAVerifiedFastLaneModel) {
    EXPECT_FALSE(isVerifiedFastLane(0x28DE, 0x1102));
    EXPECT_FALSE(isVerifiedFastLane(0x28DE, 0x1142));
}

TEST(SteamClassify, AdvertisesImuButNeitherRumbleNorTouchpad) {
    EXPECT_TRUE(parserHasImu(Parser::STEAM_CONTROLLER));
    EXPECT_FALSE(parserHasRumble(Parser::STEAM_CONTROLLER));
    EXPECT_FALSE(parserHasTouchpad(Parser::STEAM_CONTROLLER));
    EXPECT_FALSE(parserFrameworkRumbleUnreliable(Parser::STEAM_CONTROLLER));

    uint8_t buf[64];
    EXPECT_EQ(0u, buildRumbleReport(Parser::STEAM_CONTROLLER, 0xFFFF, 0xFFFF, 0, buf, sizeof(buf)));
}

TEST(SteamDecode, RejectsShortWrongVersionAndWrongType) {
    ParserState st;
    DeviceState s;
    auto shortPacket = steamState();
    shortPacket.resize(47);
    EXPECT_FALSE(decodeSteam(shortPacket, s, st));

    auto badVersion = steamState();
    badVersion[0] = 0x02;
    EXPECT_FALSE(decodeSteam(badVersion, s, st));

    // The dongle interleaves wireless connect/disconnect events onto the same endpoint.
    auto wirelessEvent = steamState();
    wirelessEvent[2] = 0x03;
    EXPECT_FALSE(decodeSteam(wirelessEvent, s, st));
}

TEST(SteamDecode, FaceAndShoulderButtonsMapByPosition) {
    ParserState st;
    DeviceState s;
    ASSERT_TRUE(decodeSteam(steamState(kBtnSouth | kBtnEast | kBtnWest | kBtnNorth), s, st));
    EXPECT_TRUE(s.wButtons & XUSB_A);
    EXPECT_TRUE(s.wButtons & XUSB_B);
    EXPECT_TRUE(s.wButtons & XUSB_X);
    EXPECT_TRUE(s.wButtons & XUSB_Y);

    DeviceState shoulders;
    ASSERT_TRUE(decodeSteam(steamState(kBtnLeftBumper | kBtnRightBumper), shoulders, st));
    EXPECT_TRUE(shoulders.wButtons & XUSB_LB);
    EXPECT_TRUE(shoulders.wButtons & XUSB_RB);
}

TEST(SteamDecode, MenuEscapeAndSteamMapToBackStartGuide) {
    ParserState st;
    DeviceState s;
    ASSERT_TRUE(decodeSteam(steamState(kBtnMenu | kBtnEscape | kBtnGuide), s, st));
    EXPECT_TRUE(s.wButtons & XUSB_BACK);
    EXPECT_TRUE(s.wButtons & XUSB_START);
    EXPECT_TRUE(s.wButtons & XUSB_GUIDE);
}

TEST(SteamDecode, DpadBitsMapDirectly) {
    ParserState st;
    DeviceState s;
    ASSERT_TRUE(decodeSteam(steamState(kBtnDpadUp | kBtnDpadRight), s, st));
    EXPECT_TRUE(s.wButtons & XUSB_DPAD_UP);
    EXPECT_TRUE(s.wButtons & XUSB_DPAD_RIGHT);
    EXPECT_FALSE(s.wButtons & XUSB_DPAD_DOWN);
    EXPECT_FALSE(s.wButtons & XUSB_DPAD_LEFT);
}

TEST(SteamDecode, TriggersScaleAndSaturateBeforeTheRawRail) {
    ParserState st;
    DeviceState s;
    auto r = steamState();
    r[11] = 0;
    r[12] = 100;
    ASSERT_TRUE(decodeSteam(r, s, st));
    EXPECT_EQ(0, s.bLT);
    EXPECT_EQ(126, s.bRT);

    // Valve's full scale is 26000 of a possible 32895, so the throw tops out before the raw rail.
    r[11] = 202;
    r[12] = 255;
    ASSERT_TRUE(decodeSteam(r, s, st));
    EXPECT_EQ(255, s.bLT);
    EXPECT_EQ(255, s.bRT);
}

TEST(SteamDecode, LeftAxesAreTheStickWhenNoFingerIsOnThePad) {
    ParserState st;
    DeviceState s;
    auto r = steamState();
    setLe16(r, 16, -8000);
    setLe16(r, 18, 12000);
    ASSERT_TRUE(decodeSteam(r, s, st));
    EXPECT_EQ(-8000, s.sLX);
    EXPECT_EQ(12000, s.sLY);
}

TEST(SteamDecode, StickHoldsItsValueAcrossAnInterleavedPadFrame) {
    ParserState st;
    DeviceState s;
    auto stickFrame = steamState();
    setLe16(stickFrame, 16, 5000);
    setLe16(stickFrame, 18, -6000);
    ASSERT_TRUE(decodeSteam(stickFrame, s, st));

    auto padFrame = steamState(kBtnLeftPadFinger | kBtnLeftPadAndStick);
    setLe16(padFrame, 16, 30000);
    setLe16(padFrame, 18, 30000);
    DeviceState next;
    ASSERT_TRUE(decodeSteam(padFrame, next, st));
    EXPECT_EQ(5000, next.sLX);
    EXPECT_EQ(-6000, next.sLY);
}

TEST(SteamDecode, StickCentresWhenThePadTakesOverWithoutInterleaving) {
    ParserState st;
    DeviceState s;
    auto stickFrame = steamState();
    setLe16(stickFrame, 16, 5000);
    ASSERT_TRUE(decodeSteam(stickFrame, s, st));

    auto padFrame = steamState(kBtnLeftPadFinger);
    setLe16(padFrame, 16, 30000);
    DeviceState next;
    ASSERT_TRUE(decodeSteam(padFrame, next, st));
    EXPECT_EQ(0, next.sLX);
    EXPECT_EQ(0, next.sLY);
}

TEST(SteamDecode, LeftPadClickIsAStickClickWhileThePadIsIdle) {
    ParserState st;
    DeviceState s;
    ASSERT_TRUE(decodeSteam(steamState(kBtnLeftPadClicked), s, st));
    EXPECT_TRUE(s.wButtons & XUSB_THUMB_L);

    // With a finger actually on the pad the click belongs to the pad, not the stick.
    DeviceState onPad;
    ASSERT_TRUE(decodeSteam(steamState(kBtnLeftPadClicked | kBtnLeftPadFinger), onPad, st));
    EXPECT_FALSE(onPad.wButtons & XUSB_THUMB_L);
}

TEST(SteamDecode, StickAndRightPadClicksMapToThumbButtons) {
    ParserState st;
    DeviceState s;
    ASSERT_TRUE(decodeSteam(steamState(kBtnStickButton | kBtnRightPadClicked), s, st));
    EXPECT_TRUE(s.wButtons & XUSB_THUMB_L);
    EXPECT_TRUE(s.wButtons & XUSB_THUMB_R);
}

TEST(SteamDecode, RightPadDrivesTheRightStickThroughTheShellRotation) {
    ParserState st;
    DeviceState s;
    auto r = steamState(kBtnRightPadFinger);
    setLe16(r, 20, 10000);
    setLe16(r, 22, 0);
    ASSERT_TRUE(decodeSteam(r, s, st));
    EXPECT_EQ(9659, s.sRX);
    EXPECT_EQ(2588, s.sRY);
}

TEST(SteamDecode, RightStickRecentresWhenTheFingerLifts) {
    ParserState st;
    DeviceState s;
    auto held = steamState(kBtnRightPadFinger);
    setLe16(held, 20, 20000);
    setLe16(held, 22, 20000);
    ASSERT_TRUE(decodeSteam(held, s, st));
    ASSERT_NE(0, s.sRX);

    // Pad coordinates are meaningless once the finger lifts, so the stick must not stay deflected.
    auto lifted = steamState();
    setLe16(lifted, 20, 20000);
    setLe16(lifted, 22, 20000);
    DeviceState next;
    ASSERT_TRUE(decodeSteam(lifted, next, st));
    EXPECT_EQ(0, next.sRX);
    EXPECT_EQ(0, next.sRY);
}

TEST(SteamDecode, ImuRotatesOntoTheWireAxisOrderAndScale) {
    ParserState st;
    DeviceState s;
    auto r = steamState();
    setLe16(r, 28, 1000); // accel x
    setLe16(r, 30, 2000); // accel y
    setLe16(r, 32, 3000); // accel z
    setLe16(r, 34, 4000); // gyro x
    setLe16(r, 36, 5000); // gyro y
    setLe16(r, 38, 6000); // gyro z
    ASSERT_TRUE(decodeSteam(r, s, st));
    EXPECT_TRUE(s.motionValid);
    EXPECT_EQ(3999, s.gyroX);
    EXPECT_EQ(5999, s.gyroY);
    EXPECT_EQ(4999, s.gyroZ);
    EXPECT_EQ(499, s.accelX);
    EXPECT_EQ(1499, s.accelY);
    EXPECT_EQ(-999, s.accelZ);
}

TEST(SteamDecode, SilentImuBlockDoesNotPublishMotion) {
    ParserState st;
    DeviceState s;
    ASSERT_TRUE(decodeSteam(steamState(kBtnSouth), s, st));
    EXPECT_FALSE(s.motionValid);
}

TEST(SteamConfigPackets, QuietSequenceClearsMappingsThenWritesSettings) {
    uint8_t buf[16];
    ASSERT_EQ(2u, buildSteamConfigPacket(SteamConfig::QUIET, 0, buf, sizeof(buf)));
    EXPECT_EQ(0x81, buf[0]);
    EXPECT_EQ(0x00, buf[1]);

    ASSERT_EQ(11u, buildSteamConfigPacket(SteamConfig::QUIET, 1, buf, sizeof(buf)));
    EXPECT_EQ(0x87, buf[0]);
    EXPECT_EQ(0x09, buf[1]);
    EXPECT_EQ(0x07, buf[2]); // left trackpad mode
    EXPECT_EQ(0x07, buf[3]); // = none
    EXPECT_EQ(0x08, buf[5]); // right trackpad mode
    EXPECT_EQ(0x07, buf[6]); // = none
    EXPECT_EQ(0x30, buf[8]); // imu mode
    EXPECT_EQ(0x18, buf[9]); // = raw accel | raw gyro

    EXPECT_EQ(0u, buildSteamConfigPacket(SteamConfig::QUIET, 2, buf, sizeof(buf)));
}

TEST(SteamConfigPackets, RestoreSequencePutsTheDeviceBack) {
    uint8_t buf[16];
    ASSERT_EQ(2u, buildSteamConfigPacket(SteamConfig::RESTORE, 0, buf, sizeof(buf)));
    EXPECT_EQ(0x85, buf[0]);
    ASSERT_EQ(2u, buildSteamConfigPacket(SteamConfig::RESTORE, 1, buf, sizeof(buf)));
    EXPECT_EQ(0x8E, buf[0]);

    // Loading the defaults leaves the right pad silent, so mouse mode is restored by name.
    ASSERT_EQ(5u, buildSteamConfigPacket(SteamConfig::RESTORE, 2, buf, sizeof(buf)));
    EXPECT_EQ(0x87, buf[0]);
    EXPECT_EQ(0x03, buf[1]);
    EXPECT_EQ(0x08, buf[2]); // right trackpad mode
    EXPECT_EQ(0x00, buf[3]); // = absolute mouse
    EXPECT_EQ(0x00, buf[4]);

    EXPECT_EQ(0u, buildSteamConfigPacket(SteamConfig::RESTORE, 3, buf, sizeof(buf)));
}

TEST(SteamConfigPackets, RefusesToOverrunACallerBuffer) {
    uint8_t small[4];
    EXPECT_EQ(0u, buildSteamConfigPacket(SteamConfig::QUIET, 1, small, sizeof(small)));
    EXPECT_EQ(0u, buildSteamConfigPacket(SteamConfig::QUIET, -1, small, sizeof(small)));
}

namespace {

// Dongle wireless event framing per hid-steam: header {version u16, type, payload length},
// payload byte 0x01 = disconnected, 0x02 = connected.
std::vector<uint8_t> steamWirelessEvent(uint8_t payload) {
    std::vector<uint8_t> r(64, 0);
    r[0] = 0x01;
    r[1] = 0x00;
    r[2] = 0x03; // ID_CONTROLLER_WIRELESS
    r[3] = 0x01; // payload length
    r[4] = payload;
    return r;
}

} // namespace

TEST(SteamWireless, ConnectAndDisconnectEventsClassify) {
    auto disc = steamWirelessEvent(0x01);
    EXPECT_EQ(WirelessEvent::DISCONNECT,
              checkWirelessEvent(Parser::STEAM_CONTROLLER, disc.data(), disc.size()));

    auto conn = steamWirelessEvent(0x02);
    EXPECT_EQ(WirelessEvent::CONNECT,
              checkWirelessEvent(Parser::STEAM_CONTROLLER, conn.data(), conn.size()));
}

// The issue and the fix as a pair: a wireless event never decodes as input (so on its own the last
// published state would stay latched), and the classifier is what routes it to the host instead.
TEST(SteamWireless, WirelessEventNeverDecodesAsInputButStillClassifies) {
    ParserState st;
    DeviceState s;
    auto disc = steamWirelessEvent(0x01);
    EXPECT_FALSE(decodeReport(Parser::STEAM_CONTROLLER, disc.data(), disc.size(), s, &st));
    EXPECT_NE(WirelessEvent::NONE,
              checkWirelessEvent(Parser::STEAM_CONTROLLER, disc.data(), disc.size()));
}

TEST(SteamWireless, InputStateIsNotAWirelessEvent) {
    ParserState st;
    DeviceState s;
    auto state = steamState(kBtnSouth);
    EXPECT_EQ(WirelessEvent::NONE,
              checkWirelessEvent(Parser::STEAM_CONTROLLER, state.data(), state.size()));
    EXPECT_TRUE(decodeReport(Parser::STEAM_CONTROLLER, state.data(), state.size(), s, &st));
}

TEST(SteamWireless, RejectsMalformedEvents) {
    auto shortEvent = steamWirelessEvent(0x02);
    shortEvent.resize(4);
    EXPECT_EQ(WirelessEvent::NONE,
              checkWirelessEvent(Parser::STEAM_CONTROLLER, shortEvent.data(), shortEvent.size()));

    auto badVersion = steamWirelessEvent(0x02);
    badVersion[0] = 0x02;
    EXPECT_EQ(WirelessEvent::NONE,
              checkWirelessEvent(Parser::STEAM_CONTROLLER, badVersion.data(), badVersion.size()));

    auto badPayloadLen = steamWirelessEvent(0x02);
    badPayloadLen[3] = 0x02;
    EXPECT_EQ(WirelessEvent::NONE, checkWirelessEvent(Parser::STEAM_CONTROLLER,
                                                      badPayloadLen.data(), badPayloadLen.size()));

    auto unknownPayload = steamWirelessEvent(0x03);
    EXPECT_EQ(
        WirelessEvent::NONE,
        checkWirelessEvent(Parser::STEAM_CONTROLLER, unknownPayload.data(), unknownPayload.size()));

    // ID_CONTROLLER_STATUS (battery) shares the endpoint but is not a connect event.
    auto battery = steamWirelessEvent(0x02);
    battery[2] = 0x04;
    EXPECT_EQ(WirelessEvent::NONE,
              checkWirelessEvent(Parser::STEAM_CONTROLLER, battery.data(), battery.size()));
}

TEST(SteamWireless, OtherParsersNeverSeeWirelessEvents) {
    auto conn = steamWirelessEvent(0x02);
    EXPECT_EQ(WirelessEvent::NONE,
              checkWirelessEvent(Parser::XINPUT_360, conn.data(), conn.size()));
    EXPECT_EQ(WirelessEvent::NONE, checkWirelessEvent(Parser::DUALSENSE, conn.data(), conn.size()));
    EXPECT_EQ(WirelessEvent::NONE, checkWirelessEvent(Parser::NONE, conn.data(), conn.size()));
}

// A released Steam Controller settles as a keyboard/mouse, never a framework gamepad; every other
// model (and anything unknown) keeps the wait-for-re-enumeration contract.
TEST(SteamClassify, OnlySteamModelsSettleWithoutAFrameworkGamepad) {
    EXPECT_FALSE(modelExpectsFrameworkGamepad(0x28DE, 0x1102));
    EXPECT_FALSE(modelExpectsFrameworkGamepad(0x28DE, 0x1142));
    EXPECT_TRUE(modelExpectsFrameworkGamepad(0x045E, 0x028E));
    EXPECT_TRUE(modelExpectsFrameworkGamepad(0x054C, 0x05C4));
    EXPECT_TRUE(modelExpectsFrameworkGamepad(0x1234, 0x5678));
}
