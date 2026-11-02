// SPDX-License-Identifier: LGPL-3.0-or-later

#pragma once

#include <atomic>
#include <cstddef>
#include <cstdint>

#include "mpmc_ring.h"
#include "slot_table.h"
#include "worker_wake.h"

// What the input threads hand the bridge thread: Bluetooth and Moonlight reports, and the pads'
// mic-mute edges. Producers are the USB pollers, the UI thread and the binding calls; the one
// consumer is the bridge thread, which makes the JVM upcalls.
namespace dish_bridge {

// A pad's mic-mute latch flipped.
struct MicMuteEdge {
    int32_t deviceId = 0;
    bool muted = false;
};

// What the bridge thread does with each item, in the order drain hands them over.
class BridgeConsumer {
  public:
    virtual void report(const dish_slots::BridgeReport& report) = 0;
    virtual void micMute(int32_t deviceId, bool muted) = 0;

  protected:
    ~BridgeConsumer() = default;
};

class BridgeQueue final : public dish_slots::BridgeSink {
  public:
    // A report older than 64 newer ones is a stale stick position, worth nothing.
    static constexpr size_t REPORTS = 64;
    // One per button press; a full ring makes the presser wait rather than lose an edge.
    static constexpr size_t MIC_MUTE_EDGES = 16;

    BridgeQueue() = default;
    BridgeQueue(const BridgeQueue&) = delete;
    BridgeQueue& operator=(const BridgeQueue&) = delete;

    // Drops the oldest report when the ring is full: a stale stick position is worth nothing.
    void push(const dish_slots::BridgeReport& report) override;

    // Never drops: a lost mute edge would leave the app believing a muted pad is live.
    void pushMicMute(int32_t deviceId, bool muted);

    // The bridge thread: sleeps until there is something to drain or stop is called. False once
    // stopped with both rings empty.
    bool waitForWork();

    // Hands over everything queued, each mute edge before any report queued with it, and answers
    // how many items it handed over.
    size_t drain(BridgeConsumer& consumer);

    void stop();

    uint64_t droppedReports() const { return dropped_.load(std::memory_order_relaxed); }

  private:
    bool hasWork() const;

    dish_hot::MpmcRing<dish_slots::BridgeReport, REPORTS> reports_;
    dish_hot::MpmcRing<MicMuteEdge, MIC_MUTE_EDGES> mutes_;
    dish_hot::WorkerWake wake_;
    std::atomic<bool> stopping_{false};
    std::atomic<uint64_t> dropped_{0};
};

} // namespace dish_bridge
