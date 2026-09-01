// SPDX-License-Identifier: LGPL-3.0-or-later

// The libopus wrapper (audio_codec.*): the two controller-audio stream formats,
// round-tripped through real encoders and decoders.
//
// The assertions are deliberately about SHAPE rather than samples. Opus is
// lossy and version-dependent, so pinning bytes would pin the library version;
// what must not drift is that a 20 ms window in comes back out as a 20 ms
// window, that a tone survives as a tone, that concealment produces audio for a
// frame that never arrived, and above all that the mic stream really carries
// in-band FEC. That last one is an encoder-setting question and exactly the
// sort of thing that silently stops being true: a stream with no FEC encodes,
// decodes and sounds perfect right up until the first packet goes missing.
//
// The client only encodes mic and decodes speaker, but both halves of both
// streams are built (audio_codec.h says why), so each stream's loop closes here
// instead of against a second implementation of the same constants.
#include "audio_codec.h"
#include "audio_jitter.h"
#include "wire_encoders.h"

#include <gtest/gtest.h>

#include <cmath>
#include <cstdint>
#include <vector>

namespace {

using dish_audio::OpusStreamDecoder;
using dish_audio::OpusStreamEncoder;
using dish_audio::Stream;

constexpr size_t MIC_FRAME = static_cast<size_t>(dish_audio::AUDIO_FRAME_SAMPLES);
constexpr size_t SPEAKER_FRAME = static_cast<size_t>(dish_audio::AUDIO_SPEAKER_FRAME_SAMPLES);
// Comfortably past a 96 kbps 20 ms packet (~240 bytes) without being the wire
// ceiling, so a wildly oversized packet would still be visible as one.
constexpr size_t MAX_PACKET = 1024;
constexpr double PI = 3.14159265358979;

// Speech-ish content: a 220 Hz fundamental plus two harmonics, amplitude
// modulated so successive frames differ. Steady silence would let a
// concealment path look identical to a real decode and prove nothing.
void fillMicFrame(std::vector<int16_t>& pcm, int frameIndex) {
    pcm.resize(MIC_FRAME);
    for (size_t i = 0; i < MIC_FRAME; i++) {
        const double t = (frameIndex * static_cast<double>(MIC_FRAME) + static_cast<double>(i)) /
                         dish_audio::AUDIO_SAMPLE_RATE_HZ;
        const double env = 0.55 + 0.45 * std::sin(2.0 * PI * 3.0 * t);
        const double s = std::sin(2.0 * PI * 220.0 * t) + 0.5 * std::sin(2.0 * PI * 440.0 * t) +
                         0.25 * std::sin(2.0 * PI * 880.0 * t);
        pcm[i] = static_cast<int16_t>(env * s * 8000.0);
    }
}

// Stereo with the channels deliberately unequal, so a wrapper that collapsed or
// swapped them would show up as an energy imbalance rather than passing.
void fillSpeakerFrame(std::vector<int16_t>& pcm, int frameIndex) {
    pcm.resize(SPEAKER_FRAME);
    for (size_t i = 0; i < MIC_FRAME; i++) {
        const double t = (frameIndex * static_cast<double>(MIC_FRAME) + static_cast<double>(i)) /
                         dish_audio::AUDIO_SAMPLE_RATE_HZ;
        pcm[i * 2 + 0] = static_cast<int16_t>(std::sin(2.0 * PI * 330.0 * t) * 9000.0);
        pcm[i * 2 + 1] = static_cast<int16_t>(std::sin(2.0 * PI * 660.0 * t) * 4000.0);
    }
}

// Mean square over a channel of an interleaved buffer (stride 1 for mono).
double energy(const int16_t* pcm, size_t frames, int stride, int offset) {
    if (frames == 0) return 0.0;
    double sum = 0.0;
    for (size_t i = 0; i < frames; i++) {
        const double v = pcm[i * stride + offset];
        sum += v * v;
    }
    return sum / static_cast<double>(frames);
}

bool sameSamples(const std::vector<int16_t>& a, const std::vector<int16_t>& b) {
    if (a.size() != b.size()) return false;
    for (size_t i = 0; i < a.size(); i++) {
        if (a[i] != b[i]) return false;
    }
    return true;
}

// Encode a run of mic frames, drop `lost`, and decode the run twice from
// identical decoder state: once recovering the hole from the carrier packet's
// in-band FEC, once concealing it blind. Whether the two outputs differ is
// exactly "did packet lost+1 carry a redundant copy of frame lost".
bool fecBeatsPlcForFrame(int lost, double& outFecEnergy, double& outSourceEnergy) {
    auto enc = OpusStreamEncoder::create(Stream::Mic);
    auto decFec = OpusStreamDecoder::create(Stream::Mic);
    auto decPlc = OpusStreamDecoder::create(Stream::Mic);
    if (!enc || !decFec || !decPlc) return false;

    const int kFrames = 14;
    std::vector<std::vector<uint8_t>> packets;
    std::vector<std::vector<int16_t>> sources;
    for (int f = 0; f < kFrames; f++) {
        std::vector<int16_t> src;
        fillMicFrame(src, f);
        uint8_t buf[MAX_PACKET];
        const size_t bytes = enc->encode(src.data(), MIC_FRAME, buf, sizeof(buf));
        if (bytes == 0) return false;
        packets.push_back(std::vector<uint8_t>(buf, buf + bytes));
        sources.push_back(src);
    }

    std::vector<int16_t> fecOut(MIC_FRAME, 0);
    std::vector<int16_t> plcOut(MIC_FRAME, 0);
    std::vector<int16_t> scratch(MIC_FRAME, 0);
    for (int f = 0; f < kFrames; f++) {
        if (f == lost) {
            // Recovered from packet f+1, which is what the jitter window hands
            // over as a gap's carrier. Order matters: the FEC copy is decoded
            // BEFORE the carrier's own frame.
            if (decFec->decodeFec(packets[f + 1].data(), packets[f + 1].size(), fecOut.data(),
                                  fecOut.size()) != MIC_FRAME) {
                return false;
            }
            if (decPlc->conceal(plcOut.data(), plcOut.size()) != MIC_FRAME) return false;
            continue;
        }
        decFec->decode(packets[f].data(), packets[f].size(), scratch.data(), scratch.size());
        decPlc->decode(packets[f].data(), packets[f].size(), scratch.data(), scratch.size());
    }

    outFecEnergy = energy(fecOut.data(), MIC_FRAME, 1, 0);
    outSourceEnergy = energy(sources[lost].data(), MIC_FRAME, 1, 0);
    return !sameSamples(fecOut, plcOut);
}

} // namespace

