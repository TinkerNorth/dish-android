// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.
//
// Bitwise arithmetic on unsigned values only, so no shift, mask or OR ever has a signed operand.
// A uint8_t or uint16_t is promoted to int before any operator touches it, and int is signed:
// shifting a byte whose top bit is set into the sign bit, or masking a value after it was
// sign-extended, is how a parser goes wrong. So:
//
//   * bytes and half-words widen through u32() before any bitwise operator;
//   * shift counts and masks are unsigned literals (8U, 0xFFU);
//   * a narrower result is taken explicitly (lowByte, low16, byteOf);
//   * a signed value, a jint from Kotlin for one, is turned into its unsigned bits first
//     (asUint32) and a signed result is produced last (asInt16, asInt32).
//
// The reinterpretations are static_casts, which are modular on every compiler this builds with
// (and defined as such from C++20); byte_boundaries_test.cpp pins them at the sign boundaries.
// clang-tidy's bugprone-signed-bitwise holds every first-party file to this.

#pragma once

#include <cstddef>
#include <cstdint>

namespace dish_bits {

constexpr uint32_t u32(const uint8_t v) noexcept { return v; }
constexpr uint32_t u32(const uint16_t v) noexcept { return v; }
constexpr uint32_t u32(const uint32_t v) noexcept { return v; }
constexpr uint64_t u64(const uint8_t v) noexcept { return v; }
constexpr uint64_t u64(const uint32_t v) noexcept { return v; }
constexpr uint64_t u64(const uint64_t v) noexcept { return v; }

constexpr uint32_t asUint32(const int32_t v) noexcept { return static_cast<uint32_t>(v); }
constexpr uint64_t asUint64(const int64_t v) noexcept { return static_cast<uint64_t>(v); }
constexpr uint32_t low32(const uint64_t v) noexcept {
    return static_cast<uint32_t>(v & 0xFFFFFFFFULL);
}
constexpr int32_t asInt32(const uint32_t v) noexcept { return static_cast<int32_t>(v); }
constexpr int16_t asInt16(const uint16_t v) noexcept { return static_cast<int16_t>(v); }
constexpr uint16_t asUint16(const int16_t v) noexcept { return static_cast<uint16_t>(v); }

constexpr uint8_t lowByte(const uint32_t v) noexcept { return static_cast<uint8_t>(v & 0xFFU); }
constexpr uint8_t lowByte(const uint64_t v) noexcept { return static_cast<uint8_t>(v & 0xFFU); }
constexpr uint16_t low16(const uint32_t v) noexcept { return static_cast<uint16_t>(v & 0xFFFFU); }

// The low byte or half-word of a signed 32-bit value (a jint), by its bits, never its sign.
constexpr uint8_t lowByte(const int32_t v) noexcept { return lowByte(asUint32(v)); }
constexpr uint16_t low16(const int32_t v) noexcept { return low16(asUint32(v)); }

// True when every bit of `mask` is set in `flags` (a jint or an Android constant, by its bits).
constexpr bool hasAllBits(const int32_t flags, const int32_t mask) noexcept {
    return (asUint32(flags) & asUint32(mask)) == asUint32(mask);
}

// `flags` with only the bits of `mask` kept.
constexpr int32_t maskedBits(const int32_t flags, const int32_t mask) noexcept {
    return asInt32(asUint32(flags) & asUint32(mask));
}

// Byte `index` of `v`, counting from the least significant.
constexpr uint8_t byteOf(const uint32_t v, const unsigned index) noexcept {
    return lowByte(v >> (8U * index));
}
constexpr uint8_t byteOf(const uint64_t v, const unsigned index) noexcept {
    return lowByte(v >> (8U * index));
}

constexpr uint16_t loadLe16(const uint8_t* p) noexcept {
    return low16(u32(p[0]) | (u32(p[1]) << 8U));
}
constexpr uint16_t loadBe16(const uint8_t* p) noexcept {
    return low16((u32(p[0]) << 8U) | u32(p[1]));
}
constexpr uint32_t loadLe24(const uint8_t* p) noexcept {
    return u32(p[0]) | (u32(p[1]) << 8U) | (u32(p[2]) << 16U);
}
constexpr uint32_t loadLe32(const uint8_t* p) noexcept {
    return u32(p[0]) | (u32(p[1]) << 8U) | (u32(p[2]) << 16U) | (u32(p[3]) << 24U);
}
constexpr uint32_t loadBe32(const uint8_t* p) noexcept {
    return (u32(p[0]) << 24U) | (u32(p[1]) << 16U) | (u32(p[2]) << 8U) | u32(p[3]);
}

inline void storeLe16(uint8_t* p, const uint16_t v) noexcept {
    p[0] = byteOf(u32(v), 0U);
    p[1] = byteOf(u32(v), 1U);
}
inline void storeBe16(uint8_t* p, const uint16_t v) noexcept {
    p[0] = byteOf(u32(v), 1U);
    p[1] = byteOf(u32(v), 0U);
}
inline void storeLe32(uint8_t* p, const uint32_t v) noexcept {
    p[0] = byteOf(v, 0U);
    p[1] = byteOf(v, 1U);
    p[2] = byteOf(v, 2U);
    p[3] = byteOf(v, 3U);
}
inline void storeBe32(uint8_t* p, const uint32_t v) noexcept {
    p[0] = byteOf(v, 3U);
    p[1] = byteOf(v, 2U);
    p[2] = byteOf(v, 1U);
    p[3] = byteOf(v, 0U);
}

} // namespace dish_bits
