// SPDX-License-Identifier: LGPL-3.0-or-later

#include "usb_device_table.h"

#include <gtest/gtest.h>

#include <atomic>
#include <cstdint>
#include <thread>

namespace {

constexpr int32_t kPad = -1000;
constexpr int32_t kOtherPad = -1001;
constexpr int kTurnovers = 20000;

uint64_t markerOf(const int32_t pad) { return static_cast<uint64_t>(-static_cast<int64_t>(pad)); }

uint64_t urbsOf(const dish_usb::UsbDeviceTable& table, const int32_t pad) {
    return table.read<uint64_t>(pad, 0, [](const dish_usb::UsbDeviceStats& s) {
        return s.urbCount.load(std::memory_order_relaxed);
    });
}

} // namespace

TEST(UsbDeviceTable, AClaimedPadsCountersReadBackAndAnUnknownPadReadsTheFallback) {
    dish_usb::UsbDeviceTable table;
    dish_usb::UsbDeviceStats* stats = table.claim(kPad);
    ASSERT_NE(stats, nullptr);
    dish_usb::bump(stats->urbCount);
    dish_usb::bump(stats->urbCount);
    EXPECT_EQ(urbsOf(table, kPad), 2u);
    EXPECT_EQ(urbsOf(table, kOtherPad), 0u);
    EXPECT_EQ(table.read<int32_t>(kPad, -7,
                                  [](const dish_usb::UsbDeviceStats& s) {
                                      return s.lastBattery.load(std::memory_order_relaxed);
                                  }),
              -1);
}

TEST(UsbDeviceTable, AGivenBackEntryReadsTheFallbackAndIsClaimedAgainAtZero) {
    dish_usb::UsbDeviceTable table;
    dish_usb::UsbDeviceStats* first = table.claim(kPad);
    first->urbErrorCount.store(5);
    first->lastBattery.store(0x6402);
    table.release(kPad);
    EXPECT_EQ(urbsOf(table, kPad), 0u);
    dish_usb::UsbDeviceStats* second = table.claim(kOtherPad);
    ASSERT_EQ(second, first);
    EXPECT_EQ(second->urbErrorCount.load(), 0u);
    EXPECT_EQ(second->lastBattery.load(), -1);
}

TEST(UsbDeviceTable, AFullTableRefusesTheNextPad) {
    dish_usb::UsbDeviceTable table;
    for (size_t i = 0; i < dish_usb::MAX_USB_DEVICES; i++) {
        ASSERT_NE(table.claim(kPad - (int32_t)i), nullptr);
    }
    EXPECT_EQ(table.claim(kPad - 100), nullptr);
    table.release(kPad - 3);
    EXPECT_NE(table.claim(kPad - 100), nullptr);
}

// Pads come and go through the entries under ids that are never reused, as attach hands them out,
// each counting a marker of its own; a reader of the newest pad only ever answers with that pad's
// marker or with nothing, never with a count from a pad that held the entry before or after it.
TEST(UsbDeviceTable, AReaderRacingTurnoversNeverAnswersWithAnotherPadsCounters) {
    dish_usb::UsbDeviceTable table;
    std::atomic<int32_t> newest{0};
    std::atomic<bool> stop{false};
    std::thread turnover([&] {
        for (int i = 1; i <= kTurnovers; i++) {
            const int32_t pad = kPad - i;
            dish_usb::UsbDeviceStats* stats = table.claim(pad);
            stats->urbCount.store(markerOf(pad), std::memory_order_relaxed);
            newest.store(pad);
            table.release(pad);
        }
        stop = true;
    });
    uint64_t foreign = 0;
    while (!stop.load()) {
        const int32_t pad = newest.load();
        const uint64_t seen = urbsOf(table, pad);
        if (seen != 0 && seen != markerOf(pad)) foreign++;
    }
    turnover.join();
    EXPECT_EQ(foreign, 0u);
}
