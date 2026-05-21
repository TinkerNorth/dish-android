// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.store

import com.tinkernorth.dish.architecture.abstracts.AbstractStateSource
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory `(connectionId, slotId) -> controllerType` map for satellite bindings.
 * Source of truth for the UI's per-slot Xbox/PS toggle. The actual push to the
 * native session goes through `SatelliteConnection.setControllerType`; this store
 * just remembers the choice so the toggle is sticky across rebinds.
 *
 * **Pattern:** [AbstractStateSource]`<Map<Pair<String, String>, Int>>`
 * — reactive in-memory store. Bluetooth's type is fixed by the remembered host's
 * HID profile, so BT bindings never appear here; only satellite slots do.
 *
 * Pulled out of `ConnectionHub` so the orchestration (`setSatelliteControllerType`
 * + the imperative side-effect on `SatelliteConnection`) is the only thing the
 * Hub does.
 */
@Singleton
class ControllerTypeStore
    @Inject
    constructor() : AbstractStateSource<Map<Pair<String, String>, Int>>(emptyMap()) {
        /** Current controller type for [connectionId]/[slotId], or null if unset. */
        fun typeFor(
            connectionId: String,
            slotId: String,
        ): Int? = state.value[connectionId to slotId]

        /** Set the controller type. */
        fun setType(
            connectionId: String,
            slotId: String,
            type: Int,
        ) {
            setState { it + ((connectionId to slotId) to type) }
        }

        /**
         * Set the controller type only if absent (e.g. when a new binding lands and
         * a default needs to be stashed for the UI toggle to render).
         */
        fun setTypeIfAbsent(
            connectionId: String,
            slotId: String,
            type: Int,
        ) {
            setState {
                val key = connectionId to slotId
                if (key in it) it else it + (key to type)
            }
        }

        /** Drop the entry for [connectionId]/[slotId]. */
        fun clear(
            connectionId: String,
            slotId: String,
        ) {
            setState {
                val key = connectionId to slotId
                if (key in it) it - key else it
            }
        }

        /**
         * Project a `slotId -> type` map for a single connection's bound slots,
         * filtering only those currently bound (per [boundSlotIds]).
         */
        fun slotTypesFor(
            connectionId: String,
            boundSlotIds: List<String>,
        ): Map<String, Int> {
            val snapshot = state.value
            val out = mutableMapOf<String, Int>()
            for (slotId in boundSlotIds) {
                val type = snapshot[connectionId to slotId] ?: continue
                out[slotId] = type
            }
            return out
        }
    }
