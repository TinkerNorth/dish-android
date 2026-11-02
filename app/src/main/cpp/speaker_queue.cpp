// SPDX-License-Identifier: LGPL-3.0-or-later

#include "speaker_queue.h"

#include <string.h>

#include "audio_codec.h"
#include "receive_path.h"

namespace dish_receive {

SpeakerQueue::SpeakerQueue()
    : pcm_(static_cast<size_t>(dish_audio::AUDIO_JITTER_MAX_EVENTS_PER_PUSH) *
           dish_audio::AUDIO_SPEAKER_FRAME_SAMPLES) {}

void SpeakerQueue::push(const int handle, const int lane, const dish_wire::AudioFrameHeader& header,
                        const uint8_t* opus, const size_t opusLen) {
    if (opusLen > sizeof(SpeakerFrame::opus)) return;
    SpeakerFrame frame;
    frame.handle = handle;
    frame.ctrlIdx = header.ctrlIdx;
    frame.lane = static_cast<uint8_t>(lane);
    frame.seq = header.seq;
    frame.opusLen = static_cast<uint16_t>(opusLen);
    memcpy(frame.opus, opus, opusLen);
    const uint32_t dropped = frames_.pushDroppingOldest(frame);
    if (dropped > 0) dropped_.fetch_add(dropped, std::memory_order_relaxed);
    wake_.notify();
}

bool SpeakerQueue::hasWork() const {
    return stopping_.load(std::memory_order_seq_cst) || !frames_.empty();
}

bool SpeakerQueue::waitForWork() {
    wake_.sleepUnless([this] { return hasWork(); });
    return !(stopping_.load(std::memory_order_seq_cst) && frames_.empty());
}

size_t SpeakerQueue::drain(dish_session::SessionTable& sessions, SpeakerSink& sink) {
    size_t taken = 0;
    SpeakerFrame frame;
    while (frames_.tryPop(frame)) {
        taken++;
        const int produced =
            decodeSpeakerAudio(sessions.find(frame.handle), frame.ctrlIdx, frame.lane, frame.seq,
                               frame.opus, frame.opusLen, pcm_, concealed_);
        for (int i = 0; i < produced; i++) {
            sink.pcm(frame.handle, frame.ctrlIdx, frame.lane,
                     pcm_.data() + static_cast<size_t>(i) * dish_audio::AUDIO_SPEAKER_FRAME_SAMPLES,
                     concealed_[i]);
        }
    }
    return taken;
}

void SpeakerQueue::stop() {
    stopping_.store(true, std::memory_order_seq_cst);
    wake_.notify();
}

} // namespace dish_receive
