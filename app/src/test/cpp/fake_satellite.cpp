// SPDX-License-Identifier: LGPL-3.0-or-later

#include "fake_satellite.h"

#include <sodium.h>

#include <cstring>

#include "wire_encoders.h"

#ifdef _WIN32
#include <mutex>
#else
#include <arpa/inet.h>
#include <sys/select.h>
#include <sys/socket.h>
#include <unistd.h>
#endif

namespace fake_satellite {

namespace {

void putBE32(uint8_t* dst, const uint32_t v) {
    dst[0] = (uint8_t)(v >> 24);
    dst[1] = (uint8_t)(v >> 16);
    dst[2] = (uint8_t)(v >> 8);
    dst[3] = (uint8_t)v;
}

uint32_t readBE32(const uint8_t* src) {
    return (uint32_t)src[0] << 24 | (uint32_t)src[1] << 16 | (uint32_t)src[2] << 8 |
           (uint32_t)src[3];
}

void nonceFor(const uint8_t direction, const uint32_t counter, uint8_t nonce[12]) {
    memset(nonce, 0, 12);
    nonce[0] = direction;
    putBE32(nonce + 8, counter);
}

#ifdef _WIN32
using SocketHandle = SOCKET;
using AddressLength = int;
void startSockets() {
    static std::once_flag started;
    std::call_once(started, [] {
        WSADATA data;
        WSAStartup(MAKEWORD(2, 2), &data);
    });
}
void closeHandle(const SocketHandle s) { closesocket(s); }
#else
using SocketHandle = int;
using AddressLength = socklen_t;
void startSockets() {}
void closeHandle(const SocketHandle s) { close(s); }
#endif

SocketHandle handleOf(const int sock) { return static_cast<SocketHandle>(sock); }

} // namespace

Bytes sealToClient(const uint8_t token[4], const uint8_t key[32], const uint32_t counter,
                   const uint16_t msgType, const uint8_t* payload, const size_t payloadLen) {
    Bytes inner{(uint8_t)(msgType >> 8), (uint8_t)msgType, (uint8_t)(payloadLen >> 8),
                (uint8_t)payloadLen};
    inner.insert(inner.end(), payload, payload + payloadLen);

    Bytes datagram(dish_wire::OUTER_HEADER_BYTES + inner.size() + dish_wire::AEAD_TAG_BYTES);
    memcpy(datagram.data(), token, 4);
    putBE32(datagram.data() + 4, counter);
    uint8_t nonce[12];
    nonceFor(dish_wire::CRYPTO_DIR_SERVER_TO_CLIENT, counter, nonce);
    unsigned long long sealedLen = 0;
    crypto_aead_chacha20poly1305_ietf_encrypt(datagram.data() + dish_wire::OUTER_HEADER_BYTES,
                                              &sealedLen, inner.data(), inner.size(), token, 4,
                                              nullptr, nonce, key);
    datagram.resize(dish_wire::OUTER_HEADER_BYTES + (size_t)sealedLen);
    return datagram;
}

std::optional<Inner> openFromClient(const uint8_t token[4], const uint8_t key[32],
                                    const uint8_t* datagram, const size_t len) {
    if (len < dish_wire::OUTER_HEADER_BYTES + dish_wire::AEAD_TAG_BYTES) return std::nullopt;
    if (memcmp(datagram, token, 4) != 0) return std::nullopt;
    const uint32_t counter = readBE32(datagram + 4);
    uint8_t nonce[12];
    nonceFor(dish_wire::CRYPTO_DIR_CLIENT_TO_SERVER, counter, nonce);
    Bytes inner(len);
    unsigned long long innerLen = 0;
    const int opened = crypto_aead_chacha20poly1305_ietf_decrypt(
        inner.data(), &innerLen, nullptr, datagram + dish_wire::OUTER_HEADER_BYTES,
        len - dish_wire::OUTER_HEADER_BYTES, token, 4, nonce, key);
    if (opened != 0 || innerLen < dish_wire::INNER_HEADER_BYTES) return std::nullopt;
    Inner out;
    out.counter = counter;
    out.msgType = (uint16_t)(inner[0] << 8 | inner[1]);
    out.payload.assign(inner.begin() + dish_wire::INNER_HEADER_BYTES,
                       inner.begin() + (std::ptrdiff_t)innerLen);
    return out;
}

Peer::Peer() {
    startSockets();
    const SocketHandle s = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
    sockaddr_in local = {};
    local.sin_family = AF_INET;
    local.sin_port = 0;
    inet_pton(AF_INET, "127.0.0.1", &local.sin_addr);
    bind(s, reinterpret_cast<const sockaddr*>(&local), sizeof(local));
    AddressLength len = sizeof(local);
    getsockname(s, reinterpret_cast<sockaddr*>(&local), &len);
    sock_ = static_cast<int>(s);
    port_ = ntohs(local.sin_port);
}

Peer::~Peer() { closeHandle(handleOf(sock_)); }

Bytes Peer::receive(const int timeoutMs) {
    const SocketHandle s = handleOf(sock_);
    fd_set readable;
    FD_ZERO(&readable);
    FD_SET(s, &readable);
    timeval timeout = {timeoutMs / 1000, (timeoutMs % 1000) * 1000};
    if (select(sock_ + 1, &readable, nullptr, nullptr, &timeout) <= 0) return Bytes();
    Bytes datagram(dish_wire::MAX_DATAGRAM_BYTES);
    sockaddr_in from = {};
    AddressLength fromLen = sizeof(from);
    const auto n = recvfrom(s, reinterpret_cast<char*>(datagram.data()), (int)datagram.size(), 0,
                            reinterpret_cast<sockaddr*>(&from), &fromLen);
    if (n <= 0) return Bytes();
    client_ = from;
    haveClient_ = true;
    datagram.resize((size_t)n);
    return datagram;
}

bool Peer::reply(const Bytes& datagram) {
    if (!haveClient_) return false;
    const auto n = sendto(handleOf(sock_), reinterpret_cast<const char*>(datagram.data()),
                          (int)datagram.size(), 0, reinterpret_cast<const sockaddr*>(&client_),
                          sizeof(client_));
    return n >= 0 && static_cast<size_t>(n) == datagram.size();
}

} // namespace fake_satellite
