// SPDX-License-Identifier: LGPL-3.0-or-later

// Reorder window for one direction of one controller's audio stream
// (MSG_SPEAKER_AUDIO inbound here, MSG_MIC_AUDIO on the satellite): wrapping
// u16 sequence numbers in, in-order packets and explicit gap signals out.
//
// The streams are lossy by contract (satellite docs/contract.md: no acks, no
// retransmits), so `seq` buys exactly two things and no more: it says which
// frames never arrived, so the decoder can conceal them, and it says which
// arrived too late to matter, so they can be dropped instead of spliced in
// behind audio already played.
//
// This is a deliberate mirror of satellite's src/core/audio/audio_jitter.h,
// rule for rule: both ends of one stream have to agree on what counts as lost,
// what counts as late, and how long a hole is worth concealing, and the only
// way to keep that true is to keep the two files the same shape. Its suite
// (tests/test_audio_jitter.cpp) has a twin in app/src/test/cpp.
//
// Dependency-free: std only, no JNI, no libopus. The ordering rules are the
// sort of thing that should be provable on their own.
#pragma once

#include <cstddef>
#include <cstdint>
#include <vector>

namespace dish_audio {

// Two frames = 40 ms of tolerance. A frame arriving this far ahead of the one
// we are waiting for is proof that the missing one is lost rather than merely
// out of order, which is what turns a hole into a concealment decision. Wider
// would heal more reordering at the cost of that much added ear latency on
// every gap, and a LAN reorders by one frame or not at all.
inline constexpr int AUDIO_JITTER_WINDOW_FRAMES = 2;

// Longest run of consecutive missing frames concealed before the window gives
// up and resynchronises to the newest packet in hand. Concealment is a 20 ms
// guess extrapolated from the last good frame; stretched across a half-second
// dropout it becomes synthetic noise AND holds the stream that far behind live.
// Past this, silence is the honest rendering of silence.
inline constexpr int AUDIO_JITTER_MAX_CONCEAL_FRAMES = 2;

// Refuse a packet longer than one datagram could ever have carried. Nothing in
// the pipeline hands us one (the receive path caps the whole inner message well
// below this), so it is purely a bound on what the window can be made to
// allocate from a hostile stream.
inline constexpr int AUDIO_JITTER_MAX_PACKET_BYTES = 1500;

// Worst case for one push: every packet the window can hold drains, and each
// drain may be preceded by a full concealment run.
inline constexpr int AUDIO_JITTER_MAX_EVENTS_PER_PUSH =
    AUDIO_JITTER_WINDOW_FRAMES * (AUDIO_JITTER_MAX_CONCEAL_FRAMES + 1);

class AudioJitterWindow {
  public:
    // One thing the decoder should do, in stream order.
    struct Event {
        enum class Kind : uint8_t {
            Packet, // decode `data` normally
            Gap,    // frame `seq` never arrived; conceal it
        };
        Kind kind = Kind::Packet;
        uint16_t seq = 0;
        // Packet only. Valid until the next push() or reset() (it either
        // aliases the caller's own buffer or the window's storage), which is
        // all a caller draining the result in place needs.
        const uint8_t* data = nullptr;
        size_t len = 0;
        // Gap only: the packet for seq + 1, when the window already holds it.
        // Opus puts a redundant low-rate copy of frame N inside packet N + 1,
        // so this pointer is the difference between recovering the lost frame
        // and guessing at it. Null means nothing carries it: conceal blind.
        const uint8_t* fecCarrier = nullptr;
        size_t fecCarrierLen = 0;
    };

    // What the pushed packet itself was worth. Orthogonal to the events: a late
    // packet can still be the push that flushes earlier frames out.
    enum class Accept : uint8_t {
        Ok,        // taken: emitted now or held for reordering
        Late,      // its slot was already emitted or concealed; too late to use
        Duplicate, // a packet with this seq is already waiting
        Rejected,  // empty, or longer than AUDIO_JITTER_MAX_PACKET_BYTES
    };

    struct Result {
        Accept accept = Accept::Ok;
        int count = 0;
        Event events[AUDIO_JITTER_MAX_EVENTS_PER_PUSH];
    };

    // Offer one packet. `data` must outlive the caller's use of the result.
    Result push(uint16_t seq, const uint8_t* data, size_t len) {
        Result r;
        if (data == nullptr || len == 0 ||
            len > static_cast<size_t>(AUDIO_JITTER_MAX_PACKET_BYTES)) {
            r.accept = Accept::Rejected;
            return r;
        }

        // The first packet defines where the stream starts; there is no such
        // thing as a late or missing frame before it.
        if (!primed_) {
            primed_ = true;
            next_ = seq;
            consecutiveGaps_ = 0;
        }

        const int delta = deltaFromNext(seq);
        if (delta < 0) {
            r.accept = Accept::Late;
            return r;
        }

        // The common case by far: the frame we were waiting for, with nothing
        // held behind it. Emitted straight from the caller's buffer, so a clean
        // stream never copies a packet or touches the heap.
        if (delta == 0 && usedSlots() == 0) {
            emitPacket(r, seq, data, len);
            next_ = static_cast<uint16_t>(next_ + 1);
            consecutiveGaps_ = 0;
            return r;
        }

        if (find(seq) != nullptr) {
            r.accept = Accept::Duplicate;
            return r;
        }
        Slot* slot = freeSlot();
        if (slot == nullptr) {
            // Unreachable: see the slots_ invariant. Kept because "the window
            // silently ate a frame" is a far better failure than a write past
            // the array if that invariant is ever widened.
            r.accept = Accept::Rejected;
            return r;
        }
        slot->used = true;
        slot->seq = seq;
        slot->bytes.assign(data, data + len);

        drain(r);
        return r;
    }

