// SPDX-License-Identifier: LGPL-3.0-or-later

#pragma once

#include <stdint.h>
#include <string>

#include "gamepad_input.h"

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
};

enum class InitKind : uint8_t {
    NONE = 0,
    XBOX_ONE_POWERON = 1,
    SWITCH_PRO_HANDSHAKE = 2,
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

struct ParserState {
    AxisAutoRange lx;
    AxisAutoRange ly;
    AxisAutoRange rx;
    AxisAutoRange ry;
    // Xbox One guide button (GIP report 0x07) is sticky state merged into the main 0x20 reports.
    bool xboxGuideHeld = false;
    gamepad::DeviceState xboxLastMain;
};

const KnownDevice* lookupKnown(uint16_t vid, uint16_t pid);

const char* parserName(Parser p);

bool parserHasImu(Parser p);

bool parserHasRumble(Parser p);

bool runInit(int fd, uint8_t epOut, Parser p, InitKind init);

bool runRumble(int fd, uint8_t epOut, Parser p, uint16_t strong, uint16_t weak, uint8_t seq);

// Pure: writes the device's rumble report into out, returns its length (0 if unsupported or out is
// too small). Host-tested in usb_parsers_test.cpp; runRumble is the Android write wrapper.
size_t buildRumbleReport(Parser p, uint16_t strong, uint16_t weak, uint8_t seq, uint8_t* out,
                         size_t outCap);

bool decodeReport(Parser p, const uint8_t* buf, size_t len, gamepad::DeviceState& s,
                  ParserState* sticks);

bool decodeGenericHidGamepad(const uint8_t* buf, size_t len, gamepad::DeviceState& s);

} // namespace usbparsers
