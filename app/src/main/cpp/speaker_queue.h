// SPDX-License-Identifier: LGPL-3.0-or-later

#pragma once

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <vector>

#include "audio_jitter.h"
#include "mpmc_ring.h"
#include "session_table.h"
#include "wire_encoders.h"
#include "worker_wake.h"

// The inbound MSG_SPEAKER_AUDIO / MSG_HAPTIC_AUDIO hand-off: the receive thread copies each Opus
// packet in, and the audio dispatch thread reorders, decodes and conceals it off the socket, so a
// sink that blocks can never stall the session's receive loop.
namespace dish_receive {

// One received packet, copied whole into a ring cell.
struct SpeakerFrame {
    int32_t handle = -1;
    uint8_t ctrlIdx = 0;
    uint8_t lane = 0;
    uint16_t seq = 0;
    uint16_t opusLen = 0;
    uint8_t opus[dish_wire::AUDIO_WIRE_MAX_OPUS_BYTES] = {};
};

// Where decoded audio goes: one interleaved-stereo frame of AUDIO_SPEAKER_FRAME_SAMPLES samples
// per call, on the audio dispatch thread.
class SpeakerSink {
  public:
    virtual void pcm(int handle, uint8_t ctrlIdx, int lane, const int16_t* pcm, bool concealed) = 0;

  protected:
    ~SpeakerSink() = default;
};

class SpeakerQueue {
  public:
    // 8 frames = 160 ms. A backlog past that is audio too old to play on time, and dropping the
    // oldest is exactly what the reorder window's gap handling covers.
    static constexpr size_t FRAMES = 8;

    SpeakerQueue();
    SpeakerQueue(const SpeakerQueue&) = delete;
    SpeakerQueue& operator=(const SpeakerQueue&) = delete;

    // The receive thread. A packet longer than the wire allows is not queued.
    void push(int handle, int lane, const dish_wire::AudioFrameHeader& header, const uint8_t* opus,
              size_t opusLen);

    // The audio dispatch thread: sleeps until a frame is queued or stop is called. False once
    // stopped with the ring empty.
    bool waitForWork();

    // Decodes every queued frame of a session that is still open into the sink, and answers how
    // many frames it took off the ring.
    size_t drain(dish_session::SessionTable& sessions, SpeakerSink& sink);

    void stop();

    uint64_t droppedFrames() const { return dropped_.load(std::memory_order_relaxed); }

  private:
    bool hasWork() const;

    dish_hot::MpmcRing<SpeakerFrame, FRAMES> frames_;
    dish_hot::WorkerWake wake_;
    std::atomic<bool> stopping_{false};
    std::atomic<uint64_t> dropped_{0};
    // The dispatch thread's: one push can drain the whole reorder window and conceal ahead of it,
    // so it is sized for the worst case once rather than reallocated.
    std::vector<int16_t> pcm_;
    bool concealed_[dish_audio::AUDIO_JITTER_MAX_EVENTS_PER_PUSH] = {};
};

} // namespace dish_receive
