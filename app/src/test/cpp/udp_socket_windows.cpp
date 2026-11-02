// SPDX-License-Identifier: LGPL-3.0-or-later

// udp_socket.h on Winsock, for the Windows host build only: what the session units need from a
// socket, with errno set the way the POSIX calls set it.

#include "udp_socket.h"

#include <mswsock.h>

#include <errno.h>
#include <mutex>

#include "log_port.h"

namespace dish_net {

namespace {

void startWinsock() {
    static std::once_flag started;
    std::call_once(started, [] {
        WSADATA data;
        WSAStartup(MAKEWORD(2, 2), &data);
    });
}

int errnoFor(const int wsaError) {
    switch (wsaError) {
    case WSAEWOULDBLOCK:
        return EWOULDBLOCK;
    case WSAETIMEDOUT:
        return EAGAIN;
    case WSAEINTR:
        return EINTR;
    case WSAENOTSOCK:
        return EBADF;
    default:
        return EIO;
    }
}

ssize_t withErrno(const int result) {
    if (result == SOCKET_ERROR) {
        errno = errnoFor(WSAGetLastError());
        return -1;
    }
    return result;
}

} // namespace

int openSessionSocket() {
    startWinsock();
    const SOCKET sock = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
    if (sock == INVALID_SOCKET) {
        dish_log::error("socket() failed");
        return -1;
    }
    const int tos = kSessionTos;
    if (setsockopt(sock, IPPROTO_IP, IP_TOS, reinterpret_cast<const char*>(&tos), sizeof(tos)) ==
        SOCKET_ERROR) {
        dish_log::info("IP_TOS not supported (non-fatal): %d", WSAGetLastError());
    }
    const int sendBuffer = kSessionSendBufferBytes;
    if (setsockopt(sock, SOL_SOCKET, SO_SNDBUF, reinterpret_cast<const char*>(&sendBuffer),
                   sizeof(sendBuffer)) == SOCKET_ERROR) {
        dish_log::info("SO_SNDBUF not taken (non-fatal): %d", WSAGetLastError());
    }
    // Winsock takes the receive timeout in milliseconds, not as a timeval.
    const DWORD timeoutMs = 500;
    setsockopt(sock, SOL_SOCKET, SO_RCVTIMEO, reinterpret_cast<const char*>(&timeoutMs),
               sizeof(timeoutMs));
    // An unconnected POSIX UDP socket never reports a peer's ICMP port-unreachable; Winsock would
    // fail the next receive with WSAECONNRESET unless told not to.
    BOOL reportReset = FALSE;
    DWORD returned = 0;
    WSAIoctl(sock, SIO_UDP_CONNRESET, &reportReset, sizeof(reportReset), nullptr, 0, &returned,
             nullptr, nullptr);
    // A POSIX receive on a socket that has not sent yet waits out its timeout; Winsock fails it at
    // once unless the socket is bound, so it takes the ephemeral port its first send would.
    sockaddr_in any = {};
    any.sin_family = AF_INET;
    bind(sock, reinterpret_cast<const sockaddr*>(&any), sizeof(any));
    return static_cast<int>(sock);
}

void closeSocket(const int sock) { closesocket(static_cast<SOCKET>(sock)); }

// Winsock has no per-call MSG_DONTWAIT, so this send can wait for buffer space where the POSIX
// one would fail with EAGAIN.
ssize_t sendDatagram(const int sock, const uint8_t* packet, const size_t len,
                     const sockaddr_in& dest) {
    return withErrno(sendto(static_cast<SOCKET>(sock), reinterpret_cast<const char*>(packet),
                            static_cast<int>(len), 0, reinterpret_cast<const sockaddr*>(&dest),
                            sizeof(dest)));
}

ssize_t receiveDatagram(const int sock, uint8_t* buf, const size_t cap) {
    sockaddr_in from = {};
    int fl = sizeof(from);
    return withErrno(recvfrom(static_cast<SOCKET>(sock), reinterpret_cast<char*>(buf),
                              static_cast<int>(cap), 0, reinterpret_cast<sockaddr*>(&from), &fl));
}

bool ipv4Destination(const char* ip, const int port, sockaddr_in& out) {
    out.sin_family = AF_INET;
    out.sin_port = htons(static_cast<u_short>(port));
    return inet_pton(AF_INET, ip, &out.sin_addr) == 1;
}

} // namespace dish_net
