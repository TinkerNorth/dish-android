// SPDX-License-Identifier: LGPL-3.0-or-later

#pragma once

#include <cstddef>
#include <cstdint>
#include <memory>
#include <string>

namespace dish_bridge {

// The Kotlin-side connection id of a bridge slot (Bluetooth / Moonlight). Built once when the slot
// binds and held by the slot and by the bridge thread's cache, never copied: the per-event path
// carries the bind's connection number instead. Null for a satellite slot or a bind that named no
// connection.
using BridgeConnectionId = std::shared_ptr<const std::string>;

// The bridge thread's Java string for each bind's connection id, so an upcall passes a string it
// already holds instead of building one per report. Owned by the one thread that makes the
// upcalls, which is also the only thread that makes and releases the refs, so no other thread
// ever needs a JVM to let one go.
//
// Reports name their bind by a connection number; the first report of a bind resolves it to the
// id (SlotTable::bridgeConnectionId) and makes the ref. A bind's ref is released once nothing but
// this cache holds the bind's id (the slot unbound or rebound), when the next new number arrives
// or the thread stops; a report of that bind still queued then resolves to nothing and is dropped.
// With every entry still bound, a new number takes over the entries in turn: correct, only no
// longer free.
//
// Refs: `Ref make(const std::string&)`, which may answer an empty Ref when it cannot make one, and
// `void release(Ref)`. Resolve: `BridgeConnectionId(uint32_t connection)`, null for a bind gone.
template <typename Ref, std::size_t Capacity> class ConnectionRefCache {
  public:
    template <typename Resolve, typename Refs>
    Ref refFor(const uint32_t connection, Resolve&& resolve, Refs& refs) {
        if (connection == 0) return Ref{};
        for (Entry& entry : entries_) {
            if (entry.connection == connection) return entry.ref;
        }
        const BridgeConnectionId id = resolve(connection);
        if (!id) return Ref{};
        return adopt(connection, id, refs);
    }

    template <typename Refs> void releaseAll(Refs& refs) {
        for (Entry& entry : entries_) {
            if (entry.id) clear(entry, refs);
        }
    }

  private:
    struct Entry {
        uint32_t connection = 0;
        BridgeConnectionId id;
        Ref ref{};
    };

    template <typename Refs>
    Ref adopt(const uint32_t connection, const BridgeConnectionId& id, Refs& refs) {
        releaseUnbound(refs);
        Entry& entry = freeOrNextEntry(refs);
        const Ref made = refs.make(*id);
        if (!made) return Ref{};
        entry.connection = connection;
        entry.id = id;
        entry.ref = made;
        return made;
    }

    // An id whose only owner is this cache belongs to a bind that is gone: no slot holds it, and
    // nothing can hand its number out again.
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
        entry.connection = 0;
        entry.id.reset();
        entry.ref = Ref{};
    }

    Entry entries_[Capacity];
    std::size_t nextTaken_ = 0;
};

} // namespace dish_bridge
