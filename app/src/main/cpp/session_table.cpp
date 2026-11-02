// SPDX-License-Identifier: LGPL-3.0-or-later

#include "session_table.h"

#include <string.h>

#include <cstdint>
#include <thread>

#include "log_port.h"
#include "tracked_mutex.h"

namespace dish_session {

namespace {

Material materialOf(const uint64_t (&raw)[MATERIAL_WORDS]) {
    Material m;
    memcpy(&m, raw, sizeof(m));
    return m;
}

// The gate's holder only: the seqlock write that lets the receive thread read the material whole.
void storeMaterial(Session& s, const Material& m) {
    uint64_t raw[MATERIAL_WORDS] = {};
    memcpy(raw, &m, sizeof(m));
    s.gen.fetch_add(1, std::memory_order_acq_rel);
    std::atomic_thread_fence(std::memory_order_release);
    for (size_t i = 0; i < MATERIAL_WORDS; i++) {
        s.material[i].store(raw[i], std::memory_order_relaxed);
    }
    s.gen.fetch_add(1, std::memory_order_release);
}

// What a new session starts from in an entry an earlier one may have used. The entry is reserved
// and its handle not yet published, so only a stale reference can be looking, and every reader
// checks the handle before it trusts the rest.
void restartEntry(Session& s, const int32_t handle, const int sock, const sockaddr_in& dest) {
    s.heartbeat.stop();
    s.receiver.stop();
    s.missedAcks.store(0);
    s.connectionAlive.store(true);
    s.lastPingNs.store(0);
    s.lastRxCounter.store(0);
    s.serverEpoch.store(-1);
    s.activeBitmap.store(-1);
    s.closeReason.store(-1);
    s.vigemAvailable.store(-1);
    s.activeControllerCount.store(-1);
    hotpath::clearRtt(s.rtt);
    s.rtt.pings.store(0);
    s.rtt.acks.store(0);
    for (std::atomic<uint32_t>& sent : s.sentByCtrl) sent.store(0);
    for (std::atomic<uint32_t>& motion : s.motionByCtrl) motion.store(0);
    {
        std::lock_guard<TrackedMutex> audioLock(s.audioMtx);
        s.audio.clear();
        s.audioHandle = handle;
    }
    s.gate.hold();
    s.port = dish_net::DatagramPort{};
    s.counter.store(1, std::memory_order_relaxed);
    s.protocolVersion.store(1, std::memory_order_relaxed);
    Material m;
    m.handle = handle;
    m.sock = sock;
    m.dest = dest;
    storeMaterial(s, m);
    s.gate.release();
}

} // namespace

bool SendGate::tryHold() noexcept {
    uint32_t free = 0;
    return held_.compare_exchange_strong(free, 1, std::memory_order_seq_cst);
}

void SendGate::hold() noexcept {
    while (!tryHold()) std::this_thread::yield();
}

void SendGate::release() noexcept { held_.store(0, std::memory_order_seq_cst); }

Material materialUnderGate(const Session& s) {
    uint64_t raw[MATERIAL_WORDS];
    for (size_t i = 0; i < MATERIAL_WORDS; i++) {
        raw[i] = s.material[i].load(std::memory_order_relaxed);
    }
    return materialOf(raw);
}

Material readMaterial(const Session& s) {
    for (;;) {
        const uint32_t before = s.gen.load(std::memory_order_acquire);
        if ((before & 1u) != 0) {
            std::this_thread::yield();
            continue;
        }
        uint64_t raw[MATERIAL_WORDS];
        for (size_t i = 0; i < MATERIAL_WORDS; i++) {
            raw[i] = s.material[i].load(std::memory_order_relaxed);
        }
        std::atomic_thread_fence(std::memory_order_acquire);
        if (s.gen.load(std::memory_order_relaxed) == before) return materialOf(raw);
    }
}

void setConnectionParams(const SessionRef& ref, const uint8_t token[4], const uint8_t key[32],
                         const int32_t protocolVersion) {
    if (!ref) return;
    Session& s = *ref.entry;
    s.gate.hold();
    Material m = materialUnderGate(s);
    const bool open = m.handle == ref.handle;
    if (open) {
        memcpy(m.token, token, 4);
        memcpy(m.key, key, 32);
        storeMaterial(s, m);
        // Counters restart with each (token, sessionKey) pair (contract §Crypto).
        s.counter.store(1, std::memory_order_relaxed);
        s.protocolVersion.store(protocolVersion, std::memory_order_relaxed);
        s.lastRxCounter.store(0);
        s.missedAcks.store(0);
        s.connectionAlive.store(true);
        s.serverEpoch.store(-1);
        s.activeBitmap.store(-1);
        s.closeReason.store(-1);
    }
    s.gate.release();
    if (!open) return;
    // Audio streams restart with the (token, sessionKey) pair too: a re-PUT
    // replugs the pads server-side, so the far end's sequence numbers start
    // over and the old reorder window would read the new stream's first second
    // as ancient history.
    std::lock_guard<TrackedMutex> audioLock(s.audioMtx);
    if (s.audioHandle == ref.handle) s.audio.clear();
}

SessionTable::~SessionTable() {
    for (const std::atomic<int32_t>& handle : handles_) {
        const int32_t open = handle.load();
        if (open != 0) close(open);
    }
}

int SessionTable::open(const char* ip, const int port) {
    sockaddr_in dest = {};
    // An unparseable address (e.g. an IPv6 literal from mDNS) must fail the
    // connect, not silently stream every packet to 0.0.0.0.
    if (!dish_net::ipv4Destination(ip, port, dest)) {
        dish_log::error("openSocket: not an IPv4 literal, refusing");
        return -1;
    }
    const int sock = dish_net::openSessionSocket();
    if (sock < 0) return -1;

    int index = -1;
    int32_t handle = 0;
    {
        std::lock_guard<TrackedMutex> lock(openMtx_);
        for (size_t i = 0; i < MAX_SESSIONS && index < 0; i++) {
            if (!reserved_[i]) index = static_cast<int>(i);
        }
        if (index >= 0) {
            reserved_[static_cast<size_t>(index)] = true;
            handle = nextHandle();
        }
    }
    if (index < 0) {
        dish_log::error("openSocket: all %u sessions are open, refusing", (unsigned)MAX_SESSIONS);
        dish_net::closeSocket(sock);
        return -1;
    }
    restartEntry(sessions_[static_cast<size_t>(index)], handle, sock, dest);
    handles_[static_cast<size_t>(index)].store(handle, std::memory_order_release);
    dish_log::info("UDP session %d opened -> port %d (TOS=0x%02X)", handle, port,
                   dish_net::kSessionTos);
    return handle;
}

void SessionTable::close(const int handle) {
    int index = -1;
    {
        std::lock_guard<TrackedMutex> lock(openMtx_);
        index = indexOf(handle);
        if (index < 0) return;
        handles_[static_cast<size_t>(index)].store(0, std::memory_order_release);
    }
    Session& s = sessions_[static_cast<size_t>(index)];
    s.heartbeat.stop();
    dish_net::wakeReceiver(readMaterial(s).sock);
    s.receiver.stop();
    // Before the socket goes: a speaker frame already queued must not decode into a session that
    // no longer exists.
    {
        std::lock_guard<TrackedMutex> audioLock(s.audioMtx);
        s.audio.clear();
        s.audioHandle = 0;
    }
    s.gate.hold();
    const int sock = materialUnderGate(s).sock;
    storeMaterial(s, Material{});
    s.gate.release();
    if (sock >= 0) {
        blockingRegion("net.close");
        dish_net::closeSocket(sock);
    }
    {
        std::lock_guard<TrackedMutex> lock(openMtx_);
        reserved_[static_cast<size_t>(index)] = false;
    }
    dish_log::info("UDP session %d closed", handle);
}

SessionRef SessionTable::find(const int handle) {
    const int index = indexOf(handle);
    if (index < 0) return SessionRef{};
    return SessionRef{&sessions_[static_cast<size_t>(index)], handle};
}

bool SessionTable::stillOpen(const SessionRef& s) const {
    if (!s) return false;
    const auto index = static_cast<size_t>(s.entry - sessions_.data());
    return index < MAX_SESSIONS && handles_[index].load(std::memory_order_acquire) == s.handle;
}

void SessionTable::clearRtt() {
    for (size_t i = 0; i < MAX_SESSIONS; i++) {
        if (handles_[i].load(std::memory_order_acquire) != 0) hotpath::clearRtt(sessions_[i].rtt);
    }
}

int SessionTable::indexOf(const int handle) const {
    if (handle <= 0) return -1;
    for (size_t i = 0; i < MAX_SESSIONS; i++) {
        if (handles_[i].load(std::memory_order_acquire) == handle) return static_cast<int>(i);
    }
    return -1;
}

// Rising from 1 and never 0, which marks a free entry; past INT32_MAX it starts over, skipping any
// handle still open.
int32_t SessionTable::nextHandle() {
    for (;;) {
        lastHandle_ = lastHandle_ == INT32_MAX ? 1 : lastHandle_ + 1;
        if (indexOf(lastHandle_) < 0) return lastHandle_;
    }
}

} // namespace dish_session
