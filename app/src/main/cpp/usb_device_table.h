// SPDX-License-Identifier: LGPL-3.0-or-later

#pragma once

#include <array>
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <thread>

#include "usb_output.h"

namespace dish_usb {

// The USB pads the app claims at once.
inline constexpr size_t MAX_USB_DEVICES = 8;

// A claimed pad's diagnostics: written by its poller, read by the Kotlin pollers without a lock.
struct UsbDeviceStats {
    std::atomic<uint64_t> urbCount{0};
    std::atomic<uint64_t> motionCount{0};
    // URBs that reaped with an error status, failed to submit, or failed to reap.
    std::atomic<uint64_t> urbErrorCount{0};
    std::atomic<int32_t> lastUrbStatus{0};
    // The pad's charge as its last report carried it, level << 8 | status; -1 until one did.
    std::atomic<int32_t> lastBattery{-1};
    // Whether the pad's controller is there: a receiver slot's while one is connected to it.
    std::atomic<bool> linked{true};
    // The last output write's verdict, posted by the pad's output worker (and its attach, for the
    // init) and read by Diagnostics.
    Mailbox<usbparsers::WriteVerdict> lastWrite;
};

// The reports a "Capture this controller" keeps, and the longest one kept whole.
inline constexpr size_t CAPTURE_REPORTS = 20;
inline constexpr size_t CAPTURE_REPORT_MAX_BYTES = 64;

struct CapturedReport {
    int64_t atMs = 0;
    uint8_t len = 0;
    uint8_t bytes[CAPTURE_REPORT_MAX_BYTES] = {};
};

// The next CAPTURE_REPORTS reports of a pad, kept on a person's command for the diagnostics bundle.
// The poller offers each report and keeps it only while a capture is armed; the JVM reads what was
// kept. A capture is armed only while none is running, so the poller is never mid-write when the
// slots are reset.
class ReportCapture {
  public:
    // False while a capture is still running.
    bool arm() {
        if (armed_.load(std::memory_order_acquire)) return false;
        filled_.store(0, std::memory_order_relaxed);
        armed_.store(true, std::memory_order_release);
        return true;
    }

    bool armed() const { return armed_.load(std::memory_order_relaxed); }

    // The poller's: one report at atMs, kept while armed; the last slot disarms it.
    void offer(const uint8_t* bytes, const size_t len, const int64_t atMs) {
        if (!armed_.load(std::memory_order_acquire)) return;
        const size_t i = filled_.load(std::memory_order_relaxed);
        if (i >= CAPTURE_REPORTS) {
            armed_.store(false, std::memory_order_release);
            return;
        }
        CapturedReport& kept = reports_[i];
        const size_t keptLen = len < CAPTURE_REPORT_MAX_BYTES ? len : CAPTURE_REPORT_MAX_BYTES;
        kept.atMs = atMs;
        kept.len = static_cast<uint8_t>(keptLen);
        // A loop, not memcpy: GCC's -Wstringop-overflow misreads the guarded reports_[i] as empty.
        for (size_t b = 0; b < keptLen; b++) kept.bytes[b] = bytes[b];
        filled_.store(i + 1, std::memory_order_release);
        if (i + 1 == CAPTURE_REPORTS) armed_.store(false, std::memory_order_release);
    }

    // Copies the reports kept so far into out; answers how many.
    size_t copyTo(CapturedReport* out, const size_t cap) const {
        const size_t filled = filled_.load(std::memory_order_acquire);
        const size_t n = filled < cap ? filled : cap;
        for (size_t i = 0; i < n; i++) out[i] = reports_[i];
        return n;
    }

  private:
    std::atomic<bool> armed_{false};
    std::atomic<size_t> filled_{0};
    CapturedReport reports_[CAPTURE_REPORTS];
};

// Where the reports of a pad with no poller go: a Bluetooth LE pad's arrive from the JVM's GATT
// client, one call per notification.
class ReportSink {
  public:
    virtual void report(const uint8_t* bytes, size_t len) = 0;

  protected:
    ~ReportSink() = default;
};

// A claimed pad's entry: its counters, the worker that owns its OUT endpoint, and, for a pad whose
// reports are handed in, their sink.
struct UsbDevice {
    UsbDeviceStats stats;
    OutputWorker output;
    ReportCapture capture;
    std::atomic<ReportSink*> reports{nullptr};
};

// A receiver slot's controller left: the charge its last report carried was that controller's.
inline void controllerLeft(UsbDeviceStats& stats) {
    stats.linked.store(false, std::memory_order_relaxed);
    stats.lastBattery.store(-1, std::memory_order_relaxed);
}

// The poller is its counters' one writer, so a load and a store count without a
// read-modify-write.
inline void bump(std::atomic<uint64_t>& counter) {
    counter.store(counter.load(std::memory_order_relaxed) + 1, std::memory_order_relaxed);
}

// MAX_USB_DEVICES fixed entries and a packed line of the pads holding them, which a reader scans
// without a lock. Attach claims an entry and detach gives it back once the poller and the output
// worker have stopped.
class UsbDeviceTable {
  public:
    UsbDeviceTable() = default;
    UsbDeviceTable(const UsbDeviceTable&) = delete;
    UsbDeviceTable& operator=(const UsbDeviceTable&) = delete;

