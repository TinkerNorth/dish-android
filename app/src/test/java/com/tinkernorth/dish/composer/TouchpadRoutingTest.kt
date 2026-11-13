// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.composer

import com.tinkernorth.dish.core.model.CapabilitySet
import com.tinkernorth.dish.core.model.Feature
import com.tinkernorth.dish.core.model.capabilitySetOf
import com.tinkernorth.dish.repository.TOUCHPAD_MODE_DS4
import com.tinkernorth.dish.repository.TOUCHPAD_MODE_MOUSE
import com.tinkernorth.dish.repository.TOUCHPAD_MODE_OFF
import com.tinkernorth.dish.repository.TrackpadPick
import org.junit.Assert.assertEquals
import org.junit.Test

class TouchpadRoutingTest {
    // ── sourceFor: who produces the slot's touch data ────────────────────────

    @Test
    fun `the virtual slot is always phone-sourced`() {
        assertEquals(
            TouchpadSource.PHONE,
            sourceFor(isVirtual = true, padHasTouchpad = false, padCaptured = false),
        )
        // isVirtual wins even over nonsensical pad flags: there is no pad behind the slot.
        assertEquals(
            TouchpadSource.PHONE,
            sourceFor(isVirtual = true, padHasTouchpad = true, padCaptured = true),
        )
    }

    @Test
    fun `a trackpad-less pad falls back to the phone screen`() {
        assertEquals(
            TouchpadSource.PHONE,
            sourceFor(isVirtual = false, padHasTouchpad = false, padCaptured = false),
        )
        assertEquals(
            TouchpadSource.PHONE,
            sourceFor(isVirtual = false, padHasTouchpad = false, padCaptured = true),
        )
    }

    @Test
    fun `a captured trackpad-bearing pad sources its own touch`() {
        assertEquals(
            TouchpadSource.PAD,
            sourceFor(isVirtual = false, padHasTouchpad = true, padCaptured = true),
        )
    }

    @Test
    fun `a trackpad-bearing pad on an uncapturable path gets neither producer`() {
        assertEquals(
            TouchpadSource.NONE,
            sourceFor(isVirtual = false, padHasTouchpad = true, padCaptured = false),
        )
    }

    // ── wireMode: the descriptor's derived touchpadMode for one slot ─────────

    private val none = CapabilitySet.EMPTY
    private val touch = capabilitySetOf(Feature.TOUCHPAD)
    private val mouse = capabilitySetOf(Feature.MOUSE)
    private val both = capabilitySetOf(Feature.TOUCHPAD, Feature.MOUSE)

    @Test
    fun `the pad surface wins whenever the type carries one`() {
        assertEquals(TOUCHPAD_MODE_DS4, wireMode(false, touch, touch, none))
        assertEquals(TOUCHPAD_MODE_DS4, wireMode(false, both, both, both))
    }

    @Test
    fun `a pad-less route falls through to the host mouse`() {
        assertEquals(TOUCHPAD_MODE_MOUSE, wireMode(false, mouse, none, mouse))
        assertEquals(TOUCHPAD_MODE_MOUSE, wireMode(false, both, none, both))
    }

    @Test
    fun `ds4 needs a touch source and a type that advertises the mode`() {
        assertEquals(TOUCHPAD_MODE_OFF, wireMode(false, none, touch, none))
        assertEquals(TOUCHPAD_MODE_OFF, wireMode(false, touch, none, none))
    }

    @Test
    fun `mouse needs a touch source and a host that grants mouse control`() {
        assertEquals(TOUCHPAD_MODE_OFF, wireMode(false, none, none, mouse))
        assertEquals(TOUCHPAD_MODE_OFF, wireMode(false, mouse, none, none))
    }

    @Test
    fun `a route that can carry neither declares off`() {
        assertEquals(TOUCHPAD_MODE_OFF, wireMode(false, none, none, none))
        assertEquals(TOUCHPAD_MODE_OFF, wireMode(true, none, none, none))
    }

    @Test
    fun `an open mouse surface flips a pad-routable slot to mouse`() {
        assertEquals(TOUCHPAD_MODE_MOUSE, wireMode(true, both, both, both))
        assertEquals(TOUCHPAD_MODE_MOUSE, wireMode(true, both, none, both))
    }

    @Test
    fun `an open mouse surface without a mouse route keeps the pad routing`() {
        assertEquals(TOUCHPAD_MODE_DS4, wireMode(true, touch, touch, none))
        assertEquals(TOUCHPAD_MODE_DS4, wireMode(true, both, both, none))
    }

