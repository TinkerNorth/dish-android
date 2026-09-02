// SPDX-License-Identifier: LGPL-3.0-or-later

// AudioJitterWindow (audio_jitter.h): the 2-frame reorder window that decides,
// from wrapping u16 sequence numbers alone, which speaker frames to play, which
// to conceal, and which arrived too late to be worth anything.
//
// A deliberate mirror of satellite's tests/test_audio_jitter.cpp, case for
// case: the two ends of one stream have to agree on what counts as lost, what
// counts as late, and how long a hole is worth concealing, and a divergence
// there would show up as audible glitching rather than as a failure anywhere.
//
// Dependency-free by design, so this suite links no codec: the ordering rules
// are exactly the part that should be provable without one.
#include "audio_jitter.h"

#include <gtest/gtest.h>

#include <cstdint>
#include <string>
#include <vector>

namespace {

using dish_audio::AudioJitterWindow;
using Kind = AudioJitterWindow::Event::Kind;
using Accept = AudioJitterWindow::Accept;

// Packets are only ever compared by identity here, so each one is a block of a
// distinctive byte: it makes "the window handed back the packet I pushed" an
// assertion rather than a hope.
std::vector<uint8_t> packet(uint8_t tag, size_t bytes = 24) {
    return std::vector<uint8_t>(bytes, tag);
}

AudioJitterWindow::Result push(AudioJitterWindow& w, uint16_t seq, const std::vector<uint8_t>& p) {
    return w.push(seq, p.data(), p.size());
}

// One-line shape of a result: "P12" for a packet, "G12" for a gap, "G12/F" for
// a gap that has an FEC carrier in hand. Comparing shapes catches ordering
// mistakes that per-field asserts miss.
std::string shape(const AudioJitterWindow::Result& r) {
    std::string s;
    for (int i = 0; i < r.count; i++) {
        const AudioJitterWindow::Event& e = r.events[i];
        if (!s.empty()) s += " ";
        s += (e.kind == Kind::Packet ? "P" : "G");
        s += std::to_string(e.seq);
        if (e.kind == Kind::Gap && e.fecCarrier != nullptr) s += "/F";
    }
    return s;
}

// Every byte of the event's payload equals `tag`.
bool payloadIs(const AudioJitterWindow::Event& e, uint8_t tag) {
    if (e.data == nullptr || e.len == 0) return false;
    for (size_t i = 0; i < e.len; i++) {
        if (e.data[i] != tag) return false;
    }
    return true;
}

bool carrierIs(const AudioJitterWindow::Event& e, uint8_t tag) {
    if (e.fecCarrier == nullptr || e.fecCarrierLen == 0) return false;
    for (size_t i = 0; i < e.fecCarrierLen; i++) {
        if (e.fecCarrier[i] != tag) return false;
    }
    return true;
}

} // namespace

TEST(AudioJitter, InOrderStreamPassesStraightThrough) {
    AudioJitterWindow w;
    EXPECT_FALSE(w.primed());

    for (int i = 0; i < 8; i++) {
        const auto p = packet(static_cast<uint8_t>(0x40 + i));
        const auto r = push(w, static_cast<uint16_t>(100 + i), p);
        EXPECT_TRUE(r.accept == Accept::Ok);
        EXPECT_EQ(r.count, 1);
        EXPECT_TRUE(r.events[0].kind == Kind::Packet);
        EXPECT_EQ((int)r.events[0].seq, 100 + i);
        EXPECT_TRUE(payloadIs(r.events[0], static_cast<uint8_t>(0x40 + i)));
        // The clean path never copies: the event aliases the caller's buffer.
        EXPECT_EQ(r.events[0].data, p.data());
        EXPECT_EQ(w.buffered(), 0);
    }
    EXPECT_TRUE(w.primed());
    EXPECT_EQ((int)w.nextSeq(), 108);
}

TEST(AudioJitter, FirstPacketDefinesTheOriginWhateverItsSeq) {
    AudioJitterWindow w;
    const auto p = packet(0x11);
    // There is no such thing as a late or missing frame before the stream
    // started, so a mid-range opening seq is simply where it starts.
    const auto r = push(w, 40000, p);
    EXPECT_TRUE(r.accept == Accept::Ok);
    EXPECT_EQ(shape(r), std::string("P40000"));
    EXPECT_EQ((int)w.nextSeq(), 40001);
}

