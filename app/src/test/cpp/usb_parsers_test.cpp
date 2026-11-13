// SPDX-License-Identifier: LGPL-3.0-or-later

#include "usb_parsers.h"
#include "wire_encoders.h"

#include <gtest/gtest.h>

#include <cstdint>
#include <cstring>
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

void setLe32(std::vector<uint8_t>& b, size_t off, uint32_t v) {
    for (size_t i = 0; i < 4; i++) b[off + i] = (uint8_t)(v >> (8 * i));
}

std::vector<uint8_t> switchReport(size_t len) {
    std::vector<uint8_t> r(len, 0);
    r[0] = 0x30;
    return r;
}

constexpr size_t kSwitchUsbCommandBytes = 64;
constexpr size_t kSwitchSubcommandBytes = 12;
constexpr int kSwitchInitPacketCount = 7;
constexpr int kSwitchHandshakeStep = 0;
constexpr int kSwitchThreeMegabitStep = 1;
constexpr int kSwitchHandshakeAtThreeMegabitStep = 2;
constexpr int kSwitchTimeoutDisableStep = 3;
constexpr int kSwitchReportModeStep = 4;
constexpr int kSwitchEnableVibrationStep = 5;
constexpr int kSwitchEnableImuStep = 6;

// The index-th packet of the Switch init sequence, empty once the sequence has ended.
std::vector<uint8_t> switchInitPacket(const int index) {
    std::vector<uint8_t> packet(kSwitchUsbCommandBytes, 0xAA);
    const size_t n = usbparsers::buildSwitchInitPacket(index, packet.data(), packet.size());
    packet.resize(n);
    return packet;
}

