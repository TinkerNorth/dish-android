// SPDX-License-Identifier: LGPL-3.0-or-later

#pragma once

#include <array>
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <thread>
#include <type_traits>

namespace dish_hot {

inline constexpr size_t CACHE_LINE_BYTES = 64;

// A bounded ring any thread may push to and pop from, without a lock (Vyukov's bounded queue: a
// cell's sequence number says whether it waits for a producer or a consumer). Items are copied in
// and out whole, so they hold no pointer whose lifetime the ring would have to manage.
template <typename T, size_t Capacity> class MpmcRing {
    static_assert(Capacity >= 2 && (Capacity & (Capacity - 1)) == 0,
                  "the capacity is a power of two");
    static_assert(std::is_trivially_copyable_v<T>, "items are copied in and out whole");

  public:
    static constexpr size_t CAPACITY = Capacity;

    MpmcRing() noexcept {
        for (size_t i = 0; i < Capacity; ++i)
            cells_[i].sequence.store(i, std::memory_order_relaxed);
    }
    MpmcRing(const MpmcRing&) = delete;
    MpmcRing& operator=(const MpmcRing&) = delete;

    // False when the ring is full.
    bool tryPush(const T& item) noexcept {
        uint64_t pos = enqueue_.value.load(std::memory_order_relaxed);
        for (;;) {
            Cell& cell = cells_[static_cast<size_t>(pos & MASK)];
            const uint64_t sequence = cell.sequence.load(std::memory_order_acquire);
            const auto lag = static_cast<int64_t>(sequence - pos);
            if (lag < 0) return false;
            if (lag > 0) {
                pos = enqueue_.value.load(std::memory_order_relaxed);
                continue;
            }
            if (enqueue_.value.compare_exchange_weak(pos, pos + 1, std::memory_order_seq_cst,
                                                     std::memory_order_relaxed)) {
                cell.item = item;
                cell.sequence.store(pos + 1, std::memory_order_release);
                return true;
            }
        }
    }

    // False when the ring is empty.
    bool tryPop(T& out) noexcept {
        uint64_t pos = dequeue_.value.load(std::memory_order_relaxed);
        for (;;) {
            Cell& cell = cells_[static_cast<size_t>(pos & MASK)];
            const uint64_t sequence = cell.sequence.load(std::memory_order_acquire);
            const auto lag = static_cast<int64_t>(sequence - (pos + 1));
            if (lag < 0) return false;
            if (lag > 0) {
                pos = dequeue_.value.load(std::memory_order_relaxed);
                continue;
            }
            if (dequeue_.value.compare_exchange_weak(pos, pos + 1, std::memory_order_seq_cst,
                                                     std::memory_order_relaxed)) {
                out = cell.item;
                cell.sequence.store(pos + Capacity, std::memory_order_release);
                return true;
            }
        }
    }

    // Pushes, dropping the oldest item when the ring is full; answers how many it dropped. Once
    // one is dropped the push only waits, yielding, for the consumer that is copying the oldest
    // cell out to let it go, so a full ring never loses more than the one item.
    uint32_t pushDroppingOldest(const T& item) noexcept {
        uint32_t dropped = 0;
        while (!tryPush(item)) {
            T oldest;
            if (depth() >= Capacity && tryPop(oldest)) {
                dropped++;
                continue;
            }
            std::this_thread::yield();
        }
        return dropped;
    }

    // Pushes, yielding while the ring is full: for items that must not be lost.
    void pushWaiting(const T& item) noexcept {
        while (!tryPush(item)) std::this_thread::yield();
    }

    uint64_t pushed() const noexcept { return enqueue_.value.load(std::memory_order_seq_cst); }

    uint64_t popped() const noexcept { return dequeue_.value.load(std::memory_order_seq_cst); }

    uint64_t depth() const noexcept {
        const uint64_t head = popped();
        return pushed() - head;
    }

    bool empty() const noexcept { return pushed() == popped(); }

  private:
    static constexpr uint64_t MASK = Capacity - 1;

    struct Cell {
        std::atomic<uint64_t> sequence{0};
        T item{};
    };

    struct Position {
        std::atomic<uint64_t> value{0};
        std::array<char, CACHE_LINE_BYTES - sizeof(std::atomic<uint64_t>)> padding{};
    };

    std::array<Cell, Capacity> cells_{};
    Position enqueue_{};
    Position dequeue_{};
};

} // namespace dish_hot
