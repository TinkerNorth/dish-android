// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.composer

import com.tinkernorth.dish.core.model.CapabilitySet
import com.tinkernorth.dish.core.model.Feature
import com.tinkernorth.dish.repository.TOUCHPAD_MODE_DS4
import com.tinkernorth.dish.repository.TOUCHPAD_MODE_MOUSE
import com.tinkernorth.dish.repository.TOUCHPAD_MODE_OFF

// Reducer: pure touchpad routing decisions shared by the descriptor (wire), the dashboard pill,
// and the overlay launcher, so what the satellite routes and what the UI claims cannot drift.
// A pad's own trackpad is readable where the app can get at the fingers: on the USB-direct
// path from the raw report, and on a framework path through pointer capture of the surface
// Android exposed (padCaptured covers both). A trackpad-less input falls back to the phone
// screen; a trackpad-bearing one whose surface the app cannot reach (no capture: pre-26, or
// a driver that exposed no surface) gets neither, because the surface is then a system
// mouse the app must not fight over with a second producer.
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
 * The descriptor's touchpadMode for one slot, derived from the path rather than picked:
 * whatever the route can carry is simply on. The emulated pad's own surface wins when the
 * type has one; otherwise the touch source drives the host mouse where the host grants it.
 * An open mouse overlay flips a DS4-routable slot to mouse for as long as it is up, since
 * the two routings share one MSG_TOUCHPAD stream. A path that can carry neither declares
 * "off" rather than a request the satellite would dead-letter. No transport layer here:
 * only satellite slots declare descriptors at all.
 */
fun wireMode(
    mouseSurfaceOpen: Boolean,
    controller: CapabilitySet,
    type: CapabilitySet,
    host: CapabilitySet,
): String {
    val padRoute = Feature.TOUCHPAD in controller && Feature.TOUCHPAD in type
    val mouseRoute = Feature.MOUSE in controller && Feature.MOUSE in host
    return when {
        mouseSurfaceOpen && mouseRoute -> TOUCHPAD_MODE_MOUSE
        padRoute -> TOUCHPAD_MODE_DS4
        mouseRoute -> TOUCHPAD_MODE_MOUSE
        else -> TOUCHPAD_MODE_OFF
    }
}