    @Test
    fun `the 2015 pad defaults to the right stick and the 2026 pad to the surface the type has`() {
        assertEquals(TrackpadPick.STICK, defaultTrackpadPick(SteamTrackpads.RIGHT_STICK_BY_DEFAULT, typeHasPad = true))
        assertEquals(TrackpadPick.STICK, defaultTrackpadPick(SteamTrackpads.RIGHT_STICK_BY_DEFAULT, typeHasPad = false))
        assertEquals(TrackpadPick.PAD, defaultTrackpadPick(SteamTrackpads.TWO_PADS, typeHasPad = true))
        assertEquals(TrackpadPick.OFF, defaultTrackpadPick(SteamTrackpads.TWO_PADS, typeHasPad = false))
        assertEquals(TrackpadPick.OFF, defaultTrackpadPick(SteamTrackpads.NONE, typeHasPad = true))
    }

    @Test
    fun `a stored pick the path can carry wins over the default`() {
        assertEquals(
            TrackpadPick.PAD,
            effectiveTrackpadPick(TrackpadPick.PAD, SteamTrackpads.RIGHT_STICK_BY_DEFAULT, typeHasPad = true, hostMouse = false),
        )
        assertEquals(
            TrackpadPick.MOUSE,
            effectiveTrackpadPick(TrackpadPick.MOUSE, SteamTrackpads.TWO_PADS, typeHasPad = false, hostMouse = true),
        )
        assertEquals(
            TrackpadPick.OFF,
            effectiveTrackpadPick(TrackpadPick.OFF, SteamTrackpads.TWO_PADS, typeHasPad = true, hostMouse = true),
        )
    }

    @Test
    fun `pad needs the type's surface and mouse the host's grant, else the pick is off`() {
        assertEquals(
            TrackpadPick.OFF,
            effectiveTrackpadPick(TrackpadPick.PAD, SteamTrackpads.TWO_PADS, typeHasPad = false, hostMouse = true),
        )
        assertEquals(
            TrackpadPick.OFF,
            effectiveTrackpadPick(TrackpadPick.MOUSE, SteamTrackpads.RIGHT_STICK_BY_DEFAULT, typeHasPad = true, hostMouse = false),
        )
    }

    @Test
    fun `a pick the model does not offer falls back to the model's default`() {
        assertEquals(
            TrackpadPick.PAD,
            effectiveTrackpadPick(TrackpadPick.STICK, SteamTrackpads.TWO_PADS, typeHasPad = true, hostMouse = true),
        )
        assertEquals(
            TrackpadPick.OFF,
            effectiveTrackpadPick(TrackpadPick.PAD, SteamTrackpads.NONE, typeHasPad = true, hostMouse = true),
        )
    }

    @Test
    fun `each pick projects to its descriptor mode`() {
        assertEquals(TOUCHPAD_MODE_OFF, wireModeForTrackpadPick(TrackpadPick.STICK))
        assertEquals(TOUCHPAD_MODE_DS4, wireModeForTrackpadPick(TrackpadPick.PAD))
        assertEquals(TOUCHPAD_MODE_MOUSE, wireModeForTrackpadPick(TrackpadPick.MOUSE))
        assertEquals(TOUCHPAD_MODE_OFF, wireModeForTrackpadPick(TrackpadPick.OFF))
    }

    @Test
    fun `the choices are every pick for the 2015 pad, no stick for the 2026 pad, and none otherwise`() {
        assertEquals(TrackpadPick.entries, trackpadPickChoices(SteamTrackpads.RIGHT_STICK_BY_DEFAULT))
        assertEquals(listOf(TrackpadPick.PAD, TrackpadPick.MOUSE, TrackpadPick.OFF), trackpadPickChoices(SteamTrackpads.TWO_PADS))
        assertEquals(emptyList<TrackpadPick>(), trackpadPickChoices(SteamTrackpads.NONE))
    }

    @Test
    fun `the native codes are the picks and the models in order, and an unknown model is none`() {
        assertEquals(listOf("stick", "pad", "mouse", "off"), TrackpadPick.entries.map { it.wire })
        assertEquals(listOf(0, 1, 2, 3), TrackpadPick.entries.map { it.ordinal })
        assertEquals(SteamTrackpads.NONE, steamTrackpadsOf(0))
        assertEquals(SteamTrackpads.RIGHT_STICK_BY_DEFAULT, steamTrackpadsOf(1))
        assertEquals(SteamTrackpads.TWO_PADS, steamTrackpadsOf(2))
        assertEquals(SteamTrackpads.NONE, steamTrackpadsOf(9))
        assertEquals(TrackpadPick.MOUSE, TrackpadPick.fromWire("mouse"))
        assertEquals(null, TrackpadPick.fromWire("warp-drive"))
    }
}
