// SPDX-License-Identifier: LGPL-3.0-or-later

#pragma once

#include <stddef.h>
#include <stdint.h>

#include "gamepad_input.h"

namespace usbhid {

// How many Push items (HID 1.11 §6.2.2.7) may be outstanding at once. The spec sets no bound; one
// more than this ends the parse, as any other malformed stream does.
inline constexpr size_t HID_GLOBAL_STACK_DEPTH = 8;

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
    // Set by the attach path from the model catalog, after parseReportDescriptor resets the
    // struct; never derived from the descriptor itself.
    bool switchOrderButtons = false;
};

// Parses a HID report descriptor into the gamepad field map. Pure; false leaves the layout invalid
// and the caller on the fixed-offset guess.
bool parseReportDescriptor(const uint8_t* desc, size_t len, HidLayout& out);

// Decodes one input report into the XUSB DeviceState using a parsed layout. Pure.
bool decodeFromLayout(const uint8_t* buf, size_t len, gamepad::DeviceState& s,
                      const HidLayout& layout);

} // namespace usbhid
