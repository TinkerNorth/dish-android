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

struct StickAutoRange {
    AxisAutoRange lx;
    AxisAutoRange ly;
    AxisAutoRange rx;
    AxisAutoRange ry;
};

const KnownDevice* lookupKnown(uint16_t vid, uint16_t pid);

const char* parserName(Parser p);

// True if this parser decodes an on-controller IMU into DeviceState's motion fields. Lets Kotlin
// mark the synthetic device hasGyro so the motion toggle becomes available in Direct mode.
bool parserHasImu(Parser p);

bool runInit(int fd, uint8_t epOut, Parser p, InitKind init);

// Returns true if the report was consumed and state was updated. `sticks` carries the per-device
// auto-range state for parsers that need it (Switch Pro); pass the device's persistent instance.
bool decodeReport(Parser p, const uint8_t* buf, size_t len, gamepad::DeviceState& s,
                  StickAutoRange* sticks);

// Best-effort fallback for devices we don't have a per-model parser for. Tries the simple HID
// gamepad layout (4 sticks as 1-byte axes, two trigger bytes, button bitmask). Returns false if
// the report doesn't look gamepad-shaped, so the caller can fall back to ROUTED instead of
// publishing garbage.
bool decodeGenericHidGamepad(const uint8_t* buf, size_t len, gamepad::DeviceState& s);

} // namespace usbparsers
