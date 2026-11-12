// SPDX-License-Identifier: LGPL-3.0-or-later

#pragma once

#include <atomic>
#include <chrono>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <thread>
#include <type_traits>

#include "mpmc_ring.h"
#include "usb_parsers.h"
#include "worker_wake.h"

// A claimed USB pad's OUT endpoint, owned by one worker thread. Whoever has feedback for the pad
// (the receive thread through Kotlin, a rumble's stop timer, the poller's GIP acks) only stores it
// and wakes the worker, so no producer ever waits on an OUT transfer.
namespace dish_usb {

inline constexpr size_t TRIGGER_EFFECT_BYTES = usbparsers::TRIGGER_EFFECT_BLOCK_LEN;

// The clock a worker schedules its refreshes on, and the one a device stamps its writes with.
inline int64_t outputClockMs() {
    const auto sinceEpoch = std::chrono::steady_clock::now().time_since_epoch();
    return std::chrono::duration_cast<std::chrono::milliseconds>(sinceEpoch).count();
}

// The writes a pad's worker performs: over USBDEVFS in usb_host.cpp, recorded by a fake in tests.
class OutputDevice {
  public:
    virtual void rumble(uint16_t strong, uint16_t weak) = 0;
    virtual void triggerRumble(uint16_t left, uint16_t right) = 0;
    virtual void lightbar(uint8_t r, uint8_t g, uint8_t b) = 0;
    virtual void playerLeds(uint8_t ledMask) = 0;
    virtual void triggerEffects(const uint8_t* left, const uint8_t* right) = 0;
    virtual void micLed(uint8_t state) = 0;
    virtual void ack(const uint8_t* bytes, size_t len) = 0;
    // The pad is back on its dongle, rebooted into its stand-alone defaults.
    virtual void reconnected() = 0;
    // The pad left its receiver slot.
    virtual void left() = 0;
    // Re-sends what the pad forgets unless the host repeats it (usbparsers::refreshDue) at nowMs,
    // and answers the milliseconds until it next owes a write, or usbparsers::NO_REFRESH.
    virtual uint32_t refresh(int64_t nowMs) = 0;

  protected:
    ~OutputDevice() = default;
};

// The newest value of one kind, under a seqlock any producer may write (two producers of one kind
// exclude each other by compare-exchange on the generation) and the worker reads. Every value
// names the pad it was posted for, so one posted just as the entry changed pads is never applied
// to the next pad.
template <typename T> class Mailbox {
    static_assert(std::is_trivially_copyable_v<T>, "a mailbox value is stored as words");

  public:
    void post(const T& value) {
        uint32_t gen = gen_.load(std::memory_order_relaxed);
        for (;;) {
            if ((gen & 1u) != 0) {
                std::this_thread::yield();
                gen = gen_.load(std::memory_order_relaxed);
                continue;
            }
            if (gen_.compare_exchange_weak(gen, gen + 1, std::memory_order_acq_rel,
                                           std::memory_order_relaxed)) {
                break;
            }
        }
        std::atomic_thread_fence(std::memory_order_release);
        uint64_t raw[WORDS] = {};
        memcpy(raw, &value, sizeof(value));
        for (size_t i = 0; i < WORDS; i++) words_[i].store(raw[i], std::memory_order_relaxed);
        gen_.store(gen + 2, std::memory_order_release);
    }

    T read() const {
        for (;;) {
            const uint32_t before = gen_.load(std::memory_order_acquire);
            if ((before & 1u) != 0) {
                std::this_thread::yield();
                continue;
            }
            uint64_t raw[WORDS];
            for (size_t i = 0; i < WORDS; i++) raw[i] = words_[i].load(std::memory_order_relaxed);
            std::atomic_thread_fence(std::memory_order_acquire);
            if (gen_.load(std::memory_order_relaxed) != before) continue;
            T value;
            memcpy(&value, raw, sizeof(value));
            return value;
        }
    }

  private:
    static constexpr size_t WORDS = (sizeof(T) + sizeof(uint64_t) - 1) / sizeof(uint64_t);
    std::atomic<uint32_t> gen_{0};
    std::atomic<uint64_t> words_[WORDS] = {};
};

struct RumbleOut {
    int32_t deviceId = 0;
    uint16_t strong = 0;
    uint16_t weak = 0;
};

struct TriggerRumbleOut {
    int32_t deviceId = 0;
    uint16_t left = 0;
    uint16_t right = 0;
};

struct LightbarOut {
    int32_t deviceId = 0;
    uint8_t r = 0, g = 0, b = 0;
};

struct PlayerLedsOut {
    int32_t deviceId = 0;
    uint8_t ledMask = 0;
};

struct TriggerEffectsOut {
    int32_t deviceId = 0;
    uint8_t left[TRIGGER_EFFECT_BYTES] = {};
    uint8_t right[TRIGGER_EFFECT_BYTES] = {};
};

struct MicLedOut {
    int32_t deviceId = 0;
    uint8_t state = 0;
};

// Whether the pad's controller is on its receiver slot: the newest of a connect and a leave wins.
struct LinkOut {
    int32_t deviceId = 0;
    bool present = false;
};

// A GIP ack: each one acknowledges its own packet, so acks queue in order instead of coalescing.
struct AckOut {
    int32_t deviceId = 0;
    uint8_t len = 0;
    uint8_t bytes[usbparsers::kGipAckBytes] = {};
};

class OutputWorker {
  public:
    // Acks a stalled worker has not written yet; past that the oldest goes, and the pad resends it.
    static constexpr size_t ACKS = 16;

