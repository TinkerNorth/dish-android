// SPDX-License-Identifier: LGPL-3.0-or-later

#pragma once

#include <cstddef>
#include <memory>
#include <string>

namespace dish_bridge {

// The Kotlin-side connection id of a bridge slot (Bluetooth / Moonlight). Built once when the slot
// binds and shared, never copied: every report the slot queues points at the same string, so the
// per-event path bumps a reference count where a std::string copy would allocate (an id is longer
// than the small-string buffer). Null for a satellite slot or a bind that named no connection.
using BridgeConnectionId = std::shared_ptr<const std::string>;

// The bridge thread's Java string for each bind's connection id, so an upcall passes a string it
// already holds instead of building one per report. Owned by the one thread that makes the
// upcalls, which is also the only thread that makes and releases the refs, so no other thread
// ever needs a JVM to let one go.
//
// A bind's ref is made on its first report. It is released once nothing but this cache holds the
// bind's id (the slot unbound or rebound, and every report it queued dispatched or dropped), when
// the next new id arrives or the thread stops. With every entry still bound, a new id takes over
// the entries in turn: correct, only no longer free.
//
// Refs: `Ref make(const std::string&)`, which may answer an empty Ref when it cannot make one, and
// `void release(Ref)`.
template <typename Ref, std::size_t Capacity> class ConnectionRefCache {
  public:
    template <typename Refs> Ref refFor(const BridgeConnectionId& id, Refs& refs) {
        if (!id) return Ref{};
        for (Entry& entry : entries_) {
            if (entry.id == id) return entry.ref;
        }
        return adopt(id, refs);
    }

    template <typename Refs> void releaseAll(Refs& refs) {
        for (Entry& entry : entries_) {
            if (entry.id) clear(entry, refs);
        }
    }

  private:
    struct Entry {
        BridgeConnectionId id;
        Ref ref{};
    };

    template <typename Refs> Ref adopt(const BridgeConnectionId& id, Refs& refs) {
        releaseUnbound(refs);
        Entry& entry = freeOrNextEntry(refs);
        const Ref made = refs.make(*id);
        if (!made) return Ref{};
        entry.id = id;
        entry.ref = made;
        return made;
    }

    // An id whose only owner is this cache belongs to a bind that is gone: no slot holds it and no
    // queued report does, and nothing can hand it out again.
    template <typename Refs> void releaseUnbound(Refs& refs) {
        for (Entry& entry : entries_) {
            const bool isOnlyOwner = entry.id && entry.id.use_count() == 1;
            if (isOnlyOwner) clear(entry, refs);
        }
    }

    template <typename Refs> Entry& freeOrNextEntry(Refs& refs) {
        for (Entry& entry : entries_) {
            if (!entry.id) return entry;
        }
        Entry& taken = entries_[nextTaken_];
        nextTaken_ = (nextTaken_ + 1) % Capacity;
        clear(taken, refs);
        return taken;
    }

    template <typename Refs> static void clear(Entry& entry, Refs& refs) {
        refs.release(entry.ref);
        entry.id.reset();
        entry.ref = Ref{};
    }

    Entry entries_[Capacity];
    std::size_t nextTaken_ = 0;
};

} // namespace dish_bridge