TEST(AudioCodec, WireConstantsMatchTheContract) {
    // A cross-repo agreement, not a tuning knob: satellite pins the same five
    // numbers in core/types.h, and the wire never negotiates any of them.
    EXPECT_EQ(dish_audio::AUDIO_SAMPLE_RATE_HZ, 48000);
    EXPECT_EQ(dish_audio::AUDIO_FRAME_MS, 20);
    EXPECT_EQ(dish_audio::AUDIO_FRAME_SAMPLES, 960);
    EXPECT_EQ(dish_audio::AUDIO_MIC_CHANNELS, 1);
    EXPECT_EQ(dish_audio::AUDIO_SPEAKER_CHANNELS, 2);
    EXPECT_EQ(dish_audio::AUDIO_SPEAKER_FRAME_SAMPLES, 1920);
}

TEST(AudioCodecMic, RoundTripPreservesTheFrameAndTheSignal) {
    auto enc = OpusStreamEncoder::create(Stream::Mic);
    auto dec = OpusStreamDecoder::create(Stream::Mic);
    ASSERT_NE(enc, nullptr);
    ASSERT_NE(dec, nullptr);
    EXPECT_EQ(enc->channels(), dish_audio::AUDIO_MIC_CHANNELS);
    EXPECT_EQ(dec->channels(), dish_audio::AUDIO_MIC_CHANNELS);

    std::vector<int16_t> src;
    std::vector<int16_t> out(MIC_FRAME * 2, 0);
    uint8_t packet[MAX_PACKET];
    double lastEnergyRatio = 0.0;

    // Several frames: Opus needs a few to leave its start-up transient, and a
    // wrapper that only worked on frame 0 would be a real bug.
    for (int f = 0; f < 12; f++) {
        fillMicFrame(src, f);
        const size_t bytes = enc->encode(src.data(), MIC_FRAME, packet, sizeof(packet));
        EXPECT_GT(bytes, 0u);
        EXPECT_LT(bytes, sizeof(packet));
        EXPECT_EQ(dec->decode(packet, bytes, out.data(), out.size()), MIC_FRAME);
        if (f >= 4) {
            const double in = energy(src.data(), MIC_FRAME, 1, 0);
            const double got = energy(out.data(), MIC_FRAME, 1, 0);
            lastEnergyRatio = in > 0.0 ? got / in : 0.0;
            // Lossy, so not equal; but a codec that dropped the signal or blew
            // it up by an order of magnitude is broken, not lossy.
            EXPECT_GT(lastEnergyRatio, 0.3);
            EXPECT_LT(lastEnergyRatio, 3.0);
        }
    }
    EXPECT_GT(lastEnergyRatio, 0.0);

    // ~32 kbps at 20 ms is ~80 bytes; the assertion is only that VBR is not
    // running an order of magnitude off the configured rate.
    fillMicFrame(src, 20);
    const size_t bytes = enc->encode(src.data(), MIC_FRAME, packet, sizeof(packet));
    EXPECT_GT(bytes, 20u);
    EXPECT_LT(bytes, 400u);
}

