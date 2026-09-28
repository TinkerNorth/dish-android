// SPDX-License-Identifier: LGPL-3.0-or-later

#include "usb_hid_descriptor.h"

#include <gtest/gtest.h>

#include <cstdint>
#include <vector>

using gamepad::DeviceState;
using gamepad::XUSB_A;
using gamepad::XUSB_B;
using gamepad::XUSB_BACK;
using gamepad::XUSB_DPAD_DOWN;
using gamepad::XUSB_DPAD_MASK;
using gamepad::XUSB_DPAD_RIGHT;
using gamepad::XUSB_GUIDE;
using gamepad::XUSB_LB;
using gamepad::XUSB_RB;
using gamepad::XUSB_START;
using gamepad::XUSB_THUMB_L;
using gamepad::XUSB_THUMB_R;
using gamepad::XUSB_X;
using gamepad::XUSB_Y;
using usbhid::decodeFromLayout;
using usbhid::HidLayout;
using usbhid::parseReportDescriptor;

namespace {

// A standard two-stick gamepad: X/Y/Z/Rz (bytes 0-3), 4-bit hat + 4-bit pad (byte 4), 10 buttons +
// 6-bit pad (bytes 5-6). 56-bit / 7-byte input report, no report id.
const uint8_t kGamepadDescriptor[] = {
    0x05, 0x01,       // Usage Page (Generic Desktop)
    0x09, 0x05,       // Usage (Game Pad)
    0xA1, 0x01,       // Collection (Application)
    0x05, 0x01,       //   Usage Page (Generic Desktop)
    0x09, 0x30,       //   Usage (X)
    0x09, 0x31,       //   Usage (Y)
    0x09, 0x32,       //   Usage (Z)
    0x09, 0x35,       //   Usage (Rz)
    0x15, 0x00,       //   Logical Minimum (0)
    0x26, 0xFF, 0x00, //   Logical Maximum (255)
    0x75, 0x08,       //   Report Size (8)
    0x95, 0x04,       //   Report Count (4)
    0x81, 0x02,       //   Input (Data,Var,Abs)
    0x09, 0x39,       //   Usage (Hat switch)
    0x15, 0x00,       //   Logical Minimum (0)
    0x25, 0x07,       //   Logical Maximum (7)
    0x75, 0x04,       //   Report Size (4)
    0x95, 0x01,       //   Report Count (1)
    0x81, 0x42,       //   Input (Data,Var,Abs,Null)
    0x75, 0x04,       //   Report Size (4)
    0x95, 0x01,       //   Report Count (1)
    0x81, 0x01,       //   Input (Const)
    0x05, 0x09,       //   Usage Page (Button)
    0x19, 0x01,       //   Usage Minimum (1)
    0x29, 0x0A,       //   Usage Maximum (10)
    0x15, 0x00,       //   Logical Minimum (0)
    0x25, 0x01,       //   Logical Maximum (1)
    0x75, 0x01,       //   Report Size (1)
    0x95, 0x0A,       //   Report Count (10)
    0x81, 0x02,       //   Input (Data,Var,Abs)
    0x75, 0x01,       //   Report Size (1)
    0x95, 0x06,       //   Report Count (6)
    0x81, 0x01,       //   Input (Const)
    0xC0,             // End Collection
};

// Minimal X/Y gamepad behind Report ID 3: report is {0x03, X, Y}.
const uint8_t kReportIdDescriptor[] = {
    0x05, 0x01,       // Usage Page (Generic Desktop)
    0x09, 0x05,       // Usage (Game Pad)
    0xA1, 0x01,       // Collection (Application)
    0x85, 0x03,       //   Report ID (3)
    0x09, 0x30,       //   Usage (X)
    0x09, 0x31,       //   Usage (Y)
    0x15, 0x00,       //   Logical Minimum (0)
    0x26, 0xFF, 0x00, //   Logical Maximum (255)
    0x75, 0x08,       //   Report Size (8)
    0x95, 0x02,       //   Report Count (2)
    0x81, 0x02,       //   Input (Data,Var,Abs)
    0xC0,             // End Collection
};

// A single 32-bit X axis with a 31-bit logical max, to exercise wide-axis scaling.
const uint8_t kWideAxisDescriptor[] = {
    0x05, 0x01,                   // Usage Page (Generic Desktop)
    0x09, 0x05,                   // Usage (Game Pad)
    0xA1, 0x01,                   // Collection (Application)
    0x09, 0x30,                   //   Usage (X)
    0x15, 0x00,                   //   Logical Minimum (0)
    0x27, 0xFF, 0xFF, 0xFF, 0x7F, //   Logical Maximum (0x7FFFFFFF)
    0x75, 0x20,                   //   Report Size (32)
    0x95, 0x01,                   //   Report Count (1)
    0x81, 0x02,                   //   Input (Data,Var,Abs)
    0xC0,                         // End Collection
};

// A 4-direction hat (logical 0..3); raw 4 is the out-of-range null value.
const uint8_t kNarrowHatDescriptor[] = {
    0x05, 0x01, // Usage Page (Generic Desktop)
    0x09, 0x05, // Usage (Game Pad)
    0xA1, 0x01, // Collection (Application)
    0x09, 0x39, //   Usage (Hat switch)
    0x15, 0x00, //   Logical Minimum (0)
    0x25, 0x03, //   Logical Maximum (3)
    0x75, 0x08, //   Report Size (8)
    0x95, 0x01, //   Report Count (1)
    0x81, 0x02, //   Input (Data,Var,Abs)
    0xC0,       // End Collection
};

} // namespace

TEST(HidDescriptor, ParsesStandardGamepad) {
    HidLayout L;
    ASSERT_TRUE(parseReportDescriptor(kGamepadDescriptor, sizeof(kGamepadDescriptor), L));
    EXPECT_TRUE(L.valid);
    EXPECT_EQ(0, L.reportId);

    EXPECT_TRUE(L.lx.present);
    EXPECT_EQ(0, L.lx.bitOffset);
    EXPECT_EQ(8, L.lx.bitSize);
    EXPECT_EQ(255, L.lx.logicalMax);
    EXPECT_EQ(8, L.ly.bitOffset);
    EXPECT_EQ(16, L.rx.bitOffset); // Z
    EXPECT_EQ(24, L.ry.bitOffset); // Rz

    EXPECT_TRUE(L.hasHat);
    EXPECT_EQ(32, L.hatBitOffset);
    EXPECT_EQ(4, L.hatBitSize);
    EXPECT_EQ(7, L.hatLogicalMax);

    // Button block starts after the hat nibble + its 4-bit pad (byte 5, bit 40).
    EXPECT_EQ(40, L.buttonBitOffset);
    EXPECT_EQ(10, L.buttonCount);
}

TEST(HidDescriptor, DecodesSticksButtonsAndHat) {
    HidLayout L;
    ASSERT_TRUE(parseReportDescriptor(kGamepadDescriptor, sizeof(kGamepadDescriptor), L));

    std::vector<uint8_t> report(7, 0);
    report[0] = 0xFF; // X full right
    report[4] = 0x02; // hat = 2 (East) in low nibble
    report[5] = 0x03; // buttons 1 and 2 (A, B)

    DeviceState s;
    ASSERT_TRUE(decodeFromLayout(report.data(), report.size(), s, L));
    EXPECT_GT(s.sLX, 30000);
    EXPECT_TRUE(s.wButtons & XUSB_A);
    EXPECT_TRUE(s.wButtons & XUSB_B);
    EXPECT_TRUE(s.wButtons & XUSB_DPAD_RIGHT);
}

