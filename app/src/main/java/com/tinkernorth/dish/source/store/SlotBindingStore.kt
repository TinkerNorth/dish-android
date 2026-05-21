// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.store

import com.tinkernorth.dish.architecture.abstracts.AbstractStateSource
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory map of `slotId -> connectionId`. The single source of truth for which
 * slot routes its input where.
 *
 * **Pattern:** [AbstractStateSource]`<Map<String, String>>` — reactive
 * in-memory store. No persistence (bindings are recomputed at process start),
 * no events, no lifecycle hooks. Co-located with
 * [com.tinkernorth.dish.source.store.BatteryStatusStore] and
 * [com.tinkernorth.dish.source.store.ControllerTypeStore] under `source/store/`.
 *
 * Pulled out of `ConnectionHub` so the orchestration (`bind`/`unbind` plus the
 * side-effects on `SatelliteConnection.attachSlot`) is the only thing the Hub
 * does. The Hub mutates this store; consumers (UI, composer) read its [state].
 */
@Singleton
class SlotBindingStore
    @Inject
    constructor() : AbstractStateSource<Map<String, String>>(emptyMap()) {
        /** Map of slotId -> connectionId currently routing that slot. */
        val bindings get() = state

        /** Connection id the slot routes to, or null if unbound. */
        fun connectionFor(slotId: String): String? = state.value[slotId]

        /** The slot ids currently bound to [connectionId]. Order is insertion order. */
        fun slotsFor(connectionId: String): List<String> =
            state.value.entries
                .filter { it.value == connectionId }
                .map { it.key }

        /**
         * Set `slotId -> connectionId`. Replaces any prior binding for the same
         * slot. **No side effects** — callers (typically `ConnectionHub`) are
         * responsible for detaching the prior connection's controller and
         * attaching the new one. Keeping this store side-effect-free lets it be
         * unit-tested without satellite mocks.
         */
        fun bind(
            slotId: String,
            connectionId: String,
        ) {
            setState { it + (slotId to connectionId) }
        }

        /** Remove the binding for [slotId]. No-op if unbound. */
        fun unbind(slotId: String) {
            setState { if (slotId in it) it - slotId else it }
        }

        /** Returns the prior connectionId for [slotId] (or null) and updates atomically. */
        fun replace(
            slotId: String,
            connectionId: String,
        ): String? {
            var prior: String? = null
            setState { current ->
                prior = current[slotId]
                current + (slotId to connectionId)
            }
            return prior
        }
    }