TEST(AudioCodecMic, EveryPacketFitsTheWireCeilingWithRoomToSpare) {
    // The send path hands the encoder the whole wire ceiling as its output
    // budget, so this is what keeps that generosity honest: a 20 ms mic packet
    // is two orders of magnitude below the datagram limit.
    auto enc = OpusStreamEncoder::create(Stream::Mic);
    ASSERT_NE(enc, nullptr);
    std::vector<int16_t> src;
    std::vector<uint8_t> out(dish_wire::AUDIO_WIRE_MAX_OPUS_BYTES);
    for (int f = 0; f < 12; f++) {
        fillMicFrame(src, f);
        const size_t bytes = enc->encode(src.data(), MIC_FRAME, out.data(), out.size());
        EXPECT_GT(bytes, 0u);
        EXPECT_LE(dish_wire::AUDIO_WIRE_HEADER_BYTES + bytes, dish_wire::MAX_INNER_PAYLOAD_BYTES);
    }
}

TEST(AudioCodecSpeaker, StereoRoundTripsWithTheChannelImbalanceIntact) {
    auto enc = OpusStreamEncoder::create(Stream::Speaker);
    auto dec = OpusStreamDecoder::create(Stream::Speaker);
    ASSERT_NE(enc, nullptr);
    ASSERT_NE(dec, nullptr);
    EXPECT_EQ(enc->channels(), dish_audio::AUDIO_SPEAKER_CHANNELS);
    EXPECT_EQ(dec->channels(), dish_audio::AUDIO_SPEAKER_CHANNELS);

    std::vector<int16_t> src;
    std::vector<int16_t> out(SPEAKER_FRAME * 2, 0);
    uint8_t packet[MAX_PACKET];
    double leftOverRight = 0.0;
    for (int f = 0; f < 12; f++) {
        fillSpeakerFrame(src, f);
        const size_t bytes = enc->encode(src.data(), MIC_FRAME, packet, sizeof(packet));
        EXPECT_GT(bytes, 0u);
        EXPECT_EQ(dec->decode(packet, bytes, out.data(), out.size() / 2), MIC_FRAME);
        if (f >= 4) {
            const double l = energy(out.data(), MIC_FRAME, 2, 0);
            const double r = energy(out.data(), MIC_FRAME, 2, 1);
            leftOverRight = r > 0.0 ? l / r : 0.0;
        }
    }
    // Source left is ~5x right in power. A wrapper that downmixed to mono, or
    // swapped the interleave, would land near 1.0 or well under it.
    EXPECT_GT(leftOverRight, 1.5);
    EXPECT_LT(leftOverRight, 12.0);
}

TEST(AudioCodec, EncodeRefusesAWindowThatIsNotExactlyTwentyMilliseconds) {
    auto enc = OpusStreamEncoder::create(Stream::Mic);
    ASSERT_NE(enc, nullptr);

    std::vector<int16_t> src;
    fillMicFrame(src, 0);
    uint8_t packet[MAX_PACKET];
    // One wire message is one 20 ms packet: a caller handing a different window
    // has mis-framed, and emitting the packet anyway would put audio on the
    // wire the satellite cannot place in its timeline.
    EXPECT_EQ(enc->encode(src.data(), MIC_FRAME - 1, packet, sizeof(packet)), 0u);
    EXPECT_EQ(enc->encode(src.data(), MIC_FRAME + 1, packet, sizeof(packet)), 0u);
    EXPECT_EQ(enc->encode(src.data(), 0, packet, sizeof(packet)), 0u);
    EXPECT_EQ(enc->encode(nullptr, MIC_FRAME, packet, sizeof(packet)), 0u);
    EXPECT_EQ(enc->encode(src.data(), MIC_FRAME, nullptr, sizeof(packet)), 0u);
    EXPECT_EQ(enc->encode(src.data(), MIC_FRAME, packet, 0), 0u);
    // And the good call still works afterwards: a refusal must not wedge the
    // encoder.
    EXPECT_GT(enc->encode(src.data(), MIC_FRAME, packet, sizeof(packet)), 0u);
}