    OutputWorker() = default;
    ~OutputWorker() { stop(); }
    OutputWorker(const OutputWorker&) = delete;
    OutputWorker& operator=(const OutputWorker&) = delete;

    // Attach: the pad's worker starts, owning the device's writes until stop.
    void start(int32_t deviceId, OutputDevice& device);
    // Detach: joins the worker. Nothing posted afterwards is written.
    void stop();

    void postRumble(int32_t deviceId, uint16_t strong, uint16_t weak);
    void postTriggerRumble(int32_t deviceId, uint16_t left, uint16_t right);
    void postLightbar(int32_t deviceId, uint8_t r, uint8_t g, uint8_t b);
    void postPlayerLeds(int32_t deviceId, uint8_t ledMask);
    void postTriggerEffects(int32_t deviceId, const uint8_t* left, const uint8_t* right);
    void postMicLed(int32_t deviceId, uint8_t state);
    // The poller's, for a report that asked for one; never blocks it.
    void postAck(int32_t deviceId, const uint8_t* bytes, size_t len);
    // The poller's, for a dongle's connect event: the re-init it owes runs here, off the poller.
    void postReconnected(int32_t deviceId);
    // The poller's, for a receiver slot whose controller left.
    void postLeft(int32_t deviceId);

    uint64_t droppedAcks() const { return droppedAcks_.load(std::memory_order_relaxed); }

  private:
    enum Kind : uint32_t {
        RUMBLE = 1u << 0,
        TRIGGER_RUMBLE = 1u << 1,
        LIGHTBAR = 1u << 2,
        PLAYER_LEDS = 1u << 3,
        TRIGGER_EFFECTS = 1u << 4,
        MIC_LED = 1u << 5,
        LINK = 1u << 6,
    };

    void announce(uint32_t kind);
    bool hasWork();
    void sleepUntilWorkOrRefresh(uint32_t refreshInMs);
    void run();
    void writeAcks();
    void writeLink(const LinkOut& link);
    void writePending(uint32_t kinds);

    // Written by start and stop only, read by the worker it starts.
    int32_t deviceId_ = 0;
    OutputDevice* device_ = nullptr;
    std::thread thread_;
    std::atomic<bool> running_{false};

    Mailbox<RumbleOut> rumble_;
    Mailbox<TriggerRumbleOut> triggerRumble_;
    Mailbox<LightbarOut> lightbar_;
    Mailbox<PlayerLedsOut> playerLeds_;
    Mailbox<TriggerEffectsOut> triggerEffects_;
    Mailbox<MicLedOut> micLed_;
    Mailbox<LinkOut> link_;
    // The kinds posted since the worker last took them.
    std::atomic<uint32_t> pending_{0};
    dish_hot::MpmcRing<AckOut, ACKS> acks_;
    std::atomic<uint64_t> droppedAcks_{0};
    dish_hot::WorkerWake wake_;
};

} // namespace dish_usb
