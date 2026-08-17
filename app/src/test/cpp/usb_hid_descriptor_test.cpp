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
