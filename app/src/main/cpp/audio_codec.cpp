// SPDX-License-Identifier: LGPL-3.0-or-later

#include "audio_codec.h"

#include <opus.h>

namespace dish_audio {
namespace {

// The wire's formats, in one place, matching satellite's opus_codec.cpp knob
// for knob. Both streams are 48 kHz, 20 ms, VBR with in-band FEC requested;
// what differs is the application and the bitrate.
//
// Mic: 32 kbps mono under OPUS_APPLICATION_VOIP encodes as SILK wideband on
// band-limited input and as Hybrid fullband on broadband input. Both keep the
// SILK layer, the only one with in-band FEC: the redundant low-rate copy of the
// previous frame that lets the satellite recover a single lost packet instead of
// guessing at it. The expected-loss hint is what makes the encoder actually
// spend bits on that copy; without it the flag alone does nothing.
//
// Speaker: 96 kbps stereo under OPUS_APPLICATION_AUDIO, because that stream
// carries game and chat audio a player listens to rather than speech a codec
// can model. The FEC request and the loss hint, not the application, pick its
// mode: together they force SILK in, so it encodes as Hybrid (fullband, or
// super-wideband for band-limited content) instead of CELT alone and carries
// in-band FEC too; dropping either one would hand it to CELT. The client only
// decodes it, so these two are here for the test suite and for whoever next
// asks what the far end is sending.
constexpr int OPUS_MIC_BITRATE_BPS = 32000;
constexpr int OPUS_SPEAKER_BITRATE_BPS = 96000;
constexpr int OPUS_EXPECTED_PACKET_LOSS_PCT = 10;

// opus_decode's decode_fec argument and the value every boolean encoder ctl takes to enable.
constexpr int DECODE_FEC_OFF = 0;
constexpr int DECODE_FEC_ON = 1;
constexpr int OPUS_CTL_ON = 1;

int channelsFor(const Stream stream) {
    return stream == Stream::Mic ? AUDIO_MIC_CHANNELS : AUDIO_SPEAKER_CHANNELS;
}

// Every ctl is checked: an encoder silently running at the wrong bitrate or without FEC would
// degrade a live call in a way no test would catch later.
bool configureEncoder(::OpusEncoder* enc, const int bitrate) {
    if (opus_encoder_ctl(enc, OPUS_SET_BITRATE(bitrate)) != OPUS_OK) return false;
    if (opus_encoder_ctl(enc, OPUS_SET_VBR(OPUS_CTL_ON)) != OPUS_OK) return false;
    if (opus_encoder_ctl(enc, OPUS_SET_INBAND_FEC(OPUS_CTL_ON)) != OPUS_OK) return false;
    return opus_encoder_ctl(enc, OPUS_SET_PACKET_LOSS_PERC(OPUS_EXPECTED_PACKET_LOSS_PCT)) ==
           OPUS_OK;
}

// The speaker encoder declines DTX deliberately, matching satellite: the gate cuts anything
// ~26-30 dB below the recent peak, which on game audio replaces a reverb tail with comfort noise,
// while the far end's own suppression of exact digital silence cannot touch audible content. On
// the mic it is the only thing that can collapse a quiet room, because a live microphone never
// goes digitally silent (satellite measured 123 of 250 frames gated at -50 dBFS after speech,
// 30.0 -> 16.4 kbps, on libopus 1.6.1; this repo pins 1.5.2). Muting is a separate and stricter
// thing: it stops delivery entirely, which DTX cannot do.
bool wantsDtx(const Stream stream) { return stream == Stream::Mic; }

} // namespace

void OpusEncoderDeleter::operator()(::OpusEncoder* enc) const noexcept {
    if (enc != nullptr) opus_encoder_destroy(enc);
}

void OpusDecoderDeleter::operator()(::OpusDecoder* dec) const noexcept {
    if (dec != nullptr) opus_decoder_destroy(dec);
}

// ---- decoder ---------------------------------------------------------------

std::unique_ptr<OpusStreamDecoder> OpusStreamDecoder::create(const Stream stream) {
    const int channels = channelsFor(stream);
    int err = OPUS_OK;
    std::unique_ptr<::OpusDecoder, OpusDecoderDeleter> dec(
        opus_decoder_create(AUDIO_SAMPLE_RATE_HZ, channels, &err));
    if (!dec || err != OPUS_OK) return nullptr;
    // The decoder carries no format negotiation: a stream's parameters travel
    // inside each Opus packet, so there is nothing else to set here.
    return std::unique_ptr<OpusStreamDecoder>(new OpusStreamDecoder(std::move(dec), channels));
}

size_t OpusStreamDecoder::decode(const uint8_t* opus, const size_t opusLen, int16_t* pcm,
                                 const size_t maxFrames) {
    if (opus == nullptr || opusLen == 0 || pcm == nullptr || maxFrames == 0) return 0;
    if (opusLen > static_cast<size_t>(INT32_MAX) || maxFrames > static_cast<size_t>(INT32_MAX)) {
        return 0;
    }
    const int n = opus_decode(dec_.get(), opus, static_cast<opus_int32>(opusLen), pcm,
                              static_cast<int>(maxFrames), DECODE_FEC_OFF);
    return n > 0 ? static_cast<size_t>(n) : 0;
}

size_t OpusStreamDecoder::conceal(int16_t* pcm, const size_t maxFrames) {
    // A null packet is how libopus is asked for concealment; the frame size is then the missing
    // duration, not a capacity.
    if (pcm == nullptr || maxFrames < static_cast<size_t>(AUDIO_FRAME_SAMPLES)) return 0;
    const int n = opus_decode(dec_.get(), nullptr, 0, pcm, AUDIO_FRAME_SAMPLES, DECODE_FEC_OFF);
    return n > 0 ? static_cast<size_t>(n) : 0;
}

size_t OpusStreamDecoder::decodeFec(const uint8_t* opus, const size_t opusLen, int16_t* pcm,
                                    const size_t maxFrames) {
    if (opus == nullptr || opusLen == 0) return conceal(pcm, maxFrames);
    if (pcm == nullptr || maxFrames < static_cast<size_t>(AUDIO_FRAME_SAMPLES)) return 0;
    if (opusLen > static_cast<size_t>(INT32_MAX)) return 0;
    // decode_fec asks for the frame BEFORE this packet, so the frame size is again the missing
    // duration; libopus conceals by itself when the packet carries no FEC data.
    const int n = opus_decode(dec_.get(), opus, static_cast<opus_int32>(opusLen), pcm,
                              AUDIO_FRAME_SAMPLES, DECODE_FEC_ON);
    return n > 0 ? static_cast<size_t>(n) : 0;
}

// ---- encoder ---------------------------------------------------------------

std::unique_ptr<OpusStreamEncoder> OpusStreamEncoder::create(const Stream stream) {
    const int channels = channelsFor(stream);
    const int application = stream == Stream::Mic ? OPUS_APPLICATION_VOIP : OPUS_APPLICATION_AUDIO;
    const int bitrate = stream == Stream::Mic ? OPUS_MIC_BITRATE_BPS : OPUS_SPEAKER_BITRATE_BPS;

    int err = OPUS_OK;
    std::unique_ptr<::OpusEncoder, OpusEncoderDeleter> enc(
        opus_encoder_create(AUDIO_SAMPLE_RATE_HZ, channels, application, &err));
    if (!enc || err != OPUS_OK) return nullptr;
    if (!configureEncoder(enc.get(), bitrate)) return nullptr;
    const bool dtxRefused =
        wantsDtx(stream) && opus_encoder_ctl(enc.get(), OPUS_SET_DTX(OPUS_CTL_ON)) != OPUS_OK;
    if (dtxRefused) return nullptr;
    return std::unique_ptr<OpusStreamEncoder>(new OpusStreamEncoder(std::move(enc), channels));
}

size_t OpusStreamEncoder::encode(const int16_t* pcm, const size_t frames, uint8_t* out,
                                 const size_t maxOut) {
    if (pcm == nullptr || out == nullptr) return 0;
    if (frames != static_cast<size_t>(AUDIO_FRAME_SAMPLES)) return 0;
    if (maxOut == 0 || maxOut > static_cast<size_t>(INT32_MAX)) return 0;
    const int n =
        opus_encode(enc_.get(), pcm, AUDIO_FRAME_SAMPLES, out, static_cast<opus_int32>(maxOut));
    return n > 0 ? static_cast<size_t>(n) : 0;
}

} // namespace dish_audio