TEST(AudioJitter, OneSwappedPairIsReorderedNotConcealed) {
    AudioJitterWindow w;
    push(w, 10, packet(0xA0));
    push(w, 11, packet(0xA1));

    // 13 before 12: held, nothing due yet. This is the whole reason the window
    // exists, so it must not emit a gap here.
    const auto p13 = packet(0xA3);
    const auto held = push(w, 13, p13);
    EXPECT_TRUE(held.accept == Accept::Ok);
    EXPECT_EQ(held.count, 0);
    EXPECT_EQ(w.buffered(), 1);

    // 12 arrives: it goes out, and 13 follows it immediately.
    const auto p12 = packet(0xA2);
    const auto healed = push(w, 12, p12);
    EXPECT_TRUE(healed.accept == Accept::Ok);
    EXPECT_EQ(shape(healed), std::string("P12 P13"));
    EXPECT_TRUE(payloadIs(healed.events[0], 0xA2));
    EXPECT_TRUE(payloadIs(healed.events[1], 0xA3));
    EXPECT_EQ(w.buffered(), 0);
    EXPECT_EQ((int)w.nextSeq(), 14);
}

TEST(AudioJitter, LostFrameIsDeclaredWithItsFecCarrier) {
    AudioJitterWindow w;
    push(w, 10, packet(0xB0));
    push(w, 11, packet(0xB1));

    // 12 is lost. 13 alone proves nothing (it could be a reorder).
    const auto p13 = packet(0xB3);
    EXPECT_EQ(push(w, 13, p13).count, 0);

    // 14 is 2 ahead of the frame we want, which is the window's whole
    // definition of "lost". The gap names 12 and carries 13, because Opus hides
    // a redundant copy of 12 inside 13 and that is what makes recovery
    // possible; then 13 and 14 follow in order.
    const auto p14 = packet(0xB4);
    const auto r = push(w, 14, p14);
    EXPECT_TRUE(r.accept == Accept::Ok);
    EXPECT_EQ(shape(r), std::string("G12/F P13 P14"));
    EXPECT_TRUE(carrierIs(r.events[0], 0xB3));
    EXPECT_EQ(r.events[0].data, nullptr); // a gap has no packet of its own
    EXPECT_TRUE(payloadIs(r.events[1], 0xB3));
    EXPECT_TRUE(payloadIs(r.events[2], 0xB4));
}

TEST(AudioJitter, BackToBackLossesConcealBlindThenRecoverByFec) {
    AudioJitterWindow w;
    push(w, 10, packet(0xC0));
    push(w, 11, packet(0xC1));

    // 12 and 13 both lost. When 14 lands it is 2 ahead of 12, so 12 is
    // declared; nothing carries it (13 never arrived), so the gap is blind.
    const auto first = push(w, 14, packet(0xC4));
    EXPECT_EQ(shape(first), std::string("G12"));
    EXPECT_EQ(first.events[0].fecCarrier, nullptr);

    // 15 lands: now 13 is 2 behind, and 14 IS in hand to carry it.
    const auto second = push(w, 15, packet(0xC5));
    EXPECT_EQ(shape(second), std::string("G13/F P14 P15"));
    EXPECT_TRUE(carrierIs(second.events[0], 0xC4));
}

TEST(AudioJitter, FrameArrivingAfterItsSlotPlayedIsDropped) {
    AudioJitterWindow w;
    push(w, 10, packet(0xD0));
    push(w, 11, packet(0xD1));
    push(w, 13, packet(0xD3));
    const auto flushed = push(w, 14, packet(0xD4));
    EXPECT_EQ(shape(flushed), std::string("G12/F P13 P14"));

    // 12 finally shows up, long after it was concealed. Playing it now would be
    // an audible jump backwards.
    const auto late = push(w, 12, packet(0xD2));
    EXPECT_TRUE(late.accept == Accept::Late);
    EXPECT_EQ(late.count, 0);

    // So would replaying a frame already emitted.
    const auto replay = push(w, 13, packet(0xD3));
    EXPECT_TRUE(replay.accept == Accept::Late);
    EXPECT_EQ(replay.count, 0);

    // The stream carries on untouched.
    EXPECT_EQ(shape(push(w, 15, packet(0xD5))), std::string("P15"));
}

