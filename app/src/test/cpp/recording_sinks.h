// SPDX-License-Identifier: LGPL-3.0-or-later

#pragma once

#include <errno.h>

#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <memory>
#include <mutex>
#include <thread>
#include <utility>
#include <vector>

#include "receive_path.h"
#include "session_table.h"
#include "slot_table.h"
#include "udp_socket.h"
#include "wire_encoders.h"

// Stand-ins for what the units hand on: the socket (a DatagramPort that records), the bridge
// thread's queue and the Kotlin feedback upcalls.
namespace recording {

using Bytes = std::vector<uint8_t>;

struct Sent {
    Bytes bytes;
    std::thread::id thread;
};

// Records every datagram instead of sending it, or refuses each with an errno.
class Port {
  public:
    void attachTo(dish_session::Session& session) {
        session.port = dish_net::DatagramPort{&Port::send, this};
    }

    void refuseWith(const int error) {
        std::lock_guard<std::mutex> lock(mtx_);
        refuseErrno_ = error;
    }

    std::vector<Sent> taken() {
        std::lock_guard<std::mutex> lock(mtx_);
        return std::exchange(sent_, {});
    }

  private:
    static ssize_t send(void* context, int, const uint8_t* packet, const size_t len,
                        const sockaddr_in&) {
        Port& self = *static_cast<Port*>(context);
        std::lock_guard<std::mutex> lock(self.mtx_);
        if (self.refuseErrno_ != 0) {
            errno = self.refuseErrno_;
            return -1;
        }
        self.sent_.push_back(Sent{Bytes(packet, packet + len), std::this_thread::get_id()});
        return static_cast<ssize_t>(len);
    }

    std::mutex mtx_;
    std::vector<Sent> sent_;
    int refuseErrno_ = 0;
};

// A socket whose next send blocks, holding its sender inside sendto (and so inside the gate),
// until the test lets it go; every datagram is recorded in the order it reached the socket.
class HeldPort {
  public:
    void attachTo(dish_session::Session& s) {
        s.port = dish_net::DatagramPort{&HeldPort::send, this};
    }

    void holdNext() {
        std::lock_guard<std::mutex> lock(mtx_);
        holdNext_ = true;
    }

    bool waitUntilHolding() {
        std::unique_lock<std::mutex> lock(mtx_);
        return cv_.wait_for(lock, std::chrono::seconds(5), [this] { return holding_; });
    }

    void letGo() {
        std::lock_guard<std::mutex> lock(mtx_);
        released_ = true;
        cv_.notify_all();
    }

    std::vector<Bytes> taken() {
        std::lock_guard<std::mutex> lock(mtx_);
        return std::exchange(sent_, {});
    }

  private:
    static ssize_t send(void* context, int, const uint8_t* packet, const size_t len,
                        const sockaddr_in&) {
        HeldPort& self = *static_cast<HeldPort*>(context);
        std::unique_lock<std::mutex> lock(self.mtx_);
        self.sent_.emplace_back(packet, packet + len);
        if (self.holdNext_) {
            self.holdNext_ = false;
            self.holding_ = true;
            self.cv_.notify_all();
            self.cv_.wait(lock, [&self] { return self.released_; });
        }
        return static_cast<ssize_t>(len);
    }

    std::mutex mtx_;
    std::condition_variable cv_;
    std::vector<Bytes> sent_;
    bool holdNext_ = false;
    bool holding_ = false;
    bool released_ = false;
};

class Bridge final : public dish_slots::BridgeSink {
  public:
    void push(const dish_slots::BridgeReport& report) override {
        std::lock_guard<std::mutex> lock(mtx_);
        reports_.push_back(report);
    }

    std::vector<dish_slots::BridgeReport> taken() {
        std::lock_guard<std::mutex> lock(mtx_);
        return std::exchange(reports_, {});
    }

  private:
    std::mutex mtx_;
    std::vector<dish_slots::BridgeReport> reports_;
};

struct Rumble {
    int handle;
    uint8_t ctrlIdx;
    uint16_t strong;
    uint16_t weak;
    uint16_t durationMs;
};

struct Audio {
    int handle;
    int lane;
    uint8_t ctrlIdx;
    uint16_t seq;
    Bytes opus;
};

class Feedback final : public dish_receive::FeedbackSink {
  public:
    void rumble(const int handle, const uint8_t ctrlIdx, const uint16_t strong, const uint16_t weak,
                const uint16_t durationMs) override {
        std::lock_guard<std::mutex> lock(mtx_);
        rumbles.push_back(Rumble{handle, ctrlIdx, strong, weak, durationMs});
    }
    void lightbar(int, const dish_wire::LightbarPayload& lb) override {
        std::lock_guard<std::mutex> lock(mtx_);
        lightbars.push_back(lb);
    }
    void triggerEffects(int, const uint8_t ctrlIdx, const uint8_t* blocks) override {
        std::lock_guard<std::mutex> lock(mtx_);
        Bytes effect{ctrlIdx};
        effect.insert(effect.end(), blocks, blocks + dish_wire::TRIGGER_EFFECTS_PAYLOAD_BYTES);
        triggerEffectsSeen.push_back(effect);
    }
    void playerLeds(int, const dish_wire::PlayerLedsPayload& leds) override {
        std::lock_guard<std::mutex> lock(mtx_);
        playerLedsSeen.push_back(leds);
    }
    void micLed(int, const dish_wire::MicLedPayload& led) override {
        std::lock_guard<std::mutex> lock(mtx_);
        micLeds.push_back(led);
    }
    void audio(const int handle, const int lane, const dish_wire::AudioFrameHeader& header,
               const uint8_t* opus, const size_t opusLen) override {
        std::lock_guard<std::mutex> lock(mtx_);
        audioSeen.push_back(
            Audio{handle, lane, header.ctrlIdx, header.seq, Bytes(opus, opus + opusLen)});
    }

    size_t calls() {
        std::lock_guard<std::mutex> lock(mtx_);
        return rumbles.size() + lightbars.size() + triggerEffectsSeen.size() +
               playerLedsSeen.size() + micLeds.size() + audioSeen.size();
    }

    std::vector<Rumble> rumbles;
    std::vector<dish_wire::LightbarPayload> lightbars;
    std::vector<Bytes> triggerEffectsSeen;
    std::vector<dish_wire::PlayerLedsPayload> playerLedsSeen;
    std::vector<dish_wire::MicLedPayload> micLeds;
    std::vector<Audio> audioSeen;

  private:
    std::mutex mtx_;
};

} // namespace recording
