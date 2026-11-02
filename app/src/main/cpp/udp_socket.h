// SPDX-License-Identifier: LGPL-3.0-or-later

#pragma once

#include <cstddef>
#include <cstdint>

#ifdef _WIN32
#ifndef NOMINMAX
#define NOMINMAX
#endif
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#include <winsock2.h>
#include <ws2tcpip.h>
#include <sys/types.h>
#else
#include <netinet/in.h>
#include <sys/types.h>
#endif

// The socket calls a session makes, as POSIX spells them (udp_socket.cpp). A Windows host build
// supplies its own (app/src/test/cpp/udp_socket_windows.cpp) so the host tests and the bench run
// there too.
namespace dish_net {

// DSCP EF, the class Wi-Fi WMM queues as voice.
inline constexpr int kSessionTos = 0xB8;

// A session's UDP socket: IP_TOS and SO_BUSY_POLL, both best effort, and a 500 ms receive timeout
// so the receive loop wakes to notice a stop. -1 when socket() fails.
int openSessionSocket();

void closeSocket(int sock);

// One datagram out without waiting for buffer space (MSG_DONTWAIT): the bytes sent, or -1 with
// errno set.
ssize_t sendDatagram(int sock, const uint8_t* packet, size_t len, const sockaddr_in& dest);

// One datagram in, waiting at most the receive timeout: its length, or -1 with errno set (EAGAIN
// or EWOULDBLOCK once the timeout passes).
ssize_t receiveDatagram(int sock, uint8_t* buf, size_t cap);

// false for anything but an IPv4 literal, such as an IPv6 literal from mDNS.
bool ipv4Destination(const char* ip, int port, sockaddr_in& out);

inline ssize_t sendToSocket(void*, const int sock, const uint8_t* packet, const size_t len,
                            const sockaddr_in& dest) {
    return sendDatagram(sock, packet, len, dest);
}

// Where a session's sealed datagrams go: the socket, unless a test or the bench redirects them.
// The call is made with the session's send turn held, and errno is read right after it.
struct DatagramPort {
    using Send = ssize_t (*)(void* context, int sock, const uint8_t* packet, size_t len,
                             const sockaddr_in& dest);
    Send send = &sendToSocket;
    void* context = nullptr;
};

} // namespace dish_net