TEST(HidDescriptor, DetectsAndHonorsReportId) {
    HidLayout L;
    ASSERT_TRUE(parseReportDescriptor(kReportIdDescriptor, sizeof(kReportIdDescriptor), L));
    EXPECT_EQ(3, L.reportId);
    EXPECT_TRUE(L.lx.present);
    EXPECT_EQ(0, L.lx.bitOffset); // offsets are relative to the post-id payload

    std::vector<uint8_t> good = {0x03, 0xFF, 0x80};
    DeviceState s;
    ASSERT_TRUE(decodeFromLayout(good.data(), good.size(), s, L));
    EXPECT_GT(s.sLX, 30000);

    std::vector<uint8_t> wrongId = {0x05, 0xFF, 0x80};
    DeviceState s2;
    EXPECT_FALSE(decodeFromLayout(wrongId.data(), wrongId.size(), s2, L));
}

TEST(HidDescriptor, RejectsNonGamepadDescriptor) {
    // Usage Page (Vendor), one byte of input: nothing gamepad-like.
    const uint8_t vendor[] = {0x06, 0x00, 0xFF, 0x09, 0x01, 0xA1, 0x01,
                              0x75, 0x08, 0x95, 0x01, 0x81, 0x02, 0xC0};
    HidLayout L;
    EXPECT_FALSE(parseReportDescriptor(vendor, sizeof(vendor), L));
    EXPECT_FALSE(L.valid);
}

TEST(HidDescriptor, EmptyDescriptorIsInvalid) {
    HidLayout L;
    EXPECT_FALSE(parseReportDescriptor(nullptr, 0, L));
    EXPECT_FALSE(L.valid);
}

TEST(HidDescriptor, DecodeOnInvalidLayoutReturnsFalse) {
    HidLayout L; // default: valid == false
    std::vector<uint8_t> report(8, 0x7F);
    DeviceState s;
    EXPECT_FALSE(decodeFromLayout(report.data(), report.size(), s, L));
}

TEST(HidDescriptor, TruncatedDescriptorDoesNotOverrun) {
    // A prefix that promises 2 data bytes but supplies none must not read past the buffer.
    const uint8_t truncated[] = {0x26};
    HidLayout L;
    EXPECT_FALSE(parseReportDescriptor(truncated, sizeof(truncated), L));
}

TEST(HidDescriptor, WideAxisScalesWithoutOverflow) {
    HidLayout L;
    ASSERT_TRUE(parseReportDescriptor(kWideAxisDescriptor, sizeof(kWideAxisDescriptor), L));
    ASSERT_TRUE(L.lx.present);
    EXPECT_EQ(32, L.lx.bitSize);

    std::vector<uint8_t> full = {0xFF, 0xFF, 0xFF, 0x7F}; // raw 0x7FFFFFFF, full deflection
    DeviceState s;
    ASSERT_TRUE(decodeFromLayout(full.data(), full.size(), s, L));
    EXPECT_GT(s.sLX, 30000); // clamps near +max instead of wrapping to garbage
}

TEST(HidDescriptor, NarrowHatRejectsOutOfRangeNull) {
    HidLayout L;
    ASSERT_TRUE(parseReportDescriptor(kNarrowHatDescriptor, sizeof(kNarrowHatDescriptor), L));
    ASSERT_TRUE(L.hasHat);
    EXPECT_EQ(3, L.hatLogicalMax);

    std::vector<uint8_t> east = {0x02}; // a real direction (East)
    DeviceState s1;
    ASSERT_TRUE(decodeFromLayout(east.data(), east.size(), s1, L));
    EXPECT_TRUE(s1.wButtons & gamepad::XUSB_DPAD_RIGHT);

    std::vector<uint8_t> nullDir = {0x04}; // out of 0..3 range: no direction
    DeviceState s2;
    ASSERT_TRUE(decodeFromLayout(nullDir.data(), nullDir.size(), s2, L));
    EXPECT_EQ(0, s2.wButtons & gamepad::XUSB_DPAD_MASK);
}

namespace {

// PDP Faceoff Wired Pro (0e6f:0180) report shape: 14 buttons in Switch usage order
// (Y B A X L R ZL ZR Minus Plus L3 R3 Home Capture) + 2-bit pad, 4-bit hat + 4-bit pad, then
// X/Y/Z/Rz bytes. 56-bit / 7-byte input report, no report id.
const uint8_t kSwitchOrderDescriptor[] = {
    0x05, 0x01,       // Usage Page (Generic Desktop)
    0x09, 0x05,       // Usage (Game Pad)
    0xA1, 0x01,       // Collection (Application)
    0x15, 0x00,       //   Logical Minimum (0)
    0x25, 0x01,       //   Logical Maximum (1)
    0x75, 0x01,       //   Report Size (1)
    0x95, 0x0E,       //   Report Count (14)
    0x05, 0x09,       //   Usage Page (Button)
    0x19, 0x01,       //   Usage Minimum (1)
    0x29, 0x0E,       //   Usage Maximum (14)
    0x81, 0x02,       //   Input (Data,Var,Abs)
    0x95, 0x02,       //   Report Count (2)
    0x81, 0x01,       //   Input (Const)
    0x05, 0x01,       //   Usage Page (Generic Desktop)
    0x25, 0x07,       //   Logical Maximum (7)
    0x75, 0x04,       //   Report Size (4)
    0x95, 0x01,       //   Report Count (1)
    0x09, 0x39,       //   Usage (Hat switch)
    0x81, 0x42,       //   Input (Data,Var,Abs,Null)
    0x95, 0x01,       //   Report Count (1)
    0x81, 0x01,       //   Input (Const)
    0x26, 0xFF, 0x00, //   Logical Maximum (255)
    0x09, 0x30,       //   Usage (X)
    0x09, 0x31,       //   Usage (Y)
    0x09, 0x32,       //   Usage (Z)
    0x09, 0x35,       //   Usage (Rz)
    0x75, 0x08,       //   Report Size (8)
    0x95, 0x04,       //   Report Count (4)
    0x81, 0x02,       //   Input (Data,Var,Abs)
    0xC0,             // End Collection
};

std::vector<uint8_t> switchReport(uint8_t btnLo, uint8_t btnHi, uint8_t hat = 0x08,
                                  uint8_t x = 0x7F, uint8_t y = 0x7F, uint8_t z = 0x7F,
                                  uint8_t rz = 0x7F) {
    return {btnLo, btnHi, hat, x, y, z, rz};
}

HidLayout switchLayout(bool switchOrder) {
    HidLayout L;
    EXPECT_TRUE(parseReportDescriptor(kSwitchOrderDescriptor, sizeof(kSwitchOrderDescriptor), L));
    L.switchOrderButtons = switchOrder;
    return L;
}

} // namespace

