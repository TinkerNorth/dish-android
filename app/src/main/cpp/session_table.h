// SPDX-License-Identifier: LGPL-3.0-or-later

#pragma once

#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>
#include <unordered_map>

#include "audio_codec.h"
#include "audio_jitter.h"
#include "heartbeat_thread.h"
#include "hotpath_latency.h"
#include "udp_socket.h"

namespace dish_session {

// A session addresses its pads by a 4-bit controller index (contract §Session).
inline constexpr int MAX_CONTROLLERS = 16;
inline constexpr int CTRL_INDEX_MASK = MAX_CONTROLLERS - 1;

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

struct Session {
    int udpSock = -1;
    struct sockaddr_in dest = {};
    uint8_t token[4] = {};
    uint8_t key[32] = {}; // per-session key (HKDF-derived in Kotlin), never the pairing key
    // 64-bit so exhaustion goes silent instead of wrapping (send_counter.h).
    std::atomic<uint64_t> counter{1};
    // Held from a datagram's counter until it has left sendto (dish_counter::takeTurnAndCounter);
    // the socket, the token, the key and the counter change only under it.
    std::mutex sendTurnMtx;
    dish_net::DatagramPort port;

    dish::HeartbeatThread heartbeat;
    std::atomic<int> missedAcks{0};
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
    std::atomic<uint32_t> sentByCtrl[MAX_CONTROLLERS] = {};
    std::atomic<uint32_t> motionByCtrl[MAX_CONTROLLERS] = {};

    // Negotiated at session PUT; picks which MSG_TOUCHPAD frame this session encodes.
    std::atomic<int32_t> protocolVersion{1};

    // Per-controller audio state, keyed by ctrlIdx. Touched by the mic capture
    // thread (encode) and the audio dispatch thread (decode), never by the
    // receive thread, so one lock covers it: at 50 frames/s per direction the
    // two threads are each inside for well under a millisecond a second.
    std::mutex audioMtx;
    std::unordered_map<uint8_t, ControllerAudio> audio;
    // Set by closeSocket. Queued speaker frames hold a strong reference to this
    // session, so they can outlive it by a few milliseconds; decoding into a
    // torn-down session would deliver audio to a slot that is already gone.
    std::atomic<bool> closed{false};
};

// A new (token, key) pair: the counters, the replay guard, the ack state and the audio streams
// restart with it (contract §Crypto).
void setConnectionParams(Session& s, const uint8_t token[4], const uint8_t key[32],
                         int32_t protocolVersion);

class SessionTable {
  public:
    // The new session's handle, or -1 when no socket opens or ip is not an IPv4 literal.
    int open(const char* ip, int port);

    // Stops the heartbeat, retires the codecs and closes the socket; an unknown handle is ignored.
    void close(int handle);

    std::shared_ptr<Session> get(int handle);

    void clearRtt();

  private:
    std::mutex mtx_;
    std::unordered_map<int, std::shared_ptr<Session>> sessions_;
    std::atomic<int> nextHandle_{1};
};

} // namespace dish_session
