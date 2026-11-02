// SPDX-License-Identifier: LGPL-3.0-or-later

#include "session_table.h"

#include <string.h>

#include "log_port.h"

namespace dish_session {

void setConnectionParams(Session& s, const uint8_t token[4], const uint8_t key[32],
                         const int32_t protocolVersion) {
    {
        std::lock_guard<std::mutex> turn(s.sendTurnMtx);
        memcpy(s.token, token, 4);
        memcpy(s.key, key, 32);
        // Counters restart with each (token, sessionKey) pair (contract §Crypto).
        s.counter.store(1);
    }
    s.lastRxCounter.store(0);
    s.missedAcks.store(0);
    s.connectionAlive.store(true);
    s.serverEpoch.store(-1);
    s.activeBitmap.store(-1);
    s.closeReason.store(-1);
    s.protocolVersion.store(protocolVersion);
    // Audio streams restart with the (token, sessionKey) pair too: a re-PUT
    // replugs the pads server-side, so the far end's sequence numbers start
    // over and the old reorder window would read the new stream's first second
    // as ancient history.
    {
        std::lock_guard<std::mutex> audioLock(s.audioMtx);
        s.audio.clear();
    }
}

int SessionTable::open(const char* ip, const int port) {
    const int sock = dish_net::openSessionSocket();
    if (sock < 0) return -1;

    auto session = std::make_shared<Session>();
    session->udpSock = sock;
    // An unparseable address (e.g. an IPv6 literal from mDNS) must fail the
    // connect, not silently stream every packet to 0.0.0.0.
    if (!dish_net::ipv4Destination(ip, port, session->dest)) {
        dish_log::error("openSocket: not an IPv4 literal, refusing");
        dish_net::closeSocket(sock);
        return -1;
    }

    const int handle = nextHandle_.fetch_add(1, std::memory_order_relaxed);
    {
        std::lock_guard<std::mutex> lock(mtx_);
        sessions_[handle] = session;
    }
    dish_log::info("UDP session %d opened -> port %d (TOS=0x%02X)", handle, port,
                   dish_net::kSessionTos);
    return handle;
}

void SessionTable::close(const int handle) {
    std::shared_ptr<Session> s;
    {
        std::lock_guard<std::mutex> lock(mtx_);
        auto it = sessions_.find(handle);
        if (it == sessions_.end()) return;
        s = it->second;
        sessions_.erase(it);
    }
    s->heartbeat.stop();
    // Before the codecs go: a speaker frame already queued still holds a strong
    // reference to this session, and decoding it now would deliver audio to a
    // slot that no longer exists.
    s->closed.store(true, std::memory_order_release);
    {
        std::lock_guard<std::mutex> audioLock(s->audioMtx);
        s->audio.clear();
    }
    {
        std::lock_guard<std::mutex> turn(s->sendTurnMtx);
        if (s->udpSock >= 0) {
            dish_net::closeSocket(s->udpSock);
            s->udpSock = -1;
        }
    }
    dish_log::info("UDP session %d closed", handle);
}

std::shared_ptr<Session> SessionTable::get(const int handle) {
    std::lock_guard<std::mutex> lock(mtx_);
    auto it = sessions_.find(handle);
    return it == sessions_.end() ? nullptr : it->second;
}

void SessionTable::clearRtt() {
    std::lock_guard<std::mutex> lock(mtx_);
    for (auto& kv : sessions_) hotpath::clearRtt(kv.second->rtt);
}

} // namespace dish_session