TEST(SwitchOrderHid, ParsesTheFaceoffReportShape) {
    HidLayout L;
    ASSERT_TRUE(parseReportDescriptor(kSwitchOrderDescriptor, sizeof(kSwitchOrderDescriptor), L));
    EXPECT_EQ(0, L.reportId);
    EXPECT_EQ(0, L.buttonBitOffset);
    EXPECT_EQ(14, L.buttonCount);
    EXPECT_TRUE(L.hasHat);
    EXPECT_EQ(16, L.hatBitOffset);
    EXPECT_EQ(4, L.hatBitSize);
    EXPECT_EQ(24, L.lx.bitOffset);
    EXPECT_EQ(32, L.ly.bitOffset);
    EXPECT_EQ(40, L.rx.bitOffset); // Z
    EXPECT_EQ(48, L.ry.bitOffset); // Rz
    EXPECT_FALSE(L.lt.present);
    EXPECT_FALSE(L.rt.present);
}

TEST(SwitchOrderHid, ParseResetsTheOrderFlagSoAttachMustSetItAfter) {
    HidLayout L;
    L.switchOrderButtons = true;
    ASSERT_TRUE(parseReportDescriptor(kSwitchOrderDescriptor, sizeof(kSwitchOrderDescriptor), L));
    EXPECT_FALSE(L.switchOrderButtons);
}

TEST(SwitchOrderHid, WesternDecodeScramblesTheFaceoffPad) {
    // Pre-quirk behavior pin: without the catalog flag, physical A (bit 2) lands on X, ZL lands
    // on Back with no trigger, and R3/Home/Capture vanish.
    HidLayout L = switchLayout(false);

    DeviceState a;
    auto physicalA = switchReport(0x04, 0x00);
    ASSERT_TRUE(decodeFromLayout(physicalA.data(), physicalA.size(), a, L));
    EXPECT_EQ(XUSB_X, a.wButtons);

    DeviceState zl;
    auto zlReport = switchReport(0x40, 0x00);
    ASSERT_TRUE(decodeFromLayout(zlReport.data(), zlReport.size(), zl, L));
    EXPECT_EQ(XUSB_BACK, zl.wButtons);
    EXPECT_EQ(0, zl.bLT);

    DeviceState upper;
    auto upperReport = switchReport(0x00, 0x38);
    ASSERT_TRUE(decodeFromLayout(upperReport.data(), upperReport.size(), upper, L));
    EXPECT_EQ(0, upper.wButtons);
}

TEST(SwitchOrderHid, FaceButtonsRemapByPosition) {
    HidLayout L = switchLayout(true);
    struct Case {
        uint8_t bit;
        uint16_t expected;
    };
    const Case cases[] = {
        {0x01, XUSB_X}, // Y (west)
        {0x02, XUSB_A}, // B (south)
        {0x04, XUSB_B}, // A (east)
        {0x08, XUSB_Y}, // X (north)
    };
    for (const Case& c : cases) {
        DeviceState s;
        auto r = switchReport(c.bit, 0x00);
        ASSERT_TRUE(decodeFromLayout(r.data(), r.size(), s, L));
        EXPECT_EQ(c.expected, s.wButtons) << (int)c.bit;
    }
}

TEST(SwitchOrderHid, BumpersMapAndZlZrDriveTriggers) {
    HidLayout L = switchLayout(true);

    DeviceState bumpers;
    auto lr = switchReport(0x30, 0x00);
    ASSERT_TRUE(decodeFromLayout(lr.data(), lr.size(), bumpers, L));
    EXPECT_EQ(static_cast<uint16_t>(XUSB_LB | XUSB_RB), bumpers.wButtons);
    EXPECT_EQ(0, bumpers.bLT);
    EXPECT_EQ(0, bumpers.bRT);

    DeviceState triggers;
    auto zlzr = switchReport(0xC0, 0x00);
    ASSERT_TRUE(decodeFromLayout(zlzr.data(), zlzr.size(), triggers, L));
    EXPECT_EQ(0, triggers.wButtons);
    EXPECT_EQ(255, triggers.bLT);
    EXPECT_EQ(255, triggers.bRT);

    auto released = switchReport(0x00, 0x00);
    ASSERT_TRUE(decodeFromLayout(released.data(), released.size(), triggers, L));
    EXPECT_EQ(0, triggers.bLT);
    EXPECT_EQ(0, triggers.bRT);
}

TEST(SwitchOrderHid, UpperRowMapsMinusPlusSticksAndHome) {
    HidLayout L = switchLayout(true);
    struct Case {
        uint8_t bit;
        uint16_t expected;
    };
    const Case cases[] = {
        {0x01, XUSB_BACK},    // Minus
        {0x02, XUSB_START},   // Plus
        {0x04, XUSB_THUMB_L}, // L3
        {0x08, XUSB_THUMB_R}, // R3
        {0x10, XUSB_GUIDE},   // Home
        {0x20, 0},            // Capture: no XUSB equivalent
    };
    for (const Case& c : cases) {
        DeviceState s;
        auto r = switchReport(0x00, c.bit);
        ASSERT_TRUE(decodeFromLayout(r.data(), r.size(), s, L));
        EXPECT_EQ(c.expected, s.wButtons) << (int)c.bit;
    }
}

TEST(SwitchOrderHid, HatAndSticksAreUntouchedByTheRemap) {
    HidLayout L = switchLayout(true);

    DeviceState east;
    auto r = switchReport(0x00, 0x00, 0x02, 0xFF);
    ASSERT_TRUE(decodeFromLayout(r.data(), r.size(), east, L));
    EXPECT_TRUE(east.wButtons & XUSB_DPAD_RIGHT);
    EXPECT_GT(east.sLX, 30000);

    DeviceState neutral;
    auto n = switchReport(0x00, 0x00);
    ASSERT_TRUE(decodeFromLayout(n.data(), n.size(), neutral, L));
    EXPECT_EQ(0, neutral.wButtons & XUSB_DPAD_MASK);
    EXPECT_EQ(0, neutral.sLX);
}

TEST(SwitchOrderHid, CombinedReportDecodesAllFields) {
    HidLayout L = switchLayout(true);
    DeviceState s;
    auto r = switchReport(0x44, 0x02, 0x04, 0xFF);
    ASSERT_TRUE(decodeFromLayout(r.data(), r.size(), s, L));
    EXPECT_EQ(static_cast<uint16_t>(XUSB_B | XUSB_START | XUSB_DPAD_DOWN), s.wButtons);
    EXPECT_EQ(255, s.bLT);
    EXPECT_EQ(0, s.bRT);
    EXPECT_GT(s.sLX, 30000);
}

// ---- the item stream: long items, first-wins, the caps, signed ranges, later report ids -----