    // An entry for a newly claimed pad, its counters at zero; null with every entry taken.
    UsbDevice* claim(const int32_t deviceId) {
        for (size_t i = 0; i < MAX_USB_DEVICES; i++) {
            uint64_t free = 0;
            if (!ids_[i].compare_exchange_strong(free, CLAIMING, std::memory_order_acq_rel)) {
                continue;
            }
            UsbDeviceStats& stats = devices_[i].stats;
            stats.urbCount.store(0, std::memory_order_relaxed);
            stats.motionCount.store(0, std::memory_order_relaxed);
            stats.urbErrorCount.store(0, std::memory_order_relaxed);
            stats.lastUrbStatus.store(0, std::memory_order_relaxed);
            stats.lastBattery.store(-1, std::memory_order_relaxed);
            stats.linked.store(true, std::memory_order_relaxed);
            stats.lastWrite.post(usbparsers::WriteVerdict{});
            devices_[i].reports.store(nullptr, std::memory_order_relaxed);
            retired_[i].store(false, std::memory_order_relaxed);
            ids_[i].store(tagOf(deviceId), std::memory_order_release);
            return &devices_[i];
        }
        return nullptr;
    }

    // Once nothing writes the entry any more.
    void release(const int32_t deviceId) {
        const int index = indexOf(deviceId);
        if (index >= 0) ids_[static_cast<size_t>(index)].store(0, std::memory_order_release);
    }

    // What readStats answers from the pad's entry; the fallback when no entry is the pad's, or when
    // its entry was given back while readStats ran.
    template <typename T, typename ReadStats>
    T read(const int32_t deviceId, const T fallback, ReadStats&& readStats) const {
        const int index = indexOf(deviceId);
        if (index < 0) return fallback;
        const T value = readStats(devices_[static_cast<size_t>(index)].stats);
        const bool stillTheSame =
            ids_[static_cast<size_t>(index)].load(std::memory_order_acquire) == tagOf(deviceId);
        return stillTheSame ? value : fallback;
    }

    // The last write's verdict; unrecorded for a pad with no entry, or one that changed pads.
    usbparsers::WriteVerdict lastWrite(const int32_t deviceId) const {
        return read<usbparsers::WriteVerdict>(
            deviceId, usbparsers::WriteVerdict{},
            [](const UsbDeviceStats& s) { return s.lastWrite.read(); });
    }

    // Arms the pad's capture; false for a pad with no entry or one already capturing.
    bool armCapture(const int32_t deviceId) {
        const int index = indexOf(deviceId);
        if (index < 0) return false;
        return devices_[static_cast<size_t>(index)].capture.arm();
    }

    // The reports the pad's capture kept so far; 0 for a pad with no entry.
    size_t capturedReports(const int32_t deviceId, CapturedReport* out, const size_t cap) const {
        const int index = indexOf(deviceId);
        if (index < 0) return 0;
        return devices_[static_cast<size_t>(index)].capture.copyTo(out, cap);
    }

    // Hands the pad's entry to post: what it posts names the pad, so a post that races the entry
    // changing pads is never applied to the next one. Nothing for a pad with no entry.
    template <typename Post> void post(const int32_t deviceId, Post&& postTo) {
        const int index = indexOf(deviceId);
        if (index >= 0) postTo(devices_[static_cast<size_t>(index)].output);
    }

    // Hands one report to the pad's sink, holding the entry while it does, so retire can wait it
    // out. False for a pad with no entry or no sink, and for one being retired.
    bool deliver(const int32_t deviceId, const uint8_t* bytes, const size_t len) {
        const int index = indexOf(deviceId);
        if (index < 0) return false;
        const size_t i = static_cast<size_t>(index);
        users_[i].fetch_add(1, std::memory_order_seq_cst);
        ReportSink* const sink = openSinkOf(i, deviceId);
        if (sink != nullptr) sink->report(bytes, len);
        users_[i].fetch_sub(1, std::memory_order_seq_cst);
        return sink != nullptr;
    }

    // No report reaches the pad's sink once this returns, and none is still on its way there.
    void retire(const int32_t deviceId) {
        const int index = indexOf(deviceId);
        if (index < 0) return;
        const size_t i = static_cast<size_t>(index);
        retired_[i].store(true, std::memory_order_seq_cst);
        while (users_[i].load(std::memory_order_seq_cst) != 0) std::this_thread::yield();
    }

  private:
    // Marks an entry being claimed: never a pad's tag, which always has bit 32 set.
    static constexpr uint64_t CLAIMING = 1;

    static uint64_t tagOf(const int32_t deviceId) {
        return (uint64_t{1} << 32) | static_cast<uint32_t>(deviceId);
    }

    // The sink a delivery may use: the pad's own, while the entry is still the pad's and not
    // retired. The users count rises before this reads and retire marks before it counts, so one
    // of the two always sees the other.
    ReportSink* openSinkOf(const size_t i, const int32_t deviceId) const {
        const bool isRetired = retired_[i].load(std::memory_order_seq_cst);
        const bool isStillThePads = ids_[i].load(std::memory_order_seq_cst) == tagOf(deviceId);
        if (isRetired || !isStillThePads) return nullptr;
        return devices_[i].reports.load(std::memory_order_acquire);
    }

    int indexOf(const int32_t deviceId) const {
        const uint64_t tag = tagOf(deviceId);
        for (size_t i = 0; i < MAX_USB_DEVICES; i++) {
            if (ids_[i].load(std::memory_order_acquire) == tag) return static_cast<int>(i);
        }
        return -1;
    }

    std::array<std::atomic<uint64_t>, MAX_USB_DEVICES> ids_ = {};
    std::array<UsbDevice, MAX_USB_DEVICES> devices_;
    // The deliveries under way, never reset: one racing a turnover takes its own count back out.
    std::array<std::atomic<uint32_t>, MAX_USB_DEVICES> users_ = {};
    // Whether the entry's pad is being retired; claim clears it for the next pad.
    std::array<std::atomic<bool>, MAX_USB_DEVICES> retired_ = {};
};

} // namespace dish_usb
