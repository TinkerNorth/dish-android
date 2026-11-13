// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.composer

import com.tinkernorth.dish.core.model.CapabilitySet
import com.tinkernorth.dish.core.model.Feature
import com.tinkernorth.dish.repository.TOUCHPAD_MODE_DS4
import com.tinkernorth.dish.repository.TOUCHPAD_MODE_MOUSE
import com.tinkernorth.dish.repository.TOUCHPAD_MODE_OFF
import com.tinkernorth.dish.repository.TrackpadPick

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

// Which Steam Controller's trackpads a Direct pad has, in the order ModelTableNative.modelSteamTrackpads
// counts them: none, the 2015 pad (its right pad is the right stick until picked otherwise), the
// 2026 pad (a real right stick and two pads).
enum class SteamTrackpads { NONE, RIGHT_STICK_BY_DEFAULT, TWO_PADS }

fun steamTrackpadsOf(code: Int): SteamTrackpads = SteamTrackpads.entries.getOrElse(code) { SteamTrackpads.NONE }

// The picks a model offers: the 2026 pad has a real right stick, so "stick" is not one of its.
fun trackpadPickChoices(model: SteamTrackpads): List<TrackpadPick> =
    when (model) {
        SteamTrackpads.NONE -> emptyList()
        SteamTrackpads.RIGHT_STICK_BY_DEFAULT -> TrackpadPick.entries
        SteamTrackpads.TWO_PADS -> listOf(TrackpadPick.PAD, TrackpadPick.MOUSE, TrackpadPick.OFF)
    }

fun defaultTrackpadPick(
    model: SteamTrackpads,
    typeHasPad: Boolean,
): TrackpadPick =
    when (model) {
        SteamTrackpads.NONE -> TrackpadPick.OFF
        SteamTrackpads.RIGHT_STICK_BY_DEFAULT -> TrackpadPick.STICK
        SteamTrackpads.TWO_PADS -> if (typeHasPad) TrackpadPick.PAD else TrackpadPick.OFF
    }

/**
 * The pick in force for a slot: the stored one where the model offers it, else the model's
 * default, then held to what the path can carry. A pad surface needs the emulated type to have
 * one, and mouse the host's grant; either falls to off, never to a surface the satellite would
 * dead-letter. One pick covers both pads, so `pad` on the 2015 model gives up its right stick.
 */
fun effectiveTrackpadPick(
    stored: TrackpadPick?,
    model: SteamTrackpads,
    typeHasPad: Boolean,
    hostMouse: Boolean,
): TrackpadPick {
    val wanted = stored?.takeIf { it in trackpadPickChoices(model) } ?: defaultTrackpadPick(model, typeHasPad)
    return when (wanted) {
        TrackpadPick.STICK -> TrackpadPick.STICK
        TrackpadPick.PAD -> if (typeHasPad) TrackpadPick.PAD else TrackpadPick.OFF
        TrackpadPick.MOUSE -> if (hostMouse) TrackpadPick.MOUSE else TrackpadPick.OFF
        TrackpadPick.OFF -> TrackpadPick.OFF
    }
}

// The descriptor's touchpadMode for a pick: the pads on the surface are the DS4 pad mode, the
// pads as a mouse the mouse mode, and the stick and off carry no surface.
fun wireModeForTrackpadPick(pick: TrackpadPick): String =
    when (pick) {
        TrackpadPick.STICK, TrackpadPick.OFF -> TOUCHPAD_MODE_OFF
        TrackpadPick.PAD -> TOUCHPAD_MODE_DS4
        TrackpadPick.MOUSE -> TOUCHPAD_MODE_MOUSE
    }