namespace {

// One X axis, with a long item (prefix 0xFE: length byte, tag byte, that many data bytes) sitting
// in front of its usage. No gamepad descriptor carries one; the parser steps over it whole.
const uint8_t kLongItemDescriptor[] = {
    0x05, 0x01,                   // Usage Page (Generic Desktop)
    0x09, 0x05,                   // Usage (Game Pad)
    0xA1, 0x01,                   // Collection (Application)
    0xFE, 0x02, 0x7F, 0xAA, 0xBB, //   Long item: 2 data bytes, tag 0x7F, payload AA BB
    0x09, 0x30,                   //   Usage (X)
    0x15, 0x00,                   //   Logical Minimum (0)
    0x26, 0xFF, 0x00,             //   Logical Maximum (255)
    0x75, 0x08,                   //   Report Size (8)
    0x95, 0x01,                   //   Report Count (1)
    0x81, 0x02,                   //   Input (Data,Var,Abs)
    0xC0,                         // End Collection
};

// X axis, then a long-item prefix with nothing behind it.
const uint8_t kTruncatedLongItemDescriptor[] = {
    0x05, 0x01,       // Usage Page (Generic Desktop)
    0x09, 0x05,       // Usage (Game Pad)
    0xA1, 0x01,       // Collection (Application)
    0x09, 0x30,       //   Usage (X)
    0x15, 0x00,       //   Logical Minimum (0)
    0x26, 0xFF, 0x00, //   Logical Maximum (255)
    0x75, 0x08,       //   Report Size (8)
    0x95, 0x01,       //   Report Count (1)
    0x81, 0x02,       //   Input (Data,Var,Abs)
    0xFE,             //   Long item prefix, cut off
};

// X axis, then a long item claiming 32 data bytes it does not have, then a button block.
const uint8_t kOverrunningLongItemDescriptor[] = {
    0x05, 0x01,             // Usage Page (Generic Desktop)
    0x09, 0x05,             // Usage (Game Pad)
    0xA1, 0x01,             // Collection (Application)
    0x09, 0x30,             //   Usage (X)
    0x15, 0x00,             //   Logical Minimum (0)
    0x26, 0xFF, 0x00,       //   Logical Maximum (255)
    0x75, 0x08,             //   Report Size (8)
    0x95, 0x01,             //   Report Count (1)
    0x81, 0x02,             //   Input (Data,Var,Abs)
    0xFE, 0x20, 0x7F, 0x00, //   Long item: claims 32 data bytes, supplies one
    0x05, 0x09,             //   Usage Page (Button)
    0x19, 0x01,             //   Usage Minimum (1)
    0x29, 0x08,             //   Usage Maximum (8)
    0x25, 0x01,             //   Logical Maximum (1)
    0x75, 0x01,             //   Report Size (1)
    0x95, 0x08,             //   Report Count (8)
    0x81, 0x02,             //   Input (Data,Var,Abs)
    0xC0,                   // End Collection
};

// X axis, then Simulation Controls Brake and Accelerator: {X, brake, accelerator}.
const uint8_t kSimulationTriggersDescriptor[] = {
    0x05, 0x01,       // Usage Page (Generic Desktop)
    0x09, 0x05,       // Usage (Game Pad)
    0xA1, 0x01,       // Collection (Application)
    0x09, 0x30,       //   Usage (X)
    0x15, 0x00,       //   Logical Minimum (0)
    0x26, 0xFF, 0x00, //   Logical Maximum (255)
    0x75, 0x08,       //   Report Size (8)
    0x95, 0x01,       //   Report Count (1)
    0x81, 0x02,       //   Input (Data,Var,Abs)
    0x05, 0x02,       //   Usage Page (Simulation Controls)
    0x09, 0xC5,       //   Usage (Brake)
    0x09, 0xC4,       //   Usage (Accelerator)
    0x95, 0x02,       //   Report Count (2)
    0x81, 0x02,       //   Input (Data,Var,Abs)
    0xC0,             // End Collection
};

// X, then Generic Desktop Rx and Ry as the trigger pair: {X, Rx, Ry}.
const uint8_t kRxRyTriggersDescriptor[] = {
    0x05, 0x01,       // Usage Page (Generic Desktop)
    0x09, 0x05,       // Usage (Game Pad)
    0xA1, 0x01,       // Collection (Application)
    0x09, 0x30,       //   Usage (X)
    0x09, 0x33,       //   Usage (Rx)
    0x09, 0x34,       //   Usage (Ry)
    0x15, 0x00,       //   Logical Minimum (0)
    0x26, 0xFF, 0x00, //   Logical Maximum (255)
    0x75, 0x08,       //   Report Size (8)
    0x95, 0x03,       //   Report Count (3)
    0x81, 0x02,       //   Input (Data,Var,Abs)
    0xC0,             // End Collection
};

// The trigger pair alone: nothing a game could steer with.
const uint8_t kTriggersOnlyDescriptor[] = {
    0x05, 0x01,       // Usage Page (Generic Desktop)
    0x09, 0x05,       // Usage (Game Pad)
    0xA1, 0x01,       // Collection (Application)
    0x09, 0x33,       //   Usage (Rx)
    0x09, 0x34,       //   Usage (Ry)
    0x15, 0x00,       //   Logical Minimum (0)
    0x26, 0xFF, 0x00, //   Logical Maximum (255)
    0x75, 0x08,       //   Report Size (8)
    0x95, 0x02,       //   Report Count (2)
    0x81, 0x02,       //   Input (Data,Var,Abs)
    0xC0,             // End Collection
};

// X declared twice, then Y: {X, X again, Y}.
const uint8_t kAxisDeclaredTwiceDescriptor[] = {
    0x05, 0x01,       // Usage Page (Generic Desktop)
    0x09, 0x05,       // Usage (Game Pad)
    0xA1, 0x01,       // Collection (Application)
    0x15, 0x00,       //   Logical Minimum (0)
    0x26, 0xFF, 0x00, //   Logical Maximum (255)
    0x75, 0x08,       //   Report Size (8)
    0x95, 0x01,       //   Report Count (1)
    0x09, 0x30,       //   Usage (X)
    0x81, 0x02,       //   Input (Data,Var,Abs)
    0x09, 0x30,       //   Usage (X)
    0x81, 0x02,       //   Input (Data,Var,Abs)
    0x09, 0x31,       //   Usage (Y)
    0x81, 0x02,       //   Input (Data,Var,Abs)
    0xC0,             // End Collection
};

// Two button blocks of four: buttons 1-4, then buttons 5-8.
const uint8_t kTwoButtonBlocksDescriptor[] = {
    0x05, 0x01, // Usage Page (Generic Desktop)
    0x09, 0x05, // Usage (Game Pad)
    0xA1, 0x01, // Collection (Application)
    0x15, 0x00, //   Logical Minimum (0)
    0x25, 0x01, //   Logical Maximum (1)
    0x75, 0x01, //   Report Size (1)
    0x95, 0x04, //   Report Count (4)
    0x05, 0x09, //   Usage Page (Button)
    0x19, 0x01, //   Usage Minimum (1)
    0x29, 0x04, //   Usage Maximum (4)
    0x81, 0x02, //   Input (Data,Var,Abs)
    0x19, 0x05, //   Usage Minimum (5)
    0x29, 0x08, //   Usage Maximum (8)
    0x81, 0x02, //   Input (Data,Var,Abs)
    0xC0,       // End Collection
};

// Twenty buttons in one block.
const uint8_t kTwentyButtonsDescriptor[] = {
    0x05, 0x01, // Usage Page (Generic Desktop)
    0x09, 0x05, // Usage (Game Pad)
    0xA1, 0x01, // Collection (Application)
    0x15, 0x00, //   Logical Minimum (0)
    0x25, 0x01, //   Logical Maximum (1)
    0x75, 0x01, //   Report Size (1)
    0x95, 0x14, //   Report Count (20)
    0x05, 0x09, //   Usage Page (Button)
    0x19, 0x01, //   Usage Minimum (1)
    0x29, 0x14, //   Usage Maximum (20)
    0x81, 0x02, //   Input (Data,Var,Abs)
    0xC0,       // End Collection
};

// `listed` Generic Desktop usages this parser does not map (Vx onward), then X as the last one, in
// front of one Input of `listed + 1` fields.
std::vector<uint8_t> unmappedUsagesThenX(const uint8_t listed) {
    std::vector<uint8_t> d = {0x05, 0x01, 0x09, 0x05, 0xA1,
                              0x01, 0x15, 0x00, 0x26, 0xFF,
                              0x00, 0x75, 0x08, 0x95, (uint8_t)(listed + 1)};
    for (uint8_t u = 0; u < listed; u++) {
        d.push_back(0x09);
        d.push_back((uint8_t)(0x40 + u)); // Vx, Vy, Vz, Vbrx, Vbry, Vbrz, Vno, ... reserved
    }
    d.push_back(0x09);
    d.push_back(0x30); // Usage (X)
    d.push_back(0x81);
    d.push_back(0x02); // Input (Data,Var,Abs)
    d.push_back(0xC0);
    return d;
}

// X and Y listed for a four-field Input, then a button block: {X, Y, ?, ?, buttons}.
const uint8_t kShortUsageListDescriptor[] = {
    0x05, 0x01,       // Usage Page (Generic Desktop)
    0x09, 0x05,       // Usage (Game Pad)
    0xA1, 0x01,       // Collection (Application)
    0x15, 0x00,       //   Logical Minimum (0)
    0x26, 0xFF, 0x00, //   Logical Maximum (255)
    0x75, 0x08,       //   Report Size (8)
    0x95, 0x04,       //   Report Count (4)
    0x09, 0x30,       //   Usage (X)
    0x09, 0x31,       //   Usage (Y)
    0x81, 0x02,       //   Input (Data,Var,Abs)
    0x05, 0x09,       //   Usage Page (Button)
    0x19, 0x01,       //   Usage Minimum (1)
    0x29, 0x08,       //   Usage Maximum (8)
    0x25, 0x01,       //   Logical Maximum (1)
    0x75, 0x01,       //   Report Size (1)
    0x95, 0x08,       //   Report Count (8)
    0x81, 0x02,       //   Input (Data,Var,Abs)
    0xC0,             // End Collection
};

// One signed 8-bit X axis, -127..127.
const uint8_t kSignedAxisDescriptor[] = {
    0x05, 0x01, // Usage Page (Generic Desktop)
    0x09, 0x05, // Usage (Game Pad)
    0xA1, 0x01, // Collection (Application)
    0x09, 0x30, //   Usage (X)
    0x15, 0x81, //   Logical Minimum (-127)
    0x25, 0x7F, //   Logical Maximum (127)
    0x75, 0x08, //   Report Size (8)
    0x95, 0x01, //   Report Count (1)
    0x81, 0x02, //   Input (Data,Var,Abs)
    0xC0,       // End Collection
};

// One signed 16-bit X axis, -32768..32767, both bounds as two-byte items.
const uint8_t kSignedWideAxisDescriptor[] = {
    0x05, 0x01,       // Usage Page (Generic Desktop)
    0x09, 0x05,       // Usage (Game Pad)
    0xA1, 0x01,       // Collection (Application)
    0x09, 0x30,       //   Usage (X)
    0x16, 0x00, 0x80, //   Logical Minimum (-32768)
    0x26, 0xFF, 0x7F, //   Logical Maximum (32767)
    0x75, 0x10,       //   Report Size (16)
    0x95, 0x01,       //   Report Count (1)
    0x81, 0x02,       //   Input (Data,Var,Abs)
    0xC0,             // End Collection
};

// X, then a Simulation Brake trigger over a signed range, -127..127: {X, brake}.
const uint8_t kSignedTriggerDescriptor[] = {
    0x05, 0x01,       // Usage Page (Generic Desktop)
    0x09, 0x05,       // Usage (Game Pad)
    0xA1, 0x01,       // Collection (Application)
    0x09, 0x30,       //   Usage (X)
    0x15, 0x00,       //   Logical Minimum (0)
    0x26, 0xFF, 0x00, //   Logical Maximum (255)
    0x75, 0x08,       //   Report Size (8)
    0x95, 0x01,       //   Report Count (1)
    0x81, 0x02,       //   Input (Data,Var,Abs)
    0x05, 0x02,       //   Usage Page (Simulation Controls)
    0x09, 0xC5,       //   Usage (Brake)
    0x15, 0x81,       //   Logical Minimum (-127)
    0x25, 0x7F,       //   Logical Maximum (127)
    0x81, 0x02,       //   Input (Data,Var,Abs)
    0xC0,             // End Collection
};

// Report 1 carries X and Y; report 2 carries eight buttons that belong to another interface.
const uint8_t kTwoReportIdsDescriptor[] = {
    0x05, 0x01,       // Usage Page (Generic Desktop)
    0x09, 0x05,       // Usage (Game Pad)
    0xA1, 0x01,       // Collection (Application)
    0x85, 0x01,       //   Report ID (1)
    0x09, 0x30,       //   Usage (X)
    0x09, 0x31,       //   Usage (Y)
    0x15, 0x00,       //   Logical Minimum (0)
    0x26, 0xFF, 0x00, //   Logical Maximum (255)
    0x75, 0x08,       //   Report Size (8)
    0x95, 0x02,       //   Report Count (2)
    0x81, 0x02,       //   Input (Data,Var,Abs)
    0x85, 0x02,       //   Report ID (2)
    0x05, 0x09,       //   Usage Page (Button)
    0x19, 0x01,       //   Usage Minimum (1)
    0x29, 0x08,       //   Usage Maximum (8)
    0x25, 0x01,       //   Logical Maximum (1)
    0x75, 0x01,       //   Report Size (1)
    0x95, 0x08,       //   Report Count (8)
    0x81, 0x02,       //   Input (Data,Var,Abs)
    0xC0,             // End Collection
};

HidLayout parsed(const uint8_t* desc, const size_t len) {
    HidLayout L;
    EXPECT_TRUE(parseReportDescriptor(desc, len, L));
    return L;
}

DeviceState decoded(const HidLayout& L, const std::vector<uint8_t>& report) {
    DeviceState s;
    EXPECT_TRUE(decodeFromLayout(report.data(), report.size(), s, L));
    return s;
}

} // namespace