    // Fresh pad, fresh stream. Keeps the slots' allocations so a replug does
    // not re-enter the allocator on its first frame.
    void reset() {
        primed_ = false;
        next_ = 0;
        consecutiveGaps_ = 0;
        for (Slot& s : slots_) s.used = false;
    }

    bool primed() const { return primed_; }
    // The seq the window is waiting for. Meaningless until primed.
    uint16_t nextSeq() const { return next_; }
    int buffered() const { return usedSlots(); }

  private:
    struct Slot {
        bool used = false;
        uint16_t seq = 0;
        std::vector<uint8_t> bytes;
    };

    // Sized by the invariant, not by guesswork: when drain() returns, every
    // held packet is less than AUDIO_JITTER_WINDOW_FRAMES ahead of next_ (a
    // packet further ahead forces a gap and drains the rest), and a delta of 0
    // would have been emitted, so at most WINDOW - 1 survive a push. push()
    // inserts one before draining, hence WINDOW slots exactly.
    Slot slots_[AUDIO_JITTER_WINDOW_FRAMES];
    bool primed_ = false;
    uint16_t next_ = 0;
    // Carried across pushes: a concealment run is emitted one frame per
    // arriving packet, so the cap only means anything if it is remembered.
    int consecutiveGaps_ = 0;

    // Wrapping distance from next_, signed. The int16_t cast is the whole wrap
    // story: 0x0000 is one after 0xFFFF, and a frame from before the wrap comes
    // out negative rather than 65535 frames ahead.
    int deltaFromNext(uint16_t seq) const {
        return static_cast<int>(static_cast<int16_t>(seq - next_));
    }

    int usedSlots() const {
        int n = 0;
        for (const Slot& s : slots_) {
            if (s.used) n++;
        }
        return n;
    }

    Slot* find(uint16_t seq) {
        for (Slot& s : slots_) {
            if (s.used && s.seq == seq) return &s;
        }
        return nullptr;
    }

    Slot* freeSlot() {
        for (Slot& s : slots_) {
            if (!s.used) return &s;
        }
        return nullptr;
    }

    // Furthest-ahead held packet, or -1 when nothing is held.
    int maxAhead() const {
        int best = -1;
        for (const Slot& s : slots_) {
            if (!s.used) continue;
            const int d = static_cast<int>(static_cast<int16_t>(s.seq - next_));
            if (d > best) best = d;
        }
        return best;
    }

    // Nearest held packet. The resync target: after giving up on a long
    // dropout, this is the oldest audio still worth playing.
    const Slot* oldestHeld() const {
        const Slot* best = nullptr;
        int bestDelta = 0;
        for (const Slot& s : slots_) {
            if (!s.used) continue;
            const int d = static_cast<int>(static_cast<int16_t>(s.seq - next_));
            if (best == nullptr || d < bestDelta) {
                best = &s;
                bestDelta = d;
            }
        }
        return best;
    }

    static void emitPacket(Result& r, uint16_t seq, const uint8_t* data, size_t len) {
        Event& e = r.events[r.count++];
        e.kind = Event::Kind::Packet;
        e.seq = seq;
        e.data = data;
        e.len = len;
    }

    void drain(Result& r) {
        while (r.count < AUDIO_JITTER_MAX_EVENTS_PER_PUSH) {
            Slot* due = find(next_);
            if (due != nullptr) {
                // Released, not cleared: the event points into these bytes and
                // the slot cannot be reused before the next push().
                emitPacket(r, due->seq, due->bytes.data(), due->bytes.size());
                due->used = false;
                next_ = static_cast<uint16_t>(next_ + 1);
                consecutiveGaps_ = 0;
                continue;
            }

            const int ahead = maxAhead();
            // Nothing held, or nothing far enough ahead to prove a loss: the
            // frame may still be one hop behind. Wait for the next packet.
            if (ahead < AUDIO_JITTER_WINDOW_FRAMES) break;

            if (consecutiveGaps_ >= AUDIO_JITTER_MAX_CONCEAL_FRAMES) {
                const Slot* resume = oldestHeld();
                if (resume == nullptr) break;
                next_ = resume->seq;
                consecutiveGaps_ = 0;
                continue;
            }

            const Slot* carrier = find(static_cast<uint16_t>(next_ + 1));
            Event& e = r.events[r.count++];
            e.kind = Event::Kind::Gap;
            e.seq = next_;
            if (carrier != nullptr) {
                e.fecCarrier = carrier->bytes.data();
                e.fecCarrierLen = carrier->bytes.size();
            }
            next_ = static_cast<uint16_t>(next_ + 1);
            consecutiveGaps_++;
        }
    }
};

} // namespace dish_audio