TEST(AudioCodec, GarbageAndTruncatedPacketsLeaveAUsableDecoder) {
    auto enc = OpusStreamEncoder::create(Stream::Speaker);
    auto dec = OpusStreamDecoder::create(Stream::Speaker);
    ASSERT_NE(enc, nullptr);
    ASSERT_NE(dec, nullptr);

    std::vector<int16_t> out(SPEAKER_FRAME, 0);
    const uint8_t garbage[] = {0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF};
    // Not asserting failure: some byte strings ARE valid Opus. Asserting only
    // that nothing reads out of bounds and the decoder survives.
    (void)dec->decode(garbage, sizeof(garbage), out.data(), MIC_FRAME);
    EXPECT_EQ(dec->decode(nullptr, 4, out.data(), MIC_FRAME), 0u);
    EXPECT_EQ(dec->decode(garbage, 0, out.data(), MIC_FRAME), 0u);
    EXPECT_EQ(dec->decode(garbage, sizeof(garbage), nullptr, MIC_FRAME), 0u);
    EXPECT_EQ(dec->decode(garbage, sizeof(garbage), out.data(), 0), 0u);

    std::vector<int16_t> src;
    fillSpeakerFrame(src, 0);
    uint8_t packet[MAX_PACKET];
    const size_t bytes = enc->encode(src.data(), MIC_FRAME, packet, sizeof(packet));
    ASSERT_GT(bytes, 4u);
    (void)dec->decode(packet, bytes / 2, out.data(), MIC_FRAME); // truncated
    // Whatever the malformed input did, a real packet still decodes.
    EXPECT_EQ(dec->decode(packet, bytes, out.data(), MIC_FRAME), MIC_FRAME);
}

TEST(AudioCodec, APacketClaimingMoreThanTwentyMillisecondsIsRefused) {
    auto enc = OpusStreamEncoder::create(Stream::Speaker);
    auto dec = OpusStreamDecoder::create(Stream::Speaker);
    ASSERT_NE(enc, nullptr);
    ASSERT_NE(dec, nullptr);

    std::vector<int16_t> src;
    fillSpeakerFrame(src, 0);
    uint8_t packet[MAX_PACKET];
    const size_t bytes = enc->encode(src.data(), MIC_FRAME, packet, sizeof(packet));
    ASSERT_GT(bytes, 1u);

    // Forge a 40 ms packet out of the 20 ms one: Opus's TOC byte carries the
    // frame count in its low two bits, so code 1 (two equal frames) plus a
    // duplicated body is a structurally valid packet of twice the duration.
    // This is exactly what a hostile host could put on the wire, and the
    // dispatch thread decodes into a fixed AUDIO_FRAME_SAMPLES buffer, so
    // "refused" and "not written past" have to be the same thing.
    std::vector<uint8_t> twoFrames;
    twoFrames.push_back(static_cast<uint8_t>((packet[0] & 0xFC) | 0x01));
    twoFrames.insert(twoFrames.end(), packet + 1, packet + bytes);
    twoFrames.insert(twoFrames.end(), packet + 1, packet + bytes);

    std::vector<int16_t> out(SPEAKER_FRAME, 0);
    EXPECT_EQ(dec->decode(twoFrames.data(), twoFrames.size(), out.data(), MIC_FRAME), 0u);
    // Given room for the whole 40 ms it decodes fine, which is what makes the
    // refusal above a capacity check rather than the packet being malformed.
    std::vector<int16_t> roomy(SPEAKER_FRAME * 2, 0);
    EXPECT_EQ(dec->decode(twoFrames.data(), twoFrames.size(), roomy.data(), MIC_FRAME * 2),
              MIC_FRAME * 2);
}