TEST(HidDescriptor, ALongItemIsSteppedOverWhole) {
    const HidLayout L = parsed(kLongItemDescriptor, sizeof(kLongItemDescriptor));
    ASSERT_TRUE(L.lx.present);
    EXPECT_EQ(0, L.lx.bitOffset);
    EXPECT_EQ(8, L.lx.bitSize);
    EXPECT_EQ(255, L.lx.logicalMax);
}

TEST(HidDescriptor, ATruncatedLongItemStopsTheParseAndKeepsWhatCameBefore) {
    const HidLayout L = parsed(kTruncatedLongItemDescriptor, sizeof(kTruncatedLongItemDescriptor));
    EXPECT_TRUE(L.lx.present);
}

TEST(HidDescriptor, ALongItemThatOverrunsTheDescriptorEndsTheParse) {
    const HidLayout L =
        parsed(kOverrunningLongItemDescriptor, sizeof(kOverrunningLongItemDescriptor));
    EXPECT_TRUE(L.lx.present);
    EXPECT_EQ(0, L.buttonCount);
}

TEST(HidDescriptor, SimulationBrakeAndAcceleratorAreTheTriggers) {
    const HidLayout L =
        parsed(kSimulationTriggersDescriptor, sizeof(kSimulationTriggersDescriptor));
    ASSERT_TRUE(L.lt.present);
    ASSERT_TRUE(L.rt.present);
    EXPECT_EQ(8, L.lt.bitOffset);
    EXPECT_EQ(16, L.rt.bitOffset);
    const DeviceState s = decoded(L, {0x80, 0xFF, 0x00});
    EXPECT_EQ(255, s.bLT);
    EXPECT_EQ(0, s.bRT);
}

