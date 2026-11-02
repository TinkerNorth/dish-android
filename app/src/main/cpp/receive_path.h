// SPDX-License-Identifier: LGPL-3.0-or-later

#pragma once

#include <cstddef>
#include <cstdint>
#include <vector>

#include "audio_codec.h"
#include "audio_jitter.h"
#include "session_table.h"
#include "wire_encoders.h"

// The session's receive loop: one datagram from the satellite opened, checked and handed on.
namespace dish_receive {

// The two outbound lanes as SpeakerAudioBridge numbers them.
inline constexpr int AUDIO_LANE_SPEAKER = 0;
inline constexpr int AUDIO_LANE_HAPTICS = 1;

// Where the receive thread hands each feedback datagram, in arrival order, on that thread: the
// JNI layer upcalls into Kotlin for all but audio, which goes onto the SpeakerQueue.
class FeedbackSink {
  public:
    virtual void rumble(int handle, uint8_t ctrlIdx, uint16_t strong, uint16_t weak,
                        uint16_t durationMs) = 0;
    virtual void lightbar(int handle, const dish_wire::LightbarPayload& lightbar) = 0;
    // blocks is dish_wire::TRIGGER_EFFECTS_PAYLOAD_BYTES long: left then right.
    virtual void triggerEffects(int handle, uint8_t ctrlIdx, const uint8_t* blocks) = 0;
    virtual void playerLeds(int handle, const dish_wire::PlayerLedsPayload& leds) = 0;
    virtual void micLed(int handle, const dish_wire::MicLedPayload& led) = 0;
    virtual void audio(int handle, int lane, const dish_wire::AudioFrameHeader& header,
                       const uint8_t* opus, size_t opusLen) = 0;

  protected:
    ~FeedbackSink() = default;
};

// One pass of the receive loop: -1 when the loop must stop (no session, a closed socket, or a
// receive error that would recur at once), 0 for the receive timeout or a datagram dropped as
// foreign, replayed or forged, 1 for a datagram opened and handled.
int receiveDatagram(dish_session::SessionTable& sessions, int handle, FeedbackSink& sink);

// The open-and-dispatch half for a datagram already in hand: 0 or 1 as above.
int handleDatagram(const dish_session::SessionRef& session, const uint8_t* buf, size_t n,
                   FeedbackSink& sink);

// Reorder + decode one received speaker or haptic frame into `pcm` (which holds up to
// AUDIO_JITTER_MAX_EVENTS_PER_PUSH consecutive interleaved-stereo frames). Returns how many frames
// it produced and, per frame, whether it was concealed rather than decoded from a packet that
// actually arrived; nothing for a session that is no longer open.
int decodeSpeakerAudio(const dish_session::SessionRef& session, uint8_t ctrlIdx, int lane,
                       uint16_t seq, const uint8_t* opus, size_t opusLen, std::vector<int16_t>& pcm,
                       bool concealed[dish_audio::AUDIO_JITTER_MAX_EVENTS_PER_PUSH]);

} // namespace dish_receive
