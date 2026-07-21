// SPDX-License-Identifier: LGPL-3.0-or-later

#pragma once

#include <stddef.h>
#include <stdint.h>
#include <string>

#include "gamepad_input.h"
#include "usb_hid_descriptor.h"

namespace usbparsers {

enum class Parser : uint8_t {
    NONE = 0,
    XINPUT_360 = 1,
    XBOX_ONE_GIP = 2,
    DUALSHOCK4 = 3,
    DUALSENSE = 4,
    SWITCH_PRO_USB = 5,
    STADIA = 6,
    GENERIC_HID_GAMEPAD = 7,
    // Same input report as XINPUT_360, but rumble needs the wrapped wireless-receiver frame.
    XINPUT_360_WIRELESS = 8,
};

enum class InitKind : uint8_t {
    NONE = 0,
    XBOX_ONE_POWERON = 1,
    SWITCH_PRO_HANDSHAKE = 2,
    // Xbox One S / Elite Series 2 want the GIP set-mode packet on top of the universal sequence.
    XBOX_ONE_S = 3,
};

struct KnownDevice {
    uint16_t vid;
    uint16_t pid;
    const char* name;
    Parser parser;
    InitKind init;
};

// Per-device, expand-only auto-range for sticks that report raw ADC values. We don't read the
// controller's factory calibration from SPI flash, and the usable deflection varies per unit and
// per direction, so each axis tracks the largest deflection seen on each side of center
// independently and stretches each side to full scale. A full sweep into every corner teaches it
// the true range; the seed sits below any healthy stick's throw so each direction can still reach
// the rail before it is fully learned.
struct AxisAutoRange {
    int32_t posReach = 1000;
    int32_t negReach = 1000;
};

// DualShock 4 / DualSense per-device IMU calibration (read from the calibration feature report).
// raw -> calibrated math from Linux hid-playstation; calibrated gyro is 1024 units/deg-s, accel
// 8192 units/g. Without it the raw sensor scale and bias are unknown, so motion stays off.
struct PsImuCalib {
    bool valid = false;
    int32_t gyroNumer[3] = {0, 0, 0};
    int32_t gyroDenom[3] = {1, 1, 1};
    int32_t accelBias[3] = {0, 0, 0};
    int32_t accelNumer[3] = {0, 0, 0};
    int32_t accelDenom[3] = {1, 1, 1};
};

struct ParserState {
    AxisAutoRange lx;
    AxisAutoRange ly;
    AxisAutoRange rx;
    AxisAutoRange ry;
    // Xbox One guide button (GIP report 0x07) is sticky state merged into the main 0x20 reports.
    bool xboxGuideHeld = false;
    gamepad::DeviceState xboxLastMain;
    // Filled at attach for GENERIC_HID_GAMEPAD when the report descriptor parses; empty otherwise.
    usbhid::HidLayout hidLayout;
    // Filled at attach for DUALSHOCK4 / DUALSENSE when the calibration report reads; invalid
    // otherwise.
    PsImuCalib psImu;
};

const KnownDevice* lookupKnown(uint16_t vid, uint16_t pid);

bool isVerifiedFastLane(uint16_t vid, uint16_t pid);

const char* parserName(Parser p);

bool parserHasImu(Parser p);

bool parserHasRumble(Parser p);

bool parserHasTouchpad(Parser p);

// True for families whose rumble the Android framework can't drive (the Switch Pro's proprietary
// HD-rumble); a framework vibrator they expose is a false positive, so only the Direct path
// actuates.
bool parserFrameworkRumbleUnreliable(Parser p);

// Pure: writes the index-th GIP init packet for an Xbox One InitKind into out (with the sequence
// number at byte 2), returns its length or 0 when there are no more. runInit sends them in order.
size_t buildGipInitPacket(InitKind init, int index, uint8_t seq, uint8_t* out, size_t outCap);

bool runInit(int fd, uint8_t epOut, Parser p, InitKind init);

bool runRumble(int fd, uint8_t epOut, Parser p, uint16_t strong, uint16_t weak, uint8_t seq);

// Pure: writes the device's rumble report into out, returns its length (0 if unsupported or out is
// too small). Host-tested in usb_parsers_test.cpp; runRumble is the Android write wrapper.
size_t buildRumbleReport(Parser p, uint16_t strong, uint16_t weak, uint8_t seq, uint8_t* out,
                         size_t outCap);

bool decodeReport(Parser p, const uint8_t* buf, size_t len, gamepad::DeviceState& s,
                  ParserState* sticks);

bool decodeGenericHidGamepad(const uint8_t* buf, size_t len, gamepad::DeviceState& s);

// Parses a DualShock 4 / DualSense calibration feature report into per-axis gyro/accel factors.
bool parsePsCalibration(const uint8_t* buf, size_t len, PsImuCalib& out);

} // namespace usbparsers