TEST(HidDescriptor, RxAndRyAreTheTriggers) {
    const HidLayout L = parsed(kRxRyTriggersDescriptor, sizeof(kRxRyTriggersDescriptor));
    ASSERT_TRUE(L.lt.present);
    ASSERT_TRUE(L.rt.present);
    EXPECT_EQ(8, L.lt.bitOffset);
    EXPECT_EQ(16, L.rt.bitOffset);
    EXPECT_FALSE(L.rx.present);
    EXPECT_FALSE(L.ry.present);
    const DeviceState s = decoded(L, {0x80, 0x00, 0xFF});
    EXPECT_EQ(0, s.bLT);
    EXPECT_EQ(255, s.bRT);
}

TEST(HidDescriptor, TriggersAloneAreNotAGamepad) {
    // A layout is gamepad-like on a stick, a button block or a hat; a pair of triggers with nothing
    // to steer by is not, so the attach path falls back to the fixed-offset guess instead.
    HidLayout L;
    EXPECT_FALSE(
        parseReportDescriptor(kTriggersOnlyDescriptor, sizeof(kTriggersOnlyDescriptor), L));
    EXPECT_FALSE(L.valid);
    EXPECT_TRUE(L.lt.present);
    EXPECT_TRUE(L.rt.present);
}

TEST(HidDescriptor, ASecondDeclarationOfAnAxisIsIgnored) {
    const HidLayout L = parsed(kAxisDeclaredTwiceDescriptor, sizeof(kAxisDeclaredTwiceDescriptor));
    EXPECT_EQ(0, L.lx.bitOffset);
    ASSERT_TRUE(L.ly.present);
    EXPECT_EQ(16, L.ly.bitOffset); // the ignored second X still occupies its byte
    const DeviceState s = decoded(L, {0xFF, 0x00, 0x00});
    EXPECT_GT(s.sLX, 30000);
}

TEST(HidDescriptor, OnlyTheFirstButtonBlockIsTaken) {
    const HidLayout L = parsed(kTwoButtonBlocksDescriptor, sizeof(kTwoButtonBlocksDescriptor));
    EXPECT_EQ(0, L.buttonBitOffset);
    EXPECT_EQ(4, L.buttonCount);
    const DeviceState s = decoded(L, {0xF1});
    EXPECT_EQ(XUSB_A, s.wButtons);
}

TEST(HidDescriptor, MoreThanSixteenButtonsAreCappedAtSixteen) {
    const HidLayout L = parsed(kTwentyButtonsDescriptor, sizeof(kTwentyButtonsDescriptor));
    EXPECT_EQ(16, L.buttonCount);
}

TEST(HidDescriptor, ASeventeenthListedUsageIsDropped) {
    const std::vector<uint8_t> sixteen = unmappedUsagesThenX(15);
    const HidLayout fits = parsed(sixteen.data(), sixteen.size());
    ASSERT_TRUE(fits.lx.present);
    EXPECT_EQ(15 * 8, fits.lx.bitOffset);

    const std::vector<uint8_t> seventeen = unmappedUsagesThenX(16);
    HidLayout dropped;
    EXPECT_FALSE(parseReportDescriptor(seventeen.data(), seventeen.size(), dropped));
    EXPECT_FALSE(dropped.lx.present);
}

TEST(HidDescriptor, AShortUsageListMapsItsListedUsagesAndPadsTheRest) {
    const HidLayout L = parsed(kShortUsageListDescriptor, sizeof(kShortUsageListDescriptor));
    EXPECT_EQ(0, L.lx.bitOffset);
    EXPECT_EQ(8, L.ly.bitOffset);
    EXPECT_FALSE(L.rx.present);
    EXPECT_FALSE(L.ry.present);
    EXPECT_EQ(32, L.buttonBitOffset);
    EXPECT_EQ(8, L.buttonCount);
}

TEST(HidDescriptor, ASignedEightBitAxisCentresAtZero) {
    const HidLayout L = parsed(kSignedAxisDescriptor, sizeof(kSignedAxisDescriptor));
    EXPECT_EQ(-127, L.lx.logicalMin);
    EXPECT_EQ(0, decoded(L, {0x00}).sLX);
    EXPECT_EQ(32767, decoded(L, {0x7F}).sLX);
    EXPECT_EQ(-32767, decoded(L, {0x81}).sLX);
}

TEST(HidDescriptor, ATwoByteNegativeLogicalMinimumSignExtends) {
    const HidLayout L = parsed(kSignedWideAxisDescriptor, sizeof(kSignedWideAxisDescriptor));
    EXPECT_EQ(-32768, L.lx.logicalMin);
    EXPECT_EQ(32767, L.lx.logicalMax);
    EXPECT_EQ(0, decoded(L, {0x00, 0x00}).sLX);
    EXPECT_EQ(32767, decoded(L, {0xFF, 0x7F}).sLX);
    EXPECT_EQ(-32768, decoded(L, {0x00, 0x80}).sLX);
}

TEST(HidDescriptor, ATriggerWithANegativeRangeScalesFromItsMinimum) {
    const HidLayout L = parsed(kSignedTriggerDescriptor, sizeof(kSignedTriggerDescriptor));
    ASSERT_TRUE(L.lt.present);
    EXPECT_EQ(-127, L.lt.logicalMin);
    EXPECT_EQ(0, decoded(L, {0x80, 0x81}).bLT);
    EXPECT_EQ(127, decoded(L, {0x80, 0x00}).bLT);
    EXPECT_EQ(255, decoded(L, {0x80, 0x7F}).bLT);
}

TEST(HidDescriptor, FieldsOfALaterReportIdAreNotOurs) {
    const HidLayout L = parsed(kTwoReportIdsDescriptor, sizeof(kTwoReportIdsDescriptor));
    EXPECT_EQ(1, L.reportId);
    EXPECT_TRUE(L.lx.present);
    EXPECT_TRUE(L.ly.present);
    EXPECT_EQ(0, L.buttonCount);

    const DeviceState ours = decoded(L, {0x01, 0xFF, 0x80});
    EXPECT_GT(ours.sLX, 30000);
    DeviceState theirs;
    const std::vector<uint8_t> otherReport = {0x02, 0xFF};
    EXPECT_FALSE(decodeFromLayout(otherReport.data(), otherReport.size(), theirs, L));
}

