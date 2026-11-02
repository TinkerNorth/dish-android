// SPDX-License-Identifier: LGPL-3.0-or-later

#pragma once

#include <array>
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <memory>
#include <type_traits>
#include <unordered_map>

#include "audio_codec.h"
#include "audio_jitter.h"
#include "heartbeat_thread.h"
#include "hotpath_latency.h"
#include "tracked_mutex.h"
#include "udp_socket.h"

namespace dish_session {

// A session addresses its pads by a 4-bit controller index (contract §Session).
inline constexpr int MAX_CONTROLLERS = 16;
inline constexpr int CTRL_INDEX_MASK = MAX_CONTROLLERS - 1;

// The sessions the app holds at once: one per satellite it streams to.
inline constexpr size_t MAX_SESSIONS = 8;

// One bound controller's audio working set: the outbound mic encoder with its
// wrapping wire sequence, and the inbound speaker pair (reorder window feeding
// the decoder whose gaps it declares). Allocated lazily on the slot's first
// frame in that direction, because an Opus codec is ~20 KB of state each and
// most controllers never carry audio at all.
struct ControllerAudio {
    std::unique_ptr<dish_audio::OpusStreamEncoder> micEncoder;
    uint16_t micSeq = 0;

    // One reorder window and decoder per outbound lane: the two streams are
    // independent on the wire (own seq, own silence suppression), so their
    // gaps and their concealment state must never mix.
    dish_audio::AudioJitterWindow speakerWindow;
    std::unique_ptr<dish_audio::OpusStreamDecoder> speakerDecoder;
    dish_audio::AudioJitterWindow hapticWindow;
    std::unique_ptr<dish_audio::OpusStreamDecoder> hapticDecoder;
};

// What a datagram is sealed and addressed with, as the cold path publishes it. A handle of 0 is
// an entry no session holds.
struct Material {
    int32_t handle = 0;
    int32_t sock = -1;
    uint8_t token[4] = {};
    uint8_t key[32] = {}; // per-session key (HKDF-derived in Kotlin), never the pairing key
    sockaddr_in dest = {};
};
static_assert(std::is_trivially_copyable_v<Material>, "the material is published as words");
inline constexpr size_t MATERIAL_WORDS =
    (sizeof(Material) + sizeof(uint64_t) - 1) / sizeof(uint64_t);

// Held by one sender of a session at a time, from drawing its counter to the return of its send,
// so the session's datagrams leave in counter order. The cold path holds it too, to change what a
// sender reads, so a close or a re-key never overlaps a send under the old material and a counter
// the new key restarts is never sealed under the old one. Taken while no other lock is held; a
// sender that finds it taken yields until it is free.
class SendGate {
  public:
    bool tryHold() noexcept;
    void hold() noexcept;
    void release() noexcept;

  private:
    std::atomic<uint32_t> held_{0};
};

struct Session {
    // Published: written by the cold path holding the gate, inside a seqlock write (gen odd), so a
    // sender holding the gate reads it whole and the receive thread reads it under the seqlock.
    std::atomic<uint32_t> gen{0};
    std::atomic<uint64_t> material[MATERIAL_WORDS] = {};

    // Under the gate: drawn, read and restarted only by its holder.
    alignas(64) SendGate gate;
    // 64-bit so exhaustion goes silent instead of wrapping (send_counter.h).
    std::atomic<uint64_t> counter{1};
    // Negotiated at session PUT; picks which MSG_TOUCHPAD frame this session encodes.
    std::atomic<int32_t> protocolVersion{1};
    // Where the sealed datagrams go; set before the session's first send.
    dish_net::DatagramPort port;
    std::atomic<uint32_t> sentByCtrl[MAX_CONTROLLERS] = {};
    std::atomic<uint32_t> motionByCtrl[MAX_CONTROLLERS] = {};

    // Written by the heartbeat and receive threads, read by the JNI getters.
    alignas(64) std::atomic<int> missedAcks{0};
    std::atomic<bool> connectionAlive{true};
    std::atomic<int64_t> lastPingNs{0};

