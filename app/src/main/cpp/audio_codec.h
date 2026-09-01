// SPDX-License-Identifier: LGPL-3.0-or-later

// libopus behind a RAII wrapper, pinned to the two controller-audio stream
// formats (satellite docs/contract.md §Controller audio). Nothing here is
// negotiated at runtime: the wire fixes the rate, the window and the channel
// count in both directions, so this header is only about lifetime and the
// four entry points the streams need.
//
// The client encodes mic and decodes speaker; satellite does the reverse. Both
// directions of both streams are built anyway, because that is what lets the
// test suite close the loop on a stream instead of asserting against a second
// implementation of the same constants (satellite's opus_codec.h says the same
// thing from the other side).
#pragma once

#include <cstddef>
#include <cstdint>
#include <memory>

// libopus's handle types, forward-declared rather than including <opus.h>: this
// header is reached from the JNI translation unit, which has no business
// carrying the codec's include path. opus.h's own
// `typedef struct OpusEncoder OpusEncoder` agrees with these declarations.
struct OpusEncoder;
struct OpusDecoder;

namespace dish_audio {

// The wire format, mirrored from satellite core/types.h. Opus resamples to
// 48 kHz internally regardless, so pinning the rate costs nothing and spares
// both ends a resampler; 20 ms is also the emulated pad's USB-audio service
// interval, so no side re-windows.
inline constexpr int AUDIO_SAMPLE_RATE_HZ = 48000;
inline constexpr int AUDIO_FRAME_MS = 20;
inline constexpr int AUDIO_FRAME_SAMPLES = AUDIO_SAMPLE_RATE_HZ / 1000 * AUDIO_FRAME_MS; // per ch
// Mic is the pad's headset microphone (mono); speaker is channels 1/2 of the
// DualSense 4-channel OUT stream, its speaker and headset jack (stereo).
inline constexpr int AUDIO_MIC_CHANNELS = 1;
inline constexpr int AUDIO_SPEAKER_CHANNELS = 2;
// One decoded speaker frame, interleaved, as handed to Kotlin.
inline constexpr int AUDIO_SPEAKER_FRAME_SAMPLES = AUDIO_FRAME_SAMPLES * AUDIO_SPEAKER_CHANNELS;

// Which of the two wire streams an instance is pinned to. The distinction is
// not just channel count: the mic runs Opus's VOIP application at a bitrate
// where in-band FEC exists, the speaker runs the AUDIO application at a bitrate
// where fidelity matters more (audio_codec.cpp carries the numbers and why).
enum class Stream { Mic, Speaker };

// Declared here, defined in audio_codec.cpp, so the unique_ptrs below work
// against the incomplete handle types above.
struct OpusEncoderDeleter {
    void operator()(::OpusEncoder* enc) const noexcept;
};
struct OpusDecoderDeleter {
    void operator()(::OpusDecoder* dec) const noexcept;
};

// One controller's inbound stream. Stateful (decoders carry filter state and a
// concealment history across frames), so one instance per controller, never
// shared, destroyed with the pad it belongs to.
//
// Every entry returns FRAMES (samples per channel) written, 0 on failure, and
// writes frames * channels interleaved int16 samples. `maxFrames` is capacity
// for decode(); for the two concealment entries it must be at least one whole
// AUDIO_FRAME_SAMPLES window, because the codec has to be told exactly how much
// audio is missing.
class OpusStreamDecoder {
  public:
    // Null when libopus refuses to allocate. Callers treat that as "no codec"
    // rather than fatal: a controller without audio is still a controller.
    static std::unique_ptr<OpusStreamDecoder> create(Stream stream);

    size_t decode(const uint8_t* opus, size_t opusLen, int16_t* pcm, size_t maxFrames);

    // No packet at all: synthesize one frame from the decoder's own history.
    size_t conceal(int16_t* pcm, size_t maxFrames);

    // Recover the frame BEFORE `opus` from the in-band FEC copy that packet
    // carries. Degrades to plain concealment when it turns out to carry none,
    // so a caller never has to ask first (nor could it: whether a packet holds
    // FEC data is an encoder-side decision made per packet).
    size_t decodeFec(const uint8_t* opus, size_t opusLen, int16_t* pcm, size_t maxFrames);

    int channels() const { return channels_; }

  private:
    OpusStreamDecoder(std::unique_ptr<::OpusDecoder, OpusDecoderDeleter> dec, int channels)
        : dec_(std::move(dec)), channels_(channels) {}

    std::unique_ptr<::OpusDecoder, OpusDecoderDeleter> dec_;
    int channels_ = 1;
};

// One controller's outbound stream. `frames` is per channel and must be exactly
// one AUDIO_FRAME_SAMPLES window: the wire carries one 20 ms packet per message
// and the windowing is the caller's job. Returns bytes written, 0 on failure.
class OpusStreamEncoder {
  public:
    // Null when libopus refuses to allocate; see OpusStreamDecoder::create.
    static std::unique_ptr<OpusStreamEncoder> create(Stream stream);

    size_t encode(const int16_t* pcm, size_t frames, uint8_t* out, size_t maxOut);

    int channels() const { return channels_; }

  private:
    OpusStreamEncoder(std::unique_ptr<::OpusEncoder, OpusEncoderDeleter> enc, int channels)
        : enc_(std::move(enc)), channels_(channels) {}

    std::unique_ptr<::OpusEncoder, OpusEncoderDeleter> enc_;
    int channels_ = 1;
};

} // namespace dish_audio