namespace {

// A 32-bit X axis whose declared range is only 0..100: a raw value far outside it has to clamp
// to the rail, not wrap through the narrowing.
const uint8_t kWideFieldNarrowRangeDescriptor[] = {
    0x05, 0x01,       // Usage Page (Generic Desktop)
    0x09, 0x05,       // Usage (Game Pad)
    0xA1, 0x01,       // Collection (Application)
    0x09, 0x30,       //   Usage (X)
    0x15, 0x00,       //   Logical Minimum (0)
    0x26, 0x64, 0x00, //   Logical Maximum (100)
    0x75, 0x20,       //   Report Size (32)
    0x95, 0x01,       //   Report Count (1)
    0x81, 0x02,       //   Input (Data,Var,Abs)
    0xC0,             // End Collection
};

// X, then a 32-bit Rx trigger whose declared range is only 0..100: {X, Rx (4 bytes)}.
const uint8_t kWideTriggerNarrowRangeDescriptor[] = {
    0x05, 0x01,       // Usage Page (Generic Desktop)
    0x09, 0x05,       // Usage (Game Pad)
    0xA1, 0x01,       // Collection (Application)
    0x09, 0x30,       //   Usage (X)
    0x15, 0x00,       //   Logical Minimum (0)
    0x26, 0xFF, 0x00, //   Logical Maximum (255)
    0x75, 0x08,       //   Report Size (8)
    0x95, 0x01,       //   Report Count (1)
    0x81, 0x02,       //   Input (Data,Var,Abs)
    0x09, 0x33,       //   Usage (Rx)
    0x26, 0x64, 0x00, //   Logical Maximum (100)
    0x75, 0x20,       //   Report Size (32)
    0x81, 0x02,       //   Input (Data,Var,Abs)
    0xC0,             // End Collection
};

// A signed 32-bit X axis over the whole int32 range, both bounds as four-byte items.
const uint8_t kSignedFullWidthAxisDescriptor[] = {
    0x05, 0x01,                   // Usage Page (Generic Desktop)
    0x09, 0x05,                   // Usage (Game Pad)
    0xA1, 0x01,                   // Collection (Application)
    0x09, 0x30,                   //   Usage (X)
    0x17, 0x00, 0x00, 0x00, 0x80, //   Logical Minimum (-2147483648)
    0x27, 0xFF, 0xFF, 0xFF, 0x7F, //   Logical Maximum (2147483647)
    0x75, 0x20,                   //   Report Size (32)
    0x95, 0x01,                   //   Report Count (1)
    0x81, 0x02,                   //   Input (Data,Var,Abs)
    0xC0,                         // End Collection
};

} // namespace

TEST(HidDescriptor, AWideFieldBeyondItsDeclaredRangeClampsToTheRail) {
    const HidLayout L =
        parsed(kWideFieldNarrowRangeDescriptor, sizeof(kWideFieldNarrowRangeDescriptor));
    ASSERT_TRUE(L.lx.present);
    EXPECT_EQ(100, L.lx.logicalMax);
    EXPECT_EQ(32767, decoded(L, {0x64, 0x00, 0x00, 0x00}).sLX);
    EXPECT_EQ(32767, decoded(L, {0xFF, 0xFF, 0xFF, 0x7F}).sLX);
    EXPECT_EQ(-32767, decoded(L, {0x00, 0x00, 0x00, 0x00}).sLX);
}

TEST(HidDescriptor, AnUnsignedFieldWithItsTopBitSetIsAboveTheRangeNotBelowIt) {
    // Both logical bounds are non-negative, so the field is unsigned (HID 1.11 §6.2.2.7): raw
    // 0x80000000 is two billion, far above 100, and has to land on the positive rail.
    const HidLayout L =
        parsed(kWideFieldNarrowRangeDescriptor, sizeof(kWideFieldNarrowRangeDescriptor));
    EXPECT_EQ(32767, decoded(L, {0x00, 0x00, 0x00, 0x80}).sLX);
    EXPECT_EQ(32767, decoded(L, {0xFF, 0xFF, 0xFF, 0xFF}).sLX);
}

TEST(HidDescriptor, AnUnsignedWideTriggerWithItsTopBitSetIsFullyPressed) {
    const HidLayout L =
        parsed(kWideTriggerNarrowRangeDescriptor, sizeof(kWideTriggerNarrowRangeDescriptor));
    ASSERT_TRUE(L.lt.present);
    EXPECT_EQ(8, L.lt.bitOffset);
    EXPECT_EQ(0, decoded(L, {0x80, 0x00, 0x00, 0x00, 0x00}).bLT);
    EXPECT_EQ(255, decoded(L, {0x80, 0x64, 0x00, 0x00, 0x00}).bLT);
    EXPECT_EQ(255, decoded(L, {0x80, 0x00, 0x00, 0x00, 0x80}).bLT);
}

TEST(HidDescriptor, ASignedThirtyTwoBitFieldReadsItsTopBitAsTheSign) {
    const HidLayout L =
        parsed(kSignedFullWidthAxisDescriptor, sizeof(kSignedFullWidthAxisDescriptor));
    EXPECT_EQ(INT32_MIN, L.lx.logicalMin);
    EXPECT_EQ(INT32_MAX, L.lx.logicalMax);
    EXPECT_EQ(-32767, decoded(L, {0x00, 0x00, 0x00, 0x80}).sLX);
    EXPECT_EQ(32767, decoded(L, {0xFF, 0xFF, 0xFF, 0x7F}).sLX);
    EXPECT_EQ(0, decoded(L, {0x00, 0x00, 0x00, 0x00}).sLX);
}

namespace {

// Report 1 carries X, report 2 two bytes of buttons, then report 1 resumes with Y: report 1 is
// {0x01, X, Y} whatever report 2 declared in between.
const uint8_t kReportResumedDescriptor[] = {
    0x05, 0x01,       // Usage Page (Generic Desktop)
    0x09, 0x05,       // Usage (Game Pad)
    0xA1, 0x01,       // Collection (Application)
    0x15, 0x00,       //   Logical Minimum (0)
    0x26, 0xFF, 0x00, //   Logical Maximum (255)
    0x75, 0x08,       //   Report Size (8)
    0x95, 0x01,       //   Report Count (1)
    0x85, 0x01,       //   Report ID (1)
    0x09, 0x30,       //   Usage (X)
    0x81, 0x02,       //   Input (Data,Var,Abs)
    0x85, 0x02,       //   Report ID (2)
    0x05, 0x09,       //   Usage Page (Button)
    0x19, 0x01,       //   Usage Minimum (1)
    0x29, 0x10,       //   Usage Maximum (16)
    0x75, 0x01,       //   Report Size (1)
    0x95, 0x10,       //   Report Count (16)
    0x81, 0x02,       //   Input (Data,Var,Abs)
    0x85, 0x01,       //   Report ID (1)
    0x05, 0x01,       //   Usage Page (Generic Desktop)
    0x09, 0x31,       //   Usage (Y)
    0x75, 0x08,       //   Report Size (8)
    0x95, 0x01,       //   Report Count (1)
    0x81, 0x02,       //   Input (Data,Var,Abs)
    0xC0,             // End Collection
};

} // namespace

TEST(HidDescriptor, AReportIdThatReturnsResumesWhereItsReportLeftOff) {
    const HidLayout L = parsed(kReportResumedDescriptor, sizeof(kReportResumedDescriptor));
    EXPECT_EQ(1, L.reportId);
    EXPECT_EQ(0, L.lx.bitOffset);
    ASSERT_TRUE(L.ly.present);
    EXPECT_EQ(8, L.ly.bitOffset);
    EXPECT_EQ(0, L.buttonCount);

    const DeviceState s = decoded(L, {0x01, 0xFF, 0x00});
    EXPECT_EQ(32767, s.sLX);
    EXPECT_EQ(32767, s.sLY); // Y is inverted: raw 0 is full up
}

