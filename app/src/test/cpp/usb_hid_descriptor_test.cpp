// SPDX-License-Identifier: LGPL-3.0-or-later

#include "usb_hid_descriptor.h"

#include <gtest/gtest.h>

#include <cstdint>
#include <vector>

using gamepad::DeviceState;
using gamepad::XUSB_A;
using gamepad::XUSB_B;
using gamepad::XUSB_DPAD_RIGHT;
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
