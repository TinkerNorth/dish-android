// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.composer

import com.tinkernorth.dish.core.model.CapabilitySet
import com.tinkernorth.dish.core.model.Feature
import com.tinkernorth.dish.repository.TouchpadModeValue

// Who produces a slot's touch data. The phone screen is a FALLBACK, not a sibling: a pad that
// has its own trackpad never gets the overlay, because two producers on one slot would fight
// over the single MSG_TOUCHPAD stream.
enum class TouchpadSource { PHONE, PAD, NONE }

// Reducer: pure touchpad routing decisions shared by the descriptor (wire), the dashboard pill,
// and the overlay launcher, so what the satellite routes and what the UI claims cannot drift.
object TouchpadRouting {
    // A pad's own trackpad is only readable on the USB-direct path (raw reports); the framework
    // paths surface it as a system mouse the app must not hijack. A trackpad-less input falls
    // back to the phone screen; a trackpad-bearing one on a framework path gets neither.
    fun sourceFor(
        isVirtual: Boolean,
        padHasTouchpad: Boolean,
        padCaptured: Boolean,
    ): TouchpadSource =
        when {
            isVirtual -> TouchpadSource.PHONE
            !padHasTouchpad -> TouchpadSource.PHONE
            padCaptured -> TouchpadSource.PAD
            else -> TouchpadSource.NONE
        }

    /**
     * The descriptor's touchpadMode for one slot: the per-satellite pick gated by what the path
     * can actually carry. DS4 pad routing needs a touch source and a type that advertises the
     * mode; mouse routing needs a touch source and a host that grants mouse control. A pick the
     * path cannot carry declares "off" rather than a request the satellite would dead-letter.
     * No transport layer here: only satellite slots declare descriptors at all.
     */
    fun wireMode(
        pick: String?,
        controller: CapabilitySet,
        type: CapabilitySet,
        host: CapabilitySet,
    ): String =
        when {
            pick == TouchpadModeValue.DS4 &&
                Feature.TOUCHPAD in controller &&
                Feature.TOUCHPAD in type -> TouchpadModeValue.DS4
            pick == TouchpadModeValue.MOUSE &&
                Feature.MOUSE in controller &&
                Feature.MOUSE in host -> TouchpadModeValue.MOUSE
            else -> TouchpadModeValue.OFF
        }
}