TEST(AudioCodec, ConcealmentSynthesizesAFullFrameWithNoPacketAtAll) {
    auto enc = OpusStreamEncoder::create(Stream::Speaker);
    auto dec = OpusStreamDecoder::create(Stream::Speaker);
    ASSERT_NE(enc, nullptr);
    ASSERT_NE(dec, nullptr);

    std::vector<int16_t> src;
    std::vector<int16_t> out(SPEAKER_FRAME, 0);
    uint8_t packet[MAX_PACKET];
    for (int f = 0; f < 8; f++) {
        fillSpeakerFrame(src, f);
        const size_t bytes = enc->encode(src.data(), MIC_FRAME, packet, sizeof(packet));
        EXPECT_EQ(dec->decode(packet, bytes, out.data(), MIC_FRAME), MIC_FRAME);
    }

    std::vector<int16_t> concealed(SPEAKER_FRAME, 0);
    EXPECT_EQ(dec->conceal(concealed.data(), SPEAKER_FRAME), MIC_FRAME);
    // Extrapolated from the tone that came before, so it must not be silence.
    // (An all-zero frame is what a decoder that ignored the request would give.)
    EXPECT_GT(energy(concealed.data(), MIC_FRAME, 2, 0), 1000.0);

    // Too small a buffer is a caller error, not a request to conceal less: the
    // codec has to be told exactly how much audio is missing.
    EXPECT_EQ(dec->conceal(concealed.data(), MIC_FRAME - 1), 0u);
    EXPECT_EQ(dec->conceal(nullptr, SPEAKER_FRAME), 0u);
}

TEST(AudioCodecMic, InBandFecRecoversMostLostFramesWherePlcAloneCannot) {
    // Every frame in a run, not one: whether a given packet carries LBRR is an
    // encoder decision made per packet, and a mode switch can make the two
    // decode paths agree for a frame on its own. A strict majority separates
    // the two worlds cleanly. This is the regression that pins
    // OPUS_SET_INBAND_FEC plus the expected-loss hint; nothing else would catch
    // losing them, because a stream without FEC sounds perfect until a packet
    // goes missing.
    const int first = 4;
    const int last = 11;
    const int trials = last - first + 1;
    int recovered = 0;
    double fecEnergy = 0.0;
    double sourceEnergy = 0.0;
    for (int lost = first; lost <= last; lost++) {
        double e = 0.0;
        double s = 0.0;
        if (fecBeatsPlcForFrame(lost, e, s)) {
            recovered++;
            fecEnergy = e;
            sourceEnergy = s;
        }
    }
    EXPECT_GT(recovered * 2, trials);
    ASSERT_GT(recovered, 0);

    // And a recovery is audio, not a click: energy in the same league as what
    // was encoded for the frame that went missing.
    EXPECT_GT(sourceEnergy, 0.0);
    EXPECT_GT(fecEnergy, sourceEnergy * 0.1);
    EXPECT_LT(fecEnergy, sourceEnergy * 10.0);
}

TEST(AudioCodec, DecodeFecWithNoCarrierConcealsInsteadOfFailing) {
    auto dec = OpusStreamDecoder::create(Stream::Speaker);
    ASSERT_NE(dec, nullptr);

    // The dispatch thread takes the FEC path unconditionally on a gap, because
    // whether a packet carries FEC is an encoder decision it cannot see, and
    // the reorder window hands over a null carrier whenever the next frame has
    // not arrived either. A null carrier therefore has to mean "conceal".
    std::vector<int16_t> out(SPEAKER_FRAME, 0);
    EXPECT_EQ(dec->decodeFec(nullptr, 0, out.data(), SPEAKER_FRAME), MIC_FRAME);
    EXPECT_EQ(dec->decodeFec(nullptr, 0, out.data(), MIC_FRAME - 1), 0u);
}

// DTX is asymmetric between the two streams on purpose, and the asymmetry is
// invisible from the header: only behaviour can pin it. The mic wants it
// because a live microphone never goes digitally silent, so a VAD gate is the
// only thing that can collapse a quiet room. The speaker must not have it,
// because its gate cuts anything ~26-30 dB below the recent peak, which on game
// audio turns a reverb tail into comfort noise.
TEST(AudioCodecMic, SustainedSilenceCollapsesToDtxPackets) {
    auto enc = OpusStreamEncoder::create(Stream::Mic);
    ASSERT_NE(enc, nullptr);

    const std::vector<int16_t> silence(MIC_FRAME, 0);
    uint8_t packet[MAX_PACKET];

    // DTX needs a run of qualifying input before it engages (200 ms when
    // satellite measured it), so the steady state is what is asserted, not
    // frame 1. The 20-frame lead-in is deliberately looser than that figure so
    // this does not become a pin on one libopus version's ramp.
    size_t tiny = 0;
    size_t counted = 0;
    for (int i = 0; i < 100; i++) {
        const size_t bytes = enc->encode(silence.data(), MIC_FRAME, packet, sizeof(packet));
        ASSERT_GT(bytes, 0u) << "frame " << i;
        if (i >= 20) {
            counted++;
            if (bytes <= 2) tiny++;
        }
    }
    ASSERT_GT(counted, 0u);
    EXPECT_GT(tiny, counted * 3 / 4);
}