TEST(AudioJitter, DuplicateOfAHeldFrameIsDropped) {
    AudioJitterWindow w;
    push(w, 10, packet(0xE0));
    EXPECT_EQ(push(w, 12, packet(0xE2)).count, 0); // held, waiting on 11

    const auto dup = push(w, 12, packet(0xE2));
    EXPECT_TRUE(dup.accept == Accept::Duplicate);
    EXPECT_EQ(dup.count, 0);
    EXPECT_EQ(w.buffered(), 1);

    // The real 11 still heals the hole.
    EXPECT_EQ(shape(push(w, 11, packet(0xE1))), std::string("P11 P12"));
}

TEST(AudioJitter, EmptyNullAndOversizePacketsAreRefused) {
    AudioJitterWindow w;
    push(w, 10, packet(0xF0));

    const auto p = packet(0xF1);
    EXPECT_TRUE(w.push(11, nullptr, p.size()).accept == Accept::Rejected);
    EXPECT_TRUE(w.push(11, p.data(), 0).accept == Accept::Rejected);

    const auto huge = packet(0xF2, dish_audio::AUDIO_JITTER_MAX_PACKET_BYTES + 1);
    EXPECT_TRUE(w.push(11, huge.data(), huge.size()).accept == Accept::Rejected);

    // Exactly at the ceiling is fine; the bound is on what the window can be
    // made to allocate, not on what is plausible.
    const auto atCap = packet(0xF3, dish_audio::AUDIO_JITTER_MAX_PACKET_BYTES);
    const auto ok = w.push(11, atCap.data(), atCap.size());
    EXPECT_TRUE(ok.accept == Accept::Ok);
    EXPECT_EQ(shape(ok), std::string("P11"));

    // None of the refusals moved the stream on.
    EXPECT_EQ((int)w.nextSeq(), 12);
}

TEST(AudioJitter, ZeroIsTheFrameAfterFfffNotSixtyFiveThousandLosses) {
    AudioJitterWindow w;
    const uint16_t start = 0xFFFD;
    for (int i = 0; i < 6; i++) {
        const uint16_t seq = static_cast<uint16_t>(start + i);
        const auto r = push(w, seq, packet(static_cast<uint8_t>(0x50 + i)));
        EXPECT_TRUE(r.accept == Accept::Ok);
        EXPECT_EQ(r.count, 1);
        EXPECT_EQ((int)r.events[0].seq, (int)seq);
    }
    EXPECT_EQ((int)w.nextSeq(), 3);
}

TEST(AudioJitter, ReorderAndGapDetectionKeepWorkingAcrossTheWrap) {
    AudioJitterWindow w;
    push(w, 0xFFFD, packet(0x60));
    push(w, 0xFFFE, packet(0x61));

    // 0x0000 before 0xFFFF: a one-frame reorder that happens to straddle the
    // wrap. Signed u16 arithmetic is what keeps this from reading as a jump.
    EXPECT_EQ(push(w, 0x0000, packet(0x63)).count, 0);
    EXPECT_EQ(shape(push(w, 0xFFFF, packet(0x62))), std::string("P65535 P0"));

    // And a loss straddling it: 0x0001 lost, 0x0002 held, 0x0003 declares it.
    EXPECT_EQ(push(w, 0x0002, packet(0x65)).count, 0);
    const auto gapped = push(w, 0x0003, packet(0x66));
    EXPECT_EQ(shape(gapped), std::string("G1/F P2 P3"));
    EXPECT_TRUE(carrierIs(gapped.events[0], 0x65));
}

