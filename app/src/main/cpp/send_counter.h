// SPDX-License-Identifier: LGPL-3.0-or-later

#pragma once

#include <atomic>
#include <cstdint>

namespace dish_counter {

// Counters never wrap (contract §Crypto): sealing two plaintexts under one
// (key, nonce) would be catastrophic. 64-bit storage so exhaustion parks the
// sender silent instead of wrapping the 32-bit wire field into nonce reuse.
inline constexpr uint64_t kCounterMaxWire = 0xFFFFFFFFull;

// Draws the next wire counter; false once the 32-bit space is exhausted (the
// caller must go silent, never send). A drawn value is never repeated.
inline bool acquireSendCounter(std::atomic<uint64_t>& counter, uint32_t* out) {
    const uint64_t seq = counter.fetch_add(1, std::memory_order_relaxed);
    if (seq > kCounterMaxWire) return false;
    *out = static_cast<uint32_t>(seq);
    return true;
}

// Clamped, not truncated, for the Kotlin re-key poll: past exhaustion it must
// keep reading re-PUT needed, never wrap under the threshold.
inline uint32_t sendCounterView(const std::atomic<uint64_t>& counter) {
    const uint64_t v = counter.load(std::memory_order_relaxed);
    return v > kCounterMaxWire ? static_cast<uint32_t>(kCounterMaxWire) : static_cast<uint32_t>(v);
}

} // namespace dish_counter