// ---- Push and Pop (HID 1.11 §6.2.2.7): the global item state table saved and restored ---------

namespace {

// X, then a Push, a button block that rewrites every global, a Pop, and Y: {X, buttons, Y}.
const uint8_t kPushPopDescriptor[] = {
    0x05, 0x01,       // Usage Page (Generic Desktop)
    0x09, 0x05,       // Usage (Game Pad)
    0xA1, 0x01,       // Collection (Application)
    0x15, 0x00,       //   Logical Minimum (0)
    0x26, 0xFF, 0x00, //   Logical Maximum (255)
    0x75, 0x08,       //   Report Size (8)
    0x95, 0x01,       //   Report Count (1)
    0x09, 0x30,       //   Usage (X)
    0x81, 0x02,       //   Input (Data,Var,Abs)
    0xA4,             //   Push
    0x05, 0x09,       //   Usage Page (Button)
    0x19, 0x01,       //   Usage Minimum (1)
    0x29, 0x08,       //   Usage Maximum (8)
    0x25, 0x01,       //   Logical Maximum (1)
    0x75, 0x01,       //   Report Size (1)
    0x95, 0x08,       //   Report Count (8)
    0x81, 0x02,       //   Input (Data,Var,Abs)
    0xB4,             //   Pop
    0x09, 0x31,       //   Usage (Y)
    0x81, 0x02,       //   Input (Data,Var,Abs)
    0xC0,             // End Collection
};

// Report 1 carries X; a Push, report 2's buttons, and a Pop bring report 1 back for Y.
const uint8_t kPopRestoresTheReportIdDescriptor[] = {
    0x05, 0x01,       // Usage Page (Generic Desktop)
    0x09, 0x05,       // Usage (Game Pad)
    0xA1, 0x01,       // Collection (Application)
    0x85, 0x01,       //   Report ID (1)
    0x15, 0x00,       //   Logical Minimum (0)
    0x26, 0xFF, 0x00, //   Logical Maximum (255)
    0x75, 0x08,       //   Report Size (8)
    0x95, 0x01,       //   Report Count (1)
    0x09, 0x30,       //   Usage (X)
    0x81, 0x02,       //   Input (Data,Var,Abs)
    0xA4,             //   Push
    0x85, 0x02,       //   Report ID (2)
    0x05, 0x09,       //   Usage Page (Button)
    0x19, 0x01,       //   Usage Minimum (1)
    0x29, 0x08,       //   Usage Maximum (8)
    0x81, 0x02,       //   Input (Data,Var,Abs)
    0xB4,             //   Pop
    0x09, 0x31,       //   Usage (Y)
    0x81, 0x02,       //   Input (Data,Var,Abs)
    0xC0,             // End Collection
};

// X, then a Pop with nothing pushed, then Y.
const uint8_t kPopWithNothingPushedDescriptor[] = {
    0x05, 0x01,       // Usage Page (Generic Desktop)
    0x09, 0x05,       // Usage (Game Pad)
    0xA1, 0x01,       // Collection (Application)
    0x15, 0x00,       //   Logical Minimum (0)
    0x26, 0xFF, 0x00, //   Logical Maximum (255)
    0x75, 0x08,       //   Report Size (8)
    0x95, 0x01,       //   Report Count (1)
    0x09, 0x30,       //   Usage (X)
    0x81, 0x02,       //   Input (Data,Var,Abs)
    0xB4,             //   Pop
    0x09, 0x31,       //   Usage (Y)
    0x81, 0x02,       //   Input (Data,Var,Abs)
    0xC0,             // End Collection
};

// X, then `depth` Pushes, a switch to the Button page, `depth` Pops, and Y: {X, Y} when every
// Push fit.
std::vector<uint8_t> nestedPushesThenY(const size_t depth) {
    constexpr uint8_t kPush = 0xA4;
    constexpr uint8_t kPop = 0xB4;
    const uint8_t xAxis[] = {0x05, 0x01, 0x09, 0x05, 0xA1, 0x01, 0x15, 0x00, 0x26, 0xFF,
                             0x00, 0x75, 0x08, 0x95, 0x01, 0x09, 0x30, 0x81, 0x02};
    const uint8_t buttonPage[] = {0x05, 0x09};
    const uint8_t yAxis[] = {0x09, 0x31, 0x81, 0x02, 0xC0};
    std::vector<uint8_t> d(xAxis, xAxis + sizeof(xAxis));
    d.insert(d.end(), depth, kPush);
    d.insert(d.end(), buttonPage, buttonPage + sizeof(buttonPage));
    d.insert(d.end(), depth, kPop);
    d.insert(d.end(), yAxis, yAxis + sizeof(yAxis));
    return d;
}

} // namespace

TEST(HidDescriptor, APopRestoresTheGlobalsThePushSaved) {
    const HidLayout L = parsed(kPushPopDescriptor, sizeof(kPushPopDescriptor));
    EXPECT_EQ(0, L.lx.bitOffset);
    EXPECT_EQ(8, L.buttonBitOffset);
    EXPECT_EQ(8, L.buttonCount);
    ASSERT_TRUE(L.ly.present);
    EXPECT_EQ(16, L.ly.bitOffset);
    EXPECT_EQ(8, L.ly.bitSize);
    EXPECT_EQ(0, L.ly.logicalMin);
    EXPECT_EQ(255, L.ly.logicalMax);

    const DeviceState s = decoded(L, {0xFF, 0x01, 0x00});
    EXPECT_EQ(32767, s.sLX);
    EXPECT_EQ(XUSB_A, s.wButtons);
    EXPECT_EQ(32767, s.sLY);
}

TEST(HidDescriptor, APopRestoresTheReportIdThePushSaved) {
    const HidLayout L =
        parsed(kPopRestoresTheReportIdDescriptor, sizeof(kPopRestoresTheReportIdDescriptor));
    EXPECT_EQ(1, L.reportId);
    EXPECT_EQ(0, L.buttonCount);
    ASSERT_TRUE(L.ly.present);
    EXPECT_EQ(8, L.ly.bitOffset);
}

TEST(HidDescriptor, APopWithNothingPushedEndsTheParseAndKeepsWhatCameBefore) {
    const HidLayout L =
        parsed(kPopWithNothingPushedDescriptor, sizeof(kPopWithNothingPushedDescriptor));
    EXPECT_TRUE(L.lx.present);
    EXPECT_FALSE(L.ly.present);
}

TEST(HidDescriptor, PushesNestAsDeepAsTheStackAndOneMoreEndsTheParse) {
    const std::vector<uint8_t> deepest = nestedPushesThenY(usbhid::HID_GLOBAL_STACK_DEPTH);
    const HidLayout fits = parsed(deepest.data(), deepest.size());
    ASSERT_TRUE(fits.ly.present);
    EXPECT_EQ(8, fits.ly.bitOffset);

    const std::vector<uint8_t> tooDeep = nestedPushesThenY(usbhid::HID_GLOBAL_STACK_DEPTH + 1);
    const HidLayout overflowed = parsed(tooDeep.data(), tooDeep.size());
    EXPECT_TRUE(overflowed.lx.present);
    EXPECT_FALSE(overflowed.ly.present);
}