TEST(AudioJitter, LongDropoutIsConcealedOnlyToTheCapThenResyncs) {
    AudioJitterWindow w;
    push(w, 10, packet(0x70));

    // Half a second of nothing, then the stream resumes. Concealing all of it
    // would inject synthetic audio AND hold the stream that far behind live.
    const auto resume = push(w, 36, packet(0x71));
    int gaps = 0;
    int packets = 0;
    for (int i = 0; i < resume.count; i++) {
        if (resume.events[i].kind == Kind::Gap)
            gaps++;
        else
            packets++;
    }
    EXPECT_EQ(gaps, dish_audio::AUDIO_JITTER_MAX_CONCEAL_FRAMES);
    EXPECT_EQ(packets, 1);
    EXPECT_EQ(shape(resume), std::string("G11 G12 P36"));
    // Resynchronised onto the new audio, not still grinding through the hole.
    EXPECT_EQ((int)w.nextSeq(), 37);
    EXPECT_EQ(w.buffered(), 0);

    // A frame from inside the skipped range is history now.
    EXPECT_TRUE(push(w, 20, packet(0x72)).accept == Accept::Late);
}

TEST(AudioJitter, ConcealmentBudgetRefillsOnceRealAudioGetsThrough) {
    AudioJitterWindow w;
    push(w, 10, packet(0x80));
    // Burn the budget on one dropout...
    EXPECT_EQ(shape(push(w, 30, packet(0x81))), std::string("G11 G12 P30"));
    // ...then a clean frame, then another dropout: the second one gets its own
    // full allowance rather than inheriting a spent counter.
    EXPECT_EQ(shape(push(w, 31, packet(0x82))), std::string("P31"));
    EXPECT_EQ(shape(push(w, 60, packet(0x83))), std::string("G32 G33 P60"));
}

TEST(AudioJitter, AtMostOneFrameIsEverLeftWaiting) {
    AudioJitterWindow w;
    // A deliberately hostile order: reorders, repeats, jumps forward and back.
    const uint16_t order[] = {100, 102, 101, 105, 103,   104, 104, 110, 108,
                              111, 112, 109, 113, 65535, 116, 115, 117};
    for (uint16_t seq : order) {
        push(w, seq, packet(0x90));
        // The slots_ array is sized by this invariant; if it ever stops
        // holding, the window would be writing past its storage.
        EXPECT_LT(w.buffered(), dish_audio::AUDIO_JITTER_WINDOW_FRAMES);
    }
}

TEST(AudioJitter, NoArrivalOrderOverrunsTheResultArray) {
    AudioJitterWindow w;
    // Worst shape the window can produce: something held, then a jump big
    // enough to burn the concealment budget and resync in one push.
    push(w, 10, packet(0xA0));
    push(w, 11, packet(0xA1));
    EXPECT_EQ(push(w, 13, packet(0xA3)).count, 0); // 12 held back
    const auto burst = push(w, 900, packet(0xA9));
    EXPECT_LE(burst.count, dish_audio::AUDIO_JITTER_MAX_EVENTS_PER_PUSH);
    EXPECT_GT(burst.count, 0);
}

TEST(AudioJitter, ResetForgetsTheOldPadsStreamEntirely) {
    AudioJitterWindow w;
    push(w, 500, packet(0xB0));
    push(w, 502, packet(0xB2)); // left waiting
    EXPECT_EQ(w.buffered(), 1);

    w.reset();
    EXPECT_FALSE(w.primed());
    EXPECT_EQ(w.buffered(), 0);

    // A seq that would have been ancient history before the reset is now simply
    // where the new stream begins: a re-PUT restarts the far end's numbering and
    // must not have its first second thrown away as "late".
    const auto r = push(w, 3, packet(0xB3));
    EXPECT_TRUE(r.accept == Accept::Ok);
    EXPECT_EQ(shape(r), std::string("P3"));
}

TEST(AudioJitter, WindowConstantsMatchTheContract) {
    // Pinned here because they are a cross-repo agreement, not a tuning knob:
    // satellite's window is the same two frames with the same conceal cap, and
    // a change on one side alone would desynchronise the two ends of a stream.
    EXPECT_EQ(dish_audio::AUDIO_JITTER_WINDOW_FRAMES, 2);
    EXPECT_EQ(dish_audio::AUDIO_JITTER_MAX_CONCEAL_FRAMES, 2);
    EXPECT_EQ(dish_audio::AUDIO_JITTER_MAX_PACKET_BYTES, 1500);
    EXPECT_EQ(dish_audio::AUDIO_JITTER_MAX_EVENTS_PER_PUSH, 6);
}
