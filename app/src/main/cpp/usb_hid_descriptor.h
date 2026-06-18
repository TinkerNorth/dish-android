// SPDX-License-Identifier: LGPL-3.0-or-later

#pragma once

#include <stddef.h>
#include <stdint.h>

#include "gamepad_input.h"

namespace usbhid {

struct HidAxis {
    bool present = false;
    uint16_t bitOffset = 0;
    uint8_t bitSize = 0;
    int32_t logicalMin = 0;
    int32_t logicalMax = 0;
};

// A gamepad field map distilled from a HID report descriptor: where each stick/trigger/hat/button
// lives within the input report. Fixed-size (no heap) so it can be stored per device and read on
// the report hot path.
struct HidLayout {
    bool valid = false;
    uint8_t reportId = 0; // 0 means the device sends no report-id prefix byte
    HidAxis lx, ly, rx, ry, lt, rt;
    bool hasHat = false;
    uint16_t hatBitOffset = 0;
    uint8_t hatBitSize = 0;
    int32_t hatLogicalMin = 0;
    int32_t hatLogicalMax = 0;
    uint16_t buttonBitOffset = 0;
    uint8_t buttonCount = 0;
};

// Parses a HID report descriptor into the gamepad field map. Pure and defensive: returns false and
// leaves the layout invalid on malformed input or when nothing gamepad-like is found, so callers
// fall back to a fixed-offset guess.
bool parseReportDescriptor(const uint8_t* desc, size_t len, HidLayout& out);

// Decodes one input report into the XUSB DeviceState using a parsed layout. Pure.
bool decodeFromLayout(const uint8_t* buf, size_t len, gamepad::DeviceState& s,
                      const HidLayout& layout);

} // namespace usbhid
