// SPDX-License-Identifier: LGPL-3.0-or-later

#pragma once

#include <array>
#include <atomic>
#include <cstddef>
#include <cstdint>

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
};

// A claimed pad's entry: its counters, and the worker that owns its OUT endpoint.
struct UsbDevice {
    UsbDeviceStats stats;
    OutputWorker output;
};

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

    // Hands the pad's entry to post: what it posts names the pad, so a post that races the entry
    // changing pads is never applied to the next one. Nothing for a pad with no entry.
    template <typename Post> void post(const int32_t deviceId, Post&& postTo) {
        const int index = indexOf(deviceId);
        if (index >= 0) postTo(devices_[static_cast<size_t>(index)].output);
    }

  private:
    // Marks an entry being claimed: never a pad's tag, which always has bit 32 set.
    static constexpr uint64_t CLAIMING = 1;

    static uint64_t tagOf(const int32_t deviceId) {
        return (uint64_t{1} << 32) | static_cast<uint32_t>(deviceId);
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
};

} // namespace dish_usb
