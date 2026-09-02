// SPDX-License-Identifier: LGPL-3.0-or-later

#include "audio_codec.h"

#include <opus.h>

namespace dish_audio {
namespace {

// The wire's formats, in one place, matching satellite's opus_codec.cpp knob
// for knob. Both streams are 48 kHz, 20 ms, VBR with in-band FEC requested;
// what differs is the application and the bitrate.
//
// Mic: 32 kbps mono under OPUS_APPLICATION_VOIP, with in-band FEC -- the
// redundant low-rate copy of the previous frame that lets the satellite recover
// a single lost packet instead of guessing at it. The expected-loss hint is
// what makes the encoder actually spend bits on that copy; without it the flag
// alone does nothing. It is also what picks the MODE: the hint forces SILK in,
// so both streams encode as Hybrid fullband and both really do carry FEC.
// Dropping it to zero would hand the speaker to CELT and silently delete that.
//
// Speaker: 96 kbps stereo under OPUS_APPLICATION_AUDIO, because that stream
// carries game and chat audio a player listens to rather than speech a codec
// can model. The client only decodes it, so these two are here for the test
// suite and for whoever next asks what the far end is sending.
constexpr int OPUS_MIC_BITRATE_BPS = 32000;
constexpr int OPUS_SPEAKER_BITRATE_BPS = 96000;
constexpr int OPUS_EXPECTED_PACKET_LOSS_PCT = 10;

int channelsFor(Stream stream) {
    return stream == Stream::Mic ? AUDIO_MIC_CHANNELS : AUDIO_SPEAKER_CHANNELS;
}

} // namespace

void OpusEncoderDeleter::operator()(::OpusEncoder* enc) const noexcept {
    if (enc != nullptr) opus_encoder_destroy(enc);
}

void OpusDecoderDeleter::operator()(::OpusDecoder* dec) const noexcept {
    if (dec != nullptr) opus_decoder_destroy(dec);
}

// ---- decoder ---------------------------------------------------------------

std::unique_ptr<OpusStreamDecoder> OpusStreamDecoder::create(Stream stream) {
    const int channels = channelsFor(stream);
    int err = OPUS_OK;
    std::unique_ptr<::OpusDecoder, OpusDecoderDeleter> dec(
        opus_decoder_create(AUDIO_SAMPLE_RATE_HZ, channels, &err));
    if (!dec || err != OPUS_OK) return nullptr;
    // The decoder carries no format negotiation: a stream's parameters travel
    // inside each Opus packet, so there is nothing else to set here.
    return std::unique_ptr<OpusStreamDecoder>(new OpusStreamDecoder(std::move(dec), channels));
}

size_t OpusStreamDecoder::decode(const uint8_t* opus, size_t opusLen, int16_t* pcm,
                                 size_t maxFrames) {
    if (opus == nullptr || opusLen == 0 || pcm == nullptr || maxFrames == 0) return 0;
    if (opusLen > static_cast<size_t>(INT32_MAX) || maxFrames > static_cast<size_t>(INT32_MAX)) {
        return 0;
    }
    const int n = opus_decode(dec_.get(), opus, static_cast<opus_int32>(opusLen), pcm,
                              static_cast<int>(maxFrames), /*decode_fec=*/0);
    return n > 0 ? static_cast<size_t>(n) : 0;
}

size_t OpusStreamDecoder::conceal(int16_t* pcm, size_t maxFrames) {
    // A null packet is how libopus is asked for concealment, and unlike a real
    // decode the frame size is an instruction rather than a capacity: it must
    // be the duration of what is missing, which on this wire is always one
    // 20 ms window.
    if (pcm == nullptr || maxFrames < static_cast<size_t>(AUDIO_FRAME_SAMPLES)) return 0;
    const int n = opus_decode(dec_.get(), nullptr, 0, pcm, AUDIO_FRAME_SAMPLES, /*decode_fec=*/0);
    return n > 0 ? static_cast<size_t>(n) : 0;
}

size_t OpusStreamDecoder::decodeFec(const uint8_t* opus, size_t opusLen, int16_t* pcm,
                                    size_t maxFrames) {
    if (opus == nullptr || opusLen == 0) return conceal(pcm, maxFrames);
    if (pcm == nullptr || maxFrames < static_cast<size_t>(AUDIO_FRAME_SAMPLES)) return 0;
    if (opusLen > static_cast<size_t>(INT32_MAX)) return 0;
    // decode_fec asks for the frame BEFORE this packet, so the frame size is
    // again the missing duration, not this packet's. libopus falls back to
    // concealment by itself when the packet carries no FEC data, which is why
    // callers can take this path unconditionally on a gap.
    const int n = opus_decode(dec_.get(), opus, static_cast<opus_int32>(opusLen), pcm,
                              AUDIO_FRAME_SAMPLES, /*decode_fec=*/1);
    return n > 0 ? static_cast<size_t>(n) : 0;
}

// ---- encoder ---------------------------------------------------------------

std::unique_ptr<OpusStreamEncoder> OpusStreamEncoder::create(Stream stream) {
    const int channels = channelsFor(stream);
    const int application = stream == Stream::Mic ? OPUS_APPLICATION_VOIP : OPUS_APPLICATION_AUDIO;
    const int bitrate = stream == Stream::Mic ? OPUS_MIC_BITRATE_BPS : OPUS_SPEAKER_BITRATE_BPS;

    int err = OPUS_OK;
    std::unique_ptr<::OpusEncoder, OpusEncoderDeleter> enc(
        opus_encoder_create(AUDIO_SAMPLE_RATE_HZ, channels, application, &err));
    if (!enc || err != OPUS_OK) return nullptr;

    // Every ctl is checked: an encoder silently running at the wrong bitrate or
    // without FEC would degrade a live call in a way no test would catch later.
    if (opus_encoder_ctl(enc.get(), OPUS_SET_BITRATE(bitrate)) != OPUS_OK) return nullptr;
    if (opus_encoder_ctl(enc.get(), OPUS_SET_VBR(1)) != OPUS_OK) return nullptr;
    if (opus_encoder_ctl(enc.get(), OPUS_SET_INBAND_FEC(1)) != OPUS_OK) return nullptr;
    if (opus_encoder_ctl(enc.get(), OPUS_SET_PACKET_LOSS_PERC(OPUS_EXPECTED_PACKET_LOSS_PCT)) !=
        OPUS_OK) {
        return nullptr;
    }
    // Mic only, and this is the encoder that actually ships: a live microphone
    // never goes digitally silent, so a VAD gate is the only thing that can
    // collapse a quiet room (satellite measured 123 of 250 frames gated at
    // -50 dBFS after speech, 30.0 -> 16.4 kbps, on libopus 1.6.1; this repo
    // pins 1.5.2, where the suite below re-proves the collapse holds). Muting
    // is a separate and stricter thing -- it stops delivery entirely, which DTX
    // cannot do.
    //
    // The speaker encoder declines it deliberately, matching satellite: that
    // gate cuts anything ~26-30 dB below the recent peak, which on game audio
    // replaces a reverb tail with comfort noise. The far end suppresses exact
    // digital silence instead, which cannot touch audible content.
    if (stream == Stream::Mic) {
        if (opus_encoder_ctl(enc.get(), OPUS_SET_DTX(1)) != OPUS_OK) return nullptr;
    }
    return std::unique_ptr<OpusStreamEncoder>(new OpusStreamEncoder(std::move(enc), channels));
}

size_t OpusStreamEncoder::encode(const int16_t* pcm, size_t frames, uint8_t* out, size_t maxOut) {
    // One wire message is exactly one 20 ms packet, so a caller handing a
    // different window has mis-framed rather than merely mis-sized: refuse it
    // instead of emitting a packet the other end cannot place in its timeline.
    if (pcm == nullptr || out == nullptr) return 0;
    if (frames != static_cast<size_t>(AUDIO_FRAME_SAMPLES)) return 0;
    if (maxOut == 0 || maxOut > static_cast<size_t>(INT32_MAX)) return 0;
    const int n =
        opus_encode(enc_.get(), pcm, AUDIO_FRAME_SAMPLES, out, static_cast<opus_int32>(maxOut));
    // A 1-byte result is a legal silence packet, not a failure, and the wire
    // format is sized to carry one (wire_encoders.h AUDIO_WIRE_MIN_PAYLOAD_BYTES).
    return n > 0 ? static_cast<size_t>(n) : 0;
}

} // namespace dish_audio
