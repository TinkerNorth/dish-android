// SPDX-License-Identifier: LGPL-3.0-or-later

#pragma once

#include <gtest/gtest.h>
#include <sodium.h>

#include <cstdint>
#include <memory>
#include <vector>

#include "recording_sinks.h"
#include "session_table.h"
#include "wire_vectors.h"

// A session keyed as the one wire_vectors.h was captured from, with its datagrams recorded
// rather than sent.
namespace vector_session {

using Bytes = std::vector<uint8_t>;

template <size_t N> Bytes bytesOf(const uint8_t (&array)[N]) { return Bytes(array, array + N); }

struct Key {
    uint8_t bytes[32];
};

inline Key vectorKey() {
    Key key{};
    for (int i = 0; i < 32; i++) key.bytes[i] = (uint8_t)(wire_vectors::kKeyBase + i);
    return key;
}

inline uint32_t counterOf(const Bytes& datagram) {
    return (uint32_t)datagram[4] << 24 | (uint32_t)datagram[5] << 16 | (uint32_t)datagram[6] << 8 |
           (uint32_t)datagram[7];
}

class VectorSession : public ::testing::Test {
  protected:
    static void SetUpTestSuite() { ASSERT_GE(sodium_init(), 0); }

    void SetUp() override {
        handle = table.open("127.0.0.1", 9);
        ASSERT_GT(handle, 0);
        session = table.get(handle);
        const Key key = vectorKey();
        dish_session::setConnectionParams(*session, wire_vectors::kToken, key.bytes, 1);
        session->counter.store(wire_vectors::kFirstCounter);
        port.attachTo(*session);
    }

    void TearDown() override { table.close(handle); }

    std::vector<Bytes> sentBytes() {
        std::vector<Bytes> out;
        for (recording::Sent& sent : port.taken()) out.push_back(std::move(sent.bytes));
        return out;
    }

    dish_session::SessionTable table;
    int handle = -1;
    std::shared_ptr<dish_session::Session> session;
    recording::Port port;
};

} // namespace vector_session
