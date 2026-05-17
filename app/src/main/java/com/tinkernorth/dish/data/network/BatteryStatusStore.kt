// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.data.network

import com.tinkernorth.dish.data.network.BatteryValidator.BatterySample
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-scoped cache of the latest battery [BatterySample] per controller
 * slot, purely for the dashboard's per-slot battery indicator.
 *
 * Two process-scoped sources keep it fresh — each a `ProcessLifecycleOwner`
 * observer installed in DishApplication, so both poll on every screen:
 * [VirtualBatterySource] writes `VIRTUAL_SLOT_ID` (the phone battery — the
 * virtual controller), and [PhysicalBatterySource] writes one entry per
 * physical pad keyed by its `InputDevice` id as a string. The keys match
 * [com.tinkernorth.dish.ui.main.ControllerSlot.id] so
 * [com.tinkernorth.dish.ui.main.MainViewModel] can render the level without
 * re-reading the battery.
 *
 * The `MSG_BATTERY` wire send is a separate concern: [PhysicalBatterySource]
 * forwards it for physical pads, and GamepadOverlayActivity forwards the
 * virtual controller's while the touch overlay is the active input device.
 *
 * Process scope means an entry stays warm across the MainActivity →
 * GamepadOverlayActivity hand-off and across an app background/foreground.
 */
@Singleton
class BatteryStatusStore
    @Inject
    constructor() {
        private val _samples = MutableStateFlow<Map<String, BatterySample>>(emptyMap())

        /** Latest battery sample per slot id; empty entries mean "not reported yet". */
        val samples: StateFlow<Map<String, BatterySample>> = _samples.asStateFlow()

        /**
         * Record the latest battery [sample] for [slotId].
         *
         * Uses [MutableStateFlow.update] — a compare-and-set loop — rather than
         * a `value = value + entry` read-modify-write: two process-scoped
         * sources write this store from different threads, and a plain R-M-W
         * would let a concurrent write for another slot clobber this one.
         */
        fun put(
            slotId: String,
            sample: BatterySample,
        ) {
            _samples.update { it + (slotId to sample) }
        }

        /**
         * Drop the cached sample for [slotId] (e.g. when its pad is unplugged).
         * Atomic for the same reason as [put].
         */
        fun clear(slotId: String) {
            _samples.update { if (slotId in it) it - slotId else it }
        }
    }