    // Downstream replay guard (server → client direction).
    std::atomic<uint32_t> lastRxCounter{0};

    // Latest enriched heartbeat-ack material (-1 = none seen this session). The
    // Kotlin alive-poll compares epoch/bitmap against its applied state and
    // reconciles via REST on mismatch.
    std::atomic<int32_t> serverEpoch{-1};
    std::atomic<int32_t> activeBitmap{-1};

    // CLOSE_REASON_* from MSG_SESSION_CLOSE; -1 = none. Terminal for the session.
    std::atomic<int32_t> closeReason{-1};

    std::atomic<int8_t> vigemAvailable{-1};
    std::atomic<int8_t> activeControllerCount{-1};

    hotpath::RttStats rtt;

    dish::HeartbeatThread heartbeat;
    // The receive thread: started once the session is live, stopped by close before the socket
    // goes, so a receive never reads a closed or reused descriptor.
    dish::HeartbeatThread receiver;

    // Per-controller audio state, keyed by ctrlIdx. Touched by the mic capture
    // thread (encode) and the audio dispatch thread (decode), never by the
    // receive thread, so one lock covers it: at 50 frames/s per direction the
    // two threads are each inside for well under a millisecond a second.
    TrackedMutex audioMtx{"session.audio"};
    std::unordered_map<uint8_t, ControllerAudio> audio;
    // Under audioMtx: the handle the audio state belongs to, 0 once the session closed, so a frame
    // queued for a session that is gone never decodes into whichever session took the entry next.
    int32_t audioHandle = 0;
};

// A session as its handle names it: the table entry and the handle a sender expects to find in it.
// Senders check the handle under the gate, so a reference outliving its session sends nothing.
struct SessionRef {
    Session* entry = nullptr;
    int32_t handle = 0;

    explicit operator bool() const { return entry != nullptr; }
    Session* operator->() const { return entry; }
    Session& operator*() const { return *entry; }
};

// The material as a sender holding the gate reads it: nothing can change it meanwhile.
Material materialUnderGate(const Session& s);

// The material as a thread without the gate reads it: under the seqlock, waiting out a write.
Material readMaterial(const Session& s);

// A new (token, key) pair: the counters, the replay guard, the ack state and the audio streams
// restart with it (contract §Crypto).
void setConnectionParams(const SessionRef& s, const uint8_t token[4], const uint8_t key[32],
                         int32_t protocolVersion);

// MAX_SESSIONS fixed entries and a packed line of their handles that find scans without a lock.
class SessionTable {
  public:
    SessionTable() = default;
    ~SessionTable();
    SessionTable(const SessionTable&) = delete;
    SessionTable& operator=(const SessionTable&) = delete;

    // The new session's handle, or -1 when ip is not an IPv4 literal, no socket opens or every
    // entry is taken. Handles rise and are never reused.
    int open(const char* ip, int port);

    // Stops the heartbeat, retires the codecs and closes the socket; an unknown handle is ignored.
    void close(int handle);

    // Lock-free; empty for a handle that is not open.
    SessionRef find(int handle);

    // Whether the handle still names the session find answered for it: a getter that read an
    // entry's state checks this afterwards, so a close in between never answers for a newer one.
    bool stillOpen(const SessionRef& s) const;

    void clearRtt();

  private:
    int indexOf(int handle) const;
    int32_t nextHandle();

    // Serialises open and close; the senders and the receive loop never take it.
    TrackedMutex openMtx_{"session.table"};
    // Under openMtx_: an entry is reserved from its open until its close has closed the socket.
    std::array<bool, MAX_SESSIONS> reserved_ = {};
    int32_t lastHandle_ = 0;
    // The open handles, published last at open and cleared first at close.
    std::array<std::atomic<int32_t>, MAX_SESSIONS> handles_ = {};
    std::array<Session, MAX_SESSIONS> sessions_;
};

} // namespace dish_session