TEST(AudioCodecMic, DtxPacketsStayLegalOnTheWireAndDecode) {
    auto enc = OpusStreamEncoder::create(Stream::Mic);
    auto dec = OpusStreamDecoder::create(Stream::Mic);
    ASSERT_NE(enc, nullptr);
    ASSERT_NE(dec, nullptr);

    const std::vector<int16_t> silence(MIC_FRAME, 0);
    uint8_t packet[MAX_PACKET];
    size_t bytes = 0;
    for (int i = 0; i < 40; i++) {
        bytes = enc->encode(silence.data(), MIC_FRAME, packet, sizeof(packet));
    }
    ASSERT_GE(bytes, 1u);
    ASSERT_LE(bytes, 2u);

    // A 1-byte packet is a legal Opus frame, and the wire minimum exists so it
    // survives dispatch: header + at least one Opus byte.
    EXPECT_GE(bytes + static_cast<size_t>(dish_wire::AUDIO_WIRE_HEADER_BYTES),
              static_cast<size_t>(dish_wire::AUDIO_WIRE_MIN_PAYLOAD_BYTES));

    std::vector<int16_t> out(MIC_FRAME, 12345);
    EXPECT_EQ(dec->decode(packet, bytes, out.data(), dish_audio::AUDIO_FRAME_SAMPLES), MIC_FRAME);

    // And the reorder window must take it, not reject it as a runt.
    dish_audio::AudioJitterWindow window;
    const auto pushed = window.push(0, packet, bytes);
    EXPECT_EQ(pushed.accept, dish_audio::AudioJitterWindow::Accept::Ok);
}

TEST(AudioCodecMic, DtxDoesNotGateRealSpeech) {
    auto enc = OpusStreamEncoder::create(Stream::Mic);
    ASSERT_NE(enc, nullptr);

    std::vector<int16_t> pcm;
    uint8_t packet[MAX_PACKET];
    size_t tiny = 0;
    for (int i = 0; i < 60; i++) {
        fillMicFrame(pcm, i);
        const size_t bytes = enc->encode(pcm.data(), MIC_FRAME, packet, sizeof(packet));
        ASSERT_GT(bytes, 0u) << "frame " << i;
        if (bytes <= 2) tiny++;
    }
    EXPECT_EQ(tiny, 0u);
}

TEST(AudioCodecSpeaker, DecliningDtxKeepsSilenceAFullPacket) {
    auto enc = OpusStreamEncoder::create(Stream::Speaker);
    ASSERT_NE(enc, nullptr);

    const std::vector<int16_t> silence(SPEAKER_FRAME, 0);
    uint8_t packet[MAX_PACKET];
    size_t tiny = 0;
    for (int i = 0; i < 100; i++) {
        const size_t bytes = enc->encode(silence.data(), MIC_FRAME, packet, sizeof(packet));
        ASSERT_GT(bytes, 0u) << "frame " << i;
        if (bytes <= 2) tiny++;
    }
    // Not a bug being pinned: the satellite suppresses exact digital silence
    // before it ever reaches this encoder, so the codec never needs a VAD.
    EXPECT_EQ(tiny, 0u);
}

TEST(AudioCodec, ATightOutputBufferTruncatesThePacketRatherThanOverrunningIt) {
    auto enc = OpusStreamEncoder::create(Stream::Speaker);
    ASSERT_NE(enc, nullptr);

    std::vector<int16_t> src;
    fillSpeakerFrame(src, 3);
    // libopus treats max_data_bytes as a hard ceiling it encodes down to, so a
    // small buffer produces a smaller packet rather than a buffer overrun. The
    // wire ceiling is generous, but this guarantee is what makes passing
    // sizeof(buffer) safe at the call site.
    uint8_t tight[64];
    const size_t bytes = enc->encode(src.data(), MIC_FRAME, tight, sizeof(tight));
    EXPECT_GT(bytes, 0u);
    EXPECT_LE(bytes, sizeof(tight));
}
