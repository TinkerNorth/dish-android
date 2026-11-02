// SPDX-License-Identifier: LGPL-3.0-or-later

#include "udp_socket.h"

#include <arpa/inet.h>
#include <errno.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <unistd.h>

#include "log_port.h"

namespace dish_net {

int openSessionSocket() {
    const int sock = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
    if (sock < 0) {
        dish_log::error("socket() failed");
        return -1;
    }

    const int tos = kSessionTos;
    if (setsockopt(sock, IPPROTO_IP, IP_TOS, &tos, sizeof(tos)) < 0)
        dish_log::info("IP_TOS not supported (non-fatal): %s", strerror(errno));

    const int busyPoll = 50;
    if (setsockopt(sock, SOL_SOCKET, SO_BUSY_POLL, &busyPoll, sizeof(busyPoll)) < 0)
        dish_log::info("SO_BUSY_POLL not supported (non-fatal): %s", strerror(errno));

    const int sendBuffer = kSessionSendBufferBytes;
    if (setsockopt(sock, SOL_SOCKET, SO_SNDBUF, &sendBuffer, sizeof(sendBuffer)) < 0)
        dish_log::info("SO_SNDBUF not taken (non-fatal): %s", strerror(errno));

    const struct timeval rtv = {0, 500000};
    setsockopt(sock, SOL_SOCKET, SO_RCVTIMEO, &rtv, sizeof(rtv));
    return sock;
}

void closeSocket(const int sock) { close(sock); }

ssize_t sendDatagram(const int sock, const uint8_t* packet, const size_t len,
                     const sockaddr_in& dest) {
    // MSG_DONTWAIT: a blocking sendto was observed to stall 1.5s during Wi-Fi power-save
    // transitions.
    return sendto(sock, packet, len, MSG_DONTWAIT, (const struct sockaddr*)&dest, sizeof(dest));
}

ssize_t receiveDatagram(const int sock, uint8_t* buf, const size_t cap) {
    struct sockaddr_in from = {};
    socklen_t fl = sizeof(from);
    return recvfrom(sock, buf, cap, 0, (struct sockaddr*)&from, &fl);
}

// On an unconnected UDP socket Linux answers ENOTCONN but still marks the receive side shut and
// wakes the waiting recvfrom, which then returns 0.
void wakeReceiver(const int sock) { shutdown(sock, SHUT_RD); }

bool ipv4Destination(const char* ip, const int port, sockaddr_in& out) {
    out.sin_family = AF_INET;
    out.sin_port = htons((uint16_t)port);
    return inet_pton(AF_INET, ip, &out.sin_addr) == 1;
}

} // namespace dish_net
