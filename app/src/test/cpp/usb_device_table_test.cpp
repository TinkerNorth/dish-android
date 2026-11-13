// SPDX-License-Identifier: LGPL-3.0-or-later

#include "usb_device_table.h"

#include <gtest/gtest.h>

#include <atomic>
#include <chrono>
#include <cstdint>
#include <thread>
#include <vector>

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
    dish_usb::UsbDevice* device = table.claim(kPad);
    ASSERT_NE(device, nullptr);
    dish_usb::bump(device->stats.urbCount);
    dish_usb::bump(device->stats.urbCount);
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
    dish_usb::UsbDevice* first = table.claim(kPad);
    first->stats.urbErrorCount.store(5);
    first->stats.lastBattery.store(0x6402);
    table.release(kPad);
    EXPECT_EQ(urbsOf(table, kPad), 0u);
    dish_usb::UsbDevice* second = table.claim(kOtherPad);
    ASSERT_EQ(second, first);
    EXPECT_EQ(second->stats.urbErrorCount.load(), 0u);
    EXPECT_EQ(second->stats.lastBattery.load(), -1);
}

// A receiver slot marks its controller away; the pad that takes the entry next starts with its
// own there.
TEST(UsbDeviceTable, AClaimedEntryStartsWithItsControllerThere) {
    dish_usb::UsbDeviceTable table;
    dish_usb::UsbDevice* slot = table.claim(kPad);
    ASSERT_NE(slot, nullptr);
    EXPECT_TRUE(slot->stats.linked.load(std::memory_order_relaxed));
    slot->stats.linked.store(false, std::memory_order_relaxed);
    table.release(kPad);
    dish_usb::UsbDevice* next = table.claim(kOtherPad);
    ASSERT_NE(next, nullptr);
    EXPECT_TRUE(next->stats.linked.load(std::memory_order_relaxed));
}

// The charge was the controller's that left; the one that next connects reports its own.
TEST(UsbDeviceTable, AControllerThatLeavesTakesItsBatteryReadingWithIt) {
    dish_usb::UsbDeviceTable table;
    dish_usb::UsbDevice* slot = table.claim(kPad);
    ASSERT_NE(slot, nullptr);
    slot->stats.lastBattery.store(0x6402, std::memory_order_relaxed);
    dish_usb::controllerLeft(slot->stats);
    EXPECT_FALSE(slot->stats.linked.load(std::memory_order_relaxed));
    EXPECT_EQ(slot->stats.lastBattery.load(std::memory_order_relaxed), -1);
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
            dish_usb::UsbDevice* device = table.claim(pad);
            device->stats.urbCount.store(markerOf(pad), std::memory_order_relaxed);
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

namespace {

constexpr uint8_t kReport[] = {0x45, 0x01, 0x02};
constexpr auto kAWhile = std::chrono::milliseconds(20);

// What reached it; while gated, it holds each report until the gate opens.
class RecordingSink final : public dish_usb::ReportSink {
  public:
    void report(const uint8_t* bytes, const size_t len) override {
        entered.store(true);
        while (gated.load() && !opened.load()) std::this_thread::yield();
        last.assign(bytes, bytes + len);
        reports.fetch_add(1);
    }

    std::atomic<bool> gated{false};
    std::atomic<bool> opened{false};
    std::atomic<bool> entered{false};
    std::atomic<int> reports{0};
    std::vector<uint8_t> last;
};

constexpr auto kLongPastAnyDelivery = std::chrono::seconds(1);

// Whether the flag rose before the deadline, so a report that never arrives fails the test
// rather than hanging it.
bool waitFor(const std::atomic<bool>& flag) {
    const auto deadline = std::chrono::steady_clock::now() + kLongPastAnyDelivery;
    while (!flag.load() && std::chrono::steady_clock::now() < deadline) std::this_thread::yield();
    return flag.load();
}

void deliverOne(dish_usb::UsbDeviceTable& table, std::atomic<bool>& delivered) {
    delivered.store(table.deliver(kPad, kReport, sizeof(kReport)));
}

void retireThePad(dish_usb::UsbDeviceTable& table, std::atomic<bool>& retired) {
    table.retire(kPad);
    retired.store(true);
}

} // namespace

TEST(UsbDeviceTableReports, AReportReachesThePadsSink) {
    dish_usb::UsbDeviceTable table;
    RecordingSink sink;
    table.claim(kPad)->reports.store(&sink);
    EXPECT_TRUE(table.deliver(kPad, kReport, sizeof(kReport)));
    EXPECT_EQ(sink.reports.load(), 1);
    EXPECT_EQ(sink.last, std::vector<uint8_t>(std::begin(kReport), std::end(kReport)));
}

TEST(UsbDeviceTableReports, APadWithNoSinkOrNoEntryTakesNoReport) {
    dish_usb::UsbDeviceTable table;
    ASSERT_NE(table.claim(kPad), nullptr);
    EXPECT_FALSE(table.deliver(kPad, kReport, sizeof(kReport)));
    EXPECT_FALSE(table.deliver(kOtherPad, kReport, sizeof(kReport)));
}

TEST(UsbDeviceTableReports, ARetiredPadTakesNoReport) {
    dish_usb::UsbDeviceTable table;
    RecordingSink sink;
    table.claim(kPad)->reports.store(&sink);
    table.retire(kPad);
    EXPECT_FALSE(table.deliver(kPad, kReport, sizeof(kReport)));
    EXPECT_EQ(sink.reports.load(), 0);
}

TEST(UsbDeviceTableReports, RetireWaitsOutAReportOnItsWay) {
    dish_usb::UsbDeviceTable table;
    RecordingSink sink;
    sink.gated = true;
    table.claim(kPad)->reports.store(&sink);
    std::atomic<bool> delivered{false};
    std::thread reporter(deliverOne, std::ref(table), std::ref(delivered));
    const bool reportArrived = waitFor(sink.entered);
    std::atomic<bool> retired{false};
    std::thread retirer(retireThePad, std::ref(table), std::ref(retired));
    std::this_thread::sleep_for(kAWhile);
    const bool retiredUnderTheReport = retired.load();
    sink.opened = true;
    retirer.join();
    reporter.join();
    EXPECT_TRUE(reportArrived);
    EXPECT_FALSE(retiredUnderTheReport);
    EXPECT_TRUE(retired.load());
    EXPECT_TRUE(delivered.load());
}

TEST(UsbDeviceTableReports, TheNextPadOnAnEntryStartsWithNoSinkAndTakesReports) {
    dish_usb::UsbDeviceTable table;
    RecordingSink first;
    table.claim(kPad)->reports.store(&first);
    table.retire(kPad);
    table.release(kPad);
    dish_usb::UsbDevice* next = table.claim(kOtherPad);
    ASSERT_NE(next, nullptr);
    EXPECT_EQ(next->reports.load(), nullptr);
    RecordingSink second;
    next->reports.store(&second);
    EXPECT_TRUE(table.deliver(kOtherPad, kReport, sizeof(kReport)));
    EXPECT_EQ(second.reports.load(), 1);
    EXPECT_EQ(first.reports.load(), 0);
}
