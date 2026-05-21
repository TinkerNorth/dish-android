// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.store

import com.tinkernorth.dish.architecture.abstracts.AbstractStateSource
import com.tinkernorth.dish.source.sensor.BatteryValidator.BatterySample
import com.tinkernorth.dish.source.sensor.PhysicalBatterySource
import com.tinkernorth.dish.source.sensor.VirtualBatterySource
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-scoped cache of the latest battery [BatterySample] per controller slot,
 * purely for the dashboard's per-slot battery indicator.
 *
 * **Pattern:** [AbstractStateSource]`<Map<String, BatterySample>>` —
 * reactive in-memory store. The state is the per-slot map; there are no events
 * and no lifecycle hooks (the source is written-to by other sources and
 * read-from by the UI; nothing about its own lifetime is interesting). The
 * Nothing event-type declares the absence honestly.
 *
 * Two process-scoped sources keep it fresh — each a `ProcessLifecycleOwner`
 * observer installed in DishApplication, so both poll on every screen:
 * [VirtualBatterySource] writes `VIRTUAL_SLOT_ID` (the phone battery — the
 * virtual controller), and [PhysicalBatterySource] writes one entry per physical
 * pad keyed by its `InputDevice` id as a string. The keys match
 * [com.tinkernorth.dish.ui.main.ControllerSlot.id] so
 * [com.tinkernorth.dish.ui.main.MainViewModel] can render the level without
 * re-reading the battery.
 *
 * The `MSG_BATTERY` wire send is a separate concern: [PhysicalBatterySource]
 * forwards it for physical pads, and GamepadOverlayActivity forwards the virtual
 * controller's while the touch overlay is the active input device.
 *
 * Process scope means an entry stays warm across the MainActivity →
 * GamepadOverlayActivity hand-off and across an app background/foreground.
 */
@Singleton
class BatteryStatusStore
    @Inject
    constructor() : AbstractStateSource<Map<String, BatterySample>>(emptyMap()) {
        /**
         * Latest battery sample per slot id; empty entries mean "not reported yet".
         * Backwards-compat name for the state flow — callers reading
         * `batteryStatusStore.samples` keep working.
         */
        val samples get() = state

        /**
         * Record the latest battery [sample] for [slotId]. Uses [setState] (a
         * compare-and-set loop) so two process-scoped sources writing from
         * different threads cannot clobber each other's entries.
         */
        fun put(
            slotId: String,
            sample: BatterySample,
        ) {
            setState { it + (slotId to sample) }
        }

        /**
         * Drop the cached sample for [slotId] (e.g. when its pad is unplugged).
         * Atomic for the same reason as [put].
         */
        fun clear(slotId: String) {
            setState { if (slotId in it) it - slotId else it }
        }
    }