// A 0x80 USB command, zero-padded to the full packet.
std::vector<uint8_t> switchUsbCommand(const uint8_t command) {
    std::vector<uint8_t> packet(kSwitchUsbCommandBytes, 0);
    packet[0] = 0x80;
    packet[1] = command;
    return packet;
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
    EXPECT_EQ(0x02 | 0x01, out[1]) << "no firmware read: the original mode";
    EXPECT_EQ(0x55, out[3]);
    EXPECT_EQ(0xAA, out[4]);
    EXPECT_EQ(0x00, out[39]);
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

// ---- DualSense rumble mode (hid-playstation's rules) ----

TEST(Rumble, DualSenseSelectsClassicHapticsBeforeDrivingTheMotors) {
    usbparsers::FeedbackState st;
    st.strong = 0xAA00;
    st.weak = 0x5500;
    uint8_t out[64];
    ASSERT_EQ(63u, usbparsers::buildMergedRumbleReport(Parser::DUALSENSE, st, 0, out, sizeof(out)));
    EXPECT_EQ(0x02, out[0]);
    EXPECT_EQ(0x02 | 0x01, out[1]) << "HAPTICS_SELECT + COMPATIBLE_VIBRATION";
    EXPECT_EQ(0x55, out[3]);
    EXPECT_EQ(0xAA, out[4]);
    EXPECT_EQ(0x00, out[39]) << "no COMPATIBLE_VIBRATION2 on original firmware";
}

TEST(Rumble, DualSenseOnRevisedFirmwareTakesTheSecondVibrationMode) {
    usbparsers::FeedbackState st;
    st.ds5VibrationV2 = true;
    st.strong = 0xAA00;
    st.weak = 0x5500;
    uint8_t out[64];
    ASSERT_EQ(63u, usbparsers::buildMergedRumbleReport(Parser::DUALSENSE, st, 0, out, sizeof(out)));
    EXPECT_EQ(0x02, out[1]) << "HAPTICS_SELECT alone in valid_flag0";
    EXPECT_EQ(0x04, out[39]) << "COMPATIBLE_VIBRATION2 in valid_flag2";
    EXPECT_EQ(0x55, out[3]);
    EXPECT_EQ(0xAA, out[4]);
}

constexpr uint16_t kDualSensePid = 0x0CE6;
constexpr uint16_t kDualSenseEdgePid = 0x0DF2;

TEST(Firmware, DualSenseFeatureVersionIsReadFromReport0x20) {
    uint8_t buf[usbparsers::DS5_FIRMWARE_INFO_REPORT_BYTES] = {};
    buf[0] = usbparsers::DS5_FIRMWARE_INFO_REPORT_ID;
    buf[44] = 0x15;
    buf[45] = 0x02;
    usbparsers::Ds5FirmwareInfo info;
    ASSERT_TRUE(usbparsers::parseDs5FirmwareInfo(buf, sizeof(buf), info));
    EXPECT_TRUE(info.valid);
    EXPECT_EQ(0x0215, info.featureVersion);
}

TEST(Firmware, DualSenseFirmwareInfoOfAnotherLengthOrIdIsNotRead) {
    uint8_t buf[usbparsers::DS5_FIRMWARE_INFO_REPORT_BYTES] = {};
    buf[0] = usbparsers::DS5_FIRMWARE_INFO_REPORT_ID;
    buf[44] = 0x24;
    buf[45] = 0x02;
    usbparsers::Ds5FirmwareInfo info;
    EXPECT_FALSE(usbparsers::parseDs5FirmwareInfo(buf, sizeof(buf) - 1, info));
    EXPECT_FALSE(info.valid);
    buf[0] = 0x05;
    EXPECT_FALSE(usbparsers::parseDs5FirmwareInfo(buf, sizeof(buf), info));
    EXPECT_FALSE(info.valid);
}

TEST(Firmware, TheSecondVibrationModeFollowsHidPlaystation) {
    const usbparsers::Ds5FirmwareInfo unread;
    usbparsers::Ds5FirmwareInfo revised;
    revised.valid = true;
    revised.featureVersion = 0x0215;
    usbparsers::Ds5FirmwareInfo original;
    original.valid = true;
    original.featureVersion = 0x0214;
    EXPECT_TRUE(usbparsers::ds5UsesVibrationV2(kDualSensePid, revised));
    EXPECT_FALSE(usbparsers::ds5UsesVibrationV2(kDualSensePid, original));
    EXPECT_FALSE(usbparsers::ds5UsesVibrationV2(kDualSensePid, unread));
    EXPECT_TRUE(usbparsers::ds5UsesVibrationV2(kDualSenseEdgePid, unread));
    EXPECT_TRUE(usbparsers::ds5UsesVibrationV2(kDualSenseEdgePid, original));
}

TEST(Feedback, MergedGipRumbleCarriesAllFourMotors) {
    usbparsers::FeedbackState st;
    st.strong = 0xFFFF;
    st.weak = 0x8000;
    st.leftTrigger = 0x4000;
    st.rightTrigger = 0x2000;
    uint8_t out[64];
    size_t n =
        usbparsers::buildMergedRumbleReport(Parser::XBOX_ONE_GIP, st, 0x11, out, sizeof(out));
    ASSERT_EQ(13u, n);
    // Mask 0x0F keeps all four motors addressed; trigger magnitudes ride bytes
    // 6/7 with the same /512 scale as the mains.
    EXPECT_EQ(0x09, out[0]);
    EXPECT_EQ(0x11, out[2]);
    EXPECT_EQ(0x0F, out[5]);
    EXPECT_EQ(0x20, out[6]);
    EXPECT_EQ(0x10, out[7]);
    EXPECT_EQ(0x7F, out[8]);
    EXPECT_EQ(0x40, out[9]);
}

TEST(Feedback, MergedRumbleOffGipIgnoresTheTriggerLevels) {
    usbparsers::FeedbackState st;
    st.strong = 0xAA00;
    st.weak = 0x5500;
    st.leftTrigger = 0xFFFF; // must be ignored off-GIP
    uint8_t out[64];
    size_t n = usbparsers::buildMergedRumbleReport(Parser::DUALSHOCK4, st, 0, out, sizeof(out));
    ASSERT_EQ(32u, n);
    EXPECT_EQ(0x05, out[0]);
    EXPECT_EQ(0x01, out[1]);
    EXPECT_EQ(0x55, out[4]);
    EXPECT_EQ(0xAA, out[5]);
}

TEST(Feedback, DualShock4LightbarLeavesMotorsUnclaimed) {
    usbparsers::FeedbackState st;
    uint8_t out[64];
    size_t n = usbparsers::buildLightbarReport(Parser::DUALSHOCK4, st, 1, 2, 3, out, sizeof(out));
    ASSERT_EQ(32u, n);
    EXPECT_EQ(0x05, out[0]);
    EXPECT_EQ(0x02, out[1]); // lightbar valid, motors NOT valid
    EXPECT_EQ(0x00, out[4]);
    EXPECT_EQ(0x00, out[5]);
    EXPECT_EQ(1, out[6]);
    EXPECT_EQ(2, out[7]);
    EXPECT_EQ(3, out[8]);
}

TEST(Feedback, DualSenseLightbarSetupHandoffFiresExactlyOnce) {
    usbparsers::FeedbackState st;
    uint8_t out[64];
    size_t n = usbparsers::buildLightbarReport(Parser::DUALSENSE, st, 9, 8, 7, out, sizeof(out));
    ASSERT_EQ(63u, n);
    EXPECT_EQ(0x02, out[0]);
    EXPECT_EQ(0x00, out[1]);  // no motor/trigger claims
    EXPECT_EQ(0x04, out[2]);  // valid_flag1 lightbar control
    EXPECT_EQ(0x02, out[39]); // valid_flag2 LIGHTBAR_SETUP on the first write
    EXPECT_EQ(0x02, out[42]); // lightbar_setup = LIGHT_OUT
    EXPECT_EQ(9, out[45]);
    EXPECT_EQ(8, out[46]);
    EXPECT_EQ(7, out[47]);
    EXPECT_TRUE(st.ds5LightbarSetupSent);

    n = usbparsers::buildLightbarReport(Parser::DUALSENSE, st, 1, 1, 1, out, sizeof(out));
    ASSERT_EQ(63u, n);
    EXPECT_EQ(0x00, out[39]); // handoff never repeats
    EXPECT_EQ(0x00, out[42]);
}

TEST(Feedback, LightbarUnsupportedFamiliesReturnZero) {
    usbparsers::FeedbackState st;
    uint8_t out[64];
    EXPECT_EQ(0u,
              usbparsers::buildLightbarReport(Parser::XBOX_ONE_GIP, st, 1, 2, 3, out, sizeof(out)));
    EXPECT_EQ(
        0u, usbparsers::buildLightbarReport(Parser::SWITCH_PRO_USB, st, 1, 2, 3, out, sizeof(out)));
    EXPECT_EQ(0u,
              usbparsers::buildLightbarReport(Parser::XINPUT_360, st, 1, 2, 3, out, sizeof(out)));
}

TEST(Feedback, DualSensePlayerLedsMaskedToFiveBits) {
    usbparsers::FeedbackState st;
    uint8_t out[64];
    size_t n = usbparsers::buildPlayerLedsReport(Parser::DUALSENSE, st, 0xFF, 0, out, sizeof(out));
    ASSERT_EQ(63u, n);
    EXPECT_EQ(0x02, out[0]);
    EXPECT_EQ(0x10, out[2]); // valid_flag1 player-indicator control
    EXPECT_EQ(0x1F, out[44]);
}

TEST(Feedback, SwitchPlayerLedsRideNeutralRumbleSubcommand) {
    usbparsers::FeedbackState st;
    uint8_t out[64];
    size_t n =
        usbparsers::buildPlayerLedsReport(Parser::SWITCH_PRO_USB, st, 0xF3, 0x2B, out, sizeof(out));
    ASSERT_EQ(12u, n);
    EXPECT_EQ(0x01, out[0]);
    EXPECT_EQ(0x0B, out[1]); // seq low nibble
    const uint8_t neutral[4] = {0x00, 0x01, 0x40, 0x40};
    for (int i = 0; i < 4; i++) {
        EXPECT_EQ(neutral[i], out[2 + i]) << "left byte " << i;
        EXPECT_EQ(neutral[i], out[6 + i]) << "right byte " << i;
    }
    EXPECT_EQ(0x30, out[10]);
    EXPECT_EQ(0x03, out[11]); // solid-LED nibble only
}

TEST(Feedback, TriggerEffectsAreDualSenseOnlyAndByteExact) {
    usbparsers::FeedbackState st;
    uint8_t left[11], right[11];
    for (int i = 0; i < 11; i++) {
        left[i] = (uint8_t)(0x60 + i);
        right[i] = (uint8_t)(0x40 + i);
    }
    uint8_t out[64];
    size_t n =
        usbparsers::buildTriggerEffectsReport(Parser::DUALSENSE, st, left, right, out, sizeof(out));
    ASSERT_EQ(63u, n);
    EXPECT_EQ(0x02, out[0]);
    EXPECT_EQ(0x04 | 0x08, out[1]); // right + left trigger-effect claims
    for (int i = 0; i < 11; i++) {
        EXPECT_EQ(right[i], out[11 + i]) << "right byte " << i;
        EXPECT_EQ(left[i], out[22 + i]) << "left byte " << i;
    }
    EXPECT_EQ(0u, usbparsers::buildTriggerEffectsReport(Parser::DUALSHOCK4, st, left, right, out,
                                                        sizeof(out)));
    EXPECT_EQ(0u, usbparsers::buildTriggerEffectsReport(Parser::XBOX_ONE_GIP, st, left, right, out,
                                                        sizeof(out)));
}

// ---- DualSense mic-mute lamp (MSG_MIC_LED) ----

TEST(MicMuteLed, DualSenseStatesAreByteExact) {
    // hid-playstation's RID-stripped 8 and 9, plus one for the report id this file's builders
    // carry at out[0]: lamp at out[9], power_save at out[10], valid_flag1 at out[2].
    const uint8_t states[] = {usbparsers::MIC_MUTE_LED_OFF, usbparsers::MIC_MUTE_LED_ON,
                              usbparsers::MIC_MUTE_LED_PULSE};
    for (uint8_t state : states) {
        usbparsers::FeedbackState st;
        uint8_t out[64];
        size_t n =
            usbparsers::buildMicMuteLedReport(Parser::DUALSENSE, st, state, out, sizeof(out));
        ASSERT_EQ(63u, n) << "state " << (int)state;
        EXPECT_EQ(0x02, out[0]);
        EXPECT_EQ(0x00, out[1]); // no motor or trigger claims
        // valid_flag1: MIC_MUTE_LED_CONTROL_ENABLE | POWER_SAVE_CONTROL_ENABLE.
        EXPECT_EQ(0x01 | 0x02, out[2]);
        EXPECT_EQ(state, out[9]);
        // The amp follows the lamp: lit (on OR pulse) means the pad's own microphone is muted.
        EXPECT_EQ(state == usbparsers::MIC_MUTE_LED_OFF ? 0x00 : 0x10, out[10]);
        EXPECT_EQ(state, st.ds5MicMuteLed);
        EXPECT_TRUE(st.ds5MicMuteLedSet);
        // Nothing else in the report is claimed or set.
        EXPECT_EQ(0x00, out[39]);
        EXPECT_EQ(0x00, out[44]);
        EXPECT_EQ(0x00, out[45]);
    }
}

TEST(MicMuteLed, OnlyTheDualSenseHasTheLamp) {
    usbparsers::FeedbackState st;
    uint8_t out[64];
    for (auto p : {Parser::NONE, Parser::XINPUT_360, Parser::XBOX_ONE_GIP, Parser::DUALSHOCK4,
                   Parser::SWITCH_PRO_USB, Parser::STADIA, Parser::GENERIC_HID_GAMEPAD,
                   Parser::XINPUT_360_WIRELESS, Parser::STEAM_CONTROLLER, Parser::SWITCH_N64_USB}) {
        EXPECT_EQ(0u, usbparsers::buildMicMuteLedReport(p, st, usbparsers::MIC_MUTE_LED_ON, out,
                                                        sizeof(out)))
            << usbparsers::parserName(p);
    }
    EXPECT_FALSE(st.ds5MicMuteLedSet) << "a refused build must not shadow a lamp";
}

TEST(MicMuteLed, AnUnknownStateIsRefusedRatherThanClamped) {
    usbparsers::FeedbackState st;
    uint8_t out[64];
    EXPECT_EQ(0u, usbparsers::buildMicMuteLedReport(Parser::DUALSENSE, st, 3, out, sizeof(out)));
    EXPECT_EQ(0u, usbparsers::buildMicMuteLedReport(Parser::DUALSENSE, st, 0xFF, out, sizeof(out)));
    EXPECT_FALSE(st.ds5MicMuteLedSet);
    // And a too-small buffer is the usual refusal.
    EXPECT_EQ(0u, usbparsers::buildMicMuteLedReport(Parser::DUALSENSE, st,
                                                    usbparsers::MIC_MUTE_LED_ON, out, 62));
}

TEST(MicMuteLed, EveryOtherDualSenseReportReassertsTheLamp) {
    usbparsers::FeedbackState st;
    uint8_t out[64];
    uint8_t block[11] = {};
    for (int i = 0; i < 11; i++) block[i] = (uint8_t)(0x70 + i);

    ASSERT_EQ(63u, usbparsers::buildMicMuteLedReport(
                       Parser::DUALSENSE, st, usbparsers::MIC_MUTE_LED_ON, out, sizeof(out)));

    // A colour written after the lamp carries both: the firmware applies whatever the valid flags
    // claim, so a lightbar report that flagged the lamp field and left it zeroed would turn the
    // lamp off as a side effect of changing colour.
    ASSERT_EQ(63u,
              usbparsers::buildLightbarReport(Parser::DUALSENSE, st, 9, 8, 7, out, sizeof(out)));
    EXPECT_EQ(0x04 | 0x01 | 0x02, out[2]) << "lightbar + lamp + power-save claims";
    EXPECT_EQ(usbparsers::MIC_MUTE_LED_ON, out[9]);
    EXPECT_EQ(0x10, out[10]);
    EXPECT_EQ(9, out[45]);
    EXPECT_EQ(8, out[46]);
    EXPECT_EQ(7, out[47]);

    ASSERT_EQ(63u,
              usbparsers::buildPlayerLedsReport(Parser::DUALSENSE, st, 0x1F, 0, out, sizeof(out)));
    EXPECT_EQ(0x10 | 0x01 | 0x02, out[2]);
    EXPECT_EQ(usbparsers::MIC_MUTE_LED_ON, out[9]);
    EXPECT_EQ(0x1F, out[44]);

    ASSERT_EQ(63u, usbparsers::buildTriggerEffectsReport(Parser::DUALSENSE, st, block, block, out,
                                                         sizeof(out)));
    EXPECT_EQ(0x04 | 0x08, out[1]) << "the trigger claims are untouched";
    EXPECT_EQ(0x01 | 0x02, out[2]);
    EXPECT_EQ(usbparsers::MIC_MUTE_LED_ON, out[9]);
    EXPECT_EQ(block[0], out[11]);
    EXPECT_EQ(block[0], out[22]);

    st.strong = 0x8000;
    st.weak = 0x4000;
    ASSERT_EQ(63u, usbparsers::buildMergedRumbleReport(Parser::DUALSENSE, st, 0, out, sizeof(out)));
    EXPECT_EQ(0x02 | 0x01, out[1]) << "the motor claims are untouched";
    EXPECT_EQ(0x01 | 0x02, out[2]);
    EXPECT_EQ(usbparsers::MIC_MUTE_LED_ON, out[9]);
    EXPECT_EQ(0x40, out[3]);
    EXPECT_EQ(0x80, out[4]);
}

TEST(MicMuteLed, TheLampWrittenAfterOtherEffectsSurvivesToo) {
    // The other direction of the same rule: a lamp write is a whole fresh report, so nothing the
    // pad was already doing may be claimed by it.
    usbparsers::FeedbackState st;
    uint8_t out[64];
    uint8_t block[11] = {};
    for (int i = 0; i < 11; i++) block[i] = (uint8_t)(0x50 + i);
    ASSERT_EQ(63u, usbparsers::buildTriggerEffectsReport(Parser::DUALSENSE, st, block, block, out,
                                                         sizeof(out)));
    ASSERT_EQ(63u, usbparsers::buildMicMuteLedReport(
                       Parser::DUALSENSE, st, usbparsers::MIC_MUTE_LED_PULSE, out, sizeof(out)));
    EXPECT_EQ(0x00, out[1]) << "a lamp write must not claim the trigger blocks";
    EXPECT_EQ(0x01 | 0x02, out[2]);
    EXPECT_EQ(usbparsers::MIC_MUTE_LED_PULSE, out[9]);
    EXPECT_EQ(0x10, out[10]);
    EXPECT_EQ(0x00, out[11]);
    EXPECT_EQ(0x00, out[22]);

    // And the next trigger write still carries the pulse.
    ASSERT_EQ(63u, usbparsers::buildTriggerEffectsReport(Parser::DUALSENSE, st, block, block, out,
                                                         sizeof(out)));
    EXPECT_EQ(usbparsers::MIC_MUTE_LED_PULSE, out[9]);
    EXPECT_EQ(block[0], out[11]);
}

TEST(MicMuteLed, ALampTurnedOffStaysAssertedAndUnmutesTheAmp) {
    usbparsers::FeedbackState st;
    uint8_t out[64];
    ASSERT_EQ(63u, usbparsers::buildMicMuteLedReport(
                       Parser::DUALSENSE, st, usbparsers::MIC_MUTE_LED_ON, out, sizeof(out)));
    ASSERT_EQ(63u, usbparsers::buildMicMuteLedReport(
                       Parser::DUALSENSE, st, usbparsers::MIC_MUTE_LED_OFF, out, sizeof(out)));
    EXPECT_EQ(usbparsers::MIC_MUTE_LED_OFF, out[9]);
    EXPECT_EQ(0x00, out[10]);
    // Still shadowed: an off lamp is a state the host asked for, so later reports must keep saying
    // off rather than dropping the claim and letting the pad's own button state show through.
    EXPECT_TRUE(st.ds5MicMuteLedSet);
    ASSERT_EQ(63u,
              usbparsers::buildLightbarReport(Parser::DUALSENSE, st, 1, 2, 3, out, sizeof(out)));
    EXPECT_EQ(0x04 | 0x01 | 0x02, out[2]);
    EXPECT_EQ(usbparsers::MIC_MUTE_LED_OFF, out[9]);
    EXPECT_EQ(0x00, out[10]);
}

TEST(MicMuteLed, ReportsAreUntouchedUntilTheHostDrivesTheLamp) {
    // Nothing changes for a host that never sends MSG_MIC_LED: no lamp claim, no power-save claim.
    usbparsers::FeedbackState st;
    uint8_t out[64];
    uint8_t block[11] = {};
    ASSERT_EQ(63u,
              usbparsers::buildLightbarReport(Parser::DUALSENSE, st, 1, 2, 3, out, sizeof(out)));
    EXPECT_EQ(0x04, out[2]);
    EXPECT_EQ(0x00, out[9]);
    EXPECT_EQ(0x00, out[10]);
    ASSERT_EQ(63u,
              usbparsers::buildPlayerLedsReport(Parser::DUALSENSE, st, 0x03, 0, out, sizeof(out)));
    EXPECT_EQ(0x10, out[2]);
    ASSERT_EQ(63u, usbparsers::buildTriggerEffectsReport(Parser::DUALSENSE, st, block, block, out,
                                                         sizeof(out)));
    EXPECT_EQ(0x00, out[2]);
}

TEST(FeedbackCapability, PredicatesMatchTheBuilders) {
    using usbparsers::parserHasLightbar;
    using usbparsers::parserHasMicMuteLed;
    using usbparsers::parserHasPlayerLeds;
    using usbparsers::parserHasTriggerEffects;
    using usbparsers::parserHasTriggerRumble;
    for (auto p : {Parser::NONE, Parser::XINPUT_360, Parser::XBOX_ONE_GIP, Parser::DUALSHOCK4,
                   Parser::DUALSENSE, Parser::SWITCH_PRO_USB, Parser::STADIA,
                   Parser::GENERIC_HID_GAMEPAD, Parser::XINPUT_360_WIRELESS,
                   Parser::STEAM_CONTROLLER, Parser::SWITCH_N64_USB, Parser::STEAM_TRITON}) {
        usbparsers::FeedbackState st;
        uint8_t out[64];
        uint8_t block[11] = {};
        EXPECT_EQ(parserHasRumble(p), buildRumbleReport(p, 1, 1, 0, out, sizeof(out)) != 0)
            << usbparsers::parserName(p);
        EXPECT_EQ(parserHasLightbar(p),
                  usbparsers::buildLightbarReport(p, st, 0, 0, 0, out, sizeof(out)) != 0)
            << usbparsers::parserName(p);
        EXPECT_EQ(parserHasPlayerLeds(p),
                  usbparsers::buildPlayerLedsReport(p, st, 0, 0, out, sizeof(out)) != 0)
            << usbparsers::parserName(p);
        EXPECT_EQ(parserHasTriggerEffects(p),
                  usbparsers::buildTriggerEffectsReport(p, st, block, block, out, sizeof(out)) != 0)
            << usbparsers::parserName(p);
        EXPECT_EQ(parserHasMicMuteLed(p),
                  usbparsers::buildMicMuteLedReport(p, st, usbparsers::MIC_MUTE_LED_ON, out,
                                                    sizeof(out)) != 0)
            << usbparsers::parserName(p);
    }
    EXPECT_TRUE(parserHasTriggerRumble(Parser::XBOX_ONE_GIP));
    EXPECT_FALSE(parserHasTriggerRumble(Parser::DUALSENSE));
    EXPECT_FALSE(parserHasTriggerRumble(Parser::XINPUT_360));
    EXPECT_FALSE(parserHasTriggerRumble(Parser::SWITCH_PRO_USB));
}

// parserHasRumble must agree with which families buildRumbleReport actually emits.
TEST(RumbleCapability, FamiliesWithBuildersReportTrue) {
    EXPECT_TRUE(parserHasRumble(Parser::XINPUT_360));
    EXPECT_TRUE(parserHasRumble(Parser::XBOX_ONE_GIP));
    EXPECT_TRUE(parserHasRumble(Parser::DUALSHOCK4));
    EXPECT_TRUE(parserHasRumble(Parser::DUALSENSE));
    EXPECT_TRUE(parserHasRumble(Parser::SWITCH_PRO_USB));
    EXPECT_TRUE(parserHasRumble(Parser::SWITCH_N64_USB));
    EXPECT_TRUE(parserHasRumble(Parser::STEAM_TRITON));
}

TEST(RumbleCapability, FamiliesWithoutBuildersReportFalse) {
    EXPECT_FALSE(parserHasRumble(Parser::STADIA));
    EXPECT_FALSE(parserHasRumble(Parser::GENERIC_HID_GAMEPAD));
    EXPECT_FALSE(parserHasRumble(Parser::NONE));
}

TEST(FrameworkRumble, SwitchProProtocolIsUnreliable) {
    EXPECT_TRUE(parserFrameworkRumbleUnreliable(Parser::SWITCH_PRO_USB));
    EXPECT_TRUE(parserFrameworkRumbleUnreliable(Parser::SWITCH_N64_USB));
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
    EXPECT_TRUE(probePermitsClaim(ProbeOutcome::DECODED, true, Parser::XINPUT_360));
    EXPECT_TRUE(probePermitsClaim(ProbeOutcome::DECODED, false, Parser::GENERIC_HID_GAMEPAD));
    EXPECT_TRUE(probePermitsClaim(ProbeOutcome::DECODED, true, Parser::DUALSENSE));
}

TEST(ProbeRule, SilenceClaimsOnlyVerifiedModelsOfFamiliesThatReportOnChange) {
    EXPECT_TRUE(probePermitsClaim(ProbeOutcome::SILENT, true, Parser::XINPUT_360));
    EXPECT_TRUE(probePermitsClaim(ProbeOutcome::SILENT, true, Parser::XBOX_ONE_GIP));
    EXPECT_FALSE(probePermitsClaim(ProbeOutcome::SILENT, false, Parser::XINPUT_360));
    EXPECT_FALSE(probePermitsClaim(ProbeOutcome::SILENT, false, Parser::GENERIC_HID_GAMEPAD));
}

TEST(ProbeRule, SilenceFromAFamilyThatStreamsUnpromptedNeverClaims) {
    // A Sony pad or a Switch pad reports whether or not anything moved, so a verified identity
    // that stays quiet through the probe is not that pad: an 8BitDo adapter in its Switch or
    // PS Classic mode, say.
    for (const Parser p :
         {Parser::SWITCH_PRO_USB, Parser::SWITCH_N64_USB, Parser::DUALSHOCK4, Parser::DUALSENSE}) {
        EXPECT_FALSE(probePermitsClaim(ProbeOutcome::SILENT, true, p));
        EXPECT_FALSE(probePermitsClaim(ProbeOutcome::SILENT, false, p));
    }
}

TEST(ProbeRule, UndecodedTrafficNeverClaims) {
    EXPECT_FALSE(probePermitsClaim(ProbeOutcome::UNDECODED, true, Parser::XINPUT_360));
    EXPECT_FALSE(probePermitsClaim(ProbeOutcome::UNDECODED, false, Parser::XINPUT_360));
    EXPECT_FALSE(probePermitsClaim(ProbeOutcome::UNDECODED, true, Parser::DUALSHOCK4));
}

TEST(ProbeRule, TheStreamingFamiliesAreTheSonyPadsAndTheSwitchPads) {
    EXPECT_TRUE(usbparsers::parserStreamsUnprompted(Parser::DUALSHOCK4));
    EXPECT_TRUE(usbparsers::parserStreamsUnprompted(Parser::DUALSENSE));
    EXPECT_TRUE(usbparsers::parserStreamsUnprompted(Parser::SWITCH_PRO_USB));
    EXPECT_TRUE(usbparsers::parserStreamsUnprompted(Parser::SWITCH_N64_USB));
    EXPECT_FALSE(usbparsers::parserStreamsUnprompted(Parser::XINPUT_360));
    EXPECT_FALSE(usbparsers::parserStreamsUnprompted(Parser::XINPUT_360_WIRELESS));
    EXPECT_FALSE(usbparsers::parserStreamsUnprompted(Parser::XBOX_ONE_GIP));
    EXPECT_FALSE(usbparsers::parserStreamsUnprompted(Parser::GENERIC_HID_GAMEPAD));
    EXPECT_FALSE(usbparsers::parserStreamsUnprompted(Parser::STEAM_CONTROLLER));
    EXPECT_FALSE(usbparsers::parserStreamsUnprompted(Parser::STADIA));
    EXPECT_FALSE(usbparsers::parserStreamsUnprompted(Parser::NONE));
}

TEST(KnownDevices, BluetoothOnlyXboxIdentitiesAreNotUsbRows) {
    // 0x0B05 and 0x0B22 are the Elite Series 2's Bluetooth and BLE identities; neither ever
    // enumerates on USB.
    EXPECT_EQ(nullptr, usbparsers::lookupKnown(0x045E, 0x0B05));
    EXPECT_EQ(nullptr, usbparsers::lookupKnown(0x045E, 0x0B22));
    EXPECT_FALSE(usbparsers::isVerifiedFastLane(0x045E, 0x0B22));
}

TEST(KnownDevices, EightBitDoXinputRowsCarryTheXpadNames) {
    const usbparsers::KnownDevice* sn30 = usbparsers::lookupKnown(0x2DC8, 0x6001);
    ASSERT_NE(nullptr, sn30);
    EXPECT_STREQ("8BitDo SN30 Pro (XInput)", sn30->name);
    EXPECT_EQ(Parser::XINPUT_360, sn30->parser);
    const usbparsers::KnownDevice* ultimate = usbparsers::lookupKnown(0x2DC8, 0x310A);
    ASSERT_NE(nullptr, ultimate);
    EXPECT_STREQ("8BitDo Ultimate 2C Wireless Controller", ultimate->name);
}

// The Ultimate 2 Wireless in X-input mode, the xpad identity Linux gained in April 2025: older
// Android kernels never bind it, so the framework shows no gamepad and only Direct reads it.
TEST(KnownDevices, EightBitDoUltimate2WirelessInXinputModeIsAVerifiedXinputRow) {
    constexpr uint16_t kEightBitDo = 0x2DC8;
    constexpr uint16_t kUltimate2WirelessXinput = 0x310B;
    const usbparsers::KnownDevice* k =
        usbparsers::lookupKnown(kEightBitDo, kUltimate2WirelessXinput);
    ASSERT_NE(nullptr, k);
    EXPECT_STREQ("8BitDo Ultimate 2 Wireless Controller", k->name);
    EXPECT_EQ(Parser::XINPUT_360, k->parser);
    EXPECT_EQ(InitKind::NONE, k->init);
    EXPECT_TRUE(isVerifiedFastLane(kEightBitDo, kUltimate2WirelessXinput));

    constexpr uint8_t kVendorClass = 0xFF;
    constexpr uint8_t kXinputSubclass = 0x5D;
    constexpr uint8_t kXinputProtocol = 0x01;
    const usbparsers::Classification c = usbparsers::classifyDevice(
        kEightBitDo, kUltimate2WirelessXinput, kVendorClass, kXinputSubclass, kXinputProtocol);
    EXPECT_EQ(Parser::XINPUT_360, c.parser);
    EXPECT_EQ(InitKind::NONE, c.init);
    EXPECT_STREQ("8BitDo Ultimate 2 Wireless Controller", c.name);
    EXPECT_TRUE(modelExpectsFrameworkGamepad(kEightBitDo, kUltimate2WirelessXinput));
}

TEST(KnownDevices, EightBitDoHidIdentitiesAreNoLongerXinputRows) {
    // 0x9000 and 0x9001 are the FC30 Pro and N30 Pro in D-input, 0x6101 the SN30 Pro's Bluetooth
    // identity: none speaks XInput, and a row that said so claimed a quiet pad as dead.
    EXPECT_EQ(nullptr, usbparsers::lookupKnown(0x2DC8, 0x9000));
    EXPECT_EQ(nullptr, usbparsers::lookupKnown(0x2DC8, 0x9001));
    EXPECT_EQ(nullptr, usbparsers::lookupKnown(0x2DC8, 0x6101));
}

TEST(KnownDevices, EightBitDoDinputIdentitiesAreGenericHidInTheirOrderAndNotFastLane) {
    for (const uint16_t pid : {0x5006, 0x3100, 0x3105}) {
        const usbparsers::KnownDevice* k = usbparsers::lookupKnown(0x2DC8, pid);
        ASSERT_NE(nullptr, k) << pid;
        EXPECT_EQ(Parser::GENERIC_HID_GAMEPAD, k->parser) << pid;
        EXPECT_EQ(usbparsers::ButtonOrder::EIGHTBITDO, k->order) << pid;
        EXPECT_FALSE(usbparsers::isVerifiedFastLane(0x2DC8, pid)) << pid;
    }
    const usbparsers::Classification c = usbparsers::classifyDevice(0x2DC8, 0x5006, 3, 0, 0);
    EXPECT_EQ(Parser::GENERIC_HID_GAMEPAD, c.parser);
    EXPECT_EQ(usbparsers::ButtonOrder::EIGHTBITDO, c.order);
}

TEST(KnownDevices, ThePlayStationClassicControllerIsGenericHidInItsOwnOrder) {
    const usbparsers::KnownDevice* k = usbparsers::lookupKnown(0x054C, 0x0CDA);
    ASSERT_NE(nullptr, k);
    EXPECT_EQ(Parser::GENERIC_HID_GAMEPAD, k->parser);
    EXPECT_EQ(usbparsers::ButtonOrder::PLAYSTATION_CLASSIC, k->order);
    EXPECT_FALSE(usbparsers::isVerifiedFastLane(0x054C, 0x0CDA));
}

TEST(SwitchInit, TheUsbCommandsAreHandshakeThreeMegabitHandshakeThenTimeoutDisable) {
    EXPECT_EQ(switchUsbCommand(0x02), switchInitPacket(kSwitchHandshakeStep));
    EXPECT_EQ(switchUsbCommand(0x03), switchInitPacket(kSwitchThreeMegabitStep));
    EXPECT_EQ(switchUsbCommand(0x02), switchInitPacket(kSwitchHandshakeAtThreeMegabitStep));
    EXPECT_EQ(switchUsbCommand(0x04), switchInitPacket(kSwitchTimeoutDisableStep));
}

TEST(SwitchInit, EachUsbCommandGoesOutAsAFullSixtyFourBytePacket) {
    for (int step = kSwitchHandshakeStep; step <= kSwitchTimeoutDisableStep; step++) {
        EXPECT_EQ(kSwitchUsbCommandBytes, switchInitPacket(step).size()) << step;
    }
}

TEST(SwitchInit, TheSubcommandsFollowTheUsbCommandsAsTwelveBytesEach) {
    const std::vector<uint8_t> reportMode = switchInitPacket(kSwitchReportModeStep);
    ASSERT_EQ(kSwitchSubcommandBytes, reportMode.size());
    EXPECT_EQ(0x01, reportMode[0]);
    EXPECT_EQ(0x03, reportMode[10]);
    EXPECT_EQ(0x30, reportMode[11]);
    const std::vector<uint8_t> enableVibration = switchInitPacket(kSwitchEnableVibrationStep);
    ASSERT_EQ(kSwitchSubcommandBytes, enableVibration.size());
    EXPECT_EQ(0x48, enableVibration[10]);
    EXPECT_EQ(0x01, enableVibration[11]);
    const std::vector<uint8_t> enableImu = switchInitPacket(kSwitchEnableImuStep);
    ASSERT_EQ(kSwitchSubcommandBytes, enableImu.size());
    EXPECT_EQ(0x40, enableImu[10]);
    EXPECT_EQ(0x01, enableImu[11]);
}

TEST(SwitchInit, TheSubcommandsCountTheirPacketsInTheirSecondByte) {
    const std::vector<uint8_t> enableImu = switchInitPacket(kSwitchEnableImuStep);
    ASSERT_EQ(kSwitchSubcommandBytes, enableImu.size());
    EXPECT_EQ(0x00, switchInitPacket(kSwitchReportModeStep)[1]);
    EXPECT_EQ(0x01, switchInitPacket(kSwitchEnableVibrationStep)[1]);
    EXPECT_EQ(0x02, enableImu[1]);
}

TEST(SwitchInit, TheSequenceEndsAfterSevenPacketsAndRefusesASmallBuffer) {
    EXPECT_FALSE(switchInitPacket(kSwitchInitPacketCount - 1).empty());
    EXPECT_TRUE(switchInitPacket(kSwitchInitPacketCount).empty());
    EXPECT_TRUE(switchInitPacket(-1).empty());
    uint8_t small[16];
    EXPECT_EQ(0u, usbparsers::buildSwitchInitPacket(kSwitchHandshakeStep, small, sizeof(small)));
}

TEST(SwitchInit, AFailedFirstHandshakeOrReportModeWriteEndsTheSequence) {
    EXPECT_TRUE(usbparsers::switchInitStepIsFatal(kSwitchHandshakeStep));
    EXPECT_TRUE(usbparsers::switchInitStepIsFatal(kSwitchReportModeStep));
}

TEST(SwitchInit, AThreeMegabitWriteFailingAsOnThe8BitDoM30DoesNotEndTheSequence) {
    EXPECT_FALSE(usbparsers::switchInitStepIsFatal(kSwitchThreeMegabitStep));
}

TEST(SwitchInit, AFailedHandshakeAtThreeMegabitDoesNotEndTheSequence) {
    EXPECT_FALSE(usbparsers::switchInitStepIsFatal(kSwitchHandshakeAtThreeMegabitStep));
}

TEST(SwitchInit, AFailedTimeoutDisableVibrationEnableOrImuEnableDoesNotEndTheSequence) {
    EXPECT_FALSE(usbparsers::switchInitStepIsFatal(kSwitchTimeoutDisableStep));
    EXPECT_FALSE(usbparsers::switchInitStepIsFatal(kSwitchEnableVibrationStep));
    EXPECT_FALSE(usbparsers::switchInitStepIsFatal(kSwitchEnableImuStep));
}

TEST(SwitchInit, NoStepPastTheSequenceIsFatal) {
    EXPECT_FALSE(usbparsers::switchInitStepIsFatal(kSwitchInitPacketCount));
    EXPECT_FALSE(usbparsers::switchInitStepIsFatal(-1));
}

TEST(GipAck, AReportThatAsksForAnAckGetsXpadsAnswer) {
    // The mode-button report an Xbox One S retransmits until it is acknowledged; the answer is
    // the one xpad sends (xpadone_ack_mode_report).
    const uint8_t guide[] = {0x07, 0x30, 0x1A, 0x02, 0x01, 0x5B};
    uint8_t ack[usbparsers::kGipAckBytes];
    ASSERT_EQ(usbparsers::kGipAckBytes,
              usbparsers::buildGipAck(guide, sizeof(guide), ack, sizeof(ack)));
    const uint8_t expected[] = {0x01, 0x20, 0x1A, 0x09, 0x00, 0x07, 0x20,
                                0x02, 0x00, 0x00, 0x00, 0x00, 0x00};
    EXPECT_EQ(0, memcmp(expected, ack, sizeof(expected)));
}

TEST(GipAck, AReportWithoutTheAckFlagsGetsNone) {
    const uint8_t input[] = {0x20, 0x00, 0x05, 0x0E, 0x00, 0x00, 0x00, 0x00, 0x00};
    uint8_t ack[usbparsers::kGipAckBytes];
    EXPECT_EQ(0u, usbparsers::buildGipAck(input, sizeof(input), ack, sizeof(ack)));
    const uint8_t halfFlag[] = {0x07, 0x20, 0x1A, 0x02, 0x01, 0x5B};
    EXPECT_EQ(0u, usbparsers::buildGipAck(halfFlag, sizeof(halfFlag), ack, sizeof(ack)));
}

TEST(GipAck, AShortReportOrASmallBufferGetsNone) {
    const uint8_t guide[] = {0x07, 0x30, 0x1A, 0x02, 0x01, 0x5B};
    uint8_t ack[usbparsers::kGipAckBytes];
    EXPECT_EQ(0u, usbparsers::buildGipAck(guide, 3, ack, sizeof(ack)));
    uint8_t small[usbparsers::kGipAckBytes - 1];
    EXPECT_EQ(0u, usbparsers::buildGipAck(guide, sizeof(guide), small, sizeof(small)));
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
    EXPECT_FALSE(parserHasImu(Parser::SWITCH_N64_USB));
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

// ── DualSense mic-mute button ───────────────────────────────────────────────
//
// The button is momentary (byte 10 bit 0x04, next to the touchpad click at 0x02) but the wire's
// WBUTTON_MIC_MUTE carries the mute STATE it toggles, so the decoder latches it in ParserState.

namespace {

// One report with the mute button held or released, decoded against a persistent parser state.
bool decodeMute(ParserState& st, bool muteDown) {
    auto r = dualSenseReport();
    if (muteDown) r[10] |= 0x04;
    DeviceState s{};
    EXPECT_TRUE(decodeReport(Parser::DUALSENSE, r.data(), r.size(), s, &st));
    return (s.wButtons & gamepad::WBUTTON_MIC_MUTE) != 0;
}

} // namespace

TEST(MicMute, PressTogglesTheStateAndReleaseDoesNot) {
    ParserState st;
    EXPECT_FALSE(decodeMute(st, false)); // idle: unmuted
    EXPECT_TRUE(decodeMute(st, true));   // press: muted from this report on
    EXPECT_TRUE(decodeMute(st, false));  // release: still muted
    EXPECT_FALSE(decodeMute(st, true));  // second press: unmuted
    EXPECT_FALSE(decodeMute(st, false));
}

TEST(MicMute, HoldingTheButtonDoesNotChatterAtReportRate) {
    // A DualSense reports at up to 1 kHz; a level-triggered toggle would flip the mute a
    // thousand times a second for as long as a finger rested on the button.
    ParserState st;
    EXPECT_TRUE(decodeMute(st, true));
    for (int i = 0; i < 50; i++) EXPECT_TRUE(decodeMute(st, true));
    EXPECT_TRUE(decodeMute(st, false));
}

TEST(MicMute, TheBitRidesEveryReportWhileMutedNotJustThePressFrame) {
    // The host reads it as state, so a frame in the middle of a quiet minute still has to say
    // "muted"; that is what makes a lost packet harmless.
    ParserState st;
    EXPECT_TRUE(decodeMute(st, true));
    auto r = dualSenseReport();
    r[8] |= 0x20; // an unrelated button (cross) on a later frame
    DeviceState s{};
    ASSERT_TRUE(decodeReport(Parser::DUALSENSE, r.data(), r.size(), s, &st));
    EXPECT_TRUE((s.wButtons & gamepad::WBUTTON_MIC_MUTE) != 0);
    EXPECT_TRUE((s.wButtons & gamepad::XUSB_A) != 0);
}

TEST(MicMute, TheMuteBitIsIndependentOfTheTouchpadClickBesideIt) {
    ParserState st;
    auto r = dualSenseReport();
    r[10] |= 0x02; // click only
    DeviceState s{};
    ASSERT_TRUE(decodeReport(Parser::DUALSENSE, r.data(), r.size(), s, &st));
    EXPECT_TRUE(s.touchClick);
    EXPECT_FALSE((s.wButtons & gamepad::WBUTTON_MIC_MUTE) != 0);

    r[10] |= 0x04; // both
    DeviceState s2{};
    ASSERT_TRUE(decodeReport(Parser::DUALSENSE, r.data(), r.size(), s2, &st));
    EXPECT_TRUE(s2.touchClick);
    EXPECT_TRUE((s2.wButtons & gamepad::WBUTTON_MIC_MUTE) != 0);
}

TEST(MicMute, AStatelessDecodeCarriesNoMuteBit) {
    // A caller with no per-device memory (the attach-time probe) cannot own a latch, so it
    // reports what it can see and never invents a mute state.
    auto r = dualSenseReport();
    r[10] |= 0x04;
    DeviceState s{};
    ASSERT_TRUE(decodeReport(Parser::DUALSENSE, r.data(), r.size(), s, nullptr));
    EXPECT_FALSE((s.wButtons & gamepad::WBUTTON_MIC_MUTE) != 0);
}

TEST(MicMute, AFreshlyClaimedPadStartsUnmuted) {
    // Matches the hardware: the pad's own mute clears when it powers down, and a new
    // ParserState is this app's equivalent of a power cycle.
    ParserState st;
    EXPECT_FALSE(st.micMuted);
    EXPECT_FALSE(st.micMuteHeld);
    EXPECT_FALSE(decodeMute(st, false));
}

TEST(MicMute, NoOtherFamilyProducesTheBit) {
    // 0x0800 is spent on the DualSense's mute button and nothing else may set it, or a pad with
    // no mute button would arrive at the host holding one down.
    ParserState st;
    auto ds4 = ds4Report();
    ds4[7] |= 0xFF; // every button byte 7 bit, mute's neighbours included
    DeviceState s{};
    ASSERT_TRUE(decodeReport(Parser::DUALSHOCK4, ds4.data(), ds4.size(), s, &st));
    EXPECT_FALSE((s.wButtons & gamepad::WBUTTON_MIC_MUTE) != 0);
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
    st.hidLayout.order = usbhid::ButtonOrder::SWITCH;

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
// payload byte 0x01 = disconnected, 0x02 = connected, 0x03 = newly paired (SDL).
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

// SDL's HIDAPI_DriverSteam_UpdateDevice: a controller that pairs to the dongle for the first time
// announces itself with 0x03 and is on its slot from then on, as a 0x02 one is.
TEST(SteamWireless, ANewlyPairedControllerIsAConnect) {
    auto paired = steamWirelessEvent(0x03);
    EXPECT_EQ(WirelessEvent::CONNECT,
              checkWirelessEvent(Parser::STEAM_CONTROLLER, paired.data(), paired.size()));
}

// SDL reads the event's first payload byte from any payload of one byte or more.
TEST(SteamWireless, AnEventWithALongerPayloadStillClassifies) {
    auto conn = steamWirelessEvent(0x02);
    conn[3] = 0x02;
    EXPECT_EQ(WirelessEvent::CONNECT,
              checkWirelessEvent(Parser::STEAM_CONTROLLER, conn.data(), conn.size()));

    auto disc = steamWirelessEvent(0x01);
    disc[3] = 0x05;
    EXPECT_EQ(WirelessEvent::DISCONNECT,
              checkWirelessEvent(Parser::STEAM_CONTROLLER, disc.data(), disc.size()));
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

    auto noPayload = steamWirelessEvent(0x02);
    noPayload[3] = 0x00;
    EXPECT_EQ(WirelessEvent::NONE,
              checkWirelessEvent(Parser::STEAM_CONTROLLER, noPayload.data(), noPayload.size()));

    auto unknownPayload = steamWirelessEvent(0x04);
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

// ---- the Steam Controller (2026, "Triton") --------------------------------------------------

namespace {

constexpr uint16_t kValve = 0x28DE;
constexpr uint16_t kTritonWired = 0x1302;
constexpr uint16_t kTritonBle = 0x1303;
constexpr uint16_t kTritonProteusDongle = 0x1304;
constexpr uint16_t kTritonNereidDongle = 0x1305;
constexpr uint16_t kTritonUsbIdentities[] = {kTritonWired, kTritonProteusDongle,
                                             kTritonNereidDongle};

// SDL's report ids (controller_structs.h, ETritonReportIDTypes).
constexpr uint8_t kTritonStateId = 0x42;
constexpr uint8_t kTritonBatteryId = 0x43;
constexpr uint8_t kTritonStateBleId = 0x45;
constexpr uint8_t kTritonWirelessXId = 0x46;
constexpr uint8_t kTritonStateTimestampId = 0x47;
constexpr uint8_t kTritonWirelessId = 0x79;

// SDL's TritonButtons.
constexpr uint32_t kTritonA = 0x00000001;
constexpr uint32_t kTritonB = 0x00000002;
constexpr uint32_t kTritonX = 0x00000004;
constexpr uint32_t kTritonY = 0x00000008;
constexpr uint32_t kTritonQam = 0x00000010;
constexpr uint32_t kTritonR3 = 0x00000020;
constexpr uint32_t kTritonView = 0x00000040;
constexpr uint32_t kTritonR4 = 0x00000080;
constexpr uint32_t kTritonR5 = 0x00000100;
constexpr uint32_t kTritonR = 0x00000200;
constexpr uint32_t kTritonDpadDown = 0x00000400;
constexpr uint32_t kTritonDpadRight = 0x00000800;
constexpr uint32_t kTritonDpadLeft = 0x00001000;
constexpr uint32_t kTritonDpadUp = 0x00002000;
constexpr uint32_t kTritonMenu = 0x00004000;
constexpr uint32_t kTritonL3 = 0x00008000;
constexpr uint32_t kTritonSteam = 0x00010000;
constexpr uint32_t kTritonL4 = 0x00020000;
constexpr uint32_t kTritonL5 = 0x00040000;
constexpr uint32_t kTritonL = 0x00080000;
constexpr uint32_t kTritonRightStickTouch = 0x00100000;
constexpr uint32_t kTritonRightPadTouch = 0x00200000;
constexpr uint32_t kTritonRightPadClick = 0x00400000;
constexpr uint32_t kTritonRightTriggerClick = 0x00800000;
constexpr uint32_t kTritonLeftStickTouch = 0x01000000;
constexpr uint32_t kTritonLeftPadTouch = 0x02000000;
constexpr uint32_t kTritonLeftPadClick = 0x04000000;
constexpr uint32_t kTritonLeftTriggerClick = 0x08000000;
constexpr uint32_t kTritonRightGripTouch = 0x10000000;
constexpr uint32_t kTritonLeftGripTouch = 0x20000000;

// The report id, then SDL's packed TritonMTUNoQuat_t (or TritonMTUNoQuat32TS_t): the buttons at
// 2, the triggers at 6 and 8, the sticks LX LY RX RY from 10, the IMU's u32 tick at 30, accel
// x y z from 34, gyro x y z from 40, 46 bytes in all. The timestamp layout has the right pad's
// pressure at 30 and shortens the tick to a u16 at 32.
constexpr size_t kTritonStateBytes = 46;
constexpr size_t kTritonButtons = 2;
constexpr size_t kTritonLeftTrigger = 6;
constexpr size_t kTritonRightTrigger = 8;
constexpr size_t kTritonSticks = 10;
constexpr size_t kTritonImuTick = 30;
constexpr size_t kTritonTimestampRightPadPressure = 30;
constexpr size_t kTritonTimestampImuTick = 32;
constexpr size_t kTritonAccel = 34;
constexpr size_t kTritonGyro = 40;
constexpr uint32_t kAnImuTick = 500;
constexpr uint32_t kTheNextImuTick = 504;

std::vector<uint8_t> tritonState(const uint8_t reportId = kTritonStateId,
                                 const uint32_t buttons = 0) {
    std::vector<uint8_t> r(kTritonStateBytes, 0);
    r[0] = reportId;
    for (size_t i = 0; i < 4; i++) r[kTritonButtons + i] = (uint8_t)(buttons >> (8 * i));
    return r;
}

void setTritonImuTick(std::vector<uint8_t>& r, const uint32_t tick) {
    const bool isTheTimestampLayout = r[0] == kTritonStateTimestampId;
    if (isTheTimestampLayout) return setLe16(r, kTritonTimestampImuTick, (int16_t)tick);
    setLe32(r, kTritonImuTick, tick);
}

void setTritonImu(std::vector<uint8_t>& r, const uint32_t tick, const int16_t (&accel)[3],
                  const int16_t (&gyro)[3]) {
    setTritonImuTick(r, tick);
    for (size_t axis = 0; axis < 3; axis++) {
        setLe16(r, kTritonAccel + 2 * axis, accel[axis]);
        setLe16(r, kTritonGyro + 2 * axis, gyro[axis]);
    }
}

bool decodeTriton(const std::vector<uint8_t>& r, DeviceState& s, ParserState& st) {
    return decodeReport(Parser::STEAM_TRITON, r.data(), r.size(), s, &st);
}

bool decodeTriton(const std::vector<uint8_t>& r, DeviceState& s) {
    ParserState st;
    return decodeTriton(r, s, st);
}

// SDL's TritonBatteryStatus_t after the id: the charge state, the percent, then six u16 readings
// this port does not use, fifteen bytes in all.
constexpr size_t kTritonBatteryBytes = 15;
constexpr size_t kTritonBatteryPaddedBytes = 64;
constexpr uint8_t kTritonChargeReset = 0;
constexpr uint8_t kTritonChargeDischarging = 1;
constexpr uint8_t kTritonChargeCharging = 2;
constexpr uint8_t kTritonChargeSourceValidate = 3;
constexpr uint8_t kTritonChargeDone = 4;

std::vector<uint8_t> tritonBattery(const uint8_t chargeState, const uint8_t percent) {
    std::vector<uint8_t> r(kTritonBatteryBytes, 0);
    r[0] = kTritonBatteryId;
    r[1] = chargeState;
    r[2] = percent;
    return r;
}

std::vector<uint8_t> tritonWireless(const uint8_t reportId, const uint8_t state) {
    return {reportId, state};
}

constexpr uint8_t kTritonWirelessDisconnected = 1;
constexpr uint8_t kTritonWirelessConnected = 2;

} // namespace

TEST(TritonClassify, TheWiredPadAndBothDonglesAreOptInRowsOnTheTritonParser) {
    for (const uint16_t pid : kTritonUsbIdentities) {
        const usbparsers::KnownDevice* k = usbparsers::lookupKnown(kValve, pid);
        ASSERT_NE(nullptr, k) << pid;
        EXPECT_EQ(Parser::STEAM_TRITON, k->parser) << pid;
        EXPECT_EQ(InitKind::STEAM_TRITON_QUIET, k->init) << pid;
        EXPECT_FALSE(isVerifiedFastLane(kValve, pid)) << pid;

        const usbparsers::Classification c = usbparsers::classifyDevice(kValve, pid, 0x03, 0, 0);
        EXPECT_EQ(Parser::STEAM_TRITON, c.parser) << pid;
        EXPECT_EQ(InitKind::STEAM_TRITON_QUIET, c.init) << pid;
    }
    EXPECT_STREQ("Valve Steam Controller (2026)",
                 usbparsers::lookupKnown(kValve, kTritonWired)->name);
}

// The Bluetooth LE identity speaks Valve's own GATT protocol and never reaches USB.
TEST(TritonClassify, TheBluetoothIdentityIsNoUsbRow) {
    EXPECT_EQ(nullptr, usbparsers::lookupKnown(kValve, kTritonBle));
}

TEST(TritonClassify, AdvertisesImuAndRumbleButNoTouchpad) {
    EXPECT_TRUE(parserHasImu(Parser::STEAM_TRITON));
    EXPECT_TRUE(parserHasRumble(Parser::STEAM_TRITON));
    EXPECT_FALSE(parserHasTouchpad(Parser::STEAM_TRITON));
    EXPECT_FALSE(parserFrameworkRumbleUnreliable(Parser::STEAM_TRITON));
    EXPECT_FALSE(usbparsers::parserStreamsUnprompted(Parser::STEAM_TRITON));
}

// Released, the pad goes back to its lizard keyboard and mouse once the firmware's watchdog
// fires, so no framework gamepad is worth waiting for.
TEST(TritonClassify, SettlesWithoutAFrameworkGamepad) {
    for (const uint16_t pid : kTritonUsbIdentities) {
        EXPECT_FALSE(modelExpectsFrameworkGamepad(kValve, pid)) << pid;
    }
}

TEST(TritonDecode, EveryStateReportIdDecodes) {
    for (const uint8_t id : {kTritonStateId, kTritonStateBleId, kTritonStateTimestampId}) {
        DeviceState s;
        EXPECT_TRUE(decodeTriton(tritonState(id), s)) << (int)id;
    }
}

TEST(TritonDecode, RejectsAShortStateAndEveryOtherReportId) {
    DeviceState s;
    auto shortState = tritonState();
    shortState.resize(kTritonStateBytes - 1);
    EXPECT_FALSE(decodeTriton(shortState, s));
    for (const uint8_t id :
         {kTritonBatteryId, kTritonWirelessXId, kTritonWirelessId, (uint8_t)0x01, (uint8_t)0x00}) {
        EXPECT_FALSE(decodeTriton(tritonState(id), s)) << (int)id;
    }
}

// SDL's mapping onto its gamepad buttons, which sit where XUSB's do: MENU is SDL's BACK and VIEW
// its START.
TEST(TritonDecode, EveryMappedButtonBitSetsItsXusbBit) {
    struct WideBitCase {
        uint32_t bit;
        uint16_t expected;
    };
    const WideBitCase cases[] = {
        {kTritonA, XUSB_A},
        {kTritonB, XUSB_B},
        {kTritonX, XUSB_X},
        {kTritonY, XUSB_Y},
        {kTritonL, XUSB_LB},
        {kTritonR, XUSB_RB},
        {kTritonMenu, XUSB_BACK},
        {kTritonView, XUSB_START},
        {kTritonSteam, XUSB_GUIDE},
        {kTritonL3, XUSB_THUMB_L},
        {kTritonR3, XUSB_THUMB_R},
        {kTritonDpadUp, XUSB_DPAD_UP},
        {kTritonDpadDown, XUSB_DPAD_DOWN},
        {kTritonDpadLeft, XUSB_DPAD_LEFT},
        {kTritonDpadRight, XUSB_DPAD_RIGHT},
    };
    for (const WideBitCase& c : cases) {
        DeviceState s;
        ASSERT_TRUE(decodeTriton(tritonState(kTritonStateId, c.bit), s));
        EXPECT_EQ(c.expected, s.wButtons) << "bit 0x" << std::hex << c.bit;
    }
}

TEST(TritonDecode, ButtonsWithNoXusbEquivalentPublishNoBit) {
    for (const uint32_t bit :
         {kTritonQam, kTritonR4, kTritonR5, kTritonL4, kTritonL5, kTritonRightStickTouch,
          kTritonRightPadTouch, kTritonRightPadClick, kTritonRightTriggerClick,
          kTritonLeftStickTouch, kTritonLeftPadTouch, kTritonLeftPadClick, kTritonLeftTriggerClick,
          kTritonRightGripTouch, kTritonLeftGripTouch}) {
        DeviceState s;
        ASSERT_TRUE(decodeTriton(tritonState(kTritonStateId, bit), s));
        EXPECT_EQ(0, s.wButtons) << "bit 0x" << std::hex << bit;
    }
}

// SDL reads the triggers as 0..32767 (it sends raw * 2 - 32768); the wire's are a byte.
TEST(TritonDecode, TriggersScaleTheirFifteenBitThrowOntoAByte) {
    auto r = tritonState();
    setLe16(r, kTritonLeftTrigger, 32767);
    setLe16(r, kTritonRightTrigger, 16384);
    DeviceState s;
    ASSERT_TRUE(decodeTriton(r, s));
    EXPECT_EQ(255, s.bLT);
    EXPECT_EQ(127, s.bRT);

    setLe16(r, kTritonLeftTrigger, 0);
    setLe16(r, kTritonRightTrigger, -300);
    ASSERT_TRUE(decodeTriton(r, s));
    EXPECT_EQ(0, s.bLT);
    EXPECT_EQ(0, s.bRT);
}

// SDL negates both Y axes into its down-positive convention, so the raw axes are up-positive,
// which is the wire's own.
TEST(TritonDecode, SticksAreLittleEndianAndUpPositiveLikeTheWire) {
    auto r = tritonState();
    setLe16(r, kTritonSticks, -8000);
    setLe16(r, kTritonSticks + 2, 12000);
    setLe16(r, kTritonSticks + 4, 32767);
    setLe16(r, kTritonSticks + 6, -32768);
    DeviceState s;
    ASSERT_TRUE(decodeTriton(r, s));
    EXPECT_EQ(-8000, s.sLX);
    EXPECT_EQ(12000, s.sLY);
    EXPECT_EQ(32767, s.sRX);
    EXPECT_EQ(-32768, s.sRY);
}

// SDL: gyro (x, z, -y) over +-2000 deg/s and accel (x, z, -y) over +-2 g; the wire is +-2000 deg/s
// and +-4 g on the same axes.
TEST(TritonDecode, ImuRotatesOntoTheWireAxisOrderAndScale) {
    auto r = tritonState();
    setTritonImu(r, kAnImuTick, {1000, 2000, 3000}, {4000, 5000, 6000});
    DeviceState s;
    ASSERT_TRUE(decodeTriton(r, s));
    EXPECT_TRUE(s.motionValid);
    EXPECT_EQ(3999, s.gyroX);
    EXPECT_EQ(5999, s.gyroY);
    EXPECT_EQ(-4999, s.gyroZ);
    EXPECT_EQ(499, s.accelX);
    EXPECT_EQ(1499, s.accelY);
    EXPECT_EQ(-999, s.accelZ);
}

// The timestamp report moves the trackpads and shortens the IMU timestamp, but the six IMU words
// land where the plain state report has them.
TEST(TritonDecode, TheTimestampReportReadsTheSameButtonsSticksAndImu) {
    auto plain = tritonState(kTritonStateId, kTritonA | kTritonDpadUp);
    auto stamped = tritonState(kTritonStateTimestampId, kTritonA | kTritonDpadUp);
    for (auto* r : {&plain, &stamped}) {
        setLe16(*r, kTritonSticks, 1234);
        setTritonImu(*r, kAnImuTick, {-100, 200, -300}, {400, -500, 600});
    }
    DeviceState fromPlain;
    DeviceState fromStamped;
    ASSERT_TRUE(decodeTriton(plain, fromPlain));
    ASSERT_TRUE(decodeTriton(stamped, fromStamped));
    EXPECT_EQ(fromPlain.wButtons, fromStamped.wButtons);
    EXPECT_EQ(fromPlain.sLX, fromStamped.sLX);
    EXPECT_EQ(fromPlain.gyroX, fromStamped.gyroX);
    EXPECT_EQ(fromPlain.gyroY, fromStamped.gyroY);
    EXPECT_EQ(fromPlain.gyroZ, fromStamped.gyroZ);
    EXPECT_EQ(fromPlain.accelZ, fromStamped.accelZ);
    EXPECT_TRUE(fromStamped.motionValid);
}

// SDL sends a sensor sample only for an IMU tick it has not seen (last_sensor_tick32 in
// HandleState, last_sensor_tick16 in HandleState_Timestamp).
TEST(TritonMotion, ARepeatedImuTickIsInputButNotMotion) {
    auto r = tritonState(kTritonStateId, kTritonA);
    setTritonImu(r, kAnImuTick, {1000, 2000, 3000}, {4000, 5000, 6000});
    ParserState st;
    DeviceState first;
    ASSERT_TRUE(decodeTriton(r, first, st));
    EXPECT_TRUE(first.motionValid);
    DeviceState again;
    ASSERT_TRUE(decodeTriton(r, again, st));
    EXPECT_EQ(XUSB_A, again.wButtons);
    EXPECT_FALSE(again.motionValid);
}

TEST(TritonMotion, ANewImuTickPublishesTheNextSample) {
    auto r = tritonState();
    setTritonImu(r, kAnImuTick, {1000, 2000, 3000}, {4000, 5000, 6000});
    ParserState st;
    DeviceState first;
    ASSERT_TRUE(decodeTriton(r, first, st));
    setTritonImu(r, kTheNextImuTick, {1000, 2000, 3000}, {8000, 5000, 6000});
    DeviceState next;
    ASSERT_TRUE(decodeTriton(r, next, st));
    EXPECT_TRUE(next.motionValid);
    EXPECT_EQ(7999, next.gyroX);
}

TEST(TritonMotion, AnAllZeroImuBlockWithANewTickIsStillPublished) {
    auto r = tritonState();
    setTritonImu(r, kAnImuTick, {0, 0, 0}, {0, 0, 0});
    DeviceState s;
    ASSERT_TRUE(decodeTriton(r, s));
    EXPECT_TRUE(s.motionValid);
    EXPECT_EQ(0, s.gyroX);
    EXPECT_EQ(0, s.accelZ);
}

// Tick 0 is where SDL's last tick starts, so a report at tick 0 carries no sample it has not seen.
TEST(TritonMotion, AnImuThatNeverTickedPublishesNoMotion) {
    auto r = tritonState(kTritonStateId, kTritonA);
    setTritonImu(r, 0, {1000, 2000, 3000}, {4000, 5000, 6000});
    DeviceState s;
    ASSERT_TRUE(decodeTriton(r, s));
    EXPECT_EQ(XUSB_A, s.wButtons);
    EXPECT_FALSE(s.motionValid);
}

TEST(TritonMotion, ThePlainReportsTickOnTheirThirtyTwoBitWordAtThirty) {
    for (const uint8_t id : {kTritonStateId, kTritonStateBleId}) {
        auto r = tritonState(id);
        setTritonImu(r, kAnImuTick, {1000, 2000, 3000}, {4000, 5000, 6000});
        ParserState st;
        DeviceState first;
        ASSERT_TRUE(decodeTriton(r, first, st)) << (int)id;
        EXPECT_TRUE(first.motionValid) << (int)id;
        setLe32(r, kTritonImuTick, kTheNextImuTick);
        DeviceState next;
        ASSERT_TRUE(decodeTriton(r, next, st)) << (int)id;
        EXPECT_TRUE(next.motionValid) << (int)id;
    }
}

TEST(TritonMotion, TheTimestampReportTicksOnItsSixteenBitWordAfterTheRightPadsPressure) {
    auto r = tritonState(kTritonStateTimestampId);
    setTritonImu(r, kAnImuTick, {1000, 2000, 3000}, {4000, 5000, 6000});
    ParserState st;
    DeviceState first;
    ASSERT_TRUE(decodeTriton(r, first, st));
    EXPECT_TRUE(first.motionValid);
    setLe16(r, kTritonTimestampRightPadPressure, 100);
    DeviceState pressed;
    ASSERT_TRUE(decodeTriton(r, pressed, st));
    EXPECT_FALSE(pressed.motionValid);
    setLe16(r, kTritonTimestampImuTick, (int16_t)kTheNextImuTick);
    DeviceState next;
    ASSERT_TRUE(decodeTriton(r, next, st));
    EXPECT_TRUE(next.motionValid);
}

TEST(TritonMotion, TheLastTickIsHeldPerPad) {
    auto r = tritonState();
    setTritonImu(r, kAnImuTick, {1000, 2000, 3000}, {4000, 5000, 6000});
    ParserState onePad;
    ParserState anotherPad;
    DeviceState onOne;
    ASSERT_TRUE(decodeTriton(r, onOne, onePad));
    DeviceState onTheOther;
    ASSERT_TRUE(decodeTriton(r, onTheOther, anotherPad));
    EXPECT_TRUE(onOne.motionValid);
    EXPECT_TRUE(onTheOther.motionValid);
}

TEST(TritonDecode, AStateReportCarriesNoBattery) {
    DeviceState s;
    ASSERT_TRUE(decodeTriton(tritonState(), s));
    EXPECT_FALSE(s.batteryValid);
}

// SDL's charge states: discharging, charging and done are readings; reset and source-validate are
// not, and SDL reports them as an unknown state with the percent beside it.
TEST(TritonBattery, ReadsTheChargeStateAndThePercent) {
    struct ChargeCase {
        uint8_t chargeState;
        uint8_t percent;
        uint8_t level;
        uint8_t status;
    };
    const ChargeCase cases[] = {
        {kTritonChargeDischarging, 57, 57, usbparsers::PAD_BATTERY_STATUS_DISCHARGING},
        {kTritonChargeCharging, 80, 80, usbparsers::PAD_BATTERY_STATUS_CHARGING},
        {kTritonChargeDone, 100, 100, usbparsers::PAD_BATTERY_STATUS_FULL},
        {kTritonChargeReset, 40, 40, usbparsers::PAD_BATTERY_STATUS_UNKNOWN},
        {kTritonChargeSourceValidate, 40, 40, usbparsers::PAD_BATTERY_STATUS_UNKNOWN},
        {(uint8_t)9, 40, 40, usbparsers::PAD_BATTERY_STATUS_UNKNOWN},
    };
    for (const ChargeCase& c : cases) {
        const auto r = tritonBattery(c.chargeState, c.percent);
        DeviceState s;
        ASSERT_TRUE(usbparsers::decodePadBatteryReport(Parser::STEAM_TRITON, r.data(), r.size(), s))
            << (int)c.chargeState;
        EXPECT_TRUE(s.batteryValid);
        EXPECT_EQ(c.level, s.batteryLevel) << (int)c.chargeState;
        EXPECT_EQ(c.status, s.batteryStatus) << (int)c.chargeState;
    }
}

TEST(TritonBattery, APercentPastAHundredIsAnUnknownLevel) {
    const auto r = tritonBattery(kTritonChargeDischarging, 101);
    DeviceState s;
    ASSERT_TRUE(usbparsers::decodePadBatteryReport(Parser::STEAM_TRITON, r.data(), r.size(), s));
    EXPECT_EQ(usbparsers::PAD_BATTERY_LEVEL_UNKNOWN, s.batteryLevel);
    EXPECT_EQ(usbparsers::PAD_BATTERY_STATUS_DISCHARGING, s.batteryStatus);
}

TEST(TritonBattery, AReportOfSdlsFifteenBytesReadsItsChargeStateAndLevel) {
    const auto r = tritonBattery(kTritonChargeCharging, 63);
    ASSERT_EQ(15u, r.size());
    DeviceState s;
    ASSERT_TRUE(usbparsers::decodePadBatteryReport(Parser::STEAM_TRITON, r.data(), r.size(), s));
    EXPECT_TRUE(s.batteryValid);
    EXPECT_EQ(63, s.batteryLevel);
    EXPECT_EQ(usbparsers::PAD_BATTERY_STATUS_CHARGING, s.batteryStatus);
}

TEST(TritonBattery, AReportPaddedPastSdlsFifteenBytesStillReads) {
    auto r = tritonBattery(kTritonChargeDischarging, 42);
    r.resize(kTritonBatteryPaddedBytes, 0);
    DeviceState s;
    ASSERT_TRUE(usbparsers::decodePadBatteryReport(Parser::STEAM_TRITON, r.data(), r.size(), s));
    EXPECT_EQ(42, s.batteryLevel);
    EXPECT_EQ(usbparsers::PAD_BATTERY_STATUS_DISCHARGING, s.batteryStatus);
}

// The battery arrives on its own report, so it must never publish as input (that would flash a
// neutral pad on the wire), and nothing else is read as a battery.
TEST(TritonBattery, IsNeverInputAndNothingElseReadsAsOne) {
    const auto battery = tritonBattery(kTritonChargeDischarging, 50);
    DeviceState s;
    EXPECT_FALSE(decodeTriton(battery, s));

    auto shortBattery = battery;
    shortBattery.resize(kTritonBatteryBytes - 1);
    EXPECT_FALSE(usbparsers::decodePadBatteryReport(Parser::STEAM_TRITON, shortBattery.data(),
                                                    shortBattery.size(), s));
    const auto state = tritonState();
    EXPECT_FALSE(
        usbparsers::decodePadBatteryReport(Parser::STEAM_TRITON, state.data(), state.size(), s));
    EXPECT_FALSE(s.batteryValid);

    for (const Parser p : {Parser::STEAM_CONTROLLER, Parser::DUALSENSE, Parser::NONE}) {
        EXPECT_FALSE(usbparsers::decodePadBatteryReport(p, battery.data(), battery.size(), s));
    }
}

TEST(TritonWireless, BothWirelessReportIdsClassifyConnectAndDisconnect) {
    for (const uint8_t id : {kTritonWirelessId, kTritonWirelessXId}) {
        const auto gone = tritonWireless(id, kTritonWirelessDisconnected);
        EXPECT_EQ(WirelessEvent::DISCONNECT,
                  checkWirelessEvent(Parser::STEAM_TRITON, gone.data(), gone.size()))
            << (int)id;
        const auto back = tritonWireless(id, kTritonWirelessConnected);
        EXPECT_EQ(WirelessEvent::CONNECT,
                  checkWirelessEvent(Parser::STEAM_TRITON, back.data(), back.size()))
            << (int)id;
    }
}

TEST(TritonWireless, AWirelessEventIsNeverInput) {
    const auto back = tritonWireless(kTritonWirelessId, kTritonWirelessConnected);
    DeviceState s;
    EXPECT_FALSE(decodeTriton(back, s));
}

TEST(TritonWireless, RejectsMalformedEventsAndOtherReports) {
    const std::vector<uint8_t> idOnly = {kTritonWirelessId};
    EXPECT_EQ(WirelessEvent::NONE,
              checkWirelessEvent(Parser::STEAM_TRITON, idOnly.data(), idOnly.size()));
    const auto unknownState = tritonWireless(kTritonWirelessId, 3);
    EXPECT_EQ(WirelessEvent::NONE,
              checkWirelessEvent(Parser::STEAM_TRITON, unknownState.data(), unknownState.size()));
    const auto state = tritonState(kTritonStateId, kTritonWirelessConnected);
    EXPECT_EQ(WirelessEvent::NONE,
              checkWirelessEvent(Parser::STEAM_TRITON, state.data(), state.size()));
    const auto battery = tritonBattery(kTritonWirelessConnected, kTritonWirelessConnected);
    EXPECT_EQ(WirelessEvent::NONE,
              checkWirelessEvent(Parser::STEAM_TRITON, battery.data(), battery.size()));
}

// The two Steam Controllers frame their wireless events differently; neither parser reads the
// other's.
TEST(TritonWireless, TheTwoSteamControllersNeverReadEachOthersEvents) {
    const auto triton = tritonWireless(kTritonWirelessId, kTritonWirelessConnected);
    EXPECT_EQ(WirelessEvent::NONE,
              checkWirelessEvent(Parser::STEAM_CONTROLLER, triton.data(), triton.size()));
    const auto steam2015 = steamWirelessEvent(0x02);
    EXPECT_EQ(WirelessEvent::NONE,
              checkWirelessEvent(Parser::STEAM_TRITON, steam2015.data(), steam2015.size()));
}

// SDL's DisableSteamTritonLizardMode and SetSensorsEnabled: a 64-byte feature report, id 1, then
// ID_SET_SETTINGS_VALUES, one three-byte setting, its number and its u16 LE value.
TEST(TritonSettings, TheLizardSwitchIsSdlsSettingsReport) {
    uint8_t out[usbparsers::TRITON_SETTINGS_REPORT_BYTES];
    memset(out, 0xEE, sizeof(out));
    ASSERT_EQ(64u, usbparsers::buildTritonSettingsReport(usbparsers::TritonSetting::LIZARD_OFF, out,
                                                         sizeof(out)));
    EXPECT_EQ(0x01, out[0]);
    EXPECT_EQ(0x87, out[1]);
    EXPECT_EQ(0x03, out[2]);
    EXPECT_EQ(0x09, out[3]); // SETTING_LIZARD_MODE
    EXPECT_EQ(0x00, out[4]); // LIZARD_MODE_OFF
    EXPECT_EQ(0x00, out[5]);
    for (size_t i = 6; i < sizeof(out); i++) EXPECT_EQ(0x00, out[i]) << i;
}

TEST(TritonSettings, TheImuModeStreamsRawAccelAndGyro) {
    uint8_t out[usbparsers::TRITON_SETTINGS_REPORT_BYTES];
    ASSERT_EQ(64u, usbparsers::buildTritonSettingsReport(usbparsers::TritonSetting::RAW_IMU, out,
                                                         sizeof(out)));
    EXPECT_EQ(0x01, out[0]);
    EXPECT_EQ(0x87, out[1]);
    EXPECT_EQ(0x03, out[2]);
    EXPECT_EQ(0x30, out[3]); // SETTING_IMU_MODE
    EXPECT_EQ(0x18, out[4]); // SEND_RAW_ACCEL | SEND_RAW_GYRO
    EXPECT_EQ(0x00, out[5]);
}

TEST(TritonSettings, RefusesASmallBuffer) {
    uint8_t out[usbparsers::TRITON_SETTINGS_REPORT_BYTES - 1];
    EXPECT_EQ(0u, usbparsers::buildTritonSettingsReport(usbparsers::TritonSetting::LIZARD_OFF, out,
                                                        sizeof(out)));
}

// SDL's HIDAPI_DriverSteamTriton_RumbleJoystick: output report 0x80, type and intensity zero, the
// low-frequency (strong) speed on the left and the high-frequency (weak) one on the right, each a
// u16 LE beside a zero gain.
TEST(TritonRumble, TheHapticRumbleReportCarriesBothSpeeds) {
    uint8_t out[64];
    ASSERT_EQ(10u, buildRumbleReport(Parser::STEAM_TRITON, 0x1234, 0xABCD, 7, out, sizeof(out)));
    const uint8_t expected[10] = {0x80, 0x00, 0x00, 0x00, 0x34, 0x12, 0x00, 0xCD, 0xAB, 0x00};
    EXPECT_EQ(0, memcmp(expected, out, sizeof(expected)));
}

TEST(TritonRumble, AStopIsAReportOfZeroSpeeds) {
    uint8_t out[64];
    ASSERT_EQ(10u, buildRumbleReport(Parser::STEAM_TRITON, 0, 0, 0, out, sizeof(out)));
    const uint8_t expected[10] = {0x80, 0, 0, 0, 0, 0, 0, 0, 0, 0};
    EXPECT_EQ(0, memcmp(expected, out, sizeof(expected)));
}

TEST(TritonRumble, RefusesASmallBuffer) {
    uint8_t out[9];
    EXPECT_EQ(0u, buildRumbleReport(Parser::STEAM_TRITON, 1, 1, 0, out, sizeof(out)));
}

// hidapi writes an output report on the interrupt OUT endpoint when the interface has one, and as
// a SET_REPORT on the control pipe when it does not; only a HID family can take the second.
TEST(OutputRoute, AnOutEndpointCarriesEveryFamilysReports) {
    using usbparsers::OutputRoute;
    using usbparsers::outputRouteFor;
    constexpr uint8_t kEpOut = 0x02;
    EXPECT_EQ(OutputRoute::INTERRUPT_OUT, outputRouteFor(Parser::XINPUT_360, kEpOut));
    EXPECT_EQ(OutputRoute::INTERRUPT_OUT, outputRouteFor(Parser::DUALSENSE, kEpOut));
    EXPECT_EQ(OutputRoute::INTERRUPT_OUT, outputRouteFor(Parser::STEAM_TRITON, kEpOut));
}

TEST(OutputRoute, WithoutOneOnlyTheTritonFallsBackToTheControlPipe) {
    using usbparsers::OutputRoute;
    using usbparsers::outputRouteFor;
    EXPECT_EQ(OutputRoute::CONTROL_SET_REPORT, outputRouteFor(Parser::STEAM_TRITON, 0));
    for (const Parser p :
         {Parser::XINPUT_360, Parser::XBOX_ONE_GIP, Parser::DUALSHOCK4, Parser::DUALSENSE,
          Parser::SWITCH_PRO_USB, Parser::STEAM_CONTROLLER, Parser::NONE}) {
        EXPECT_EQ(OutputRoute::NONE, outputRouteFor(p, 0)) << usbparsers::parserName(p);
    }
}

// SDL re-sends the lizard switch every 3 s (the firmware's watchdog turns lizard mode back on
// without it) and a running rumble every 40 ms (inside the firmware's ~50 ms motor timeout).
TEST(TritonRefresh, TheLizardSwitchIsOwedAtOnceUntilSentThenEveryThreeSeconds) {
    usbparsers::FeedbackState st;
    EXPECT_TRUE(usbparsers::refreshDue(Parser::STEAM_TRITON, st, 0).settings);
    EXPECT_EQ(0u, usbparsers::nextRefreshInMs(Parser::STEAM_TRITON, st, 0));

    st.settingsSentMs = 1000;
    EXPECT_FALSE(usbparsers::refreshDue(Parser::STEAM_TRITON, st, 3999).settings);
    EXPECT_TRUE(usbparsers::refreshDue(Parser::STEAM_TRITON, st, 4000).settings);
    EXPECT_EQ(3000u, usbparsers::nextRefreshInMs(Parser::STEAM_TRITON, st, 1000));
    EXPECT_EQ(1u, usbparsers::nextRefreshInMs(Parser::STEAM_TRITON, st, 3999));
    EXPECT_EQ(0u, usbparsers::nextRefreshInMs(Parser::STEAM_TRITON, st, 9000));
}

TEST(TritonRefresh, ARunningRumbleIsRepeatedEveryFortyMilliseconds) {
    usbparsers::FeedbackState st;
    st.settingsSentMs = 1000;
    st.weak = 1;
    st.rumbleSentMs = 1000;
    EXPECT_FALSE(usbparsers::refreshDue(Parser::STEAM_TRITON, st, 1039).rumble);
    EXPECT_TRUE(usbparsers::refreshDue(Parser::STEAM_TRITON, st, 1040).rumble);
    EXPECT_EQ(40u, usbparsers::nextRefreshInMs(Parser::STEAM_TRITON, st, 1000));
    EXPECT_EQ(15u, usbparsers::nextRefreshInMs(Parser::STEAM_TRITON, st, 1025));
}

TEST(TritonRefresh, AStoppedRumbleIsNeverRepeated) {
    usbparsers::FeedbackState st;
    st.settingsSentMs = 1000;
    EXPECT_FALSE(usbparsers::refreshDue(Parser::STEAM_TRITON, st, 1000).rumble);
    st.rumbleSentMs = 1000;
    EXPECT_FALSE(usbparsers::refreshDue(Parser::STEAM_TRITON, st, 999999).rumble);
    EXPECT_EQ(2000u, usbparsers::nextRefreshInMs(Parser::STEAM_TRITON, st, 2000));
}

TEST(TritonRefresh, NoOtherFamilyIsEverRefreshed) {
    usbparsers::FeedbackState st;
    st.strong = 0xFFFF;
    for (const Parser p : {Parser::STEAM_CONTROLLER, Parser::XINPUT_360, Parser::DUALSENSE,
                           Parser::SWITCH_PRO_USB, Parser::NONE}) {
        const usbparsers::RefreshDue due = usbparsers::refreshDue(p, st, 0);
        EXPECT_FALSE(due.settings) << usbparsers::parserName(p);
        EXPECT_FALSE(due.rumble) << usbparsers::parserName(p);
        EXPECT_EQ(usbparsers::NO_REFRESH, usbparsers::nextRefreshInMs(p, st, 0))
            << usbparsers::parserName(p);
    }
}

// A receiver slot with no controller on it owes nothing: SDL writes to a dongle's slot only while
// its controller is connected.
TEST(TritonRefresh, ASlotWhoseControllerIsAwayIsOwedNothing) {
    usbparsers::FeedbackState st;
    st.controllerAway = true;
    st.strong = 0xFFFF;
    const usbparsers::RefreshDue due = usbparsers::refreshDue(Parser::STEAM_TRITON, st, 0);
    EXPECT_FALSE(due.settings);
    EXPECT_FALSE(due.rumble);
    EXPECT_EQ(usbparsers::NO_REFRESH, usbparsers::nextRefreshInMs(Parser::STEAM_TRITON, st, 0));
}

// ---- a wireless receiver's slots --------------------------------------------------------------

using usbparsers::SlotLinkStep;
using usbparsers::slotLinkStep;

TEST(ReceiverSlot, AConnectEventBringsTheSlotsController) {
    EXPECT_EQ(SlotLinkStep::ARRIVES, slotLinkStep(false, WirelessEvent::CONNECT, false));
}

// The controller rebooted onto the slot: its settings are gone and go again.
TEST(ReceiverSlot, AConnectOnASlotThatHasItsControllerArrivesAgain) {
    EXPECT_EQ(SlotLinkStep::ARRIVES, slotLinkStep(true, WirelessEvent::CONNECT, false));
}

TEST(ReceiverSlot, ADisconnectEventTakesTheControllerAway) {
    EXPECT_EQ(SlotLinkStep::LEAVES, slotLinkStep(true, WirelessEvent::DISCONNECT, false));
}

TEST(ReceiverSlot, ADisconnectOnAnEmptySlotChangesNothing) {
    EXPECT_EQ(SlotLinkStep::STAYS, slotLinkStep(false, WirelessEvent::DISCONNECT, false));
}

TEST(ReceiverSlot, AStateReportFromAnEmptySlotBringsItsController) {
    EXPECT_EQ(SlotLinkStep::ARRIVES, slotLinkStep(false, WirelessEvent::NONE, true));
}

TEST(ReceiverSlot, AStateReportFromASlotThatHasItsControllerChangesNothing) {
    EXPECT_EQ(SlotLinkStep::STAYS, slotLinkStep(true, WirelessEvent::NONE, true));
}

TEST(ReceiverSlot, AReportThatIsNeitherChangesNothing) {
    EXPECT_EQ(SlotLinkStep::STAYS, slotLinkStep(false, WirelessEvent::NONE, false));
    EXPECT_EQ(SlotLinkStep::STAYS, slotLinkStep(true, WirelessEvent::NONE, false));
}

TEST(ReceiverSlot, APadOfItsOwnAlwaysHasItsController) {
    EXPECT_TRUE(usbparsers::controllerIsThere(false, false));
    EXPECT_TRUE(usbparsers::controllerIsThere(false, true));
}

// Only a slot whose controller is connected is configured or restored.
TEST(ReceiverSlot, ASlotHasItsControllerOnlyWhileItIsConnected) {
    EXPECT_TRUE(usbparsers::controllerIsThere(true, true));
    EXPECT_FALSE(usbparsers::controllerIsThere(true, false));
}

// SDL's HIDAPI_DriverSteam_InitDevice and hid-steam's steam_get_conn_status:
// ID_DONGLE_GET_WIRELESS_STATE, with no payload, in the 2015 framing {message id, payload length}.
TEST(ReceiverSlot, The2015ReceiversSlotIsAskedForItsWirelessState) {
    uint8_t out[16];
    memset(out, 0xEE, sizeof(out));
    ASSERT_EQ(2u,
              usbparsers::buildReceiverStateRequest(Parser::STEAM_CONTROLLER, out, sizeof(out)));
    EXPECT_EQ(0xB4, out[0]);
    EXPECT_EQ(0x00, out[1]);
}

// SDL asks the 2026 receivers nothing: a slot's controller is there once it reports.
TEST(ReceiverSlot, NoOtherFamilysSlotIsAsked) {
    uint8_t out[16];
    for (const Parser p :
         {Parser::STEAM_TRITON, Parser::XINPUT_360, Parser::DUALSENSE, Parser::NONE}) {
        EXPECT_EQ(0u, usbparsers::buildReceiverStateRequest(p, out, sizeof(out)))
            << usbparsers::parserName(p);
    }
}

TEST(ReceiverSlot, TheStateRequestRefusesASmallBuffer) {
    uint8_t out[1];
    EXPECT_EQ(0u,
              usbparsers::buildReceiverStateRequest(Parser::STEAM_CONTROLLER, out, sizeof(out)));
}

// ---- the pad's own battery ------------------------------------------------------------------

namespace {

DeviceState decodePs(Parser p, std::vector<uint8_t>& r) {
    DeviceState s;
    ParserState st;
    EXPECT_TRUE(decodeReport(p, r.data(), r.size(), s, &st));
    return s;
}

std::vector<uint8_t> psReport(size_t len, uint8_t buttonByte) {
    std::vector<uint8_t> r(len, 0);
    r[0] = 0x01;
    r[1] = r[2] = r[3] = r[4] = 128;
    r[buttonByte] = 0x08;
    return r;
}

} // namespace

TEST(Battery, DualShock4ReadsTheCableBitAndTheTenths) {
    // hid-playstation's status[0] at byte 30: tenths low, cable in bit 4. Off the cable 10 is the
    // 100 % cap; on it 10 still charges, 11 is full, and above that is a fault with no charge.
    auto r = psReport(64, 5);
    r[30] = 0x07;
    DeviceState s = decodePs(Parser::DUALSHOCK4, r);
    ASSERT_TRUE(s.batteryValid);
    EXPECT_EQ(75, s.batteryLevel);
    EXPECT_EQ(usbparsers::PAD_BATTERY_STATUS_DISCHARGING, s.batteryStatus);

    r[30] = 0x0A;
    s = decodePs(Parser::DUALSHOCK4, r);
    EXPECT_EQ(100, s.batteryLevel);
    EXPECT_EQ(usbparsers::PAD_BATTERY_STATUS_DISCHARGING, s.batteryStatus);

    r[30] = 0x13;
    s = decodePs(Parser::DUALSHOCK4, r);
    EXPECT_EQ(35, s.batteryLevel);
    EXPECT_EQ(usbparsers::PAD_BATTERY_STATUS_CHARGING, s.batteryStatus);

    r[30] = 0x1B;
    s = decodePs(Parser::DUALSHOCK4, r);
    EXPECT_EQ(100, s.batteryLevel);
    EXPECT_EQ(usbparsers::PAD_BATTERY_STATUS_FULL, s.batteryStatus);

    r[30] = 0x1D;
    s = decodePs(Parser::DUALSHOCK4, r);
    ASSERT_TRUE(s.batteryValid);
    EXPECT_EQ(usbparsers::PAD_BATTERY_LEVEL_UNKNOWN, s.batteryLevel);
    EXPECT_EQ(usbparsers::PAD_BATTERY_STATUS_UNKNOWN, s.batteryStatus);

    // A short report keeps whatever reading stood; it does not report unknown.
    r.resize(30);
    s = decodePs(Parser::DUALSHOCK4, r);
    EXPECT_FALSE(s.batteryValid);
}

TEST(Battery, DualSenseReadsTheStateNibbleAndTheTenths) {
    // hid-playstation's status at byte 53: tenths low, state high. 0/1/2 are
    // discharging/charging/full; 0xA, 0xB and 0xF are faults.
    auto r = psReport(64, 8);
    r[53] = 0x04;
    DeviceState s = decodePs(Parser::DUALSENSE, r);
    ASSERT_TRUE(s.batteryValid);
    EXPECT_EQ(45, s.batteryLevel);
    EXPECT_EQ(usbparsers::PAD_BATTERY_STATUS_DISCHARGING, s.batteryStatus);

    r[53] = 0x18;
    s = decodePs(Parser::DUALSENSE, r);
    EXPECT_EQ(85, s.batteryLevel);
    EXPECT_EQ(usbparsers::PAD_BATTERY_STATUS_CHARGING, s.batteryStatus);

    r[53] = 0x1A; // the tenths cannot exceed 100
    s = decodePs(Parser::DUALSENSE, r);
    EXPECT_EQ(100, s.batteryLevel);

    r[53] = 0x2F; // full: the nibble is ignored
    s = decodePs(Parser::DUALSENSE, r);
    EXPECT_EQ(100, s.batteryLevel);
    EXPECT_EQ(usbparsers::PAD_BATTERY_STATUS_FULL, s.batteryStatus);

    for (uint8_t fault : {(uint8_t)0xA5, (uint8_t)0xB5, (uint8_t)0xF5}) {
        r[53] = fault;
        s = decodePs(Parser::DUALSENSE, r);
        ASSERT_TRUE(s.batteryValid) << "state byte " << (int)fault;
        EXPECT_EQ(usbparsers::PAD_BATTERY_LEVEL_UNKNOWN, s.batteryLevel);
        EXPECT_EQ(usbparsers::PAD_BATTERY_STATUS_UNKNOWN, s.batteryStatus);
    }

    r.resize(53);
    s = decodePs(Parser::DUALSENSE, r);
    EXPECT_FALSE(s.batteryValid);
}

TEST(Battery, SwitchProReadsTheFiveStepsTheChargingBitAndTheHostBit) {
    // hid-nintendo's bat_con at byte 2: the step in bits 7..5, charging in bit 4, host power in
    // bit 0. Every 0x30 report is long enough to carry it.
    auto r = switchReport(12);
    r[2] = 0x40; // step 2 (low), on battery
    DeviceState s = decodePs(Parser::SWITCH_PRO_USB, r);
    ASSERT_TRUE(s.batteryValid);
    EXPECT_EQ(50, s.batteryLevel);
    EXPECT_EQ(usbparsers::PAD_BATTERY_STATUS_DISCHARGING, s.batteryStatus);

    r[2] = 0x71; // step 3 (medium), host-powered, charging
    s = decodePs(Parser::SWITCH_PRO_USB, r);
    EXPECT_EQ(75, s.batteryLevel);
    EXPECT_EQ(usbparsers::PAD_BATTERY_STATUS_CHARGING, s.batteryStatus);

    r[2] = 0x81; // step 4 (full), host-powered, not charging: full
    s = decodePs(Parser::SWITCH_PRO_USB, r);
    EXPECT_EQ(100, s.batteryLevel);
    EXPECT_EQ(usbparsers::PAD_BATTERY_STATUS_FULL, s.batteryStatus);

    r[2] = 0x80; // full on battery is a full reading, discharging
    s = decodePs(Parser::SWITCH_PRO_USB, r);
    EXPECT_EQ(100, s.batteryLevel);
    EXPECT_EQ(usbparsers::PAD_BATTERY_STATUS_DISCHARGING, s.batteryStatus);

    r[2] = 0x00; // empty
    s = decodePs(Parser::SWITCH_PRO_USB, r);
    EXPECT_EQ(5, s.batteryLevel);

    r[2] = 0xA0; // a step the pad never reports
    s = decodePs(Parser::SWITCH_PRO_USB, r);
    EXPECT_EQ(usbparsers::PAD_BATTERY_LEVEL_UNKNOWN, s.batteryLevel);
}

TEST(Battery, FamiliesWithoutAChargeInTheReportLeaveItUntouched) {
    std::vector<uint8_t> r(20, 0);
    r[0] = 0x03; // Stadia
    DeviceState s;
    ParserState st;
    ASSERT_TRUE(decodeReport(Parser::STADIA, r.data(), r.size(), s, &st));
    EXPECT_FALSE(s.batteryValid);
    EXPECT_EQ(usbparsers::PAD_BATTERY_LEVEL_UNKNOWN, s.batteryLevel);
    EXPECT_EQ(usbparsers::PAD_BATTERY_STATUS_UNKNOWN, s.batteryStatus);
}

// ---- every button bit, per family ----------------------------------------------------------

namespace {

struct BitCase {
    uint8_t bit;
    uint16_t expected;
};

// Xbox 360 wired input report: type 0x00, length 0x14, no buttons, sticks at rest.
std::vector<uint8_t> x360Report() {
    std::vector<uint8_t> r(20, 0);
    r[1] = 0x14;
    return r;
}

// Stadia report 0x03 with centred sticks and a neutral hat.
std::vector<uint8_t> stadiaReport() {
    std::vector<uint8_t> r(11, 0);
    r[0] = 0x03;
    r[1] = r[2] = r[3] = r[4] = 128;
    r[7] = 0x08;
    return r;
}

// The fixed-offset generic HID shape: four centred stick bytes, then hat/buttons, then buttons.
std::vector<uint8_t> hidFallbackReport() {
    std::vector<uint8_t> r(7, 0);
    r[0] = r[1] = r[2] = r[3] = 128;
    r[4] = 0x08;
    return r;
}

DeviceState decodeFresh(const Parser p, const std::vector<uint8_t>& r) {
    DeviceState s;
    ParserState st;
    EXPECT_TRUE(decodeReport(p, r.data(), r.size(), s, &st));
    return s;
}

// Decodes `r` once per case with `keep | c.bit` in byte `at`, expecting exactly `c.expected`.
void expectEachBitAlone(const Parser p, std::vector<uint8_t> r, const size_t at, const uint8_t keep,
                        const std::vector<BitCase>& cases) {
    for (const BitCase& c : cases) {
        r[at] = (uint8_t)(keep | c.bit);
        const DeviceState s = decodeFresh(p, r);
        EXPECT_EQ(c.expected, s.wButtons) << "byte " << at << " bit " << (int)c.bit;
    }
}

// Every hat value a nibble can hold: the eight directions, then centred.
void expectHatNibbleFoldsIntoTheDpad(const Parser p, std::vector<uint8_t> r, const size_t at) {
    for (uint8_t hat = 0; hat < 16; hat++) {
        r[at] = (uint8_t)((r[at] & 0xF0) | hat);
        const DeviceState s = decodeFresh(p, r);
        EXPECT_EQ(gamepad::hatDirectionBits(hat), s.wButtons) << "hat " << (int)hat;
    }
}

} // namespace

TEST(Xbox360Decode, EveryButtonBitMapsToItsXusbBit) {
    expectEachBitAlone(Parser::XINPUT_360, x360Report(), 2, 0x00,
                       {{0x01, XUSB_DPAD_UP},
                        {0x02, XUSB_DPAD_DOWN},
                        {0x04, XUSB_DPAD_LEFT},
                        {0x08, XUSB_DPAD_RIGHT},
                        {0x10, XUSB_START},
                        {0x20, XUSB_BACK},
                        {0x40, XUSB_THUMB_L},
                        {0x80, XUSB_THUMB_R}});
    expectEachBitAlone(Parser::XINPUT_360, x360Report(), 3, 0x00,
                       {{0x01, XUSB_LB},
                        {0x02, XUSB_RB},
                        {0x04, XUSB_GUIDE},
                        {0x10, XUSB_A},
                        {0x20, XUSB_B},
                        {0x40, XUSB_X},
                        {0x80, XUSB_Y}});
}

TEST(Xbox360Decode, TriggersAreRawBytesAndSticksAreLittleEndian) {
    auto r = x360Report();
    r[4] = 0x7F;
    r[5] = 0xFF;
    setLe16(r, 6, -32768);
    setLe16(r, 8, 32767);
    setLe16(r, 10, -1);
    setLe16(r, 12, 1);
    const DeviceState s = decodeFresh(Parser::XINPUT_360, r);
    EXPECT_EQ(127, s.bLT);
    EXPECT_EQ(255, s.bRT);
    EXPECT_EQ(-32768, s.sLX);
    EXPECT_EQ(32767, s.sLY);
    EXPECT_EQ(-1, s.sRX);
    EXPECT_EQ(1, s.sRY);
}

TEST(Xbox360Decode, ShortOrNonInputReportsAreRejected) {
    DeviceState s;
    ParserState st;
    auto tooShort = x360Report();
    tooShort.resize(13);
    EXPECT_FALSE(decodeReport(Parser::XINPUT_360, tooShort.data(), tooShort.size(), s, &st));
    auto wrongType = x360Report();
    wrongType[0] = 0x01;
    EXPECT_FALSE(decodeReport(Parser::XINPUT_360, wrongType.data(), wrongType.size(), s, &st));
}

TEST(XboxGip, EveryButtonBitMapsToItsXusbBit) {
    expectEachBitAlone(Parser::XBOX_ONE_GIP, gipMain(), 4, 0x00,
                       {{0x04, XUSB_START},
                        {0x08, XUSB_BACK},
                        {0x10, XUSB_A},
                        {0x20, XUSB_B},
                        {0x40, XUSB_X},
                        {0x80, XUSB_Y}});
    expectEachBitAlone(Parser::XBOX_ONE_GIP, gipMain(), 5, 0x00,
                       {{0x01, XUSB_DPAD_UP},
                        {0x02, XUSB_DPAD_DOWN},
                        {0x04, XUSB_DPAD_LEFT},
                        {0x08, XUSB_DPAD_RIGHT},
                        {0x10, XUSB_LB},
                        {0x20, XUSB_RB},
                        {0x40, XUSB_THUMB_L},
                        {0x80, XUSB_THUMB_R}});
}

TEST(XboxGip, TenBitTriggersScaleToEightBits) {
    auto r = gipMain();
    setLe16(r, 6, 1023);
    setLe16(r, 8, 512);
    const DeviceState s = decodeFresh(Parser::XBOX_ONE_GIP, r);
    EXPECT_EQ(255, s.bLT);
    EXPECT_EQ(127, s.bRT);
}

TEST(XboxGip, ATriggerAboveTenBitsSaturates) {
    auto r = gipMain();
    r[6] = 0xFF;
    r[7] = 0xFF;
    const DeviceState s = decodeFresh(Parser::XBOX_ONE_GIP, r);
    EXPECT_EQ(255, s.bLT);
    EXPECT_EQ(0, s.bRT);
}

TEST(XboxGip, RightStickReadsLittleEndianAtFourteen) {
    auto r = gipMain();
    setLe16(r, 12, -2);
    setLe16(r, 14, -1234);
    setLe16(r, 16, 4321);
    const DeviceState s = decodeFresh(Parser::XBOX_ONE_GIP, r);
    EXPECT_EQ(-2, s.sLY);
    EXPECT_EQ(-1234, s.sRX);
    EXPECT_EQ(4321, s.sRY);
}

TEST(XboxGip, ShortMainReportIsRejected) {
    ParserState st;
    DeviceState s;
    auto r = gipMain();
    r.resize(17);
    EXPECT_FALSE(decodeXbox(r, s, st));
}

TEST(PsDecode, FaceRowMapsToXinputPositions) {
    expectEachBitAlone(Parser::DUALSHOCK4, psReport(10, 5), 5, 0x08,
                       {{0x10, XUSB_X}, {0x20, XUSB_A}, {0x40, XUSB_B}, {0x80, XUSB_Y}});
}

TEST(PsDecode, ShoulderByteMapsBumpersMenusAndSticks) {
    expectEachBitAlone(Parser::DUALSHOCK4, psReport(10, 5), 6, 0x00,
                       {{0x01, XUSB_LB},
                        {0x02, XUSB_RB},
                        {0x10, XUSB_BACK},
                        {0x20, XUSB_START},
                        {0x40, XUSB_THUMB_L},
                        {0x80, XUSB_THUMB_R}});
}

TEST(PsDecode, HatDiagonalsSetTwoDpadBits) {
    expectHatNibbleFoldsIntoTheDpad(Parser::DUALSHOCK4, psReport(10, 5), 5);
}

TEST(PsDecode, DualSenseButtonBytesSitAtEightAndNine) {
    expectEachBitAlone(Parser::DUALSENSE, psReport(11, 8), 8, 0x08,
                       {{0x20, XUSB_A}, {0x80, XUSB_Y}});
    expectEachBitAlone(Parser::DUALSENSE, psReport(11, 8), 9, 0x00,
                       {{0x02, XUSB_RB}, {0x40, XUSB_THUMB_L}});
    expectHatNibbleFoldsIntoTheDpad(Parser::DUALSENSE, psReport(11, 8), 8);
}

TEST(PsDecode, WrongReportIdIsRejected) {
    DeviceState s;
    ParserState st;
    auto ds4 = psReport(10, 5);
    ds4[0] = 0x02;
    EXPECT_FALSE(decodeReport(Parser::DUALSHOCK4, ds4.data(), ds4.size(), s, &st));
    auto ds5 = psReport(11, 8);
    ds5[0] = 0x02;
    EXPECT_FALSE(decodeReport(Parser::DUALSENSE, ds5.data(), ds5.size(), s, &st));
}

// ---- Switch Pro: buttons, digital triggers and the auto-ranged sticks ------------------------

namespace {

constexpr uint16_t kSwitchCentre = 2048;
constexpr uint16_t kSwitchDeadzone = 320;
constexpr uint16_t kSwitchRawMax = 4095;
constexpr uint16_t kSwitchSeedReach = 1000;

// Packs the four 12-bit stick values the way report 0x30 lays them out: two axes per three bytes.
void setSwitchSticks(std::vector<uint8_t>& r, const uint16_t lx, const uint16_t ly,
                     const uint16_t rx, const uint16_t ry) {
    r[6] = (uint8_t)(lx & 0xFF);
    r[7] = (uint8_t)(((lx >> 8) & 0x0F) | ((ly & 0x0F) << 4));
    r[8] = (uint8_t)(ly >> 4);
    r[9] = (uint8_t)(rx & 0xFF);
    r[10] = (uint8_t)(((rx >> 8) & 0x0F) | ((ry & 0x0F) << 4));
    r[11] = (uint8_t)(ry >> 4);
}

DeviceState decodeSwitchSticks(ParserState& st, const uint16_t lx, const uint16_t ly,
                               const uint16_t rx, const uint16_t ry) {
    auto r = switchReport(12);
    setSwitchSticks(r, lx, ly, rx, ry);
    DeviceState s;
    EXPECT_TRUE(decodeReport(Parser::SWITCH_PRO_USB, r.data(), r.size(), s, &st));
    return s;
}

} // namespace

TEST(SwitchProDecode, ButtonsMapByPhysicalPosition) {
    expectEachBitAlone(
        Parser::SWITCH_PRO_USB, switchReport(12), 3, 0x00,
        {{0x01, XUSB_X}, {0x02, XUSB_Y}, {0x04, XUSB_A}, {0x08, XUSB_B}, {0x40, XUSB_RB}});
    expectEachBitAlone(
        Parser::SWITCH_PRO_USB, switchReport(12), 4, 0x00,
        {{0x01, XUSB_BACK}, {0x02, XUSB_START}, {0x04, XUSB_THUMB_R}, {0x08, XUSB_THUMB_L}});
    expectEachBitAlone(Parser::SWITCH_PRO_USB, switchReport(12), 5, 0x00,
                       {{0x01, XUSB_DPAD_DOWN},
                        {0x02, XUSB_DPAD_UP},
                        {0x04, XUSB_DPAD_RIGHT},
                        {0x08, XUSB_DPAD_LEFT},
                        {0x40, XUSB_LB}});
}

TEST(SwitchProDecode, ZlZrAreDigitalTriggers) {
    auto r = switchReport(12);
    r[5] = 0x80;
    DeviceState zl = decodeFresh(Parser::SWITCH_PRO_USB, r);
    EXPECT_EQ(255, zl.bLT);
    EXPECT_EQ(0, zl.bRT);
    EXPECT_EQ(0, zl.wButtons);

    r[5] = 0x00;
    r[3] = 0x80;
    DeviceState zr = decodeFresh(Parser::SWITCH_PRO_USB, r);
    EXPECT_EQ(0, zr.bLT);
    EXPECT_EQ(255, zr.bRT);
    EXPECT_EQ(0, zr.wButtons);

    r[3] = 0x00;
    DeviceState released = decodeFresh(Parser::SWITCH_PRO_USB, r);
    EXPECT_EQ(0, released.bLT);
    EXPECT_EQ(0, released.bRT);
}

TEST(SwitchProDecode, WrongReportIdOrShortReportIsRejected) {
    DeviceState s;
    ParserState st;
    auto wrongId = switchReport(12);
    wrongId[0] = 0x21;
    EXPECT_FALSE(decodeReport(Parser::SWITCH_PRO_USB, wrongId.data(), wrongId.size(), s, &st));
    auto tooShort = switchReport(11);
    EXPECT_FALSE(decodeReport(Parser::SWITCH_PRO_USB, tooShort.data(), tooShort.size(), s, &st));
}

TEST(SwitchN64Decode, TheOnlinePadIsAVerifiedModelOnTheSwitchHandshake) {
    const usbparsers::Classification c = usbparsers::classifyDevice(0x057E, 0x2019, 3, 0, 0);
    EXPECT_EQ(Parser::SWITCH_N64_USB, c.parser);
    EXPECT_EQ(InitKind::SWITCH_PRO_HANDSHAKE, c.init);
    EXPECT_STREQ("Nintendo N64 Online Controller", c.name);
    EXPECT_TRUE(isVerifiedFastLane(0x057E, 0x2019));
}

TEST(SwitchN64Decode, ButtonsMapToTheirN64Roles) {
    expectEachBitAlone(Parser::SWITCH_N64_USB, switchReport(12), 3, 0x00,
                       {{0x04, XUSB_B}, {0x08, XUSB_A}, {0x40, XUSB_RB}});
    expectEachBitAlone(Parser::SWITCH_N64_USB, switchReport(12), 4, 0x00,
                       {{0x02, XUSB_START}, {0x10, XUSB_GUIDE}, {0x20, XUSB_BACK}});
    expectEachBitAlone(Parser::SWITCH_N64_USB, switchReport(12), 5, 0x00,
                       {{0x01, XUSB_DPAD_DOWN},
                        {0x02, XUSB_DPAD_UP},
                        {0x04, XUSB_DPAD_RIGHT},
                        {0x08, XUSB_DPAD_LEFT},
                        {0x40, XUSB_LB}});
}

TEST(SwitchN64Decode, ZAndZrAreTheDigitalTriggers) {
    auto r = switchReport(12);
    r[5] = 0x80;
    const DeviceState z = decodeFresh(Parser::SWITCH_N64_USB, r);
    EXPECT_EQ(255, z.bLT);
    EXPECT_EQ(0, z.bRT);
    EXPECT_EQ(0, z.wButtons);

    r[5] = 0x00;
    r[4] = 0x08;
    const DeviceState zr = decodeFresh(Parser::SWITCH_N64_USB, r);
    EXPECT_EQ(0, zr.bLT);
    EXPECT_EQ(255, zr.bRT);
    EXPECT_EQ(0, zr.wButtons);
}

TEST(SwitchN64Decode, CButtonsRailTheRightStick) {
    auto r = switchReport(12);
    r[3] = 0x01;
    DeviceState s = decodeFresh(Parser::SWITCH_N64_USB, r);
    EXPECT_EQ(32767, s.sRY);
    EXPECT_EQ(0, s.sRX);
    EXPECT_EQ(0, s.wButtons);

    r[3] = 0x80;
    s = decodeFresh(Parser::SWITCH_N64_USB, r);
    EXPECT_EQ(-32767, s.sRY);
    EXPECT_EQ(0, s.bRT);

    r[3] = 0x02;
    s = decodeFresh(Parser::SWITCH_N64_USB, r);
    EXPECT_EQ(-32767, s.sRX);
    EXPECT_EQ(0, s.sRY);

    r[3] = 0x00;
    r[4] = 0x01;
    s = decodeFresh(Parser::SWITCH_N64_USB, r);
    EXPECT_EQ(32767, s.sRX);
    EXPECT_EQ(0, s.wButtons);

    r[3] = 0x81;
    r[4] = 0x00;
    s = decodeFresh(Parser::SWITCH_N64_USB, r);
    EXPECT_EQ(0, s.sRY);
}

TEST(SwitchN64Decode, OnlyTheLeftStickIsReadAndThereIsNoMotion) {
    ParserState st;
    auto r = switchReport(49);
    setSwitchSticks(r, kSwitchRawMax, kSwitchCentre, kSwitchRawMax, kSwitchRawMax);
    r[13] = 0x34;
    r[14] = 0x12;
    DeviceState s;
    ASSERT_TRUE(decodeReport(Parser::SWITCH_N64_USB, r.data(), r.size(), s, &st));
    EXPECT_GT(s.sLX, 0);
    EXPECT_EQ(0, s.sLY);
    EXPECT_EQ(0, s.sRX);
    EXPECT_EQ(0, s.sRY);
    EXPECT_FALSE(s.motionValid);
}

TEST(SwitchN64Decode, BatteryReadsLikeTheProController) {
    auto r = switchReport(12);
    r[2] = 0x80;
    const DeviceState s = decodeFresh(Parser::SWITCH_N64_USB, r);
    EXPECT_TRUE(s.batteryValid);
    EXPECT_EQ(100, s.batteryLevel);
}

TEST(SwitchN64Decode, WrongReportIdOrShortReportIsRejected) {
    DeviceState s;
    ParserState st;
    auto wrongId = switchReport(12);
    wrongId[0] = 0x21;
    EXPECT_FALSE(decodeReport(Parser::SWITCH_N64_USB, wrongId.data(), wrongId.size(), s, &st));
    auto tooShort = switchReport(11);
    EXPECT_FALSE(decodeReport(Parser::SWITCH_N64_USB, tooShort.data(), tooShort.size(), s, &st));
}

TEST(SwitchN64Feedback, SharesTheSwitchRumbleAndPlayerLedReports) {
    uint8_t pro[16];
    uint8_t n64[16];
    ASSERT_EQ(10u, buildRumbleReport(Parser::SWITCH_PRO_USB, 0x8000, 0x4000, 5, pro, sizeof(pro)));
    ASSERT_EQ(10u, buildRumbleReport(Parser::SWITCH_N64_USB, 0x8000, 0x4000, 5, n64, sizeof(n64)));
    EXPECT_EQ(0, memcmp(pro, n64, 10));

    usbparsers::FeedbackState st;
    uint8_t proLeds[16];
    uint8_t n64Leds[16];
    const size_t proLen = usbparsers::buildPlayerLedsReport(Parser::SWITCH_PRO_USB, st, 0x03, 1,
                                                            proLeds, sizeof(proLeds));
    const size_t n64Len = usbparsers::buildPlayerLedsReport(Parser::SWITCH_N64_USB, st, 0x03, 1,
                                                            n64Leds, sizeof(n64Leds));
    ASSERT_NE(0u, proLen);
    ASSERT_EQ(proLen, n64Len);
    EXPECT_EQ(0, memcmp(proLeds, n64Leds, proLen));
}

TEST(SwitchProSticks, TwelveBitPackingUnpacksBothAxes) {
    ParserState st;
    const DeviceState s = decodeSwitchSticks(st, kSwitchRawMax, 0, kSwitchCentre, kSwitchRawMax);
    EXPECT_EQ(32767, s.sLX);
    EXPECT_EQ(-32768, s.sLY);
    EXPECT_EQ(0, s.sRX);
    EXPECT_EQ(32767, s.sRY);
}

TEST(SwitchProSticks, RestingOffsetInsideTheDeadzoneReadsCentre) {
    ParserState st;
    const DeviceState s =
        decodeSwitchSticks(st, kSwitchCentre + kSwitchDeadzone, kSwitchCentre - kSwitchDeadzone,
                           kSwitchCentre, kSwitchCentre + kSwitchDeadzone + 1);
    EXPECT_EQ(0, s.sLX);
    EXPECT_EQ(0, s.sLY);
    EXPECT_EQ(0, s.sRX);
    // One count past the deadzone against the seed reach: 1 * 32767 / 1000.
    EXPECT_EQ(32, s.sRY);
}

TEST(SwitchProSticks, ALearnedReachStretchesToTheRail) {
    ParserState st;
    const uint16_t partial = kSwitchCentre + kSwitchDeadzone + 500;
    // Untaught: 500 counts against the 1000-count seed.
    EXPECT_EQ(16383,
              decodeSwitchSticks(st, partial, kSwitchCentre, kSwitchCentre, kSwitchCentre).sLX);
    // The full throw teaches the side its reach (4095 - 2048 - 320 = 1727) and hits the rail.
    EXPECT_EQ(
        32767,
        decodeSwitchSticks(st, kSwitchRawMax, kSwitchCentre, kSwitchCentre, kSwitchCentre).sLX);
    EXPECT_EQ(1727, st.lx.posReach);
    // The same 500 counts now read against the learned reach.
    EXPECT_EQ(9486,
              decodeSwitchSticks(st, partial, kSwitchCentre, kSwitchCentre, kSwitchCentre).sLX);
}

TEST(SwitchProSticks, EachSideLearnsItsOwnReach) {
    ParserState st;
    EXPECT_EQ(
        32767,
        decodeSwitchSticks(st, kSwitchRawMax, kSwitchCentre, kSwitchCentre, kSwitchCentre).sLX);
    EXPECT_EQ(1727, st.lx.posReach);
    EXPECT_EQ(kSwitchSeedReach, st.lx.negReach);
    // The pull side still scales against its own untaught seed...
    EXPECT_EQ(-16384, decodeSwitchSticks(st, kSwitchCentre - kSwitchDeadzone - 500, kSwitchCentre,
                                         kSwitchCentre, kSwitchCentre)
                          .sLX);
    // ...and the other axes were never touched by the left X sweep.
    EXPECT_EQ(kSwitchSeedReach, st.ly.posReach);
    EXPECT_EQ(kSwitchSeedReach, st.rx.posReach);
    EXPECT_EQ(kSwitchSeedReach, st.ry.posReach);
}

// ---- Stadia --------------------------------------------------------------------------------

TEST(StadiaDecode, FaceButtonsMapByPosition) {
    expectEachBitAlone(Parser::STADIA, stadiaReport(), 8, 0x00,
                       {{0x40, XUSB_A},
                        {0x20, XUSB_B},
                        {0x10, XUSB_X},
                        {0x08, XUSB_Y},
                        {0x04, XUSB_LB},
                        {0x02, XUSB_RB}});
}

TEST(StadiaDecode, SystemByteMapsStartBackAndStickClicks) {
    expectEachBitAlone(
        Parser::STADIA, stadiaReport(), 9, 0x00,
        {{0x80, XUSB_START}, {0x40, XUSB_BACK}, {0x20, XUSB_THUMB_L}, {0x10, XUSB_THUMB_R}});
}

TEST(StadiaDecode, HatNibbleFoldsIntoTheDpad) {
    expectHatNibbleFoldsIntoTheDpad(Parser::STADIA, stadiaReport(), 7);
}

TEST(StadiaDecode, TriggersAndSticksDecode) {
    auto r = stadiaReport();
    r[1] = 0xFF;
    r[2] = 0x00;
    r[3] = 0x00;
    r[4] = 0xFF;
    r[5] = 0x80;
    r[6] = 0xFF;
    const DeviceState s = decodeFresh(Parser::STADIA, r);
    EXPECT_EQ(32639, s.sLX);
    EXPECT_EQ(32767, s.sLY);
    EXPECT_EQ(-32768, s.sRX);
    EXPECT_EQ(-32639, s.sRY);
    EXPECT_EQ(128, s.bLT);
    EXPECT_EQ(255, s.bRT);
}

TEST(StadiaDecode, WrongReportIdOrShortReportIsRejected) {
    DeviceState s;
    ParserState st;
    auto wrongId = stadiaReport();
    wrongId[0] = 0x01;
    EXPECT_FALSE(decodeReport(Parser::STADIA, wrongId.data(), wrongId.size(), s, &st));
    auto tooShort = stadiaReport();
    tooShort.resize(10);
    EXPECT_FALSE(decodeReport(Parser::STADIA, tooShort.data(), tooShort.size(), s, &st));
}

// ---- generic HID: the fixed-offset fallback when no descriptor parsed ------------------------

TEST(GenericHidDecode, FixedOffsetFallbackDecodesSticksButtonsAndHat) {
    auto r = hidFallbackReport();
    r[0] = 0xFF;
    r[1] = 0x00;
    r[4] = 0x12;
    r[5] = 0x01;
    DeviceState s;
    ParserState st; // no descriptor parsed: hidLayout stays invalid
    ASSERT_TRUE(decodeReport(Parser::GENERIC_HID_GAMEPAD, r.data(), r.size(), s, &st));
    EXPECT_EQ(32639, s.sLX);
    EXPECT_EQ(32767, s.sLY);
    EXPECT_EQ(0, s.sRX);
    EXPECT_EQ(0, s.sRY);
    EXPECT_EQ(XUSB_A | XUSB_LB | XUSB_DPAD_RIGHT, s.wButtons);

    DeviceState stateless;
    ASSERT_TRUE(decodeReport(Parser::GENERIC_HID_GAMEPAD, r.data(), r.size(), stateless, nullptr));
    EXPECT_EQ(s.wButtons, stateless.wButtons);
    EXPECT_EQ(s.sLX, stateless.sLX);
}

TEST(GenericHidDecode, EveryFallbackButtonBitMapsToItsXusbBit) {
    expectEachBitAlone(Parser::GENERIC_HID_GAMEPAD, hidFallbackReport(), 4, 0x08,
                       {{0x10, XUSB_A}, {0x20, XUSB_B}, {0x40, XUSB_X}, {0x80, XUSB_Y}});
    expectEachBitAlone(Parser::GENERIC_HID_GAMEPAD, hidFallbackReport(), 5, 0x00,
                       {{0x01, XUSB_LB},
                        {0x02, XUSB_RB},
                        {0x04, XUSB_BACK},
                        {0x08, XUSB_START},
                        {0x10, XUSB_THUMB_L},
                        {0x20, XUSB_THUMB_R}});
}

TEST(GenericHidDecode, TheFallbackHatNibbleFoldsIntoTheDpad) {
    expectHatNibbleFoldsIntoTheDpad(Parser::GENERIC_HID_GAMEPAD, hidFallbackReport(), 4);
}

TEST(GenericHidDecode, Bits6And7OfByte5AreTheTriggers) {
    auto r = hidFallbackReport();
    r[5] = 0x40;
    DeviceState lt = decodeFresh(Parser::GENERIC_HID_GAMEPAD, r);
    EXPECT_EQ(255, lt.bLT);
    EXPECT_EQ(0, lt.bRT);
    EXPECT_EQ(0, lt.wButtons);
    r[5] = 0x80;
    DeviceState rt = decodeFresh(Parser::GENERIC_HID_GAMEPAD, r);
    EXPECT_EQ(0, rt.bLT);
    EXPECT_EQ(255, rt.bRT);
    r[5] = 0xC0;
    DeviceState both = decodeFresh(Parser::GENERIC_HID_GAMEPAD, r);
    EXPECT_EQ(255, both.bLT);
    EXPECT_EQ(255, both.bRT);
}

TEST(GenericHidDecode, ShortReportIsRejected) {
    DeviceState s;
    ParserState st;
    auto r = hidFallbackReport();
    r.resize(6);
    EXPECT_FALSE(decodeReport(Parser::GENERIC_HID_GAMEPAD, r.data(), r.size(), s, &st));
    EXPECT_FALSE(decodeReport(Parser::GENERIC_HID_GAMEPAD, r.data(), r.size(), s, nullptr));
    r.resize(7);
    EXPECT_TRUE(decodeReport(Parser::GENERIC_HID_GAMEPAD, r.data(), r.size(), s, &st));
}

// ---- odds and ends the byte-exact pins left out --------------------------------------------

TEST(Rumble, SwitchProMidAmplitudePicksTheNearestLowerCode) {
    // 0x8000 lands at amplitude 501 of 1003, between the 387 and 650 codes: the lower one wins.
    uint8_t out[64];
    ASSERT_EQ(10u, buildRumbleReport(Parser::SWITCH_PRO_USB, 0x8000, 0, 0, out, sizeof(out)));
    EXPECT_EQ(0x00, out[2]);
    EXPECT_EQ(0x71, out[3]);
    EXPECT_EQ(0x40, out[4]);
    EXPECT_EQ(0x5C, out[5]);
}

TEST(Rumble, SwitchProLowAmplitudeCodeCarriesItsHighByte) {
    // Amplitude 10 is the one code whose low word has a high byte (0x8040), so it is the code
    // that proves the two halves land in the right bytes.
    uint8_t out[64];
    ASSERT_EQ(10u, buildRumbleReport(Parser::SWITCH_PRO_USB, 0, 654, 0, out, sizeof(out)));
    EXPECT_EQ(0x00, out[6]);
    EXPECT_EQ(0x03, out[7]);
    EXPECT_EQ(0xC0, out[8]);
    EXPECT_EQ(0x40, out[9]);
}

TEST(TouchDecode, RawBeyondTheSurfaceClampsToTheEdge) {
    auto ds4 = ds4Report();
    ds4[33] = 1;
    writeTouchPoint(ds4.data() + 35, true, 1, 4095, 4095);
    DeviceState s{};
    ASSERT_TRUE(decodeReport(Parser::DUALSHOCK4, ds4.data(), ds4.size(), s, nullptr));
    EXPECT_EQ(32767, s.touch0X);
    EXPECT_EQ(32767, s.touch0Y);

    auto ds5 = dualSenseReport();
    writeTouchPoint(ds5.data() + 33, true, 1, 4095, 4095);
    DeviceState s5{};
    ASSERT_TRUE(decodeReport(Parser::DUALSENSE, ds5.data(), ds5.size(), s5, nullptr));
    EXPECT_EQ(32767, s5.touch0X);
    EXPECT_EQ(32767, s5.touch0Y);
}

TEST(ParserName, EveryParserHasADistinctNonEmptyName) {
    const Parser all[] = {Parser::NONE,
                          Parser::XINPUT_360,
                          Parser::XBOX_ONE_GIP,
                          Parser::DUALSHOCK4,
                          Parser::DUALSENSE,
                          Parser::SWITCH_PRO_USB,
                          Parser::STADIA,
                          Parser::GENERIC_HID_GAMEPAD,
                          Parser::XINPUT_360_WIRELESS,
                          Parser::STEAM_CONTROLLER,
                          Parser::SWITCH_N64_USB,
                          Parser::STEAM_TRITON};
    for (const Parser a : all) {
        ASSERT_NE(nullptr, usbparsers::parserName(a));
        EXPECT_GT(strlen(usbparsers::parserName(a)), 0u);
        for (const Parser b : all) {
            if (a == b) continue;
            EXPECT_STRNE(usbparsers::parserName(a), usbparsers::parserName(b));
        }
    }
}

// ---- the extended-buttons word (satellite docs/contract.md §Extended buttons), SDL's names ----

namespace {

// SDL_hidapi_steam.c: STEAM_RIGHT_TRIGGER_MASK, STEAM_LEFT_TRIGGER_MASK (the full-pull clicks),
// STEAM_BUTTON_BACK_LEFT_MASK and STEAM_BUTTON_BACK_RIGHT_MASK (the grips).
constexpr uint32_t kBtnRightTriggerClick = 0x000001;
constexpr uint32_t kBtnLeftTriggerClick = 0x000002;
constexpr uint32_t kBtnBackLeft = 0x008000;
constexpr uint32_t kBtnBackRight = 0x010000;

DeviceState steamDecoded(const uint32_t buttons) {
    DeviceState s;
    ParserState st;
    EXPECT_TRUE(decodeSteam(steamState(buttons), s, st));
    return s;
}

} // namespace

TEST(SteamExtended, TheGripsAreSdlsLeftAndRightPaddle) {
    EXPECT_EQ(dish_wire::EXT_BUTTON_LEFT_PADDLE1, steamDecoded(kBtnBackLeft).extButtons);
    EXPECT_EQ(dish_wire::EXT_BUTTON_RIGHT_PADDLE1, steamDecoded(kBtnBackRight).extButtons);
    EXPECT_EQ(0, steamDecoded(kBtnBackLeft | kBtnBackRight).wButtons);
}

TEST(SteamExtended, TheFullPullTriggerBitsAreTheTriggerClicks) {
    EXPECT_EQ(dish_wire::EXT_BUTTON_LEFT_TRIGGER_CLICK,
              steamDecoded(kBtnLeftTriggerClick).extButtons);
    EXPECT_EQ(dish_wire::EXT_BUTTON_RIGHT_TRIGGER_CLICK,
              steamDecoded(kBtnRightTriggerClick).extButtons);
    EXPECT_EQ(0, steamDecoded(kBtnLeftTriggerClick | kBtnRightTriggerClick).wButtons);
}

TEST(SteamExtended, ALeftPadClickUnderAFingerIsTheLeftPadClickAndNotTheStickClick) {
    const DeviceState s = steamDecoded(kBtnLeftPadFinger | kBtnLeftPadClicked);
    EXPECT_EQ(dish_wire::EXT_BUTTON_LEFT_PAD_CLICK, s.extButtons);
    EXPECT_EQ(0, s.wButtons & XUSB_THUMB_L);
}

TEST(SteamExtended, ALeftPadClickWithNoFingerStaysTheStickClickAlone) {
    const DeviceState s = steamDecoded(kBtnLeftPadClicked);
    EXPECT_EQ(0u, s.extButtons);
    EXPECT_EQ(XUSB_THUMB_L, s.wButtons & XUSB_THUMB_L);
}

TEST(SteamExtended, TheRightPadClickStaysTheRightStickClickAloneWhileThePadIsTheStick) {
    const DeviceState s = steamDecoded(kBtnRightPadFinger | kBtnRightPadClicked);
    EXPECT_EQ(0u, s.extButtons);
    EXPECT_EQ(XUSB_THUMB_R, s.wButtons & XUSB_THUMB_R);
}

TEST(SteamExtended, TheMappedButtonsLeaveTheWordClear) {
    const uint32_t everyMapped = kBtnRightBumper | kBtnLeftBumper | kBtnNorth | kBtnEast |
                                 kBtnWest | kBtnSouth | kBtnDpadUp | kBtnDpadRight | kBtnMenu |
                                 kBtnGuide | kBtnEscape | kBtnStickButton;
    EXPECT_EQ(0u, steamDecoded(everyMapped).extButtons);
}

TEST(SteamExtended, EveryExtraRidesTheWordAtOnce) {
    const DeviceState s =
        steamDecoded(kBtnBackLeft | kBtnBackRight | kBtnLeftTriggerClick | kBtnRightTriggerClick |
                     kBtnLeftPadFinger | kBtnLeftPadClicked);
    EXPECT_EQ(dish_wire::EXT_BUTTON_LEFT_PADDLE1 | dish_wire::EXT_BUTTON_RIGHT_PADDLE1 |
                  dish_wire::EXT_BUTTON_LEFT_TRIGGER_CLICK |
                  dish_wire::EXT_BUTTON_RIGHT_TRIGGER_CLICK | dish_wire::EXT_BUTTON_LEFT_PAD_CLICK,
              s.extButtons);
}

// SDL_hidapi_steam_triton.c TritonButtons: QAM is misc1, R4/L4/R5/L5 are paddle 1-4, the pad and
// trigger clicks and the stick and grip touches are their own bits.
TEST(TritonExtended, EachExtraBitIsItsSdlNamedExtendedButton) {
    struct ExtendedCase {
        uint32_t bit;
        uint32_t expected;
    };
    const ExtendedCase cases[] = {
        {kTritonQam, dish_wire::EXT_BUTTON_MISC1},
        {kTritonR4, dish_wire::EXT_BUTTON_RIGHT_PADDLE1},
        {kTritonL4, dish_wire::EXT_BUTTON_LEFT_PADDLE1},
        {kTritonR5, dish_wire::EXT_BUTTON_RIGHT_PADDLE2},
        {kTritonL5, dish_wire::EXT_BUTTON_LEFT_PADDLE2},
        {kTritonLeftPadClick, dish_wire::EXT_BUTTON_LEFT_PAD_CLICK},
        {kTritonRightPadClick, dish_wire::EXT_BUTTON_RIGHT_PAD_CLICK},
        {kTritonLeftTriggerClick, dish_wire::EXT_BUTTON_LEFT_TRIGGER_CLICK},
        {kTritonRightTriggerClick, dish_wire::EXT_BUTTON_RIGHT_TRIGGER_CLICK},
        {kTritonLeftStickTouch, dish_wire::EXT_BUTTON_LEFT_STICK_TOUCH},
        {kTritonRightStickTouch, dish_wire::EXT_BUTTON_RIGHT_STICK_TOUCH},
        {kTritonLeftGripTouch, dish_wire::EXT_BUTTON_LEFT_GRIP_TOUCH},
        {kTritonRightGripTouch, dish_wire::EXT_BUTTON_RIGHT_GRIP_TOUCH},
    };
    for (const ExtendedCase& c : cases) {
        DeviceState s;
        ASSERT_TRUE(decodeTriton(tritonState(kTritonStateId, c.bit), s));
        EXPECT_EQ(c.expected, s.extButtons) << "triton bit 0x" << std::hex << c.bit;
        EXPECT_EQ(0, s.wButtons) << "triton bit 0x" << std::hex << c.bit;
    }
}

TEST(TritonExtended, ThePadTouchesAndTheMappedButtonsLeaveTheWordClear) {
    const uint32_t mapped = kTritonA | kTritonB | kTritonX | kTritonY | kTritonL | kTritonR |
                            kTritonMenu | kTritonView | kTritonSteam | kTritonL3 | kTritonR3 |
                            kTritonDpadUp | kTritonDpadDown | kTritonDpadLeft | kTritonDpadRight;
    DeviceState s;
    ASSERT_TRUE(decodeTriton(
        tritonState(kTritonStateId, mapped | kTritonLeftPadTouch | kTritonRightPadTouch), s));
    EXPECT_EQ(0u, s.extButtons);
}

TEST(TritonExtended, TheTimestampReportCarriesTheSameWord) {
    DeviceState s;
    ASSERT_TRUE(decodeTriton(tritonState(kTritonStateTimestampId, kTritonQam | kTritonL5), s));
    EXPECT_EQ(dish_wire::EXT_BUTTON_MISC1 | dish_wire::EXT_BUTTON_LEFT_PADDLE2, s.extButtons);
}

// SDL_hidapi_switch.c HandleFullControllerState: bit 0x20 of the shared button byte is
// SDL_GAMEPAD_BUTTON_SWITCH_SHARE, the Capture button, which SDL's gamepad layer calls misc1.
TEST(SwitchExtended, TheCaptureBitOfTheSharedByteIsMisc1) {
    auto r = switchReport(64);
    r[4] = 0x20;
    DeviceState s;
    ParserState st;
    ASSERT_TRUE(decodeReport(Parser::SWITCH_PRO_USB, r.data(), r.size(), s, &st));
    EXPECT_EQ(dish_wire::EXT_BUTTON_MISC1, s.extButtons);
    EXPECT_EQ(0, s.wButtons);
}

TEST(SwitchExtended, EveryOtherSharedBitLeavesTheWordClear) {
    auto r = switchReport(64);
    r[4] = 0xDF;
    DeviceState s;
    ParserState st;
    ASSERT_TRUE(decodeReport(Parser::SWITCH_PRO_USB, r.data(), r.size(), s, &st));
    EXPECT_EQ(0u, s.extButtons);
}

// SDL_hidapi_ps5.c HandleStatePacketCommon: byte 10 bits 0x10 LEFT_FUNCTION, 0x20 RIGHT_FUNCTION,
// 0x40 LEFT_PADDLE, 0x80 RIGHT_PADDLE, which SDL's Edge mapping reads as paddle 4, 3, 2, 1.
TEST(DualSenseEdgeExtended, TheBackButtonsInByte10AreSdlsPaddlesAndFunctionButtons) {
    struct EdgeCase {
        uint8_t bit;
        uint32_t expected;
    };
    const EdgeCase cases[] = {
        {0x10, dish_wire::EXT_BUTTON_LEFT_PADDLE2},
        {0x20, dish_wire::EXT_BUTTON_RIGHT_PADDLE2},
        {0x40, dish_wire::EXT_BUTTON_LEFT_PADDLE1},
        {0x80, dish_wire::EXT_BUTTON_RIGHT_PADDLE1},
    };
    for (const EdgeCase& c : cases) {
        auto r = dualSenseReport();
        r[8] = 0x08;
        r[10] = c.bit;
        DeviceState s;
        ParserState st;
        st.productId = 0x0DF2;
        ASSERT_TRUE(decodeReport(Parser::DUALSENSE, r.data(), r.size(), s, &st));
        EXPECT_EQ(c.expected, s.extButtons) << "byte 10 bit 0x" << std::hex << (int)c.bit;
        EXPECT_EQ(0, s.wButtons) << "byte 10 bit 0x" << std::hex << (int)c.bit;
    }
}

TEST(DualSenseEdgeExtended, APlainDualSenseNeverSourcesTheWord) {
    auto r = dualSenseReport();
    r[10] = 0xF0;
    DeviceState s;
    ParserState st;
    st.productId = 0x0CE6;
    ASSERT_TRUE(decodeReport(Parser::DUALSENSE, r.data(), r.size(), s, &st));
    EXPECT_EQ(0u, s.extButtons);
}

TEST(DualSenseEdgeExtended, WithoutAParserStateTheWordIsClear) {
    auto r = dualSenseReport();
    r[10] = 0xF0;
    DeviceState s;
    ASSERT_TRUE(decodeReport(Parser::DUALSENSE, r.data(), r.size(), s, nullptr));
    EXPECT_EQ(0u, s.extButtons);
}

TEST(DualSenseEdgeExtended, ThePsTouchClickAndMuteBitsAreNotBackButtons) {
    auto r = dualSenseReport();
    r[10] = 0x01 | 0x02 | 0x04;
    DeviceState s;
    ParserState st;
    st.productId = 0x0DF2;
    ASSERT_TRUE(decodeReport(Parser::DUALSENSE, r.data(), r.size(), s, &st));
    EXPECT_EQ(0u, s.extButtons);
}

TEST(ExtendedButtonsModel, TheSteamControllersTheEdgeAndTheSwitchProSourceTheWord) {
    for (const uint16_t pid : {0x1102, 0x1142, 0x1302, 0x1304, 0x1305}) {
        EXPECT_TRUE(usbparsers::modelHasExtendedButtons(0x28DE, pid)) << std::hex << pid;
    }
    EXPECT_TRUE(usbparsers::modelHasExtendedButtons(0x054C, 0x0DF2));
    EXPECT_TRUE(usbparsers::modelHasExtendedButtons(0x057E, 0x2009));
    EXPECT_TRUE(usbparsers::modelHasExtendedButtons(0x057E, 0x200E));
}

TEST(ExtendedButtonsModel, EveryOtherPadSourcesNothing) {
    EXPECT_FALSE(usbparsers::modelHasExtendedButtons(0x054C, 0x0CE6));
    EXPECT_FALSE(usbparsers::modelHasExtendedButtons(0x054C, 0x05C4));
    EXPECT_FALSE(usbparsers::modelHasExtendedButtons(0x057E, 0x2017));
    EXPECT_FALSE(usbparsers::modelHasExtendedButtons(0x057E, 0x2019));
    EXPECT_FALSE(usbparsers::modelHasExtendedButtons(0x1234, 0x5678));
}

// ---- the Steam trackpads through the per-slot pick ------------------------------------------

TEST(SteamTrackpads, TheDefaultPickIsTheRightStickAsToday) {
    ParserState st;
    EXPECT_EQ(gamepad::TrackpadPick::STICK, st.trackpadPick);
}

// SDL rotates the left pad by -15 degrees and the right pad by +15 (RotatePad with
// -flRotationAngle and flRotationAngle), so a finger at the pad's +x edge lands as below.
TEST(SteamTrackpads, PadPickPutsTheLeftPadOnTheLeftHalfThroughTheShellRotation) {
    ParserState st;
    st.trackpadPick = gamepad::TrackpadPick::PAD;
    auto r = steamState(kBtnLeftPadFinger);
    setLe16(r, 16, 32767);
    setLe16(r, 18, 0);
    DeviceState s;
    ASSERT_TRUE(decodeSteam(r, s, st));
    ASSERT_TRUE(s.touchValid);
    EXPECT_TRUE(s.touch0Active);
    EXPECT_EQ(-559, s.touch0X);
    EXPECT_EQ(8480, s.touch0Y);
    EXPECT_FALSE(s.touch1Active);
    EXPECT_EQ(0, s.sLX);
    EXPECT_EQ(0, s.sLY);
}

TEST(SteamTrackpads, PadPickPutsTheRightPadOnTheRightHalfAndOffTheStick) {
    ParserState st;
    st.trackpadPick = gamepad::TrackpadPick::PAD;
    auto r = steamState(kBtnRightPadFinger);
    setLe16(r, 20, 32767);
    setLe16(r, 22, 0);
    DeviceState s;
    ASSERT_TRUE(decodeSteam(r, s, st));
    ASSERT_TRUE(s.touchValid);
    EXPECT_TRUE(s.touch1Active);
    EXPECT_EQ(32209, s.touch1X);
    EXPECT_EQ(-8480, s.touch1Y);
    EXPECT_FALSE(s.touch0Active);
    EXPECT_EQ(0, s.sRX);
    EXPECT_EQ(0, s.sRY);
}

TEST(SteamTrackpads, OutsideTheStickPickTheRightPadClickIsTheSurfaceClickAndNotR3) {
    for (const gamepad::TrackpadPick pick :
         {gamepad::TrackpadPick::PAD, gamepad::TrackpadPick::MOUSE}) {
        ParserState st;
        st.trackpadPick = pick;
        DeviceState s;
        ASSERT_TRUE(decodeSteam(steamState(kBtnRightPadFinger | kBtnRightPadClicked), s, st));
        EXPECT_EQ(0, s.wButtons & XUSB_THUMB_R) << (int)pick;
        EXPECT_TRUE(s.touchClick) << (int)pick;
        EXPECT_EQ(0u, s.extButtons) << (int)pick;
    }
}

TEST(SteamTrackpads, OffPickDropsThePositionsAndSendsBothClicksAsExtendedButtons) {
    ParserState st;
    st.trackpadPick = gamepad::TrackpadPick::OFF;
    auto r = steamState(kBtnLeftPadFinger | kBtnLeftPadClicked | kBtnRightPadFinger |
                        kBtnRightPadClicked);
    setLe16(r, 16, 20000);
    setLe16(r, 20, 20000);
    DeviceState s;
    ASSERT_TRUE(decodeSteam(r, s, st));
    EXPECT_FALSE(s.touch0Active);
    EXPECT_FALSE(s.touch1Active);
    EXPECT_EQ(0, s.sRX);
    EXPECT_EQ(0, s.wButtons & XUSB_THUMB_R);
    EXPECT_EQ(dish_wire::EXT_BUTTON_LEFT_PAD_CLICK | dish_wire::EXT_BUTTON_RIGHT_PAD_CLICK,
              s.extButtons);
}

TEST(SteamTrackpads, MousePickMakesTheRightPadTheCursorAndTheLeftPadTheWheel) {
    ParserState st;
    st.trackpadPick = gamepad::TrackpadPick::MOUSE;
    auto cursor = steamState(kBtnRightPadFinger | kBtnRightPadClicked);
    setLe16(cursor, 20, 0);
    setLe16(cursor, 22, 0);
    DeviceState s;
    ASSERT_TRUE(decodeSteam(cursor, s, st));
    EXPECT_TRUE(s.touch0Active);
    EXPECT_TRUE(s.touchClick);
    EXPECT_FALSE(s.touchRightDown);

    auto rest = steamState(kBtnLeftPadFinger);
    setLe16(rest, 16, 0);
    setLe16(rest, 18, 0);
    DeviceState r0;
    ASSERT_TRUE(decodeSteam(rest, r0, st));
    auto swipe = steamState(kBtnLeftPadFinger | kBtnLeftPadClicked);
    setLe16(swipe, 16, 0);
    setLe16(swipe, 18, 8500);
    DeviceState r1;
    ASSERT_TRUE(decodeSteam(swipe, r1, st));
    EXPECT_TRUE(r1.touchRightDown);
    EXPECT_FALSE(r1.touchClick);
    EXPECT_EQ(2 * gamepad::kWheelNotch, r1.touchScrollV);
    EXPECT_EQ(0, r1.wButtons & XUSB_THUMB_L);
}

TEST(TritonTrackpads, TheDefaultPickLeavesBothPadsOffTheWireAndTheirClicksInTheWord) {
    auto r = tritonState(kTritonStateId, kTritonLeftPadTouch | kTritonRightPadTouch |
                                             kTritonLeftPadClick | kTritonRightPadClick);
    setLe16(r, 18, 1000);
    setLe16(r, 24, -1000);
    DeviceState s;
    ASSERT_TRUE(decodeTriton(r, s));
    EXPECT_TRUE(s.touchValid);
    EXPECT_FALSE(s.touch0Active);
    EXPECT_FALSE(s.touch1Active);
    EXPECT_EQ(dish_wire::EXT_BUTTON_LEFT_PAD_CLICK | dish_wire::EXT_BUTTON_RIGHT_PAD_CLICK,
              s.extButtons);
}

TEST(TritonTrackpads, PadPickReadsThePadsAtEighteenAndTwentyFourOnThePlainReports) {
    for (const uint8_t id : {kTritonStateId, kTritonStateBleId}) {
        ParserState st;
        st.trackpadPick = gamepad::TrackpadPick::PAD;
        auto r = tritonState(id, kTritonLeftPadTouch | kTritonRightPadTouch);
        setLe16(r, 18, 1000);
        setLe16(r, 20, 2000);
        setLe16(r, 24, -1000);
        setLe16(r, 26, -2000);
        DeviceState s;
        ASSERT_TRUE(decodeTriton(r, s, st)) << (int)id;
        ASSERT_TRUE(s.touchValid) << (int)id;
        EXPECT_TRUE(s.touch0Active) << (int)id;
        EXPECT_EQ(-15884, s.touch0X) << (int)id;
        EXPECT_EQ(-2000, s.touch0Y) << (int)id;
        EXPECT_TRUE(s.touch1Active) << (int)id;
        EXPECT_EQ(15884, s.touch1X) << (int)id;
        EXPECT_EQ(2000, s.touch1Y) << (int)id;
    }
}

TEST(TritonTrackpads, PadPickReadsThePadsAtTwentyAndTwentySixOnTheTimestampReport) {
    ParserState st;
    st.trackpadPick = gamepad::TrackpadPick::PAD;
    auto r = tritonState(kTritonStateTimestampId, kTritonLeftPadTouch | kTritonRightPadTouch);
    setLe16(r, 20, 1000);
    setLe16(r, 22, 2000);
    setLe16(r, 26, -1000);
    setLe16(r, 28, -2000);
    DeviceState s;
    ASSERT_TRUE(decodeTriton(r, s, st));
    EXPECT_EQ(-15884, s.touch0X);
    EXPECT_EQ(-2000, s.touch0Y);
    EXPECT_EQ(15884, s.touch1X);
    EXPECT_EQ(2000, s.touch1Y);
}

TEST(TritonTrackpads, APadWithoutItsTouchBitIsLiftedWhateverItsCoordinates) {
    ParserState st;
    st.trackpadPick = gamepad::TrackpadPick::PAD;
    auto r = tritonState(kTritonStateId, kTritonRightPadTouch);
    setLe16(r, 18, 1000);
    setLe16(r, 24, 1000);
    DeviceState s;
    ASSERT_TRUE(decodeTriton(r, s, st));
    EXPECT_FALSE(s.touch0Active);
    EXPECT_TRUE(s.touch1Active);
}

TEST(TritonTrackpads, PadPickMakesEitherPadClickTheSurfaceClickAndNotAnExtendedButton) {
    ParserState st;
    st.trackpadPick = gamepad::TrackpadPick::PAD;
    DeviceState s;
    ASSERT_TRUE(decodeTriton(tritonState(kTritonStateId, kTritonLeftPadTouch | kTritonLeftPadClick),
                             s, st));
    EXPECT_TRUE(s.touchClick);
    EXPECT_EQ(0u, s.extButtons);
}

TEST(TritonTrackpads, EveryPickLeavesTheRealRightStickAlone) {
    for (const gamepad::TrackpadPick pick :
         {gamepad::TrackpadPick::STICK, gamepad::TrackpadPick::PAD, gamepad::TrackpadPick::MOUSE,
          gamepad::TrackpadPick::OFF}) {
        ParserState st;
        st.trackpadPick = pick;
        auto r = tritonState(kTritonStateId, kTritonRightPadTouch);
        setLe16(r, 14, 5000);
        setLe16(r, 16, -6000);
        setLe16(r, 24, 30000);
        DeviceState s;
        ASSERT_TRUE(decodeTriton(r, s, st));
        EXPECT_EQ(5000, s.sRX) << (int)pick;
        EXPECT_EQ(-6000, s.sRY) << (int)pick;
    }
}
