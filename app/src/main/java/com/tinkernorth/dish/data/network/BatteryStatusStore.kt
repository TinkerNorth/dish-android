// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.data.network

import com.tinkernorth.dish.data.network.BatteryCoalescer.BatterySample
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-scoped cache of the latest battery [BatterySample] per controller
 * slot, purely for the dashboard's per-slot battery indicator.
 *
 * The actual `MSG_BATTERY` wire send is owned by the two battery sources
 * ([PhoneBatterySource] for the touch overlay's virtual controller,
 * [PhysicalBatterySource] for physical pads); each also calls [put] here with
 * the same sample so [com.tinkernorth.dish.ui.main.MainViewModel] can render
 * it without re-reading the battery. Keyed by slot id — `VIRTUAL_SLOT_ID` for
 * the virtual controller, the `InputDevice` id (as a string) for a physical
 * pad — matching [com.tinkernorth.dish.ui.main.ControllerSlot.id].
 *
 * Lives at process scope so it survives the MainActivity →
 * GamepadOverlayActivity hand-off: the overlay's [PhoneBatterySource] keeps
 * writing here while the overlay is foreground, and the value is still warm
 * when the dashboard comes back.
 */
@Singleton
class BatteryStatusStore
    @Inject
    constructor() {
        private val _samples = MutableStateFlow<Map<String, BatterySample>>(emptyMap())

        /** Latest battery sample per slot id; empty entries mean "not reported yet". */
        val samples: StateFlow<Map<String, BatterySample>> = _samples.asStateFlow()

        /** Record the latest battery [sample] for [slotId]. */
        fun put(
            slotId: String,
            sample: BatterySample,
        ) {
            _samples.value = _samples.value + (slotId to sample)
        }

        /** Drop the cached sample for [slotId] (e.g. when its pad is unplugged). */
        fun clear(slotId: String) {
            if (slotId !in _samples.value) return
            _samples.value = _samples.value - slotId
        }
    }
