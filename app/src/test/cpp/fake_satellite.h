// SPDX-License-Identifier: LGPL-3.0-or-later

#pragma once

#include <cstddef>
#include <cstdint>
#include <optional>
#include <vector>

#include "udp_socket.h"

// The satellite's end of a session, for host tests and the bench: its sealing, and a UDP socket
// on the loopback interface that the session sends to and hears back from.
namespace fake_satellite {

using Bytes = std::vector<uint8_t>;

// A datagram sealed as the satellite seals what it sends a client (contract §Crypto: the nonce
// direction byte is 1).
Bytes sealToClient(const uint8_t token[4], const uint8_t key[32], uint32_t counter,
                   uint16_t msgType, const uint8_t* payload, size_t payloadLen);

// A client datagram as the satellite opens it; empty when the token or the tag does not match.
struct Inner {
    uint32_t counter = 0;
    uint16_t msgType = 0;
    Bytes payload;
};
std::optional<Inner> openFromClient(const uint8_t token[4], const uint8_t key[32],
                                    const uint8_t* datagram, size_t len);

// A UDP socket bound to an ephemeral loopback port.
class Peer {
  public:
    Peer();
    ~Peer();
    Peer(const Peer&) = delete;
    Peer& operator=(const Peer&) = delete;

    int port() const { return port_; }

    // One datagram within timeoutMs, empty on timeout; its sender becomes the reply address.
    Bytes receive(int timeoutMs);

    // To the sender of the last datagram received; false before any has arrived.
    bool reply(const Bytes& datagram);

  private:
    int sock_ = -1;
    int port_ = 0;
    sockaddr_in client_ = {};
    bool haveClient_ = false;
};

} // namespace fake_satellite
