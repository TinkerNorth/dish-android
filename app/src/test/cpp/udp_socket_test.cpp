// SPDX-License-Identifier: LGPL-3.0-or-later

// udp_socket.h as each host build provides it: POSIX on Linux, the Winsock shim on Windows. The
// session units rely on exactly these behaviours, so both have to show them.

#include "udp_socket.h"

#include <gtest/gtest.h>

#include <errno.h>

#include <chrono>
#include <cstdint>
#include <vector>

#include "fake_satellite.h"

namespace {

using Bytes = std::vector<uint8_t>;

constexpr int kReceiveTimeoutMs = 5000;
constexpr long long kSessionReceiveTimeoutMs = 500;

long long millisSince(const std::chrono::steady_clock::time_point start) {
    return std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() -
                                                                 start)
        .count();
}

class UdpSocket : public ::testing::Test {
  protected:
    void SetUp() override {
        sock = dish_net::openSessionSocket();
        ASSERT_GE(sock, 0);
        ASSERT_TRUE(dish_net::ipv4Destination("127.0.0.1", satellite.port(), dest));
    }

    void TearDown() override { dish_net::closeSocket(sock); }

    fake_satellite::Peer satellite;
    int sock = -1;
    sockaddr_in dest = {};
};

} // namespace

TEST_F(UdpSocket, ASentDatagramArrivesWhole) {
    const Bytes datagram{1, 2, 3, 4, 5, 6, 7, 8, 9};
    EXPECT_EQ(dish_net::sendDatagram(sock, datagram.data(), datagram.size(), dest),
              (ssize_t)datagram.size());
    EXPECT_EQ(satellite.receive(kReceiveTimeoutMs), datagram);
}

TEST_F(UdpSocket, AReplyToTheSocketsOwnAddressIsReceivedWhole) {
    const Bytes ping{0xAA};
    ASSERT_EQ(dish_net::sendDatagram(sock, ping.data(), ping.size(), dest), 1);
    ASSERT_FALSE(satellite.receive(kReceiveTimeoutMs).empty());
    const Bytes reply(1400, 0x5C);
    ASSERT_TRUE(satellite.reply(reply));
    uint8_t buf[1500];
    const ssize_t n = dish_net::receiveDatagram(sock, buf, sizeof(buf));
    ASSERT_EQ(n, (ssize_t)reply.size());
    EXPECT_EQ(Bytes(buf, buf + n), reply);
}

TEST_F(UdpSocket, NothingToReceiveIsATimeoutWithEagainAfterHalfASecond) {
    uint8_t buf[64];
    const auto start = std::chrono::steady_clock::now();
    const ssize_t n = dish_net::receiveDatagram(sock, buf, sizeof(buf));
    const long long waitedMs = millisSince(start);
    EXPECT_EQ(n, -1);
    EXPECT_TRUE(errno == EAGAIN || errno == EWOULDBLOCK) << "errno " << errno;
    EXPECT_GE(waitedMs, kSessionReceiveTimeoutMs / 2);
    EXPECT_LT(waitedMs, kSessionReceiveTimeoutMs * 4);
}

TEST_F(UdpSocket, APeerThatIsGoneDoesNotEndTheReceiveLoop) {
    sockaddr_in nobody = {};
    {
        fake_satellite::Peer gone;
        ASSERT_TRUE(dish_net::ipv4Destination("127.0.0.1", gone.port(), nobody));
    }
    const Bytes ping{0x01};
    dish_net::sendDatagram(sock, ping.data(), ping.size(), nobody);
    uint8_t buf[64];
    EXPECT_EQ(dish_net::receiveDatagram(sock, buf, sizeof(buf)), -1);
    EXPECT_TRUE(errno == EAGAIN || errno == EWOULDBLOCK) << "errno " << errno;
}

TEST(UdpDestination, TakesAnIpv4LiteralWithItsPortInNetworkOrder) {
    sockaddr_in out = {};
    ASSERT_TRUE(dish_net::ipv4Destination("192.168.1.20", 0x1234, out));
    EXPECT_EQ(out.sin_family, AF_INET);
    EXPECT_EQ(out.sin_port, htons(0x1234));
    const auto* address = reinterpret_cast<const uint8_t*>(&out.sin_addr);
    EXPECT_EQ(Bytes(address, address + 4), (Bytes{192, 168, 1, 20}));
}

TEST(UdpDestination, RefusesAnythingButAnIpv4Literal) {
    sockaddr_in out = {};
    EXPECT_FALSE(dish_net::ipv4Destination("::1", 1, out));
    EXPECT_FALSE(dish_net::ipv4Destination("fe80::1", 1, out));
    EXPECT_FALSE(dish_net::ipv4Destination("satellite.local", 1, out));
    EXPECT_FALSE(dish_net::ipv4Destination("256.1.1.1", 1, out));
    EXPECT_FALSE(dish_net::ipv4Destination("", 1, out));
}
